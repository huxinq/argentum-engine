package com.wingedsheep.engine.core

import kotlinx.serialization.Serializable

/**
 * Stored execution stack entries. A [Suspension] consumes a player's answer;
 * [AutomaticContinuation] resumes work by its position in the stack.
 */
@Serializable
sealed interface ContinuationFrame

/** Work waiting underneath a question. Its stack position supplies its relationship. */
@Serializable
sealed interface AutomaticContinuation : ContinuationFrame

/**
 * The data needed to consume one answer. It has no independent routing identity and cannot be
 * pushed onto the execution stack alone: [Suspension] associates it with the question it answers.
 */
@Serializable
sealed interface AnswerContinuation
