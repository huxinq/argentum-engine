package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ConditionalSelectionMinimum
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The payload an agent receives from any gym environment after `observe` / `step`.
 *
 * A gym env is no longer only a game of Magic — deckbuilding is its own env type
 * ([DeckbuildObservation]) — so the wire observation is a discriminated union. The
 * `type` field tells a client which variant it holds: `"Game"` for an in-progress
 * match, `"Deckbuild"` for a sealed-pool build. The fields hoisted here are the ones
 * every env exposes so a generic driver loop (read `legalActions`, pick one, `step`,
 * stop on `terminated`) works without knowing the variant up front.
 */
@Serializable
sealed interface Observation {
    /** Sha256 of the canonical schema — clients compare to abort on drift. */
    val schemaHash: String

    /** The player/agent who must act next, or null when [terminated]. */
    val agentToAct: EntityId?

    /** Non-null only for in-game complex decisions; always null for deckbuild. */
    val pendingDecision: PendingDecisionView?

    /** Every action available to [agentToAct] this step. Action IDs are per-step. */
    val legalActions: List<LegalActionView>

    /** True once the env reached a terminal state (game over, or deck finalized). */
    val terminated: Boolean

    /** Deterministic hash of the observable state, for MCTS transposition tables. */
    val stateDigest: String
}

/**
 * Root payload for a **game** env, sent to a training agent after every `reset()` / `step()`.
 *
 * Designed for RL consumers (neural policies, MCTS) — not for human display.
 * The schema is stable across card sets; new mechanics appear as strings in
 * [EntityFeatures.types] / [EntityFeatures.subtypes] / [EntityFeatures.keywords]
 * rather than as new fields.
 *
 * Action IDs in [legalActions] are **per-step** — they are regenerated every
 * time the environment advances and must not be cached across steps.
 */
@Serializable
@SerialName("Game")
data class TrainingObservation(
    /** Sha256 of the canonical schema. Python clients compare this to abort on drift. */
    override val schemaHash: String,

    /** The player whose information-set this observation represents. */
    val perspectivePlayerId: EntityId,

    /** The player who needs to act next, or null if the game is over. */
    override val agentToAct: EntityId?,

    val turnNumber: Int,
    val phase: Phase,
    val step: Step,
    val activePlayerId: EntityId?,
    val priorityPlayerId: EntityId?,

    val players: List<PlayerView>,

    /**
     * Per-zone entity views. A `(ownerId, zoneType)` pair appears at most once.
     * Hidden zones (opponent hand, libraries) expose [ZoneView.hidden] = true
     * and [ZoneView.cards] is empty (only [ZoneView.size] is populated).
     */
    val zones: List<ZoneView>,

    /** Stack contents, ordered bottom → top (top of stack = last element). */
    val stack: List<StackItemView>,

    /** Non-null when the engine paused for a player decision. */
    override val pendingDecision: PendingDecisionView?,

    /** All actions available to [agentToAct]. Empty when the game is over. */
    override val legalActions: List<LegalActionView>,

    /** True if the game ended naturally. */
    override val terminated: Boolean,

    /** Set if [terminated] and there is a winner (null = draw or ongoing). */
    val winnerId: EntityId?,

    /**
     * Deterministic hash of the observable game state, intended for transposition
     * tables in MCTS. Two observations with the same digest describe the same
     * information-set from the same perspective.
     */
    override val stateDigest: String
) : Observation

/** Per-player summary. Counts reflect what [perspectivePlayerId] can see. */
@Serializable
data class PlayerView(
    val id: EntityId,
    val name: String,
    val lifeTotal: Int,
    val handSize: Int,
    val librarySize: Int,
    val graveyardSize: Int,
    val exileSize: Int,
    /** Mana currently floating in this player's mana pool (colorless bucket in `colorless`). */
    val manaPool: ManaPoolView,
    val isPerspective: Boolean,
    val isActive: Boolean,
    val hasPriority: Boolean,
    val hasLost: Boolean
)

@Serializable
data class ManaPoolView(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0
)

/**
 * A zone's contents from [TrainingObservation.perspectivePlayerId]'s point of view.
 *
 * When [hidden] is true (opponent's hand, any library), [cards] is empty and
 * only [size] is meaningful. This mirrors real-MTG information hiding.
 */
@Serializable
data class ZoneView(
    val ownerId: EntityId,
    val zoneType: Zone,
    val hidden: Boolean,
    val size: Int,
    val cards: List<EntityFeatures>
)

