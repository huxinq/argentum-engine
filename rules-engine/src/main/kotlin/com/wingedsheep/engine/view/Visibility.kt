package com.wingedsheep.engine.view

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.faceDownDisplayName
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.playerWhoMayLookAtFaceDown
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.ForetoldComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.MayLookAtInExileComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.permissions.hasMayPlayFor
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.LookAtFaceDownCreatures
import com.wingedsheep.sdk.scripting.LookAtTopOfLibrary
import com.wingedsheep.sdk.scripting.OpponentsPlayWithHandsRevealed
import com.wingedsheep.sdk.scripting.PlayFromTopOfLibrary
import com.wingedsheep.sdk.scripting.PlayersRevealTopOfLibrary
import com.wingedsheep.sdk.scripting.RevealTopOfLibrary
import com.wingedsheep.sdk.scripting.StaticAbility

/**
 * The engine's single source of truth for which card identities a player may see.
 *
 * This covers both identities in hidden zones and face-down objects in otherwise public zones.
 * Client state and decision masking, AI determinization, and Gym observations deliberately share
 * this service. Adding a reveal rule to one without the others would either leak information or
 * hide information a perspective is legally entitled to use. Consumers still own their
 * representations: knowing an identity does not prescribe a client card DTO, a Gym feature vector,
 * or opaque zone slots.
 */
