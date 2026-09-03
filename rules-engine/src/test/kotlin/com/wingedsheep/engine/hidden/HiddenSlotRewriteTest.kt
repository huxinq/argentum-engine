package com.wingedsheep.engine.hidden

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.InFlightEntityReferences
import com.wingedsheep.engine.core.InFlightReferenceProjector
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class HiddenSlotRewriteTest : ScenarioTestBase() {

    init {
        test("a Mind Rot paused graph pins its discard options but not an unrelated library slot") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Mind Rot")
                .withCardOnBattlefield(1, "Swamp")
                .withCardOnBattlefield(1, "Swamp")
                .withCardOnBattlefield(1, "Swamp")
                .withCardInHand(2, "Grizzly Bears")
                .withCardInHand(2, "Hill Giant")
                .withCardInHand(2, "Craw Wurm")
                .withCardInLibrary(2, "Forest")
                .build()
            game.castSpellTargetingPlayer(1, "Mind Rot", 2).error shouldBe null
            game.resolveStack()

            val source = game.state
            val decision = source.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
            val libraryId = source.getLibrary(game.player2Id).single()

            val pins = HiddenSlotRewrite.identitySensitiveInFlightPins(source)
                .shouldBeInstanceOf<HiddenSlotRewrite.IdentitySensitiveInFlightPins.Complete>()

            decision.options.forEach { pins.entityIds shouldContain it }
            pins.entityIds shouldNotContain libraryId
        }

        test("an incomplete paused projection is the shared whole-state answer") {
            val game = scenario().withPlayers().build()
            val state = game.state.copy(
                pendingDecision = SelectCardsDecision(
                    id = "untraversable",
                    playerId = game.player1Id,
                    prompt = "choose",
                    context = DecisionContext(),
                    options = emptyList(),
                    minSelections = 0,
                    maxSelections = 0,
                ),
            )

            HiddenSlotRewrite.identitySensitiveInFlightPins(
                state,
                object : InFlightReferenceProjector {
                    override fun project(stackObject: ComponentContainer) =
                        InFlightEntityReferences.Projection.Complete(emptySet())

                    override fun project(decision: com.wingedsheep.engine.core.PendingDecision) =
                        InFlightEntityReferences.Projection.Incomplete("test", "forced")

                    override fun project(frame: com.wingedsheep.engine.core.ContinuationFrame) =
                        InFlightEntityReferences.Projection.Complete(emptySet())
                },
            ).shouldBeInstanceOf<HiddenSlotRewrite.IdentitySensitiveInFlightPins.Incomplete>()
                .details shouldBe listOf("could not traverse pending decision test: forced")
        }

        test("a missing or untraversable stack object makes the shared pin answer incomplete") {
            val game = scenario().withPlayers().build()
            val missingStackId = EntityId.of("missing-stack-object")

            HiddenSlotRewrite.identitySensitiveInFlightPins(
                game.state.copy(stack = listOf(missingStackId)),
            ).shouldBeInstanceOf<HiddenSlotRewrite.IdentitySensitiveInFlightPins.Incomplete>()
                .details shouldBe listOf("could not traverse stack[0] missing-stack-object: missing entity")

            val stackId = EntityId.of("untraversable-stack-object")
            val withStack = game.state.copy(
                entities = game.state.entities + (stackId to ComponentContainer.EMPTY),
                stack = listOf(stackId),
            )
            HiddenSlotRewrite.identitySensitiveInFlightPins(
                withStack,
                object : InFlightReferenceProjector {
                    override fun project(stackObject: ComponentContainer) =
                        InFlightEntityReferences.Projection.Incomplete("test", "forced")

                    override fun project(decision: com.wingedsheep.engine.core.PendingDecision) =
                        InFlightEntityReferences.Projection.Complete(emptySet())

                    override fun project(frame: com.wingedsheep.engine.core.ContinuationFrame) =
                        InFlightEntityReferences.Projection.Complete(emptySet())
                },
            ).shouldBeInstanceOf<HiddenSlotRewrite.IdentitySensitiveInFlightPins.Incomplete>()
                .details shouldBe listOf("could not traverse stack[0] test: forced")
        }
    }
}
