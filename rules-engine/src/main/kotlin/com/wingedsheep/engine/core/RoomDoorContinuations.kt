package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.components.identity.RoomFaceId
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resumed after the controller chooses *which* door to lock or unlock, when a resolution-time
 * `LockDoorEffect` / `UnlockDoorEffect` finds a Room with more than one eligible door (CR 709.5f/g).
 *
 * Both door effects funnel through one continuation: [lock] selects lock vs. unlock so the resumer
 * applies the right primitive ([com.wingedsheep.engine.handlers.actions.room.RoomDoorLocker] /
 * [com.wingedsheep.engine.handlers.actions.room.RoomDoorUnlocker]) and emits the matching event.
 *
 * @property candidateFaceIds the eligible doors, positionally aligned with the
 *   `ChooseOptionDecision.options` shown to the player; `OptionChosenResponse.optionIndex` selects one.
 * @property controllerId the player making the choice (the door effect's controller, who also owns
 *   the Room — Keys to the House targets a Room you control) and the event's controller.
 * @property lock `true` to lock the chosen door, `false` to unlock it.
 */
@Serializable
@SerialName("ChooseDoorContinuation")
data class ChooseDoorContinuation(
    val controllerId: EntityId,
    val roomId: EntityId,
    val candidateFaceIds: List<RoomFaceId>,
    val lock: Boolean,
) : AnswerContinuation
