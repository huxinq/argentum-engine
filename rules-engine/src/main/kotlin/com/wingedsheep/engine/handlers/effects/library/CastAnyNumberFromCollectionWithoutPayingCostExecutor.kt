package com.wingedsheep.engine.handlers.effects.library

import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.CastAnyNumberFromCollectionContinuation
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CastAnyNumberFromCollectionWithoutPayingCostEffect
import kotlin.reflect.KClass

/**
 * Executor for [CastAnyNumberFromCollectionWithoutPayingCostEffect].
 *
 * Drives the "cast any number of spells from among them for free" loop **during resolution**.
 * One invocation = one loop iteration: it presents the controller a 0..1 [SelectCardsDecision]
 * over the cards in the collection that are still in exile, then pauses. The actual cast and
 * the next iteration are run by `resumeCastAnyNumberFromCollection`
 * ([com.wingedsheep.engine.handlers.continuations.LibraryAndZoneContinuationResumer]), which
 * re-enters this effect over the remaining cards.
 *
 * Empty / all-gone collection → no-op (the loop ends). The collection must already be the
 * eligible set — callers filter it (nonland, mana value ≤ X, …) upstream; this effect casts
 * whatever it's handed and leaves uncast cards in place. Timing restrictions based on card type
 * are ignored because the synthesized casts go through `CastSpellHandler.execute` directly
 * (the player-action `validate()` timing gate never runs), exactly like Cascade.
 *
 * A non-null `maxCasts` is the remaining budget for the "cast **up to N** spells from among
 * them" wording: an exhausted budget (`<= 0`) ends the loop before the decision is offered, and
 * the budget rides on the continuation so the resumer can re-enter with one fewer once a cast
 * actually initiates (a pick that can't be cast for want of a legal target doesn't spend one).
 */
class CastAnyNumberFromCollectionWithoutPayingCostExecutor :
    EffectExecutor<CastAnyNumberFromCollectionWithoutPayingCostEffect> {

    override val effectType: KClass<CastAnyNumberFromCollectionWithoutPayingCostEffect> =
        CastAnyNumberFromCollectionWithoutPayingCostEffect::class

    override fun execute(
        state: GameState,
        effect: CastAnyNumberFromCollectionWithoutPayingCostEffect,
        context: EffectContext,
    ): EffectResult {
        val remainingCasts = effect.maxCasts
        if (remainingCasts != null && remainingCasts <= 0) return EffectResult.success(state)

        val candidates = stillCastable(state, context.pipeline.storedCollections[effect.from].orEmpty())
        if (candidates.isEmpty()) return EffectResult.success(state)

        val controllerId = context.controllerId
        val sourceName = context.sourceId?.let { state.getEntity(it)?.get<CardComponent>()?.name }

        val cardInfo = candidates.associateWith { cardId ->
            val cardComponent = state.getEntity(cardId)?.get<CardComponent>()
            SearchCardInfo(
                name = cardComponent?.name ?: "Unknown",
                manaCost = cardComponent?.manaCost?.toString() ?: "",
                typeLine = cardComponent?.typeLine?.toString() ?: "",
                imageUri = null,
                colors = cardComponent?.colors?.map { it.name } ?: emptyList(),
            )
        }

        val decision = { decisionId: String -> SelectCardsDecision(
            id = decisionId,
            playerId = controllerId,
            prompt = buildString {
                append(if (effect.payManaCost) "Choose a spell to cast" else "Choose a spell to cast for free")
                // "$n remaining", not "$n more": the card being chosen right now is one of them.
                if (remainingCasts != null) append(" ($remainingCasts remaining)")
                append(", or select none to stop")
            },
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = sourceName,
                phase = DecisionPhase.CASTING,
            ),
            options = candidates,
            minSelections = 0,
            maxSelections = 1,
            cardInfo = cardInfo,
        ) }

        // Normalize the collection to the still-castable set so the resumer's bookkeeping
        // (remaining = collection − chosen) matches exactly what was offered.
        val normalizedContext = context.copy(
            pipeline = context.pipeline.copy(
                storedCollections = context.pipeline.storedCollections + (effect.from to candidates)
            )
        )

        val continuation = CastAnyNumberFromCollectionContinuation(
            from = effect.from,
            effectContext = normalizedContext,
            payManaCost = effect.payManaCost,
            maxCasts = effect.maxCasts,
        )

        return EffectResult.from(state.withPriority(controllerId).suspendForDecision(decision, continuation, emptyList()))
    }

    /** Cards from the collection that are still in their owner's exile (castable from there). */
    private fun stillCastable(state: GameState, ids: List<EntityId>): List<EntityId> =
        ids.filter { id ->
            val ownerId = state.getEntity(id)?.get<OwnerComponent>()?.playerId ?: return@filter false
            id in state.getZone(ZoneKey(ownerId, Zone.EXILE))
        }
}
