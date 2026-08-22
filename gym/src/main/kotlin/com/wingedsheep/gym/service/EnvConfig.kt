package com.wingedsheep.gym.service

import com.wingedsheep.gym.contract.ActionParams
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/**
 * Everything needed to spin up a new gym environment.
 *
 * Designed so a JSON payload can drive the HTTP layer in Phase 3 without
 * any translation.
 */
@Serializable
data class EnvConfig(
    val players: List<PlayerSpec>,

    /** Opening-hand size. Standard MTG = 7. */
    val startingHandSize: Int = 7,

    /**
     * Skip mulligan phase. Default `true` — training loops rarely care about
     * mulligans and the extra decision points slow rollouts down.
     */
    val skipMulligans: Boolean = true,

    /** MTGA-style hand smoothing (only useful for realistic play-feel runs). */
    val useHandSmoother: Boolean = false,

    /**
     * Which player goes first (0-indexed). `null` = random — which should be
     * the default for training diversity. Set explicitly only for reproducible
     * scenarios.
     */
    val startingPlayerIndex: Int? = null,

    /**
     * Player used by [ObservationPerspective.FIXED], and as the fallback when no player currently
     * acts (for example before initialisation). Acting-player mode retains its most recent actor at
     * terminal states before falling back here.
     */
    val perspectivePlayerIndex: Int = 0,

    /**
     * If `true`, opponent hand and libraries are revealed — debug only,
     * must never be enabled in production self-play.
     */
    val revealAll: Boolean = false,

    /** Deterministic game seed. Null asks the engine to choose fresh entropy. */
    val seed: Long? = null,

    /** How the observation perspective is selected after each transition. */
    val perspectiveMode: ObservationPerspective = ObservationPerspective.ACTING_PLAYER,
) {
    init {
        require(players.size >= 2) { "Need at least 2 players" }
        require(perspectivePlayerIndex in players.indices) {
            "perspectivePlayerIndex=$perspectivePlayerIndex out of range for ${players.size} players"
        }
    }
}

@Serializable
enum class ObservationPerspective {
    /** Observe the player with the pending decision or priority. Suitable for masked self-play. */
    ACTING_PLAYER,

    /** Always observe [EnvConfig.perspectivePlayerIndex]. Suitable for one-seat evaluation. */
    FIXED,
}

/**
 * Everything needed to spin up a new **deckbuild** env (`POST /envs/deckbuild`).
 *
 * Opens [boosterCount] boosters from [setCode] into a sealed pool, then hands the agent
 * an enumerated build interface (add / remove / finalize) until it commits a [targetSize]-card
 * deck. The finished list is exposed on the terminal observation for the caller to feed into a
 * game env via [DeckSpec.Explicit].
 */
@Serializable
data class DeckbuildConfig(
    /** Set to open boosters from (e.g. "BLB"). Must be sealed-supported in the booster generator. */
    val setCode: String,

    /** Number of boosters to open (pack size follows the set's booster strategy). Tournament sealed = 6. */
    val boosterCount: Int = 6,

    /** Minimum legal deck size; `FINALIZE` unlocks once the build reaches it. */
    val targetSize: Int = 40
) {
    init {
        require(boosterCount > 0) { "boosterCount must be positive" }
        require(targetSize > 0) { "targetSize must be positive" }
    }
}

/** A single player's identity + deck. */
@Serializable
data class PlayerSpec(
    val name: String,
    val deck: DeckSpec,
    val startingLife: Int = 20,
    val playerId: EntityId? = null
)

/** A single environment's `step()` input — batched into [com.wingedsheep.gym.service.MultiEnvService.stepBatch]. */
@Serializable
data class StepRequest(
    val envId: EnvId,
    val actionId: Int,
    /** Choices the action ID can't carry — attackers, blockers, targets, X. See [ActionParams]. */
    val params: ActionParams = ActionParams.EMPTY
)

/** Result of deck validation. Surfaced by [DeckResolver.validate]. */
@Serializable
data class DeckValidation(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
    val totalCards: Int = 0
)
