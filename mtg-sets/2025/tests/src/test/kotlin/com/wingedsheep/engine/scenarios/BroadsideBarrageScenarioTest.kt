package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dft.cards.BroadsideBarrage
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Broadside Barrage (DFT #192) — {1}{U}{R} Instant.
 *
 * Broadside Barrage deals 5 damage to target creature or planeswalker. Draw a card, then discard
 * a card. Because it has one target, none of its effects occur if that target is illegal when the
 * spell would resolve (CR 608.2b).
 */
class BroadsideBarrageScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BroadsideBarrage)
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("deals 5 damage, then draws and discards") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        val discard = driver.putCardInHand(caster, "Mountain")
        val drawn = driver.putCardOnTopOfLibrary(caster, "Forest")
        val barrage = driver.putCardInHand(caster, "Broadside Barrage")
        driver.giveMana(caster, Color.BLUE, 1)
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, barrage, listOf(target)).isSuccess shouldBe true
        driver.bothPass()

        (driver.pendingDecision is SelectCardsDecision) shouldBe true
        driver.submitCardSelection(caster, listOf(discard)).isSuccess shouldBe true

        driver.findPermanent(driver.player2, "Centaur Courser") shouldBe null
        driver.findCardInHand(caster, "Forest") shouldBe drawn
        (discard in driver.getGraveyard(caster)) shouldBe true
    }

    test("does not draw or discard if its only target is illegal on resolution") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        driver.putCardOnTopOfLibrary(caster, "Forest")
        val barrage = driver.putCardInHand(caster, "Broadside Barrage")
        driver.giveMana(caster, Color.BLUE, 1)
        driver.giveMana(caster, Color.RED, 2)

        driver.castSpell(caster, barrage, listOf(target)).isSuccess shouldBe true
        val librarySizeBeforeResolution = driver.state.getLibrary(caster).size
        val handSizeBeforeResolution = driver.getHandSize(caster)
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.pendingDecision shouldBe null
        driver.state.getLibrary(caster).size shouldBe librarySizeBeforeResolution
        driver.getHandSize(caster) shouldBe handSizeBeforeResolution
    }
})
