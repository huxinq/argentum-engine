package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.springframework.web.socket.WebSocketSession

/**
 * A browser seat can't follow a card by its id once it has lost track of the card.
 *
 * An engine id stays with its card all game. Without per-seat names, an opponent who saw two cards
 * revealed in a hand could tell which one was later cast face down, and a card seen before a
 * shuffle could be recognised when it came back as a manifest.
 */
class SeatIdentityTest : ScenarioTestBase() {

    private val json = Json { encodeDefaults = true; classDiscriminator = "type"; serializersModule = engineSerializersModule }

    private fun players(game: TestGame) = listOf(game.player1Id, game.player2Id).associateWith { player ->
        val socket = mockk<WebSocketSession>(relaxed = true) { every { id } returns player.value }
        PlayerSession(socket, player, player.value)
    }

    /** A full update for [seat], as a browser gets it on (re)connecting. */
    private fun GameSession.fullUpdate(seat: EntityId, state: GameState, game: TestGame): ServerMessage.StateUpdate {
        injectStateForTesting(state, players(game))
        clearLastSentState(seat)
        val observation = requireNotNull(createSeatObservation(seat, emptyList()))
        val browser = presentSeatObservation(observation) as ServerMessage.StateUpdate
        browser.state shouldBe observation.state
        browser.events shouldBe observation.events
        browser.legalActions shouldBe observation.legalActions
        browser.pendingDecision shouldBe observation.pendingDecision
        return browser
    }

    private fun ServerMessage.StateUpdate.battlefieldOf(player: EntityId): List<EntityId> =
        state.zones.single { it.zoneId == ZoneKey(player, Zone.BATTLEFIELD) }.cardIds

    init {
        test("an opponent shown two cards can't tell which one was cast face down") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(2, "Grizzly Bears")
                .withCardInHand(2, "Hill Giant")
                .build()
            val viewer = game.player1Id
            val owner = game.player2Id
            val (bears, giant) = game.state.getHand(owner)
            val revealed = listOf(bears, giant).fold(game.state) { state, card ->
                state.updateEntity(card) { it.with(RevealedToComponent.to(viewer)) }
            }
            val session = GameSession(cardRegistry = cardRegistry)
            session.fullUpdate(viewer, revealed, game).state.cards.keys shouldContainAll listOf(bears, giant)

            // One of them is cast face down, and the reveal of the other lapses.
            val faceDown = revealed
                .moveToZone(bears, ZoneKey(owner, Zone.HAND), ZoneKey(owner, Zone.BATTLEFIELD))
                .updateEntity(bears) { it.without<RevealedToComponent>().with(FaceDownComponent) }
                .updateEntity(giant) { it.without<RevealedToComponent>() }
            val update = session.fullUpdate(viewer, faceDown, game)
            val name = update.battlefieldOf(owner).single()

            name shouldNotBe bears
            name shouldNotBe giant
            json.encodeToString(ServerMessage.serializer(), update) shouldNotContain "\"${bears.value}\""
            // Watching the face-down permanent, the seat keeps its name.
            session.fullUpdate(viewer, faceDown, game).battlefieldOf(owner).single() shouldBe name
            // The owner saw its own card leave its hand, so it keeps following it.
            session.fullUpdate(owner, faceDown, game).battlefieldOf(owner).single() shouldBe bears
            // The seat's name reaches the card; the old one can't be used to probe which card it is.
            session.fromSeat(viewer, listOf(name), ListSerializer(EntityId.serializer())) shouldBe listOf(bears)
            session.fromSeat(viewer, listOf(bears), ListSerializer(EntityId.serializer())) shouldBe null
        }

        test("a card seen before it was shuffled away comes back under a new name") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(2, "Grizzly Bears")
                .build()
            val viewer = game.player1Id
            val owner = game.player2Id
            val bears = game.findPermanent("Grizzly Bears")!!
            val session = GameSession(cardRegistry = cardRegistry)
            session.fullUpdate(viewer, game.state, game).battlefieldOf(owner) shouldBe listOf(bears)

            val tucked = game.state.moveToZone(bears, ZoneKey(owner, Zone.BATTLEFIELD), ZoneKey(owner, Zone.LIBRARY))
            val whileHidden = session.fullUpdate(viewer, tucked, game)
            // While the renamed card is out of sight, nothing in the message needs renaming.
            session.clearLastSentState(viewer)
            (session.createStateUpdate(viewer, emptyList(), useEngineDecisionIds = true) as ServerMessage.StateUpdate)
                .state shouldBe whileHidden.state
            val manifested = tucked
                .moveToZone(bears, ZoneKey(owner, Zone.LIBRARY), ZoneKey(owner, Zone.BATTLEFIELD))
                .updateEntity(bears) { it.with(FaceDownComponent) }

            val renamed = session.fullUpdate(viewer, manifested, game).battlefieldOf(owner).single()
            renamed shouldNotBe bears
            // A restart keeps the seat's names, so the card doesn't come back under its engine id.
            val restored = GameSession(cardRegistry = cardRegistry)
            restored.restoreFromPersistence(
                manifested, emptyMap(), mutableMapOf(), emptyMap(),
                seatNames = session.getSeatNamesForPersistence(),
            )
            restored.fullUpdate(viewer, manifested, game).battlefieldOf(owner).single() shouldBe renamed
            // In-process AI seats read raw engine state and keep engine ids.
            session.injectStateForTesting(manifested, players(game))
            session.clearLastSentState(viewer)
            (session.createStateUpdate(viewer, emptyList(), useEngineDecisionIds = true) as ServerMessage.StateUpdate)
                .battlefieldOf(owner).single() shouldBe bears
        }
    }
}
