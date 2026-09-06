package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SuspensionOwnershipTest : FunSpec({
    val playerId = EntityId.of("player")
    val spellId = EntityId.of("spell")
    val answer = PayLifeOrEnterTappedSpellContinuation(
        spellId = spellId,
        controllerId = playerId,
        ownerId = playerId,
        lifeCost = 2,
    )
    fun question(id: String) = YesNoDecision(
        id = id,
        playerId = playerId,
        prompt = "Pay 2 life to enter untapped?",
        context = DecisionContext(sourceId = spellId, sourceName = "Test land"),
    )
    val json = Json {
        serializersModule = engineSerializersModule
        allowStructuredMapKeys = true
        encodeDefaults = true
    }

    test("fresh suspension allocates one routing handle and stores one question-answer pair") {
        val initial = GameState(rng = GameRng.seeded(42), nextRoutingId = 11L)
        val paused = initial.suspendForDecision(::question, answer)
        val suspension = paused.state.continuationStack.single().shouldBeInstanceOf<Suspension>()

        paused.isPaused shouldBe true
        paused.pendingDecision shouldBeSameInstanceAs suspension.question
        paused.state.pendingDecision shouldBeSameInstanceAs suspension.question
        suspension.question.id shouldBe "r11"
        suspension.answer shouldBe answer
        paused.state.nextRoutingId shouldBe 12L
        paused.state.rng shouldBe initial.rng
        paused.state.nextEntityId shouldBe initial.nextEntityId
        paused.state.nextRandom { nextBoolean() }.first shouldBe initial.nextRandom { nextBoolean() }.first
        paused.state.newEntity().first shouldBe initial.newEntity().first
        paused.events shouldBe listOf(
            DecisionRequestedEvent("r11", playerId, "YES_NO", suspension.question.prompt)
        )
        initial.continuationStack.shouldBeEmpty()
        initial.nextRoutingId shouldBe 11L

        // Gameplay entropy/entity use before the question does not change its routing identity.
        val advanced = initial.nextRandom { nextBoolean() }.second.newEntity().second
        advanced.suspendForDecision(::question, answer).pendingDecision?.id shouldBe "r11"

        val encoded = json.encodeToString(paused.state)
        json.parseToJsonElement(encoded).jsonObject["pendingDecision"] shouldBe null
        val restored = json.decodeFromString<GameState>(encoded)
        restored shouldBe paused.state
        restored.continuationStack.single().shouldBeInstanceOf<Suspension>().answer shouldBe answer
    }

    test("outer pause propagation preserves identity and emits no additional request") {
        val preceding = TappedEvent(spellId, "Test land")
        val paused = GameState().suspendForDecision(::question, answer, events = listOf(preceding))
        var propagated = paused
        repeat(3) {
            propagated = ExecutionResult.propagatePause(propagated.state, propagated.events)
        }

        propagated.state shouldBeSameInstanceAs paused.state
        propagated.pendingDecision shouldBeSameInstanceAs paused.pendingDecision
        propagated.state.nextRoutingId shouldBe 1L
        propagated.events shouldBe paused.events
        propagated.events.first() shouldBe preceding
        propagated.events.filterIsInstance<DecisionRequestedEvent>().size shouldBe 1
        ExecutionResult.propagatePause(paused.state).events.shouldBeEmpty()
    }

    test("automatic work survives beneath a suspension without allocating a handle") {
        val initial = GameState(nextRoutingId = 7L)
        val draw = CycleDrawContinuation(playerId)
        val queued = initial.pushContinuation(draw)
        queued.nextRoutingId shouldBe 7L
        queued.pendingDecision shouldBe null
        queued.peekContinuation() shouldBe draw

        val paused = queued.suspendForDecision(::question, answer)
        paused.state.continuationStack.first() shouldBe draw
        paused.state.continuationStack.last().shouldBeInstanceOf<Suspension>()
        paused.state.nextRoutingId shouldBe 8L
        val (_, afterAnswer) = paused.state.popContinuation()
        afterAnswer.pendingDecision shouldBe null
        afterAnswer.peekContinuation() shouldBe draw
        val (automatic, afterAutomatic) = afterAnswer.popContinuation()
        automatic shouldBe draw
        afterAutomatic.nextRoutingId shouldBe 8L
        afterAutomatic.continuationStack.shouldBeEmpty()
    }

    test("an unanswered suspension cannot be replaced and rejection leaves its input usable") {
        val paused = GameState().suspendForDecision(::question, answer)
        val original = paused.state
        var factoryCalled = false
        shouldThrow<IllegalStateException> {
            original.suspendForDecision(
                question = { id -> factoryCalled = true; question(id) },
                answer = answer,
            )
        }
        factoryCalled shouldBe false
        shouldThrow<IllegalStateException> {
            original.pushContinuation(CycleDrawContinuation(playerId))
        }
        original.pendingDecision shouldBeSameInstanceAs paused.pendingDecision
        original.nextRoutingId shouldBe 1L
        original.continuationStack.single().shouldBeInstanceOf<Suspension>().answer shouldBe answer

        val (_, ready) = original.popContinuation()
        val next = ready.suspendForDecision(::question, answer)
        next.pendingDecision?.id shouldBe "r1"
        next.state.nextRoutingId shouldBe 2L
    }

    test("a malformed question factory cannot consume an identity from its input") {
        val initial = GameState(nextRoutingId = 5L)
        shouldThrow<IllegalArgumentException> {
            initial.suspendForDecision({ question("unallocated") }, answer)
        }
        initial.pendingDecision shouldBe null
        initial.continuationStack.shouldBeEmpty()
        initial.nextRoutingId shouldBe 5L
        initial.suspendForDecision(::question, answer).pendingDecision?.id shouldBe "r5"
    }
})
