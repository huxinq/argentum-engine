package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.ons.cards.FutureSight
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json

class TrainingObservationTest : FunSpec({

    fun createRegistry(): CardRegistry {
        val registry = CardRegistry()
        registry.register(PortalSet.cards)
        registry.register(PortalSet.basicLands)
        registry.register(FutureSight)
        return registry
    }

    fun simpleDeck() = Deck.of("Mountain" to 17, "Raging Goblin" to 3)

    fun newEnv(): GameEnvironment {
        val env = GameEnvironment.create(createRegistry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", simpleDeck()),
                    PlayerConfig("Bob", simpleDeck())
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun move(state: GameState, entityId: EntityId, destination: ZoneKey): GameState {
        val source = state.zones.entries.single { (_, ids) -> entityId in ids }.key
        return state.removeFromZone(source, entityId).addToZone(destination, entityId)
    }

    fun advanceToPrecombatMain(env: GameEnvironment) {
        repeat(32) {
            if (env.state.step == Step.PRECOMBAT_MAIN && env.state.priorityPlayerId == env.playerIds[0]) {
                return
            }
            val pass = env.legalActions().first { it.action is PassPriority }
            env.stepRaw(pass.action)
        }
        error("Did not reach the starting player's precombat main phase")
    }

    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    test("observation includes all basic state fields and round-trips through JSON") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder().build(env.state, perspective, env.legalActions())

        val obs = result.observation as TrainingObservation
        obs.perspectivePlayerId shouldBe perspective
        obs.players.size shouldBe 2
        obs.agentToAct.shouldNotBeNull()
        obs.terminated.shouldBeFalse()
        obs.schemaHash shouldBe SchemaHash.CURRENT
        obs.stateDigest shouldMatch Regex("[0-9a-f]{64}")
        obs.legalActions.shouldNotBeEmpty()

        // Hand + library + graveyard + exile + battlefield + command for each player.
        obs.zones.size shouldBe 2 * 6

        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded shouldBe obs
    }

    test("an actionId from the observation resolves to a steppable action") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder().build(env.state, perspective, env.legalActions())

        val passView = result.observation.legalActions.first { it.description.contains("Pass", ignoreCase = true) || it.kind.contains("Pass", ignoreCase = true) }
        val resolved = result.registry.resolve(passView.actionId)
        resolved.shouldNotBe(ResolvedAction.Unknown)

        val legal = resolved as ResolvedAction.Legal
        (legal.action is PassPriority).shouldBeTrue()

        val stepResult = env.step(legal.action)
        stepResult.state.shouldNotBeNull()
        env.stepCount shouldBeGreaterThan 0
    }

    test("opponent hand is hidden by default, visible when revealAll=true") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]

        val masked = ObservationBuilder().build(env.state, me, env.legalActions())
            .observation as TrainingObservation
        val myHand = masked.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }
        val theirHand = masked.zones.single { it.ownerId == opponent && it.zoneType == Zone.HAND }
        myHand.hidden.shouldBeFalse()
        myHand.cards.size shouldBe myHand.size
        theirHand.hidden.shouldBeTrue()
        theirHand.cards.size shouldBe 0
        theirHand.size shouldBeGreaterThan 0

        // Every library is hidden regardless of perspective.
        masked.zones.filter { it.zoneType == Zone.LIBRARY }.forEach { it.hidden.shouldBeTrue() }

        val revealed = ObservationBuilder().build(env.state, me, env.legalActions(), revealAll = true)
            .observation as TrainingObservation
        val theirHandRevealed = revealed.zones.single { it.ownerId == opponent && it.zoneType == Zone.HAND }
        theirHandRevealed.hidden.shouldBeFalse()
        theirHandRevealed.cards.size shouldBe theirHandRevealed.size
    }

    test("one typed visibility decision admits a playable top-library source and keeps its zone hidden") {
        val registry = createRegistry()
        val env = GameEnvironment.create(registry)
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Future Sight" to 1, "Mountain" to 19)),
                    PlayerConfig("Bob", simpleDeck()),
                ),
                seed = 902L,
                skipMulligans = true,
                startingPlayerIndex = 0,
            )
        )
        advanceToPrecombatMain(env)
        val viewer = env.playerIds[0]
        val futureSight = env.state.entities.entries.single { (_, container) ->
            container.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name ==
                "Future Sight"
        }.key
        var state = move(env.state, futureSight, ZoneKey(viewer, Zone.BATTLEFIELD))
        val top = state.getLibrary(viewer).first()
        env.restore(state, env.playerIds)
        val legalActions = env.legalActions()

        val observation = ObservationBuilder(registry).build(state, viewer, legalActions).observation
            as TrainingObservation
        val library = observation.zones.single { it.ownerId == viewer && it.zoneType == Zone.LIBRARY }

        library.hidden.shouldBeTrue()
        library.cards.map { it.entityId } shouldBe listOf(top)
        legalActions.any { it.action.sourceEntityIdOrNull() == top }.shouldBeTrue()
        observation.legalActions.any { it.sourceEntityId == top }.shouldBeTrue()
    }

    test("command-zone cards and their real engine action sources share visibility admission") {
        val registry = createRegistry()
        val env = newEnv()
        advanceToPrecombatMain(env)
        val viewer = env.playerIds[0]
        val commander = (env.state.getHand(viewer) + env.state.getLibrary(viewer)).first { id ->
            env.state.getEntity(id)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name ==
                "Raging Goblin"
        }
        val mountain = (env.state.getHand(viewer) + env.state.getLibrary(viewer)).first { id ->
            env.state.getEntity(id)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name ==
                "Mountain"
        }
        var state = move(env.state, commander, ZoneKey(viewer, Zone.COMMAND))
            .updateEntity(commander) { it.with(CommanderComponent(viewer)) }
        state = move(state, mountain, ZoneKey(viewer, Zone.BATTLEFIELD))
            .copy(format = Format.Commander())
        env.restore(state, env.playerIds)
        val legalActions = env.legalActions()
        val observation = ObservationBuilder(registry).build(state, viewer, legalActions).observation
            as TrainingObservation
        val command = observation.zones.single { it.ownerId == viewer && it.zoneType == Zone.COMMAND }

        command.hidden.shouldBeFalse()
        command.cards.map { it.entityId } shouldContain commander
        legalActions.any { it.action.sourceEntityIdOrNull() == commander }.shouldBeTrue()
        observation.legalActions.any { it.sourceEntityId == commander }.shouldBeTrue()
    }

    test("face-down and selectively revealed objects are admitted without leaking other hidden cards") {
        val registry = createRegistry()
        val env = newEnv()
        val viewer = env.playerIds[0]
        val opponent = env.playerIds[1]
        val faceDown = env.state.getHand(opponent).first()
        val selectivelyRevealed = env.state.getHand(opponent)[1]
        val underlyingName = env.state.getEntity(faceDown)!!
            .get<com.wingedsheep.engine.state.components.identity.CardComponent>()!!.name
        var state = move(env.state, faceDown, ZoneKey(opponent, Zone.BATTLEFIELD))
            .updateEntity(faceDown) { it.with(FaceDownComponent) }
            .updateEntity(selectivelyRevealed) { it.with(RevealedToComponent.to(viewer)) }

        val observation = ObservationBuilder(registry).build(state, viewer, emptyList()).observation
            as TrainingObservation
        val battlefieldCard = observation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == faceDown }
        val opponentHand = observation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.HAND
        }

        battlefieldCard.faceDown.shouldBeTrue()
        battlefieldCard.name shouldBe "Face-down creature"
        battlefieldCard.cardDefinitionId shouldBe null
        battlefieldCard.name shouldNotBe underlyingName
        opponentHand.hidden.shouldBeTrue()
        opponentHand.cards.map { it.entityId } shouldBe listOf(selectivelyRevealed)
    }

    test("legal action with an unadmitted source is filtered before registry and observation") {
        val env = newEnv()
        val viewer = env.playerIds[0]
        val hiddenSource = env.state.getHand(env.playerIds[1]).first()
        val malicious = LegalAction(
            action = PlayLand(viewer, hiddenSource),
            actionType = "PlayLand",
            description = "Play hidden source",
        )

        val result = ObservationBuilder(createRegistry()).build(env.state, viewer, listOf(malicious))

        result.observation.legalActions shouldBe emptyList()
        result.registry.resolve(0) shouldBe ResolvedAction.Unknown
    }

    test("oracle text is serialized for cards in visible zones") {
        // Raging Goblin's printed text is "Haste" — that's what the agent
        // needs to see to know the creature can attack the turn it enters.
        val env = newEnv()
        val me = env.playerIds[0]

        val obs = ObservationBuilder().build(env.state, me, env.legalActions()).observation as TrainingObservation
        val myHand = obs.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }

        // At least one card should be in the opening hand; every card there
        // is fully visible to the perspective player including its oracle text.
        myHand.cards.shouldNotBeEmpty()
        val rages = myHand.cards.filter { it.name == "Raging Goblin" }
        if (rages.isNotEmpty()) {
            rages.first().oracleText shouldContain "Haste"
        }
        // Round-trip through JSON — oracle text must survive.
        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded.zones.flatMap { it.cards }.forEach { c ->
            // Every serialized card either has empty oracle text or preserves it.
            c.oracleText shouldBe (obs.zones.flatMap { it.cards }.first { it.entityId == c.entityId }.oracleText)
        }
    }

    test("stateDigest is stable for equivalent observations and changes when state changes") {
        val env = newEnv()
        val me = env.playerIds[0]

        val a = ObservationBuilder().build(env.state, me, env.legalActions()).observation
        val b = ObservationBuilder().build(env.state, me, env.legalActions()).observation
        a.stateDigest shouldBe b.stateDigest

        // Advance a step and verify the digest changes.
        val pass = env.legalActions().first { it.action is PassPriority }
        env.step(pass.action)
        val c = ObservationBuilder().build(env.state, me, env.legalActions()).observation
        c.stateDigest shouldNotBe a.stateDigest
    }

    test("permuting hidden opponent cards leaves the unauthorized observation byte-identical") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val handKey = ZoneKey(opponent, Zone.HAND)
        val libraryKey = ZoneKey(opponent, Zone.LIBRARY)
        val permuted = env.state.copy(
            zones = env.state.zones + mapOf(
                handKey to env.state.getZone(handKey).reversed(),
                libraryKey to env.state.getZone(libraryKey).reversed(),
            )
        )

        val originalView = ObservationBuilder().build(env.state, me, env.legalActions()).observation as TrainingObservation
        val permutedView = ObservationBuilder().build(permuted, me, env.legalActions()).observation as TrainingObservation
        json.encodeToString(TrainingObservation.serializer(), originalView) shouldBe
            json.encodeToString(TrainingObservation.serializer(), permutedView)
    }

    test("private search and reorder candidates are visible only to their chooser") {
        val env = newEnv()
        val observer = env.playerIds[0]
        val chooser = env.playerIds[1]
        val candidates = env.state.getLibrary(chooser).take(2)
        val info = candidates.associateWith { id ->
            val card = env.state.getEntity(id)!!
                .get<com.wingedsheep.engine.state.components.identity.CardComponent>()!!
            SearchCardInfo(card.name, card.manaCost.toString(), card.typeLine.toString())
        }
        val context = DecisionContext(sourceId = EntityId("public-source"), sourceName = "Search")
        val decisions = listOf(
            SearchLibraryDecision(
                "search-id", chooser, "Search", context, candidates, 0, 1, info, "a card"
            ),
            ReorderLibraryDecision(
                "reorder-id", chooser, "Reorder", context, candidates, info
            ),
        )

        for (decision in decisions) {
            val paused = env.state.copy(pendingDecision = decision)
            val unauthorized = ObservationBuilder().build(paused, observer, emptyList()).observation
            val unauthorizedPending = unauthorized.pendingDecision.shouldNotBeNull()
            unauthorizedPending.canRespond.shouldBeFalse()
            unauthorizedPending.choiceSpec shouldBe null
            unauthorized.legalActions shouldBe emptyList()

            val authorized = ObservationBuilder().build(paused, chooser, emptyList()).observation
            val authorizedPending = authorized.pendingDecision.shouldNotBeNull()
            authorizedPending.canRespond.shouldBeTrue()
            authorizedPending.choiceSpec.shouldNotBeNull()
        }
    }

    test("semantic digest ignores routing nonces but includes decision constraints") {
        val env = newEnv()
        val me = env.playerIds[0]
        val context = DecisionContext(sourceId = EntityId("source"), sourceName = "Prompt")
        val yesA = env.state.copy(pendingDecision = YesNoDecision("nonce-a", me, "Choose", context))
        val yesB = env.state.copy(pendingDecision = YesNoDecision("nonce-b", me, "Choose", context))
        val numberA = env.state.copy(pendingDecision = ChooseNumberDecision("n-a", me, "Choose", context, 0, 2))
        val numberB = env.state.copy(pendingDecision = ChooseNumberDecision("n-b", me, "Choose", context, 0, 3))

        ObservationBuilder().build(yesA, me, emptyList()).observation.stateDigest shouldBe
            ObservationBuilder().build(yesB, me, emptyList()).observation.stateDigest
        ObservationBuilder().build(numberA, me, emptyList()).observation.stateDigest shouldNotBe
            ObservationBuilder().build(numberB, me, emptyList()).observation.stateDigest
    }

    test("semantic digest includes every visible stack field and canonicalizes map keys") {
        val env = newEnv()
        val me = env.playerIds[0]
        val base = ObservationBuilder().build(env.state, me, env.legalActions()).observation as TrainingObservation
        val stackA = StackItemView(
            EntityId("stack"), me, "Spell", StackItemKind.SPELL, "Deal damage", listOf(EntityId("a"))
        )
        val stackB = stackA.copy(targets = listOf(EntityId("b")))
        StateDigest.compute(base.copy(stack = listOf(stackA))) shouldNotBe
            StateDigest.compute(base.copy(stack = listOf(stackB)))

        val a = EntityId("a")
        val b = EntityId("b")
        val pendingA = PendingDecisionView(
            "routing-a",
            PendingDecisionKind.DISTRIBUTE,
            me,
            "Divide",
            choiceSpec = DistributionChoiceSpec(2, listOf(a, b), 0, linkedMapOf(a to 1, b to 2), false),
        )
        val pendingB = pendingA.copy(
            decisionId = "routing-b",
            choiceSpec = DistributionChoiceSpec(2, listOf(a, b), 0, linkedMapOf(b to 2, a to 1), false),
        )
        StateDigest.compute(base.copy(pendingDecision = pendingA)) shouldBe
            StateDigest.compute(base.copy(pendingDecision = pendingB))
    }

    test("semantic digest includes public speed and combat relationships") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val base = ObservationBuilder().build(env.state, me, env.legalActions()).observation as TrainingObservation
        val faster = base.copy(players = base.players.map { player ->
            if (player.id == me) player.copy(speed = 2) else player
        })
        StateDigest.compute(base) shouldNotBe StateDigest.compute(faster)

        val attacker = EntityId("visible-attacker")
        val blocker = EntityId("visible-blocker")
        val unblocked = base.copy(combat = CombatView(
            attackingPlayerId = me,
            attackers = listOf(AttackerView(attacker, opponent)),
            blockers = emptyList(),
        ))
        val blocked = unblocked.copy(combat = CombatView(
            attackingPlayerId = me,
            attackers = listOf(AttackerView(attacker, opponent, listOf(blocker))),
            blockers = listOf(BlockerView(blocker, listOf(attacker))),
        ))
        StateDigest.compute(base) shouldNotBe StateDigest.compute(unblocked)
        StateDigest.compute(unblocked) shouldNotBe StateDigest.compute(blocked)
    }
})
