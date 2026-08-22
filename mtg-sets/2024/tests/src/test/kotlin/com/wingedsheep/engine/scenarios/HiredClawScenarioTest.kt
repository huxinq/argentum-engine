package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Scenario tests for Hired Claw (BLB #140). */
class HiredClawScenarioTest : ScenarioTestBase() {

    private val counterAbilityId by lazy {
        cardRegistry.requireCard("Hired Claw").activatedAbilities.single().id
    }

    init {
        context("Hired Claw") {
            test("attacking with itself as a Lizard deals 1 damage to the target opponent") {
                val game = clawGame(lands = 0)
                attackAndResolveTrigger(game)

                game.getLifeTotal(2) shouldBe 19
            }

            test("the counter ability requires life loss and can be activated only once each turn") {
                val game = clawGame(lands = 4)
                val claw = game.findPermanent("Hired Claw")!!

                game.execute(
                    ActivateAbility(game.player1Id, claw, counterAbilityId)
                ).error shouldNotBe null

                attackAndResolveTrigger(game)

                game.execute(
                    ActivateAbility(game.player1Id, claw, counterAbilityId)
                ).error shouldBe null
                game.resolveStack()
                game.state.getEntity(claw)?.get<CountersComponent>()
                    ?.getCount(CounterType.PLUS_ONE_PLUS_ONE) shouldBe 1

                game.execute(
                    ActivateAbility(game.player1Id, claw, counterAbilityId)
                ).error shouldNotBe null
            }
        }
    }

    private fun clawGame(lands: Int): TestGame = scenario()
        .withPlayers("Player", "Opponent")
        .withCardOnBattlefield(1, "Hired Claw", summoningSickness = false)
        .withLandsOnBattlefield(1, "Mountain", lands)
        .withLifeTotal(2, 20)
        .withActivePlayer(1)
        .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        .build()

    private fun attackAndResolveTrigger(game: TestGame) {
        game.advanceToPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
        game.declareAttackers(mapOf("Hired Claw" to 2)).error shouldBe null
        val decision = game.state.pendingDecision as? ChooseTargetsDecision
        if (decision != null) {
            game.submitDecision(TargetsResponse(decision.id, mapOf(0 to listOf(game.player2Id))))
        }
        game.resolveStack()
    }
}
