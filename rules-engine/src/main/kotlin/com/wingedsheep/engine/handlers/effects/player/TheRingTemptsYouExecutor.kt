package com.wingedsheep.engine.handlers.effects.player

import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.RingTemptContinuation
import com.wingedsheep.engine.core.RingTemptedEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RingBearerComponent
import com.wingedsheep.engine.state.components.player.TheRingComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.TheRingTemptsYouEffect
import kotlin.reflect.KClass

/**
 * Resolves [TheRingTemptsYouEffect] — "the Ring tempts you" (CR 701.54).
 *
 * 1. Ensure the tempted player has the Ring emblem ([TheRingComponent]) and increment its tempt
 *    count (CR 701.54c — the emblem and its leveling happen *before* choosing a Ring-bearer).
 * 2. If the player controls one or more creatures, pause to let them choose one to become their
 *    Ring-bearer; [RingTemptContinuation] applies the designation and announces the temptation.
 * 3. If the player controls no creature, the temptation still happens (CR 701.54a/d): the count is
 *    incremented and [RingTemptedEvent] fires with no bearer chosen.
 */
class TheRingTemptsYouExecutor : EffectExecutor<TheRingTemptsYouEffect> {

    override val effectType: KClass<TheRingTemptsYouEffect> = TheRingTemptsYouEffect::class

    override fun execute(
        state: GameState,
        effect: TheRingTemptsYouEffect,
        context: EffectContext
    ): EffectResult {
        val temptedId = context.resolveTarget(effect.target)
            ?: return EffectResult.error(state, "No valid player for 'the Ring tempts you'")
        if (!state.turnOrder.contains(temptedId)) {
            return EffectResult.error(state, "'The Ring tempts you' must target a player")
        }

        val sourceName = context.sourceId
            ?.let { state.getEntity(it)?.get<CardComponent>()?.name }
            ?: "The Ring"

        val newCount = (state.getEntity(temptedId)?.get<TheRingComponent>()?.temptCount ?: 0) + 1
        var newState = state.updateEntity(temptedId) { it.with(TheRingComponent(newCount)) }

        val projected = newState.projectedState
        val candidates = projected.getBattlefieldControlledBy(temptedId)
            .filter { projected.isCreature(it) }

        if (candidates.isEmpty()) {
            return EffectResult.success(
                newState,
                listOf(
                    RingTemptedEvent(
                        playerId = temptedId,
                        temptCount = newCount,
                        bearerId = currentBearer(newState, temptedId),
                        sourceName = sourceName
                    )
                )
            )
        }

        val decision = { decisionId: String -> SelectCardsDecision(
            id = decisionId,
            playerId = temptedId,
            prompt = "The Ring tempts you — choose a creature to become your Ring-bearer",
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = sourceName,
                phase = DecisionPhase.RESOLUTION
            ),
            options = candidates,
            minSelections = 1,
            maxSelections = 1,
            useTargetingUI = true
        ) }

        val continuation = RingTemptContinuation(
            temptedPlayerId = temptedId,
            temptCount = newCount,
            sourceName = sourceName,
            candidates = candidates
        )

        return EffectResult.from(newState.suspendForDecision(decision, continuation, emptyList()))
    }

    private fun currentBearer(state: GameState, ownerId: EntityId): EntityId? =
        state.getBattlefield().firstOrNull { id ->
            state.getEntity(id)?.get<RingBearerComponent>()?.ownerId == ownerId
        }
}
