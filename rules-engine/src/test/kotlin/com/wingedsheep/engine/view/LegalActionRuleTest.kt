package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.mechanics.mana.CostCalculator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LegalActionRuleTest : FunSpec({
    val subject = CardDefinition.creature("Quote Subject", ManaCost.parse("{1}"), emptySet(), 2, 2)
    fun spell(targets: Int) = CardDefinition("Quote Spell $targets", ManaCost.parse("{W}"), TypeLine.parse("Instant"),
        script = CardScript(spellEffect = Effects.GainLife(1),
            targetRequirements = List(targets) { TargetObject(filter = TargetFilter.Creature) },
            staticAbilities = if (targets == 1) emptyList() else listOf(ModifySpellCost(
                SpellCostTarget.SelfCast, CostModification.IncreaseGenericBy(CostReductionSource.ChosenTargetsBeyondTheFirst)))))
    fun driver(vararg definitions: CardDefinition) = GameTestDriver().also {
        it.registerCards(TestCards.all + subject + definitions.toList())
        it.initMirrorMatch(Deck.of("Forest" to 40))
        it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    fun enrich(d: GameTestDriver) = LegalActionEnricher(
        ManaSolver(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator), d.cardRegistry)

    test("target affordability honors spending permission while retaining the displayed cost") {
        val card = spell(1)
        val permission = subject.copy(name = "Quote Permission", script = CardScript(
            staticAbilities = listOf(SpendAnyManaTypeForSpells(GameObjectFilter.Any))))
        for (allowed in listOf(false, true)) {
            val d = driver(card, permission)
            val source = d.putCardInHand(d.player1, card.name)
            val target = d.putCreatureOnBattlefield(d.player2, subject.name)
            repeat(19) { d.putCreatureOnBattlefield(d.player2, subject.name) }
            d.giveMana(d.player1, Color.WHITE, 1)
            val offer = d.legalActions(d.player1).single { (it.action as? CastSpell)?.cardId == source }
            d.replaceState(d.state.updateEntity(d.player1) { it.with(ManaPoolComponent(red = 1)) })
            if (allowed) d.putCreatureOnBattlefield(d.player1, permission.name)
            val info = enrich(d).enrich(listOf(offer), d.state, d.player1).single()
            val quote = info.targetManaCosts!!.single { it.targetId == target }
            info.targetManaCosts!!.size shouldBe 20
            info.targetManaCosts!!.all { it.affordable == allowed && it.manaCost == "{W}" } shouldBe true
            quote.manaCost shouldBe "{W}"
            quote.affordable shouldBe allowed
            info.isAffordable shouldBe allowed
            val action = (offer.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(target)))
            if (allowed) d.submitSuccess(action) else d.submitExpectFailure(action)
        }
    }

    test("a surcharge over two mandatory target slots is never quoted as a single target") {
        val card = spell(2)
        val d = driver(card)
        val source = d.putCardInHand(d.player1, card.name)
        val first = d.putCreatureOnBattlefield(d.player2, subject.name)
        val second = d.putCreatureOnBattlefield(d.player2, subject.name)
        d.giveMana(d.player1, Color.WHITE, 2)
        val offer = d.legalActions(d.player1).single { (it.action as? CastSpell)?.cardId == source }
        offer.targetCount shouldBe 1
        offer.targetRequirements!!.size shouldBe 2
        enrich(d).enrich(listOf(offer), d.state, d.player1).single().targetManaCosts.shouldBeNull()
        CostCalculator(d.cardRegistry, d.services.predicateEvaluator)
            .calculateEffectiveCost(d.state, card, d.player1, listOf(first, second)).cmc shouldBe 2
        d.submitSuccess((offer.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second))))
    }

    test("same-cost activations retain definition identity and visible stack source") {
        val sourceCard = subject.copy(script = CardScript.permanent(
            ActivatedAbility(id = AbilityId("one"), cost = AbilityCost.Tap, effect = Effects.GainLife(1)),
            ActivatedAbility(id = AbilityId("two"), cost = AbilityCost.Tap, effect = Effects.GainLife(2))))
        for (ability in sourceCard.script.activatedAbilities) {
            val d = driver(sourceCard)
            val source = d.putCreatureOnBattlefield(d.player1, sourceCard.name)
            d.removeSummoningSickness(source)
            val info = enrich(d).enrich(d.legalActions(d.player1), d.state, d.player1)
                .single { (it.action as? ActivateAbility)?.abilityId == ability.id }
            info.rule!!.abilityIdentity!!.abilityId shouldBe ability.id.value
            info.rule!!.origin shouldBe AbilityOrigin.DEFINITION
            d.submitSuccess(info.action)
            for (viewer in listOf(d.player1, d.player2)) {
                val stack = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator)
                    .transform(d.state, viewer).cards.values.single { it.abilityIdentity != null }
                stack.abilityIdentity shouldBe info.rule!!.abilityIdentity
                stack.abilitySourceId shouldBe source
                stack.abilityDefinitionIsExact shouldBe true
            }
        }
    }
})
