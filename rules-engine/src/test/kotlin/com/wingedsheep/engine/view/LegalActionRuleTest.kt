package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.event.GrantedActivatedAbility
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.handlers.effects.stack.CopyTargetSpellOrAbilityExecutor
import com.wingedsheep.engine.handlers.effects.stack.CopyTargetTriggeredAbilityExecutor
import com.wingedsheep.engine.mechanics.stack.StackPlacement
import com.wingedsheep.engine.state.components.identity.*
import com.wingedsheep.engine.state.components.stack.*
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
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
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
    test("text replacement exposes the effective filter and a copied stack rule stays inexact") {
        val ability = ActivatedAbility(id = AbilityId("color"), cost = AbilityCost.Free,
            effect = Effects.GainLife(1), targetRequirements = listOf(
                TargetObject(filter = TargetFilter.Creature.withColor(Color.RED))))
        val card = subject.copy(name = "Changed Rule", script = CardScript.permanent(ability))
        val red = CardDefinition.creature("Red Witness", ManaCost.parse("{R}"), emptySet(), 1, 1)
        val blue = CardDefinition.creature("Blue Witness", ManaCost.parse("{U}"), emptySet(), 1, 1)
        val d = driver(card, red, blue)
        val source = d.putCreatureOnBattlefield(d.player1, card.name)
        val redId = d.putCreatureOnBattlefield(d.player2, red.name)
        val blueId = d.putCreatureOnBattlefield(d.player2, blue.name)
        d.replaceState(d.state.updateEntity(source) { it.with(TextReplacementComponent(listOf(
            TextReplacement("Red", "Blue", TextReplacementCategory.COLOR_WORD)))) })
        val info = enrich(d).enrich(d.legalActions(d.player1), d.state, d.player1)
            .single { (it.action as? ActivateAbility)?.sourceId == source }
        info.rule!!.origin shouldBe AbilityOrigin.DEFINITION
        info.rule!!.hasTextChanges shouldBe true
        (info.rule!!.effectiveAbility!!.targetRequirements.single() as TargetObject).filter shouldBe
            TargetFilter.Creature.withColor(Color.BLUE)
        info.validTargets!!.contains(blueId) shouldBe true
        info.validTargets!!.contains(redId) shouldBe false
        d.submitSuccess((info.action as ActivateAbility).copy(targets = listOf(ChosenTarget.Permanent(blueId))))
        val original = d.getTopOfStack()!!
        val scopes = d.state.getEntity(original)!!.get<TargetsComponent>()!!
        d.replaceState(CopyTargetSpellOrAbilityExecutor.cloneAndPush(d.state, original, d.player1,
            scopes.targets, scopes.targetRequirements).state)
        val view = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator)
            .transform(d.state, d.player2)
        view.cards[source]!!.hasTextChanges shouldBe true
        view.cards[original]!!.abilityDefinitionIsExact shouldBe false
        view.cards[d.getTopOfStack()!!]!!.abilityDefinitionIsExact shouldBe false
        view.cards[d.getTopOfStack()!!]!!.abilityIdentity shouldBe info.rule!!.abilityIdentity
    }

    for (static in listOf(false, true)) {
        test("${if (static) "static" else "runtime"} grants expose executable rules with honest provenance") {
            val d = driver()
            val recipient = d.putCreatureOnBattlefield(d.player1, subject.name)
            val granter = d.putCreatureOnBattlefield(d.player1, subject.name)
            val ability = ActivatedAbility(id = AbilityId("granted"), cost = AbilityCost.Free, effect = Effects.GainLife(2))
            d.replaceState(if (static) d.state.copy(grantedStaticAbilities = listOf(GrantedStaticAbility(
                granter, GrantActivatedAbility(ability, GroupFilter(GameObjectFilter.Creature.youControl())), Duration.Permanent)))
                else d.state.copy(grantedActivatedAbilities = listOf(GrantedActivatedAbility(recipient, ability, Duration.Permanent))))
            val info = enrich(d).enrich(d.legalActions(d.player1), d.state, d.player1).single {
                (it.action as? ActivateAbility)?.let { action -> action.sourceId == recipient && action.abilityId == ability.id } == true }
            info.rule!!.origin shouldBe if (static) AbilityOrigin.STATIC_GRANTED else AbilityOrigin.RUNTIME_GRANTED
            info.rule!!.abilityIdentity.shouldBeNull()
            info.rule!!.effectiveAbility shouldBe ability
            info.rule!!.granterId shouldBe if (static) granter else null
            d.submitSuccess(info.action)
            for (viewer in listOf(d.player1, d.player2)) {
                val stack = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator)
                    .transform(d.state, viewer).cards[d.getTopOfStack()!!]!!
                stack.abilityDefinitionIsExact shouldBe false
                stack.abilityIdentity.shouldBeNull()
                stack.abilitySourceId shouldBe recipient
            }
            d.bothPass().error.shouldBeNull()
            d.getLifeTotal(d.player1) shouldBe 22
        }
    }

    test("copied printed triggers retain identity but do not claim an exact definition rule") {
        val d = driver()
        val source = d.putCreatureOnBattlefield(d.player1, subject.name)
        val identity = AbilityIdentity(subject.name, AbilityId("trigger"))
        val component = TriggeredAbilityOnStackComponent(source, subject.name, d.player1, Effects.GainLife(1),
            "Printed trigger", abilityIdentity = identity, definitionRuleIsExact = true)
        d.replaceState(StackPlacement.putTriggeredAbility(d.state, component).state)
        val original = d.getTopOfStack()!!
        val copied = CopyTargetTriggeredAbilityExecutor.cloneAbility(component, d.player1)
        d.replaceState(StackPlacement.putTriggeredAbility(d.state, copied).state)
        for (viewer in listOf(d.player1, d.player2)) {
            val view = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator).transform(d.state, viewer)
            view.cards[original]!!.abilityDefinitionIsExact shouldBe true
            view.cards[d.getTopOfStack()!!]!!.abilityDefinitionIsExact shouldBe false
            view.cards[d.getTopOfStack()!!]!!.abilityIdentity shouldBe view.cards[original]!!.abilityIdentity
        }
        d.bothPass(); d.bothPass()
        d.getLifeTotal(d.player1) shouldBe 22
    }

})