class Visibility(
    private val cardRegistry: CardRegistry,
    private val debugMode: Boolean = false,
) {
    private val conditionEvaluator = ConditionEvaluator()

    /**
     * Whether [viewingPlayerId] may know the identity of [entityId] where it currently is.
     *
     * This is the source-oriented form of [isCardIdentityVisibleTo]. Stack abilities carry a
     * source entity rather than a card of their own, and their presentation must not reconstruct
     * a zone from controller, owner, or stack metadata. Keeping that lookup here makes the same
     * identity authority answer for card views, ability views, Gym observations, and any later
     * presentation consumer.
     *
     * An entity no longer in a visible game zone has already left the face-down zone that hid it;
     * [faceDownDisplayName] consequently has no mask for it, so its identity is public here too.
     */
    fun isCardIdentityVisibleTo(
        state: GameState,
        entityId: EntityId,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean {
        val zoneKey = currentZoneKeyOf(state, entityId) ?: return true
        return isCardIdentityVisibleTo(state, zoneKey, entityId, viewingPlayerId, isSpectator)
    }

    fun isZoneVisibleTo(
        state: GameState,
        zoneKey: ZoneKey,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean = when (zoneKey.zoneType) {
        Zone.LIBRARY -> false
        Zone.HAND -> debugMode || (!isSpectator && (
            zoneKey.ownerId == viewingPlayerId ||
                state.actorFor(zoneKey.ownerId) == viewingPlayerId ||
                zoneKey.ownerId in state.teammatesOf(viewingPlayerId) ||
                (zoneKey.ownerId != viewingPlayerId && revealsOpponentHandsTo(state, viewingPlayerId))
            ))
        Zone.SIDEBOARD -> debugMode || (!isSpectator && (
            zoneKey.ownerId == viewingPlayerId ||
                state.actorFor(zoneKey.ownerId) == viewingPlayerId
            ))
        Zone.BATTLEFIELD,
        Zone.GRAVEYARD,
        Zone.STACK,
        Zone.EXILE,
        Zone.COMMAND -> true
    }

    /**
     * Whether [viewingPlayerId] may know the identity of [entityId], which sits in [zoneType].
     *
     * Use this where the caller has an entity but no honest [ZoneKey] to pass: the stack is not
     * part of [GameState.zones] at all, and a battlefield key names the *controller*, not the
     * owner. Deriving the key here keeps those callers from inventing one — defaulting the owner
     * to the viewer reads as "my own zone", and is only ever harmless because the public zones
     * where it happens ignore the owner entirely.
     */
    fun isCardIdentityVisibleTo(
        state: GameState,
        zoneType: Zone,
        entityId: EntityId,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean = isCardIdentityVisibleTo(
        state,
        // A dangling reference — a target whose object has already left — has no zone to key on.
        // The viewer's own key is the harmless choice: it can only make a public zone public.
        ZoneKey(zoneOwnerOf(state, entityId, zoneType) ?: viewingPlayerId, zoneType),
        entityId,
        viewingPlayerId,
        isSpectator,
    )

    /**
     * Whether [viewingPlayerId] may know the identity of [entityId] in [zoneKey].
     *
     * A hidden zone is not all-or-nothing: [RevealedToComponent] and top-of-library effects can
     * expose one card while the rest stays hidden. Conversely, a public zone does not make the
     * identity under a face-down object public. This query combines those identity facts while
     * leaving ordered hidden-zone structure and consumer-specific presentation to the caller.
     */
    fun isCardIdentityVisibleTo(
        state: GameState,
        zoneKey: ZoneKey,
        entityId: EntityId,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean {
        // Public-zone visibility does not reveal what is underneath a face-down object.
        if (faceDownDisplayName(state, entityId) != null) {
            if (debugMode) return true
            if (isSpectator) return false
            // CR 708.5: "At any time, you may look at a face-down spell you control on the stack or
            // a face-down permanent you control … You can't look at face-down cards in any other
            // zone." Exile therefore gets no controller baseline: a card put there by a plain
            // "exile face down" rider is hidden from its owner too. Only an effect that grants
            // access opens one, and every such effect names its grantee — see below.
            if (zoneKey.zoneType != Zone.EXILE &&
                playerWhoMayLookAtFaceDown(state, entityId) == viewingPlayerId
            ) return true
            if (isCardRevealedTo(state, entityId, viewingPlayerId)) return true
            if (zoneKey.zoneType == Zone.BATTLEFIELD &&
                hasLookAtFaceDownCreatures(state, viewingPlayerId)
            ) return true
            if (zoneKey.zoneType == Zone.EXILE &&
                grantsFaceDownExileAccessTo(state, entityId, viewingPlayerId)
            ) return true
            return false
        }

        if (isZoneVisibleTo(state, zoneKey, viewingPlayerId, isSpectator)) return true

        // A spectator receives public reveals but never a player's private reveal memory.
        val isTopCard = zoneKey.zoneType == Zone.LIBRARY &&
            state.getLibrary(zoneKey.ownerId).firstOrNull() == entityId
        if (isTopCard && revealsTopOfLibraryPublicly(state, zoneKey.ownerId)) return true
        if (isSpectator) return false

        if (isCardRevealedTo(state, entityId, viewingPlayerId)) return true
        return isTopCard && zoneKey.ownerId == viewingPlayerId &&
            hasLookAtTopOfLibrary(state, viewingPlayerId)
    }

    /**
     * Whether an effect entitles [viewingPlayerId] to look under face-down [entityId] in exile.
     *
     * CR 708.5 grants nothing here, so this is the whole permission, and each granting effect
     * names the player it grants to:
     *
     * - **Foretell** (CR 702.143a) — "That player may look at that card as long as it remains in
     *   exile." The engine stamps [FaceDownComponent] on a foretold card purely to mask it from
     *   *opponents*, so reading [ForetoldComponent] is what keeps the foreteller's own view.
     * - **May-play grants** — Gonti's "you may look at that card for as long as it remains
     *   exiled", and the filter-defined grants [hasMayPlayFor] derives. Keyed on the same check
     *   that drives castability, so a card the viewer may cast is never one they cannot see.
     * - **A bare look grant** — [MayLookAtInExileComponent], stamped by an effect whose text
     *   grants the look and nothing else (Jacob Hauken, Inspector: "exile a card from your hand
     *   face down. You may look at that card for as long as it remains exiled", with no permission
     *   to play it until the card transforms). Kept separate from the may-play check above
     *   precisely because seeing and playing are different grants.
     *
     * A card exiled face down by a plain rider grants none of the three, and stays hidden from
     * everyone.
     */
    private fun grantsFaceDownExileAccessTo(
        state: GameState,
        entityId: EntityId,
        viewingPlayerId: EntityId,
    ): Boolean {
        val container = state.getEntity(entityId)
        if (container?.get<ForetoldComponent>()?.controllerId == viewingPlayerId) return true
        if (container?.get<MayLookAtInExileComponent>()?.mayLook(viewingPlayerId) == true) return true
        return state.hasMayPlayFor(entityId, viewingPlayerId, conditionEvaluator, cardRegistry)
    }

    /**
     * Which player's [ZoneKey] holds [entityId] while it is in [zoneType].
     *
     * The battlefield is keyed by controller and the stack — which lives outside
     * [GameState.zones] — by caster. Every other zone is keyed by owner: CR 401.1 and 402.1 give
     * each player their own library and hand, and a card can only ever be in its owner's.
     */
    private fun zoneOwnerOf(state: GameState, entityId: EntityId, zoneType: Zone): EntityId? {
        val container = state.getEntity(entityId) ?: return null
        val keyedByController = when (zoneType) {
            Zone.BATTLEFIELD -> state.projectedState.getController(entityId)
                ?: container.get<ControllerComponent>()?.playerId
            Zone.STACK -> container.get<SpellOnStackComponent>()?.casterId
            else -> null
        }
        return keyedByController
            ?: container.get<CardComponent>()?.ownerId
            ?: container.get<OwnerComponent>()?.playerId
    }

    /**
     * The actual zone key for an entity the caller holds, including the stack which deliberately
     * lives outside [GameState.zones]. This is private because callers should ask the visibility
     * question, not derive their own zone identity approximation.
     */
    private fun currentZoneKeyOf(state: GameState, entityId: EntityId): ZoneKey? {
        if (entityId in state.stack) {
            return ZoneKey(zoneOwnerOf(state, entityId, Zone.STACK) ?: return null, Zone.STACK)
        }
        return state.zones.entries.firstOrNull { entityId in it.value }?.key
    }

    // The ingredients below are deliberately private. This class exists because four subsystems
    // each grew their own answer to "may this perspective know this identity?"; handing those
    // ingredients back out is how a fifth local approximation gets built. Consumers ask
    // [isCardIdentityVisibleTo] or [isZoneVisibleTo] and encode the answer however they like.

    private fun isCardRevealedTo(
        state: GameState,
        entityId: EntityId,
        viewingPlayerId: EntityId,
    ): Boolean = state.getEntity(entityId)
        ?.get<RevealedToComponent>()
        ?.isRevealedTo(viewingPlayerId) == true

    private fun hasLookAtFaceDownCreatures(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) { it is LookAtFaceDownCreatures }

    /**
     * Whether the top card of [playerId]'s library is face up to everyone. Two shapes reach this:
     * a self-scoped static that [playerId] themselves controls — [PlayFromTopOfLibrary] (Future
     * Sight) or [RevealTopOfLibrary] (Goblin Spy) — or a symmetric
     * [PlayersRevealTopOfLibrary] (Wizened Snitches) controlled by *any* player, which opens every
     * library including the libraries of players who control nothing.
     */
    private fun revealsTopOfLibraryPublicly(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) {
            it is PlayFromTopOfLibrary || it is RevealTopOfLibrary
        } ||
            state.turnOrder.any { controllerId ->
                hasActiveStaticAbility(state, controllerId) { it is PlayersRevealTopOfLibrary }
            }

    private fun hasLookAtTopOfLibrary(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) { it is LookAtTopOfLibrary }

    private fun revealsOpponentHandsTo(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) { it is OpponentsPlayWithHandsRevealed }

    private fun hasActiveStaticAbility(
        state: GameState,
        playerId: EntityId,
        predicate: (StaticAbility) -> Boolean,
    ): Boolean {
        for (entityId in state.getBattlefield(playerId)) {
            val card = state.getEntity(entityId)?.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue
            if (cardDef.script.staticAbilities.any { ability ->
                    activeStaticAbility(state, ability, entityId, playerId)?.let(predicate) == true
                }
            ) return true
        }
        // Also scan durationally granted statics (e.g. Gwenom, Remorseless grants LookAtTopOfLibrary
        // to itself on attack until end of turn). These live in `grantedStaticAbilities` anchored to
        // the granting permanent, never in `cardDef.script.staticAbilities`. Mirrors the printed-or-
        // granted scan the cast-legality path already uses (CastPermissionUtils.playFromTopAlternativeCost),
        // so visibility and castability stay in lockstep — otherwise the top card is castable but the
        // controller never sees it, so there is nothing to play.
        for (grant in state.grantedStaticAbilities) {
            // Match the cast-legality scan in CastPermissionUtils.playFromTopAlternativeCost (base
            // control) so visibility and castability stay in lockstep.
            val anchor = state.getEntity(grant.entityId) ?: continue
            if (anchor.get<ControllerComponent>()?.playerId != playerId) continue
            if (activeStaticAbility(state, grant.ability, grant.entityId, playerId)?.let(predicate) == true) {
                return true
            }
        }
        return false
    }

    fun activeStaticAbility(
        state: GameState,
        ability: StaticAbility,
        sourceId: EntityId,
        controllerId: EntityId,
    ): StaticAbility? = when (ability) {
        is ConditionalStaticAbility -> {
            val context = EffectContext(sourceId = sourceId, controllerId = controllerId)
            if (conditionEvaluator.evaluate(state, ability.condition, context)) ability.ability else null
        }
        else -> ability
    }
}
