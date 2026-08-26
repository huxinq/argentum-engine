package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.replay.CanonicalReplayRecorder
import com.wingedsheep.engine.replay.CanonicalReplayTerminal
import com.wingedsheep.engine.replay.ReplayCompletionStatus
import com.wingedsheep.engine.replay.ReplayIncompleteReason
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What a caller needs to serve one replay to the viewer: the `{initialSnapshot, deltas}` body plus
 * how much to trust it.
 */
data class ReplayViewerPayload(
    /** The composed JSON object — `{"initialSnapshot":…,"deltas":[…]}` — ready to serve as-is. */
    val body: String,
    val frameCount: Int,
    val fidelity: ReplayFidelity,
    /** Set when [fidelity] is not [ReplayFidelity.EXACT] and the viewer should say so. */
    val degradedReason: String? = null,
    /**
     * Whether this game's exact position can still be rebuilt — i.e. whether "share frame as
     * scenario" will work. False when we are serving the archive because re-simulation diverged.
     */
    val stateReproducible: Boolean = true,
) {
    /**
     * [body]'s fields without the enclosing braces, for endpoints that splice the frames in
     * alongside keys of their own (the public one prepends `metadata`).
     *
     * Splicing beats decode-and-re-encode — the frames are hundreds of KB of already-serialized
     * JSON, freshly rendered or read straight out of the archive. But "body is a brace-wrapped
     * object" is then an assumption, and one an archive row written by some other build could
     * violate, so it is asserted here once instead of being taken positionally at each call site.
     * Corrupt JSON that a client fails to parse is a much worse symptom than a loud failure.
     */
    fun bodyFields(): String {
        val trimmed = body.trim()
        require(trimmed.length >= 2 && trimmed.startsWith('{') && trimmed.endsWith('}')) {
            "Replay viewer body must be a JSON object, got: ${trimmed.take(40)}"
        }
        return trimmed.substring(1, trimmed.length - 1)
    }
}

/**
 * The single entry point for reading and writing replays.
 *
 * Writing goes to exactly one place ([ReplayStore]). Reading resolves a game id to its
 * [StoredReplay] and then decides *how* to answer, which is the whole point of the two-payload
 * format:
 *
 * - Reconstruct canonical v3 directly from its recorded states, or re-simulate a legacy input log.
 *   If it comes back faithful, serve that through today's view code and support "share frame as
 *   scenario", because we hold a real [GameState].
 * - If a legacy replay diverged, serve the frames
 *   that were archived at record time instead, flagged as degraded. The player still watches the
 *   game they played; they just can't fork a scenario out of it.
 * - Only if there is no archive either do we serve the truncated re-simulation, which is the old
 *   behaviour and the reason this class exists.
 */
