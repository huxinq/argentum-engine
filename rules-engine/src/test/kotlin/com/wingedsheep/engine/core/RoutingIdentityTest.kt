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

/** Routing handles must reproduce without spending gameplay entropy or entity identity. */
class RoutingIdentityTest : ScenarioTestBase() {
    private val json = Json {
        serializersModule = engineSerializersModule
        allowStructuredMapKeys = true
        encodeDefaults = true
    }

    init {
        test("routing allocation is independent of gameplay randomness and entity allocation") {
            val initial = GameState(rng = GameRng.seeded(42))
            val (first, allocated) = initial.newRoutingId()
            first shouldBe "r0"
            val (second, twiceAllocated) = allocated.newRoutingId()
            second shouldBe "r1"
            initial.nextRoutingId shouldBe 0L
            twiceAllocated.nextRoutingId shouldBe 2L
            twiceAllocated.rng shouldBe initial.rng
            twiceAllocated.nextEntityId shouldBe initial.nextEntityId
            twiceAllocated.nextRandom { nextBoolean() }.first shouldBe
                initial.nextRandom { nextBoolean() }.first
            twiceAllocated.newEntity().first shouldBe initial.newEntity().first

            val advanced = initial.nextRandom { nextBoolean() }.second.newEntity().second
            advanced.newRoutingId().first shouldBe first
            advanced.newRoutingId().second.nextRoutingId shouldBe 1L
        }

        test("routing allocation fails before the counter can overflow or reuse handles") {
            val last = GameState(nextRoutingId = Long.MAX_VALUE - 1)
            val (id, exhausted) = last.newRoutingId()
            id shouldBe "r${Long.MAX_VALUE - 1}"
            exhausted.nextRoutingId shouldBe Long.MAX_VALUE
            shouldThrow<IllegalStateException> { exhausted.newRoutingId() }
            shouldThrow<IllegalStateException> { GameState(nextRoutingId = -1).newRoutingId() }
        }

        test("serialized routing allocation resumes at the saved counter") {
            val state = GameState(rng = GameRng.seeded(17)).newRoutingId().second.newRoutingId().second
            val restored = json.decodeFromString<GameState>(json.encodeToString(state))
            restored shouldBe state
            restored.newRoutingId() shouldBe state.newRoutingId()
            restored.newRoutingId().first shouldBe "r2"

        }

        test("a current-format opaque UUID decision resumes before allocating the first routing handle") {
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
            val current = paused.state.copy(
                nextRoutingId = 0,
                continuationStack = listOf(Suspension(oldDecision, oldContinuation))
            )
            val restored = json.decodeFromString<GameState>(json.encodeToString(current))
            restored.nextRoutingId shouldBe 0L
            restored.pendingDecision shouldBe oldDecision
            restored.continuationStack shouldBe listOf(Suspension(oldDecision, oldContinuation))
            val oldResponse = SubmitDecision(game.player1Id, YesNoResponse(oldId, true))
            val resumed = actionProcessor.process(restored, oldResponse).result
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

        test("real consecutive decisions replay with recorded responses and reject a stale response") {
            val game = scenario().withPlayers().build()
            val initial = game.state
            val handler = EffectHandler(cardRegistry = cardRegistry)
            val effect = CompositeEffect(listOf(
                MayEffect(Effects.GainLife(1)),
                MayEffect(Effects.GainLife(2))
            ))
            val context = EffectContext(sourceId = null, controllerId = game.player1Id)
            val first = handler.execute(initial, effect, context)
            val replayFirst = handler.execute(initial, effect, context)
            first.error shouldBe null
            val decision = first.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            first.state shouldBe replayFirst.state
            first.events shouldBe replayFirst.events
            first.state.rng shouldBe initial.rng
            first.state.nextEntityId shouldBe initial.nextEntityId

            val recorded = SubmitDecision(game.player1Id, YesNoResponse(decision.id, true))
            val recordedJson = json.encodeToString(recorded)
            val resumed = actionProcessor.process(first.state, recorded).result
            val restored = json.decodeFromString<GameState>(json.encodeToString(replayFirst.state))
            val replayResumed = actionProcessor.process(
                restored, json.decodeFromString<SubmitDecision>(recordedJson)
            ).result
            resumed.error shouldBe null
            resumed.state shouldBe replayResumed.state
            resumed.events shouldBe replayResumed.events
            resumed.state.lifeTotal(game.player1Id) shouldBe 21
            val nextDecision = resumed.state.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            nextDecision.id shouldNotBe decision.id

            val stale = actionProcessor.process(resumed.state, recorded).result
            stale.error.shouldNotBeNull()
            stale.state shouldBe resumed.state
            stale.events.shouldBeEmpty()

            val nextResponse = SubmitDecision(game.player1Id, YesNoResponse(nextDecision.id, true))
            val completed = actionProcessor.process(resumed.state, nextResponse).result
            val replayCompleted = actionProcessor.process(replayResumed.state, nextResponse).result
            completed.error shouldBe null
            completed.state.pendingDecision shouldBe null
            completed.state.lifeTotal(game.player1Id) shouldBe 23
            completed.state shouldBe replayCompleted.state
            completed.events shouldBe replayCompleted.events
        }

        test("multiple delayed triggers retain distinct reproducible handles and watched references") {
            val game = scenario().withPlayers().withCardOnBattlefield(1, "Grizzly Bears").build()
            val source = game.findPermanent("Grizzly Bears")!!
            val context = EffectContext(
                sourceId = source,
                controllerId = game.player1Id,
                targets = listOf(ChosenTarget.Permanent(source))
            )
            val delayed = CreateDelayedTriggerEffect(
                step = Step.END,
                effect = Effects.GainLife(1),
                watchedTarget = EffectTarget.ContextTarget(0)
            )
            val handler = EffectHandler(cardRegistry = cardRegistry)
            val effect = CompositeEffect(listOf(delayed, delayed))
            val created = handler.execute(game.state, effect, context)
            val replay = handler.execute(game.state, effect, context)
            created.error shouldBe null
            created.state shouldBe replay.state
            created.events shouldBe replay.events
            val triggers = created.state.delayedTriggers
            triggers.size shouldBe 2
            triggers.map { it.id }.toSet().size shouldBe 2
            triggers.map { it.watchedEntityId } shouldBe listOf(source, source)
            triggers.map { it.sourceId } shouldBe listOf(source, source)
            created.state.nextRoutingId shouldBe game.state.nextRoutingId + 2
            created.state.nextEntityId shouldBe game.state.nextEntityId
            created.state.rng shouldBe game.state.rng
            json.decodeFromString<GameState>(json.encodeToString(created.state)) shouldBe created.state
        }

        test("multiple attacking bands receive distinct reproducible handles shared by their members") {
            val game = scenario().withPlayers()
                .withCardOnBattlefield(1, "Banding Scout")
                .withCardOnBattlefield(1, "Banding Scout")
                .withCardOnBattlefield(1, "Centaur Courser")
                .withCardOnBattlefield(1, "Centaur Courser")
                .build()
            val scouts = game.findPermanents("Banding Scout")
            val coursers = game.findPermanents("Centaur Courser")
            val initial = game.state.copy(phase = Phase.COMBAT, step = Step.DECLARE_ATTACKERS)
            val action = DeclareAttackers(
                game.player1Id,
                (scouts + coursers).associateWith { game.player2Id },
                bands = listOf(setOf(scouts[0], coursers[0]), setOf(scouts[1], coursers[1]))
            )
            val declared = actionProcessor.process(initial, action).result
            val replay = actionProcessor.process(initial, action).result
            declared.error shouldBe null
            declared.state shouldBe replay.state
            declared.events shouldBe replay.events
            val bandIds = scouts.map { declared.state.getEntity(it)!!.get<AttackingComponent>()!!.bandId }
            bandIds.forEach { it.shouldNotBeNull() }
            bandIds[0] shouldNotBe bandIds[1]
            coursers.map { declared.state.getEntity(it)!!.get<AttackingComponent>()!!.bandId } shouldBe bandIds
            declared.state.nextRoutingId shouldBe initial.nextRoutingId + 2
            declared.state.rng shouldBe initial.rng
            declared.state.nextEntityId shouldBe initial.nextEntityId
        }
    }
}
