package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Scenario tests for Hexing Squelcher (ECL #145). */
class HexingSquelcherScenarioTest : ScenarioTestBase() {

    init {
        context("Hexing Squelcher") {
            test("the Squelcher spell itself cannot be countered") {
                val game = scenario()
                    .withPlayers("Player", "Opponent")
                    .withCardInHand(1, "Hexing Squelcher")
                    .withLandsOnBattlefield(1, "Mountain", 2)
                    .withCardInHand(2, "Cancel")
                    .withLandsOnBattlefield(2, "Island", 3)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpell(1, "Hexing Squelcher").error shouldBe null
                game.passPriority()
                game.castSpellTargetingStackSpell(2, "Cancel", "Hexing Squelcher").error shouldBe null
                game.resolveStack()

                game.isOnBattlefield("Hexing Squelcher") shouldBe true
            }

            test("spells its controller casts cannot be countered") {
                val game = scenario()
                    .withPlayers("Player", "Opponent")
                    .withCardOnBattlefield(1, "Hexing Squelcher")
                    .withCardInHand(1, "Shock")
                    .withLandsOnBattlefield(1, "Mountain", 1)
                    .withCardInHand(2, "Cancel")
                    .withLandsOnBattlefield(2, "Island", 3)
                    .withLifeTotal(2, 20)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                game.castSpellTargetingPlayer(1, "Shock", 2).error shouldBe null
                game.passPriority()
                game.castSpellTargetingStackSpell(2, "Cancel", "Shock").error shouldBe null
                game.resolveStack()

                game.getLifeTotal(2) shouldBe 18
            }

            test("another creature it controls gets ward by paying 2 life") {
                val game = wardGame("Grizzly Bears")

                game.resolveStack()

                val decision = game.getPendingDecision() as? YesNoDecision
                    ?: error("expected ward life payment decision")
                decision.playerId shouldBe game.player2Id
            }

            test("its own printed ward also asks the opponent to pay 2 life") {
                val game = wardGame("Hexing Squelcher")

                game.resolveStack()

                val decision = game.getPendingDecision() as? YesNoDecision
                    ?: error("expected ward life payment decision")
                decision.playerId shouldBe game.player2Id
            }
        }
    }

    private fun wardGame(targetName: String): TestGame {
        val game = scenario()
            .withPlayers("Player", "Opponent")
            .withCardOnBattlefield(1, "Hexing Squelcher")
            .withCardOnBattlefield(1, "Grizzly Bears")
            .withCardInHand(2, "Shock")
            .withLandsOnBattlefield(2, "Mountain", 1)
            .withActivePlayer(2)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()
        game.castSpell(2, "Shock", game.findPermanent(targetName)!!).error shouldBe null
        return game
    }
}
