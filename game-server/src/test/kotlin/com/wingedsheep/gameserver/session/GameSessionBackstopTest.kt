package com.wingedsheep.gameserver.session

import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.GameOverReason
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

/**
 * The two runaway backstops, wired end to end through a real recorded session.
 *
 * The stall threshold is tightened through [GameSession.tightenBackstopsForTesting]. The old replay
 * cap argument remains as a compatibility seam, but canonical recordings deliberately ignore it.
 */
class GameSessionBackstopTest : ScenarioTestBase() {

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    /** A real two-seat recorded game, with the backstops shrunk to test size before it starts. */
    private fun startedGame(guard: GameStallGuard, replayCap: Int): GameSession {
        val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        session.tightenBackstopsForTesting(guard, replayCap)
        val p1 = com.wingedsheep.sdk.model.EntityId.of("backstop-p1")
        val p2 = com.wingedsheep.sdk.model.EntityId.of("backstop-p2")
        session.addPlayer(PlayerSession(mockWs("backstop-ws1"), p1, "Alice"), mapOf("Forest" to 40))
        session.addPlayer(PlayerSession(mockWs("backstop-ws2"), p2, "Bob"), mapOf("Forest" to 40))
        session.startGame()
        session.keepHand(p1)
        session.keepHand(p2)
        return session
    }

    /** Auto-pass until the game ends or [rounds] passes run out. */
    private fun GameSession.autoPass(rounds: Int) {
        repeat(rounds) {
            val state = getStateForTesting() ?: return
            if (state.gameOver) return
            state.priorityPlayerId?.let { executeAutoPass(it) }
        }
    }

    init {
        test("canonical replay recording ignores the former cap and remains exhaustive") {
            val session = startedGame(GameStallGuard(), replayCap = 6)

            session.autoPass(40)

            (session.getRecordedActions().size > 6) shouldBe true
            session.isReplayTruncated() shouldBe false
            session.getReplayFrameCount() shouldBe 1 + session.getRecordedActions().size
            session.isGameOver() shouldBe false

            session.replayRecordingSnapshot().shouldNotBeNull().truncated shouldBe false
        }

        test("a game that stops making progress is ended as a draw that explains itself") {
            // keepHand ×2 are already recorded, so this trips a few auto-passes in.
            val session = startedGame(GameStallGuard(maxActions = 8), replayCap = 10_000)

            session.autoPass(40)

            session.isGameOver() shouldBe true
            // A draw, not a win for whoever happened to hold priority when the clock ran out.
            session.getWinnerId() shouldBe null
            session.getGameOverReason() shouldBe GameOverReason.DRAW
            session.stallMessage().shouldNotBeNull() shouldContain "draw"
            // Nobody is eliminated by a draw — the Free-for-All standings read this order, and a
            // seat listed as eliminated would be reported as having lost a game that nobody lost.
            session.getEliminationOrder() shouldBe emptyList()
        }

        test("an ordinary game is neither truncated nor called stalled") {
            val session = startedGame(GameStallGuard(), replayCap = 10_000)

            session.autoPass(40)

            session.stallMessage() shouldBe null
            session.isReplayTruncated() shouldBe false
        }
    }
}
