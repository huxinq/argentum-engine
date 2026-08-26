package com.wingedsheep.engine.replay

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanonicalReplayTest : FunSpec({
    test("canonical JSON patches reconstruct object and array changes exactly") {
        val before = JsonObject(
            mapOf(
                "kept" to JsonPrimitive(1),
                "removed" to JsonPrimitive(true),
                "array" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("d"))),
            )
        )
        val after = JsonObject(
            mapOf(
                "added" to JsonPrimitive("new"),
                "kept" to JsonPrimitive(2),
                "array" to JsonArray(
                    listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"), JsonPrimitive("d"))
                ),
            )
        )

        val patch = ReplayCanonicalJson.diff(before, after)

        ReplayCanonicalJson.apply(before, patch) shouldBe ReplayCanonicalJson.canonicalize(after)
        patch.filterIsInstance<ReplayPatchSplice>() shouldHaveSize 1
    }

    test("recorded choices and states reconstruct without engine re-execution") {
        val initial = GameState(initialSeed = 17L, turnNumber = 1)
        val recorder = CanonicalReplayRecorder(
            gameId = "game-1",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "rules-test",
            players = listOf("p0", "p1"),
            initialState = initial,
        )
        val rejected = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = false,
            rejectionReason = "not your priority",
            resultingState = initial,
        )
        val terminalState = initial.copy(turnNumber = 2, gameOver = true, winnerId = EntityId("p0"))
        val accepted = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = terminalState,
        )
        val terminal = recorder.finish(
            status = ReplayCompletionStatus.COMPLETE,
            finalState = terminalState,
            winnerId = "p0",
        )

        val replay = CanonicalReplayReconstructor.reconstruct(
            listOf(recorder.header, rejected, accepted, terminal)
        )

        replay.stateAt(0) shouldBe initial
        replay.stateAt(1) shouldBe initial
        replay.stateAt(2) shouldBe terminalState
        replay.transitions.map { it.action } shouldBe listOf(
            PassPriority(EntityId("p0")),
            PassPriority(EntityId("p0")),
        )
        (rejected.state as ReplayPatchedState).operations shouldHaveSize 0
    }

    test("tampered state patches fail their state digest") {
        val initial = GameState(initialSeed = 2L)
        val recorder = CanonicalReplayRecorder(
            gameId = "tamper",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "rules-test",
            players = listOf("p0"),
            initialState = initial,
        )
        val final = initial.copy(turnNumber = 1, gameOver = true)
        val transition = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = final,
        )
        val terminal = recorder.finish(ReplayCompletionStatus.COMPLETE, final)
        val changedEncoding = ReplayPatchedState(listOf(ReplayPatchSet("/turnNumber", JsonPrimitive(99))))
        val unsignedTampered = transition.copy(state = changedEncoding, recordDigest = "")
        val tampered = unsignedTampered.copy(recordDigest = ReplayRecordDigests.of(unsignedTampered))
        val unsignedTerminal = terminal.copy(previousRecordDigest = tampered.recordDigest, recordDigest = "")
        val chainedTerminal = unsignedTerminal.copy(recordDigest = ReplayRecordDigests.of(unsignedTerminal))

        shouldThrow<IllegalArgumentException> {
            CanonicalReplayReconstructor.reconstruct(listOf(recorder.header, tampered, chainedTerminal))
        }
    }

    test("validated prefixes resume and force a full checkpoint every 128 transitions") {
        val initial = GameState(initialSeed = 9L)
        val recorder = CanonicalReplayRecorder(
            gameId = "resume",
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "rules-test",
            players = listOf("p0"),
            initialState = initial,
        )
        val transitions = (0..128).map { index ->
            recorder.appendAction(
                origin = ReplayTransitionOrigin.PLAYER,
                action = PassPriority(EntityId("p0")),
                accepted = true,
                resultingState = initial.copy(turnNumber = index + 1),
            )
        }
        val prefix = listOf(recorder.header) + transitions

        CanonicalReplayReconstructor.reconstructPrefix(prefix).stateAt(129) shouldBe
            initial.copy(turnNumber = 129)
        transitions[128].state::class shouldBe ReplayFullState::class
        CanonicalReplayRecorder.resume(prefix).appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = initial.copy(turnNumber = 130),
        ).ordinal shouldBe 129
    }
})
