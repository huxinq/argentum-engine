package com.wingedsheep.gameserver.session

import com.wingedsheep.sdk.model.EntityId
import java.time.Instant

/**
 * A host-level policy incident, deliberately separate from Magic state and game outcomes.
 *
 * This is process-lifetime state. It is retained in the live [GameSession] and its lifecycle is
 * mirrored into the canonical replay as a non-action mutation, but it is not yet restored after a
 * server restart. In particular, no timeout or restart path may turn this incident into a winner.
 */
data class PolicyFaultIncident(
    val incidentId: String,
    val failingSeat: EntityId,
    val code: String,
    val diagnostic: String,
    val actionIndex: Int,
    val detectedAt: Instant,
    /** A retry failure is distinct, but it never loses the original incident lineage. */
    val parentIncidentId: String? = null,
    val recovery: PolicyFaultRecovery? = null,
    /** Keeps all ordinary input gated while the retained invocation is running. */
    val retryInProgress: Boolean = false,
)

enum class PolicyFaultRecovery {
    RETRY,
    TRANSFER_CONTROL,
    CONCEDE,
}

sealed interface PolicyFaultReport {
    data class Paused(val incident: PolicyFaultIncident) : PolicyFaultReport
    data class AlreadyPaused(val incident: PolicyFaultIncident) : PolicyFaultReport
    data object GameAlreadyOver : PolicyFaultReport
}

sealed interface PolicyFaultRetryStart {
    data class Started(val incident: PolicyFaultIncident) : PolicyFaultRetryStart
    data object NoActiveIncident : PolicyFaultRetryStart
    data object StaleIncident : PolicyFaultRetryStart
    data object AlreadyRecovering : PolicyFaultRetryStart
}

sealed interface PolicyFaultConcession {
    data class Conceded(val incident: PolicyFaultIncident) : PolicyFaultConcession
    data object NoActiveIncident : PolicyFaultConcession
    data object StaleIncident : PolicyFaultConcession
}
