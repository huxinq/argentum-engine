package com.wingedsheep.engine.handlers.effects.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.stack.StackPlacement
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CopyTargetTriggeredAbilityEffect
import kotlin.reflect.KClass

/**
 * Executor for [CopyTargetTriggeredAbilityEffect].
 *
 * Copies a targeted triggered ability on the stack and pushes the copy as a new
 * [TriggeredAbilityOnStackComponent] entity. If the original ability has targets, the
 * copy's controller may choose new targets (Rule 707.10c). Modal choices and inherited
 * values (triggering entity, X, counters, etc.) are preserved per Rule 707.10.
 *
 * Per Rule 707.10: "A copy of a spell or ability isn't cast or activated. The copy is
 * created on the stack. The copy's controller is the controller of the spell or ability
 * that created it."
 */
class CopyTargetTriggeredAbilityExecutor(
    private val targetFinder: TargetFinder
) : EffectExecutor<CopyTargetTriggeredAbilityEffect> {

    override val effectType: KClass<CopyTargetTriggeredAbilityEffect> =
        CopyTargetTriggeredAbilityEffect::class

    override fun execute(
        state: GameState,
        effect: CopyTargetTriggeredAbilityEffect,
        context: EffectContext
    ): EffectResult {
        val abilityEntityId = context.resolveTarget(effect.target)
            ?: return EffectResult.error(state, "No target triggered ability to copy")

        val container = state.getEntity(abilityEntityId)
            ?: return EffectResult.error(state, "Target ability entity not found on stack")

        val sourceAbility = container.get<TriggeredAbilityOnStackComponent>()
            ?: return EffectResult.error(state, "Target entity is not a triggered ability on stack")

        // Both ability-copy effects use the same selected-cardinality and slot-preserving path.
        return EffectResult.from(CopyTargetSpellOrAbilityExecutor.driveAbilityCopies(
            state, targetFinder, abilityEntityId, context.controllerId, context.sourceId,
            remainingCopies = 1, totalCopies = 1, priorEvents = emptyList()))
    }

    companion object {
        /**
         * Clone a source triggered ability into a fresh component. The copy inherits
         * every cast-time value (triggering entity, X, counter counts, modal choices,
         * chosen modes, damage distribution) per Rule 707.10, and is controlled
         * by [copyController] per Rule 707.10.
         */
        fun cloneAbility(
            source: TriggeredAbilityOnStackComponent,
            copyController: EntityId
        ): TriggeredAbilityOnStackComponent {
            return source.copy(
                controllerId = copyController,
                stateTriggerAbilityId = null,
                description = "Copy of ${source.description}"
            )
        }
    }
}
