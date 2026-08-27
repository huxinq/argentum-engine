package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.persistence.GameReplayPlayerRow
import com.wingedsheep.gameserver.persistence.GameReplayRepository
import com.wingedsheep.gameserver.persistence.GameReplayRow
import com.wingedsheep.gameserver.persistence.GameReplayChunkRepository
import com.wingedsheep.gameserver.persistence.GameReplayChunkRow
import com.wingedsheep.engine.replay.CanonicalReplayJson
import com.wingedsheep.engine.replay.CanonicalReplayRecord
import com.wingedsheep.engine.replay.CanonicalReplayReconstructor
import com.wingedsheep.engine.replay.CanonicalReplayTerminal
import com.wingedsheep.engine.replay.CanonicalReplayTransition
import com.wingedsheep.engine.replay.ReplaySystemMutationKind
import com.wingedsheep.engine.state.YieldKind
import com.wingedsheep.sdk.scripting.AbilityIdentity
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Collections
import org.springframework.transaction.annotation.Transactional
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Whether a stored record is still being written to, or is the final record of a finished game. */
enum class ReplayStatus { IN_PROGRESS, FINISHED }

/**
 * Everything persisted for one recorded game: its canonical stream or legacy recipe, optional
 * archived viewer stream, and the bookkeeping that lets an interrupted recording resume safely.
 */
data class StoredReplay(
    val replay: CompactReplay,
    val status: ReplayStatus,
    /** gzip+base64 [ReplayPresentation] body. Null while in progress, and for over-sized games. */
    val presentation: String? = null,
    /**
     * [ReplayFingerprint] of the live position at the moment this record was written.
     *
     * A game in progress is flushed periodically, so a crash loses the actions played since the last
     * flush. Appending later actions onto that short prefix would silently produce a record of a
     * game nobody played, so on restore we compare this against the recovered live state: equal
     * means nothing was lost and recording continues; different means we stop and keep the honest
     * shorter replay. See [com.wingedsheep.gameserver.session.GameSession.restoreReplayRecording].
     */
    val resumeFingerprint: String? = null,
)

/** Listing projection, read from the metadata columns without decoding the payload. */
data class ReplaySummary(
    val gameId: String,
    val playerNames: List<String>,
    val startedAt: String,
    val endedAt: String,
    val winnerName: String?,
    val frameCount: Int,
    val tournamentName: String? = null,
    val tournamentRound: Int? = null,
    val strategyEvidenceEligible: Boolean = true,
    val policyFaultIncidentId: String? = null,
)

/**
 * The one home for replays.
 *
 * Replays used to live in three places at once — this table, an in-memory ring buffer of the last
 * 100 games, and (for games still in progress) the Redis session blob — which meant three
 * lifetimes, three eviction rules, and three different answers to "is this game replayable". There
 * is now a single store: finished games and in-flight recordings alike are rows here, written by
 * [ReplayService] and nobody else.
 *
 * Which implementation is wired depends on whether accounts (and therefore a database) are enabled,
 * so the game-over path stays decoupled from the persistence layer — exactly like
 * [com.wingedsheep.gameserver.stats.MatchResultSink].
 */
interface ReplayStore {
    fun save(record: StoredReplay)
    fun find(gameId: String): StoredReplay?
    fun findRecentForPlayer(playerId: String, limit: Int): List<ReplaySummary>

    /** In-progress records, for resuming recordings after a restart. */
    fun findInProgress(): List<StoredReplay>
}

/**
 * Default: no database configured (accounts disabled — local dev, e2e, self-hosted casual play).
 *
 * Replays still have exactly one home, it just doesn't survive a restart. Bounded so a long-running
 * server without a database can't accumulate them without limit.
 */
@Component
@ConditionalOnProperty(name = ["accounts.enabled"], havingValue = "false", matchIfMissing = true)
class InMemoryReplayStore : ReplayStore {

    private val records = Collections.synchronizedMap(
        object : LinkedHashMap<String, StoredReplay>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, StoredReplay>) = size > MAX_RECORDS
        }
    )

    override fun save(record: StoredReplay) {
        records[record.replay.gameId] = record
    }

    override fun find(gameId: String): StoredReplay? = records[gameId]

    override fun findRecentForPlayer(playerId: String, limit: Int): List<ReplaySummary> =
        synchronized(records) {
            records.values
                .filter { it.status == ReplayStatus.FINISHED }
                .filter { record -> record.replay.players.any { it.playerId == playerId } }
                .sortedByDescending { it.replay.endedAt }
                .take(limit)
                .map { it.replay.toSummary() }
        }

    override fun findInProgress(): List<StoredReplay> =
        synchronized(records) { records.values.filter { it.status == ReplayStatus.IN_PROGRESS } }

    private companion object {
        const val MAX_RECORDS = 200
    }
}

