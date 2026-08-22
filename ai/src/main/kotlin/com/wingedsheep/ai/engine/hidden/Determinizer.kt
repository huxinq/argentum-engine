package com.wingedsheep.ai.engine.hidden

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CantBeCopiedComponent
import com.wingedsheep.engine.state.components.identity.CantBeCounteredComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.HasMorphAbilityComponent
import com.wingedsheep.engine.state.components.identity.HexproofFromComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.ProtectionComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.identity.SelfZoneRedirectComponent
import com.wingedsheep.engine.state.components.identity.ToxicComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng

/**
 * Result of the strict known-deck sampler used by information-safe search.
 *
 * Unlike [sample], this API never leaves an inaccessible identity inherited from the referee's
 * authoritative state. If a hidden object cannot be rewritten safely, sampling fails closed and
 * the caller must not search that root.
 */
sealed interface KnownDeckSampleResult {
    data class Success(
        val state: GameState,
        /** The externally-owned belief stream after all sampling performed for this world. */
        val nextBeliefRng: GameRng,
        val rewrittenCardCount: Int,
    ) : KnownDeckSampleResult

    data class Unsupported(val reasons: List<KnownDeckSampleFailure>) : KnownDeckSampleResult {
        init {
            require(reasons.isNotEmpty()) { "Unsupported sampling must explain why it failed" }
        }
    }
}

/** A diagnostic reason the strict sampler refused to construct a world. */
sealed interface KnownDeckSampleFailure {
    data class MissingDecklist(val playerId: EntityId) : KnownDeckSampleFailure
    data class UnknownCard(val playerId: EntityId, val cardName: String) : KnownDeckSampleFailure
    data class InsufficientDeckRemainder(
        val playerId: EntityId,
        val hiddenSlots: Int,
        val availableCards: Int,
    ) : KnownDeckSampleFailure

    data class HiddenCardReferencedByStack(val playerId: EntityId, val cardId: EntityId) :
        KnownDeckSampleFailure

    data class HiddenCardCarriesRuntimeState(val playerId: EntityId, val cardId: EntityId) :
        KnownDeckSampleFailure

    data class InFlightContinuation(val frameCount: Int) : KnownDeckSampleFailure
}

/**
 * Samples a complete state consistent with what [viewerId] is allowed to know.
 *
 * Entities and zone membership never change. Only hidden card identities and opponent library
 * ordering are sampled, which keeps continuations, targets and pending decisions structurally
 * valid. Cards carrying runtime components are pinned because changing their definition could make
 * those components nonsensical.
 */
