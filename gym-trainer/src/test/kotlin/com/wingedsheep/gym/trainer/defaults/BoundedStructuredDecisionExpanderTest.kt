package com.wingedsheep.gym.trainer.defaults

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class BoundedStructuredDecisionExpanderTest : FunSpec({
    val player = EntityId("player")
    val a = EntityId("a")
    val b = EntityId("b")
    val c = EntityId("c")
    val context = DecisionContext(sourceId = EntityId("source"), sourceName = "Test")
    val cardInfo = SearchCardInfo("Card", "{1}", "Creature")

    fun decisions(): List<PendingDecision> = listOf(
        YesNoDecision("yes-no", player, "Choose", context),
        BatchYesNoDecision("batch", player, "Choose", context, count = 2),
        ChooseNumberDecision("number", player, "Choose", context, 0, 2),
        ChooseColorDecision("color", player, "Choose", context, setOf(Color.RED, Color.BLUE)),
        ChooseOptionDecision("option", player, "Choose", context, listOf("A", "B"), canCancel = true),
        ChooseReplacementDecision(
            "replacement",
            player,
            "Choose",
            context,
            fromOptions = listOf("red", "blue"),
            toOptions = listOf("green", "white"),
            allowedToByFrom = listOf(listOf(0), listOf(1)),
        ),
        ChooseModeDecision(
            "modes",
            player,
            "Choose",
            context,
            modes = listOf(ModeOption(0, "A"), ModeOption(1, "B")),
            minModes = 1,
            maxModes = 2,
        ),
        SelectCardsDecision("cards", player, "Choose", context, listOf(a, b, c), 1, 2),
        SearchLibraryDecision(
            "search",
            player,
            "Choose",
            context,
            options = listOf(a, b),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(a to cardInfo, b to cardInfo),
            filterDescription = "a card",
        ),
        ChooseTargetsDecision(
            "targets",
            player,
            "Choose",
            context,
            targetRequirements = listOf(TargetRequirementInfo(0, "target", 1, 1)),
            legalTargets = mapOf(0 to listOf(a, b)),
            canCancel = true,
        ),
        DistributeDecision("distribution", player, "Choose", context, 2, listOf(a, b)),
        OrderObjectsDecision("order", player, "Choose", context, listOf(a, b, c)),
        ReorderLibraryDecision(
            "reorder",
            player,
            "Choose",
            context,
            cards = listOf(a, b, c),
            cardInfo = mapOf(a to cardInfo, b to cardInfo, c to cardInfo),
        ),
        SplitPilesDecision("piles", player, "Choose", context, listOf(a, b), 2),
        AssignDamageDecision(
            "damage",
            player,
            "Choose",
            context,
            attackerId = a,
            availablePower = 2,
            orderedTargets = listOf(b),
            defenderId = null,
            minimumAssignments = mapOf(b to 1),
            defaultAssignments = mapOf(b to 2),
            hasTrample = false,
            hasDeathtouch = false,
        ),
        CombatResolutionDecision(
            "combat",
            player,
            "Choose",
            context,
            firstStrike = false,
            attackers = emptyList(),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
        ),
        SelectManaSourcesDecision(
            "mana",
            player,
            "Choose",
            context,
            availableSources = listOf(ManaSourceOption(a, "Mountain", setOf(Color.RED), false)),
            requiredCost = "{R}",
            autoPaySuggestion = listOf(a),
            canDecline = true,
        ),
        BudgetModalDecision(
            "budget",
            player,
            "Choose",
            context,
            budget = 2,
            modes = listOf(BudgetModeOption(1, "A"), BudgetModeOption(2, "B")),
        ),
    )

    test("every pending-decision subtype expands deterministically to valid, duplicate-free responses") {
        val state = GameState(initialSeed = 1234L)
        val expander = BoundedStructuredDecisionExpander()

        for (decision in decisions()) {
            val before = state
            val first = expander.expand(state, decision)
            val second = expander.expand(state, decision)

            first.responses.shouldNotBeEmpty()
            first shouldBe second
            first.responses.size shouldBe first.responses.toSet().size
            first.responses.forEach { DecisionValidators.validate(decision, it, state) shouldBe null }
            state shouldBe before
        }
    }

    test("response spaces at or below the cap are exhaustive") {
        val decision = ChooseNumberDecision("small", player, "Choose", context, 0, 3)
        val expansion = BoundedStructuredDecisionExpander(maxResponses = 64).expand(GameState(), decision)

        expansion.isExhaustive.shouldBeTrue()
        expansion.responses.size shouldBe 4
        expansion.estimatedResponseCount shouldBe 4L
    }

    test("response spaces above the cap are deterministic and explicitly non-exhaustive") {
        val decision = ChooseNumberDecision("large", player, "Choose", context, 0, 100)
        val expander = BoundedStructuredDecisionExpander(maxResponses = 64)
        val first = expander.expand(GameState(), decision)
        val second = expander.expand(GameState(), decision)

        first.isExhaustive.shouldBeFalse()
        first.responses.size shouldBe 64
        first.estimatedResponseCount shouldBe 101L
        first shouldBe second
    }
})
