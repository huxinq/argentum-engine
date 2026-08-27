package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseManaColor
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice

/** Primary visible game object an action operates on, when the action has exactly one. */
fun GameAction.sourceEntityIdOrNull(): EntityId? = when (this) {
    is CastSpell -> cardId
    is ActivateAbility -> sourceId
    is CycleCard -> cardId
    is PlotCard -> cardId
    is ForetellCard -> cardId
    is SuspendCardFromHand -> cardId
    is TypecycleCard -> cardId
    is PlayLand -> cardId
    is OrderBlockers -> attackerId
    is CrewVehicle -> vehicleId
    is SaddleMount -> mountId
    is TurnFaceUp -> sourceId
    is UnlockRoomDoor -> roomId
    is PassPriority,
    is DeclareAttackers,
    is DeclareBlockers,
    is ChooseManaColor,
    is SubmitDecision,
    is TakeMulligan,
    is KeepHand,
    is BottomCards,
    is Concede -> null
}

/**
 * Entity references at typed, player-authored positions in an action.
 *
 * Ordinary strings, enum names, ability IDs, decision nonces, labels, and JSON object keys are
 * deliberately absent. This exhaustive function is the admission schema used at the Gym boundary
 * and by the MTGallium adapter.
 */
fun GameAction.playerFacingEntityReferences(): Set<EntityId> = buildSet {
    add(playerId)
    when (val action = this@playerFacingEntityReferences) {
        is PassPriority,
        is ChooseManaColor,
        is TakeMulligan,
        is KeepHand,
        is Concede -> Unit
        is CastSpell -> {
            add(action.cardId)
            action.targets.forEach { addAll(it.entityReferences()) }
            action.modeTargetsOrdered.flatten().forEach { addAll(it.entityReferences()) }
            addAll(action.damageDistribution.orEmpty().keys)
            action.modeDamageDistribution.values.forEach { addAll(it.keys) }
            action.paymentStrategy.addReferencesTo(this)
            action.alternativePayment?.addReferencesTo(this)
            action.additionalCostPayment?.addReferencesTo(this)
            action.giftRecipient?.let(::add)
            addAll(action.splicedCardIds)
            addAll(action.conspiredCreatures)
            action.casualtyCreature?.let(::add)
        }
        is ActivateAbility -> {
            add(action.sourceId)
            action.targets.forEach { addAll(it.entityReferences()) }
            addAll(action.damageDistribution.orEmpty().keys)
            action.paymentStrategy.addReferencesTo(this)
            action.alternativePayment?.addReferencesTo(this)
            action.costPayment?.addReferencesTo(this)
        }
        is CycleCard -> {
            add(action.cardId)
            action.paymentStrategy.addReferencesTo(this)
        }
        is PlotCard -> {
            add(action.cardId)
            action.paymentStrategy.addReferencesTo(this)
        }
        is ForetellCard -> {
            add(action.cardId)
            action.paymentStrategy.addReferencesTo(this)
        }
        is SuspendCardFromHand -> {
            add(action.cardId)
            action.paymentStrategy.addReferencesTo(this)
        }
        is TypecycleCard -> {
            add(action.cardId)
            action.paymentStrategy.addReferencesTo(this)
        }
        is PlayLand -> add(action.cardId)
        is DeclareAttackers -> {
            addAll(action.attackers.keys)
            addAll(action.attackers.values)
            action.bands.forEach(::addAll)
        }
        is DeclareBlockers -> {
            addAll(action.blockers.keys)
            action.blockers.values.forEach(::addAll)
        }
        is OrderBlockers -> {
            add(action.attackerId)
            addAll(action.orderedBlockers)
        }
        is SubmitDecision -> addAll(action.response.playerFacingEntityReferences())
        is BottomCards -> addAll(action.cardIds)
        is CrewVehicle -> {
            add(action.vehicleId)
            addAll(action.crewCreatures)
        }
        is SaddleMount -> {
            add(action.mountId)
            addAll(action.saddleCreatures)
        }
        is TurnFaceUp -> {
            add(action.sourceId)
            addAll(action.costTargetIds)
            action.paymentStrategy.addReferencesTo(this)
        }
        is UnlockRoomDoor -> {
            add(action.roomId)
            action.paymentStrategy.addReferencesTo(this)
        }
    }
}