class Determinizer(
    private val cardRegistry: CardRegistry,
    private val visibility: Visibility = Visibility(cardRegistry),
) {
    /**
     * Construct one complete world from exact deck knowledge without retaining referee truth.
     *
     * The caller owns [beliefRng]. Game randomness in [state] is neither read nor advanced. Every
     * inaccessible hand identity and every unknown library identity/order is replaced from
     * `decklists - visible cards`. The viewer's own library is included because its future order is
     * hidden too.
     */
    fun sampleKnownDeckWorld(
        state: GameState,
        viewerId: EntityId,
        decklists: Map<EntityId, Map<String, Int>>,
        beliefRng: GameRng,
    ): KnownDeckSampleResult {
        val failures = mutableListOf<KnownDeckSampleFailure>()
        if (state.continuationStack.isNotEmpty()) {
            failures += KnownDeckSampleFailure.InFlightContinuation(state.continuationStack.size)
        }

        val hiddenByPlayer = linkedMapOf<EntityId, List<EntityId>>()
        for (playerId in state.turnOrder) {
            if (playerId !in decklists) {
                failures += KnownDeckSampleFailure.MissingDecklist(playerId)
                continue
            }
            val hidden = inaccessibleCards(state, playerId, viewerId)
            hiddenByPlayer[playerId] = hidden

            val stackReferences = stackReferences(state)
            hidden.filterTo(mutableSetOf()) { it in stackReferences }.forEach { id ->
                failures += KnownDeckSampleFailure.HiddenCardReferencedByStack(playerId, id)
            }
            hidden.filterNot { isSafeToRewrite(state.getEntity(it)) }.forEach { id ->
                failures += KnownDeckSampleFailure.HiddenCardCarriesRuntimeState(playerId, id)
            }
        }
        if (failures.isNotEmpty()) return KnownDeckSampleResult.Unsupported(failures.distinct())

        var sampled = state
        var currentRng = beliefRng
        var rewritten = 0
        for ((playerId, hidden) in hiddenByPlayer) {
            if (hidden.isEmpty()) continue
            val decklist = decklists.getValue(playerId)
            val remaining = remainingKnownDeck(state, playerId, hidden, decklist, failures)
            if (failures.isNotEmpty()) continue
            if (remaining.size < hidden.size) {
                failures += KnownDeckSampleFailure.InsufficientDeckRemainder(
                    playerId = playerId,
                    hiddenSlots = hidden.size,
                    availableCards = remaining.size,
                )
                continue
            }

            val (shuffledDefinitions, afterDefinitions) = currentRng.shuffle(remaining)
            currentRng = afterDefinitions
            for ((entityId, cardDef) in hidden.zip(shuffledDefinitions.take(hidden.size))) {
                val old = sampled.getEntity(entityId) ?: continue
                val ownerId = old.get<OwnerComponent>()?.playerId ?: playerId
                var replacement = CardEntityFactory.create(cardDef, ownerId)
                old.get<RevealedToComponent>()?.let { replacement = replacement.with(it) }
                sampled = sampled.withEntity(entityId, replacement)
                rewritten++
            }

            val libraryKey = ZoneKey(playerId, Zone.LIBRARY)
            val library = sampled.getZone(libraryKey)
            val hiddenLibraryIds = hidden.filterTo(mutableSetOf()) { it in library }
            val (shuffledIds, afterLibrary) = currentRng.shuffle(library.filter { it in hiddenLibraryIds })
            currentRng = afterLibrary
            val iterator = shuffledIds.iterator()
            sampled = sampled.copy(
                zones = sampled.zones + (libraryKey to library.map { id ->
                    if (id in hiddenLibraryIds) iterator.next() else id
                })
            )
        }

        return if (failures.isEmpty()) {
            KnownDeckSampleResult.Success(sampled, currentRng, rewritten)
        } else {
            KnownDeckSampleResult.Unsupported(failures.distinct())
        }
    }

    /** Pure per-position entry point used by the Strategist before it simulates any candidate. */
    fun sampleForSearch(
        state: GameState,
        viewerId: EntityId,
        models: Map<EntityId, OpponentModel> = emptyMap(),
    ): GameState = sample(
        state,
        viewerId,
        models,
        GameRng.seeded(
            state.rng.state xor
                (state.turnNumber.toLong() shl 32) xor
                (state.step.ordinal.toLong() shl 16) xor
                viewerId.value.hashCode().toLong()
        ),
    )

    fun sample(
        state: GameState,
        viewerId: EntityId,
        model: OpponentModel,
        rng: GameRng,
    ): GameState = sample(state, viewerId, emptyMap(), rng, model)

    fun sample(
        state: GameState,
        viewerId: EntityId,
        models: Map<EntityId, OpponentModel>,
        rng: GameRng,
        fallback: OpponentModel = OpponentModel.IdentityPermutation,
    ): GameState {
        var sampled = state
        var currentRng = rng

        // Even the viewer's own library order is hidden. Teammate hands are visible, but teammate
        // libraries are not. Walk every player and let Visibility decide which cards are hidden.
        for (opponentId in state.turnOrder) {
            val hidden = hiddenCards(state, opponentId, viewerId)
            if (hidden.isEmpty()) continue

            val definitions = when (val model = models[opponentId] ?: fallback) {
                is OpponentModel.KnownDecklist -> fromKnownDecklist(state, opponentId, hidden, model, currentRng)
                    .also { currentRng = it.second }
                    .first
                OpponentModel.IdentityPermutation -> {
                    val existing = hidden.mapNotNull { id ->
                        state.getEntity(id)?.get<CardComponent>()?.let { cardRegistry.getCard(it.cardDefinitionId) }
                    }
                    currentRng.shuffle(existing).also { currentRng = it.second }.first
                }
            }

            if (definitions.size != hidden.size) continue
            for ((entityId, cardDef) in hidden.zip(definitions)) {
                val old = sampled.getEntity(entityId) ?: continue
                val ownerId = old.get<OwnerComponent>()?.playerId ?: opponentId
                var replacement = CardEntityFactory.create(cardDef, ownerId)
                old.get<RevealedToComponent>()?.let { replacement = replacement.with(it) }
                sampled = sampled.withEntity(entityId, replacement)
            }

            val libraryKey = ZoneKey(opponentId, Zone.LIBRARY)
            val library = sampled.getZone(libraryKey)
            val hiddenLibraryIds = hidden.filterTo(mutableSetOf()) { it in library }
            val (shuffledHidden, next) = currentRng.shuffle(library.filter { it in hiddenLibraryIds })
            currentRng = next
            val iterator = shuffledHidden.iterator()
            val shuffledLibrary = library.map { id ->
                if (id in hiddenLibraryIds) iterator.next() else id
            }
            sampled = sampled.copy(zones = sampled.zones + (libraryKey to shuffledLibrary))
        }
        return sampled
    }

    private fun hiddenCards(
        state: GameState,
        opponentId: EntityId,
        viewerId: EntityId,
    ): List<EntityId> {
        val candidates = inaccessibleCards(state, opponentId, viewerId)
        val visibleTop = state.getLibrary(opponentId).firstOrNull()?.takeIf {
            visibility.revealsTopOfLibraryPublicly(state, opponentId) ||
                (opponentId == viewerId && visibility.hasLookAtTopOfLibrary(state, viewerId))
        }
        val referencedByStack = stackReferences(state)
        return candidates.filter { id ->
            id != visibleTop &&
                id !in referencedByStack &&
                // Continuation frames carry entity references in several different shapes.
                // Quiet search roots normally have none; pinning while one exists is the safe
                // fallback until those shapes share a common reference visitor.
                state.continuationStack.isEmpty() &&
                !visibility.isCardRevealedTo(state, id, viewerId) &&
                isSafeToRewrite(state.getEntity(id))
        }
    }

    private fun inaccessibleCards(
        state: GameState,
        playerId: EntityId,
        viewerId: EntityId,
    ): List<EntityId> {
        val handKey = ZoneKey(playerId, Zone.HAND)
        val handHidden = !visibility.isZoneVisibleTo(state, handKey, viewerId)
        val candidates = buildList {
            addAll(state.getLibrary(playerId))
            if (handHidden) addAll(state.getHand(playerId))
        }
        val visibleTop = state.getLibrary(playerId).firstOrNull()?.takeIf {
            visibility.revealsTopOfLibraryPublicly(state, playerId) ||
                (playerId == viewerId && visibility.hasLookAtTopOfLibrary(state, viewerId))
        }
        return candidates.filter { id ->
            id != visibleTop && !visibility.isCardRevealedTo(state, id, viewerId)
        }
    }

    private fun stackReferences(state: GameState): Set<EntityId> =
        state.stack.flatMapTo(mutableSetOf()) { stackId ->
            state.getEntity(stackId)?.get<TargetsComponent>()?.targets.orEmpty().mapNotNull {
                when (it) {
                    is ChosenTarget.Card -> it.cardId
                    is ChosenTarget.Permanent -> it.entityId
                    is ChosenTarget.Spell -> it.spellEntityId
                    is ChosenTarget.Player -> null
                }
            }
        }

    /**
     * A normal hidden card has only definition-derived identity/ownership components. Anything
     * else may be referenced by an in-flight effect or carry state that a different definition
     * cannot legally inherit, so it is pinned.
     */
    private fun isSafeToRewrite(container: ComponentContainer?): Boolean {
        if (container == null) return false
        return container.all().all {
            it is CardComponent ||
                it is OwnerComponent ||
                it is ControllerComponent ||
                it is RevealedToComponent ||
                it is CantBeCounteredComponent ||
                it is CantBeCopiedComponent ||
                it is HasMorphAbilityComponent ||
                it is com.wingedsheep.engine.state.components.identity.HasDisguiseAbilityComponent ||
                it is ProtectionComponent ||
                it is SelfZoneRedirectComponent ||
                it is HexproofFromComponent ||
                it is ToxicComponent
        }
    }

    private fun fromKnownDecklist(
        state: GameState,
        opponentId: EntityId,
        hidden: List<EntityId>,
        model: OpponentModel.KnownDecklist,
        rng: GameRng,
    ): Pair<List<CardDefinition>, GameRng> {
        val remaining = model.cards.toMutableMap()
        val sampledIds = hidden.toSet()
        for ((id, container) in state.entities) {
            if (id in sampledIds || container.get<OwnerComponent>()?.playerId != opponentId) continue
            val name = container.get<CardComponent>()?.name ?: continue
            remaining.computeIfPresent(name) { _, n -> (n - 1).coerceAtLeast(0) }
        }
        val pool = remaining.flatMap { (name, copies) ->
            val definition = cardRegistry.getCard(name) ?: return@flatMap emptyList()
            List(copies) { definition }
        }
        if (pool.size < hidden.size) return emptyList<CardDefinition>() to rng
        val (shuffled, next) = rng.shuffle(pool)
        return shuffled.take(hidden.size) to next
    }

    private fun remainingKnownDeck(
        state: GameState,
        playerId: EntityId,
        hidden: List<EntityId>,
        decklist: Map<String, Int>,
        failures: MutableList<KnownDeckSampleFailure>,
    ): List<CardDefinition> {
        val remaining = decklist.toMutableMap()
        val sampledIds = hidden.toSet()
        for ((id, container) in state.entities) {
            if (id in sampledIds || container.get<OwnerComponent>()?.playerId != playerId) continue
            val name = container.get<CardComponent>()?.name ?: continue
            remaining.computeIfPresent(name) { _, count -> (count - 1).coerceAtLeast(0) }
        }
        return remaining.flatMap { (name, copies) ->
            val definition = cardRegistry.getCard(name)
            if (definition == null) {
                failures += KnownDeckSampleFailure.UnknownCard(playerId, name)
                emptyList()
            } else {
                List(copies) { definition }
            }
        }
    }
}