/**
 * Flat feature bundle for a card/permanent. Values come from the projected
 * state (post-Rule 613), not base components, so control-changing and
 * type-changing effects are reflected.
 *
 * Not every field is populated for every zone:
 * - On the battlefield: all fields relevant to a permanent are set.
 * - In the library/hand/graveyard/exile: the card's static properties are set;
 *   dynamic properties (tapped, damage, counters) default to their "not present" values.
 */
@Serializable
data class EntityFeatures(
    val entityId: EntityId,
    val cardDefinitionId: String?,
    val name: String,
    val zone: Zone,
    val ownerId: EntityId?,
    /** Projected controller (battlefield only; null elsewhere). */
    val controllerId: EntityId?,

    /** Projected card types as strings (e.g., "CREATURE", "ARTIFACT", "LEGENDARY"). */
    val types: Set<String>,
    /** Projected subtypes as strings (e.g., "GOBLIN", "WARRIOR"). */
    val subtypes: Set<String>,
    /** Projected colors (e.g., "RED", "WHITE"). */
    val colors: Set<String>,
    /** Projected keywords (e.g., "FLYING", "TRAMPLE"). */
    val keywords: Set<String>,

    /** Canonical mana cost string, e.g. "{1}{R}{R}". Empty for lands and tokens. */
    val manaCost: String,
    val manaValue: Int,

    /**
     * The card's printed rules text (oracle text), e.g.
     * `"Flying\nWhen Dawnhand Eulogist dies, draw a card."`.
     *
     * Reflects the base card definition — it is *not* rewritten by Rule
     * 613 text-changing effects, so a Copy-Enchantment-style scenario
     * will occasionally lie to a strict reader. For most cards it is
     * the single most informative field an agent can read, and without
     * it an NN has no way to know what a card actually does.
     */
    val oracleText: String = "",

    /** Projected power — null if not a creature (via projection). */
    val power: Int?,
    /** Projected toughness — null if not a creature. */
    val toughness: Int?,

    val tapped: Boolean = false,
    val summoningSick: Boolean = false,
    val faceDown: Boolean = false,
    val damageMarked: Int = 0,
    /** Counter type name → count. */
    val counters: Map<String, Int> = emptyMap(),
    /** Non-null if attached (aura/equipment) to another entity. */
    val attachedTo: EntityId? = null,
    val attachments: List<EntityId> = emptyList()
)

/** An item on the stack (spell or ability). */
@Serializable
data class StackItemView(
    val entityId: EntityId,
    val controllerId: EntityId?,
    val name: String,
    val kind: StackItemKind,
    /** Printed oracle text of the card backing this stack item — empty for stackless triggers. */
    val oracleText: String = "",
    val targets: List<EntityId> = emptyList()
)

@Serializable
enum class StackItemKind { SPELL, TRIGGERED_ABILITY, ACTIVATED_ABILITY, OTHER }

/**
 * Compact view of a single legal action. Trainers post back the [actionId]
 * to commit. The registry mapping `Int → engine action` lives on the server
 * and is regenerated every step.
 *
 * Decision options (when [TrainingObservation.pendingDecision] is set and
 * the decision is simple enough to fold in — YesNo, ChooseNumber, ChooseMode,
 * ChooseOption, ChooseColor, and single-select SelectCards) also appear as
 * [LegalActionView]s in the same list, distinguished by [kind] == "DECISION".
 */
@Serializable
data class LegalActionView(
    val actionId: Int,
    val kind: String,
    val description: String,
    val affordable: Boolean,
    val sourceEntityId: EntityId? = null,
    val targetEntityIds: List<EntityId> = emptyList(),
    val manaCost: String? = null,
    val hasXCost: Boolean = false,
    val maxAffordableX: Int? = null,
    val minTargets: Int = 0,
    val maxTargets: Int = 0,
    val requiresDamageDistribution: Boolean = false,
    val isManaAbility: Boolean = false,
    /**
     * Creatures that may be declared as attackers (`kind == "DeclareAttackers"`), empty otherwise.
     * Pair each with one of [validAttackTargets] in `ActionParams.attackers` when stepping; step it
     * with no params to attack with nobody.
     */
    val validAttackers: List<EntityId> = emptyList(),
    /** Attackers that *must* attack if able (CR 508.1d) — a declaration omitting one is rejected. */
    val mandatoryAttackers: List<EntityId> = emptyList(),
    /** Players, planeswalkers and battles this player may attack. */
    val validAttackTargets: List<EntityId> = emptyList(),
    /**
     * Creatures that may be declared as blockers (`kind == "DeclareBlockers"`), empty otherwise.
     * Map each to the attackers it blocks in `ActionParams.blockers`.
     */
    val validBlockers: List<EntityId> = emptyList(),
    /**
     * How many attackers each blocker may block at once — absent means the default one (CR 509.1a).
     * A declaration exceeding a blocker's limit is rejected, so a caller building
     * `ActionParams.blockers` has to respect it.
     */
    val blockerMaxBlockCounts: Map<EntityId, Int> = emptyMap(),
    /**
     * Blocks that *must* be made if able (CR 509.1c) — blocker id → the attackers it is required to
     * block. Like [mandatoryAttackers] on the attack side, a declaration that obeys fewer of these
     * than it could is illegal, so this is not advisory.
     */
    val mandatoryBlockerAssignments: Map<EntityId, List<EntityId>> = emptyMap(),
    /** True when this entry was generated from [PendingDecisionView], not a GameAction. */
    val isDecisionOption: Boolean = false
)

