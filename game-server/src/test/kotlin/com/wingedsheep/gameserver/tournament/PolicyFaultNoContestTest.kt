package com.wingedsheep.gameserver.tournament

import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A policy-fault terminal host path is held, never converted into standings data. */
class PolicyFaultNoContestTest : FunSpec({
    test("held no-contest leaves every standing and tiebreaker input untouched") {
        val alice = EntityId("alice")
        val bob = EntityId("bob")
        val manager = TournamentManager("policy-fault", listOf(alice to "Alice", bob to "Bob"))
        val match = manager.startNextRound()!!.matches.single()
        match.gameSessionId = "faulted-game"

        val before = manager.getStandingsInfo().associateBy { it.playerId }
        manager.holdPolicyFaultNoContest("faulted-game", "incident-42", "AI_POLICY_PROCESSING_EXCEPTION") shouldBe true
        // Duplicate delivery preserves the same incident rather than materialising a second outcome.
        manager.holdPolicyFaultNoContest("faulted-game", "incident-42", "different-code") shouldBe true
        // A stale ordinary callback and a later lobby abandon must not escape the held path.
        manager.reportMatchResult("faulted-game", alice, winnerLifeRemaining = 20)
        manager.recordAbandon(bob)

        val after = manager.getStandingsInfo().associateBy { it.playerId }
        after shouldBe before
        match.isComplete shouldBe false
        match.isDraw shouldBe false
        match.winnerId shouldBe null
        match.player1GameWins shouldBe 0
        match.player2GameWins shouldBe 0
        match.policyFaultIncidentId shouldBe "incident-42"
        manager.getCurrentRoundResults().single().policyFaultIncidentId shouldBe "incident-42"
    }
})
