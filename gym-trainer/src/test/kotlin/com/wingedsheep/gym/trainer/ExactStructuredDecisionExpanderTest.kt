package com.wingedsheep.gym.trainer.defaults

import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpansion
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.asClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExactStructuredDecisionExpanderTest : FunSpec({

    val playerId = EntityId.of("player")
    val first = EntityId.of("first")
    val second = EntityId.of("second")
    val third = EntityId.of("third")
    val fourth = EntityId.of("fourth")
    val fifth = EntityId.of("fifth")
    val state = GameState(turnOrder = listOf(playerId))

    fun targetDecision(
        id: String,
        legalTargets: List<EntityId>,
        minTargets: Int = 1,
        maxTargets: Int = 1,
        canCancel: Boolean = false
    ) = ChooseTargetsDecision(
        id = id,
        playerId = playerId,
        prompt = "Choose targets",
        context = DecisionContext(),
        targetRequirements = listOf(
            TargetRequirementInfo(0, "target", minTargets = minTargets, maxTargets = maxTargets)
        ),
        legalTargets = mapOf(0 to legalTargets),
        canCancel = canCancel
    )

    test("required single-target expansion is complete") {
        val decision = targetDecision("targets", listOf(first, second))

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.shouldContainExactly(
            TargetsResponse("targets", mapOf(0 to listOf(first))),
            TargetsResponse("targets", mapOf(0 to listOf(second)))
        )
        expansion.responses.forEach { response ->
            response.asClue {
                DecisionValidators.validate(decision, response, state) shouldBe null
            }
        }
    }

    // "Up to one target" is as finite as "exactly one target" — the extra alternative is declining,
    // which is an empty selection for the requirement, not a cancellation of the whole decision.
    test("optional single-target expansion offers the empty selection") {
        val decision = targetDecision("optional", listOf(first, second), minTargets = 0)

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.shouldContainExactly(
            TargetsResponse("optional", mapOf(0 to emptyList())),
            TargetsResponse("optional", mapOf(0 to listOf(first))),
            TargetsResponse("optional", mapOf(0 to listOf(second)))
        )
        expansion.responses.forEach { response ->
            response.asClue {
                DecisionValidators.validate(decision, response, state) shouldBe null
            }
        }
    }

    // Cancelling a cast-time decision rewinds to the priority state that offered the cast, so a
    // cancel edge would be a transposition back onto the search node's own ancestor rather than an
    // alternative within the decision.
    test("cancellation is never offered as a response") {
        val decision = targetDecision("cancellable", listOf(first, second), canCancel = true)

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.none { it is CancelDecisionResponse } shouldBe true
        expansion.responses.size shouldBe 2
    }

    test("duplicate legal targets collapse to one response each") {
        val decision = targetDecision("duplicates", listOf(first, second, first))

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.shouldContainExactly(
            TargetsResponse("duplicates", mapOf(0 to listOf(first))),
            TargetsResponse("duplicates", mapOf(0 to listOf(second)))
        )
    }

    test("variable-cardinality target expansion is explicitly unsupported") {
        val decision = targetDecision("many-targets", listOf(first, second, third), minTargets = 0, maxTargets = 2)

        ExactStructuredDecisionExpander.expand(state, decision) shouldBe
            StructuredDecisionExpansion.Unsupported
    }

    test("multi-requirement target expansion is explicitly unsupported") {
        val decision = ChooseTargetsDecision(
            id = "two-requirements",
            playerId = playerId,
            prompt = "Choose targets",
            context = DecisionContext(),
            targetRequirements = listOf(
                TargetRequirementInfo(0, "first target"),
                TargetRequirementInfo(1, "second target")
            ),
            legalTargets = mapOf(0 to listOf(first), 1 to listOf(second))
        )

        ExactStructuredDecisionExpander.expand(state, decision) shouldBe
            StructuredDecisionExpansion.Unsupported
    }

    // An empty Complete is unsearchable, so a supported family with nothing legal to say hands the
    // decision back to the caller's resolver instead of claiming an exhaustive empty response set.
    test("supported family with no legal response reports unsupported") {
        val decision = targetDecision("no-targets", emptyList())

        ExactStructuredDecisionExpander.expand(state, decision) shouldBe
            StructuredDecisionExpansion.Unsupported
    }

    test("small object ordering expands every validator-accepted permutation deterministically") {
        val decision = OrderObjectsDecision(
            id = "order",
            playerId = playerId,
            prompt = "Order objects",
            context = DecisionContext(),
            objects = listOf(first, second, third)
        )

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.shouldContainExactly(
            OrderedResponse("order", listOf(first, second, third)),
            OrderedResponse("order", listOf(first, third, second)),
            OrderedResponse("order", listOf(second, first, third)),
            OrderedResponse("order", listOf(second, third, first)),
            OrderedResponse("order", listOf(third, first, second)),
            OrderedResponse("order", listOf(third, second, first))
        )
        expansion.responses.forEach { response ->
            response.asClue {
                DecisionValidators.validate(decision, response, state) shouldBe null
            }
        }
    }

    test("small library reorder expands every validator-accepted permutation") {
        val decision = ReorderLibraryDecision(
            id = "reorder",
            playerId = playerId,
            prompt = "Reorder library",
            context = DecisionContext(),
            cards = listOf(first, second),
            cardInfo = emptyMap()
        )

        val expansion = ExactStructuredDecisionExpander.expand(state, decision)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()

        expansion.responses.shouldContainExactly(
            OrderedResponse("reorder", listOf(first, second)),
            OrderedResponse("reorder", listOf(second, first))
        )
        expansion.responses.forEach { response ->
            response.asClue {
                DecisionValidators.validate(decision, response, state) shouldBe null
            }
        }
    }

    test("ordering materializes exactly through the response ceiling and not beyond it") {
        val atCeiling = OrderObjectsDecision(
            id = "four-objects",
            playerId = playerId,
            prompt = "Order objects",
            context = DecisionContext(),
            objects = listOf(first, second, third, fourth)
        )
        val overCeiling = ReorderLibraryDecision(
            id = "five-cards",
            playerId = playerId,
            prompt = "Reorder library",
            context = DecisionContext(),
            cards = listOf(first, second, third, fourth, fifth),
            cardInfo = emptyMap()
        )

        val expansion = ExactStructuredDecisionExpander.expand(state, atCeiling)
            .shouldBeInstanceOf<StructuredDecisionExpansion.Complete>()
        expansion.responses.size shouldBe 24
        expansion.responses.toSet().size shouldBe 24
        expansion.responses.forEach { response ->
            DecisionValidators.validate(atCeiling, response, state) shouldBe null
        }
        ExactStructuredDecisionExpander.expand(state, overCeiling) shouldBe
            StructuredDecisionExpansion.Unsupported
    }

    test("duplicate ordering IDs are unsupported rather than conflated into fewer permutations") {
        val duplicateObjects = OrderObjectsDecision(
            id = "duplicate-objects",
            playerId = playerId,
            prompt = "Order objects",
            context = DecisionContext(),
            objects = listOf(first, second, first)
        )
        val duplicateCards = ReorderLibraryDecision(
            id = "duplicate-cards",
            playerId = playerId,
            prompt = "Reorder library",
            context = DecisionContext(),
            cards = listOf(first, second, first),
            cardInfo = emptyMap()
        )

        ExactStructuredDecisionExpander.expand(state, duplicateObjects) shouldBe
            StructuredDecisionExpansion.Unsupported
        ExactStructuredDecisionExpander.expand(state, duplicateCards) shouldBe
            StructuredDecisionExpansion.Unsupported
    }

    test("degenerate unique orderings retain their single validator-approved response") {
        val emptyObjects = OrderObjectsDecision(
            id = "empty-order",
            playerId = playerId,
            prompt = "Order objects",
            context = DecisionContext(),
            objects = emptyList()
        )
        val oneCard = ReorderLibraryDecision(
            id = "one-card",
            playerId = playerId,
            prompt = "Reorder library",
            context = DecisionContext(),
            cards = listOf(first),
            cardInfo = emptyMap()
        )

        ExactStructuredDecisionExpander.expand(state, emptyObjects) shouldBe
            StructuredDecisionExpansion.Complete(listOf(OrderedResponse("empty-order", emptyList())))
        ExactStructuredDecisionExpander.expand(state, oneCard) shouldBe
            StructuredDecisionExpansion.Complete(listOf(OrderedResponse("one-card", listOf(first))))
    }

    test("unrelated structured family remains unsupported") {
        val decision = SplitPilesDecision(
            id = "piles",
            playerId = playerId,
            prompt = "Split piles",
            context = DecisionContext(),
            cards = listOf(first, second),
            numberOfPiles = 2
        )

        ExactStructuredDecisionExpander.expand(state, decision) shouldBe
            StructuredDecisionExpansion.Unsupported
    }
})
