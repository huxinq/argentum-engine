package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.engine.SealedDeckGenerator
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.gameserver.config.AiProperties
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.gameserver.session.SessionRegistry
import com.wingedsheep.gameserver.tournament.llm.LlmCostTracker
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class AiControllerProviderTest : FunSpec({
    test("provider modes are resolved case-insensitively after trimming") {
        val provider = StubProvider(" Search-Teacher ")
        val registry = AiControllerProviderRegistry(listOf(provider))

        registry["search-teacher"] shouldBe provider
        registry["SEARCH-TEACHER"] shouldBe provider
        registry.supportedModes() shouldBe setOf("engine", "llm", "search-teacher")
    }

    test("blank provider modes fail during registry construction") {
        shouldThrow<IllegalArgumentException> {
            AiControllerProviderRegistry(listOf(StubProvider("  ")))
        }
    }

    test("providers cannot replace built-in modes") {
        shouldThrow<IllegalArgumentException> {
            AiControllerProviderRegistry(listOf(StubProvider("LLM")))
        }
    }

    test("duplicate provider modes fail instead of depending on bean order") {
        shouldThrow<IllegalArgumentException> {
            AiControllerProviderRegistry(listOf(StubProvider("custom"), StubProvider("CUSTOM")))
        }
    }

    test("an external mode is usable without an LLM key and receives honest placeholder context") {
        val provider = CapturingProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(provider, sessions = sessions)

        manager.isEnabled shouldBe true
        manager.createAiIdentity()

        provider.contexts.size shouldBe 1
        provider.contexts.single().gameSessionId shouldBe null
        provider.contexts.single().snapshot() shouldBe null
        sessions.destroy()
    }

    test("a game-attached external controller receives the session's consistent runtime snapshot") {
        val expected = AiRuntimeSnapshot(
            state = mockk<GameState>(),
            replayHistory = AiReplayHistory.Complete(
                setup = mockk<ReplaySetup>(),
                actions = emptyList(),
                yields = emptyList(),
            ),
        )
        val game = mockk<GameSession>(relaxed = true) {
            every { sessionId } returns "game-1"
            every { getAiRuntimeSnapshot() } returns expected
        }
        val deckGenerator = mockk<SealedDeckGenerator> {
            every { generate() } returns mapOf("Mountain" to 40)
        }
        val provider = CapturingProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(provider, sessions, deckGenerator)

        manager.createAiOpponent(
            gameSession = game,
            onActionReady = { _, _ -> },
            onMulliganKeep = { _ -> },
            onMulliganTake = { _ -> },
            onBottomCards = { _, _ -> },
        )

        provider.contexts.size shouldBe 1
        provider.contexts.single().gameSessionId shouldBe "game-1"
        provider.contexts.single().snapshot() shouldBe expected
        provider.controller.lastDeck shouldBe mapOf("Mountain" to 40)
        sessions.destroy()
    }

    test("a model override cannot bypass the LLM credential gate when creating an identity") {
        val provider = StubProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(provider, sessions)

        shouldThrow<IllegalArgumentException> {
            manager.createAiIdentity(modelOverride = "openai/test-model")
        }.message shouldBe "A per-player LLM model override requires an API key"
        sessions.destroy()
    }

    test("a credentialed model override deliberately selects the built-in LLM") {
        val provider = CapturingProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(
            provider,
            sessions,
            aiProperties = AiProperties(
                enabled = true,
                mode = provider.mode,
                apiKey = "test-key",
            ),
        )

        val identity = manager.createAiIdentity(modelOverride = "openai/test-model")

        identity.aiModelOverride shouldBe "openai/test-model"
        provider.contexts shouldBe emptyList()
        sessions.destroy()
    }

    test("a restored model override cannot bypass the LLM credential gate") {
        val provider = StubProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(provider, sessions)
        val identity = PlayerIdentity(
            token = "restored-ai",
            playerId = EntityId.of("restored-ai"),
            playerName = "Restored AI",
            isAi = true,
            aiModelOverride = "openai/test-model",
        )

        shouldThrow<IllegalArgumentException> {
            manager.rehydrateAiIdentity(identity)
        }.message shouldBe "A per-player LLM model override requires an API key"
        sessions.destroy()
    }

    test("a model override cannot bypass the LLM credential gate when wiring a game") {
        val provider = StubProvider("search-teacher")
        val sessions = SessionRegistry()
        val manager = manager(provider, sessions)
        val aiPlayerId = EntityId.of("match-ai")
        sessions.preRegisterIdentity(
            PlayerIdentity(
                token = "match-ai",
                playerId = aiPlayerId,
                playerName = "Match AI",
                isAi = true,
                aiModelOverride = "openai/test-model",
            )
        )
        val game = mockk<GameSession>(relaxed = true) {
            every { sessionId } returns "game-credential-gate"
        }

        shouldThrow<IllegalArgumentException> {
            manager.wireAiForGame(
                gameSession = game,
                aiPlayerId = aiPlayerId,
                deckList = null,
                onActionReady = { _, _ -> },
                onMulliganKeep = { _ -> },
                onMulliganTake = { _ -> },
                onBottomCards = { _, _ -> },
            )
        }.message shouldBe "A per-player LLM model override requires an API key"
        sessions.destroy()
    }
})

private fun manager(
    provider: AiControllerProvider,
    sessions: SessionRegistry,
    deckGenerator: SealedDeckGenerator = mockk(relaxed = true),
    aiProperties: AiProperties = AiProperties(enabled = true, mode = provider.mode),
): AiGameManager {
    val properties = GameProperties(ai = aiProperties)
    return AiGameManager(
        gameProperties = properties,
        sessionRegistry = sessions,
        deckGenerator = deckGenerator,
        cardRegistry = mockk<CardRegistry>(relaxed = true),
        llmCostTracker = LlmCostTracker(),
        aiInsightService = AiInsightService(GameProperties()),
        controllerProviders = listOf(provider),
    )
}

private class StubProvider(override val mode: String) : AiControllerProvider {
    override fun create(context: AiControllerContext): AiPlayerController = StubController
}

private class CapturingProvider(override val mode: String) : AiControllerProvider {
    val contexts = mutableListOf<AiControllerContext>()
    val controller = RecordingController()

    override fun create(context: AiControllerContext): AiPlayerController {
        contexts += context
        return controller
    }
}

private data object StubController : AiPlayerController {
    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = error("not used")

    override fun decideMulligan(mulliganMessage: MulliganInfo): Boolean = error("not used")
    override fun chooseBottomCards(message: BottomCardsInfo): List<EntityId> = error("not used")
    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) = Unit
    override fun chooseDraftPick(
        pack: List<CardSummary>,
        pickedSoFar: List<CardSummary>,
        packNumber: Int,
        pickNumber: Int,
        picksRequired: Int,
        passDirection: String,
    ): List<String> = error("not used")

    override fun chooseWinstonAction(
        pileCards: List<CardSummary>,
        pileIndex: Int,
        pileSizes: List<Int>,
        pickedSoFar: List<CardSummary>,
    ): Boolean = error("not used")

    override fun chooseGridDraftPick(
        grid: List<CardSummary?>,
        availableSelections: List<String>,
        pickedSoFar: List<CardSummary>,
    ): String = error("not used")
}

private class RecordingController : AiPlayerController by StubController {
    var lastDeck: Map<String, Int>? = null

    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) {
        lastDeck = deckList
    }
}
