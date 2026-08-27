package com.wingedsheep.ai.engine

import com.wingedsheep.ai.ResponsiblePolicyUnavailableException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EngineAiPlayerControllerPolicyBoundaryTest : StringSpec({
    "missing authoritative state refuses instead of inventing a priority pass" {
        val playerId = EntityId.of("engine-ai")
        val registry = CardRegistry().apply { register(PredefinedTokens.allTokens) }
        val controller = EngineAiPlayerController(registry, playerId, gameStateProvider = { null })

        val error = shouldThrow<ResponsiblePolicyUnavailableException> {
            controller.chooseAction(
                state = clientState(playerId),
                legalActions = emptyList(),
                pendingDecision = null,
                recentGameLog = emptyList(),
            )
        }

        error.choiceKind shouldBe "PRIORITY_ACTION"
        error.message shouldContain "authoritative game state unavailable"
    }
})

private fun clientState(playerId: EntityId): ClientGameState = ClientGameState(
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