/** Accounts enabled: replays are rows in Postgres, upserted by game id. */
@Component
@ConditionalOnProperty(name = ["accounts.enabled"], havingValue = "true")
class JdbcReplayStore(
    private val replays: GameReplayRepository,
    private val chunks: GameReplayChunkRepository,
) : ReplayStore {
    private val logger = LoggerFactory.getLogger(JdbcReplayStore::class.java)

    /**
     * Upsert by game id, keeping immutable pins, setup, and canonical prefixes out of the hot path.
     *
     * A game in progress is flushed every few seconds. Canonical v3 appends only its new chunk suffix
     * and uses a metadata-only parent update; legacy v1/v2 uses
     * [GameReplayRepository.updateRecording]. The full
     * aggregate save — which also (re)writes the pins, the seat children and the finished-game
     * metadata — runs on the first flush and again at game over, twice per game rather than once per
     * five seconds.
     */
    @Transactional
    override fun save(record: StoredReplay) {
        val replay = record.replay
        val existing = replays.findByGameId(replay.gameId)
        // A checkpoint sweep can overlap game-over. FINISHED is terminal even when this invocation
        // read it after ReplayService's own preflight check.
        if (existing?.status == ReplayStatus.FINISHED.name && record.status == ReplayStatus.IN_PROGRESS) return
        val canonical = replay.canonicalRecords
        val canonicalWrite = canonical.isNotEmpty() || (existing?.canonicalRecordCount ?: 0) > 0
        // Pins and canonical records live in append/write-once columns, so neither rides in the
        // periodically rewritten compatibility envelope. For v3 the old action/checkpoint shadows
        // are omitted as well: the canonical chunks are the sole authoritative transition stream.
        val envelope = replay.copy(
            pinnedCards = emptyList(),
            actions = replay.actions.takeUnless { canonicalWrite } ?: emptyList(),
            yields = replay.yields.takeUnless { canonicalWrite } ?: emptyList(),
            checkpoints = replay.checkpoints.takeUnless { canonicalWrite } ?: emptyList(),
            truncated = replay.truncated && !canonicalWrite,
            canonicalRecords = emptyList(),
        )
        val data = ReplayCodec.encode(envelope)
        val endedAt = parseInstant(replay.endedAt) ?: Instant.now()

        if (existing != null && existing.status == ReplayStatus.IN_PROGRESS.name && record.status == ReplayStatus.IN_PROGRESS) {
            if (canonicalWrite) {
                val updated = replays.updateCanonicalRecording(
                    gameId = replay.gameId,
                    status = record.status.name,
                    resumeFingerprint = record.resumeFingerprint,
                    frameCount = replay.frameCount,
                    canonicalRecordCount = canonical.size,
                    expectedCanonicalRecordCount = existing.canonicalRecordCount,
                    endedAt = endedAt,
                    engineVersion = replay.engineVersion,
                )
                if (updated == 0) {
                    // A finished writer won the row lock, or another flush advanced the optimistic
                    // count. Do not insert chunks against metadata this transaction did not claim.
                    val current = replays.findByGameId(replay.gameId)
                    if (current?.status == ReplayStatus.FINISHED.name) return
                    error("Concurrent canonical replay checkpoint for ${replay.gameId}; retry on the next sweep")
                }
                appendCanonical(
                    replayId = requireNotNull(existing.id) { "Stored replay ${replay.gameId} has no database id" },
                    gameId = replay.gameId,
                    persistedCount = existing.canonicalRecordCount,
                    records = canonical,
                )
            } else {
                replays.updateRecording(
                    gameId = replay.gameId,
                    data = data,
                    status = record.status.name,
                    resumeFingerprint = record.resumeFingerprint,
                    frameCount = replay.frameCount,
                    endedAt = endedAt,
                    engineVersion = replay.engineVersion,
                )
            }
            logger.debug(
                "Flushed in-progress replay {} ({} canonical records, immutable payloads untouched)",
                replay.gameId, canonical.size,
            )
            return
        }

        val persisted = replays.save(
            GameReplayRow(
                id = existing?.id,
                gameId = replay.gameId,
                format = replay.setup.format::class.simpleName,
                winnerName = replay.winnerName,
                tournamentName = replay.tournamentName,
                tournamentRound = replay.tournamentRound,
                startedAt = parseInstant(replay.startedAt),
                endedAt = endedAt,
                frameCount = replay.frameCount,
                playerNames = replay.players.joinToString(", ") { it.name },
                status = record.status.name,
                engineVersion = replay.engineVersion,
                resumeFingerprint = record.resumeFingerprint,
                canonicalRecordCount = if (canonicalWrite) canonical.size else 0,
                data = data,
                // Never drop pins already stored: a record can be re-saved by a path that didn't
                // recompute them (finalizePartial), and losing them costs the replay its durability.
                pinnedCards = ReplayCodec.encodePins(replay.pinnedCards) ?: existing?.pinnedCards,
                presentation = record.presentation?.let { ReplayCodec.encodeText(it) }
                    ?: existing?.presentation,
                players = replay.players.mapIndexed { seat, player ->
                    GameReplayPlayerRow(seat = seat, playerId = player.playerId, playerName = player.name)
                }.toSet(),
            )
        )
        if (canonicalWrite) {
            appendCanonical(
                replayId = requireNotNull(persisted.id) { "Persisted replay ${replay.gameId} has no database id" },
                gameId = replay.gameId,
                persistedCount = existing?.canonicalRecordCount ?: 0,
                records = canonical,
            )
        }
        logger.debug(
            "Persisted {} replay {} ({} canonical records)", record.status, replay.gameId, canonical.size,
        )
    }

    override fun find(gameId: String): StoredReplay? = replays.findByGameId(gameId)?.toStored()

    override fun findRecentForPlayer(playerId: String, limit: Int): List<ReplaySummary> =
        replays.findRecentForPlayer(playerId, limit).map { row ->
            val replay = requireNotNull(row.toStored()).replay
            ReplaySummary(
                gameId = row.gameId,
                playerNames = row.playerNames.split(", ").filter { it.isNotBlank() },
                startedAt = row.startedAt?.toString() ?: "",
                endedAt = row.endedAt.toString(),
                winnerName = row.winnerName,
                frameCount = row.frameCount,
                tournamentName = row.tournamentName,
                tournamentRound = row.tournamentRound,
                strategyEvidenceEligible = replay.strategyEvidenceEligible,
                policyFaultIncidentId = replay.policyFaults.lastOrNull { it.recovery == "CONCEDE" }?.incidentId,
            )
        }

    override fun findInProgress(): List<StoredReplay> =
        replays.findByStatus(ReplayStatus.IN_PROGRESS.name).mapNotNull { it.toStored() }

    private fun GameReplayRow.toStored(): StoredReplay? {
        val decoded = runCatching { ReplayCodec.decode(data) }
            .onFailure { logger.error("Replay {} failed to decode: {}", gameId, it.message) }
            .getOrNull() ?: return null
        // Pins come from their own column post-V11. A pre-V11 row has none there and still carries
        // them inside the blob, so only overwrite when the column actually holds something.
        val pins = runCatching { ReplayCodec.decodePins(pinnedCards) }
            .onFailure { logger.error("Replay {} has unreadable pins: {}", gameId, it.message) }
            .getOrDefault(emptyList())
        val chunkRecords = runCatching { loadCanonicalRecords(this) }
            .onFailure { logger.error("Replay {} has unreadable canonical chunks: {}", gameId, it.message) }
            .getOrNull() ?: return null
        val canonical = runCatching {
            require(
                decoded.canonicalRecords.isEmpty() || chunkRecords.isEmpty() ||
                    decoded.canonicalRecords == chunkRecords
            ) { "Embedded and chunked canonical replay streams disagree for $gameId" }
            chunkRecords.ifEmpty { decoded.canonicalRecords }.also { records ->
                if (records.isNotEmpty()) {
                    if (records.last() is CanonicalReplayTerminal) {
                        CanonicalReplayReconstructor.reconstruct(records)
                    } else {
                        CanonicalReplayReconstructor.reconstructPrefix(records)
                    }
                }
            }
        }.onFailure {
            logger.error("Replay {} has an invalid canonical stream: {}", gameId, it.message)
        }.getOrNull() ?: return null
        val shadow = canonicalLegacyShadow(canonical)
        return StoredReplay(
            replay = decoded.copy(
                pinnedCards = pins.ifEmpty { decoded.pinnedCards },
                actions = decoded.actions.ifEmpty { shadow.actions },
                yields = decoded.yields.ifEmpty { shadow.yields },
                canonicalRecords = canonical,
            ),
            status = runCatching { ReplayStatus.valueOf(status) }.getOrDefault(ReplayStatus.FINISHED),
            presentation = presentation?.let { runCatching { ReplayCodec.decodeText(it) }.getOrNull() },
            resumeFingerprint = resumeFingerprint,
        )
    }

    private fun parseInstant(value: String?): Instant? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun appendCanonical(
        replayId: Long,
        gameId: String,
        persistedCount: Int,
        records: List<CanonicalReplayRecord>,
    ) {
        require(persistedCount <= records.size) {
            "Canonical replay $gameId regressed from $persistedCount to ${records.size} records"
        }
        if (persistedCount > 0) {
            val lastChunk = requireNotNull(chunks.findFirstByReplayIdOrderByFirstRecordDesc(replayId)) {
                "Canonical replay $gameId says it has $persistedCount records but has no chunks"
            }
            val persisted = ReplayCodec.decodeCanonicalRecords(lastChunk.data)
            require(lastChunk.recordCount == persisted.size)
            require(lastChunk.firstRecord + persisted.size == persistedCount)
            require(persisted.last().recordDigest == records[persistedCount - 1].recordDigest) {
                "Canonical replay $gameId changed its persisted prefix"
            }
        }
        records.drop(persistedCount).chunked(CANONICAL_CHUNK_RECORDS).forEachIndexed { index, batch ->
            val first = persistedCount + index * CANONICAL_CHUNK_RECORDS
            chunks.save(
                GameReplayChunkRow(
                    replayId = replayId,
                    firstRecord = first,
                    recordCount = batch.size,
                    data = ReplayCodec.encodeCanonicalRecords(batch),
                )
            )
        }
    }

    private fun loadCanonicalRecords(row: GameReplayRow): List<CanonicalReplayRecord> {
        val replayId = row.id ?: return emptyList()
        val result = ArrayList<CanonicalReplayRecord>(row.canonicalRecordCount)
        chunks.findByReplayIdOrderByFirstRecordAsc(replayId).forEach { chunk ->
            require(chunk.firstRecord == result.size) {
                "Canonical replay ${row.gameId} has a gap at record ${result.size}"
            }
            val decoded = ReplayCodec.decodeCanonicalRecords(chunk.data)
            require(decoded.size == chunk.recordCount)
            result += decoded
        }
        require(result.size == row.canonicalRecordCount) {
            "Canonical replay ${row.gameId} expected ${row.canonicalRecordCount} records, found ${result.size}"
        }
        return result
    }

    private companion object {
        const val CANONICAL_CHUNK_RECORDS = 128
    }
}

