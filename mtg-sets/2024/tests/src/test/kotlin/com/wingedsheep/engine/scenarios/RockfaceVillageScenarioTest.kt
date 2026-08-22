package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.blb.cards.HiredClaw
import com.wingedsheep.mtg.sets.definitions.blb.cards.RockfaceVillage
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Scenario tests for Rockface Village (BLB #259). */
class RockfaceVillageScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(RockfaceVillage, HiredClaw))
        driver.initMirrorMatch(Deck.of("Mountain" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("its red mana may cast a creature spell") {
        val driver = newDriver()
        val player = driver.player1
        val village = driver.putLandOnBattlefield(player, "Rockface Village")
        val creature = driver.putCardInHand(player, "Hired Claw")
        val redCreatureManaAbility = RockfaceVillage.activatedAbilities[1].id

        driver.submit(ActivateAbility(player, village, redCreatureManaAbility)).isSuccess shouldBe true
        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Hired Claw") shouldNotBe null
    }

    test("its creature-only red mana cannot cast a noncreature spell") {
        val driver = newDriver()
        val player = driver.player1
        val village = driver.putLandOnBattlefield(player, "Rockface Village")
        val shock = driver.putCardInHand(player, "Shock")
        val redCreatureManaAbility = RockfaceVillage.activatedAbilities[1].id

        driver.submit(ActivateAbility(player, village, redCreatureManaAbility)).isSuccess shouldBe true
        driver.castSpell(player, shock, listOf(driver.player2)).isSuccess shouldBe false
    }

    test("at sorcery speed it gives a controlled Lizard +1 power and haste") {
        val driver = newDriver()
        val player = driver.player1
        val village = driver.putLandOnBattlefield(player, "Rockface Village")
        val lizard = driver.putCreatureOnBattlefield(player, "Hired Claw")
        val tribalAbility = RockfaceVillage.activatedAbilities[2].id
        driver.giveMana(player, Color.RED, 1)

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = village,
                abilityId = tribalAbility,
                targets = listOf(ChosenTarget.Permanent(lizard))
            )
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.state.projectedState.getPower(lizard) shouldBe 2
        driver.state.projectedState.hasKeyword(lizard, Keyword.HASTE) shouldBe true
    }

    test("the tribal pump cannot target a creature outside its four named types") {
        val driver = newDriver()
        val player = driver.player1
        val village = driver.putLandOnBattlefield(player, "Rockface Village")
        val bear = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val tribalAbility = RockfaceVillage.activatedAbilities[2].id
        driver.giveMana(player, Color.RED, 1)

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = village,
                abilityId = tribalAbility,
                targets = listOf(ChosenTarget.Permanent(bear))
            )
        ).error shouldNotBe null
    }
})
