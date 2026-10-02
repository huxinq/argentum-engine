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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

class SeatReferencesTest : StringSpec({
    val old = EntityId("e1"); val fresh = EntityId("h1"); val player = EntityId("p1")
    val rename: (String) -> String? = { if (it == old.value) fresh.value else null }

    "unchanged typed objects are shared and collections preserve nulls and order" {
        val serializer = ListSerializer(ListSerializer(EntityId.serializer().nullable))
        val facts = listOf(listOf(old, null, player), listOf(old))
        SeatReferences.map(serializer, facts) { null } shouldBeSameInstanceAs facts
        SeatReferences.map(serializer, facts, rename) shouldBe
            listOf(listOf(fresh, null, player), listOf(fresh))
        facts.first().first() shouldBe old
    }
    "polymorphic action references and their serialized string leaves are renamed together" {
        val action: GameAction = CastSpell(player, old, targets = listOf(ChosenTarget.Permanent(old), ChosenTarget.Player(player)))
        SeatReferences.map(GameAction.serializer(), action, rename) shouldBe (action as CastSpell).copy(
            cardId = fresh, targets = listOf(ChosenTarget.Permanent(fresh), ChosenTarget.Player(player)))
        SeatReferences.strings(GameAction.serializer(), action).containsAll(listOf(old.value, player.value)) shouldBe true
        val target = EffectTarget.SpecificEntity(old)
        SeatReferences.map(EffectTarget.serializer(), target, rename) shouldBe EffectTarget.SpecificEntity(fresh)
    }
    "typed mapping matches the prior JSON key and string-leaf traversal" {
        val json = Json { encodeDefaults = true; classDiscriminator = "type"; serializersModule = engineSerializersModule }
        fun legacy(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.entries.associate { (key, value) -> (rename(key) ?: key) to legacy(value) })
            is JsonArray -> JsonArray(e.map(::legacy))
            is JsonPrimitive -> if (e.isString) rename(e.content)?.let(::JsonPrimitive) ?: e else e
        }
        fun <T> compare(serializer: KSerializer<T>, value: T) {
            SeatReferences.map(serializer, value, rename) shouldBe
                json.decodeFromJsonElement(serializer, legacy(json.encodeToJsonElement(serializer, value)))
        }
        compare(MapSerializer(EntityId.serializer(), ListSerializer(EntityId.serializer().nullable)),
            mapOf(old to listOf(old, null, player)))
        compare(GameAction.serializer(), CastSpell(player, old, targets = listOf(ChosenTarget.Permanent(old))))
        compare(EffectTarget.serializer(), EffectTarget.SpecificEntity(old))
        compare(CharacteristicValue.serializer(), CharacteristicValue.Fixed(3))
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
