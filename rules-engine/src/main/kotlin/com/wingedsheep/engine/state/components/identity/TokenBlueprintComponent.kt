package com.wingedsheep.engine.state.components.identity

import com.wingedsheep.engine.state.Component
import com.wingedsheep.engine.core.IntrinsicToken
import kotlinx.serialization.Serializable

/** The SDK factory that supplied this generated token's intrinsic characteristics and rules. */
@Serializable
data class TokenBlueprintComponent(val blueprint: IntrinsicToken) : Component
