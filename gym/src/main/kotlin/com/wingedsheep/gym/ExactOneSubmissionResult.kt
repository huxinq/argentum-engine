package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameAction

/**
 * The outcome of submitting exactly one action to [GameEnvironment.stepExactlyOne].
 *
 * Rejection is deliberately separate from [StepResult]: it is control flow from the
 * authoritative [com.wingedsheep.engine.core.ActionProcessor] boundary, not a game transition
 * with a reward or terminal outcome.
 */
sealed interface ExactOneSubmissionResult {
    /** The submitted action was accepted and its direct engine result was installed. */
    data class Applied(val step: StepResult) : ExactOneSubmissionResult

    /** The submitted [action] was rejected atomically by the action processor. */
    data class Rejected(
        val action: GameAction,
        val reason: String
    ) : ExactOneSubmissionResult
}