/** Entity references at typed positions in every sealed decision-response family. */
fun DecisionResponse.playerFacingEntityReferences(): Set<EntityId> = when (this) {
    is TargetsResponse -> selectedTargets.values.flatten().toSet()
    is CardsSelectedResponse -> selectedCards.toSet()
    is DistributionResponse -> distribution.keys
    is OrderedResponse -> orderedObjects.toSet()
    is PilesSplitResponse -> piles.flatten().toSet()
    is DamageAssignmentResponse -> assignments.keys
    is ManaSourcesSelectedResponse -> selectedSources.toSet() + waterbendPermanents
    is CombatResolutionResponse ->
        orderedBlockers.keys + orderedBlockers.values.flatten() +
            orderedAttackers.keys + orderedAttackers.values.flatten()
    is YesNoResponse,
    is BatchYesNoResponse,
    is ModesChosenResponse,
    is ColorChosenResponse,
    is NumberChosenResponse,
    is OptionChosenResponse,
    is ReplacementChosenResponse,
    is BudgetModalResponse,
    is CancelDecisionResponse -> emptySet()
}

/** Entity references at typed positions in all 18 Gym pending-decision schemas. */
fun DecisionChoiceSpec.playerFacingEntityReferences(): Set<EntityId> = when (this) {
    is TargetsChoiceSpec -> legalTargets.values.flatten().toSet()
    is CardsChoiceSpec -> options.toSet() + nonSelectableOptions +
        cardInfo.orEmpty().keys + conditionalMinimums.flatMap { it.matchingOptions }
    is DistributionChoiceSpec -> targets.toSet() + maxPerTarget.keys
    is OrderChoiceSpec -> objects.toSet() + cardInfo.orEmpty().keys
    is PilesChoiceSpec -> cards.toSet() + cardInfo.orEmpty().keys
    is OptionsChoiceSpec -> optionCardIds.orEmpty().values.flatten().toSet()
    is LibrarySearchChoiceSpec -> options.toSet() + cards.keys
    is LibraryReorderChoiceSpec -> cards.toSet() + cardInfo.keys
    is DamageAssignmentChoiceSpec -> setOf(attackerId) + orderedTargets + listOfNotNull(defenderId) +
        minimumAssignments.keys + defaultAssignments.keys
    is CombatResolutionChoiceSpec ->
        attackers.flatMap { listOf(it.id, it.attackedDefenderId) + it.blockedByIds } +
            blockers.flatMap { listOf(it.id) + it.blockedAttackerIds + it.orderedAttackers } +
            defenders.map { it.id } +
            edges.flatMap { listOf(it.sourceId, it.targetId, it.editableBy) } +
            listOfNotNull(coChooserId)
    is ManaSourcesChoiceSpec -> availableSources.map { it.entityId } + autoPaySuggestion +
        waterbendPermanents.map { it.entityId }
    is YesNoChoiceSpec,
    is BatchYesNoChoiceSpec,
    is ModesChoiceSpec,
    is ColorsChoiceSpec,
    is NumberChoiceSpec,
    is ReplacementChoiceSpec,
    is BudgetModesChoiceSpec -> emptySet()
}.toSet()

private fun ChosenTarget.entityReferences(): List<EntityId> = when (this) {
    is ChosenTarget.Player -> listOf(playerId)
    is ChosenTarget.Permanent -> listOf(entityId)
    is ChosenTarget.Card -> listOf(cardId, ownerId)
    is ChosenTarget.Spell -> listOf(spellEntityId)
}

private fun com.wingedsheep.engine.core.PaymentStrategy.addReferencesTo(target: MutableSet<EntityId>) {
    if (this is com.wingedsheep.engine.core.PaymentStrategy.Explicit) {
        target.addAll(manaAbilitiesToActivate)
    }
}

private fun AlternativePaymentChoice.addReferencesTo(target: MutableSet<EntityId>) {
    target.addAll(delvedCards)
    target.addAll(convokedCreatures.keys)
    harmonizeCreature?.let(target::add)
    target.addAll(tapForGenericPermanents)
}

private fun AdditionalCostPayment.addReferencesTo(target: MutableSet<EntityId>) {
    target.addAll(sacrificedPermanents)
    target.addAll(discardedCards)
    target.addAll(exiledCards)
    target.addAll(variableCostPermanents)
    target.addAll(beheldCards)
    target.addAll(tappedPermanents)
    target.addAll(bouncedPermanents)
    target.addAll(blightTargets)
    target.addAll(distributedCounterRemovals.map { it.entityId })
}
