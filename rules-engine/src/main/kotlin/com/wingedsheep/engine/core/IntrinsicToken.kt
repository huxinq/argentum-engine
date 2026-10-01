package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.StaticAbility
import kotlinx.serialization.Serializable

/** Resolved intrinsic characteristics; no token-creation instruction or creator context. */
@Serializable
data class IntrinsicToken(
    val power: Int, val toughness: Int, val colors: Set<Color>, val creatureTypes: Set<String>,
    val keywords: Set<Keyword>, val legendary: Boolean, val artifactToken: Boolean, val enchantmentToken: Boolean,
    val staticAbilities: List<StaticAbility>, val triggeredAbilities: List<TriggeredAbility>,
    val activatedAbilities: List<ActivatedAbility>,
)