@Component
class ReplayService(
    private val store: ReplayStore,
    private val reconstructor: ReplayReconstructor,
    private val presentation: ReplayPresentation,
) {
    private val logger = LoggerFactory.getLogger(ReplayService::class.java)

    /**
     * Materializes legacy archived frame streams off the game-over path. Folding a long game and
     * building a spectator snapshot per action takes seconds; game over runs on a WebSocket handler
     * thread and has already done its DB writes and lobby callbacks by the time we get here.
     * Single-threaded so a tournament finishing eight legacy matches at once queues rather than
     * stampedes.
     */
    private val archiver = Executors.newSingleThreadExecutor { r ->
        Thread(r, "replay-archiver").apply { isDaemon = true }
    }

    /**
     * Record a finished game.
     *
     * The canonical stream (or legacy input log) is stored synchronously—it is the record. Canonical
     * v3 already contains every authoritative state and survives engine changes by direct decoding,
     * so storing a second spectator-only frame stream would add cost without fidelity. Legacy
     * records can still archive that fallback asynchronously (see [archiver]).
     *
     * [archive] requests this fallback for a legacy record and is ignored for canonical v3. It is
     * false for AI-only games (the LLM tournament).
     */
    fun save(replay: CompactReplay, archive: Boolean) {
        val record = StoredReplay(replay = replay, status = ReplayStatus.FINISHED)
        store.save(record)
        if (!archive || replay.canonicalRecords.isNotEmpty()) return

        archiver.execute {
            runCatching {
                val reconstructed = reconstructor.reconstruct(replay)
                if (!reconstructed.isComplete) {
                    // The build that just played the game can't reproduce it — a live engine bug,
                    // not version drift. Loud, because this was our chance to archive the frames.
                    logger.error(
                        "Replay {} does not re-simulate on the build that recorded it: {}",
                        replay.gameId, reconstructed.divergenceReason,
                    )
                }
                presentation.materialize(reconstructed)?.let {
                    store.save(record.copy(presentation = it))
                }
            }.onFailure { logger.error("Could not archive replay {}: {}", replay.gameId, it.message) }
        }
    }

    @PreDestroy
    fun awaitArchiving() {
        archiver.shutdown()
        // Long enough to finish an in-flight fold, short enough not to hold up a deploy.
        runCatching { archiver.awaitTermination(20, TimeUnit.SECONDS) }
    }

    /**
     * Persist an in-flight recording so a restart doesn't lose it. See [ReplayCheckpointFlusher].
     *
     * `FINISHED` is terminal. A game over stores the final record while its session is still live and
     * still carries a setup, so a flush sweep that overlaps the game-over path would otherwise write
     * an `IN_PROGRESS` record straight over it — dropping the archive, the winner and `endedAt`, and
     * hiding the game from its own players' history for good (nothing ever revisits a record no
     * session is being flushed for). Refusing the downgrade here makes the ordering irrelevant.
     */
    fun saveInProgress(replay: CompactReplay, resumeFingerprint: String?) {
        if (store.find(replay.gameId)?.status == ReplayStatus.FINISHED) return
        store.save(
            StoredReplay(
                replay = replay,
                status = ReplayStatus.IN_PROGRESS,
                resumeFingerprint = resumeFingerprint,
            )
        )
    }

    /**
     * Close out an in-progress record that will never get a proper ending — the game was abandoned,
     * or its recording was stopped because a restart left the stored log behind the live state.
     *
     * The partial recording is a real replay of however far it got, so it gets an explicit canonical
     * `INCOMPLETE/ABANDONED` terminal and is promoted to `FINISHED`
     * (and becomes visible in history) rather than left in a limbo status forever. No archive: we
     * are not necessarily on the build that recorded it any more.
     */
    fun finalizePartial(gameId: String) {
        val stored = store.find(gameId) ?: return
        if (stored.status != ReplayStatus.IN_PROGRESS) return
        store.save(
            stored.copy(
                replay = finalizeCanonicalPrefix(stored.replay).copy(endedAt = Instant.now().toString()),
                status = ReplayStatus.FINISHED,
                resumeFingerprint = null,
            )
        )
        logger.info(
            "Finalized partial replay {} at {} actions", gameId, stored.replay.actions.size,
        )
    }

    private fun finalizeCanonicalPrefix(replay: CompactReplay): CompactReplay {
        val records = replay.canonicalRecords
        if (records.isEmpty() || records.lastOrNull() is CanonicalReplayTerminal) return replay
        val prefix = com.wingedsheep.engine.replay.CanonicalReplayReconstructor.reconstructPrefix(records)
        val finalState = prefix.stateAt(prefix.states.lastIndex)
        val terminal = CanonicalReplayRecorder.resume(records).finish(
            status = ReplayCompletionStatus.INCOMPLETE,
            finalState = finalState,
            incompleteReason = ReplayIncompleteReason.ABANDONED,
        )
        return replay.copy(canonicalRecords = records + terminal)
    }

    /** The stored record for [gameId], or null if unknown. */
    fun find(gameId: String): CompactReplay? = store.find(gameId)?.replay

    /** The stored record with its archive and bookkeeping, for the recording/resume paths. */
    fun findStored(gameId: String): StoredReplay? = store.find(gameId)

    /** Every in-flight recording, for resuming after a restart. */
    fun inProgress(): List<StoredReplay> = store.findInProgress()

    /**
     * The viewer body for [gameId] — directly decoded for canonical v3, or re-simulated/fallen back
     * to archived frames for legacy data. Null if the game is unknown, or known but unwatchable.
     */
    fun viewerPayload(gameId: String): ReplayViewerPayload? =
        store.find(gameId)?.let { viewerPayload(it) }

    fun viewerPayload(stored: StoredReplay): ReplayViewerPayload? {
        val reconstructed = runCatching { reconstructor.reconstruct(stored.replay) }
            .onFailure { logger.error("Replay {} failed to reconstruct: {}", stored.replay.gameId, it.message) }
            .getOrNull()

        if (reconstructed != null && reconstructed.isComplete) {
            return ReplayViewerPayload(
                body = presentation.compose(reconstructed),
                frameCount = reconstructed.frameCount,
                fidelity = reconstructed.fidelity,
                // A truncated record re-simulates perfectly — it is simply not the whole game, and
                // the one thing it must not do is look like it is. Note this says nothing about
                // fidelity: these frames are exact, there are just fewer of them than were played.
                degradedReason = if (stored.replay.truncated) {
                    "Only the first ${stored.replay.frameCount} frames of this game were recorded — " +
                        "it ran long enough that recording had to stop, and it continued past the end " +
                        "of what you can watch here."
                } else null,
                stateReproducible = true,
            )
        }

        val archived = stored.presentation
        if (archived != null) {
            logger.info(
                "Serving archived frames for replay {} (recorded on {}): {}",
                stored.replay.gameId, stored.replay.engineVersion,
                reconstructed?.divergenceReason ?: "reconstruction threw",
            )
            return ReplayViewerPayload(
                body = archived,
                frameCount = stored.replay.frameCount,
                fidelity = ReplayFidelity.DIVERGED,
                degradedReason = "Recorded on an earlier version of the engine — showing the game " +
                    "exactly as it was played, from the archive.",
                stateReproducible = false,
            )
        }

        if (reconstructed == null) return null
        return ReplayViewerPayload(
            body = presentation.compose(reconstructed),
            frameCount = reconstructed.frameCount,
            fidelity = reconstructed.fidelity,
            degradedReason = "This replay could not be fully re-simulated on the current version " +
                "and stops after ${reconstructed.frameCount} of ${stored.replay.frameCount} frames.",
            stateReproducible = false,
        )
    }

    /** The full unmasked [GameState] at [frame] for [gameId] (0 = initial), or null. */
    fun reconstructStateAt(gameId: String, frame: Int): GameState? =
        find(gameId)?.let { reconstructor.reconstructStateAt(it, frame) }

    /** Finished games this player took part in, newest first. */
    fun recentForPlayer(playerId: String, limit: Int = 50): List<ReplaySummary> =
        store.findRecentForPlayer(playerId, limit)

    /** Summary for a single game id (tournament game lists resolve ids one at a time). */
    fun summary(gameId: String): ReplaySummary? = store.find(gameId)?.replay?.let {
        ReplaySummary(
            gameId = it.gameId,
            playerNames = it.players.map { player -> player.name },
            startedAt = it.startedAt,
            endedAt = it.endedAt,
            winnerName = it.winnerName,
            frameCount = it.frameCount,
            tournamentName = it.tournamentName,
            tournamentRound = it.tournamentRound,
        )
    }
}
