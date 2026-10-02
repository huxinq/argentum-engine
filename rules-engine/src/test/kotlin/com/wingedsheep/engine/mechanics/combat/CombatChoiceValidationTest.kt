package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CombatChoiceValidationTest : FunSpec({
    val player = EntityId("player")
    val attacker = EntityId("attacker")
    val first = EntityId("first")
    val second = EntityId("second")
    val edges = listOf(
        DamageEdge("first", attacker, first, DamageEdgeDirection.ATTACKER_TO_BLOCKER, 2, 5, 2, true, false, player),
        DamageEdge("second", attacker, second, DamageEdgeDirection.ATTACKER_TO_BLOCKER, 3, 5, 3, true, false, player),
        DamageEdge("drain", attacker, player, DamageEdgeDirection.ATTACKER_TO_PLAYER, 0, 5, 0, false, true, player),
    )
    val decision = CombatResolutionDecision("combat", player, "Assign damage", DecisionContext(),
        false, emptyList(), emptyList(), emptyList(), edges)

    test("singleton damage bounds preserve exact budget and lethal validation") {
        fun bounds(vararg amounts: Int) = edges.zip(amounts.toList()).associate { (edge, amount) -> edge.id to amount..amount }
        DecisionValidators.validateDamageBounds(decision, bounds(2, 3, 0)).shouldBeNull()
        DecisionValidators.validateDamageBounds(decision, bounds(3, 3, 0)).shouldNotBeNull()
        DecisionValidators.validateDamageBounds(decision, bounds(1, 1, 3)).shouldNotBeNull()
        for (a in 0..5) for (b in 0..5) for (c in 0..5) {
            val values = listOf(a, b, c)
            val response = CombatResolutionResponse(decision.id, edges.zip(values).map { (edge, amount) -> DamageEdgeAmount(edge.id, amount) })
            DecisionValidators.validateDamageBounds(decision, bounds(a, b, c)) shouldBe
                DecisionValidators.validate(decision, response)
        }
    }

    test("partial damage bounds are optimistic while rejecting impossible fixed budgets") {
        DecisionValidators.validateDamageBounds(decision, mapOf("first" to 0..2, "second" to 0..3, "drain" to 0..5)).shouldBeNull()
        DecisionValidators.validateDamageBounds(decision, mapOf("first" to 3..5, "second" to 3..5, "drain" to 0..5)).shouldNotBeNull()
        DecisionValidators.validateDamageBounds(decision, mapOf("first" to 0..1, "second" to 0..3, "drain" to 1..5)).shouldNotBeNull()
    }

    test("read-only block validation agrees with execution for menace") {
        val menace = CardDefinition.creature("Validation Menace", ManaCost.parse("{1}"), emptySet(), 3, 3).copy(keywords = setOf(Keyword.MENACE))
        val blocker = CardDefinition.creature("Validation Blocker", ManaCost.parse("{1}"), emptySet(), 2, 2)
        val d = GameTestDriver()
        d.registerCards(TestCards.all + listOf(menace, blocker))
        d.initMirrorMatch(Deck.of("Forest" to 40))
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val source = d.putCreatureOnBattlefield(d.player1, menace.name)
        val a = d.putCreatureOnBattlefield(d.player2, blocker.name)
        val b = d.putCreatureOnBattlefield(d.player2, blocker.name)
        d.removeSummoningSickness(source)
        d.passPriorityUntil(Step.DECLARE_ATTACKERS)
        d.declareAttackers(d.player1, listOf(source), d.player2)
        d.passPriorityUntil(Step.DECLARE_BLOCKERS)
        val before = d.state
        val single = mapOf(a to listOf(source))
        d.services.combatManager.validateBlockDeclaration(before, d.player2, single).shouldNotBeNull()
        d.services.combatManager.declareBlockers(before, d.player2, single).error.shouldNotBeNull()
        val pair = single + (b to listOf(source))
        d.services.combatManager.validateBlockDeclaration(before, d.player2, pair).shouldBeNull()
        d.state shouldBe before
        d.services.combatManager.declareBlockers(before, d.player2, pair).error.shouldBeNull()
    }
})
