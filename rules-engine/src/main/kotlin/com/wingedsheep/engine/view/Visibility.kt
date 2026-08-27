package com.wingedsheep.engine.view

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
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
import com.wingedsheep.sdk.scripting.RevealTopOfLibrary
import com.wingedsheep.sdk.scripting.StaticAbility

/**
 * Typed result of asking what one viewer may receive for one engine object.
 *
 * [HIDDEN] objects are absent and may not be named by player-facing actions. [FACE_DOWN] objects
 * keep a routable public identity but expose only their face-down characteristics. [IDENTITY]
 * objects expose the card identity and their current characteristics. Consumers must use this
 * result for both object admission and reference admission so an action cannot name an object the
 * corresponding observation omitted.
 */
enum class ObjectVisibilityDecision(
    val admitsReference: Boolean,
    val exposesIdentity: Boolean,
) {
    HIDDEN(admitsReference = false, exposesIdentity = false),
    FACE_DOWN(admitsReference = true, exposesIdentity = false),
    IDENTITY(admitsReference = true, exposesIdentity = true),
}

/**
 * The engine's single source of truth for which hidden-zone identities a player may see.
 *
 * Client masking and AI determinization deliberately share this service. Adding a reveal rule to
 * one without the other would either leak information to the AI or hide information it is legally
 * entitled to use.
 */
class Visibility(
    private val cardRegistry: CardRegistry,
    private val debugMode: Boolean = false,
) {
    private val conditionEvaluator = ConditionEvaluator()

    fun isZoneVisibleTo(
        state: GameState,
        zoneKey: ZoneKey,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean = when (zoneKey.zoneType) {
        Zone.LIBRARY -> debugMode
        Zone.HAND -> debugMode || zoneKey.ownerId == viewingPlayerId ||
            (!isSpectator && state.actorFor(zoneKey.ownerId) == viewingPlayerId) ||
            (!isSpectator && zoneKey.ownerId in state.teammatesOf(viewingPlayerId)) ||
            (!isSpectator && zoneKey.ownerId != viewingPlayerId &&
                revealsOpponentHandsTo(state, viewingPlayerId))
        Zone.SIDEBOARD -> debugMode || zoneKey.ownerId == viewingPlayerId ||
            (!isSpectator && state.actorFor(zoneKey.ownerId) == viewingPlayerId)
        Zone.BATTLEFIELD,
        Zone.GRAVEYARD,
        Zone.STACK,
        Zone.EXILE,
        Zone.COMMAND -> true
    }

    /**
     * Decide admission and identity visibility for one card at its typed zone position.
     *
     * This folds whole-zone visibility, selective [RevealedToComponent] grants, public/private
     * top-library effects, face-down rules, and face-down exile play permission into one result.
     * Callers must not reconstruct a second zone-based approximation.
     */
    fun objectVisibility(
        state: GameState,
        zoneKey: ZoneKey,
        entityId: EntityId,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): ObjectVisibilityDecision {
        if (debugMode) return ObjectVisibilityDecision.IDENTITY

        val specificallyRevealed = !isSpectator && isCardRevealedTo(state, entityId, viewingPlayerId)
        val visibleTopOfLibrary = zoneKey.zoneType == Zone.LIBRARY &&
            state.getZone(zoneKey).firstOrNull() == entityId &&
            (
                revealsTopOfLibraryPublicly(state, zoneKey.ownerId) ||
                    (!isSpectator && zoneKey.ownerId == viewingPlayerId &&
                        hasLookAtTopOfLibrary(state, viewingPlayerId))
                )
        val admitted = isZoneVisibleTo(state, zoneKey, viewingPlayerId, isSpectator) ||
            specificallyRevealed || visibleTopOfLibrary
        if (!admitted) return ObjectVisibilityDecision.HIDDEN

        val container = state.getEntity(entityId) ?: return ObjectVisibilityDecision.HIDDEN
        val faceDownZone = zoneKey.zoneType == Zone.BATTLEFIELD ||
            zoneKey.zoneType == Zone.STACK || zoneKey.zoneType == Zone.EXILE
        val faceDown = faceDownZone && (
            container.get<FaceDownComponent>() != null ||
                container.get<SpellOnStackComponent>()?.castFaceDown == true
            )
        if (!faceDown) return ObjectVisibilityDecision.IDENTITY

        val controller = container.get<ControllerComponent>()?.playerId
            ?: container.get<CardComponent>()?.ownerId
        val mayInspectFaceDown = !isSpectator && (
            controller == viewingPlayerId ||
                specificallyRevealed ||
                (zoneKey.zoneType == Zone.BATTLEFIELD &&
                    hasLookAtFaceDownCreatures(state, viewingPlayerId)) ||
                (zoneKey.zoneType == Zone.EXILE &&
                    state.hasMayPlayFor(entityId, viewingPlayerId, conditionEvaluator, cardRegistry))
            )
        return if (mayInspectFaceDown) {
            ObjectVisibilityDecision.IDENTITY
        } else {
            ObjectVisibilityDecision.FACE_DOWN
        }
    }

    fun isCardRevealedTo(
        state: GameState,
        entityId: EntityId,
        viewingPlayerId: EntityId,
    ): Boolean = state.getEntity(entityId)
        ?.get<RevealedToComponent>()
        ?.isRevealedTo(viewingPlayerId) == true

    fun hasLookAtFaceDownCreatures(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) { it is LookAtFaceDownCreatures }

    fun revealsTopOfLibraryPublicly(state: GameState, playerId: EntityId): Boolean =
        hasActiveStaticAbility(state, playerId) {
            it is PlayFromTopOfLibrary || it is RevealTopOfLibrary
        }

    fun hasLookAtTopOfLibrary(state: GameState, playerId: EntityId): Boolean =
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
