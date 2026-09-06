package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.GameAction

/** A canonical engine action paired with the live timeline on which the choice originated. */
data class LiveActionSubmission(
    val action: GameAction,
    val interactionEpoch: String,
    val messageId: String? = null,
)
