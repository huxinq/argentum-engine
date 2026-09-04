package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gameserver.replay.ReplayYieldEntry
import com.wingedsheep.sdk.model.EntityId

/**
 * One lock-consistent view of the live inputs needed by a trusted server-side AI controller.
 *
 * [state] is always the authoritative live state. [replayHistory] says whether its compact inputs
 * reproduce that state or are only the prefix retained after replay recording reached its cap.
 * Keeping that distinction in the type prevents an external controller from treating a plausible
 * but incomplete action list as the history of the live position.
 */
data class AiRuntimeSnapshot(
    val state: GameState,
    val replayHistory: AiReplayHistory,
)

/**
 * Replay inputs sampled atomically with an [AiRuntimeSnapshot]'s state.
 *
 * Persistent yield changes are inputs too: the engine can consume them without a [GameAction], so
 * setup plus actions alone does not necessarily reconstruct the position an AI is looking at.
 */
sealed interface AiReplayHistory {
    val setup: ReplaySetup
    val actions: List<GameAction>
    val yields: List<ReplayYieldEntry>

    /** These inputs reproduce the snapshot's live state. */
    data class Complete(
        override val setup: ReplaySetup,
        override val actions: List<GameAction>,
        override val yields: List<ReplayYieldEntry>,
    ) : AiReplayHistory

    /**
     * Recording stopped before the snapshot's live state. These inputs remain an honest replay
     * prefix, but must not be used to reconstruct or explain the current state.
     */
    data class TruncatedPrefix(
        override val setup: ReplaySetup,
        override val actions: List<GameAction>,
        override val yields: List<ReplayYieldEntry>,
    ) : AiReplayHistory
}

/** Inputs supplied to an AI implementation hosted outside the game-server build. */
data class AiControllerContext(
    val playerId: EntityId,
    /** Null while a persisted or tournament AI identity is not yet attached to a game. */
    val gameSessionId: String?,
    /** Null until the attached game has started and has reproducible replay inputs. */
    val snapshot: () -> AiRuntimeSnapshot?,
)

/**
 * Extension point for a server-side AI implementation supplied by another build.
 *
 * Providers own controller policy only. Game lifecycle, callbacks, and the authoritative runtime
 * snapshot remain game-server responsibilities.
 */
interface AiControllerProvider {
    /** Case-insensitive configuration value used by `game.ai.mode`. */
    val mode: String

    fun create(context: AiControllerContext): AiPlayerController
}

/** Single authority for validating and resolving external mode names. */
internal class AiControllerProviderRegistry(providers: List<AiControllerProvider>) {
    private val providersByMode: Map<String, AiControllerProvider>

    init {
        val indexed = linkedMapOf<String, AiControllerProvider>()
        for (provider in providers) {
            val mode = normalize(provider.mode)
            require(mode.isNotEmpty()) { "AI controller provider mode must not be blank" }
            require(mode !in BUILT_IN_MODES) {
                "AI controller provider mode '${provider.mode}' conflicts with a built-in mode"
            }
            require(indexed.put(mode, provider) == null) {
                "Multiple AI controller providers registered for mode '${provider.mode}'"
            }
        }
        providersByMode = indexed
    }

    operator fun get(mode: String): AiControllerProvider? = providersByMode[normalize(mode)]

    fun supportedModes(): Set<String> = BUILT_IN_MODES + providersByMode.keys

    private fun normalize(mode: String): String = mode.trim().lowercase()

    private companion object {
        val BUILT_IN_MODES = setOf("engine", "llm")
    }
}
