package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.mtg.sets.definitions.spm.cards.MultiversalPassage
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

class LiveActionSubmissionTest : ScenarioTestBase() {
    init {
        cardRegistry.register(MultiversalPassage)

        test("ordinary browser actions require their original timeline and rejection does not consume a message id") {
            val game = scenario().withPlayers()
                .withCardInHand(1, "Forest")
                .withCardInHand(1, "Island")
                .build()
            val player = game.player1Id
            val session = newSession(game)
            val firstAction = PlayLand(player, game.findCardsInHand(1, "Forest").single())
            val nextAction = PlayLand(player, game.findCardsInHand(1, "Island").single())
            val oldEpoch = epoch(session, player)
            session.executeClientAction(player, firstAction, "first", oldEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Success>()
            session.executeUndo(player).shouldBeInstanceOf<GameSession.ActionResult.Success>()
            val currentEpoch = epoch(session, player)
            currentEpoch shouldNotBe oldEpoch

            val beforeState = session.getStateForTesting()
            val beforeActions = session.getRecordedActions()
            val beforeIds = session.getLastMessageIdsForPersistence()
            val beforeLogs = session.getLogsForPersistence()
            val beforeCheckpoints = session.getReplayCheckpoints()
            session.executeClientAction(player, nextAction, "retry")
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            session.executeClientAction(player, nextAction, "retry", oldEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            session.executeAiAction(player, nextAction, oldEpoch) shouldBe null
            session.executeAiAction(player, nextAction, null) shouldBe null
            session.getStateForTesting() shouldBe beforeState
            session.getRecordedActions() shouldBe beforeActions
            session.getLastMessageIdsForPersistence() shouldBe beforeIds
            session.getLogsForPersistence() shouldBe beforeLogs
            session.getReplayCheckpoints() shouldBe beforeCheckpoints

            // A same-session reconnect keeps the original snapshot's generation usable.
            session.clearLastSentState(player)
            epoch(session, player) shouldBe currentEpoch
            val accepted = session.executeClientAction(player, nextAction, "retry", currentEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Success>()
            session.getRecordedActions() shouldBe listOf(nextAction)
            session.getLastMessageIdsForPersistence()[player] shouldBe "retry"
            session.executeClientAction(player, nextAction, "retry", currentEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>().reason shouldBe "Duplicate message"
            session.getStateForTesting() shouldBe accepted.state
            session.getRecordedActions() shouldBe listOf(nextAction)
        }

        test("browser and AI adapters record the same canonical actions from equivalent snapshots") {
            val game = scenario().withPlayers().withCardInHand(1, "Multiversal Passage").build()
            val player = game.player1Id
            val browser = newSession(game)
            val ai = newSession(game)
            val browserEpoch = epoch(browser, player)
            val aiEpoch = epoch(ai, player)
            browserEpoch shouldNotBe aiEpoch
            val play = PlayLand(player, game.findCardsInHand(1, "Multiversal Passage").single())
            val browserPause = browser.executeClientAction(player, play, "play", browserEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val aiPause = ai.executeAiAction(player, play, aiEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            browserPause.state shouldBe aiPause.state
            val pending = browserPause.decision.shouldBeInstanceOf<ChooseOptionDecision>()
            val canonical = SubmitDecision(player, OptionChosenResponse(pending.id, pending.options.indexOf("Island")))
            val wire = canonical.copy(response = OptionChosenResponse("$browserEpoch:${pending.id}", pending.options.indexOf("Island")))

            // Both the redundant field and the encoded origin must agree. A raw engine ID is
            // still not a valid browser token even when an epoch is supplied separately.
            browser.executeClientAction(player, wire, "choose", aiEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            browser.executeClientAction(player, canonical, "choose", browserEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            browser.getStateForTesting() shouldBe browserPause.state
            browser.getRecordedActions() shouldBe listOf(play)

            // Legacy decision clients may omit the field because their prefixed ID already
            // carries the originating generation. This never applies to an ordinary action.
            val browserResult = browser.executeClientAction(player, wire, "choose")
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            val aiResult = ai.executeAiAction(player, canonical, aiEpoch)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            browserResult.state shouldBe aiResult.state
            browserResult.events shouldBe aiResult.events
            browser.getRecordedActions() shouldBe listOf(play, canonical)
            ai.getRecordedActions() shouldBe listOf(play, canonical)
            val replay = actionProcessor.process(browserPause.state, canonical).result
            replay.error shouldBe null
            replay.state shouldBe browserResult.state
        }

        test("obsolete decision routing in either transport leaves another player's undo checkpoint intact") {
            val game = scenario().withPlayers().withCardInHand(1, "Multiversal Passage").build()
            val session = newSession(game)
            val owner = game.player1Id
            val other = game.player2Id
            val origin = epoch(session, other)
            val play = PlayLand(owner, game.findCardsInHand(1, "Multiversal Passage").single())
            session.executeClientAction(owner, play, interactionEpoch = origin)
                .shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
            session.isUndoAvailable(owner) shouldBe true
            val before = session.getStateForTesting()
            val beforeIds = session.getLastMessageIdsForPersistence()
            val stale = SubmitDecision(other, OptionChosenResponse("obsolete-routing", 0))
            session.executeAiAction(other, stale, origin) shouldBe null
            session.executeClientAction(
                other,
                stale.copy(response = OptionChosenResponse("$origin:obsolete-routing", 0)),
                "stale-response",
            ).shouldBeInstanceOf<GameSession.ActionResult.Failure>()
            session.getStateForTesting() shouldBe before
            session.getRecordedActions() shouldBe listOf(play)
            session.getLastMessageIdsForPersistence() shouldBe beforeIds
            session.isUndoAvailable(owner) shouldBe true
        }
    }

    private fun epoch(session: GameSession, player: EntityId): String =
        when (val update = session.createStateUpdate(player, emptyList())) {
            is ServerMessage.StateUpdate -> update.interactionEpoch.shouldNotBeNull()
            is ServerMessage.StateDeltaUpdate -> update.interactionEpoch.shouldNotBeNull()
            else -> error("Expected a live state snapshot")
        }

    private fun newSession(game: TestGame): GameSession = GameSession(cardRegistry = cardRegistry).also { session ->
        val firstSocket = mockk<WebSocketSession>(relaxed = true) { every { id } returns "first" }
        val secondSocket = mockk<WebSocketSession>(relaxed = true) { every { id } returns "second" }
        session.injectStateForTesting(game.state, mapOf(
            game.player1Id to PlayerSession(firstSocket, game.player1Id, "First"),
            game.player2Id to PlayerSession(secondSocket, game.player2Id, "Second"),
        ))
    }
}
