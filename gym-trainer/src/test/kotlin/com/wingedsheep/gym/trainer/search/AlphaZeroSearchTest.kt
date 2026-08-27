package com.wingedsheep.gym.trainer.search

import com.wingedsheep.ai.ResponsiblePolicyUnavailableException
import com.wingedsheep.engine.core.CancelDecisionResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.trainer.defaults.DynamicSlotActionFeaturizer
import com.wingedsheep.gym.trainer.defaults.HeuristicEvaluator
import com.wingedsheep.gym.trainer.defaults.StructuralFeatures
import com.wingedsheep.gym.trainer.defaults.StructuralStateFeaturizer
import com.wingedsheep.gym.trainer.spi.StructuredDecisionResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.core.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [AlphaZeroSearch] — exercises expansion, PUCT selection,
 * visit accounting, and the Dirichlet noise path.
 */
class AlphaZeroSearchTest : FunSpec({

    fun setupRoot(): GameEnvironment {
        val reg = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val env = GameEnvironment.create(reg)
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 17, "Raging Goblin" to 3))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    test("run expands the root and sums visits to exactly `simulations`") {
        val env = setupRoot()
        val search = AlphaZeroSearch<StructuralFeatures>(
            env = env,
            featurizer = StructuralStateFeaturizer(),
            actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
            evaluator = HeuristicEvaluator(),
            dirichletAlpha = null
        )
        val result = search.run(simulations = 8)

        result.root.edges.shouldNotBeEmpty()
        result.root.visits shouldBeGreaterThan 0
        // Every simulation adds one visit to the root plus one to every
        // descendant on its path; sum of edge visits == simulations.
        result.visits.sum() shouldBe 8
    }

    test("bestEdge is the most-visited edge") {
        val env = setupRoot()
        val search = AlphaZeroSearch<StructuralFeatures>(
            env = env,
            featurizer = StructuralStateFeaturizer(),
            actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
            evaluator = HeuristicEvaluator(),
            dirichletAlpha = null
        )
        val result = search.run(simulations = 12)
        val best = result.bestEdge
        (best != null && result.root.edges.all { it.visits <= best.visits }).shouldBeTrue()
    }

    test("enabling Dirichlet noise is accepted and does not break search") {
        // Sanity test only — verifying the exact prior perturbation needs a
        // root state with multiple legal actions, and the opening priority
        // step often has just one (Pass). The self-play integration test
        // exercises the noise path on real states.
        val env = setupRoot()
        val result = AlphaZeroSearch<StructuralFeatures>(
            env = env,
            featurizer = StructuralStateFeaturizer(),
            actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
            evaluator = HeuristicEvaluator(),
            dirichletAlpha = 0.3,
            dirichletWeight = 0.25
        ).run(simulations = 4)
        result.visits.sum() shouldBe 4
    }

    test("a multi-response engine decision becomes multiple edges with distinct child states") {
        val env = setupRoot()
        var transitions = 0
        while (env.pendingDecision == null && transitions < 300) {
            val pass = env.legalActions().first { it.action is PassPriority }
            env.step(pass.action)
            transitions += 1
        }
        env.pendingDecision.shouldNotBeNull()
        val parent = env.state

        val result = AlphaZeroSearch<StructuralFeatures>(
            env = env,
            featurizer = StructuralStateFeaturizer(),
            actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
            evaluator = HeuristicEvaluator(),
            dirichletAlpha = null,
        ).run(simulations = 16)

        result.root.edges.size shouldBeGreaterThan 1
        result.structuredExpansionExhaustive.shouldBeTrue()
        result.root.edges.mapNotNull { it.child?.state }.toSet().size shouldBeGreaterThan 1
        env.state shouldBe parent
        env.state.rng shouldBe parent.rng
    }

    @Suppress("DEPRECATION")
    test("legacy single-response resolver refuses a real choice without calling the resolver") {
        val env = setupRoot()
        var transitions = 0
        while (env.pendingDecision == null && transitions < 300) {
            val pass = env.legalActions().first { it.action is PassPriority }
            env.step(pass.action)
            transitions += 1
        }
        val pending = env.pendingDecision.shouldNotBeNull()
        var resolverCalled = false
        val resolver = StructuredDecisionResolver { _, decision ->
            resolverCalled = true
            CancelDecisionResponse(decision.id)
        }

        val error = shouldThrow<ResponsiblePolicyUnavailableException> {
            AlphaZeroSearch<StructuralFeatures>(
                env = env,
                featurizer = StructuralStateFeaturizer(),
                actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
                evaluator = HeuristicEvaluator(),
                structuredResolver = resolver,
                dirichletAlpha = null,
            ).run(simulations = 1)
        }

        resolverCalled.shouldBeFalse()
        error.choiceKind shouldBe pending::class.simpleName
        error.message shouldContain "no declared behavior identity or measurement"
    }

    @Suppress("DEPRECATION")
    test("legacy resolver is bypassed only for an independently proven forced response") {
        val env = setupRoot()
        val player = env.playerIds.first()
        env.restore(
            env.state.copy(
                pendingDecision = ChooseColorDecision(
                    id = "forced-color",
                    playerId = player,
                    prompt = "Choose the only available color",
                    context = DecisionContext(),
                    availableColors = setOf(Color.RED),
                ),
            ),
            env.playerIds,
        )
        var resolverCalled = false
        val resolver = StructuredDecisionResolver { _, decision ->
            resolverCalled = true
            CancelDecisionResponse(decision.id)
        }

        val result = AlphaZeroSearch<StructuralFeatures>(
            env = env,
            featurizer = StructuralStateFeaturizer(),
            actionFeaturizer = DynamicSlotActionFeaturizer(headSize = 128),
            evaluator = HeuristicEvaluator(),
            structuredResolver = resolver,
            dirichletAlpha = null,
        ).run(simulations = 1)

        resolverCalled.shouldBeFalse()
        result.root.edges.size shouldBe 1
        result.structuredExpansionExhaustive.shouldBeTrue()
        result.structuredEstimatedResponseCount shouldBe 1L
    }
})