/**
 * Summary of the currently-paused decision. When present, [LegalActionView]s
 * with `isDecisionOption = true` are the concrete choices the player can post.
 *
 * For complex decisions (multi-target ChooseTargets, DistributeDecision,
 * OrderObjectsDecision, SplitPilesDecision, ReorderLibraryDecision) the folded
 * action-ID space is not expressive enough; [legalActions] will be empty and
 * the trainer must submit a structured `DecisionResponse` (exposed via a
 * separate endpoint in Phase 3).
 */
@Serializable
data class PendingDecisionView(
    val decisionId: String,
    val kind: PendingDecisionKind,
    val playerId: EntityId,
    val prompt: String,
    val sourceEntityId: EntityId? = null,
    val sourceName: String? = null,
    val triggeringEntityId: EntityId? = null,
    val effectHint: String? = null,
    val phase: DecisionPhase = DecisionPhase.RESOLUTION,
    val subjectEntityId: EntityId? = null,
    /** True only when this observation's perspective may submit the response. */
    val canRespond: Boolean = true,
    /** True when no LegalActionView options were generated; structured response required. */
    val requiresStructuredResponse: Boolean = false,
    /** Extra hints about the decision shape (min/max selections, numeric range, etc.). */
    val shape: DecisionShape = DecisionShape(),
    /**
     * Complete response-building contract for the chooser. Null for a non-chooser masked view.
     * This payload is never populated merely because a hidden zone exists; it is populated only
     * when the engine has explicitly granted the decision player permission to inspect it.
     */
    val choiceSpec: DecisionChoiceSpec? = null,
)

@Serializable
enum class PendingDecisionKind {
    CHOOSE_TARGETS,
    SELECT_CARDS,
    YES_NO,
    BATCH_YES_NO,
    CHOOSE_MODE,
    CHOOSE_COLOR,
    CHOOSE_NUMBER,
    DISTRIBUTE,
    ORDER_OBJECTS,
    SPLIT_PILES,
    CHOOSE_OPTION,
    CHOOSE_REPLACEMENT,
    SEARCH_LIBRARY,
    REORDER_LIBRARY,
    ASSIGN_DAMAGE,
    COMBAT_RESOLUTION,
    SELECT_MANA_SOURCES,
    BUDGET_MODAL
}

@Serializable
data class DecisionShape(
    val minSelections: Int = 0,
    val maxSelections: Int = 0,
    val numericMin: Int? = null,
    val numericMax: Int? = null,
    val availableColors: Set<Color> = emptySet(),
    val totalToDistribute: Int? = null,
    val budget: Int? = null
)

/** Typed, Gym-owned description of every payload a legal [DecisionResponse] may contain. */
@Serializable
sealed interface DecisionChoiceSpec

@Serializable
@SerialName("Targets")
data class TargetsChoiceSpec(
    val requirements: List<TargetRequirementInfo>,
    val legalTargets: Map<Int, List<EntityId>>,
    val canCancel: Boolean,
) : DecisionChoiceSpec

@Serializable
@SerialName("Cards")
data class CardsChoiceSpec(
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val ordered: Boolean,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
    val useTargetingUI: Boolean = false,
    val selectedLabel: String? = null,
    val remainderLabel: String? = null,
    val nonSelectableOptions: List<EntityId> = emptyList(),
    val onePerCardType: Boolean = false,
    val onePerColor: Boolean = false,
    val availableColors: List<String>? = null,
    val onePerCardName: Boolean = false,
    val onePerBasicLandType: Boolean = false,
    val onePerPower: Boolean = false,
    val maxTotalManaValue: Int? = null,
    val minTotalManaValue: Int? = null,
    val maxTotalPower: Int? = null,
    val conditionalMinimums: List<ConditionalSelectionMinimum> = emptyList(),
) : DecisionChoiceSpec

