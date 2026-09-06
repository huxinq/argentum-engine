package com.wingedsheep.gameserver.persistence

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.LeylineDecisionContinuation
import com.wingedsheep.engine.core.LeylinePhaseContinuation
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class GameStatePersistenceFormatTest : FunSpec({
    val player = EntityId("player")
    val question: PendingDecision = YesNoDecision(
        id = "old-question", playerId = player, prompt = "Begin with this leyline?", context = DecisionContext(),
    )

    test("persistence JSON roundtrips current execution and permits unrelated unknown fields") {
        val current = GameState(nextRoutingId = 23).pushContinuation(LeylinePhaseContinuation).suspendForDecision(
            question = { id -> (question as YesNoDecision).copy(id = id) },
            answer = LeylineDecisionContinuation(player, EntityId("leyline"), "Test leyline"),
        ).state
        val encoded = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(current)).jsonObject
        val extended = JsonObject(encoded + ("unrelatedMetadata" to JsonPrimitive("ignored")))
        val restored = persistenceJson.decodeFromString<GameState>(extended.toString())
        restored shouldBe current
        restored.nextRoutingId shouldBe 24L
        restored.pendingDecision shouldBe current.pendingDecision
    }

    test("generic persistence decoding cannot silently lose an old question without an answer frame") {
        // Keep the actual generic persistence path: this is invalid even for a future legacy reader,
        // because the valid question has no represented operation that can consume its answer.
        val encoded = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(GameState())).jsonObject
        val old = JsonObject(encoded + (
            "pendingDecision" to persistenceJson.parseToJsonElement(persistenceJson.encodeToString(question))
        ))
        shouldThrow<SerializationException> {
            persistenceJson.decodeFromString<GameState>(old.toString())
        }
    }
})
