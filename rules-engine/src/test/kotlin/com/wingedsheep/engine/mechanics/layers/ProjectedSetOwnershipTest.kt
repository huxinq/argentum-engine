package com.wingedsheep.engine.mechanics.layers

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TextReplacement
import com.wingedsheep.engine.state.components.identity.TextReplacementCategory
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProjectedSetOwnershipTest : FunSpec({
    test("final characteristic sets stay isolated across entities and later projections") {
        val owner = EntityId.of("owner")
        val first = EntityId.of("first")
        val second = EntityId.of("second")
        val definition = card("Projection Ownership Witness") {
            manaCost = "{W}{U}"
            typeLine = "Legendary Artifact Creature — Bear Warrior"
            power = 2
            toughness = 2
        }
        val factory = CardEntityFactory.create(definition, owner)
        val card = factory.require<CardComponent>().copy(
            baseKeywords = linkedSetOf(Keyword.HASTE, Keyword.HEXPROOF),
            colors = linkedSetOf(Color.BLUE, Color.WHITE),
        )
        val container = factory.with(card)
        val source = GameState(
            entities = linkedMapOf(first to container, second to container),
            zones = mapOf(ZoneKey(owner, Zone.BATTLEFIELD) to listOf(first, second)),
        )
        val projector = StateProjector()
        val retained = projector.project(source)
        val changed = source.withEntity(first, container.with(card.copy(
            baseKeywords = linkedSetOf(Keyword.FLYING),
            colors = linkedSetOf(Color.RED),
        )).with(TextReplacementComponent(listOf(
            TextReplacement("Bear", "Goblin", TextReplacementCategory.CREATURE_TYPE),
        ))))
        val later = projector.project(changed)

        later.getKeywords(first).toList() shouldBe listOf("FLYING")
        later.getColors(first).toList() shouldBe listOf("RED")
        later.getTypes(first).toList() shouldBe listOf("LEGENDARY", "ARTIFACT", "CREATURE", "Goblin", "Warrior")
        later.getSubtypes(first).toList() shouldBe listOf("Goblin", "Warrior")
        for ((projection, id) in listOf(retained to first, retained to second, later to second)) {
            projection.getKeywords(id).toList() shouldBe listOf("HASTE", "HEXPROOF")
            projection.getColors(id).toList() shouldBe listOf("BLUE", "WHITE")
            projection.getTypes(id).toList() shouldBe listOf("LEGENDARY", "ARTIFACT", "CREATURE", "Bear", "Warrior")
            projection.getSubtypes(id).toList() shouldBe listOf("Bear", "Warrior")
        }
        projector.project(source).getAllProjectedValues() shouldBe retained.getAllProjectedValues()
        source.getEntity(first) shouldBe container
        source.getEntity(second) shouldBe container
        card.baseKeywords.toList() shouldBe listOf(Keyword.HASTE, Keyword.HEXPROOF)
        card.colors.toList() shouldBe listOf(Color.BLUE, Color.WHITE)
    }
})
