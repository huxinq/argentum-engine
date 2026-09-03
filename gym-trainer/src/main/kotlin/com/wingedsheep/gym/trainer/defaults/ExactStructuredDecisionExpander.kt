package com.wingedsheep.gym.trainer.defaults

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpander
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpansion
import com.wingedsheep.sdk.model.EntityId

/**
 * Exact, policy-free expansion for structured families whose pending-decision metadata fully
 * describes a finite response space.
 *
 * A target decision with one requirement for at most one target is finite and described completely
 * by its pending-decision payload: one response per legal target, plus the empty selection when the
 * requirement is optional. Every candidate is filtered through [DecisionValidators] before it is
 * exposed. Variable-cardinality and multi-requirement target decisions remain unsupported because
 * current MCTS materializes every edge and has no caller-owned widening or response-budget policy.
 * Small unique orderings are likewise finite: they have one semantic response for every
 * permutation. The explicit [MAX_ORDERING_RESPONSES] materialization ceiling is this expander's
 * capability bound, not an engine legality restriction. Larger, duplicate, or otherwise
 * non-materializable orderings remain unsupported.
 *
 * Cancellation is deliberately not one of the responses. A
 * [com.wingedsheep.engine.core.CancelDecisionResponse] on a cast-time target decision rewinds to the
 * priority state that offered the cast, so a cancel edge is a transposition back to the search node's
 * own ancestor rather than an alternative *within* the decision — the "don't cast" branch already
 * exists where the cast was chosen. Declining an optional requirement is the empty selection, which
 * stays inside the decision.
 *
 * A supported decision left with no validator-approved response is reported as
 * [StructuredDecisionExpansion.Unsupported], not as an empty [StructuredDecisionExpansion.Complete]:
 * an empty complete set is unsearchable, so the caller's resolver fallback owns that degenerate case.
 */
object ExactStructuredDecisionExpander : StructuredDecisionExpander {

    override fun expand(
        state: GameState,
        decision: PendingDecision
    ): StructuredDecisionExpansion = when (decision) {
        is ChooseTargetsDecision -> {
            val requirement = decision.targetRequirements.singleOrNull()
            val legalTargets = requirement?.let { decision.legalTargets[it.index] }
            if (
                requirement == null || legalTargets == null ||
                requirement.maxTargets != 1 || requirement.minTargets !in 0..1
            ) {
                StructuredDecisionExpansion.Unsupported
            } else {
                complete(
                    state = state,
                    decision = decision,
                    candidates = targetResponses(
                        decision = decision,
                        requirementIndex = requirement.index,
                        legalTargets = legalTargets,
                        optional = requirement.minTargets == 0
                    )
                )
            }
        }

        is OrderObjectsDecision -> completeOrdering(
            state = state,
            decision = decision,
            objects = decision.objects
        )

        is ReorderLibraryDecision -> completeOrdering(
            state = state,
            decision = decision,
            objects = decision.cards
        )

        else -> StructuredDecisionExpansion.Unsupported
    }

    private fun completeOrdering(
        state: GameState,
        decision: PendingDecision,
        objects: List<EntityId>
    ): StructuredDecisionExpansion {
        // An ordering has every and only validator-accepted semantic responses precisely when its
        // IDs are unique: then those responses are its permutations. The engine owns legality;
        // this guard keeps the exact-expansion claim well-defined even if a malformed pending
        // decision repeats an ID.
        if (!permutationCountFitsCeiling(objects.size) || objects.distinct().size != objects.size) {
            return StructuredDecisionExpansion.Unsupported
        }
        return complete(state, decision, orderingResponses(decision.id, objects))
    }

    private fun permutationCountFitsCeiling(size: Int): Boolean {
        var permutations = 1
        for (factor in 2..size) {
            // Check before multiplying, so a malformed arbitrarily large input cannot overflow
            // its way back under the materialization ceiling.
            if (permutations > MAX_ORDERING_RESPONSES / factor) return false
            permutations *= factor
        }
        return true
    }

    private fun orderingResponses(
        decisionId: String,
        objects: List<EntityId>
    ): List<DecisionResponse> = buildList {
        fun appendPermutations(prefix: MutableList<EntityId>, remaining: List<EntityId>) {
            if (remaining.isEmpty()) {
                add(OrderedResponse(decisionId, prefix.toList()))
                return
            }

            for ((index, objectId) in remaining.withIndex()) {
                prefix += objectId
                appendPermutations(prefix, remaining.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                prefix.removeAt(prefix.lastIndex)
            }
        }

        appendPermutations(mutableListOf(), objects)
    }

    private fun targetResponses(
        decision: ChooseTargetsDecision,
        requirementIndex: Int,
        legalTargets: List<EntityId>,
        optional: Boolean
    ): List<DecisionResponse> = buildList {
        if (optional) {
            add(TargetsResponse(decision.id, mapOf(requirementIndex to emptyList())))
        }

        for (target in legalTargets.distinct()) {
            add(TargetsResponse(decision.id, mapOf(requirementIndex to listOf(target))))
        }
    }

    private fun complete(
        state: GameState,
        decision: PendingDecision,
        candidates: List<DecisionResponse>
    ): StructuredDecisionExpansion {
        val legal = candidates.filter { DecisionValidators.validate(decision, it, state) == null }
        return if (legal.isEmpty()) {
            StructuredDecisionExpansion.Unsupported
        } else {
            StructuredDecisionExpansion.Complete(legal)
        }
    }

    /** Four objects have exactly 24 permutations; the fifth would require 120 search edges. */
    private const val MAX_ORDERING_RESPONSES = 24
}
