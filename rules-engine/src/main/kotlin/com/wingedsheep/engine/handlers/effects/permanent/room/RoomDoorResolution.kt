package com.wingedsheep.engine.handlers.effects.permanent.room

import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.ChooseDoorContinuation
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.handlers.actions.room.RoomDoorLocker
import com.wingedsheep.engine.handlers.actions.room.RoomDoorUnlocker
import com.wingedsheep.engine.mechanics.layers.StaticAbilityHandler
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.RoomComponent
import com.wingedsheep.engine.state.components.identity.RoomFace
import com.wingedsheep.engine.state.components.identity.RoomFaceId
import com.wingedsheep.sdk.model.EntityId

/**
 * Shared resolution logic for the resolution-time "lock a door" / "unlock a door" effects
 * (CR 709.5f/g), driven by [LockDoorExecutor] and [UnlockDoorExecutor].
 *
 * "A player chooses an unlocked/locked half of that permanent" — so when the targeted Room has more
 * than one eligible door this pauses for the controller to pick which one (a [ChooseDoorContinuation]
 * resumed by [com.wingedsheep.engine.handlers.continuations.RoomDoorContinuationResumer]). With a
 * single eligible door there is no choice and it is applied directly; with none (e.g. "unlock" a
 * fully-unlocked Room, or "lock" a fully-locked one) it resolves as a harmless no-op.
 */
object RoomDoorResolution {

    /**
     * Resolve a lock ([lock] = true) or unlock ([lock] = false) of one door of the Room [roomId].
     * The Room's controller (falling back to [effectControllerId]) makes any door choice and is the
     * resulting event's controller.
     */
    fun resolve(
        state: GameState,
        roomId: EntityId,
        effectControllerId: EntityId,
        lock: Boolean,
        staticAbilityHandler: StaticAbilityHandler,
    ): EffectResult {
        val container = state.getEntity(roomId) ?: return EffectResult.success(state, emptyList())
        val room = container.get<RoomComponent>() ?: return EffectResult.success(state, emptyList())

        val controllerId = container.get<ControllerComponent>()?.playerId ?: effectControllerId

        // Eligible doors: unlocked faces can be locked; locked faces can be unlocked (CR 709.5f/g).
        val candidates: List<RoomFace> = if (lock) room.unlockedFaces else room.lockedFaces

        return when {
            // Nothing to do — choosing this mode on a Room with no eligible door is legal but inert.
            candidates.isEmpty() -> EffectResult.success(state, emptyList())

            // Exactly one eligible door — no real choice, apply it directly.
            candidates.size == 1 -> {
                val (newState, events) = apply(state, roomId, candidates.single().id, controllerId, lock, staticAbilityHandler)
                EffectResult.success(newState, events)
            }

            // Multiple eligible doors — the controller chooses which one (CR 709.5f/g).
            else -> pauseForDoorChoice(state, roomId, container.get<CardComponent>()?.name, candidates, controllerId, lock)
        }
    }

    /** Apply the lock/unlock to a specific [faceId] via the matching shared primitive. */
    fun apply(
        state: GameState,
        roomId: EntityId,
        faceId: RoomFaceId,
        controllerId: EntityId,
        lock: Boolean,
        staticAbilityHandler: StaticAbilityHandler,
    ): Pair<GameState, List<GameEvent>> =
        if (lock) RoomDoorLocker.lock(state, roomId, faceId, controllerId, staticAbilityHandler)
        else RoomDoorUnlocker.unlock(state, roomId, faceId, controllerId, staticAbilityHandler)

    private fun pauseForDoorChoice(
        state: GameState,
        roomId: EntityId,
        roomName: String?,
        candidates: List<RoomFace>,
        controllerId: EntityId,
        lock: Boolean,
    ): EffectResult {
        val verb = if (lock) "lock" else "unlock"

        val decision = { decisionId: String -> ChooseOptionDecision(
            id = decisionId,
            playerId = controllerId,
            prompt = "Choose a door to $verb${roomName?.let { " of $it" } ?: ""}",
            context = DecisionContext(
                sourceId = roomId,
                sourceName = roomName,
                phase = DecisionPhase.RESOLUTION,
            ),
            options = candidates.map { it.name },
        ) }

        val continuation = ChooseDoorContinuation(
            controllerId = controllerId,
            roomId = roomId,
            candidateFaceIds = candidates.map { it.id },
            lock = lock,
        )

        return EffectResult.from(state.suspendForDecision(decision, continuation))
    }
}
