package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CharacteristicValue
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.builtins.ListSerializer

class SeatReferencesTest : StringSpec({
    val old = EntityId("e1"); val fresh = EntityId("h1"); val player = EntityId("p1")
    val rename: (String) -> String? = { if (it == old.value) fresh.value else null }

    "unchanged typed objects are shared and collections preserve nulls and order" {
        val facts = RuleFacts(targetGroups = listOf(listOf(old, null, player), listOf(old)))
        SeatReferences.map(RuleFacts.serializer(), facts) { null } shouldBeSameInstanceAs facts
        SeatReferences.map(RuleFacts.serializer(), facts, rename) shouldBe facts.copy(
            targetGroups = listOf(listOf(fresh, null, player), listOf(fresh)))
        facts.targetGroups.first().first() shouldBe old
    }
    "polymorphic action references and their serialized string leaves are renamed together" {
        val action: GameAction = CastSpell(player, old, targets = listOf(ChosenTarget.Permanent(old), ChosenTarget.Player(player)))
        SeatReferences.map(GameAction.serializer(), action, rename) shouldBe (action as CastSpell).copy(
            cardId = fresh, targets = listOf(ChosenTarget.Permanent(fresh), ChosenTarget.Player(player)))
        SeatReferences.strings(GameAction.serializer(), action).containsAll(listOf(old.value, player.value)) shouldBe true
        val target = EffectTarget.SpecificEntity(old)
        SeatReferences.map(EffectTarget.serializer(), target, rename) shouldBe EffectTarget.SpecificEntity(fresh)
    }
    "events retain order and SDK compact scalar values need no JSON decoder" {
        val events = listOf<ClientEvent>(ClientEvent.SpellCast(old, "Card", player),
            ClientEvent.LifeChanged(player, 20, 19, -1))
        SeatReferences.map(ListSerializer(ClientEvent.serializer()), events, rename) shouldBe listOf(
            (events[0] as ClientEvent.SpellCast).copy(spellId = fresh), events[1])
        val scalar = CharacteristicValue.Fixed(3)
        SeatReferences.map(CharacteristicValue.serializer(), scalar, rename) shouldBe scalar
    }
})
