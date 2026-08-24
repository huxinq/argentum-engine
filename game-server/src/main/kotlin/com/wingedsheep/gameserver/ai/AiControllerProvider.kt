package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.sdk.model.EntityId

/** Validated server-wide AI controller modes. */
enum class AiControllerMode(val wireName: String) {
    ENGINE("engine"),
    LLM("llm"),
    SEARCH_TEACHER("search-teacher");

    companion object {
        fun parse(value: String): AiControllerMode = entries.firstOrNull {
            it.wireName.equals(value.trim(), ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unknown game.ai.mode '$value'; expected ${entries.joinToString { it.wireName }}"
        )
    }
}

/** One lock-consistent view of the live inputs needed to rebuild an AI shadow world. */
data class AiRuntimeSnapshot(
    val state: GameState,
    val replaySetup: ReplaySetup,
    val actions: List<GameAction>,
)

data class AiControllerContext(
    val playerId: EntityId,
    val gameSessionId: String,
    val snapshot: () -> AiRuntimeSnapshot?,
    /** Publish perspective-safe, read-only diagnostics for the local AI Insight panel. */
    val publishInsight: (SearchTeacherInsight) -> Unit = {},
)

/** Extension point for AI implementations hosted outside the Argentum composite build. */
interface AiControllerProvider {
    val mode: AiControllerMode
    val lockedQuickGame: AiLockedQuickGameContract? get() = null
    fun create(context: AiControllerContext): AiPlayerController
}

data class AiLockedQuickGameContract(
    val deckId: String,
    val deckName: String,
    val deckList: Map<String, Int>,
    val profileLabel: String,
) {
    init {
        require(deckList.values.sum() == 60)
    }
}

/** A controller throws this when continuing would disguise a search/reducer correctness failure. */
class AiControllerFatalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A search candidate stripped down to values safe to expose to the human opponent. */
data class SearchTeacherCandidateInsight(
    val label: String,
    val signature: String,
    val visits: Int,
    val meanValue: Double,
    val policyProbability: Double,
    val chosen: Boolean,
)

/**
 * Cross-build diagnostics contract. It deliberately contains no engine state or hidden-card data.
 * The outer provider creates it; Argentum only stores and serializes it.
 */
data class SearchTeacherInsight(
    val actionIndex: Int,
    val chosenLabel: String? = null,
    val chosenSignature: String? = null,
    val candidates: List<SearchTeacherCandidateInsight> = emptyList(),
    val rootValue: Double? = null,
    val thinkTimeMs: Double = 0.0,
    val simulations: Int = 0,
    val particles: Int = 0,
    val nodes: Int = 0,
    val maximumDepth: Int = 0,
    val exhaustiveNodes: Int = 0,
    val nonExhaustiveNodes: Int = 0,
    val wideningEvents: Int = 0,
    val beliefEntropy: Double = 0.0,
    val effectiveSampleSize: Double = 0.0,
    val resamplingCount: Int = 0,
    val reconditioningCount: Int = 0,
    val failureCode: String? = null,
    val diagnostic: String? = null,
    val authoritativeFingerprint: String? = null,
    val shadowFingerprint: String? = null,
)
