package com.wingedsheep.ai

/** The responsible policy and its declared fallback could not supply a player choice. */
class ResponsiblePolicyUnavailableException(
    val choiceKind: String,
    diagnostic: String,
) : IllegalStateException("RESPONSIBLE_POLICY_UNAVAILABLE:$choiceKind:$diagnostic")
