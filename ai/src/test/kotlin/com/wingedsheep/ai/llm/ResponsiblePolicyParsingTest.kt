package com.wingedsheep.ai.llm

import com.wingedsheep.ai.llm.decision.handlers.SearchLibraryHandler
import com.wingedsheep.ai.llm.decision.handlers.ChooseTargetsHandler
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ResponsiblePolicyParsingTest : StringSpec({
    val parser = AiResponseParser()

    "mulligan parser preserves a declared no response" {
        parser.parseMulliganChoice("no") shouldBe false
        parser.parseMulliganChoice("yes") shouldBe true
    }

    "library parser fails visibly instead of selecting the first option" {
        val decision = searchDecision(minSelections = 1)

        SearchLibraryHandler().parse("not a valid answer", decision, clientState(), parser)
            .shouldBeNull()
    }

    "library parser exposes the optional fail-to-find choice" {
        val decision = searchDecision(minSelections = 0)
        val response = SearchLibraryHandler().parse("C", decision, clientState(), parser)
            as CardsSelectedResponse

        response.selectedCards.shouldBeEmpty()
    }

    "multi-target parser refuses an incomplete policy answer" {
        val player = EntityId("player")
        val decision = ChooseTargetsDecision(
            id = "targets",
            playerId = player,
            prompt = "Choose two targets",
            context = DecisionContext(),
            targetRequirements = listOf(
                TargetRequirementInfo(index = 0, description = "first"),
                TargetRequirementInfo(index = 1, description = "second"),
            ),
            legalTargets = mapOf(
                0 to listOf(EntityId("a"), EntityId("b")),
                1 to listOf(EntityId("c"), EntityId("d")),
            ),
        )

        ChooseTargetsHandler().parse("A", decision, clientState(), parser).shouldBeNull()
    }
})

private fun searchDecision(minSelections: Int): SearchLibraryDecision = SearchLibraryDecision(
    id = "search",
    playerId = EntityId("player"),
    prompt = "Search",
    context = DecisionContext(),
    options = listOf(EntityId("card-a"), EntityId("card-b")),
    minSelections = minSelections,
    maxSelections = 1,
    cards = emptyMap(),
    filterDescription = "a card",
)

private fun clientState(): ClientGameState {
    val playerId = EntityId("player")
    return ClientGameState(
        viewingPlayerId = playerId,
        cards = emptyMap(),
        zones = emptyList(),
        players = emptyList(),
        currentPhase = Phase.PRECOMBAT_MAIN,
        currentStep = Step.PRECOMBAT_MAIN,
        activePlayerId = playerId,
        priorityPlayerId = playerId,
        turnNumber = 1,
        isGameOver = false,
        winnerId = null,
        combat = null,
    )
}
