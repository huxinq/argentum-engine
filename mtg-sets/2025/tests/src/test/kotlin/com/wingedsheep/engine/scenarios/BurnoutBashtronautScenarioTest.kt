package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.mechanics.speed.SpeedService
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Speed
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Scenario tests for Burnout Bashtronaut (DFT #115). */
class BurnoutBashtronautScenarioTest : ScenarioTestBase() {

    private val projector = StateProjector()
    private val pumpAbilityId by lazy {
        cardRegistry.requireCard("Burnout Bashtronaut").activatedAbilities.single().id
    }

    init {
        context("Burnout Bashtronaut") {
            test("starts speed and gains double strike only at max speed") {
                val game = bashtronautGame()
                val bashtronaut = game.findPermanent("Burnout Bashtronaut")!!

                game.state.speed(game.player1Id) shouldBe Speed.STARTING
                projector.project(game.state).hasKeyword(bashtronaut, Keyword.MENACE) shouldBe true
                projector.project(game.state).hasKeyword(bashtronaut, Keyword.DOUBLE_STRIKE) shouldBe false

                game.state = SpeedService.set(game.state, game.player1Id, Speed.MAX, "test").first
                projector.project(game.state).hasKeyword(bashtronaut, Keyword.DOUBLE_STRIKE) shouldBe true
            }

            test("its mana ability gives it +1/+0 until end of turn") {
                val game = bashtronautGame(lands = 2)
                val bashtronaut = game.findPermanent("Burnout Bashtronaut")!!

                game.execute(
                    ActivateAbility(
                        playerId = game.player1Id,
                        sourceId = bashtronaut,
                        abilityId = pumpAbilityId
                    )
                ).error shouldBe null
                game.resolveStack()

                projector.project(game.state).getPower(bashtronaut) shouldBe 2
                projector.project(game.state).getToughness(bashtronaut) shouldBe 1
            }
        }
    }

    private fun bashtronautGame(lands: Int = 0): TestGame {
        val builder = scenario()
            .withPlayers("Player1", "Player2")
            .withCardOnBattlefield(1, "Burnout Bashtronaut", summoningSickness = false)
        repeat(12) {
            builder.withCardInLibrary(1, "Mountain")
            builder.withCardInLibrary(2, "Mountain")
        }
        if (lands > 0) builder.withLandsOnBattlefield(1, "Mountain", lands)
        return builder
            .withActivePlayer(1)
            .inPhase(Phase.BEGINNING, Step.UPKEEP)
            .build()
            .also { it.passUntilPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN) }
    }
}
