package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.eoe.cards.NovaHellkite
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Scenario tests for Nova Hellkite (EOE #148). */
class NovaHellkiteScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + NovaHellkite)
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveEntry(driver: GameTestDriver, caster: EntityId, target: EntityId) {
        val decision = driver.pendingDecision as? ChooseTargetsDecision
            ?: error("expected Nova Hellkite ETB target decision")
        driver.submitTargetSelection(caster, listOf(target)).isSuccess shouldBe true
        driver.bothPass()
    }

    test("has flying and haste and its ETB deals 1 to an opposing creature") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Savannah Lions")
        val spell = driver.putCardInHand(caster, "Nova Hellkite")
        driver.giveMana(caster, Color.RED, 5)

        driver.castSpell(caster, spell).isSuccess shouldBe true
        driver.bothPass()
        resolveEntry(driver, caster, target)

        val hellkite = driver.findPermanent(caster, "Nova Hellkite")
        hellkite shouldNotBe null
        driver.state.projectedState.hasKeyword(hellkite!!, Keyword.FLYING) shouldBe true
        driver.state.projectedState.hasKeyword(hellkite, Keyword.HASTE) shouldBe true
        driver.findPermanent(driver.player2, "Savannah Lions") shouldBe null
    }

    test("may be warped for {2}{R} and is exiled at the next end step") {
        val driver = newDriver()
        val caster = driver.player1
        val target = driver.putCreatureOnBattlefield(driver.player2, "Centaur Courser")
        val spell = driver.putCardInHand(caster, "Nova Hellkite")
        driver.giveMana(caster, Color.RED, 3)

        driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spell,
                useAlternativeCost = true,
                paymentStrategy = PaymentStrategy.FromPool
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        resolveEntry(driver, caster, target)
        driver.findPermanent(caster, "Nova Hellkite") shouldNotBe null

        driver.passPriorityUntil(Step.END)
        driver.bothPass()

        driver.findPermanent(caster, "Nova Hellkite") shouldBe null
        driver.getExileCardNames(caster) shouldBe listOf("Nova Hellkite")
    }
})