private data class CanonicalLegacyShadow(
    val actions: List<com.wingedsheep.engine.core.GameAction>,
    val yields: List<ReplayYieldEntry>,
)

/** Rebuild transient v2-shaped runtime inputs from the authoritative canonical history after restart. */
private fun canonicalLegacyShadow(records: List<CanonicalReplayRecord>): CanonicalLegacyShadow {
    val actions = mutableListOf<com.wingedsheep.engine.core.GameAction>()
    val yields = mutableListOf<ReplayYieldEntry>()
    records.filterIsInstance<CanonicalReplayTransition>().forEach { transition ->
        if (!transition.accepted) return@forEach
        transition.action?.let(actions::add)
        val mutation = transition.systemMutation ?: return@forEach
        when (mutation.kind) {
            ReplaySystemMutationKind.SET_YIELD -> {
                val actor = mutation.actorId ?: return@forEach
                val identity = mutation.detail["identity"]?.let {
                    runCatching { CanonicalReplayJson.decodeFromJsonElement(AbilityIdentity.serializer(), it) }
                        .getOrNull()
                } ?: return@forEach
                val kind = (mutation.detail["kind"] as? JsonPrimitive)?.content?.let {
                    runCatching { YieldKind.valueOf(it) }.getOrNull()
                } ?: return@forEach
                yields += ReplayYieldEntry(actions.size, actor, ReplayYieldOp.SET, identity, kind)
            }
            ReplaySystemMutationKind.CLEAR_YIELD -> {
                val actor = mutation.actorId ?: return@forEach
                val identity = mutation.detail["identity"]?.let {
                    runCatching { CanonicalReplayJson.decodeFromJsonElement(AbilityIdentity.serializer(), it) }
                        .getOrNull()
                } ?: return@forEach
                yields += ReplayYieldEntry(actions.size, actor, ReplayYieldOp.CLEAR_ABILITY, identity)
            }
            ReplaySystemMutationKind.CLEAR_ALL_YIELDS -> mutation.actorId?.let { actor ->
                yields += ReplayYieldEntry(actions.size, actor, ReplayYieldOp.CLEAR_ALL)
            }
            ReplaySystemMutationKind.UNDO -> {
                val target = (mutation.detail["targetActionCount"] as? JsonPrimitive)?.intOrNull
                    ?.takeIf { it in 0..actions.size }
                    ?: return@forEach
                while (actions.size > target) actions.removeAt(actions.lastIndex)
                yields.removeIf { it.afterActionCount > target }
            }
            else -> Unit
        }
    }
    return CanonicalLegacyShadow(actions, yields)
}

/** Listing projection for an in-memory record, which has no metadata columns to read instead. */
private fun CompactReplay.toSummary() = ReplaySummary(
    gameId = gameId,
    playerNames = players.map { it.name },
    startedAt = startedAt,
    endedAt = endedAt,
    winnerName = winnerName,
    frameCount = frameCount,
    tournamentName = tournamentName,
    tournamentRound = tournamentRound,
    strategyEvidenceEligible = strategyEvidenceEligible,
    policyFaultIncidentId = policyFaults.lastOrNull { it.recovery == "CONCEDE" }?.incidentId,
)
