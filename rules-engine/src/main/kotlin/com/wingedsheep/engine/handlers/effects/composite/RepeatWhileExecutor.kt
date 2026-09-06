package com.wingedsheep.engine.handlers.effects.composite

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.DecisionHandler
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.RepeatCondition
import com.wingedsheep.sdk.scripting.effects.RepeatWhileEffect
import kotlin.reflect.KClass

/**
 * Executor for RepeatWhileEffect.
 *
 * Executes the body at least once, then evaluates the repeat condition:
 * - [RepeatCondition.PlayerChooses]: pauses for a yes/no decision
 * - [RepeatCondition.WhileCondition]: evaluates synchronously
 *
 * Uses the pre-push pattern (same as CompositeEffectExecutor):
 * 1. Pre-push RepeatWhileContinuation(phase=AFTER_BODY)
 * 2. Execute body via effectExecutor
 * 3. If body pauses → return (AFTER_BODY continuation sits below body's)
 * 4. If body succeeds → pop AFTER_BODY → ask condition
 *
 * For PlayerChooses, askDecider() creates a yes/no decision and pushes
 * AFTER_DECISION continuation. For WhileCondition, evaluates synchronously
 * and either starts another iteration or completes.
 */
class RepeatWhileExecutor(
    private val effectExecutor: (GameState, Effect, EffectContext) -> EffectResult
) : EffectExecutor<RepeatWhileEffect> {

    override val effectType: KClass<RepeatWhileEffect> = RepeatWhileEffect::class

    override fun execute(
        state: GameState,
        effect: RepeatWhileEffect,
        context: EffectContext
    ): EffectResult {
        // Resolve the decider ID (for PlayerChooses) once at the start
        val resolvedDeciderId = when (val cond = effect.repeatCondition) {
            is RepeatCondition.PlayerChooses ->
                context.resolvePlayerTarget(cond.decider)
                    ?: return EffectResult.error(state, "RepeatWhile: could not resolve decider target")
            is RepeatCondition.WhileCondition -> null
        }

        val sourceName = context.sourceId?.let { sourceId ->
            state.getEntity(sourceId)?.get<CardComponent>()?.name
        }

        return executeIteration(
            state = state,
            body = effect.body,
            repeatCondition = effect.repeatCondition,
            resolvedDeciderId = resolvedDeciderId,
            context = context,
            sourceName = sourceName,
            effectExecutor = effectExecutor,
            priorEvents = emptyList()
        )
    }

    companion object {
        /**
         * Execute one iteration of the repeat loop.
         *
         * Pre-pushes an AFTER_BODY continuation, then executes the body.
         * If the body completes synchronously, pops the continuation and asks the condition.
         */
        fun executeIteration(
            state: GameState,
            body: Effect,
            repeatCondition: RepeatCondition,
            resolvedDeciderId: EntityId?,
            context: EffectContext,
            sourceName: String?,
            effectExecutor: (GameState, Effect, EffectContext) -> EffectResult,
            priorEvents: List<GameEvent>
        ): EffectResult {
            // Pre-push AFTER_BODY continuation
            val afterBodyContinuation = RepeatWhileContinuation(
                body = body,
                repeatCondition = repeatCondition,
                resolvedDeciderId = resolvedDeciderId,
                sourceName = sourceName,
                effectContext = context
            )

            val stateWithContinuation = state.pushContinuation(afterBodyContinuation)

            // Execute the body
            val result = effectExecutor(stateWithContinuation, body, context)

            if (result.isPaused) {
                // Body paused — AFTER_BODY continuation is below body's continuation on the stack.
                // checkForMoreContinuations will handle AFTER_BODY after the body's decision resolves.
                return EffectResult.propagatePause(
                    result.state,
                    priorEvents + result.events
                )
            }

            if (!result.isSuccess) {
                // Body failed — pop AFTER_BODY and return error
                val (_, stateWithoutCont) = result.state.popContinuation()
                return EffectResult(stateWithoutCont, priorEvents + result.events, result.error)
            }

            // Body completed synchronously — pop AFTER_BODY and ask condition.
            //
            // A WhileCondition is evaluated against the *body's own outputs this iteration* (e.g.
            // CollectionSharesCardType over the cards milled this pass — The Tale of Tamiyo), so the
            // body's pipeline collections are handed to askCondition as [bodyOutputs]. They are
            // merged onto the context for the condition check only — never into the next iteration's
            // body context, which must stay the pristine pre-loop context: CompositeEffectExecutor
            // only surfaces *new* collection keys, so a stale `milled` carried forward would mask
            // the next pass's mill and the loop would never terminate.
            val (_, stateAfterPop) = result.state.popContinuation()
            return askCondition(
                state = stateAfterPop,
                body = body,
                repeatCondition = repeatCondition,
                resolvedDeciderId = resolvedDeciderId,
                context = context,
                sourceName = sourceName,
                effectExecutor = effectExecutor,
                priorEvents = priorEvents + result.events,
                bodyOutputs = BodyOutputs(
                    collections = result.updatedCollections,
                    subtypeGroups = result.updatedSubtypeGroups,
                    numbers = result.updatedStoredNumbers,
                    chosenValues = result.updatedChosenValues,
                ),
            )
        }

        /** The pipeline outputs of one body execution, merged onto the context for the condition. */
        data class BodyOutputs(
            val collections: Map<String, List<EntityId>> = emptyMap(),
            val subtypeGroups: Map<String, List<Set<String>>> = emptyMap(),
            val numbers: Map<String, Int> = emptyMap(),
            val chosenValues: Map<String, String> = emptyMap(),
        ) {
            val isEmpty: Boolean
                get() = collections.isEmpty() && subtypeGroups.isEmpty() &&
                    numbers.isEmpty() && chosenValues.isEmpty()
        }

        /**
         * After the body completes, evaluate the repeat condition.
         *
         * For PlayerChooses: create yes/no decision and push AFTER_DECISION continuation.
         * For WhileCondition: evaluate synchronously (against [context] merged with [bodyOutputs])
         * and either repeat or finish. The recursion uses the pristine [context] so each iteration's
         * body starts fresh (see executeIteration's note on why stale collections must not leak).
         */
        fun askCondition(
            state: GameState,
            body: Effect,
            repeatCondition: RepeatCondition,
            resolvedDeciderId: EntityId?,
            context: EffectContext,
            sourceName: String?,
            effectExecutor: (GameState, Effect, EffectContext) -> EffectResult,
            priorEvents: List<GameEvent>,
            conditionEvaluator: com.wingedsheep.engine.handlers.ConditionEvaluator? = null,
            bodyOutputs: BodyOutputs = BodyOutputs(),
        ): EffectResult {
            return when (repeatCondition) {
                is RepeatCondition.PlayerChooses -> {
                    askDecider(
                        state = state,
                        body = body,
                        repeatCondition = repeatCondition,
                        resolvedDeciderId = resolvedDeciderId!!,
                        context = context,
                        sourceName = sourceName,
                        priorEvents = priorEvents
                    )
                }
                is RepeatCondition.WhileCondition -> {
                    val evaluator = conditionEvaluator ?: com.wingedsheep.engine.handlers.ConditionEvaluator()
                    val conditionContext = if (bodyOutputs.isEmpty) context else context.copy(
                        pipeline = context.pipeline.copy(
                            storedCollections = context.pipeline.storedCollections + bodyOutputs.collections,
                            storedSubtypeGroups = context.pipeline.storedSubtypeGroups + bodyOutputs.subtypeGroups,
                            storedNumbers = context.pipeline.storedNumbers + bodyOutputs.numbers,
                            chosenValues = context.pipeline.chosenValues + bodyOutputs.chosenValues,
                        )
                    )
                    val shouldRepeat = evaluator.evaluate(state, repeatCondition.condition, conditionContext)
                    if (shouldRepeat) {
                        // Deepen resolution depth per iteration so a WhileCondition that never goes
                        // false is caught by the EffectExecutorRegistry depth guard (this recursion
                        // is on the JVM call stack, not via pushContinuation) instead of overflowing
                        // it. See GameLimits.MAX_RESOLUTION_DEPTH.
                        executeIteration(
                            state = state,
                            body = body,
                            repeatCondition = repeatCondition,
                            resolvedDeciderId = null,
                            context = context.copy(resolutionDepth = context.resolutionDepth + 1),
                            sourceName = sourceName,
                            effectExecutor = effectExecutor,
                            priorEvents = priorEvents
                        )
                    } else {
                        EffectResult.success(state, priorEvents)
                    }
                }
            }
        }

        /**
         * Ask a player whether to repeat (PlayerChooses condition).
         * Creates a YesNoDecision and pushes an AFTER_DECISION continuation.
         */
        fun askDecider(
            state: GameState,
            body: Effect,
            repeatCondition: RepeatCondition.PlayerChooses,
            resolvedDeciderId: EntityId,
            context: EffectContext,
            sourceName: String?,
            priorEvents: List<GameEvent>
        ): EffectResult {
            val decisionHandler = DecisionHandler()
            val continuation = RepeatWhileDecisionContinuation(loop = RepeatWhileContinuation(
                body = body,
                repeatCondition = repeatCondition,
                resolvedDeciderId = resolvedDeciderId,
                sourceName = sourceName,
                effectContext = context
            ))

            val decisionResult = decisionHandler.createYesNoDecision(
                state = state,
                playerId = resolvedDeciderId,
                sourceId = context.sourceId,
                sourceName = sourceName,
                prompt = repeatCondition.prompt,
                yesText = repeatCondition.yesText,
                noText = repeatCondition.noText,
                phase = DecisionPhase.RESOLUTION,
                answer = continuation
            )

            val stateWithContinuation = decisionResult.state

            return EffectResult.propagatePause(
                stateWithContinuation,
                priorEvents + decisionResult.events
            )
        }
    }
}
