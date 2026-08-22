package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.blb.cards.HiredClaw
import com.wingedsheep.mtg.sets.definitions.otj.cards.SlickshotShowOff
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Scenario tests for Slickshot Show-Off (OTJ #146). */
class SlickshotShowOffScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(SlickshotShowOff, HiredClaw))
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("has flying and haste and gets +2 power when its controller casts a noncreature spell") {
        val driver = newDriver()
        val player = driver.player1
        val showOff = driver.putCreatureOnBattlefield(player, "Slickshot Show-Off")
        val shock = driver.putCardInHand(player, "Shock")
        driver.giveMana(player, Color.RED, 1)

        driver.state.projectedState.hasKeyword(showOff, Keyword.FLYING) shouldBe true
        driver.state.projectedState.hasKeyword(showOff, Keyword.HASTE) shouldBe true
        driver.castSpell(player, shock, listOf(driver.player2)).isSuccess shouldBe true
        driver.bothPass() // resolve the cast trigger above Shock

        driver.state.projectedState.getPower(showOff) shouldBe 3
    }

    test("casting a creature spell does not trigger the power bonus") {
        val driver = newDriver()
        val player = driver.player1
        val showOff = driver.putCreatureOnBattlefield(player, "Slickshot Show-Off")
        val creature = driver.putCardInHand(player, "Hired Claw")
        driver.giveMana(player, Color.RED, 1)

        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.stackSize shouldBe 1
        driver.bothPass()

        driver.state.projectedState.getPower(showOff) shouldBe 1
    }

    test("may be plotted from hand for {1}{R}") {
        val driver = newDriver()
        val player = driver.player1
        val showOff = driver.putCardInHand(player, "Slickshot Show-Off")
        driver.giveMana(player, Color.RED, 2)

        driver.submit(PlotCard(player, showOff)).isSuccess shouldBe true

        (showOff in driver.getExile(player)) shouldBe true
        (showOff in driver.getHand(player)) shouldBe false
    }
})
