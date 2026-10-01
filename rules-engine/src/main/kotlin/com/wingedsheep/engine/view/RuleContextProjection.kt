package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.serialization.CardSerialization
import kotlinx.serialization.json.*
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils.toEntityId

/** The executable SDK program is public; private pipeline collections never cross this boundary. */
object RuleContextProjection {
    fun capture(state: GameState, context: EffectContext): GameState {
        val pending = state.pendingDecision ?: return state
        if (pending.context.semanticRule != null) return state
        val rule = context.semanticRule ?: return state
        val projected = pending.withContext(pending.context.copy(
            semanticRule = rule,
            controllerId = context.effectControllerId ?: context.controllerId,
            ruleFacts = RuleFacts(context.triggerContext?.damageAmount, context.triggerContext?.counterCount,
                context.lastKnownSourceSnapshot?.power, context.declaredCostSlot?.name == "KICKED", context.chosenModes,
                targetGroups(rule.targetRequirements, context.alignedTargets.ifEmpty { context.targets })),
        ))
        return state.copy(continuationStack = state.continuationStack.map { frame ->
            if (frame is Suspension && frame.question.id == pending.id) frame.copy(question = projected) else frame
        })
    }

    /** Same positional scope used by EffectContext.buildNamedTargets; null slots stay in place. */
    fun targetGroups(requirements: List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>,
                     targets: List<com.wingedsheep.engine.state.components.stack.ChosenTarget?>): List<List<EntityId?>> {
        var offset = 0
        return requirements.map { requirement ->
            List(requirement.count) { targets.getOrNull(offset + it)?.toEntityId() }.also { offset += requirement.count }
        }
    }

    /** Inspect only serializer-typed EntityId fields; ordinary aliases are not identities. */
    fun <T> visible(value: T?, serializer: kotlinx.serialization.KSerializer<T>, state: GameState,
                    viewer: EntityId, visibility: Visibility, spectator: Boolean = false): T? {
        if (value == null) return null
        val references = TypedEntityReferences.project(serializer, value) as? TypedEntityReferences.Projection.Complete ?: return null
        return value.takeIf { references.entityIds.all { id ->
            id in state.turnOrder || state.getEntity(id)?.get<CardComponent>() != null &&
                state.logicalZone(id)?.let { visibility.isCardIdentityVisibleTo(state, it, id, viewer, spectator) } == true
        } }
    }

    fun visibleRule(rule: SemanticRule?, state: GameState, viewer: EntityId, visibility: Visibility,
                    spectator: Boolean = false): SemanticRule? =
        visible(rule, SemanticRule.serializer(), state, viewer, visibility, spectator)

    fun grantedTriggeredAbilities(state: GameState, recipient: EntityId, viewer: EntityId, visibility: Visibility,
                                   spectator: Boolean = false): List<PublicGrantedTriggeredAbility> =
        state.grantedTriggeredAbilities.filter { it.entityId == recipient }.mapNotNull { grant ->
            visible(PublicGrantedTriggeredAbility(grant.ability, grant.duration,
                com.wingedsheep.sdk.scripting.targets.EffectTarget.SpecificEntity(recipient),
                grant.sourceId?.let { com.wingedsheep.sdk.scripting.targets.EffectTarget.SpecificEntity(it) }),
                PublicGrantedTriggeredAbility.serializer(), state, viewer, visibility, spectator)
        }

    fun visibleFacts(facts: RuleFacts, state: GameState, viewer: EntityId, visibility: Visibility,
                     spectator: Boolean = false): RuleFacts = facts.copy(targetGroups = facts.targetGroups.map { group ->
        group.map { id -> id?.takeIf { it in state.turnOrder || state.logicalZone(it)?.let { zone ->
            visibility.isCardIdentityVisibleTo(state, zone, it, viewer, spectator)
        } == true } }
    })

    fun pending(state: GameState, decision: PendingDecision, viewer: EntityId, visibility: Visibility): PendingDecision {
        require(state.actorFor(decision.playerId) == viewer) { "Only the chooser receives pending rule context" }
        val continuation = state.continuationStack.filterIsInstance<Suspension>().lastOrNull { it.question.id == decision.id }?.answer
        val captured = if (continuation is TriggeredAbilityContinuation) decision.context.copy(
            semanticRule = SemanticRule(continuation.effect, continuation.targetRequirements, continuation.interveningIf),
            controllerId = continuation.controllerId,
            ruleFacts = RuleFacts(continuation.triggerContext?.damageAmount, continuation.triggerContext?.counterCount,
                targetGroups = continuation.sequentialTargets.orEmpty()),
        ) else if (continuation is HandSizeDiscardContinuation) decision.context.copy(
            gameRule = PublicGameRule.HAND_SIZE_DISCARD, controllerId = continuation.playerId,
        ) else decision.context
        val optionRules = if (continuation is PayOrSufferChoiceContinuation)
            continuation.options.map { SemanticRule(cost = it) } + SemanticRule(effect = continuation.sufferEffect)
            else captured.optionRules
        val visibleOptions = optionRules.map { visibleRule(it, state, viewer, visibility) }
        val visible = visibleRule(captured.semanticRule, state, viewer, visibility)
        val facts = captured.ruleFacts?.takeIf { visible != null }?.let { visibleFacts(it, state, viewer, visibility) }
        return decision.withContext(captured.copy(semanticRule = visible, ruleFacts = facts,
            optionRules = if (visibleOptions.any { it == null }) emptyList() else visibleOptions.filterNotNull()))
    }
}
