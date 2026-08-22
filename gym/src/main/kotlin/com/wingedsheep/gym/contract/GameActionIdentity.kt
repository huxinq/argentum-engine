package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseManaColor
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.sdk.model.EntityId

/** Primary visible game object an action operates on, when the action has exactly one. */
fun GameAction.sourceEntityIdOrNull(): EntityId? = when (this) {
    is CastSpell -> cardId
    is ActivateAbility -> sourceId
    is CycleCard -> cardId
    is PlotCard -> cardId
    is ForetellCard -> cardId
    is SuspendCardFromHand -> cardId
    is TypecycleCard -> cardId
    is PlayLand -> cardId
    is OrderBlockers -> attackerId
    is CrewVehicle -> vehicleId
    is SaddleMount -> mountId
    is TurnFaceUp -> sourceId
    is UnlockRoomDoor -> roomId
    is PassPriority,
    is DeclareAttackers,
    is DeclareBlockers,
    is ChooseManaColor,
    is SubmitDecision,
    is TakeMulligan,
    is KeepHand,
    is BottomCards,
    is Concede -> null
}
