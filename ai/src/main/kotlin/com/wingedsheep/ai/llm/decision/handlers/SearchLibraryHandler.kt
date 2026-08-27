package com.wingedsheep.ai.llm.decision.handlers

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.ai.llm.AiResponseParser
import com.wingedsheep.ai.llm.GameStateFormatter
import com.wingedsheep.ai.llm.decision.AiDecisionHandler
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.sdk.model.EntityId
import kotlin.reflect.KClass

class SearchLibraryHandler : AiDecisionHandler<SearchLibraryDecision> {
    override val decisionType: KClass<SearchLibraryDecision> = SearchLibraryDecision::class

    override fun autoResolve(decision: SearchLibraryDecision): DecisionResponse {
        throw UnsupportedOperationException()
    }

    override fun format(
        sb: StringBuilder,
        decision: SearchLibraryDecision,
        state: ClientGameState,
        labels: Map<EntityId, String>
    ) {
        sb.appendLine("Search library (${decision.filterDescription}):")
        sb.appendLine("Select ${decision.minSelections}-${decision.maxSelections} card(s):")
        for ((j, eid) in decision.options.withIndex()) {
            val info = decision.cards[eid]
            val name = info?.name ?: "Unknown"
            val cost = info?.manaCost ?: ""
            val type = info?.typeLine ?: ""
            sb.appendLine("  [${GameStateFormatter.actionLetter(j)}] $name $cost — $type")
        }
        if (decision.minSelections == 0) {
            sb.appendLine("  [${GameStateFormatter.actionLetter(decision.options.size)}] Fail to find (select nothing)")
        }
    }

    override fun parse(
        response: String,
        decision: SearchLibraryDecision,
        state: ClientGameState,
        parser: AiResponseParser
    ): DecisionResponse? {
        val cleaned = response.trim().uppercase()
        val exactLetterList = Regex(
            """^(?:\[[A-Z]{1,2}]|[A-Z]{1,2})(?:\s*,\s*(?:\[[A-Z]{1,2}]|[A-Z]{1,2}))*$"""
        )
        if (!exactLetterList.matches(cleaned)) return null

        val failToFindIndex = decision.options.size
        val maxIndex = if (decision.minSelections == 0) failToFindIndex else decision.options.lastIndex
        val indices = Regex("""[A-Z]{1,2}""").findAll(cleaned).map { match ->
            GameStateFormatter.letterToIndex(match.value)
        }.toList()
        if (indices.any { it == null || it !in 0..maxIndex }) return null
        val selectedIndices = indices.filterNotNull().distinct()

        if (failToFindIndex in selectedIndices) {
            if (decision.minSelections != 0 || selectedIndices.size != 1) return null
            return CardsSelectedResponse(decisionId = decision.id, selectedCards = emptyList())
        }

        if (selectedIndices.size !in decision.minSelections..decision.maxSelections ||
            selectedIndices.any { it !in decision.options.indices }
        ) return null

        return CardsSelectedResponse(
            decisionId = decision.id,
            selectedCards = selectedIndices.map { decision.options[it] },
        )
    }

}
