package com.wingedsheep.engine.state

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.LeylineDecisionContinuation
import com.wingedsheep.engine.core.LeylinePhaseContinuation
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class GameStateFormatTest : FunSpec({
    val json = Json {
        serializersModule = engineSerializersModule
        allowStructuredMapKeys = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    val player = EntityId("player")
    val question: PendingDecision = YesNoDecision(
        id = "old-question", playerId = player, prompt = "Begin with this leyline?", context = DecisionContext(),
    )
    val current = GameState(nextRoutingId = 17).pushContinuation(LeylinePhaseContinuation).suspendForDecision(
        question = { id -> (question as YesNoDecision).copy(id = id) },
        answer = LeylineDecisionContinuation(player, EntityId("leyline"), "Test leyline"),
    ).state

    test("current execution roundtrips without allocation while unrelated unknown fields remain permitted") {
        val encoded = json.parseToJsonElement(json.encodeToString(current)).jsonObject
        encoded.containsKey("pendingDecision") shouldBe false
        val extended = JsonObject(encoded + ("unrelatedMetadata" to JsonPrimitive("ignored")))
        val restored = json.decodeFromString<GameState>(extended.toString())
        restored shouldBe current
        restored.nextRoutingId shouldBe 18L
        restored.pendingDecision shouldBe current.pendingDecision
    }

    test("explicit current reader rejects the obsolete root field even when null") {
        val encoded = json.parseToJsonElement(json.encodeToString(GameState())).jsonObject
        shouldThrow<UnsupportedGameStateFormatException> {
            json.decodeFromString(GameStateSerializer, JsonObject(encoded + ("pendingDecision" to JsonNull)).toString())
        }
    }

    test("explicit current reader rejects legacy automatic work with a decision ID") {
        val encoded = json.parseToJsonElement(json.encodeToString(GameState())).jsonObject
        val oldFrame = JsonObject(mapOf(
            "type" to JsonPrimitive("com.wingedsheep.engine.core.LeylinePhaseContinuation"),
            "decisionId" to JsonPrimitive("old-automatic-work"),
        ))
        shouldThrow<UnsupportedGameStateFormatException> {
            json.decodeFromString(GameStateSerializer, JsonObject(encoded + (
                "continuationStack" to JsonArray(listOf(oldFrame))
            )).toString())
        }
    }

    test("generic permissive decoding cannot discard an old represented question without an answer") {
        val encoded = json.parseToJsonElement(json.encodeToString(GameState())).jsonObject
        val old = JsonObject(encoded + (
            "pendingDecision" to json.parseToJsonElement(json.encodeToString(question))
        ))
        shouldThrow<kotlinx.serialization.SerializationException> {
            json.decodeFromString<GameState>(old.toString())
        }
    }
})
