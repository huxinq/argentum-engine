package com.wingedsheep.ai.engine

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TrivialDecisionsTest : FunSpec({
    val player = EntityId("player")
    val first = EntityId("first")
    val second = EntityId("second")
    val context = DecisionContext()
    val state = GameState()

    test("validator-backed singleton target is automatic only when cancel is impossible") {
        fun decision(canCancel: Boolean) = ChooseTargetsDecision(
            id = "target",
            playerId = player,
            prompt = "Choose a target",
            context = context,
            targetRequirements = listOf(TargetRequirementInfo(0, "target")),
            legalTargets = mapOf(0 to listOf(first)),
            canCancel = canCancel,
        )

        TrivialDecisions.responseFor(state, decision(canCancel = false))
            .shouldBeInstanceOf<TargetsResponse>()
            .selectedTargets shouldBe mapOf(0 to listOf(first))
        TrivialDecisions.responseFor(state, decision(canCancel = true)) shouldBe null
    }

    test("defaults and suggestions never prove a multi-response decision forced") {
        val damage = AssignDamageDecision(
            id = "damage",
            playerId = player,
            prompt = "Assign damage",
            context = context,
            attackerId = EntityId("attacker"),
            availablePower = 3,
            orderedTargets = listOf(first, second),
            defenderId = null,
            minimumAssignments = mapOf(first to 2, second to 2),
            defaultAssignments = mapOf(first to 2, second to 1),
            hasTrample = false,
            hasDeathtouch = false,
        )
        val mana = SelectManaSourcesDecision(
            id = "mana",
            playerId = player,
            prompt = "Pay {1}",
            context = context,
            availableSources = listOf(
                ManaSourceOption(first, "Mountain", setOf(Color.RED), false),
                ManaSourceOption(second, "Village", setOf(Color.RED), false),
            ),
            requiredCost = "{1}",
            autoPaySuggestion = listOf(first),
            canDecline = true,
        )

        TrivialDecisions.responseFor(state, damage) shouldBe null
        TrivialDecisions.responseFor(state, mana) shouldBe null
    }

    test("cancel and ordering alternatives remain policy choices") {
        val cancelableOption = ChooseOptionDecision(
            id = "option",
            playerId = player,
            prompt = "Choose",
            context = context,
            options = listOf("only option"),
            canCancel = true,
        )
        val orderedCards = SelectCardsDecision(
            id = "cards",
            playerId = player,
            prompt = "Order both",
            context = context,
            options = listOf(first, second),
            minSelections = 2,
            maxSelections = 2,
            ordered = true,
        )
        val repeatedModeCount = ChooseModeDecision(
            id = "mode",
            playerId = player,
            prompt = "Choose modes",
            context = context,
            modes = listOf(ModeOption(0, "Only mode")),
            minModes = 1,
            maxModes = 2,
        )

        TrivialDecisions.responseFor(state, cancelableOption) shouldBe null
        TrivialDecisions.responseFor(state, orderedCards) shouldBe null
        TrivialDecisions.responseFor(state, repeatedModeCount) shouldBe null
    }
})
