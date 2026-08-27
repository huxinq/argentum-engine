package com.wingedsheep.ai.engine

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState

/**
 * Decisions whose complete visible contract proves exactly one legal, non-decline answer.
 *
 * "Trivial" here means *forced*, not *easy*: a single legal target, a forced card selection, a mode
 * that is the only available one. Answering these needs no strategy and no simulation, so both the
 * strategic path ([GameSimulator], on the way to a quiet state) and the rollout path
 * ([com.wingedsheep.ai.engine.rollout.FastDecisionResponder], inside a playout) start here and only
 * fall through to their own policy when the choice is real.
 *
 * Structural checks prove uniqueness; [DecisionValidators] independently validates the proposed
 * answer against the current state. A decision this helper cannot prove forced is left to the
 * installed policy, even when a convenient default or auto-pay suggestion exists.
 */
object TrivialDecisions {

    /** The proven-forced response to [decision], or null when policy responsibility remains. */
    fun responseFor(state: GameState, decision: PendingDecision): DecisionResponse? {
        val candidate = structurallyUniqueNonDeclineResponse(decision) ?: return null
        return candidate.takeIf { DecisionValidators.validate(decision, it, state) == null }
    }

    private fun structurallyUniqueNonDeclineResponse(decision: PendingDecision): DecisionResponse? = when (decision) {
        // Every requirement must demand every distinct legal target, and cancellation must be absent.
        is ChooseTargetsDecision -> {
            val forced = decision.targetRequirements.associate { requirement ->
                requirement.index to decision.legalTargets[requirement.index].orEmpty().distinct()
            }
            val allForced = !decision.canCancel && forced.isNotEmpty() &&
                forced.values.sumOf { it.size } > 0 &&
                decision.targetRequirements.all { requirement ->
                    val targets = forced.getValue(requirement.index)
                    targets.size == requirement.minTargets &&
                        targets.size == requirement.maxTargets
                }
            if (allForced) {
                TargetsResponse(
                    decisionId = decision.id,
                    selectedTargets = forced,
                )
            } else null
        }

        // Selecting every card is unique only when order is irrelevant (or only one card exists).
        is SelectCardsDecision -> {
            if (decision.options.isNotEmpty() &&
                decision.options.distinct().size == decision.options.size &&
                decision.minSelections == decision.options.size &&
                decision.maxSelections == decision.options.size &&
                (!decision.ordered || decision.options.size == 1)
            ) {
                CardsSelectedResponse(decision.id, decision.options)
            } else null
        }

        // A lone option is still a choice when the player may cancel.
        is ChooseOptionDecision -> {
            if (decision.options.size == 1 && !decision.canCancel) {
                OptionChosenResponse(decision.id, 0)
            } else null
        }

        // Single color
        is ChooseColorDecision -> {
            if (decision.availableColors.size == 1) {
                ColorChosenResponse(decision.id, decision.availableColors.first())
            } else null
        }

        // With one available mode and a fixed positive count, even repetitions are determined.
        is ChooseModeDecision -> {
            val available = decision.modes.filter { it.available }
            if (available.size == 1 && decision.minModes > 0 &&
                decision.minModes == decision.maxModes
            ) {
                ModesChosenResponse(decision.id, List(decision.minModes) { available.first().index })
            } else null
        }

        // Number with single valid value
        is ChooseNumberDecision -> {
            if (decision.minValue == decision.maxValue) {
                NumberChosenResponse(decision.id, decision.minValue)
            } else null
        }

        // Empty confirmations are left to policy; one object has one non-empty order.
        is OrderObjectsDecision -> {
            if (decision.objects.size == 1) {
                OrderedResponse(decision.id, decision.objects)
            } else null
        }

        // Empty confirmations are left to policy; one card has one non-empty order.
        is ReorderLibraryDecision -> {
            if (decision.cards.size == 1) {
                OrderedResponse(decision.id, decision.cards)
            } else null
        }

        else -> null
    }
}
