package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.ContinuationHandler
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.effects.SuccessCriterion
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

class ActionProcessorAtomicityTest : ScenarioTestBase() {
    init {
        test("central error normalization keeps only the original state and message") {
            val game = scenario().withPlayers().build()
            val originalState = game.state
            val changedState = originalState.copy(turnNumber = originalState.turnNumber + 1)
            val misleadingDecision = YesNoDecision(
                id = "misleading-decision",
                playerId = game.player1Id,
                prompt = "This decision belongs to a rejected operation",
                context = DecisionContext()
            )
            val misleadingEvent = LifeChangedEvent(
                playerId = game.player1Id,
                oldLife = 20,
                newLife = 19,
                reason = LifeChangeReason.DAMAGE
            )

            val normalized = normalizeFailedAction(
                originalState,
                ExecutionResult(
                    state = changedState,
                    events = listOf(misleadingEvent),
                    error = "synthetic handler failure",
                    pendingDecision = misleadingDecision,
                    triggersAlreadyProcessed = true
                )
            )

            (normalized.state === originalState) shouldBe true
            normalized.error shouldBe "synthetic handler failure"
            normalized.events.shouldBeEmpty()
            normalized.pendingDecision shouldBe null
            normalized.triggersAlreadyProcessed shouldBe false
        }

        test("damage applied before a later continuation error is rolled back atomically") {
            val game = scenario().withPlayers().build()
            val decisionId = "divide-damage"
            val invalidBranchTarget = EffectTarget.ContextTarget(0)
            val followUp = GatedActionContinuation(
                decisionId = "after-damage",
                then = Effects.GainLife(1, invalidBranchTarget),
                otherwise = null,
                successCriterion = SuccessCriterion.Always,
                snapshot = GatedActionSnapshot(),
                effectContext = EffectContext(
                    sourceId = null,
                    controllerId = game.player1Id,
                    targets = emptyList()
                )
            )
            val distribute = DistributeDamageContinuation(
                decisionId = decisionId,
                sourceId = null,
                controllerId = game.player1Id,
                targets = listOf(game.player2Id)
            )
            val decision = DistributeDecision(
                id = decisionId,
                playerId = game.player1Id,
                prompt = "Divide 1 damage",
                context = DecisionContext(),
                totalAmount = 1,
                targets = listOf(game.player2Id),
                minPerTarget = 1
            )
            val originalState = game.state
                .pushContinuation(followUp)
                .pushContinuation(distribute)
                .withPendingDecision(decision)
            game.state = originalState

            val result = game.submitDecision(
                DistributionResponse(
                    decisionId = decisionId,
                    distribution = linkedMapOf(game.player2Id to 1)
                )
            )

            result.error shouldBe "No valid target for life gain"
            (result.state === originalState) shouldBe true
            result.state.lifeTotal(game.player2Id) shouldBe 20
            result.events.shouldBeEmpty()
            result.pendingDecision shouldBe null
            result.triggersAlreadyProcessed shouldBe false
        }

        test("a continuation mismatch preserves the still-pending entry state") {
            val game = scenario().withPlayers().build()
            val pendingId = "pending-decision"
            val originalState = game.state
                .pushContinuation(
                    DistributeDamageContinuation(
                        decisionId = "different-continuation",
                        sourceId = null,
                        controllerId = game.player1Id,
                        targets = listOf(game.player2Id)
                    )
                )
                .withPendingDecision(
                    DistributeDecision(
                        id = pendingId,
                        playerId = game.player1Id,
                        prompt = "Divide 1 damage",
                        context = DecisionContext(),
                        totalAmount = 1,
                        targets = listOf(game.player2Id),
                        minPerTarget = 1
                    )
                )
            game.state = originalState

            val result = game.submitDecision(
                DistributionResponse(pendingId, mapOf(game.player2Id to 1))
            )

            result.error shouldBe
                "Decision ID mismatch: expected different-continuation, got pending-decision"
            (result.state === originalState) shouldBe true
            result.state.pendingDecision shouldBe originalState.pendingDecision
            result.events.shouldBeEmpty()
            result.pendingDecision shouldBe null
        }

        test("every sealed action and continuation route crosses the shared rollback invariant") {
            val services = EngineServices(CardRegistry())
            val actionTypes = ActionProcessor(services).registeredActionTypes()
            val continuationHandler = ContinuationHandler(services)
            val responseTypes = continuationHandler.registeredResponseTypes()
            val automaticTypes = continuationHandler.registeredAutomaticTypes()
            val sealedActions = concreteSealedLeaves(GameAction::class)
            val sealedContinuations = concreteSealedLeaves(ContinuationFrame::class)

            actionTypes.toSet() shouldBe sealedActions
            (responseTypes + automaticTypes).toSet() shouldBe sealedContinuations
            actionTypes.size shouldBe 22
            responseTypes.size shouldBe 149
            automaticTypes.size shouldBe 17
            responseTypes.intersect(automaticTypes).size shouldBe 2
            sealedContinuations.size shouldBe 164

            val routes = buildList {
                actionTypes.sortedBy { it.qualifiedName }.forEach { add("action:${it.qualifiedName}") }
                responseTypes.sortedBy { it.qualifiedName }.forEach { add("response:${it.qualifiedName}") }
                automaticTypes.sortedBy { it.qualifiedName }.forEach { add("automatic:${it.qualifiedName}") }
            }
            routes.size shouldBe 188

            val game = scenario().withPlayers().build()
            val originalState = game.state
            routes.forEachIndexed { index, route ->
                val error = "synthetic registered-route failure: $route"
                val contaminated = ExecutionResult(
                    state = originalState.copy(turnNumber = originalState.turnNumber + index + 1),
                    events = listOf(
                        LifeChangedEvent(
                            playerId = game.player1Id,
                            oldLife = 20,
                            newLife = 19,
                            reason = LifeChangeReason.DAMAGE
                        )
                    ),
                    error = error,
                    pendingDecision = YesNoDecision(
                        id = "breadth-$index",
                        playerId = game.player1Id,
                        prompt = "Rejected registered route",
                        context = DecisionContext()
                    ),
                    triggersAlreadyProcessed = true
                )

                val normalized = normalizeFailedAction(originalState, contaminated)

                (normalized.state === originalState) shouldBe true
                normalized.error shouldBe error
                normalized.events.shouldBeEmpty()
                normalized.pendingDecision shouldBe null
                normalized.triggersAlreadyProcessed shouldBe false
            }
        }
    }
}

private fun concreteSealedLeaves(root: KClass<*>): Set<KClass<*>> =
    root.sealedSubclasses.flatMap { child ->
        when {
            child.isSealed -> concreteSealedLeaves(child)
            child.isAbstract -> emptySet()
            else -> setOf(child)
        }
    }.toSet()
