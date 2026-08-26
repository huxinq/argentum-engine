package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.state.YieldKind
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityIdentity
import kotlinx.serialization.Serializable
import com.wingedsheep.engine.replay.CanonicalReplayRecord
import com.wingedsheep.engine.replay.CanonicalReplayTransition

/**
 * The compact, durable form of a recorded game.
 *
 * Version 3 is an envelope around the authoritative [canonicalRecords] state/choice stream.
 * Deterministic state patches and periodic full states reconstruct directly without executing the
 * current engine. [setup], [actions], yields, and fingerprints remain defaulted compatibility fields
 * for reading v1/v2 input-log records and are omitted from new JDBC transition storage.
 */
@Serializable
data class CompactReplay(
    val version: Int = CURRENT_VERSION,
    val gameId: String,
    /** Every seat in turn order. 2-player is the degenerate case (two entries). */
    val players: List<ReplayPlayerInfo>,
    /** ISO-8601 instant strings, matching what the REST summaries already emit. */
    val startedAt: String,
    val endedAt: String,
    val winnerName: String?,
    val tournamentName: String? = null,
    val tournamentRound: Int? = null,
    /** Legacy v1/v2 recipe metadata; retained in v3 as stable game/deck metadata. */
    val setup: ReplaySetup,
    /** Legacy v1/v2 input stream. New durable v3 envelopes leave this empty. */
    val actions: List<GameAction>,
    /**
     * Persistent-yield mutations (MTGO right-click "always yes/no" / "yield" preferences) applied to
     * the game out-of-band of the [actions] stream. They live on [com.wingedsheep.engine.state.GameState]
     * and are *consumed* by the pure engine during resolution (auto-answering optional triggers), so a
     * game where a player set such a yield re-simulates differently unless we re-apply the yields at the
     * exact point they were set — hence [ReplayYieldEntry.afterActionCount]. Empty for games without any
     * yields (the overwhelming majority), and for replays recorded before this field existed.
     */
    val yields: List<ReplayYieldEntry> = emptyList(),
    /**
     * The build that recorded this game (git sha in production, `"dev"` locally). Purely
     * diagnostic — we can't run old code — but it turns "this replay looks wrong" into "this replay
     * was recorded four deploys ago", which is the first question anyone asks.
     */
    val engineVersion: String = UNKNOWN_VERSION,
    /**
     * The compiled definition of every card the decks reference, as compact JSON — see
     * [ReplayCardPin]. Re-simulation overlays these on the live corpus so a card whose
     * implementation changed since doesn't make this recording diverge. Empty for replays recorded
     * before pinning existed (they fall back to the live corpus, as they always did).
     */
    val pinnedCards: List<String> = emptyList(),
    /**
     * Sparse position fingerprints taken while the game was live, every
     * [ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS] actions — see [ReplayFingerprint]. Lets
     * reconstruction *detect* silent drift (actions that still apply but no longer produce the same
     * board) rather than rendering a game that never happened. Empty for older replays, which then
     * reconstruct unverified exactly as before.
     */
    val checkpoints: List<ReplayCheckpoint> = emptyList(),
    /**
     * True when recording was frozen before the game ended, so [actions] is an honest *prefix* of
     * the game rather than all of it — the game went on past the last frame here.
     *
     * Retained for read compatibility with v2 recordings that used a length cutoff. Canonical v3
     * writers never set it: interrupted v3 streams use an explicit INCOMPLETE terminal instead.
     */
    val truncated: Boolean = false,
    /**
     * Schema-v3 authoritative record. New writers populate this exhaustive state/choice stream;
     * [setup]/[actions] remain only as the read-only v1/v2 compatibility representation.
     */
    val canonicalRecords: List<CanonicalReplayRecord> = emptyList(),
) {
    /** Number of reconstructable frames: the initial state plus one per applied action. */
    val frameCount: Int get() = 1 + if (canonicalRecords.isEmpty()) actions.size
        else canonicalRecords.count { it is CanonicalReplayTransition }

    companion object {
        /**
         * Bump when a setup/action shape change would break reconstruction of older records.
         *
         * v1 → v2 added [engineVersion], [pinnedCards] and [checkpoints]. v3 added the canonical
         * state/choice stream; JDBC stores that stream in append-only chunks. All fields default to
         * empty, so v1 records decode and reconstruct unchanged — the version is a diagnostic
         * marker, not a decode gate. Decoding stays deliberately tolerant (`ignoreUnknownKeys`,
         * defaults for every added field) so a record written by a newer build never becomes
         * unreadable by an older one mid-deploy.
         */
        const val CURRENT_VERSION = 3

        const val UNKNOWN_VERSION = "unknown"
    }
}

