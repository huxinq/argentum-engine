package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.FunSpec

class InFlightEntityReferencesTest : FunSpec({

    test("projection follows typed references in a serializable live stack component") {
        val casterId = EntityId.of("caster")
        val handCardId = EntityId.of("chosen-hand-card")
        val stackObject = ComponentContainer.of(
            SpellOnStackComponent(
                casterId = casterId,
                beheldCards = listOf(handCardId),
            ),
        )

        InFlightEntityReferences.project(stackObject)
            .shouldBeInstanceOf<InFlightEntityReferences.Projection.Complete>()
            .entityIds shouldBe setOf(casterId, handCardId)
    }

    test("projection follows typed nullable nested and map-key entity references only") {
        val playerId = EntityId.of("player")
        val mapKeyId = EntityId.of("map-key")
        val nestedId = EntityId.of("nested")
        val nullableId = EntityId.of("nullable")
        val stringOnlyId = EntityId.of("looks-like-an-id")

        val decision = DistributeDecision(
            id = "decision",
            playerId = playerId,
            prompt = stringOnlyId.value,
            context = DecisionContext(
                sourceId = nullableId,
                sourceName = stringOnlyId.value,
                triggeringEntityId = null,
            ),
            totalAmount = 1,
            targets = listOf(nestedId),
            maxPerTarget = mapOf(mapKeyId to 1),
        )
        val continuation = SelectFromCollectionContinuation(
            decisionId = "continuation",
            playerId = playerId,
            sourceId = nullableId,
            sourceName = stringOnlyId.value,
            allCards = emptyList(),
            storeSelected = "selected",
            storeRemainder = null,
            storedCollections = mapOf(stringOnlyId.value to listOf(nestedId)),
        )

        InFlightEntityReferences.project(decision)
            .shouldBeInstanceOf<InFlightEntityReferences.Projection.Complete>()
            .entityIds shouldBe setOf(playerId, nullableId, nestedId, mapKeyId)
        InFlightEntityReferences.project(continuation)
            .shouldBeInstanceOf<InFlightEntityReferences.Projection.Complete>()
            .entityIds shouldBe setOf(playerId, nullableId, nestedId)
        InFlightEntityReferences.project(continuation.copy(sourceId = null))
            .shouldBeInstanceOf<InFlightEntityReferences.Projection.Complete>()
            .entityIds shouldBe setOf(playerId, nestedId)
    }
})
