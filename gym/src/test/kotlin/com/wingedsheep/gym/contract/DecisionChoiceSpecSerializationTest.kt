package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DecisionChoiceSpecSerializationTest : FunSpec({
    val json = Json { encodeDefaults = true; explicitNulls = false }
    val a = EntityId("a")
    val b = EntityId("b")
    val info = SearchCardInfo("Card", "{1}", "Creature")

    test("every choice-spec subtype round-trips losslessly") {
        val specs: List<DecisionChoiceSpec> = listOf(
            TargetsChoiceSpec(listOf(TargetRequirementInfo(0, "target")), mapOf(0 to listOf(a, b)), true),
            CardsChoiceSpec(listOf(a, b), 0, 2, ordered = true, cardInfo = mapOf(a to info)),
            YesNoChoiceSpec("Do it", "Decline", "hint"),
            BatchYesNoChoiceSpec(3, "All", "None"),
            ModesChoiceSpec(listOf(ModeOption(0, "A")), 1, 1),
            ColorsChoiceSpec(listOf(Color.RED, Color.BLUE)),
            NumberChoiceSpec(0, 5),
            DistributionChoiceSpec(3, listOf(a, b), 0, mapOf(a to 2), true),
            OrderChoiceSpec(listOf(a, b), mapOf(a to info)),
            PilesChoiceSpec(listOf(a, b), 2, listOf("A", "B"), mapOf(a to info)),
            OptionsChoiceSpec(listOf("A"), "search", mapOf(0 to listOf(a)), listOf(OptionMetadata("a")), true),
            ReplacementChoiceSpec(listOf("red"), listOf("blue"), emptyList(), emptyList(), listOf(listOf(0)), 0),
            LibrarySearchChoiceSpec(listOf(a), 0, 1, mapOf(a to info), "a card"),
            LibraryReorderChoiceSpec(listOf(a, b), mapOf(a to info, b to info)),
            DamageAssignmentChoiceSpec(a, 3, listOf(b), null, mapOf(b to 1), mapOf(b to 3), false, false),
            CombatResolutionChoiceSpec(false, emptyList(), emptyList(), emptyList(), emptyList()),
            ManaSourcesChoiceSpec(
                listOf(ManaSourceChoice(a, "Mountain", listOf(Color.RED), false, false, false)),
                "{R}",
                listOf(a),
                true,
                emptyList(),
            ),
            BudgetModesChoiceSpec(2, listOf(BudgetModeOption(1, "A"))),
        )

        specs.forEach { spec ->
            val encoded = json.encodeToString(DecisionChoiceSpec.serializer(), spec)
            json.decodeFromString(DecisionChoiceSpec.serializer(), encoded) shouldBe spec
        }
    }

    test("every decision-response subtype round-trips losslessly") {
        val responses: List<DecisionResponse> = listOf(
            TargetsResponse("id", mapOf(0 to listOf(a))),
            CardsSelectedResponse("id", listOf(a)),
            YesNoResponse("id", true),
            BatchYesNoResponse("id", true, false),
            ModesChosenResponse("id", listOf(0)),
            ColorChosenResponse("id", Color.RED),
            NumberChosenResponse("id", 2),
            DistributionResponse("id", mapOf(a to 2)),
            OrderedResponse("id", listOf(a, b)),
            PilesSplitResponse("id", listOf(listOf(a), listOf(b))),
            OptionChosenResponse("id", 0),
            ReplacementChosenResponse("id", 0, 1),
            BudgetModalResponse("id", listOf(0)),
            DamageAssignmentResponse("id", mapOf(b to 2)),
            ManaSourcesSelectedResponse("id", listOf(a)),
            CombatResolutionResponse("id", emptyList()),
            CancelDecisionResponse("id"),
        )

        responses.forEach { response ->
            val encoded = json.encodeToString(DecisionResponse.serializer(), response)
            json.decodeFromString(DecisionResponse.serializer(), encoded) shouldBe response
        }
    }
})