/**
 * A position fingerprint taken after [afterActionCount] recorded actions had been applied to the
 * live game. See [ReplayFingerprint] for what goes into it and why.
 */
@Serializable
data class ReplayCheckpoint(
    val afterActionCount: Int,
    val fingerprint: String,
)

/**
 * One consistent read of a live session's recording state, taken under the session's state lock.
 *
 * The pieces have to be sampled together or the resume gate is unsound: [fingerprint] is what a
 * restart compares the recovered position against, so if it describes a position *later* than
 * [actions] covers, a crash at exactly that position passes the gate and recording resumes with a
 * hole in the log — the fictional replay the gate exists to prevent. Reading each getter separately
 * makes that a live race, since the game thread advances between calls.
 */
data class ReplayRecordingSnapshot(
    val setup: ReplaySetup,
    val actions: List<com.wingedsheep.engine.core.GameAction>,
    val yields: List<ReplayYieldEntry>,
    val checkpoints: List<ReplayCheckpoint>,
    /** [ReplayFingerprint] of the position [actions] produces — the resume gate's expected value. */
    val fingerprint: String,
    val startedAt: java.time.Instant?,
    /** Sampled with the rest, so a game that ended mid-sweep isn't flushed as in-progress. */
    val gameOver: Boolean,
    /** Whether a restored legacy recording had already been frozen by its former size cap. */
    val truncated: Boolean,
    val canonicalRecords: List<CanonicalReplayRecord> = emptyList(),
)

/** Cadence knobs for the live recorder. */
object ReplayRecordingPolicy {
    /**
     * Actions between [ReplayFingerprint] stamps. Cheap enough to run in the game loop at this
     * cadence, dense enough to pin a divergence to a handful of actions.
     */
    const val CHECKPOINT_EVERY_ACTIONS = 20

    /** Legacy v2 cutoff, retained only for documentation/tests and never applied by canonical writers. */
    @Deprecated("Canonical replay v3 is exhaustive and has no replay-length cutoff")
    const val MAX_RECORDED_ACTIONS = 25_000
}

/** Which yield mutation a [ReplayYieldEntry] records. */
@Serializable
enum class ReplayYieldOp { SET, CLEAR_ABILITY, CLEAR_ALL }

/**
 * A single persistent-yield mutation captured in turn order against the [actions] stream.
 * [afterActionCount] is the number of recorded actions that had been applied when the yield was set,
 * so [ReplayReconstructor] re-applies it at the same point and the engine's auto-answers reproduce
 * exactly. [identity]/[kind] are populated per [op] (both for SET, [identity] only for CLEAR_ABILITY,
 * neither for CLEAR_ALL).
 */
@Serializable
data class ReplayYieldEntry(
    val afterActionCount: Int,
    val playerId: String,
    val op: ReplayYieldOp,
    val identity: AbilityIdentity? = null,
    val kind: YieldKind? = null,
)

/** A single seat in a recorded replay, in turn order. */
@Serializable
data class ReplayPlayerInfo(
    val playerId: String,
    val name: String,
)

/**
 * The reproducible inputs to [com.wingedsheep.engine.core.GameInitializer.initializeGame] for a
 * recorded game, mirroring [com.wingedsheep.engine.core.GameConfig] field-for-field (minus the
 * non-serializable [com.wingedsheep.engine.core.PlayerConfig], flattened into [ReplayPlayerSetup]).
 * [seed] is the seed the engine actually used (captured from
 * [com.wingedsheep.engine.core.InitializationResult.seed]), so the shuffle, turn order, and every
 * "at random" choice replay identically.
 */
@Serializable
data class ReplaySetup(
    val seed: Long,
    val format: Format,
    val attackMode: AttackMode,
    val startingHandSize: Int = 7,
    val skipMulligans: Boolean = false,
    val useHandSmoother: Boolean = false,
    val handSmootherCandidates: Int = 3,
    val startingPlayerIndex: Int? = null,
    val teams: List<List<Int>>? = null,
    val players: List<ReplayPlayerSetup>,
    /**
     * The spectator seat roster captured at game start (turn order, team membership). Echoed back
     * into each reconstructed snapshot so the replay viewer renders the same seating it would live.
     */
    val seatRoster: List<ServerMessage.PlayerSeatInfo>,
)

/** Flattened, serializable form of [com.wingedsheep.engine.core.PlayerConfig]. */
@Serializable
data class ReplayPlayerSetup(
    /** The engine [com.wingedsheep.sdk.model.EntityId] value this seat was assigned — replayed verbatim. */
    val playerId: String,
    val name: String,
    val deck: Deck,
    val startingLife: Int = 20,
    val commanderCardName: String? = null,
)
