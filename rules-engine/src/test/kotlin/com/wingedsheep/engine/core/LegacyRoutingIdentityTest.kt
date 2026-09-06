package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.EffectHandler
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.CreateDelayedTriggerEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Legacy UUID routing survives the snapshot representation conversion. */
class LegacyRoutingIdentityTest : ScenarioTestBase() {
    private val json = Json {
        serializersModule = engineSerializersModule
        allowStructuredMapKeys = true
        encodeDefaults = true
    }
    init {
        test("a legacy pending UUID decision resumes before allocating the first new routing handle") {
            val game = scenario().withPlayers().build()
            val paused = EffectHandler(cardRegistry = cardRegistry).execute(
                game.state,
                MayEffect(MayEffect(Effects.GainLife(2))),
                EffectContext(sourceId = null, controllerId = game.player1Id)
            )
            paused.error shouldBe null
            val oldId = "73b0b4a6-5d8a-4f36-9870-6c63852a1927"
            val oldDecision = paused.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>().copy(id = oldId)
            val suspension = paused.state.continuationStack.single().shouldBeInstanceOf<Suspension>()
            val oldContinuation = suspension.answer.shouldBeInstanceOf<GatedEffectContinuation>()
            val encoded = json.parseToJsonElement(json.encodeToString(paused.state)).jsonObject
            val encodedSuspension = encoded.getValue("continuationStack").jsonArray.single().jsonObject
            // Exercise the actual old wire shape: independent pending question plus answer frame
            // with a matching UUID, and no routing counter. New state cannot construct that shape.
            val legacy = JsonObject(
                (encoded - "nextRoutingId") + mapOf(
                    "pendingDecision" to JsonObject(
                        encodedSuspension.getValue("question").jsonObject + ("id" to JsonPrimitive(oldId))
                    ),
                    "continuationStack" to JsonArray(listOf(JsonObject(
                        encodedSuspension.getValue("answer").jsonObject + ("decisionId" to JsonPrimitive(oldId))
                    ))),
                )
            )
            val restoredLegacy = json.decodeFromString<GameState>(legacy.toString())
            restoredLegacy.nextRoutingId shouldBe 0L
            restoredLegacy.pendingDecision shouldBe oldDecision
            restoredLegacy.continuationStack shouldBe listOf(Suspension(oldDecision, oldContinuation))
            val oldResponse = SubmitDecision(game.player1Id, YesNoResponse(oldId, true))
            val resumed = actionProcessor.process(restoredLegacy, oldResponse).result
            resumed.error shouldBe null
            val newDecision = resumed.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            newDecision.id shouldBe "r0"
            newDecision.id shouldNotBe oldId
            resumed.state.nextRoutingId shouldBe 1L
            resumed.state.rng shouldBe paused.state.rng
            resumed.state.nextEntityId shouldBe paused.state.nextEntityId
            val stale = actionProcessor.process(resumed.state, oldResponse).result
            stale.error.shouldNotBeNull()
            stale.state shouldBe resumed.state
            stale.events.shouldBeEmpty()
            val completed = actionProcessor.process(
                resumed.state, SubmitDecision(game.player1Id, YesNoResponse(newDecision.id, true))
            ).result
            completed.error shouldBe null
            completed.state.pendingDecision shouldBe null
            completed.state.lifeTotal(game.player1Id) shouldBe 22
        }

    }
}
