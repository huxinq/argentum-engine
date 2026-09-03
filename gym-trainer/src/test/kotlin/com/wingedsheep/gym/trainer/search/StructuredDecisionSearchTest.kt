package com.wingedsheep.gym.trainer.search

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.MoveCollectionOrderContinuation
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.trainer.spi.ActionFeaturizer
import com.wingedsheep.gym.trainer.spi.EvaluationResult
import com.wingedsheep.gym.trainer.spi.Evaluator
import com.wingedsheep.gym.trainer.spi.PolicyHead
import com.wingedsheep.gym.trainer.spi.SlotEncoding
import com.wingedsheep.gym.trainer.spi.StateFeaturizer
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpander
import com.wingedsheep.gym.trainer.spi.StructuredDecisionExpansion
import com.wingedsheep.gym.trainer.spi.StructuredDecisionResolver
import com.wingedsheep.gym.trainer.spi.TrainerContext
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class StructuredDecisionSearchTest : FunSpec({

    val stateFeaturizer = object : StateFeaturizer<Unit> {
        override fun featurize(ctx: TrainerContext) = Unit
    }
    val sharedSlotFeaturizer = object : ActionFeaturizer {
        override val heads: List<PolicyHead> = listOf(PolicyHead("shared", 1))

        override fun slot(action: GameAction, ctx: TrainerContext): SlotEncoding =
            SlotEncoding("shared", 0)
    }
    val uniformEvaluator = Evaluator<Unit> { _, _, _ ->
        EvaluationResult(priors = mapOf("shared" to floatArrayOf(1f)), value = 0f)
    }

    fun newDriver(deckCard: String): GameTestDriver = GameTestDriver().apply {
        registerCards(TestCards.all + PredefinedTokens.allTokens)
        initMirrorMatch(
            deck = Deck.of(deckCard to 40),
            skipMulligans = true,
            startingPlayer = 0
        )
        passPriorityUntil(Step.PRECOMBAT_MAIN)
    }

    fun environment(driver: GameTestDriver): GameEnvironment =
        GameEnvironment.create(driver.cardRegistry).also {
            it.restore(driver.state, listOf(driver.player1, driver.player2))
        }

    test("MCTS exposes exact target responses as independent edges and executes each branch") {
        val driver = newDriver("Mountain")
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val ownCreature = driver.putCreatureOnBattlefield(caster, "Raging Goblin")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        repeat(5) { driver.putLandOnBattlefield(caster, "Mountain") }
        val spell = driver.putCardInHand(caster, "Zuko's Exile")

        driver.castSpell(caster, spell).error shouldBe null
        while (driver.state.stack.isNotEmpty() && !driver.isPaused) driver.bothPass()

        val pending = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        pending.legalTargets.values.flatten().toSet() shouldBe setOf(ownCreature, opposingCreature)
        val rootState = driver.state
        val env = environment(driver)

        val fallback = AlphaZeroSearch(
            env = env,
            featurizer = stateFeaturizer,
            actionFeaturizer = sharedSlotFeaturizer,
            evaluator = uniformEvaluator,
            structuredResolver = StructuredDecisionResolver { _, decision ->
                TargetsResponse(decision.id, mapOf(0 to listOf(ownCreature)))
            },
            structuredExpander = StructuredDecisionExpander { _, _ ->
                StructuredDecisionExpansion.Unsupported
            },
            dirichletAlpha = null
        ).run(simulations = 1)
        fallback.root.edges.size shouldBe 1
        (fallback.root.edges.single().action as SubmitDecision).response shouldBe
            TargetsResponse(pending.id, mapOf(0 to listOf(ownCreature)))

        val result = AlphaZeroSearch(
            env = env,
            featurizer = stateFeaturizer,
            actionFeaturizer = sharedSlotFeaturizer,
            evaluator = uniformEvaluator,
            structuredResolver = StructuredDecisionResolver { _, decision ->
                error("exact expansion unexpectedly fell back for ${decision::class.simpleName}")
            },
            dirichletAlpha = null
        ).run(simulations = 2)

        result.root.edges.size shouldBe 2
        result.visits.toList().shouldContainExactly(1, 1)
        result.root.edges.forEach { edge ->
            edge.slot shouldBe SlotEncoding("shared", 0)
            edge.prior.shouldBeExactly(0.5f)
            edge.child shouldNotBe null
        }

        val responsesByTarget = result.root.edges.associate { edge ->
            val response = (edge.action as SubmitDecision).response
                .shouldBeInstanceOf<TargetsResponse>()
            DecisionValidators.validate(pending, response, rootState) shouldBe null
            response.selectedTargets.getValue(0).single() to edge
        }
        responsesByTarget.keys shouldBe setOf(ownCreature, opposingCreature)

        for ((chosenTarget, edge) in responsesByTarget) {
            val branch = GameEnvironment.create(driver.cardRegistry).also {
                it.restore(rootState, listOf(driver.player1, driver.player2))
            }
            branch.step(edge.action)

            branch.lastRejection shouldBe null
            branch.lastStepEvents.filterIsInstance<ZoneChangeEvent>()
                .any { it.entityId == chosenTarget } shouldBe true
            branch.state.getBattlefield().contains(chosenTarget) shouldBe false
            branch.state.getBattlefield().contains(
                if (chosenTarget == ownCreature) opposingCreature else ownCreature
            ) shouldBe true
        }
        responsesByTarget.getValue(ownCreature).child!!.state shouldNotBe
            responsesByTarget.getValue(opposingCreature).child!!.state
    }

    test("default MCTS exposes and executes every small library ordering") {
        val driver = newDriver("Mountain")
        val player = driver.activePlayer!!
        val cards = driver.state.getLibrary(player).take(2)
        val decision = ReorderLibraryDecision(
            id = "search-ordering",
            playerId = player,
            prompt = "Put them back in any order",
            context = DecisionContext(),
            cards = cards,
            cardInfo = emptyMap(),
        )
        val rootState = driver.state
            .withPendingDecision(decision)
            .pushContinuation(
                MoveCollectionOrderContinuation(
                    decisionId = decision.id,
                    playerId = player,
                    sourceId = null,
                    sourceName = "Search ordering probe",
                    cards = cards,
                    destinationZone = Zone.LIBRARY,
                    destinationPlayerId = player,
                )
            )
        val env = GameEnvironment.create(driver.cardRegistry).also {
            it.restore(rootState, listOf(driver.player1, driver.player2))
        }

        val result = AlphaZeroSearch(
            env = env,
            featurizer = stateFeaturizer,
            actionFeaturizer = sharedSlotFeaturizer,
            evaluator = uniformEvaluator,
            structuredResolver = StructuredDecisionResolver { _, pending ->
                error("exact expansion unexpectedly fell back for ${pending::class.simpleName}")
            },
            dirichletAlpha = null,
        ).run(simulations = 2)

        result.root.edges.size shouldBe 2
        result.visits.toList().shouldContainExactly(1, 1)
        val responses = result.root.edges.map { edge ->
            (edge.action as SubmitDecision).response.shouldBeInstanceOf<OrderedResponse>()
        }
        responses.map { it.orderedObjects }.toSet() shouldBe setOf(
            cards,
            cards.reversed(),
        )

        result.root.edges.zip(responses).forEach { (edge, response) ->
            val branch = GameEnvironment.create(driver.cardRegistry).also {
                it.restore(rootState, listOf(driver.player1, driver.player2))
            }
            branch.step(edge.action)

            branch.lastRejection shouldBe null
            branch.state.getLibrary(player).take(2) shouldBe response.orderedObjects
        }
        result.root.edges[0].child!!.state shouldNotBe result.root.edges[1].child!!.state
    }

    test("existing yes-no folding remains a complete two-edge decision") {
        val driver = newDriver("Grizzly Bears")
        val player = driver.activePlayer!!
        driver.putCardOnTopOfLibrary(player, "Forest")
        val train = driver.putCardInHand(player, "Subway Train")
        driver.giveColorlessMana(player, 2)
        driver.giveMana(player, Color.GREEN, 1)

        driver.castSpell(player, train).error shouldBe null
        driver.bothPass()
        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()

        val result = AlphaZeroSearch(
            env = environment(driver),
            featurizer = stateFeaturizer,
            actionFeaturizer = sharedSlotFeaturizer,
            evaluator = uniformEvaluator,
            structuredResolver = StructuredDecisionResolver { _, decision ->
                when (decision) {
                    is SelectManaSourcesDecision ->
                        ManaSourcesSelectedResponse(decision.id, autoPay = true)
                    is SelectCardsDecision ->
                        CardsSelectedResponse(decision.id, decision.options.take(decision.minSelections))
                    else -> error("unexpected fallback for ${decision::class.simpleName}")
                }
            },
            dirichletAlpha = null
        ).run(simulations = 2)

        result.root.edges.size shouldBe 2
        result.root.edges.map { (it.action as SubmitDecision).response::class }
            .all { it == com.wingedsheep.engine.core.YesNoResponse::class } shouldBe true
    }

    test("unsupported multi-card decision keeps one resolver-selected edge") {
        val driver = newDriver("Island")
        val player = driver.activePlayer!!
        val spell = driver.putCardInHand(player, "Thirst for Identity")
        driver.giveColorlessMana(player, 2)
        driver.giveMana(player, Color.BLUE, 1)

        driver.castSpell(player, spell).error shouldBe null
        while (driver.state.stack.isNotEmpty() && !driver.isPaused) driver.bothPass()

        val pending = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        pending.maxSelections shouldBe 2
        val selected = pending.options.take(2)

        val wrongIdError = shouldThrow<IllegalStateException> {
            AlphaZeroSearch(
                env = environment(driver),
                featurizer = stateFeaturizer,
                actionFeaturizer = sharedSlotFeaturizer,
                evaluator = uniformEvaluator,
                structuredExpander = StructuredDecisionExpander { _, _ ->
                    StructuredDecisionExpansion.Complete(
                        listOf(CardsSelectedResponse("wrong-decision", selected))
                    )
                },
                dirichletAlpha = null
            ).run(simulations = 1)
        }
        wrongIdError.message.orEmpty() shouldContain
            "Structured decision expander returned response for wrong-decision"

        val invalidError = shouldThrow<IllegalStateException> {
            AlphaZeroSearch(
                env = environment(driver),
                featurizer = stateFeaturizer,
                actionFeaturizer = sharedSlotFeaturizer,
                evaluator = uniformEvaluator,
                structuredExpander = StructuredDecisionExpander { _, _ ->
                    StructuredDecisionExpansion.Complete(
                        listOf(CardsSelectedResponse(pending.id, emptyList()))
                    )
                },
                dirichletAlpha = null
            ).run(simulations = 1)
        }
        invalidError.message.orEmpty() shouldContain "Structured decision expander returned an illegal"

        var emptyResolverCalls = 0
        val emptyError = shouldThrow<IllegalStateException> {
            AlphaZeroSearch(
                env = environment(driver),
                featurizer = stateFeaturizer,
                actionFeaturizer = sharedSlotFeaturizer,
                evaluator = uniformEvaluator,
                structuredResolver = StructuredDecisionResolver { _, _ ->
                    emptyResolverCalls += 1
                    CardsSelectedResponse(pending.id, selected)
                },
                structuredExpander = StructuredDecisionExpander { _, _ ->
                    StructuredDecisionExpansion.Complete(emptyList())
                },
                dirichletAlpha = null
            ).run(simulations = 1)
        }
        emptyError.message.orEmpty() shouldContain "Complete response expansion"
        emptyResolverCalls shouldBe 0

        val duplicateError = shouldThrow<IllegalStateException> {
            AlphaZeroSearch(
                env = environment(driver),
                featurizer = stateFeaturizer,
                actionFeaturizer = sharedSlotFeaturizer,
                evaluator = uniformEvaluator,
                structuredExpander = StructuredDecisionExpander { _, _ ->
                    StructuredDecisionExpansion.Complete(
                        listOf(
                            CardsSelectedResponse(pending.id, selected),
                            CardsSelectedResponse(pending.id, selected)
                        )
                    )
                },
                dirichletAlpha = null
            ).run(simulations = 1)
        }
        duplicateError.message.orEmpty() shouldContain
            "Structured expander returned duplicate CardsSelectedResponse"

        var resolverCalls = 0
        val result = AlphaZeroSearch(
            env = environment(driver),
            featurizer = stateFeaturizer,
            actionFeaturizer = sharedSlotFeaturizer,
            evaluator = uniformEvaluator,
            structuredResolver = StructuredDecisionResolver { _, decision ->
                resolverCalls += 1
                CardsSelectedResponse(decision.id, selected)
            },
            dirichletAlpha = null
        ).run(simulations = 1)

        resolverCalls shouldBeGreaterThan 0
        result.root.edges.size shouldBe 1
        val response: DecisionResponse = (result.root.edges.single().action as SubmitDecision).response
        response shouldBe CardsSelectedResponse(pending.id, selected)
        result.root.edges.single().child shouldNotBe null
    }
})
