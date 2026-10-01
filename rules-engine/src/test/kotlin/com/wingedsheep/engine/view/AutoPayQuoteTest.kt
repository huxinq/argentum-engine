package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import com.wingedsheep.sdk.scripting.effects.Mode
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AutoPayQuoteTest : FunSpec({
    fun creature(cost: String) = CardDefinition.creature("Payment Creature", ManaCost.parse(cost), emptySet(), 2, 2)
    fun driver(spell: CardDefinition) = GameTestDriver().also {
        it.registerCards(TestCards.all + PredefinedTokens.allTokens + spell)
        it.initMirrorMatch(Deck.of("Forest" to 40)); it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    fun offers(d: GameTestDriver) = LegalActionEnricher(d.services.manaSolver, d.cardRegistry)
        .enrich(d.legalActions(d.player1), d.state, d.player1)
    fun cast(d: GameTestDriver, card: EntityId) = offers(d).single { (it.action as? CastSpell)?.cardId == card }

    for ((cost, treasures, pool) in listOf(
        Triple("{1}{R}", 2, ManaPoolComponent(red = 1)),
        Triple("{1}{R}{R}", 1, ManaPoolComponent(black = 1, red = 1))
    )) {
        test("$cost needs an explicit Treasure activation before its AutoPay cast") {
            val spell = creature(cost); val d = driver(spell); val p = d.player1
            repeat(3) { d.tapPermanent(d.putLandOnBattlefield(p, if (it == 0) "Swamp" else "Mountain")) }
            val treasureIds = List(treasures) { d.putPermanentOnBattlefield(p, "Treasure") }
            d.replaceState(d.state.updateEntity(p) { it.with(pool) })
            val card = d.putCardInHand(p, spell.name)
            d.services.manaSolver.canPay(d.state, p, spell.manaCost) shouldBe true
            d.services.manaSolver.canAutoPay(d.state, p, spell.manaCost) shouldBe false
            cast(d, card).canAutoPay shouldBe false
            cast(d, card).isAffordable shouldBe true
            com.wingedsheep.engine.legalactions.MeaningfulActionFilter.isMeaningful(cast(d, card).asPriorityAction()) shouldBe true
            val before = d.state
            d.submit(CastSpell(p, card)).error.shouldNotBeNull()
            d.state shouldBe before
            val activation = offers(d).single { (it.action as? ActivateAbility)?.sourceId == treasureIds.first() }
            activation.isAffordable shouldBe true
            val chosen = (activation.action as ActivateAbility).copy(manaColorChoice = Color.RED)
            d.submit(chosen).error.shouldBeNull()
            d.services.manaSolver.canAutoPay(d.state, p, spell.manaCost) shouldBe true
            val payable = cast(d, card)
            payable.canAutoPay shouldBe true
            d.submit(payable.action).error.shouldBeNull()
            treasureIds.count { it in d.state.getBattlefield() } shouldBe treasures - 1
        }
    }

    test("target quotes exclude unactivated Treasure mana and agree after floating it") {
        val spell = CardDefinition("Payment Spell", ManaCost.parse("{1}{R}"), TypeLine.parse("Instant"),
            script = CardScript(spellEffect = Effects.GainLife(1),
                targetRequirements = listOf(TargetObject(filter = TargetFilter.Creature))))
        val subject = creature("{1}")
        val d = driver(spell); d.registerCard(subject); val p = d.player1
        val card = d.putCardInHand(p, spell.name)
        val target = d.putCreatureOnBattlefield(d.player2, subject.name)
        val treasure = d.putPermanentOnBattlefield(p, "Treasure")
        d.giveMana(p, Color.RED)
        cast(d, card).targetManaCosts!!.single().canAutoPay shouldBe false
        cast(d, card).canAutoPay shouldBe false
        cast(d, card).isAffordable shouldBe true
        val activation = offers(d).map { it.action }.filterIsInstance<ActivateAbility>().single { it.sourceId == treasure }
        d.submit(activation.copy(manaColorChoice = Color.RED)).error.shouldBeNull()
        val quoted = cast(d, card)
        quoted.targetManaCosts!!.single().canAutoPay shouldBe true
        d.submit((quoted.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(target)))).error.shouldBeNull()
    }

    for ((cost, pool, lands) in listOf(
        Triple("{1}{R}", ManaPoolComponent(red = 1), listOf("Swamp")),
        Triple("{1}{R}{R}", ManaPoolComponent(black = 1, red = 2), emptyList()),
        Triple("{R/G}", ManaPoolComponent(), listOf("Forest")),
        Triple("{2/B}", ManaPoolComponent(), listOf("Mountain", "Forest")),
        Triple("{C}", ManaPoolComponent(colorless = 1), emptyList())
    )) {
        test("$cost quote and execution agree for ordinary sources and floating mana") {
            val spell = creature(cost); val d = driver(spell); val p = d.player1
            lands.forEach { d.putLandOnBattlefield(p, it) }
            d.replaceState(d.state.updateEntity(p) { it.with(pool) })
            val card = d.putCardInHand(p, spell.name)
            d.services.manaSolver.canAutoPay(d.state, p, spell.manaCost) shouldBe true
            val offered = cast(d, card); offered.canAutoPay shouldBe true
            d.submit(offered.action).error.shouldBeNull()
        }
    }

    test("Phyrexian AutoPay chooses life when Treasure mana would need an explicit activation") {
        val spell = creature("{1}{R/P}")
        for (life in listOf(1, 20)) {
            val d = driver(spell); val p = d.player1
            val treasure = d.putPermanentOnBattlefield(p, "Treasure")
            d.replaceState(d.state.updateEntity(p) { it.with(ManaPoolComponent(colorless = 1)) })
            d.setLifeTotal(p, life)
            val card = d.putCardInHand(p, spell.name)
            d.services.manaSolver.canPay(d.state, p, spell.manaCost) shouldBe true
            val payable = life >= 2
            d.services.manaSolver.canAutoPay(d.state, p, spell.manaCost) shouldBe payable
            cast(d, card).canAutoPay shouldBe payable
            if (payable) {
                d.submit(cast(d, card).action).error.shouldBeNull()
                d.state.lifeTotal(p) shouldBe life - 2
                (treasure in d.state.getBattlefield()) shouldBe true
            }
        }
    }

    test("declared mana kicker is priced by the cast cost totaller") {
        val spell = CardDefinition("Kicked Payment", ManaCost.parse("{R}"), TypeLine.parse("Instant"),
            keywordAbilities = listOf(KeywordAbility.kicker("{4}")),
            script = CardScript(spellEffect = Effects.GainLife(1),
                targetRequirements = listOf(TargetObject(filter = TargetFilter.Creature))))
        val subject = creature("{1}"); val d = driver(spell); val p = d.player1
        d.registerCard(subject); val target = d.putCreatureOnBattlefield(d.player2, subject.name)
        val card = d.putCardInHand(p, spell.name)
        val treasure = d.putPermanentOnBattlefield(p, "Treasure")
        d.replaceState(d.state.updateEntity(p) { it.with(ManaPoolComponent(red = 1, colorless = 3)) })
        fun kicked() = offers(d).single { (it.action as? CastSpell)?.let { a -> a.cardId == card && a.declaredCostSlot == ChoiceSlot.KICKED } == true }
        kicked().isAffordable shouldBe true
        kicked().canAutoPay shouldBe false
        ManaCost.parse(kicked().targetManaCosts!!.single().manaCost).cmc shouldBe 5
        val activation = offers(d).map { it.action }.filterIsInstance<ActivateAbility>().single { it.sourceId == treasure }
        d.submit(activation.copy(manaColorChoice = Color.RED)).error.shouldBeNull()
        kicked().canAutoPay shouldBe true
        d.submit((kicked().action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(target)))).error.shouldBeNull()
    }

    test("fixed mode costs are included without consuming Treasure implicitly") {
        val spell = CardDefinition("Modal Payment", ManaCost.parse("{R}"), TypeLine.parse("Instant"),
            script = CardScript(spellEffect = ModalEffect(listOf(
                Mode(Effects.GainLife(1)), Mode(Effects.GainLife(2), additionalManaCost = "{1}")))))
        for (mode in listOf(0, 1)) {
            val d = driver(spell); val p = d.player1; val card = d.putCardInHand(p, spell.name)
            val treasure = d.putPermanentOnBattlefield(p, "Treasure"); d.giveMana(p, Color.RED)
            fun chosen() = offers(d).single { (it.action as? CastSpell)?.let { a -> a.cardId == card && a.chosenModes == listOf(mode) } == true }
            chosen().isAffordable shouldBe true
            chosen().canAutoPay shouldBe (mode == 0)
            if (mode == 1) {
                val activation = offers(d).map { it.action }.filterIsInstance<ActivateAbility>().single { it.sourceId == treasure }
                d.submit(activation.copy(manaColorChoice = Color.RED)).error.shouldBeNull()
                chosen().canAutoPay shouldBe true
            }
            d.submit(chosen().action).error.shouldBeNull()
            (treasure in d.state.getBattlefield()) shouldBe (mode == 0)
        }
    }

    test("targeted kicked quotes use the execution spending context") {
        val spell = CardDefinition("Restricted Kicker", ManaCost.parse("{R}"), TypeLine.parse("Instant"),
            keywordAbilities = listOf(KeywordAbility.kicker("{4}")),
            script = CardScript(spellEffect = Effects.GainLife(1),
                targetRequirements = listOf(TargetObject(filter = TargetFilter.Creature))))
        val subject = creature("{1}"); val d = driver(spell); val p = d.player1
        d.registerCard(subject); val target = d.putCreatureOnBattlefield(d.player2, subject.name)
        val card = d.putCardInHand(p, spell.name); d.giveMana(p, Color.RED, 5)
        val templates = d.legalActions(p).filter { (it.action as? CastSpell)?.cardId == card }
        d.replaceState(d.state.updateEntity(p) { it.with(ManaPoolComponent()) })
        d.giveRestrictedMana(p, Color.RED, 5, ManaRestriction.KickedSpellsOnly)
        val quotes = LegalActionEnricher(d.services.manaSolver, d.cardRegistry).enrich(templates, d.state, p)
        val normal = quotes.single { (it.action as CastSpell).declaredCostSlot == null }
        val kicked = quotes.single { (it.action as CastSpell).declaredCostSlot == ChoiceSlot.KICKED }
        normal.isAffordable shouldBe false
        normal.canAutoPay shouldBe false
        kicked.isAffordable shouldBe true
        kicked.canAutoPay shouldBe true
        kicked.targetManaCosts!!.single().affordable shouldBe true
        kicked.targetManaCosts!!.single().canAutoPay shouldBe true
        d.submit((normal.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(target)))).error.shouldNotBeNull()
        d.submit((kicked.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(target)))).error.shouldBeNull()
    }
    test("mana-only activation quotes require Treasure to be activated explicitly") {
        val ability = ActivatedAbility(id = AbilityId("payment"), cost = AbilityCost.Mana(ManaCost.parse("{1}{R}")),
            effect = Effects.GainLife(1))
        val card = creature("{1}").copy(name = "Payment Ability", script = CardScript.permanent(ability))
        val d = driver(card); val p = d.player1
        val source = d.putCreatureOnBattlefield(p, card.name)
        val treasure = d.putPermanentOnBattlefield(p, "Treasure")
        d.giveMana(p, Color.RED)
        fun activation() = offers(d).single { (it.action as? ActivateAbility)?.sourceId == source }
        activation().isAffordable shouldBe true
        activation().canAutoPay shouldBe false
        val before = d.state
        d.submit(activation().action).error.shouldNotBeNull()
        d.state shouldBe before
        val manaAction = offers(d).map { it.action }.filterIsInstance<ActivateAbility>().single { it.sourceId == treasure }
        d.submit(manaAction.copy(manaColorChoice = Color.RED)).error.shouldBeNull()
        activation().canAutoPay shouldBe true
        d.submit(activation().action).error.shouldBeNull()
    }

    test("sacrifice-or-pay casts remain unquoted until their additional cost is declared") {
        val spell = CardDefinition("Choice Payment", ManaCost.parse("{B}"), TypeLine.parse("Sorcery"),
            script = CardScript(spellEffect = Effects.GainLife(1), additionalCosts = listOf(
                Costs.additional.SacrificeOrPay(GameObjectFilter.Creature, "{3}{B}"))))
        for (sacrifice in listOf(false, true)) {
            val d = driver(spell); val p = d.player1
            val subject = creature("{1}"); d.registerCard(subject)
            val permanent = d.putCreatureOnBattlefield(p, subject.name)
            val card = d.putCardInHand(p, spell.name)
            d.giveMana(p, Color.BLACK, if (sacrifice) 1 else 5)
            val offer = cast(d, card)
            offer.canAutoPay.shouldBeNull()
            offer.targetManaCosts.shouldBeNull()
            val payment = AdditionalCostPayment(sacrificedPermanents = if (sacrifice) listOf(permanent) else emptyList())
            d.submit((offer.action as CastSpell).copy(additionalCostPayment = payment)).error.shouldBeNull()
            (permanent in d.state.getBattlefield()) shouldBe !sacrifice
            d.state.getEntity(p)!!.get<ManaPoolComponent>()!!.total shouldBe 0
        }
    }
})
