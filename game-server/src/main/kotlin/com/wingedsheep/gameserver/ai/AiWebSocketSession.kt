package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.ResponsiblePolicyUnavailableException
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardRuling
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.StateDelta
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.model.EntityId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.security.Principal
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger(AiWebSocketSession::class.java)

/**
 * Authoritative host acknowledgement for a staged policy submission.
 *
 * A rejection carries the exact paused incident, so the actor can retain only the invocation
 * that is safe to replay.  [UNSAFE_HOST_FAILURE] deliberately consumes an invocation: its commit
 * point was not proven, so replaying it could duplicate a Magic action.
 */
sealed interface PolicySubmissionAck {
    data object ACCEPTED : PolicySubmissionAck
    data class REJECTED(val incidentId: String?) : PolicySubmissionAck
    data object UNSAFE_HOST_FAILURE : PolicySubmissionAck
}

/**
 * Virtual WebSocket session that intercepts outgoing messages and feeds them
 * to the AI controller. The AI's responses are submitted back via the provided
 * callback, asynchronously on a coroutine to avoid deadlocking on stateLock.
 */
class AiWebSocketSession(
    private val aiPlayerId: EntityId,
    private val controller: AiPlayerController,
    /**
     * Per-decision artificial delay so a human can follow along. Mutable + volatile so the
     * LLM-tournament pacing control can speed up / slow down a live AI-vs-AI game; it's re-read
     * on every decision, so a change takes effect on the AI's next move.
     */
    @Volatile var thinkingDelayMs: Long = 500,
    private val onActionReady: (EntityId, GameAction, String?) -> PolicySubmissionAck,
    private val onMulliganKeep: (EntityId, String?) -> PolicySubmissionAck,
    private val onMulliganTake: (EntityId, String?) -> PolicySubmissionAck,
    private val onBottomCards: (EntityId, List<EntityId>, String?) -> PolicySubmissionAck,
    /** Host-owned policy incident path. It must never submit a synthetic Magic action. */
    private val onPolicyFailure: (EntityId, String, String) -> String? = { _, _, _ -> null },
    /** Re-opens the explicit recovery controls if a scheduled retry is cancelled before it runs. */
    private val onRetryDispatchAbandoned: (EntityId, String, String) -> Unit = { _, _, _ -> },
    /**
     * Local testing mode: the last word on what this seat submits. Given the move the AI chose, it
     * may hold the decision until a human approves it and may hand back a different move entirely
     * (see [AiInsightService]). Null in normal play, where the AI's own pick goes straight through.
     */
    private val actionGate: AiActionGate? = null,
) : WebSocketSession {

    // =========================================================================
    // Draft callbacks — set by LobbyHandler when a draft starts
    // =========================================================================

    /** Called when AI makes a booster draft pick. Args: (playerId, cardNames) */
    @Volatile var onDraftPick: ((EntityId, List<String>) -> Unit)? = null

    /** Called when AI takes a Winston pile. Args: (playerId) */
    @Volatile var onWinstonTakePile: ((EntityId) -> Unit)? = null

    /** Called when AI skips a Winston pile. Args: (playerId) */
    @Volatile var onWinstonSkipPile: ((EntityId) -> Unit)? = null

    /** Called when AI makes a grid draft pick. Args: (playerId, selection) */
    @Volatile var onGridDraftPick: ((EntityId, String) -> Unit)? = null

    private val sessionId = "ai-${UUID.randomUUID()}"
    private val open = AtomicBoolean(true)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val retryPayloadLock = Any()
    private sealed interface InboxItem {
        data class HostPayload(val text: String) : InboxItem
        data class Retry(val invocation: StagedPolicyInvocation, val incidentId: String) : InboxItem
    }
    /** One FIFO actor owns host preprocessing, staged invocation dispatch, and retry transitions. */
    private val inbox = Channel<InboxItem>(Channel.UNLIMITED)
    private val inboxActor = scope.launch {
        for (item in inbox) {
            when (item) {
                is InboxItem.HostPayload -> if (mayProcessNormalPayload()) processServerPayload(item.text)
                is InboxItem.Retry -> processStagedRetry(item.invocation, item.incidentId)
            }
        }
    }

    init {
        inboxActor.invokeOnCompletion { cause ->
            if (cause != null) {
                val abandoned = synchronized(retryPayloadLock) {
                    val invocation = retryInvocation
                    val incidentId = retryIncidentId
                    if (policyInvocationStatus == PolicyInvocationStatus.RETRY_SCHEDULED && invocation != null && incidentId != null) {
                        policyInvocationStatus = PolicyInvocationStatus.FAULTED
                        invocation.token to incidentId
                    } else null
                }
                abandoned?.let { (token, incidentId) -> onRetryDispatchAbandoned(aiPlayerId, incidentId, token) }
            }
        }
    }

    /** Cache the last full game state so we can use it when delta updates arrive. */
    @Volatile
    private var lastFullState: ClientGameState? = null

    /** Rolling game log of recent event descriptions for AI context. */
    private val gameLog = mutableListOf<String>()
    private val maxGameLogSize = 30

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = engineSerializersModule
    }

    private enum class PolicyInvocationStatus { ACTIVE, FAULTED, RETRY_SCHEDULED, PAUSED_UNRETRYABLE }

    private data class StagedPolicyInvocation(
        val token: String = UUID.randomUUID().toString(),
        val invoke: suspend (retryIncidentId: String?) -> PolicySubmissionAck,
    )

    private class StagedPolicyFailure(
        val invocation: StagedPolicyInvocation,
        cause: Throwable,
    ) : RuntimeException(cause)

    /** Staged controller input plus a small state machine protected by [retryPayloadLock]. */
    private var policyInvocationStatus = PolicyInvocationStatus.ACTIVE
    private var retryInvocation: StagedPolicyInvocation? = null
    private var retryIncidentId: String? = null

    /**
     * Called by MessageSender when the server sends a message to this "player".
     * We parse the JSON, extract the game state, and feed it to the AI controller
     * asynchronously.
     */
    override fun sendMessage(message: WebSocketMessage<*>) {
        if (!open.get()) return

        val text = message.payload.toString()
        inbox.trySend(InboxItem.HostPayload(text))
    }

    /**
     * Reinvoke exactly the message that faulted, once and only after the host has cleared its pause.
     * There is no fallback policy on this path.
     */
    fun retryLastPolicyFailure(incidentId: String): Boolean {
        if (!open.get()) return false
        val invocation = synchronized(retryPayloadLock) {
            if (policyInvocationStatus != PolicyInvocationStatus.FAULTED || retryIncidentId != incidentId) null
            else retryInvocation?.also { policyInvocationStatus = PolicyInvocationStatus.RETRY_SCHEDULED }
        } ?: return false
        if (inbox.trySend(InboxItem.Retry(invocation, incidentId)).isFailure) {
            if (restoreAbandonedRetry(invocation, incidentId)) onRetryDispatchAbandoned(aiPlayerId, incidentId, invocation.token)
            return false
        }
        return true
    }

    fun hasRetryablePolicyFailure(incidentId: String): Boolean = synchronized(retryPayloadLock) {
        policyInvocationStatus == PolicyInvocationStatus.FAULTED && retryInvocation != null && retryIncidentId == incidentId
    } && open.get()

    private suspend fun processServerPayload(text: String) {
        val serverMessage = try {
            json.decodeFromString<ServerMessage>(text)
        } catch (e: Exception) {
            // A malformed host payload is not a controller policy decision. Do not assign a
            // software-origin outcome to the AI for a protocol decode defect.
            logger.error("AI received an undecodable server payload: {}", e.message)
            return
        }
        // Message decode/cache/projection is host protocol work. It must not be attributed to a
        // controller policy or made retryable merely because it threw.
        runCatching { handleServerMessage(serverMessage) }
            .onFailure { logger.error("AI host-message processing failed: {}", it.message, it) }
    }

    private fun mayProcessNormalPayload(): Boolean = synchronized(retryPayloadLock) {
        policyInvocationStatus == PolicyInvocationStatus.ACTIVE
    }

    private fun completeExplicitRetry(invocation: StagedPolicyInvocation, incidentId: String) = synchronized(retryPayloadLock) {
        if (policyInvocationStatus == PolicyInvocationStatus.RETRY_SCHEDULED &&
            retryInvocation?.token == invocation.token && retryIncidentId == incidentId) {
            retryInvocation = null
            retryIncidentId = null
            policyInvocationStatus = PolicyInvocationStatus.ACTIVE
        }
    }

    /** Returns true only when cancellation/close left a retry scheduled with no resulting callback. */
    private fun restoreAbandonedRetry(invocation: StagedPolicyInvocation, incidentId: String): Boolean = synchronized(retryPayloadLock) {
        if (policyInvocationStatus == PolicyInvocationStatus.RETRY_SCHEDULED &&
            retryInvocation?.token == invocation.token && retryIncidentId == incidentId) {
            policyInvocationStatus = PolicyInvocationStatus.FAULTED
            true
        } else {
            false
        }
    }

    private fun retainFailedInvocation(invocation: StagedPolicyInvocation, incidentId: String) = synchronized(retryPayloadLock) {
        if (policyInvocationStatus == PolicyInvocationStatus.ACTIVE ||
            (policyInvocationStatus == PolicyInvocationStatus.RETRY_SCHEDULED && retryInvocation?.token == invocation.token)) {
            retryInvocation = invocation
            retryIncidentId = incidentId
            policyInvocationStatus = PolicyInvocationStatus.FAULTED
        }
    }

    private suspend fun executeInitialInvocation(invocation: StagedPolicyInvocation) {
        try {
            when (val acknowledgement = invocation.invoke(null)) {
                PolicySubmissionAck.ACCEPTED -> Unit
                is PolicySubmissionAck.REJECTED -> acknowledgement.incidentId?.let { incidentId ->
                    retainFailedInvocation(invocation, incidentId)
                }
                PolicySubmissionAck.UNSAFE_HOST_FAILURE -> markUnretryablePause()
            }
        } catch (e: AiControllerFatalException) {
            reportPolicyInvocationFailure(invocation, "AI_CONTROLLER_FATAL_EXCEPTION", e.message ?: "controller failed", e)
        } catch (e: ResponsiblePolicyUnavailableException) {
            reportPolicyInvocationFailure(invocation, "RESPONSIBLE_POLICY_UNAVAILABLE", e.message ?: "policy unavailable", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // This boundary wraps only the staged controller invocation, not message decode/cache.
            reportPolicyInvocationFailure(
                invocation,
                "AI_POLICY_PROCESSING_EXCEPTION",
                e::class.simpleName ?: "unexpected policy processing failure",
                e,
            )
        }
    }

    private suspend fun processStagedRetry(invocation: StagedPolicyInvocation, incidentId: String) {
        val scheduled = synchronized(retryPayloadLock) {
            policyInvocationStatus == PolicyInvocationStatus.RETRY_SCHEDULED &&
                retryInvocation?.token == invocation.token && retryIncidentId == incidentId
        }
        if (!scheduled) return
        try {
            when (val acknowledgement = invocation.invoke(incidentId)) {
                PolicySubmissionAck.ACCEPTED -> completeExplicitRetry(invocation, incidentId)
                is PolicySubmissionAck.REJECTED -> {
                    if (acknowledgement.incidentId != null) retainFailedInvocation(invocation, acknowledgement.incidentId)
                    else markUnretryablePause()
                }
                PolicySubmissionAck.UNSAFE_HOST_FAILURE -> markUnretryablePause()
            }
        } catch (e: AiControllerFatalException) {
            reportPolicyInvocationFailure(invocation, "AI_CONTROLLER_FATAL_EXCEPTION", e.message ?: "controller failed", e)
        } catch (e: ResponsiblePolicyUnavailableException) {
            reportPolicyInvocationFailure(invocation, "RESPONSIBLE_POLICY_UNAVAILABLE", e.message ?: "policy unavailable", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportPolicyInvocationFailure(
                invocation,
                "AI_POLICY_PROCESSING_EXCEPTION",
                e::class.simpleName ?: "unexpected policy processing failure",
                e,
            )
        }
    }

    private fun reportPolicyInvocationFailure(
        invocation: StagedPolicyInvocation,
        code: String,
        diagnostic: String,
        error: Throwable,
    ) {
        logger.error("AI policy invocation failed: {}", diagnostic, error)
        val incidentId = onPolicyFailure(aiPlayerId, code, diagnostic)
        if (incidentId != null) retainFailedInvocation(invocation, incidentId)
    }

    /** Leave the authoritative GameSession pause in place but never replay an unknown commit. */
    private fun markUnretryablePause() = synchronized(retryPayloadLock) {
        retryInvocation = null
        retryIncidentId = null
        policyInvocationStatus = PolicyInvocationStatus.PAUSED_UNRETRYABLE
    }

    private suspend fun handleServerMessage(message: ServerMessage) {
        when (message) {
            is ServerMessage.StateUpdate -> {
                logger.info("AI received StateUpdate: phase={}, step={}, priority={}, legalActions={}, pendingDecision={}",
                    message.state.currentPhase, message.state.currentStep,
                    if (message.state.priorityPlayerId == aiPlayerId) "AI" else "opponent",
                    message.legalActions.size,
                    message.pendingDecision?.let { it::class.simpleName })
                lastFullState = message.state
                accumulateEvents(message.events.map { it.description })
                val invocation = stageStateUpdate(message.state, message.legalActions, message.pendingDecision)
                if (invocation != null) executeInitialInvocation(invocation)
            }

            is ServerMessage.StateDeltaUpdate -> {
                logger.info("AI received StateDeltaUpdate: legalActions={}, pendingDecision={}, hasCachedState={}",
                    message.legalActions.size,
                    message.pendingDecision?.let { it::class.simpleName },
                    lastFullState != null)
                accumulateEvents(message.events.map { it.description })
                // Apply delta to cached state to keep it current
                val cachedState = lastFullState
                val updatedState = if (cachedState != null) {
                    applyDelta(cachedState, message.delta).also { lastFullState = it }
                } else null
                if (updatedState != null && (message.legalActions.isNotEmpty() || message.pendingDecision != null)) {
                    val invocation = stageStateUpdate(updatedState, message.legalActions, message.pendingDecision)
                    if (invocation != null) executeInitialInvocation(invocation)
                } else if (message.legalActions.isNotEmpty() || message.pendingDecision != null) {
                    logger.warn("AI received delta update but has no cached state — falling back to heuristics")
                    val invocation = stageActionsOnlyFallback(message.legalActions, message.pendingDecision)
                    if (invocation != null) executeInitialInvocation(invocation)
                }
            }

            is ServerMessage.MulliganDecision -> {
                logger.info("AI received MulliganDecision: mulliganCount={}, hand={} cards, onThePlay={}",
                    message.mulliganCount, message.hand.size, message.isOnThePlay)
                val input = message.toMulliganInfo()
                executeInitialInvocation(StagedPolicyInvocation { retryIncidentId ->
                    delay(thinkingDelayMs)
                    val keep = controller.decideMulligan(input)
                    logger.info("AI mulligan result: {}", if (keep) "KEEP" else "MULLIGAN")
                    if (keep) onMulliganKeep(aiPlayerId, retryIncidentId)
                    else onMulliganTake(aiPlayerId, retryIncidentId)
                })
            }

            is ServerMessage.ChooseBottomCards -> {
                logger.info("AI received ChooseBottomCards: hand={}, bottomCount={}", message.hand.size, message.cardsToPutOnBottom)
                val input = message.toBottomCardsInfo()
                executeInitialInvocation(StagedPolicyInvocation { retryIncidentId ->
                    delay(thinkingDelayMs)
                    val bottomCards = controller.chooseBottomCards(input)
                    logger.info("AI chose bottom cards: {}", bottomCards)
                    onBottomCards(aiPlayerId, bottomCards, retryIncidentId)
                })
            }

            is ServerMessage.GameStarted -> {
                logger.info("AI received GameStarted: opponents={}",
                    message.players.filter { !it.isYou }.map { it.name })
            }

            is ServerMessage.GameOver -> {
                logger.info("AI game over. Winner: {}", message.winnerId)
                open.set(false)
                scope.cancel()
            }

            is ServerMessage.WaitingForOpponentMulligan -> {
                logger.info("AI waiting for opponent mulligan")
            }

            is ServerMessage.MulliganComplete -> {
                logger.info("AI mulligan complete, final hand size: {}", message.finalHandSize)
            }

            is ServerMessage.GameCreated -> {
                logger.info("AI received GameCreated")
            }

            is ServerMessage.GameCancelled -> {
                logger.info("AI game cancelled")
                open.set(false)
                scope.cancel()
            }

            // =================================================================
            // Draft messages
            // =================================================================

            is ServerMessage.DraftPackReceived -> {
                logger.info("AI received DraftPackReceived: pack={}, pick={}, cards={}, picksPerRound={}",
                    message.packNumber, message.pickNumber, message.cards.size, message.picksPerRound)
                handleDraftPack(message)
            }

            is ServerMessage.DraftPickConfirmed -> {
                logger.info("AI draft pick confirmed: {} (total: {})",
                    message.cardNames.joinToString(", "), message.totalPicked)
            }

            is ServerMessage.DraftComplete -> {
                logger.info("AI draft complete: {} cards picked", message.pickedCards.size)
            }

            is ServerMessage.WinstonDraftState -> {
                if (message.isYourTurn) {
                    logger.info("AI received WinstonDraftState: pile={}, pileCards={}, pileSizes={}",
                        message.currentPileIndex, message.currentPileCards?.size, message.pileSizes)
                    handleWinstonTurn(message)
                } else {
                    logger.info("AI waiting for opponent's Winston turn")
                }
            }

            is ServerMessage.GridDraftState -> {
                if (message.isYourTurn) {
                    logger.info("AI received GridDraftState: grid #{}, selections={}",
                        message.gridNumber, message.availableSelections)
                    handleGridDraftTurn(message)
                } else {
                    logger.info("AI waiting for opponent's grid draft turn")
                }
            }

            is ServerMessage.SealedPoolGenerated -> {
                logger.info("AI received SealedPoolGenerated: {} cards", message.cardPool.size)
            }

            else -> {
                logger.info("AI received message type: {}", message::class.simpleName)
            }
        }
    }

    private fun accumulateEvents(descriptions: List<String>) {
        val newEntries = descriptions.filter { it.isNotBlank() }
        if (newEntries.isEmpty()) return
        synchronized(gameLog) {
            gameLog.addAll(newEntries)
            // Trim to keep only the most recent entries
            while (gameLog.size > maxGameLogSize) {
                gameLog.removeFirst()
            }
        }
    }

    private fun getRecentGameLog(): List<String> {
        synchronized(gameLog) {
            return gameLog.toList()
        }
    }

    private fun stageStateUpdate(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?
    ): StagedPolicyInvocation? {
        // If we have legal actions or a pending decision addressed to us, it's our turn.
        // Don't rely on state.priorityPlayerId — it may be stale when using a cached state
        // from a previous StateUpdate combined with a newer StateDeltaUpdate's legal actions.
        val isOurDecision = pendingDecision?.playerId == aiPlayerId
        val hasLegalActions = legalActions.isNotEmpty()

        if (!hasLegalActions && !isOurDecision) {
            logger.info("AI skipping — no legal actions and no pending decision for us")
            return null
        }

        if (legalActions.isNotEmpty()) {
            logger.info("AI has {} legal actions: {}", legalActions.size,
                legalActions.joinToString(", ") { "${it.actionType}${if (it.description.isNotBlank()) "(${it.description})" else ""}" })
        }
        if (pendingDecision != null) {
            logger.info("AI has pending decision: {} — {}", pendingDecision::class.simpleName, pendingDecision.prompt)
        }

        // Snapshot every responsible-policy input after one-time host preprocessing. Retrying this
        // invocation neither re-applies a delta nor duplicates the accumulated game log.
        val capturedState = state
        val capturedActions = legalActions.toList()
        val capturedDecision = pendingDecision
        val capturedLog = getRecentGameLog()
        return StagedPolicyInvocation { retryIncidentId ->
            delay(thinkingDelayMs)
            val response = controller.chooseAction(capturedState, capturedActions, capturedDecision, capturedLog)
            logger.info("AI chose response: {}", when (response) {
                is ActionResponse.SubmitAction -> "Action(${response.action::class.simpleName})"
                is ActionResponse.SubmitDecision -> "Decision(${response.response::class.simpleName})"
            })

            if (response is ActionResponse.SubmitAction && response.action is DeclareBlockers) {
                delay(thinkingDelayMs * 4)
            }
            val gated = if (response is ActionResponse.SubmitAction && actionGate != null) {
                ActionResponse.SubmitAction(actionGate.approve(aiPlayerId, response.action))
            } else response
            submitResponse(gated, retryIncidentId)
        }
    }

    /** Refuse choices that cannot be routed to the controller without its full state input. */
    private fun stageActionsOnlyFallback(
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?
    ): StagedPolicyInvocation? {
        if (legalActions.isEmpty() && pendingDecision == null) return null

        if (pendingDecision?.playerId != null && pendingDecision.playerId != aiPlayerId) return null

        val capturedActions = legalActions.toList()
        val capturedDecision = pendingDecision
        return StagedPolicyInvocation { retryIncidentId ->
            delay(thinkingDelayMs)
            if (capturedDecision == null && capturedActions.size == 1 &&
                capturedActions.single().actionType == "PassPriority"
            ) {
                logger.info("AI no-state path: submitting rules-singleton PassPriority")
                submitResponse(ActionResponse.SubmitAction(capturedActions.single().action), retryIncidentId)
            } else {
                throw ResponsiblePolicyUnavailableException(
                    capturedDecision?.let { it::class.simpleName } ?: "ACTION",
                    "full game state is unavailable; choice cannot be routed to the responsible policy",
                )
            }
        }
    }

    // =========================================================================
    // Draft pick handlers
    // =========================================================================

    private suspend fun handleDraftPack(message: ServerMessage.DraftPackReceived) {
        val callback = onDraftPick
        if (callback == null) {
            logger.warn("AI received draft pack but no onDraftPick callback is set")
            return
        }

        delay(thinkingDelayMs)

        val picks = controller.chooseDraftPick(
            pack = message.cards.map { it.toCardSummary() },
            pickedSoFar = message.pickedCards.map { it.toCardSummary() },
            packNumber = message.packNumber,
            pickNumber = message.pickNumber,
            picksRequired = message.picksPerRound,
            passDirection = message.passDirection
        )
        logger.info("AI draft pick: {}", picks.joinToString(", "))
        callback(aiPlayerId, picks)
    }

    private suspend fun handleWinstonTurn(message: ServerMessage.WinstonDraftState) {
        val pileCards = message.currentPileCards
        if (pileCards == null) {
            logger.warn("AI Winston turn but no pile cards visible")
            return
        }

        delay(thinkingDelayMs)

        val take = controller.chooseWinstonAction(
            pileCards = pileCards.map { it.toCardSummary() },
            pileIndex = message.currentPileIndex,
            pileSizes = message.pileSizes,
            pickedSoFar = message.pickedCards.map { it.toCardSummary() }
        )

        if (take) {
            val callback = onWinstonTakePile
            if (callback != null) {
                logger.info("AI Winston: TAKE pile {}", message.currentPileIndex)
                callback(aiPlayerId)
            } else {
                logger.warn("AI wants to take Winston pile but no callback set")
            }
        } else {
            val callback = onWinstonSkipPile
            if (callback != null) {
                logger.info("AI Winston: SKIP pile {}", message.currentPileIndex)
                callback(aiPlayerId)
            } else {
                logger.warn("AI wants to skip Winston pile but no callback set")
            }
        }
    }

    private suspend fun handleGridDraftTurn(message: ServerMessage.GridDraftState) {
        val callback = onGridDraftPick
        if (callback == null) {
            logger.warn("AI received grid draft turn but no onGridDraftPick callback is set")
            return
        }

        delay(thinkingDelayMs)

        val selection = controller.chooseGridDraftPick(
            grid = message.grid.map { it?.toCardSummary() },
            availableSelections = message.availableSelections,
            pickedSoFar = message.pickedCards.map { it.toCardSummary() }
        )
        logger.info("AI grid draft pick: {}", selection)
        callback(aiPlayerId, selection)
    }

    private fun submitResponse(response: ActionResponse, retryIncidentId: String?): PolicySubmissionAck =
        when (response) {
            is ActionResponse.SubmitAction -> {
                onActionReady(aiPlayerId, response.action, retryIncidentId)
            }
            is ActionResponse.SubmitDecision -> {
                val action = SubmitDecision(
                    playerId = response.playerId,
                    response = response.response
                )
                onActionReady(aiPlayerId, action, retryIncidentId)
            }
        }

    /**
     * Apply a StateDelta to a previous ClientGameState to produce an updated state.
     * Mirrors the logic in ProtocolTestBase/GameServerTestBase.
     */
    private fun applyDelta(previous: ClientGameState, delta: StateDelta): ClientGameState {
        val cards = previous.cards.toMutableMap()
        delta.removedCardIds?.forEach { cards.remove(it) }
        delta.addedCards?.forEach { (id, card) -> cards[id] = card }
        delta.updatedCards?.forEach { (id, card) -> cards[id] = card }

        val updatedZones = delta.updatedZones
        val zones = if (updatedZones != null) {
            val updatedMap = updatedZones.associateBy { it.zoneId }
            previous.zones.map { updatedMap[it.zoneId] ?: it }
        } else {
            previous.zones
        }

        val newLogEntries = delta.newLogEntries
        val gameLog = if (newLogEntries != null) {
            previous.gameLog + newLogEntries
        } else {
            previous.gameLog
        }

        val combat = when {
            delta.combatCleared == true -> null
            delta.combat != null -> delta.combat
            else -> previous.combat
        }

        return previous.copy(
            cards = cards,
            zones = zones,
            players = delta.players,
            currentPhase = delta.currentPhase ?: previous.currentPhase,
            currentStep = delta.currentStep ?: previous.currentStep,
            activePlayerId = delta.activePlayerId ?: previous.activePlayerId,
            priorityPlayerId = delta.priorityPlayerId ?: previous.priorityPlayerId,
            turnNumber = delta.turnNumber ?: previous.turnNumber,
            isGameOver = delta.isGameOver ?: previous.isGameOver,
            winnerId = if (delta.winnerId != null) delta.winnerId else previous.winnerId,
            dayNight = delta.dayNight ?: previous.dayNight,
            combat = combat,
            gameLog = gameLog,
        )
    }

    fun shutdown() {
        open.set(false)
        scope.cancel()
    }

    // =========================================================================
    // WebSocketSession interface stubs
    // =========================================================================

    override fun getId(): String = sessionId
    override fun getUri(): URI? = URI.create("ai://localhost/game")
    override fun getHandshakeHeaders(): HttpHeaders = HttpHeaders()
    override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()
    override fun getPrincipal(): Principal? = null
    override fun getLocalAddress(): InetSocketAddress? = null
    override fun getRemoteAddress(): InetSocketAddress? = null
    override fun getAcceptedProtocol(): String? = null
    override fun setTextMessageSizeLimit(messageSizeLimit: Int) {}
    override fun getTextMessageSizeLimit(): Int = Int.MAX_VALUE
    override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) {}
    override fun getBinaryMessageSizeLimit(): Int = Int.MAX_VALUE
    override fun getExtensions(): MutableList<WebSocketExtension> = mutableListOf()
    override fun isOpen(): Boolean = open.get()

    @Throws(IOException::class)
    override fun close() {
        shutdown()
    }

    @Throws(IOException::class)
    override fun close(status: CloseStatus) {
        shutdown()
    }
}

private fun ServerMessage.MulliganDecision.toMulliganInfo() = MulliganInfo(
    hand = hand,
    mulliganCount = mulliganCount,
    cardsToPutOnBottom = cardsToPutOnBottom,
    cards = cards.mapValues { (_, v) -> v.toCardSummary() },
    isOnThePlay = isOnThePlay
)

private fun ServerMessage.ChooseBottomCards.toBottomCardsInfo() = BottomCardsInfo(
    hand = hand,
    cardsToPutOnBottom = cardsToPutOnBottom,
    cards = cards.mapValues { (_, v) -> v.toCardSummary() }
)

private fun ServerMessage.MulliganCardInfo.toCardSummary() = CardSummary(
    name = name,
    manaCost = manaCost,
    typeLine = typeLine,
    power = power,
    toughness = toughness,
    oracleText = oracleText
)

private fun ServerMessage.SealedCardInfo.toCardSummary() = CardSummary(
    name = name,
    manaCost = manaCost,
    typeLine = typeLine,
    rarity = rarity,
    imageUri = imageUri,
    power = power,
    toughness = toughness,
    oracleText = oracleText,
    rulings = rulings.map { CardRuling(it.date, it.text) }
)
