package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.*
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.view.*
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.springframework.web.socket.WebSocketSession

class PublicRuleObservationTest : FunSpec({
    val json = Json { encodeDefaults = true; classDiscriminator = "type"; serializersModule = engineSerializersModule }
    fun players(d: GameTestDriver) = listOf(d.player1, d.player2).associateWith { player ->
        val socket = mockk<WebSocketSession>(relaxed = true) { every { id } returns player.value }
        PlayerSession(socket, player, player.value)
    }
    fun observe(session: GameSession, d: GameTestDriver): SeatObservation {
        session.injectStateForTesting(d.state, players(d))
        session.clearLastSentState(d.player1)
        return session.createSeatObservation(d.player1, emptyList())!!
    }
    fun legacy(element: JsonElement, names: Map<String, String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> (names[key] ?: key) to legacy(value, names) })
        is JsonArray -> JsonArray(element.map { legacy(it, names) })
        is JsonPrimitive -> if (element.isString) names[element.content]?.let(::JsonPrimitive) ?: element else element
    }
    fun <T> expectLegacy(session: GameSession, d: GameTestDriver, serializer: KSerializer<T>, raw: T, observed: T) {
        val names = session.getSeatNamesForPersistence().getValue(d.player1).names
        observed shouldBe json.decodeFromJsonElement(serializer, legacy(json.encodeToJsonElement(serializer, raw), names))
    }
    fun expectBrowser(session: GameSession, observation: SeatObservation) {
        val browser = session.presentSeatObservation(observation) as ServerMessage.StateUpdate
        browser.state shouldBe observation.state
        browser.events shouldBe observation.events
        browser.legalActions shouldBe observation.legalActions
        browser.pendingDecision shouldBe observation.pendingDecision
    }

    test("public SDK rules and a generated token follow seat identities through hiding and restoration") {
        val d = GameTestDriver()
        d.registerCards(TestCards.all)
        d.initMirrorMatch(Deck.of("Forest" to 40))
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val session = GameSession(cardRegistry = d.cardRegistry)
        val tracked = d.putCreatureOnBattlefield(d.player2, "Grizzly Bears")
        val secret = d.putCardInHand(d.player2, "Hill Giant")
        observe(session, d).state.cards.containsKey(secret) shouldBe false
        d.replaceState(d.state.moveToZone(tracked, ZoneKey(d.player2, Zone.BATTLEFIELD), ZoneKey(d.player2, Zone.LIBRARY)))
        observe(session, d)
        d.replaceState(d.state.moveToZone(tracked, ZoneKey(d.player2, Zone.LIBRARY), ZoneKey(d.player2, Zone.BATTLEFIELD))
            .updateEntity(tracked) { it.with(FaceDownComponent) })
        val hidden = observe(session, d)
        val alias = hidden.state.zones.single { it.zoneId == ZoneKey(d.player2, Zone.BATTLEFIELD) }.cardIds.single()
        alias shouldNotBe tracked
        hidden.state.cards[alias]!!.name shouldNotBe "Grizzly Bears"
        d.replaceState(d.state.updateEntity(tracked) { it.without<FaceDownComponent>() })
        observe(session, d).state.cards[alias]!!.name shouldBe "Grizzly Bears"

        val granted = ActivatedAbility(id = AbilityId("token-reference"), cost = AbilityCost.Free,
            effect = Effects.DealDamage(1, EffectTarget.SpecificEntity(tracked)))
        val maker = CardDefinition("Public Rule Token Maker", ManaCost.parse("{0}"), TypeLine.parse("Sorcery"),
            script = CardScript(spellEffect = Effects.CreateToken(1, 2, creatureTypes = setOf("Spirit"),
                activatedAbilities = listOf(granted))))
        d.registerCard(maker)
        d.submitSuccess(CastSpell(d.player1, d.putCardInHand(d.player1, maker.name)))
        d.bothPass().error.shouldBeNull()
        val token = d.state.getBattlefield().single { d.state.getEntity(it)?.has<TokenBlueprintComponent>() == true }
        val actionView = observe(session, d)
        val offer = actionView.legalActions.single { (it.action as? ActivateAbility)?.sourceId == token }
        offer.rule!!.origin shouldBe AbilityOrigin.RUNTIME_GRANTED
        (offer.rule!!.effectiveAbility!!.effect as DealDamageEffect).target shouldBe EffectTarget.SpecificEntity(alias)
        val intrinsic = actionView.state.cards[token]!!.tokenBlueprint!!
        intrinsic.power shouldBe 1
        intrinsic.toughness shouldBe 2
        (intrinsic.activatedAbilities.single().effect as DealDamageEffect).target shouldBe EffectTarget.SpecificEntity(alias)
        expectLegacy(session, d, ClientGameState.serializer(), session.getClientState(d.player1)!!, actionView.state)
        expectLegacy(session, d, ListSerializer(LegalActionInfo.serializer()), session.getLegalActions(d.player1), actionView.legalActions)
        expectBrowser(session, actionView)

        d.submitSuccess(session.fromSeat(d.player1, offer.action, GameAction.serializer())!!)
        val stackId = d.getTopOfStack()!!
        val stackView = observe(session, d)
        val stack = stackView.state.cards[stackId]!!
        stack.abilitySourceId shouldBe token
        stack.abilityDefinitionIsExact shouldBe false
        (stack.semanticRule!!.effect as DealDamageEffect).target shouldBe EffectTarget.SpecificEntity(alias)
        expectLegacy(session, d, ClientGameState.serializer(), session.getClientState(d.player1)!!, stackView.state)
        expectBrowser(session, stackView)

        val hand = d.putCardInHand(d.player1, "Forest")
        val rule = SemanticRule(effect = Effects.DealDamage(1, EffectTarget.SpecificEntity(tracked)))
        val question = SelectCardsDecision("public-choice", d.player1, "Choose a card", DecisionContext(
            sourceId = token, semanticRule = rule,
            ruleFacts = RuleFacts(targetGroups = listOf(listOf(tracked, secret, null))),
            optionRules = listOf(rule, rule.copy(effect = Effects.DealDamage(1, EffectTarget.SpecificEntity(secret))))),
            listOf(hand), 1, 1)
        d.replaceState(d.state.suspendForDecision({ question }, HandSizeDiscardContinuation(d.player1), emptyList()).state)
        val pendingView = observe(session, d)
        val pending = pendingView.pendingDecision as SelectCardsDecision
        (pending.context.semanticRule!!.effect as DealDamageEffect).target shouldBe EffectTarget.SpecificEntity(alias)
        pending.context.ruleFacts!!.targetGroups shouldBe listOf(listOf(alias, null, null))
        pending.context.optionRules shouldBe emptyList()
        session.createSeatObservation(d.player2, emptyList())!!.pendingDecision.shouldBeNull()
        val raw = session.createStateUpdate(d.player1, emptyList(), useEngineDecisionIds = true) as ServerMessage.StateUpdate
        expectLegacy(session, d, PendingDecision.serializer(), (raw.pendingDecision as SelectCardsDecision).copy(id = pending.id), pending)
        expectBrowser(session, pendingView)
        json.encodeToString(ClientGameState.serializer(), pendingView.state) shouldNotContain "\"${secret.value}\""
        json.encodeToString(PendingDecision.serializer(), pending) shouldNotContain "\"${secret.value}\""

        val snapshot = json.decodeFromString(GameState.serializer(), json.encodeToString(GameState.serializer(), d.state))
        val restored = GameSession(cardRegistry = d.cardRegistry)
        restored.restoreFromPersistence(snapshot, emptyMap(), mutableMapOf(), emptyMap(), seatNames = session.getSeatNamesForPersistence())
        d.replaceState(snapshot)
        val restoredView = observe(restored, d)
        restoredView.state shouldBe pendingView.state
        restoredView.pendingDecision!!.context shouldBe pending.context
        restored.fromSeat(d.player1, listOf(alias), ListSerializer(EntityId.serializer())) shouldBe listOf(tracked)
        restored.fromSeat(d.player1, listOf(tracked), ListSerializer(EntityId.serializer())).shouldBeNull()

        // The observer loses the card again. Public programs with that private identity are suppressed.
        d.replaceState(snapshot.moveToZone(tracked, ZoneKey(d.player2, Zone.BATTLEFIELD), ZoneKey(d.player2, Zone.LIBRARY)))
        val retired = observe(restored, d)
        restored.fromSeat(d.player1, listOf(alias), ListSerializer(EntityId.serializer())).shouldBeNull()
        retired.state.cards[token]!!.tokenBlueprint.shouldBeNull()
        retired.state.cards[stackId]!!.semanticRule.shouldBeNull()
        retired.pendingDecision!!.context.semanticRule.shouldBeNull()
        retired.pendingDecision!!.context.ruleFacts.shouldBeNull()
        expectBrowser(restored, retired)

        // With priority restored, the same public-grant annotation must also omit the private reference.
        d.replaceState(d.state.copy(continuationStack = emptyList(), stack = emptyList()).withPriority(d.player1))
        val retiredActions = observe(restored, d)
        retiredActions.legalActions.single { (it.action as? ActivateAbility)?.sourceId == token }.rule.shouldBeNull()
        json.encodeToString(ListSerializer(LegalActionInfo.serializer()), retiredActions.legalActions) shouldNotContain "\"${tracked.value}\""
    }
})
