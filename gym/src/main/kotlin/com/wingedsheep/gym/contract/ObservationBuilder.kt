package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.engine.state.components.player.PlayerSpeedComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BlockedComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Converts `(GameState, perspectivePlayerId)` into a [TrainingObservation].
 *
 * ## Information hiding
 *
 * By default, opponent hand and everyone's library are hidden
 * ([ZoneView.hidden] = true, [ZoneView.cards] empty). Set [revealAll] to
 * `true` to disable masking — only appropriate for debug scripts; never
 * for real self-play training.
 *
 * ## Projected vs. base state
 *
 * All per-entity fields (types, subtypes, colors, keywords, power, toughness,
 * controller) are read from [GameState.projectedState] so Rule 613 continuous
 * effects are reflected. The zone a card sits in still comes from the base
 * zone map (control-changing effects don't move cards between owner-keyed
 * zones — see `GameState.getBattlefield`).
 */
class ObservationBuilder(
    private val schemaHash: String = SchemaHash.CURRENT
) {
    fun build(
        state: GameState,
        perspectivePlayerId: EntityId,
        legalActions: List<LegalAction>,
        revealAll: Boolean = false
    ): ObservationResult {
        val projected = state.projectedState

        val players = state.turnOrder.map { buildPlayerView(state, it, perspectivePlayerId) }

        val zones = buildZones(state, perspectivePlayerId, revealAll)

        val stack = state.stack.map { entityId -> buildStackItem(state, entityId) }
        val combat = buildCombatView(state)

        val agentToAct = state.pendingDecision?.playerId ?: state.priorityPlayerId
        val canRespond = revealAll || agentToAct == perspectivePlayerId

        val pendingDecisionAndRegistry = state.pendingDecision
            ?.let { buildPendingDecision(it, canRespond) }
        val pendingDecisionView = pendingDecisionAndRegistry?.first
        val decisionRegistry = pendingDecisionAndRegistry?.second ?: ActionRegistry.EMPTY

        // Build legal-action views and their registry. When mid-decision the
        // engine's `legalActions` is empty — we use the decision options instead.
        val legalActionViews: List<LegalActionView>
        val actionRegistry: ActionRegistry
        if (!canRespond) {
            legalActionViews = emptyList()
            actionRegistry = ActionRegistry.EMPTY
        } else if (state.pendingDecision != null) {
            val responses = decisionRegistry.decisionResponses.map { it.second }
            legalActionViews = buildDecisionOptionViews(state.pendingDecision!!, responses)
            actionRegistry = decisionRegistry
        } else {
            legalActionViews = legalActions.mapIndexed { idx, la -> legalActionToView(idx, la) }
            actionRegistry = ActionRegistry.ofLegalActions(legalActions)
        }

        val obs = TrainingObservation(
            schemaHash = schemaHash,
            perspectivePlayerId = perspectivePlayerId,
            agentToAct = agentToAct,
            turnNumber = state.turnNumber,
            phase = state.phase,
            step = state.step,
            activePlayerId = state.activePlayerId,
            priorityPlayerId = state.priorityPlayerId,
            players = players,
            zones = zones,
            stack = stack,
            combat = combat,
            pendingDecision = pendingDecisionView,
            legalActions = legalActionViews,
            terminated = state.gameOver,
            winnerId = state.winnerId,
            stateDigest = ""
        )
        val digested = obs.copy(stateDigest = StateDigest.compute(obs))
        return ObservationResult(digested, actionRegistry)
    }

    // =========================================================================
    // Players
    // =========================================================================

    private fun buildPlayerView(
        state: GameState,
        playerId: EntityId,
        perspectivePlayerId: EntityId
    ): PlayerView {
        val container = state.getEntity(playerId)
        val playerComp = container?.get<PlayerComponent>()
        val life = container?.get<LifeTotalComponent>()?.life ?: 0
        val manaPool = container?.get<ManaPoolComponent>()
        val hasLost = container?.get<PlayerLostComponent>() != null

        return PlayerView(
            id = playerId,
            name = playerComp?.name ?: playerId.value,
            lifeTotal = life,
            handSize = state.getHand(playerId).size,
            librarySize = state.getLibrary(playerId).size,
            graveyardSize = state.getGraveyard(playerId).size,
            exileSize = state.getExile(playerId).size,
            manaPool = manaPool?.let {
                ManaPoolView(
                    white = it.white,
                    blue = it.blue,
                    black = it.black,
                    red = it.red,
                    green = it.green,
                    colorless = it.colorless
                )
            } ?: ManaPoolView(),
            speed = container?.get<PlayerSpeedComponent>()?.speed ?: 0,
            isPerspective = playerId == perspectivePlayerId,
            isActive = playerId == state.activePlayerId,
            hasPriority = playerId == state.priorityPlayerId,
            hasLost = hasLost
        )
    }

    private fun buildCombatView(state: GameState): CombatView? {
        val battlefield = state.turnOrder.flatMap(state::getBattlefield)
        val attackers = battlefield.mapNotNull { entityId ->
            val attacking = state.getEntity(entityId)?.get<AttackingComponent>() ?: return@mapNotNull null
            AttackerView(
                attackerId = entityId,
                defenderId = attacking.defenderId,
                blockerIds = state.getEntity(entityId)?.get<BlockedComponent>()?.blockerIds ?: emptyList()
            )
        }
        val blockers = battlefield.mapNotNull { entityId ->
            val blocking = state.getEntity(entityId)?.get<BlockingComponent>() ?: return@mapNotNull null
            BlockerView(entityId, blocking.blockedAttackerIds)
        }
        if (attackers.isEmpty() && blockers.isEmpty()) return null
        val attackingPlayerId = attackers.firstOrNull()?.attackerId
            ?.let { state.projectedState.getController(it) }
        return CombatView(attackingPlayerId, attackers, blockers)
    }

    // =========================================================================
    // Zones
    // =========================================================================

    private fun buildZones(
        state: GameState,
        perspectivePlayerId: EntityId,
        revealAll: Boolean
    ): List<ZoneView> {
        // Emit a view for every (player, zone) in turn order so trainers see a
        // consistent shape regardless of whether a zone happens to be empty.
        val perPlayerZones = listOf(
            Zone.HAND, Zone.LIBRARY, Zone.GRAVEYARD, Zone.EXILE, Zone.BATTLEFIELD
        )
        val views = mutableListOf<ZoneView>()
        for (playerId in state.turnOrder) {
            for (zone in perPlayerZones) {
                val key = ZoneKey(playerId, zone)
                val ids = state.getZone(key)
                val hidden = !revealAll && isHiddenFrom(zone, playerId, perspectivePlayerId)
                val cards = if (hidden) emptyList() else ids.map { buildEntityFeatures(state, it, zone) }
                views += ZoneView(
                    ownerId = playerId,
                    zoneType = zone,
                    hidden = hidden,
                    size = ids.size,
                    cards = cards
                )
            }
        }
        return views
    }

    private fun isHiddenFrom(zone: Zone, owner: EntityId, perspective: EntityId): Boolean = when (zone) {
        Zone.LIBRARY -> true
        Zone.HAND -> owner != perspective
        else -> false
    }

    // =========================================================================
    // Entities
    // =========================================================================

    private fun buildEntityFeatures(
        state: GameState,
        entityId: EntityId,
        zone: Zone
    ): EntityFeatures {
        val container = state.getEntity(entityId) ?: ComponentContainer.EMPTY
        val card = container.get<CardComponent>()
        val projected = state.projectedState
        val pv = projected.getProjectedValues(entityId)

        val onBattlefield = zone == Zone.BATTLEFIELD

        val types: Set<String> = when {
            pv != null -> pv.types.toSet()
            card != null -> card.typeLine.cardTypes.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }
        val subtypes: Set<String> = when {
            pv != null -> pv.subtypes.toSet()
            card != null -> card.typeLine.subtypes.mapTo(mutableSetOf()) { it.value }
            else -> emptySet()
        }
        val colors: Set<String> = when {
            pv != null -> pv.colors.toSet()
            card != null -> card.colors.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }
        val keywords: Set<String> = when {
            pv != null -> pv.keywords.toSet()
            card != null -> card.baseKeywords.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }

        return EntityFeatures(
            entityId = entityId,
            cardDefinitionId = card?.cardDefinitionId,
            name = card?.name ?: "",
            zone = zone,
            ownerId = container.get<OwnerComponent>()?.playerId ?: card?.ownerId,
            controllerId = if (onBattlefield) projected.getController(entityId) else null,
            types = types,
            subtypes = subtypes,
            colors = colors,
            keywords = keywords,
            manaCost = card?.manaCost?.toString() ?: "",
            manaValue = card?.manaValue ?: 0,
            oracleText = card?.oracleText ?: "",
            power = if (onBattlefield) projected.getPower(entityId) else null,
            toughness = if (onBattlefield) projected.getToughness(entityId) else null,
            tapped = onBattlefield && container.get<TappedComponent>() != null,
            // Only creatures meaningfully suffer summoning sickness — the engine attaches the
            // marker to every entering permanent so Vehicles / animated lands inherit the
            // restriction when they become creatures, but for non-creatures the marker is a
            // no-op (all {T}/attack gates are creature-conditional). Reporting it on a freshly
            // played Mountain would mislead the agent into thinking it can't tap for mana.
            summoningSick = onBattlefield
                && container.get<SummoningSicknessComponent>() != null
                && projected.isCreature(entityId),
            faceDown = container.get<FaceDownComponent>() != null,
            damageMarked = container.get<DamageComponent>()?.amount ?: 0,
            counters = container.get<CountersComponent>()?.counters
                ?.mapKeys { it.key.name } ?: emptyMap(),
            attachedTo = container.get<AttachedToComponent>()?.targetId,
            attachments = container.get<AttachmentsComponent>()?.attachedIds ?: emptyList()
        )
    }

    // =========================================================================
    // Stack
    // =========================================================================

    private fun buildStackItem(state: GameState, entityId: EntityId): StackItemView {
        val container = state.getEntity(entityId)
        val card = container?.get<CardComponent>()
        val spell = container?.get<SpellOnStackComponent>()
        val triggered = container?.get<TriggeredAbilityOnStackComponent>()
        val activated = container?.get<ActivatedAbilityOnStackComponent>()
        val kind = when {
            spell != null -> StackItemKind.SPELL
            triggered != null -> StackItemKind.TRIGGERED_ABILITY
            activated != null -> StackItemKind.ACTIVATED_ABILITY
            else -> StackItemKind.OTHER
        }
        val targets = container?.get<TargetsComponent>()?.targets.orEmpty().map { target ->
            when (target) {
                is ChosenTarget.Player -> target.playerId
                is ChosenTarget.Permanent -> target.entityId
                is ChosenTarget.Card -> target.cardId
                is ChosenTarget.Spell -> target.spellEntityId
            }
        }
        return StackItemView(
            entityId = entityId,
            controllerId = spell?.casterId ?: triggered?.controllerId ?: activated?.controllerId,
            name = card?.name ?: triggered?.sourceName ?: activated?.sourceName ?: "",
            kind = kind,
            oracleText = card?.oracleText ?: triggered?.description ?: "",
            targets = targets,
        )
    }

    // =========================================================================
    // Legal actions
    // =========================================================================

    private fun legalActionToView(actionId: Int, la: LegalAction): LegalActionView {
        return LegalActionView(
            actionId = actionId,
            kind = la.actionType,
            description = la.description,
            affordable = la.affordable,
            sourceEntityId = la.action.sourceEntityIdOrNull(),
            targetEntityIds = la.validTargets ?: emptyList(),
            manaCost = la.manaCostString,
            hasXCost = la.hasXCost,
            maxAffordableX = la.maxAffordableX,
            minTargets = la.minTargets,
            maxTargets = la.targetCount,
            requiresDamageDistribution = la.requiresDamageDistribution,
            isManaAbility = la.isManaAbility,
            // Combat candidates. The enumerator offers one DeclareAttackers / DeclareBlockers action
            // carrying an empty map, so without these the caller has the action but no way to know
            // what it could declare — and `ActionParams` has nothing to be built from. The two
            // blocker constraints ride along for the same reason: a declaration that exceeds a
            // blocker's limit or skips a required block is rejected, and a caller that can't see
            // them can only discover that as a 400 it had no way to predict.
            validAttackers = la.validAttackers.orEmpty(),
            mandatoryAttackers = la.mandatoryAttackers.orEmpty(),
            validAttackTargets = la.validAttackTargets.orEmpty(),
            validBlockers = la.validBlockers.orEmpty(),
            blockerMaxBlockCounts = la.blockerMaxBlockCounts.orEmpty(),
            mandatoryBlockerAssignments = la.mandatoryBlockerAssignments.orEmpty(),
            isDecisionOption = false
        )
    }

    // =========================================================================
    // Pending decisions
    // =========================================================================

    /**
     * For simple decisions (yes/no, choose-number, choose-mode, choose-color,
     * choose-option, single-select cards) we enumerate every concrete response
     * into the unified action-ID space. For complex decisions (targets,
     * distribute, order, split, search, reorder, damage, mana sources) we emit
     * [PendingDecisionView.requiresStructuredResponse] = true; the trainer
     * submits a `DecisionResponse` via a dedicated endpoint (Phase 3).
     */
    private fun buildPendingDecision(
        decision: PendingDecision,
        canRespond: Boolean,
    ): Pair<PendingDecisionView, ActionRegistry> {
        val baseShape = DecisionShape()

        val built = when (decision) {
            is YesNoDecision -> {
                val responses = listOf(
                    YesNoResponse(decision.id, true),
                    YesNoResponse(decision.id, false)
                )
                val view = baseView(decision, PendingDecisionKind.YES_NO, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is BatchYesNoDecision -> {
                // Folded to two whole-run actions (yes-to-all / no-to-all); peel-off isn't an
                // observation action. Reuses the YES_NO encoding kind.
                val responses = listOf(
                    BatchYesNoResponse(decision.id, choice = true, applyToAll = true),
                    BatchYesNoResponse(decision.id, choice = false, applyToAll = true)
                )
                val view = baseView(decision, PendingDecisionKind.BATCH_YES_NO, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseNumberDecision -> {
                val responses = (decision.minValue..decision.maxValue).map {
                    NumberChosenResponse(decision.id, it)
                }
                val shape = DecisionShape(
                    numericMin = decision.minValue,
                    numericMax = decision.maxValue
                )
                val view = baseView(decision, PendingDecisionKind.CHOOSE_NUMBER, shape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseModeDecision -> {
                // Folds only single-mode choices into IDs; multi-mode uses structured response.
                if (decision.minModes == 1 && decision.maxModes == 1) {
                    val responses = decision.modes
                        .filter { it.available }
                        .map { ModesChosenResponse(decision.id, listOf(it.index)) }
                    val shape = DecisionShape(
                        minSelections = decision.minModes,
                        maxSelections = decision.maxModes
                    )
                    val view = baseView(decision, PendingDecisionKind.CHOOSE_MODE, shape, structured = false)
                    view to ActionRegistry.ofDecisionResponses(responses)
                } else {
                    val shape = DecisionShape(
                        minSelections = decision.minModes,
                        maxSelections = decision.maxModes
                    )
                    baseView(decision, PendingDecisionKind.CHOOSE_MODE, shape, structured = true) to
                        ActionRegistry.EMPTY
                }
            }
            is ChooseColorDecision -> {
                val responses = decision.availableColors.map {
                    ColorChosenResponse(decision.id, it)
                }
                val shape = DecisionShape(availableColors = decision.availableColors)
                val view = baseView(decision, PendingDecisionKind.CHOOSE_COLOR, shape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseOptionDecision -> {
                val responses = decision.options.indices.map {
                    OptionChosenResponse(decision.id, it)
                }
                val view = baseView(decision, PendingDecisionKind.CHOOSE_OPTION, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseReplacementDecision ->
                // Two-index (from, to) pick — emitted as a structured decision (trainer submits the
                // DecisionResponse directly rather than via the flat action-ID space).
                baseView(decision, PendingDecisionKind.CHOOSE_REPLACEMENT, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SelectCardsDecision -> {
                if (decision.minSelections == 1 && decision.maxSelections == 1 && !decision.ordered) {
                    val responses = decision.options.map {
                        CardsSelectedResponse(decision.id, listOf(it))
                    }
                    val shape = DecisionShape(
                        minSelections = decision.minSelections,
                        maxSelections = decision.maxSelections
                    )
                    val view = baseView(decision, PendingDecisionKind.SELECT_CARDS, shape, structured = false)
                    view to ActionRegistry.ofDecisionResponses(responses)
                } else {
                    val shape = DecisionShape(
                        minSelections = decision.minSelections,
                        maxSelections = decision.maxSelections
                    )
                    baseView(decision, PendingDecisionKind.SELECT_CARDS, shape, structured = true) to
                        ActionRegistry.EMPTY
                }
            }
            is BudgetModalDecision -> {
                val shape = DecisionShape(budget = decision.budget)
                baseView(decision, PendingDecisionKind.BUDGET_MODAL, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is ChooseTargetsDecision ->
                baseView(decision, PendingDecisionKind.CHOOSE_TARGETS, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is DistributeDecision -> {
                val shape = DecisionShape(totalToDistribute = decision.totalAmount)
                baseView(decision, PendingDecisionKind.DISTRIBUTE, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is OrderObjectsDecision ->
                baseView(decision, PendingDecisionKind.ORDER_OBJECTS, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SplitPilesDecision ->
                baseView(decision, PendingDecisionKind.SPLIT_PILES, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SearchLibraryDecision -> {
                val shape = DecisionShape(
                    minSelections = decision.minSelections,
                    maxSelections = decision.maxSelections
                )
                baseView(decision, PendingDecisionKind.SEARCH_LIBRARY, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is ReorderLibraryDecision ->
                baseView(decision, PendingDecisionKind.REORDER_LIBRARY, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is AssignDamageDecision ->
                baseView(decision, PendingDecisionKind.ASSIGN_DAMAGE, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is CombatResolutionDecision ->
                baseView(decision, PendingDecisionKind.COMBAT_RESOLUTION, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SelectManaSourcesDecision ->
                baseView(decision, PendingDecisionKind.SELECT_MANA_SOURCES, baseShape, structured = true) to
                    ActionRegistry.EMPTY
        }
        return if (canRespond) {
            built
        } else {
            built.first.copy(canRespond = false, choiceSpec = null) to ActionRegistry.EMPTY
        }
    }

    private fun baseView(
        decision: PendingDecision,
        kind: PendingDecisionKind,
        shape: DecisionShape,
        structured: Boolean
    ): PendingDecisionView {
        val ctx = decision.context
        return PendingDecisionView(
            decisionId = decision.id,
            kind = kind,
            playerId = decision.playerId,
            prompt = decision.prompt,
            sourceEntityId = ctx.sourceId,
            sourceName = ctx.sourceName,
            triggeringEntityId = ctx.triggeringEntityId,
            effectHint = ctx.effectHint,
            phase = ctx.phase,
            subjectEntityId = ctx.subjectEntityId,
            canRespond = true,
            requiresStructuredResponse = structured,
            shape = shape,
            choiceSpec = buildChoiceSpec(decision),
        )
    }

    private fun buildChoiceSpec(decision: PendingDecision): DecisionChoiceSpec = when (decision) {
        is ChooseTargetsDecision -> TargetsChoiceSpec(
            requirements = decision.targetRequirements,
            legalTargets = decision.legalTargets,
            canCancel = decision.canCancel,
        )
        is SelectCardsDecision -> CardsChoiceSpec(
            options = decision.options,
            minSelections = decision.minSelections,
            maxSelections = decision.maxSelections,
            ordered = decision.ordered,
            cardInfo = decision.cardInfo,
            useTargetingUI = decision.useTargetingUI,
            selectedLabel = decision.selectedLabel,
            remainderLabel = decision.remainderLabel,
            nonSelectableOptions = decision.nonSelectableOptions,
            onePerCardType = decision.onePerCardType,
            onePerColor = decision.onePerColor,
            availableColors = decision.availableColors,
            onePerCardName = decision.onePerCardName,
            onePerBasicLandType = decision.onePerBasicLandType,
            onePerPower = decision.onePerPower,
            maxTotalManaValue = decision.maxTotalManaValue,
            minTotalManaValue = decision.minTotalManaValue,
            maxTotalPower = decision.maxTotalPower,
            conditionalMinimums = decision.conditionalMinimums,
        )
        is YesNoDecision -> YesNoChoiceSpec(decision.yesText, decision.noText, decision.hint)
        is BatchYesNoDecision -> BatchYesNoChoiceSpec(
            decision.count, decision.yesText, decision.noText
        )
        is ChooseModeDecision -> ModesChoiceSpec(
            decision.modes, decision.minModes, decision.maxModes
        )
        is ChooseColorDecision -> ColorsChoiceSpec(decision.availableColors.sortedBy { it.name })
        is ChooseNumberDecision -> NumberChoiceSpec(decision.minValue, decision.maxValue)
        is DistributeDecision -> DistributionChoiceSpec(
            decision.totalAmount,
            decision.targets,
            decision.minPerTarget,
            decision.maxPerTarget,
            decision.allowPartial,
        )
        is OrderObjectsDecision -> OrderChoiceSpec(decision.objects, decision.cardInfo)
        is SplitPilesDecision -> PilesChoiceSpec(
            decision.cards, decision.numberOfPiles, decision.pileLabels, decision.cardInfo
        )
        is ChooseOptionDecision -> OptionsChoiceSpec(
            decision.options,
            decision.defaultSearch,
            decision.optionCardIds,
            decision.optionMetadata,
            decision.canCancel,
        )
        is ChooseReplacementDecision -> ReplacementChoiceSpec(
            decision.fromOptions,
            decision.toOptions,
            decision.fromMetadata,
            decision.toMetadata,
            decision.allowedToByFrom,
            decision.defaultFromIndex,
        )
        is SearchLibraryDecision -> LibrarySearchChoiceSpec(
            decision.options,
            decision.minSelections,
            decision.maxSelections,
            decision.cards,
            decision.filterDescription,
        )
        is ReorderLibraryDecision -> LibraryReorderChoiceSpec(decision.cards, decision.cardInfo)
        is AssignDamageDecision -> DamageAssignmentChoiceSpec(
            decision.attackerId,
            decision.availablePower,
            decision.orderedTargets,
            decision.defenderId,
            decision.minimumAssignments,
            decision.defaultAssignments,
            decision.hasTrample,
            decision.hasDeathtouch,
        )
        is CombatResolutionDecision -> CombatResolutionChoiceSpec(
            decision.firstStrike,
            decision.attackers,
            decision.blockers,
            decision.defenders,
            decision.edges,
            decision.coChooserId,
        )
        is SelectManaSourcesDecision -> ManaSourcesChoiceSpec(
            decision.availableSources.map { source ->
                ManaSourceChoice(
                    source.entityId,
                    source.name,
                    source.producesColors.sortedBy { it.name },
                    source.producesColorless,
                    source.requiresSacrifice,
                    source.requiresTappingAnotherPermanent,
                )
            },
            decision.requiredCost,
            decision.autoPaySuggestion,
            decision.canDecline,
            decision.waterbendPermanents,
        )
        is BudgetModalDecision -> BudgetModesChoiceSpec(decision.budget, decision.modes)
    }

    private fun buildDecisionOptionViews(
        decision: PendingDecision,
        responses: List<DecisionResponse>
    ): List<LegalActionView> {
        return responses.mapIndexed { idx, response ->
            LegalActionView(
                actionId = idx,
                kind = "DECISION",
                description = describeResponse(decision, response),
                affordable = true,
                isDecisionOption = true
            )
        }
    }

    private fun describeResponse(decision: PendingDecision, response: DecisionResponse): String = when (response) {
        is YesNoResponse -> if (response.choice) (decision as? YesNoDecision)?.yesText ?: "Yes" else
            (decision as? YesNoDecision)?.noText ?: "No"
        is NumberChosenResponse -> response.number.toString()
        is ModesChosenResponse -> response.selectedModes.joinToString(",") { idx ->
            (decision as? ChooseModeDecision)?.modes?.getOrNull(idx)?.text ?: idx.toString()
        }
        is ColorChosenResponse -> response.color.name
        is OptionChosenResponse ->
            (decision as? ChooseOptionDecision)?.options?.getOrNull(response.optionIndex)
                ?: response.optionIndex.toString()
        is CardsSelectedResponse -> response.selectedCards.joinToString(",") { it.value }
        else -> response.toString()
    }
}

/**
 * Build output pairing an [Observation] with its server-side [ActionRegistry].
 * The observation is safe to serialize; the registry must be retained on the
 * server so it can resolve incoming action IDs. Both game envs ([TrainingObservation])
 * and deckbuild envs ([DeckbuildObservation]) produce this shape.
 */
data class ObservationResult(
    val observation: Observation,
    val registry: ActionRegistry
)
