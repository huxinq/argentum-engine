package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.SuccessCriterion
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ActionProcessorAtomicityTest : ScenarioTestBase() {
    init {
        test("a later nested continuation error rejects the whole submitted decision") {
            val (game, decision) = gameAwaitingDividedDamage(
                then = Effects.GainLife(1, EffectTarget.ContextTarget(0))
            )
            val preActionState = game.state

            val processed = actionProcessor.process(
                preActionState,
                SubmitDecision(
                    playerId = game.player1Id,
                    response = DistributionResponse(
                        decisionId = decision.id,
                        distribution = linkedMapOf(game.player2Id to 1)
                    )
                )
            )
            val result = processed.result

            result.error shouldBe "No valid target for life gain"
            assertSoftly {
                // Identity, not equality: the boundary hands back the caller's own state object,
                // so nothing the rejected attempt built can survive in any field of it.
                result.state shouldBeSameInstanceAs preActionState
                result.state.lifeTotal(game.player2Id) shouldBe 20
                result.state.pendingDecision shouldBe decision
                result.state.continuationStack shouldBe preActionState.continuationStack
                result.events.shouldBeEmpty()
                result.pendingDecision shouldBe null
                result.triggersAlreadyProcessed shouldBe false
                processed.undoPolicy shouldBe UndoCheckpointAction.PRESERVE
            }
        }

        test("a rejected action drops the revealed-in-hand bookkeeping its events would have driven") {
            val (game, decision) = gameAwaitingDividedDamage(
                then = Effects.GainLife(1, EffectTarget.ContextTarget(0)),
                revealTopCardFirst = true
            )
            val preActionState = game.state
            val libraryCardId = preActionState.getLibrary(game.player1Id).single()

            val result = actionProcessor.process(
                preActionState,
                SubmitDecision(
                    playerId = game.player1Id,
                    response = DistributionResponse(decision.id, linkedMapOf(game.player2Id to 1))
                )
            ).result

            result.error shouldBe "No valid target for life gain"
            assertSoftly {
                // The rejected attempt revealed the card into hand and emitted a CardsRevealedEvent.
                // Running [RevealedInHandTracker] over those events would stamp a RevealedToComponent
                // onto a card that, in the state everyone actually sees, never left the library.
                result.state.getLibrary(game.player1Id) shouldBe listOf(libraryCardId)
                result.state.getHand(game.player1Id).shouldBeEmpty()
                result.state.getEntity(libraryCardId)?.get<RevealedToComponent>() shouldBe null
            }
        }

        test("a successful reveal into hand still records who saw the card") {
            val (game, decision) = gameAwaitingDividedDamage(
                then = Effects.GainLife(1, EffectTarget.Controller),
                revealTopCardFirst = true
            )
            val libraryCardId = game.state.getLibrary(game.player1Id).single()

            val result = game.submitDecision(
                DistributionResponse(decision.id, linkedMapOf(game.player2Id to 1))
            )

            result.error shouldBe null
            result.state.getHand(game.player1Id) shouldBe listOf(libraryCardId)
            result.state.getEntity(libraryCardId)?.get<RevealedToComponent>().shouldNotBeNull()
        }

        test("a successful nested continuation commits its state and events") {
            val (game, decision) = gameAwaitingDividedDamage(
                then = Effects.GainLife(1, EffectTarget.Controller)
            )

            val result = game.submitDecision(
                DistributionResponse(decision.id, linkedMapOf(game.player2Id to 1))
            )

            result.error shouldBe null
            result.pendingDecision shouldBe null
            result.state.lifeTotal(game.player1Id) shouldBe 21
            result.state.lifeTotal(game.player2Id) shouldBe 19
            result.state.pendingDecision shouldBe null
            result.state.continuationStack.shouldBeEmpty()
            result.events.shouldNotBeEmpty()
            result.events.filterIsInstance<LifeChangedEvent>().map { it.playerId to it.newLife } shouldBe
                listOf(game.player2Id to 19, game.player1Id to 21)
        }

        test("a nested continuation that pauses keeps its in-flight state and events") {
            val (game, decision) = gameAwaitingDividedDamage(
                then = MayEffect(Effects.GainLife(1, EffectTarget.Controller))
            )

            val result = game.submitDecision(
                DistributionResponse(decision.id, linkedMapOf(game.player2Id to 1))
            )

            result.error shouldBe null
            result.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            result.state.lifeTotal(game.player1Id) shouldBe 20
            result.state.lifeTotal(game.player2Id) shouldBe 19
            result.state.pendingDecision shouldBe result.pendingDecision
            result.state.continuationStack.shouldNotBeEmpty()
            result.events.shouldNotBeEmpty()
            result.events.filterIsInstance<LifeChangedEvent>().map { it.playerId to it.newLife } shouldBe
                listOf(game.player2Id to 19)
        }
    }

    /**
     * A game paused on a divided-damage decision, with [then] queued behind it as a gated follow-up
     * so that resuming the decision runs damage first and [then] afterwards — the shape where a
     * later step of one submitted action can fail after earlier steps have already changed state.
     *
     * With [revealTopCardFirst], a second gate in between reveals the top card of player 1's library
     * into their hand, so the attempt emits a `CardsRevealedEvent` before [then] runs.
     */
    private fun gameAwaitingDividedDamage(
        then: Effect,
        revealTopCardFirst: Boolean = false
    ): Pair<ScenarioTestBase.TestGame, DistributeDecision> {
        val builder = scenario().withPlayers()
        if (revealTopCardFirst) builder.withCardInLibrary(1, "Forest")
        val game = builder.build()
        val effectContext = EffectContext(
            sourceId = null,
            controllerId = game.player1Id,
            targets = emptyList()
        )
        val gatedFollowUp = GatedActionContinuation(
            then = then,
            otherwise = null,
            successCriterion = SuccessCriterion.Always,
            snapshot = GatedActionSnapshot(),
            effectContext = effectContext
        )
        val revealGate = GatedActionContinuation(
            then = Patterns.Library.revealTopPutAllMatchingToHand(
                count = DynamicAmount.Fixed(1),
                filter = GameObjectFilter.Any
            ),
            otherwise = null,
            successCriterion = SuccessCriterion.Always,
            snapshot = GatedActionSnapshot(),
            effectContext = effectContext
        )
        val damage = DistributeDamageContinuation(
            sourceId = null,
            controllerId = game.player1Id,
            targets = listOf(game.player2Id)
        )
        game.state = game.state
            .pushContinuation(gatedFollowUp)
            .let { if (revealTopCardFirst) it.pushContinuation(revealGate) else it }
            .suspendForDecision(
                question = { id ->
                    DistributeDecision(
                        id = id,
                        playerId = game.player1Id,
                        prompt = "Divide 1 damage",
                        context = DecisionContext(),
                        totalAmount = 1,
                        targets = listOf(game.player2Id),
                        minPerTarget = 1
                    )
                },
                answer = damage,
            ).state
        return game to game.state.pendingDecision.shouldBeInstanceOf<DistributeDecision>()
    }
}
