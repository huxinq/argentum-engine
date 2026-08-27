package com.wingedsheep.gym.trainer.defaults

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionContinuation
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.DamageAssignmentComponent
import com.wingedsheep.engine.state.components.combat.DamageAssignmentOrderComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

class BoundedStructuredDecisionExpanderTest : FunSpec({
    val player = EntityId("player")
    val a = EntityId("a")
    val b = EntityId("b")
    val c = EntityId("c")
    val context = DecisionContext(sourceId = EntityId("source"), sourceName = "Test")
    val cardInfo = SearchCardInfo("Card", "{1}", "Creature")

    fun decisions(): List<PendingDecision> = listOf(
        YesNoDecision("yes-no", player, "Choose", context),
        BatchYesNoDecision("batch", player, "Choose", context, count = 2),
        ChooseNumberDecision("number", player, "Choose", context, 0, 2),
        ChooseColorDecision("color", player, "Choose", context, setOf(Color.RED, Color.BLUE)),
        ChooseOptionDecision("option", player, "Choose", context, listOf("A", "B"), canCancel = true),
        ChooseReplacementDecision(
            "replacement",
            player,
            "Choose",
            context,
            fromOptions = listOf("red", "blue"),
            toOptions = listOf("green", "white"),
            allowedToByFrom = listOf(listOf(0), listOf(1)),
        ),
        ChooseModeDecision(
            "modes",
            player,
            "Choose",
            context,
            modes = listOf(ModeOption(0, "A"), ModeOption(1, "B")),
            minModes = 1,
            maxModes = 2,
        ),
        SelectCardsDecision("cards", player, "Choose", context, listOf(a, b, c), 1, 2),
        SearchLibraryDecision(
            "search",
            player,
            "Choose",
            context,
            options = listOf(a, b),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(a to cardInfo, b to cardInfo),
            filterDescription = "a card",
        ),
        ChooseTargetsDecision(
            "targets",
            player,
            "Choose",
            context,
            targetRequirements = listOf(TargetRequirementInfo(0, "target", 1, 1)),
            legalTargets = mapOf(0 to listOf(a, b)),
            canCancel = true,
        ),
        DistributeDecision("distribution", player, "Choose", context, 2, listOf(a, b)),
        OrderObjectsDecision("order", player, "Choose", context, listOf(a, b, c)),
        ReorderLibraryDecision(
            "reorder",
            player,
            "Choose",
            context,
            cards = listOf(a, b, c),
            cardInfo = mapOf(a to cardInfo, b to cardInfo, c to cardInfo),
        ),
        SplitPilesDecision("piles", player, "Choose", context, listOf(a, b), 2),
        AssignDamageDecision(
            "damage",
            player,
            "Choose",
            context,
            attackerId = a,
            availablePower = 2,
            orderedTargets = listOf(b),
            defenderId = null,
            minimumAssignments = mapOf(b to 1),
            defaultAssignments = mapOf(b to 2),
            hasTrample = false,
            hasDeathtouch = false,
        ),
        CombatResolutionDecision(
            "combat",
            player,
            "Choose",
            context,
            firstStrike = false,
            attackers = emptyList(),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
        ),
        SelectManaSourcesDecision(
            "mana",
            player,
            "Choose",
            context,
            availableSources = listOf(ManaSourceOption(a, "Mountain", setOf(Color.RED), false)),
            requiredCost = "{R}",
            autoPaySuggestion = listOf(a),
            canDecline = true,
        ),
        BudgetModalDecision(
            "budget",
            player,
            "Choose",
            context,
            budget = 2,
            modes = listOf(BudgetModeOption(1, "A"), BudgetModeOption(2, "B")),
        ),
    )

    test("every pending-decision subtype expands deterministically to valid, duplicate-free responses") {
        val state = GameState(initialSeed = 1234L)
        val expander = BoundedStructuredDecisionExpander()

        for (decision in decisions()) {
            val before = state
            val first = expander.expand(state, decision)
            val second = expander.expand(state, decision)

            first.responses.shouldNotBeEmpty()
            first shouldBe second
            first.responses.size shouldBe first.responses.toSet().size
            first.responses.forEach { DecisionValidators.validate(decision, it, state) shouldBe null }
            state shouldBe before
        }
    }

    test("response spaces at or below the cap are exhaustive") {
        val decision = ChooseNumberDecision("small", player, "Choose", context, 0, 3)
        val expansion = BoundedStructuredDecisionExpander(maxResponses = 64).expand(GameState(), decision)

        expansion.isExhaustive.shouldBeTrue()
        expansion.responses.size shouldBe 4
        expansion.estimatedResponseCount shouldBe 4L
    }

    test("response spaces above the cap are deterministic and explicitly non-exhaustive") {
        val decision = ChooseNumberDecision("large", player, "Choose", context, 0, 100)
        val expander = BoundedStructuredDecisionExpander(maxResponses = 64)
        val first = expander.expand(GameState(), decision)
        val second = expander.expand(GameState(), decision)

        first.isExhaustive.shouldBeFalse()
        first.responses.size shouldBe 64
        first.estimatedResponseCount shouldBe 101L
        first shouldBe second
    }

    test("one combat ordering group is exhaustively enumerated") {
        val decision = CombatResolutionDecision(
            "one-order",
            player,
            "Order blockers",
            context,
            firstStrike = false,
            attackers = listOf(resolutionAttacker(a, listOf(b, c))),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
        )

        val expansion = BoundedStructuredDecisionExpander(maxResponses = 64).expand(GameState(), decision)

        expansion.isExhaustive.shouldBeTrue()
        expansion.responses.size shouldBe 2
        expansion.responses.filterIsInstance<CombatResolutionResponse>()
            .any { it.orderedBlockers[a] == listOf(c, b) }
            .shouldBeTrue()
    }

    test("multiple independent combat ordering groups are crossed exhaustively") {
        val decision = CombatResolutionDecision(
            "two-orders",
            player,
            "Order blockers",
            context,
            firstStrike = false,
            attackers = listOf(
                resolutionAttacker(a, listOf(b, c)),
                resolutionAttacker(EntityId("d"), listOf(EntityId("e"), EntityId("f"))),
            ),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
        )

        val expansion = BoundedStructuredDecisionExpander(maxResponses = 64).expand(GameState(), decision)

        expansion.isExhaustive.shouldBeTrue()
        expansion.responses.size shouldBe 4
        expansion.estimatedResponseCount shouldBe 4L
        expansion.responses.filterIsInstance<CombatResolutionResponse>()
            .any { response ->
                response.orderedBlockers[a] == listOf(c, b) &&
                    response.orderedBlockers[EntityId("d")] == listOf(EntityId("f"), EntityId("e"))
            }
            .shouldBeTrue()
    }

    test("combat expansion crosses every legal damage assignment with every blocker order") {
        val defender = EntityId("defender")
        val opponent = EntityId("opponent")
        val blockerEdge = "a->b"
        val otherBlockerEdge = "a->c"
        val trampleEdge = "a->defender"
        val decision = CombatResolutionDecision(
            id = "order-damage-product",
            playerId = player,
            prompt = "Assign combat damage",
            context = context,
            firstStrike = false,
            attackers = listOf(
                resolutionAttacker(
                    id = a,
                    blockers = listOf(b, c),
                    power = 4,
                    hasTrample = true,
                    hasDeathtouch = true,
                )
            ),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = listOf(
                DamageEdge(
                    id = blockerEdge,
                    sourceId = a,
                    targetId = b,
                    direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                    amount = 1,
                    maximum = 4,
                    lethal = 1,
                    orderConstrained = true,
                    isTrampleDrain = false,
                    editableBy = player,
                ),
                DamageEdge(
                    id = otherBlockerEdge,
                    sourceId = a,
                    targetId = c,
                    direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                    amount = 1,
                    maximum = 4,
                    lethal = 1,
                    orderConstrained = true,
                    isTrampleDrain = false,
                    editableBy = player,
                ),
                DamageEdge(
                    id = trampleEdge,
                    sourceId = a,
                    targetId = defender,
                    direction = DamageEdgeDirection.ATTACKER_TO_PLAYER,
                    amount = 2,
                    maximum = 4,
                    lethal = 0,
                    orderConstrained = false,
                    isTrampleDrain = true,
                    editableBy = player,
                ),
            ),
        )

        // Independent oracle: enumerate the three integer amounts directly, then apply only the
        // source budget and trample lethal-first constraints. It deliberately does not call the
        // production candidate generator or DecisionValidators to decide membership.
        val expected = buildSet {
            for (toFirstBlocker in 0..4) {
                for (toSecondBlocker in 0..4) {
                    for (toDefender in 0..4) {
                        if (toFirstBlocker + toSecondBlocker + toDefender > 4) continue
                        if (toDefender > 0 && (toFirstBlocker < 1 || toSecondBlocker < 1)) continue
                        val edges = listOf(
                            DamageEdgeAmount(blockerEdge, toFirstBlocker),
                            DamageEdgeAmount(otherBlockerEdge, toSecondBlocker),
                            DamageEdgeAmount(trampleEdge, toDefender),
                        )
                        add(CombatResolutionResponse(decision.id, edges))
                        add(
                            CombatResolutionResponse(
                                decisionId = decision.id,
                                edges = edges,
                                orderedBlockers = mapOf(a to listOf(c, b)),
                            )
                        )
                    }
                }
            }
        }
        expected.size shouldBe 38

        val expander = BoundedStructuredDecisionExpander(maxResponses = 64)
        val expansion = expander.expand(GameState(), decision)
        val repeated = expander.expand(GameState(), decision)
        val actual = expansion.responses.filterIsInstance<CombatResolutionResponse>()

        expansion shouldBe repeated
        expansion.isExhaustive.shouldBeTrue()
        expansion.estimatedResponseCount shouldBe expected.size.toLong()
        actual.size shouldBe expected.size
        actual.toSet() shouldBe expected
        actual.first() shouldBe CombatResolutionResponse(
            decision.id,
            listOf(
                DamageEdgeAmount(blockerEdge, 1),
                DamageEdgeAmount(otherBlockerEdge, 1),
                DamageEdgeAmount(trampleEdge, 2),
            ),
        )

        val combinedAlternative = CombatResolutionResponse(
            decisionId = decision.id,
            edges = listOf(
                DamageEdgeAmount(blockerEdge, 2),
                DamageEdgeAmount(otherBlockerEdge, 1),
                DamageEdgeAmount(trampleEdge, 1),
            ),
            orderedBlockers = mapOf(a to listOf(c, b)),
        )
        (combinedAlternative in actual).shouldBeTrue()

        // Submit the generated product through the real action/continuation boundary. The minimal
        // state has no combat entities on the battlefield, so execution records the chosen damage
        // and order without dealing incidental damage that would obscure this contract check.
        val executableState = GameState(
            turnOrder = listOf(player, opponent),
            activePlayerId = player,
            phase = Phase.COMBAT,
            step = Step.COMBAT_DAMAGE,
        ).withEntity(a, ComponentContainer.EMPTY).withPendingDecision(decision).pushContinuation(
            CombatResolutionContinuation(
                decisionId = decision.id,
                firstStrike = false,
                pendingChoosers = listOf(player),
                decisionShape = decision,
            )
        )
        executableState.hasEntity(a).shouldBeTrue()
        val processor = ActionProcessor(EngineServices(CardRegistry()), computeUndo = false)
        for (response in actual) {
            val result = processor.process(executableState, SubmitDecision(player, response)).result
            result.error shouldBe null
        }
        val executed = processor.process(
            executableState,
            SubmitDecision(player, combinedAlternative),
        ).result
        executed.state.getEntity(a)?.get<DamageAssignmentComponent>()?.assignments shouldBe
            mapOf(b to 2, c to 1, defender to 1)
        executed.state.getEntity(a)?.get<DamageAssignmentOrderComponent>()?.orderedBlockers shouldBe
            listOf(c, b)

        val attemptLimited = BoundedStructuredDecisionExpander(
            maxResponses = 4,
            maxAttempts = 4,
        ).expand(GameState(), decision)
        attemptLimited.isExhaustive.shouldBeFalse()
        attemptLimited.estimatedResponseCount shouldBe null
    }
})

private fun resolutionAttacker(
    id: EntityId,
    blockers: List<EntityId>,
    power: Int = 0,
    hasTrample: Boolean = false,
    hasDeathtouch: Boolean = false,
) = ResolutionAttacker(
    id = id,
    name = id.value,
    power = power,
    toughness = 1,
    hasTrample = hasTrample,
    hasDeathtouch = hasDeathtouch,
    hasFirstStrike = false,
    hasDoubleStrike = false,
    dealsDamageThisStep = true,
    bandId = null,
    attackedDefenderId = EntityId("defender"),
    blockedByIds = blockers,
    markedDamage = 0,
)
