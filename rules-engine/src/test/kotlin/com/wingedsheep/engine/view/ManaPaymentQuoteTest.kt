package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow
import com.wingedsheep.engine.mechanics.mana.spellPaymentContextFor
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.effects.WardCost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ManaPaymentQuoteTest : FunSpec({
    fun driver() = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.initMirrorMatch(Deck.of("Forest" to 40))
        it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    fun decision(d: GameTestDriver, cost: String, excluded: Set<EntityId> = emptySet()) =
        ManaPaymentWindow.buildDecision(d.state, d.player1, ManaCost.parse(cost), "payment", "Pay",
            DecisionContext(), true, d.services.manaSolver, excludeSources = excluded)

    test("payment quotes combine floating mana and automatic sources without counting a reserved source") {
        val d = driver(); val land = d.putLandOnBattlefield(d.player1, "Forest")
        d.giveMana(d.player1, Color.RED)
        val payable = decision(d, "{2}")
        payable.canAutoPay shouldBe true
        payable.autoPaySuggestion shouldBe listOf(land)
        decision(d, "{2}", setOf(land)).canAutoPay shouldBe false
        d.giveMana(d.player1, Color.RED)
        val floating = decision(d, "{2}", setOf(land))
        floating.canAutoPay shouldBe true
        floating.autoPaySuggestion shouldBe emptyList()
    }

    test("reopened action payment quotes retain their restricted spending context and reservations") {
        val d = driver()
        val creature = CardDefinition.creature("Restricted Payment", ManaCost.parse("{2}"), emptySet(), 2, 2)
        d.registerCard(creature)
        val card = d.putCardInHand(d.player1, creature.name)
        val source = d.putLandOnBattlefield(d.player1, "Forest")
        val context = spellPaymentContextFor(d.state.getEntity(card)!!.get<CardComponent>()!!, isFromHand = true)
        d.giveRestrictedMana(d.player1, Color.RED, 2, ManaRestriction.CreatureSpellsOnly)
        val question = ManaPaymentWindow.buildDecision(d.state, d.player1, creature.manaCost,
            "locked", "Pay", DecisionContext(), false, d.services.manaSolver,
            excludeSources = setOf(source), spellContext = context)
        question.canAutoPay shouldBe true
        val answer = ManaActionPaymentContinuation(CastSpell(d.player1, card), creature.manaCost,
            excludedSources = setOf(source), paymentContext = context)
        val reopened = ManaPaymentWindow.reopen(d.state, Suspension(question, answer), emptyList(), d.services.manaSolver)
        val refreshed = reopened.state.pendingDecision as SelectManaSourcesDecision
        refreshed.canAutoPay shouldBe true
        refreshed.autoPaySuggestion shouldBe emptyList()
        refreshed.availableSources.any { it.entityId == source } shouldBe false
    }

    test("ward suggestions charge only the mana still owed after floating mana") {
        val d = driver()
        val warded = card("Quoted Ward") {
            manaCost = "{1}{G}"; typeLine = "Creature"; power = 2; toughness = 2
            keywordAbility(KeywordAbility.Ward(WardCost.Mana("{2}")))
        }
        d.registerCard(warded)
        val target = d.putCreatureOnBattlefield(d.player2, warded.name)
        val land = d.putLandOnBattlefield(d.player1, "Forest")
        val bolt = d.putCardInHand(d.player1, "Lightning Bolt")
        d.giveMana(d.player1, Color.RED, 2)
        d.submitSuccess(CastSpell(d.player1, bolt, targets = listOf(ChosenTarget.Permanent(target))))
        d.bothPass()
        val question = d.state.pendingDecision as SelectManaSourcesDecision
        question.autoPaySuggestion shouldBe listOf(land)
        question.canAutoPay shouldBe true
        d.submitDecision(d.player1, ManaSourcesSelectedResponse(question.id, emptyList(), autoPay = true)).error.shouldBeNull()
    }
})
