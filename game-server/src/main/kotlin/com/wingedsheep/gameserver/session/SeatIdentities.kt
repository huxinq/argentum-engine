package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The names one browser seat knows cards by.
 *
 * An engine id stays with its card all game, so a seat that once saw a card's id could follow it
 * wherever that id turns up again: a card revealed in hand and later cast face down, or one seen
 * before a shuffle that comes back as a manifest. So each time the seat loses track of a card, the
 * card gets a new name for that seat. It loses track when the card sits in a hidden zone the seat
 * can't see into, or leaves one (hand, library, sideboard) the seat couldn't see as a whole, without
 * the seat being able to identify it where it went. Everything sent to the seat uses its current
 * names, everything it sends back is read through them, and an old name is refused rather than
 * resolved, so it can't be used to probe which card is which.
 *
 * A card the seat never lost track of keeps its engine id: decks are minted in a shuffled order, so
 * the id says nothing the seat didn't see. The map therefore stays empty, and messages are passed
 * through untouched, until some card the seat saw goes out of its sight.
 */
internal class SeatIdentities {
    private val nameOf = HashMap<EntityId, EntityId>()
    private val engineIdOf = HashMap<EntityId, EntityId>()
    private val retired = HashSet<EntityId>()
    private val lastSeen = HashMap<EntityId, Sighting>()
    private var namesIssued = 0

    /** Where the seat last saw a card, and whether it could see that whole zone. */
    private data class Sighting(val zone: Zone, val zoneOpen: Boolean)

    /** Give a new name to every card the seat saw and can no longer follow in [state]. */
    fun forgetUntrackable(state: GameState, seat: EntityId, visibility: Visibility) {
        if (lastSeen.isEmpty()) return
        val keyOf = zoneKeys(state)
        val lost = lastSeen.entries.filter { (id, seen) ->
            val zone = zoneOf(state, keyOf, id) ?: return@filter false
            if (identified(state, keyOf, id, zone, seat, visibility)) return@filter false
            val inClosedHiddenZone = zone in HIDDEN_ZONES &&
                !visibility.isZoneVisibleTo(state, keyOf.getValue(id), seat)
            val leftClosedHiddenZone = seen.zone in HIDDEN_ZONES && !seen.zoneOpen && zone != seen.zone
            inClosedHiddenZone || leftClosedHiddenZone
        }.map { it.key }
        for (id in lost) {
            lastSeen.remove(id)
            nameOf.remove(id)?.let { engineIdOf.remove(it); retired += it } ?: run { retired += id }
            val name = EntityId("h${++namesIssued}")
            nameOf[id] = name
            engineIdOf[name] = id
        }
    }

    /** Record where the seat is seeing each card in [ids] (engine ids) as of [state]. */
    fun noteSeen(state: GameState, seat: EntityId, visibility: Visibility, ids: Collection<EntityId>) {
        val keyOf = zoneKeys(state)
        for (id in ids) {
            if (state.getEntity(id)?.get<CardComponent>() == null) continue
            val zone = zoneOf(state, keyOf, id) ?: continue
            val open = keyOf[id]?.let { visibility.isZoneVisibleTo(state, it, seat) } ?: true
            lastSeen[id] = Sighting(zone, open)
        }
    }

    /** Every string in [value] that could be an entity id; [noteSeen] keeps only the cards. */
    fun <T> idsIn(value: T, serializer: KSerializer<T>): Set<EntityId> {
        val ids = HashSet<EntityId>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, child) -> ids += EntityId(key); collect(child) }
                is JsonArray -> element.forEach(::collect)
                is JsonPrimitive -> if (element.isString) ids += EntityId(element.content)
            }
        }
        collect(json.encodeToJsonElement(serializer, value))
        return ids
    }

    /** Whether any of [ids] (engine ids) is a card the seat knows by another name. */
    fun renamesAny(ids: Collection<EntityId>): Boolean = ids.any { it in nameOf }

    /** [value] with every card id in the seat's names. */
    fun <T> toSeat(value: T, serializer: KSerializer<T>): T =
        if (nameOf.isEmpty()) value else rename(value, serializer) { nameOf[EntityId(it)]?.value }

    /**
     * [value], which the seat sent, with its names turned back into engine ids; null if it uses a
     * name the seat no longer has.
     */
    fun <T> fromSeat(value: T, serializer: KSerializer<T>): T? {
        if (nameOf.isEmpty()) return value
        var stale = false
        val renamed = rename(value, serializer) { name ->
            if (EntityId(name) in retired) stale = true
            engineIdOf[EntityId(name)]?.value
        }
        return if (stale) null else renamed
    }

    private fun zoneKeys(state: GameState): Map<EntityId, ZoneKey> {
        val keyOf = HashMap<EntityId, ZoneKey>()
        for ((key, ids) in state.zones) for (id in ids) keyOf[id] = key
        return keyOf
    }

    /** The zone a card is in; null once it has left the game. The stack is not in [GameState.zones]. */
    private fun zoneOf(state: GameState, keyOf: Map<EntityId, ZoneKey>, id: EntityId): Zone? =
        keyOf[id]?.zoneType ?: Zone.STACK.takeIf { id in state.stack }

    private fun identified(
        state: GameState,
        keyOf: Map<EntityId, ZoneKey>,
        id: EntityId,
        zone: Zone,
        seat: EntityId,
        visibility: Visibility,
    ): Boolean = keyOf[id]?.let { visibility.isCardIdentityVisibleTo(state, it, id, seat) }
        ?: visibility.isCardIdentityVisibleTo(state, zone, id, seat)

    private fun <T> rename(value: T, serializer: KSerializer<T>, name: (String) -> String?): T =
        json.decodeFromJsonElement(serializer, rename(json.encodeToJsonElement(serializer, value), name))

    private fun rename(element: JsonElement, name: (String) -> String?): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> (name(key) ?: key) to rename(value, name) })
        is JsonArray -> JsonArray(element.map { rename(it, name) })
        is JsonPrimitive -> if (element.isString) name(element.content)?.let(::JsonPrimitive) ?: element else element
    }

    private companion object {
        val HIDDEN_ZONES = setOf(Zone.LIBRARY, Zone.HAND, Zone.SIDEBOARD)

        val json = Json {
            encodeDefaults = true
            classDiscriminator = "type"
            serializersModule = engineSerializersModule
        }
    }
}
