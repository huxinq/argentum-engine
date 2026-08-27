package com.wingedsheep.gym.trainer.spi

import com.wingedsheep.ai.ResponsiblePolicyUnavailableException
import com.wingedsheep.ai.engine.TrivialDecisions
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.state.GameState

/**
 * A deterministic, bounded set of responses that search may branch over for a pending decision.
 *
 * [isExhaustive] is true only when [responses] contains every valid response. When it is false,
 * [estimatedResponseCount] is either a deterministic estimate or null when the full response
 * space is not cheaply countable.
 */
data class StructuredExpansion(
    val responses: List<DecisionResponse>,
    val isExhaustive: Boolean,
    val estimatedResponseCount: Long? = null,
)

/** Expands a typed engine decision into distinct, validator-approved search branches. */
fun interface StructuredDecisionExpander {
    fun expand(state: GameState, decision: PendingDecision): StructuredExpansion
}

/**
 * Fail-closed compatibility boundary for integrations that still try to turn one legacy resolver
 * response into a forced edge. The resolver is bypassed only when the shared rules boundary proves
 * one validator-approved, non-decline response; every real choice refuses. New integrations must
 * implement [StructuredDecisionExpander].
 */
fun StructuredDecisionResolver.asExpander(): StructuredDecisionExpander = StructuredDecisionExpander { state, decision ->
    val forced = TrivialDecisions.responseFor(state, decision)
        ?: throw ResponsiblePolicyUnavailableException(
            choiceKind = decision::class.simpleName ?: "PENDING_DECISION",
            diagnostic = "a legacy StructuredDecisionResolver cannot own a real player choice " +
                "because it has no declared behavior identity or measurement",
        )
    StructuredExpansion(
        responses = listOf(forced),
        isExhaustive = true,
        estimatedResponseCount = 1L,
    )
}
