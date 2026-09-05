package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackedThisCombatComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ManaExpiry
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CombatCleanupTest : FunSpec({
    val registry = CardRegistry()
    val manager = CombatManager(registry, ManaAbilitySideEffectExecutor(registry) { _, _, _ ->
        error("Combat cleanup must not execute mana-ability side effects")
    })

    test("combat cleanup preserves entity order and source while clearing markers and combat mana") {
        val player = EntityId.of("player")
        val attacker = EntityId.of("attacker")
        val ordinary = EntityId.of("ordinary")
        val combatMana = RestrictedManaEntry(Color.RED, ManaRestriction.AnySpend, expiry = ManaExpiry.END_OF_COMBAT)
        val turnMana = RestrictedManaEntry(Color.BLUE, ManaRestriction.AnySpend)
        val pool = ManaPoolComponent(green = 2, restrictedMana = listOf(combatMana, turnMana))
        val entities = linkedMapOf(
            attacker to ComponentContainer.of(AttackingComponent(player), LifeTotalComponent(3), AttackedThisCombatComponent),
            player to ComponentContainer.of(LifeTotalComponent(20), pool),
            ordinary to ComponentContainer.of(LifeTotalComponent(7)),
        )
        val source = GameState(entities = entities, turnOrder = listOf(player))
        val result = manager.endCombat(source)

        result.events shouldBe emptyList()
        result.state.entities.keys.toList() shouldBe entities.keys.toList()
        result.state.getEntity(attacker)!!.all().toList() shouldBe listOf(LifeTotalComponent(3))
        result.state.getEntity(ordinary) shouldBe entities[ordinary]
        result.state.getEntity(player)!!.all().toList() shouldBe listOf(
            LifeTotalComponent(20), pool.copy(restrictedMana = listOf(turnMana))
        )
        source.getEntity(attacker)!!.has<AttackingComponent>() shouldBe true
        source.getEntity(player)!!.get<ManaPoolComponent>() shouldBe pool
        val retained = result.state.entities.toMap()
        manager.endCombat(source)
        result.state.entities shouldBe retained
    }

    test("empty combat cleanup retains the state") {
        val state = GameState()
        (manager.endCombat(state).state === state) shouldBe true
    }
})
