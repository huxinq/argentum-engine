package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.actions.ability.ActivatedAbilityLookup
import com.wingedsheep.engine.handlers.actions.ability.ActivatedAbilityResolver
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TextChanges
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import kotlinx.serialization.Serializable

/** Addresses rule data, never an ability ordinal. Non-definition abilities carry their rule. */
@Serializable
data class LegalActionRule(
    val cardDefinitionId: String,
    val abilityIdentity: ClientAbilityIdentity? = null,
    val origin: AbilityOrigin? = null,
    val effectiveAbility: ActivatedAbility? = null,
    val granterId: EntityId? = null,
    /** A catalog address alone cannot describe text changed by continuous effects. */
    val hasTextChanges: Boolean = false,
)

@Serializable
enum class AbilityOrigin { DEFINITION, CLASS, RUNTIME_GRANTED, STATIC_GRANTED, EMBLEM_GRANTED, INTRINSIC }

internal class LegalActionRuleResolver(registry: CardRegistry) {
    private val predicates = PredicateEvaluator(registry)
    private val abilities = ActivatedAbilityResolver(
        registry, CastPermissionUtils(registry, predicates, predicates.conditions)
    )

    fun resolve(state: GameState, action: GameAction): LegalActionRule? {
        val source = when (action) {
            is ActivateAbility -> action.sourceId
            is CastSpell -> action.cardId
            else -> return null
        }
        val card = state.getEntity(source)?.get<CardComponent>() ?: return null
        val replacement = TextChanges.of(state, source)
        if (action !is ActivateAbility) return LegalActionRule(card.cardDefinitionId, hasTextChanges = replacement != null)
        val resolved = abilities.lookup(state, source, action.abilityId) ?: return null
        val origin = when (resolved) {
            is ActivatedAbilityLookup.DirectDefinition -> AbilityOrigin.DEFINITION
            is ActivatedAbilityLookup.DefinitionDerivedClass -> AbilityOrigin.CLASS
            is ActivatedAbilityLookup.RuntimeGranted -> AbilityOrigin.RUNTIME_GRANTED
            is ActivatedAbilityLookup.StaticGranted -> AbilityOrigin.STATIC_GRANTED
            is ActivatedAbilityLookup.EmblemGranted -> AbilityOrigin.EMBLEM_GRANTED
            is ActivatedAbilityLookup.Intrinsic -> AbilityOrigin.INTRINSIC
        }
        val effective = replacement?.let { resolved.ability.applyTextReplacement(it) } ?: resolved.ability
        val identity = resolved.definitionIdentity?.let {
            ClientAbilityIdentity(it.cardDefinitionId, it.abilityId.value)
        }
        return LegalActionRule(
            card.cardDefinitionId, identity, origin,
            // Printed rules are catalog data. Send only effective rules that cannot be recovered
            // from that address; clients may cache these separately from instance bindings.
            effectiveAbility = effective.takeIf { origin != AbilityOrigin.DEFINITION || replacement != null },
            granterId = resolved.staticGranterId,
            hasTextChanges = replacement != null,
        )
    }
}
