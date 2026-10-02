package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.gameserver.protocol.ServerMessage

/**
 * Authoritative factual observation for one player, using that seat's current card identities.
 * The state, events and choices are the same projection used by the browser. Hypothetical
 * sampled worlds must never be delivered through this factual observation stream.
 */
data class SeatObservation(
    val state: ClientGameState,
    val events: List<ClientEvent>,
    val legalActions: List<LegalActionInfo>,
    val pendingDecision: PendingDecision?,
    val nextStopPoint: String?,
    val opponentDecisionStatus: ServerMessage.OpponentDecisionStatus?,
    val stopOverrides: ServerMessage.StopOverrideInfo?,
    val undoAvailable: Boolean,
    val priorityMode: String,
    val stateVersion: Long,
    val interactionEpoch: String,
    val mulligan: SeatMulligan? = null,
)

/** Opening-hand choices already authorized by the session, in seat-local identities. */
data class SeatMulligan(
    val hand: List<com.wingedsheep.sdk.model.EntityId>,
    val cardsToPutOnBottom: Int,
    val mulliganCount: Int,
    val choosingBottomCards: Boolean,
)
