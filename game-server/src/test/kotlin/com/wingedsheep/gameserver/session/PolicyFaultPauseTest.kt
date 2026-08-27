package com.wingedsheep.gameserver.session

import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

/**
 * The policy-fault pause is a host incident, not an implicit concession. These checks stay at the
 * GameSession boundary so every input route shares the invariant before web-socket fanout runs.
 */
class PolicyFaultPauseTest : ScenarioTestBase() {

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun startedSession(): Triple<GameSession, EntityId, EntityId> {
        val game = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        val ai = EntityId.of("policy-fault-ai")
        val human = EntityId.of("policy-fault-human")
        game.addPlayer(PlayerSession(mockWs("policy-fault-ai-ws"), ai, "AI"), mapOf("Forest" to 40))
        game.addPlayer(PlayerSession(mockWs("policy-fault-human-ws"), human, "Human"), mapOf("Forest" to 40))
        game.startGame()
        game.keepHand(ai)
        game.keepHand(human)
        return Triple(game, ai, human)
    }

    private fun mulliganSession(): Triple<GameSession, EntityId, EntityId> {
        val game = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        val ai = EntityId.of("policy-fault-mulligan-ai")
        val human = EntityId.of("policy-fault-mulligan-human")
        game.addPlayer(PlayerSession(mockWs("policy-fault-mulligan-ai-ws"), ai, "AI"), mapOf("Forest" to 40))
        game.addPlayer(PlayerSession(mockWs("policy-fault-mulligan-human-ws"), human, "Human"), mapOf("Forest" to 40))
        game.startGame()
        return Triple(game, ai, human)
    }

    init {
        test("reported policy fault pauses without changing state, winner, or recorded game actions") {
            val (game, ai, _) = startedSession()
            val beforeState = requireNotNull(game.getStateForTesting())
            val beforeActions = game.getRecordedActions()

            val first = game.reportPolicyFault(ai, "RESPONSIBLE_POLICY_UNAVAILABLE", "no safe answer")
                as PolicyFaultReport.Paused
            val duplicate = game.reportPolicyFault(ai, "ANOTHER_FAILURE", "must be idempotent")
                as PolicyFaultReport.AlreadyPaused

            game.isPolicyFaultPaused() shouldBe true
            game.getPolicyFaultIncident()?.incidentId shouldBe first.incident.incidentId
            duplicate.incident.incidentId shouldBe first.incident.incidentId
            (game.getStateForTesting() === beforeState) shouldBe true
            game.getRecordedActions() shouldBe beforeActions
            game.isGameOver() shouldBe false
            game.getWinnerId().shouldBeNull()
            game.getAutoPassPlayer().shouldBeNull()

            (game.beginPolicyFaultRetry(first.incident.incidentId) as PolicyFaultRetryStart.Started)
                .incident.retryInProgress shouldBe true
            // This is the retry-dispatch race boundary: timeout/disconnect/ordinary-concede code
            // must still be unable to mutate the authoritative state before the retained AI call
            // returns an accepted action.
            game.playerConcedes(ai)
            (game.getStateForTesting() === beforeState) shouldBe true
            game.getRecordedActions() shouldBe beforeActions
            game.isGameOver() shouldBe false
            game.getWinnerId().shouldBeNull()
            val refault = game.reportPolicyFault(ai, "RESPONSIBLE_POLICY_UNAVAILABLE", "retry also failed")
                as PolicyFaultReport.Paused
            refault.incident.parentIncidentId shouldBe first.incident.incidentId
            game.getPolicyFaultHistory().map { it.incidentId } shouldBe listOf(first.incident.incidentId, refault.incident.incidentId)
            game.isPolicyFaultPaused() shouldBe true
        }

        test("only explicit policy-fault concession enters normal terminal processing and retains origin") {
            val (game, ai, human) = startedSession()
            val incident = (game.reportPolicyFault(ai, "AI_CONTROLLER_FATAL_EXCEPTION", "controller failed")
                as PolicyFaultReport.Paused).incident

            (game.concedePolicyFault(incident.incidentId) as PolicyFaultConcession.Conceded)
                .incident.recovery shouldBe PolicyFaultRecovery.CONCEDE

            game.isGameOver() shouldBe true
            game.getWinnerId() shouldBe human
            game.hasPolicyFaultConcession() shouldBe true
            game.getPolicyFaultHistory().single().incidentId shouldBe incident.incidentId
            game.getPolicyFaultHistory().single().recovery shouldBe PolicyFaultRecovery.CONCEDE
        }

        test("explicit retry permits one retained pregame response and clears only after its state transition") {
            val (game, ai, _) = mulliganSession()
            val before = requireNotNull(game.getStateForTesting())
            val incident = (game.reportPolicyFault(ai, "RESPONSIBLE_POLICY_UNAVAILABLE", "mulligan policy failed")
                as PolicyFaultReport.Paused).incident

            game.beginPolicyFaultRetry(incident.incidentId) shouldBe PolicyFaultRetryStart.Started(
                incident.copy(retryInProgress = true),
            )
            game.isPolicyFaultPaused() shouldBe true
            (game.getStateForTesting() === before) shouldBe true

            // A retry token is capability-bound to this incident; ordinary/stale input cannot
            // smuggle a later invocation through the pause gate.
            game.keepHand(ai) shouldBe GameSession.MulliganActionResult.Failure("Game is paused for policy recovery")
            val result = game.keepHand(ai, incident.incidentId) as GameSession.MulliganActionResult.Success
            result.policyFaultRecovery?.incidentId shouldBe incident.incidentId
            game.isPolicyFaultPaused() shouldBe false
            (game.getStateForTesting() === before) shouldBe false
        }
    }
}
