package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.mtg.sets.definitions.spm.cards.MultiversalPassage
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

/** Live transport freshness survives undo while engine snapshots keep deterministic routing IDs. */
class UndoDecisionFreshnessTest : ScenarioTestBase() {
    init {
        cardRegistry.register(MultiversalPassage)

        test("a reply retained before undo cannot answer another land's live decision") {
            val game = scenario().withPlayers()
                .withCardInHand(1, "Multiversal Passage")
                .withCardInHand(1, "Multiversal Passage")
                .build()
            val initial = game.state
            val lands = game.findCardsInHand(1, "Multiversal Passage")
            val player = game.player1Id
            val session = newSession(game)

            val first = session.executeAction(player, PlayLand(player, lands[0]), "land-a")
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val full = session.createStateUpdate(player, first.events)
                .shouldBeInstanceOf<ServerMessage.StateUpdate>()
            val oldPrompt = full.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            val oldResponse = OptionChosenResponse(oldPrompt.id, oldPrompt.options.indexOf("Island"))
            oldResponse.optionIndex shouldNotBe -1

            // Resending the same outstanding prompt, including a reconnect's full snapshot,
            // must keep its token usable; delivery alone does not create a new decision.
            session.createStateUpdate(player, emptyList())
                .shouldBeInstanceOf<ServerMessage.StateDeltaUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>().id shouldBe oldPrompt.id
            session.clearLastSentState(player)
            session.createStateUpdate(player, emptyList())
                .shouldBeInstanceOf<ServerMessage.StateUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>().id shouldBe oldPrompt.id

            session.isUndoAvailable(player) shouldBe true
            session.executeUndo(player).shouldBeInstanceOf<GameSession.ActionResult.Success>()
            session.getStateForTesting() shouldBe initial
            session.getRecordedActions() shouldBe emptyList()

            val secondAction = PlayLand(player, lands[1])
            val second = session.executeAction(player, secondAction, "land-b")
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val nextPrompt = session.createStateUpdate(player, second.events)
                .shouldBeInstanceOf<ServerMessage.StateDeltaUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            val beforeRejected = session.getStateForTesting()!!
            val recordedBefore = session.getRecordedActions()
            val logsBefore = session.getLogsForPersistence()
            val checkpointsBefore = session.getReplayCheckpoints()
            session.isUndoAvailable(player) shouldBe true

            // Undo restores the engine counter: the two different lands intentionally reuse
            // the same engine handle. The live boundary must distinguish their prompts.
            first.state.pendingDecision!!.id shouldBe second.state.pendingDecision!!.id
            val rejected = session.executeClientAction(
                player, SubmitDecision(player, oldResponse), "choice-retry"
            )
            withClue("A delayed response for land A must not select land B's type") {
                rejected.shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            }
            session.getStateForTesting() shouldBe beforeRejected
            session.getRecordedActions() shouldBe recordedBefore
            session.getLogsForPersistence() shouldBe logsBefore
            session.getReplayCheckpoints() shouldBe checkpointsBefore
            session.isUndoAvailable(player) shouldBe true
            nextPrompt.id shouldNotBe oldPrompt.id

            // Reuse the rejected request's message ID: rejection must not consume idempotency.
            val freshResponse = OptionChosenResponse(nextPrompt.id, nextPrompt.options.indexOf("Island"))
            val accepted = session.executeClientAction(
                player, SubmitDecision(player, freshResponse), "choice-retry"
            ).shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            accepted.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            accepted.state.projectedState.hasSubtype(lands[1], "Island") shouldBe true

            // The recorded input retains the deterministic engine ID. Reconstructing from
            // the same checkpoint requires no live token or session epoch.
            val enginePlay = actionProcessor.process(initial, secondAction).result
            enginePlay.error shouldBe null
            enginePlay.state shouldBe beforeRejected
            enginePlay.events shouldBe second.events
            val engineResponse = SubmitDecision(
                player, freshResponse.copy(decisionId = enginePlay.state.pendingDecision!!.id)
            )
            session.getRecordedActions() shouldBe listOf(secondAction, engineResponse)
            val replay = actionProcessor.process(enginePlay.state, engineResponse).result
            replay.error shouldBe null
            replay.state shouldBe accepted.state
            replay.events shouldBe accepted.events
            actionProcessor.process(enginePlay.state, engineResponse).result shouldBe replay
        }

        test("session tokens isolate equal engine snapshots and preserve trusted engine-ID consumers") {
            val game = scenario().withPlayers()
                .withCardInHand(1, "Multiversal Passage")
                .build()
            val player = game.player1Id
            val land = game.findCardsInHand(1, "Multiversal Passage").single()
            val firstSession = newSession(game)
            val secondSession = newSession(game)
            val action = PlayLand(player, land)
            val first = firstSession.executeAction(player, action)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val second = secondSession.executeAction(player, action)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            first.state shouldBe second.state
            val raw = first.state.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            second.state.pendingDecision!!.id shouldBe raw.id
            val firstPrompt = firstSession.createStateUpdate(player, first.events)
                .shouldBeInstanceOf<ServerMessage.StateUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            val secondPrompt = secondSession.createStateUpdate(player, second.events)
                .shouldBeInstanceOf<ServerMessage.StateUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            firstPrompt.id shouldNotBe secondPrompt.id
            firstPrompt.id shouldNotBe raw.id

            firstSession.executeUndo(game.player2Id)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            firstSession.createStateUpdate(player, emptyList())
                .shouldBeInstanceOf<ServerMessage.StateDeltaUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>().id shouldBe firstPrompt.id
            firstSession.isUndoAvailable(player) shouldBe true

            val rawResponse = SubmitDecision(player, OptionChosenResponse(raw.id, raw.options.indexOf("Island")))
            firstSession.executeClientAction(player, rawResponse)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            firstSession.getStateForTesting() shouldBe first.state
            firstSession.getRecordedActions() shouldBe listOf(action)

            val trustedPrompt = firstSession.createStateUpdate(player, emptyList(), useEngineDecisionIds = true)
                .shouldBeInstanceOf<ServerMessage.StateDeltaUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            trustedPrompt.id shouldBe raw.id
            firstSession.createStateUpdate(player, emptyList())
                .shouldBeInstanceOf<ServerMessage.StateDeltaUpdate>()
                .pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>().id shouldBe firstPrompt.id

            val trustedResult = firstSession.executeAction(player, rawResponse)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val replay = actionProcessor.process(first.state, rawResponse).result
            replay.error shouldBe null
            trustedResult.state shouldBe replay.state
            trustedResult.events shouldBe replay.events
            firstSession.getRecordedActions() shouldBe listOf(action, rawResponse)
        }
    }

    private fun newSession(game: TestGame): GameSession {
        val session = GameSession(cardRegistry = cardRegistry)
        val ws1 = mockk<WebSocketSession>(relaxed = true) { every { id } returns "ws1" }
        val ws2 = mockk<WebSocketSession>(relaxed = true) { every { id } returns "ws2" }
        session.injectStateForTesting(
            game.state,
            mapOf(
                game.player1Id to PlayerSession(ws1, game.player1Id, "Player1"),
                game.player2Id to PlayerSession(ws2, game.player2Id, "Player2")
            )
        )
        return session
    }
}
