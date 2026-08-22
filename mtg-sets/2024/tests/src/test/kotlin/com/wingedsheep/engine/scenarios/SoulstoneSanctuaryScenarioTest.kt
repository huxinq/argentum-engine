package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.fdn.cards.SoulstoneSanctuary
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Scenario tests for Soulstone Sanctuary (FDN #133). */
class SoulstoneSanctuaryScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SoulstoneSanctuary)
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("becomes a 3/3 vigilant creature with all creature types while remaining a land") {
        val driver = newDriver()
        val player = driver.player1
        val sanctuary = driver.putLandOnBattlefield(player, "Soulstone Sanctuary")
        val animateAbility = SoulstoneSanctuary.activatedAbilities[1].id
        driver.giveColorlessMana(player, 4)

        driver.submit(ActivateAbility(player, sanctuary, animateAbility)).isSuccess shouldBe true
        driver.bothPass()

        val projected = driver.state.projectedState
        projected.isCreature(sanctuary) shouldBe true
        projected.hasType(sanctuary, "LAND") shouldBe true
        projected.getPower(sanctuary) shouldBe 3
        projected.getToughness(sanctuary) shouldBe 3
        projected.hasKeyword(sanctuary, Keyword.VIGILANCE) shouldBe true
        projected.hasSubtype(sanctuary, "Lizard") shouldBe true
        projected.hasSubtype(sanctuary, "Wizard") shouldBe true
    }

    test("the animation has no duration and persists into the next turn") {
        val driver = newDriver()
        val player = driver.player1
        val sanctuary = driver.putLandOnBattlefield(player, "Soulstone Sanctuary")
        val animateAbility = SoulstoneSanctuary.activatedAbilities[1].id
        driver.giveColorlessMana(player, 4)
        driver.submit(ActivateAbility(player, sanctuary, animateAbility)).isSuccess shouldBe true
        driver.bothPass()

        driver.passPriorityUntil(Step.END)
        driver.bothPass()
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        driver.state.projectedState.isCreature(sanctuary) shouldBe true
        driver.state.projectedState.getPower(sanctuary) shouldBe 3
        driver.state.projectedState.getToughness(sanctuary) shouldBe 3
    }
})
