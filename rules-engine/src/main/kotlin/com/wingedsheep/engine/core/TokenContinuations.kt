package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect
import kotlinx.serialization.Serializable

/**
 * Resume token creation after the player answers a "may" question for
 * ReplaceTokenCreationWithAttachedCopy (Mirrormind Crown, Moonlit Meditation).
 *
 * If yes: create [tokenCount] token copies of the attached permanent.
 * If no: execute the original [originalEffect] normally — this can be any
 * token-creating effect (e.g. CreateTokenEffect, CreateTokenCopyOfTargetEffect).
 *
 * @property sourceId The Equipment / Aura / other permanent with the replacement effect
 * @property attachedPermanentId The permanent attached at the time the decision was posed
 * @property originalEffect The original token creation effect (used if player declines)
 * @property tokenCount The evaluated number of tokens to create
 * @property effectContext The execution context from the original effect
 */
@Serializable
data class TokenCreationReplacementContinuation(
    val sourceId: EntityId,
    val attachedPermanentId: EntityId,
    val originalEffect: Effect,
    val tokenCount: Int,
    val effectContext: EffectContext
) : AnswerContinuation
