package com.wingedsheep.engine.mechanics.layers

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DashedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.HexproofFromComponent
import com.wingedsheep.engine.state.components.identity.ProtectionComponent
import com.wingedsheep.engine.state.components.identity.ToxicComponent
import com.wingedsheep.sdk.core.AbilityFlag
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProjectedSetInitializationTest : FunSpec({
    test("projection retains ordered encoded keywords, duplicate collapse, colors, and subtypes") {
        val owner = EntityId.of("owner")
        val id = EntityId.of("permanent")
        val definition = card("Projection Set Witness") {
            manaCost = "{W}{U}"
            typeLine = "Legendary Artifact Creature — Bear Warrior"
            power = 2
            toughness = 2
        }
        val original = CardEntityFactory.create(definition, owner)
        val container = original.with(original.require<CardComponent>().copy(
            baseKeywords = linkedSetOf(Keyword.HASTE, Keyword.HEXPROOF),
            baseFlags = linkedSetOf(AbilityFlag.CANT_BE_BLOCKED),
            colors = linkedSetOf(Color.BLUE, Color.WHITE),
        )).with(ProtectionComponent(
            colors = linkedSetOf(Color.BLUE, Color.RED),
            subtypes = linkedSetOf("Goblin"),
            supertypes = linkedSetOf("Legendary"),
            cardTypes = linkedSetOf("ARTIFACT", "creature"),
        )).with(HexproofFromComponent(
            colors = linkedSetOf(Color.WHITE),
            cardTypes = linkedSetOf("INSTANT", "sorcery"),
        )).with(ToxicComponent(0)).with(DashedComponent)
        val source = GameState(
            entities = mapOf(id to container),
            zones = mapOf(ZoneKey(owner, Zone.BATTLEFIELD) to listOf(id)),
        )
        val projected = StateProjector().project(source)
        projected.getKeywords(id).toList() shouldBe listOf(
            "HASTE", "HEXPROOF", "CANT_BE_BLOCKED",
            "PROTECTION_FROM_BLUE", "PROTECTION_FROM_RED", "PROTECTION_FROM_SUBTYPE_GOBLIN",
            "PROTECTION_FROM_SUPERTYPE_LEGENDARY", "PROTECTION_FROM_CARDTYPE_ARTIFACT",
            "PROTECTION_FROM_CARDTYPE_creature", "HEXPROOF_FROM_WHITE",
            "HEXPROOF_FROM_CARDTYPE_INSTANT", "HEXPROOF_FROM_CARDTYPE_sorcery", "TOXIC_0",
        )
        projected.getColors(id).toList() shouldBe listOf("BLUE", "WHITE")
        projected.getSubtypes(id).toList() shouldBe listOf("Bear", "Warrior")
        source.getEntity(id) shouldBe container
    }
})
