package com.wingedsheep.gameserver.persistence

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.LeylineDecisionContinuation
import com.wingedsheep.engine.core.LeylinePhaseContinuation
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class LegacyGameStatePersistenceTest : FunSpec({
    test("generic persistence decoding migrates an old paused game and writes only the current format") {
        val player = EntityId("player")
        val current = GameState(nextRoutingId = 23).pushContinuation(LeylinePhaseContinuation).suspendForDecision(
            question = { id -> YesNoDecision(id, player, "Begin with this leyline?", DecisionContext()) },
            answer = LeylineDecisionContinuation(player, EntityId("leyline"), "Test leyline"),
        ).state
        val encoded = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(current)).jsonObject
        val frames = encoded.getValue("continuationStack").jsonArray
        val suspension = frames.last().jsonObject
        val question = suspension.getValue("question").jsonObject
        val old = JsonObject(encoded + mapOf(
            "pendingDecision" to question,
            "continuationStack" to JsonArray(listOf(
                JsonObject(frames.first().jsonObject + ("decisionId" to JsonPrimitive("old-automatic"))),
                JsonObject(suspension.getValue("answer").jsonObject + ("decisionId" to question.getValue("id"))),
            )),
            "unrelatedMetadata" to JsonPrimitive("ignored"),
        ))
        val restored = persistenceJson.decodeFromString<GameState>(old.toString())
        restored shouldBe current
        val rewritten = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(restored)).jsonObject
        rewritten shouldBe encoded
        rewritten.containsKey("pendingDecision") shouldBe false
    }
})
