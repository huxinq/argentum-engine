package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ths.cards.LightningStrike
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Scenario tests for Lightning Strike (THS #127). */
class LightningStrikeScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LightningStrike)
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("deals 3 damage to a player") {
        val driver = newDriver()
        val caster = driver.player1
        val opponent = driver.player2
        val spell = driver.putCardInHand(caster, "Lightning Strike")
        val lifeBefore = driver.getLifeTotal(opponent)
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, spell, listOf(opponent)).isSuccess shouldBe true
        driver.bothPass()

        driver.getLifeTotal(opponent) shouldBe lifeBefore - 3
    }

    test("may target and destroy a creature") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        val spell = driver.putCardInHand(caster, "Lightning Strike")
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, spell, listOf(target)).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(driver.player2, "Centaur Courser") shouldBe null
    }
})
