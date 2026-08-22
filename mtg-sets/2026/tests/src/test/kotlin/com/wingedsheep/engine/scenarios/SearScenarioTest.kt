package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ecl.cards.Sear
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Scenario tests for Sear (ECL #154). */
class SearScenarioTest : FunSpec({

    val testPlaneswalker = card("Test Four-Loyalty Planeswalker") {
        manaCost = "{2}{U}"
        typeLine = "Legendary Planeswalker — Tester"
        startingLoyalty = 4
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(Sear, testPlaneswalker))
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("deals 4 damage to a creature") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Hill Giant")
        val spell = driver.putCardInHand(caster, "Sear")
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, spell, listOf(target)).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(driver.player2, "Hill Giant") shouldBe null
    }

    test("deals 4 damage to a planeswalker") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putPermanentOnBattlefield(driver.player2, "Test Four-Loyalty Planeswalker")
        val spell = driver.putCardInHand(caster, "Sear")
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, spell, listOf(target)).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(driver.player2, "Test Four-Loyalty Planeswalker") shouldBe null
    }

    test("cannot target a player") {
        val driver = newDriver()
        val caster = driver.player1
        val spell = driver.putCardInHand(caster, "Sear")
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, spell, listOf(driver.player2)).isSuccess shouldBe false
    }
})
