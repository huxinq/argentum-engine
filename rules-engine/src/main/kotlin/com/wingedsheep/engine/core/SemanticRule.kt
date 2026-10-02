package com.wingedsheep.engine.core

import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.conditions.Condition
import kotlinx.serialization.Serializable

/** Captured SDK program and its ordered target scope, without private pipeline contents. */
@Serializable
data class SemanticRule(
    val effect: Effect? = null,
    val targetRequirements: List<TargetRequirement> = emptyList(),
    val interveningIf: Condition? = null,
    val cost: com.wingedsheep.sdk.scripting.costs.PayCost? = null,
    val replacementEffect: com.wingedsheep.sdk.scripting.ReplacementEffect? = null,
)

/** Public scalar facts captured when an ability triggered or its source paid a cost. */
@Serializable
data class RuleFacts(
    val damageAmount: Int? = null,
    val counterCount: Int? = null,
    val lastKnownSourcePower: Int? = null,
    val wasKicked: Boolean? = null,
    val chosenModes: List<Int> = emptyList(),
    val targetGroups: List<List<com.wingedsheep.sdk.model.EntityId?>> = emptyList(),
)

/** A live grant, before display badges deduplicate identical descriptions. */
@Serializable
data class PublicGrantedTriggeredAbility(
    val ability: com.wingedsheep.sdk.scripting.TriggeredAbility,
    val duration: com.wingedsheep.sdk.scripting.Duration,
    val recipient: com.wingedsheep.sdk.scripting.targets.EffectTarget,
    val grantingSource: com.wingedsheep.sdk.scripting.targets.EffectTarget? = null,
)
