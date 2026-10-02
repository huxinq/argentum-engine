package com.wingedsheep.engine.view

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DefinitionAddressProjectionTest : FunSpec({
    val first = CardDefinition.creature("Printing Subject", ManaCost.parse("{0}"), emptySet(), 1, 1)
        .copy(setCode = "AAA", metadata = ScryfallMetadata(collectorNumber = "1"))
    val second = CardDefinition.creature(first.name, ManaCost.parse("{0}"), emptySet(), 3, 3)
        .copy(setCode = "BBB", metadata = ScryfallMetadata(collectorNumber = "1"))
    fun driver() = GameTestDriver().also {
        it.registerCards(TestCards.all + first + second)
        it.initMirrorMatch(Deck.of("Forest" to 40))
        it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    test("same-name printing keys preserve the definition that the stored address resolves") {
        val d = driver()
        for (address in listOf("Printing Subject#AAA-1", "Printing Subject#BBB-1")) {
            val id = d.putCreatureOnBattlefield(d.player1, address)
            val card = d.state.getEntity(id)!!.get<CardComponent>()!!
            d.replaceState(d.state.updateEntity(id) { it.with(card.copy(cardDefinitionId = address)) })
            val projected = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator).transform(d.state, d.player2).cards[id]!!
            d.cardRegistry.getCard(projected.cardDefinitionId!!) shouldBe d.cardRegistry.getCard(address)
            projected.cardDefinitionId shouldBe address
        }
    }
    test("a child bare-name shadow cannot replace an exact parent printing address") {
        val d = driver()
        val address = "Printing Subject#AAA-1"
        val id = d.putCreatureOnBattlefield(d.player1, address)
        val original = d.state.getEntity(id)!!.get<CardComponent>()!!
        d.replaceState(d.state.updateEntity(id) { it.with(original.copy(cardDefinitionId = address)) })
        val shadow = CardDefinition.creature(first.name, ManaCost.parse("{0}"), emptySet(), 7, 7)
        val overlay = CardRegistry(d.cardRegistry).also { it.register(shadow) }
        overlay.getCard(first.name) shouldBe shadow
        overlay.getCard(address) shouldBe first
        val projected = ClientStateTransformer(overlay, predicateEvaluator = PredicateEvaluator(overlay)).transform(d.state, d.player2).cards[id]!!
        overlay.getCard(projected.cardDefinitionId!!) shouldBe overlay.getCard(address)
        projected.cardDefinitionId shouldBe address
    }
})
