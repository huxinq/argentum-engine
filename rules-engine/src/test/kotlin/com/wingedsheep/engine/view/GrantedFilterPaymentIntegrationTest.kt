package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.*
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.effects.WardCost
import com.wingedsheep.sdk.scripting.filters.unified.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class GrantedFilterPaymentIntegrationTest : FunSpec({
    val mana = ActivatedAbility(id = AbilityId("tap-mana"), cost = AbilityCost.Tap, effect = Effects.AddMana(Color.BLUE), isManaAbility = true)
    val tap = card("Filter Trigger") {
        typeLine = "Creature"; power = 2; toughness = 2
        triggeredAbility { trigger = Triggers.self.becomesTapped(); effect = Effects.GainLife(1) }
    }.triggeredAbilities.single()
    val filter = GroupFilter(GameObjectFilter.Creature.withColor(Color.BLUE).youControl(), excludeSelf = true)
    val blue = CardDefinition.creature("Filter Blue", ManaCost.parse("{U}"), emptySet(), 2, 2, script = CardScript.permanent(mana))
    val green = CardDefinition.creature("Filter Green", ManaCost.parse("{G}"), emptySet(), 2, 2, script = CardScript.permanent(mana))
    val provider = blue.copy(name = "Filter Provider", script = CardScript.permanent(mana).copy(
        staticAbilities = listOf(GrantTriggeredAbility(tap, filter), GrantWard(WardCost.Mana("{2}"), filter))))
    val spell = card("Filter Damage") {
        manaCost = "{0}"; typeLine = "Instant"
        spell { effect = Effects.DealDamage(1, target(TargetFilter.Creature)) }
    }
    for (kind in listOf("blue", "green", "provider", "opponent-blue")) {
        test("full grant filter gates actual triggers and Ward payment for $kind") {
            val d = GameTestDriver()
            d.registerCards(TestCards.all + listOf(blue, green, provider, spell))
            d.initMirrorMatch(Deck.of("Forest" to 40)); d.passPriorityUntil(Step.PRECOMBAT_MAIN)
            val granter = d.putCreatureOnBattlefield(d.player1, provider.name)
            val owner = if (kind == "opponent-blue") d.player2 else d.player1
            val receiver = if (kind == "provider") granter else
                d.putCreatureOnBattlefield(owner, if (kind == "green") green.name else blue.name)
            d.removeSummoningSickness(receiver)
            if (d.state.priorityPlayerId != owner) d.passPriority(d.state.priorityPlayerId!!)
            d.submitSuccess(ActivateAbility(owner, receiver, mana.id))
            val matches = kind == "blue"
            d.state.stack.size shouldBe if (matches) 1 else 0
            if (matches) {
                val rule = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator)
                    .transform(d.state, d.player2).cards[d.getTopOfStack()!!]!!
                rule.semanticRule!!.effect shouldBe Effects.GainLife(1)
                rule.abilityDefinitionIsExact shouldBe false
                d.bothPass().error.shouldBeNull()
            }
            d.getLifeTotal(d.player1) shouldBe if (matches) 21 else 20
            val forest = d.putLandOnBattlefield(d.player2, "Forest")
            d.giveMana(d.player2, Color.RED)
            val card = d.putCardInHand(d.player2, spell.name)
            if (d.state.priorityPlayerId != d.player2) d.passPriority(d.state.priorityPlayerId!!)
            val offer = LegalActionEnricher(d.services.manaSolver, d.cardRegistry)
                .enrich(d.legalActions(d.player2), d.state, d.player2).single { (it.action as? CastSpell)?.cardId == card }
            offer.targetManaCosts!!.single { it.targetId == receiver }.canAutoPay shouldBe true
            d.submitSuccess((offer.action as CastSpell).copy(targets = listOf(ChosenTarget.Permanent(receiver))))
            d.state.stack.size shouldBe if (matches) 2 else 1
            d.bothPass().error.shouldBeNull()
            if (matches) {
                val payment = d.pendingDecision as SelectManaSourcesDecision
                payment.canAutoPay shouldBe true
                payment.autoPaySuggestion shouldBe listOf(forest)
                d.submitDecision(d.player2, ManaSourcesSelectedResponse(payment.id, emptyList(), autoPay = true)).error.shouldBeNull()
                d.bothPass().error.shouldBeNull()
                d.isTapped(forest) shouldBe true
            } else {
                d.pendingDecision.shouldBeNull()
                d.isTapped(forest) shouldBe false
            }
            d.state.stack shouldBe emptyList()
        }
    }
})
