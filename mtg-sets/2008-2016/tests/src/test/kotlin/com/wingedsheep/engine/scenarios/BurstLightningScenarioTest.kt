package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.zen.cards.BurstLightning
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.ChoiceSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Scenario tests for Burst Lightning (ZEN #119). */
class BurstLightningScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BurstLightning)
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("deals 2 damage to a player when not kicked") {
        val driver = newDriver()
        val caster = driver.player1
        val opponent = driver.player2
        val spell = driver.putCardInHand(caster, "Burst Lightning")
        val lifeBefore = driver.getLifeTotal(opponent)
        driver.giveMana(caster, Color.RED, 1)

        driver.castSpell(caster, spell, listOf(opponent)).isSuccess shouldBe true
        driver.bothPass()

        driver.getLifeTotal(opponent) shouldBe lifeBefore - 2
    }

    test("deals 4 damage instead when kicked and may target a creature") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        val spell = driver.putCardInHand(caster, "Burst Lightning")
        driver.giveMana(caster, Color.RED, 1)
        driver.giveColorlessMana(caster, 4)

        driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spell,
                targets = listOf(ChosenTarget.Permanent(target)),
                declaredCostSlot = ChoiceSlot.KICKED,
                paymentStrategy = PaymentStrategy.FromPool
            )
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(driver.player2, "Centaur Courser") shouldBe null
    }
})