@Serializable
@SerialName("YesNo")
data class YesNoChoiceSpec(
    val yesText: String,
    val noText: String,
    val hint: String? = null,
) : DecisionChoiceSpec

@Serializable
@SerialName("BatchYesNo")
data class BatchYesNoChoiceSpec(
    val count: Int,
    val yesText: String,
    val noText: String,
) : DecisionChoiceSpec

@Serializable
@SerialName("Modes")
data class ModesChoiceSpec(
    val modes: List<ModeOption>,
    val minModes: Int,
    val maxModes: Int,
) : DecisionChoiceSpec

@Serializable
@SerialName("Colors")
data class ColorsChoiceSpec(val colors: List<Color>) : DecisionChoiceSpec

@Serializable
@SerialName("Number")
data class NumberChoiceSpec(val minValue: Int, val maxValue: Int) : DecisionChoiceSpec

@Serializable
@SerialName("Distribution")
data class DistributionChoiceSpec(
    val totalAmount: Int,
    val targets: List<EntityId>,
    val minPerTarget: Int,
    val maxPerTarget: Map<EntityId, Int>,
    val allowPartial: Boolean,
) : DecisionChoiceSpec

@Serializable
@SerialName("Order")
data class OrderChoiceSpec(
    val objects: List<EntityId>,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
) : DecisionChoiceSpec

@Serializable
@SerialName("Piles")
data class PilesChoiceSpec(
    val cards: List<EntityId>,
    val numberOfPiles: Int,
    val pileLabels: List<String>,
    val cardInfo: Map<EntityId, SearchCardInfo>? = null,
) : DecisionChoiceSpec

@Serializable
@SerialName("Options")
data class OptionsChoiceSpec(
    val options: List<String>,
    val defaultSearch: String? = null,
    val optionCardIds: Map<Int, List<EntityId>>? = null,
    val optionMetadata: List<OptionMetadata> = emptyList(),
    val canCancel: Boolean = false,
) : DecisionChoiceSpec

@Serializable
@SerialName("Replacement")
data class ReplacementChoiceSpec(
    val fromOptions: List<String>,
    val toOptions: List<String>,
    val fromMetadata: List<OptionMetadata>,
    val toMetadata: List<OptionMetadata>,
    val allowedToByFrom: List<List<Int>>,
    val defaultFromIndex: Int? = null,
) : DecisionChoiceSpec

@Serializable
@SerialName("LibrarySearch")
data class LibrarySearchChoiceSpec(
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val cards: Map<EntityId, SearchCardInfo>,
    val filterDescription: String,
) : DecisionChoiceSpec

@Serializable
@SerialName("LibraryReorder")
data class LibraryReorderChoiceSpec(
    val cards: List<EntityId>,
    val cardInfo: Map<EntityId, SearchCardInfo>,
) : DecisionChoiceSpec

@Serializable
@SerialName("DamageAssignment")
data class DamageAssignmentChoiceSpec(
    val attackerId: EntityId,
    val availablePower: Int,
    val orderedTargets: List<EntityId>,
    val defenderId: EntityId?,
    val minimumAssignments: Map<EntityId, Int>,
    val defaultAssignments: Map<EntityId, Int>,
    val hasTrample: Boolean,
    val hasDeathtouch: Boolean,
) : DecisionChoiceSpec

@Serializable
@SerialName("CombatResolution")
data class CombatResolutionChoiceSpec(
    val firstStrike: Boolean,
    val attackers: List<ResolutionAttacker>,
    val blockers: List<ResolutionBlocker>,
    val defenders: List<ResolutionDefender>,
    val edges: List<DamageEdge>,
    val coChooserId: EntityId? = null,
) : DecisionChoiceSpec

@Serializable
@SerialName("ManaSources")
data class ManaSourcesChoiceSpec(
    val availableSources: List<ManaSourceChoice>,
    val requiredCost: String,
    val autoPaySuggestion: List<EntityId>,
    val canDecline: Boolean,
    val waterbendPermanents: List<WaterbendPermanentChoice>,
) : DecisionChoiceSpec

/** Canonically ordered Gym projection of an engine mana-source candidate. */
@Serializable
data class ManaSourceChoice(
    val entityId: EntityId,
    val name: String,
    val producesColors: List<Color>,
    val producesColorless: Boolean,
    val requiresSacrifice: Boolean,
    val requiresTappingAnotherPermanent: Boolean,
)

@Serializable
@SerialName("BudgetModes")
data class BudgetModesChoiceSpec(
    val budget: Int,
    val modes: List<BudgetModeOption>,
) : DecisionChoiceSpec
