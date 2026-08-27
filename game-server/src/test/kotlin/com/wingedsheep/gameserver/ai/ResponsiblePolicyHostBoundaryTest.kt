package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.ai.ResponsiblePolicyUnavailableException
import com.wingedsheep.ai.llm.BottomCardsInfo
import com.wingedsheep.ai.llm.CardSummary
import com.wingedsheep.ai.llm.MulliganInfo
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.engine.view.StateDelta
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.web.socket.TextMessage

class ResponsiblePolicyHostBoundaryTest : StringSpec({
    "typed responsible-policy refusal reports a host fault without a game action" {
        val player = EntityId("ai")
        val result = sendAndAwaitPolicyFault(
            player,
            ServerMessage.StateUpdate(
                state = clientState(player),
                events = emptyList(),
                legalActions = listOf(LegalActionInfo("PassPriority", "Pass", PassPriority(player))),
            ),
        )

        result.seat shouldBe player
        result.code shouldBe "RESPONSIBLE_POLICY_UNAVAILABLE"
        result.actionCount shouldBe 0
    }

    "no-state host path refuses a player decision instead of scripting it" {
        val player = EntityId("ai")
        val decision = YesNoDecision(
            id = "decision",
            playerId = player,
            prompt = "Use it?",
            context = DecisionContext(),
        )
        val result = sendAndAwaitPolicyFault(
            player,
            ServerMessage.StateDeltaUpdate(
                delta = StateDelta(players = emptyList()),
                events = emptyList(),
                legalActions = emptyList(),
                pendingDecision = decision,
            ),
        )

        result.seat shouldBe player
        result.code shouldBe "RESPONSIBLE_POLICY_UNAVAILABLE"
        result.actionCount shouldBe 0
    }

    "unexpected controller processing failure also reports a typed host policy fault" {
        val player = EntityId("ai")
        val result = sendAndAwaitPolicyFault(
            player,
            ServerMessage.StateUpdate(
                state = clientState(player),
                events = emptyList(),
                legalActions = listOf(LegalActionInfo("PassPriority", "Pass", PassPriority(player))),
            ),
            ExplodingController,
        )

        result.seat shouldBe player
        result.code shouldBe "AI_POLICY_PROCESSING_EXCEPTION"
        result.actionCount shouldBe 0
    }

    "retry is incident-bound and replays one staged invocation exactly once" {
        val player = EntityId("retry-ai")
        val attempts = AtomicInteger()
        val initial = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val session = AiWebSocketSession(
            aiPlayerId = player,
            controller = PassingController,
            thinkingDelayMs = 0,
            onActionReady = { _, _, retryIncidentId ->
                when (attempts.incrementAndGet()) {
                    1 -> {
                        retryIncidentId shouldBe null
                        initial.countDown()
                        PolicySubmissionAck.REJECTED("incident-a")
                    }
                    2 -> {
                        retryIncidentId shouldBe "incident-a"
                        recovered.countDown()
                        PolicySubmissionAck.ACCEPTED
                    }
                    else -> error("staged action was duplicated")
                }
            },
            onMulliganKeep = { _, _ -> PolicySubmissionAck.ACCEPTED },
            onMulliganTake = { _, _ -> PolicySubmissionAck.ACCEPTED },
            onBottomCards = { _, _, _ -> PolicySubmissionAck.ACCEPTED },
        )
        val json = Json { encodeDefaults = true; classDiscriminator = "type"; serializersModule = engineSerializersModule }
        session.sendMessage(TextMessage(json.encodeToString<ServerMessage>(ServerMessage.StateUpdate(
            state = clientState(player), events = emptyList(),
            legalActions = listOf(LegalActionInfo("PassPriority", "Pass", PassPriority(player))),
        ))))
        check(initial.await(2, TimeUnit.SECONDS))
        check(waitUntil { session.hasRetryablePolicyFailure("incident-a") })
        session.hasRetryablePolicyFailure("incident-a") shouldBe true
        session.retryLastPolicyFailure("stale-incident") shouldBe false
        attempts.get() shouldBe 1
        session.retryLastPolicyFailure("incident-a") shouldBe true
        check(recovered.await(2, TimeUnit.SECONDS))
        session.hasRetryablePolicyFailure("incident-a") shouldBe false
        attempts.get() shouldBe 2
        session.retryLastPolicyFailure("incident-a") shouldBe false
        session.close()
    }
})

private data class ReportedPolicyFault(val seat: EntityId, val code: String, val actionCount: Int)

private fun waitUntil(predicate: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (System.nanoTime() < deadline) {
        if (predicate()) return true
        Thread.sleep(5)
    }
    return predicate()
}

private fun sendAndAwaitPolicyFault(
    player: EntityId,
    message: ServerMessage,
    controller: AiPlayerController = RefusingController,
): ReportedPolicyFault {
    val fault = AtomicReference<Pair<EntityId, String>>()
    val actionCount = AtomicInteger()
    val latch = CountDownLatch(1)
    val session = AiWebSocketSession(
        aiPlayerId = player,
        controller = controller,
        thinkingDelayMs = 0,
        onActionReady = { _, _, _ ->
            actionCount.incrementAndGet()
            PolicySubmissionAck.ACCEPTED
        },
        onMulliganKeep = { _, _ -> PolicySubmissionAck.ACCEPTED },
        onMulliganTake = { _, _ -> PolicySubmissionAck.ACCEPTED },
        onBottomCards = { _, _, _ -> PolicySubmissionAck.ACCEPTED },
        onPolicyFailure = { failingSeat, code, _ ->
            fault.set(failingSeat to code)
            latch.countDown()
            "test-policy-fault"
        },
    )
    val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        serializersModule = engineSerializersModule
    }
    session.sendMessage(TextMessage(json.encodeToString<ServerMessage>(message)))
    check(latch.await(2, TimeUnit.SECONDS)) { "AI host did not report the policy refusal" }
    // The callback is asynchronous. Give the old synthetic-action path an opportunity to surface;
    // it must remain empty after the typed failure is reported.
    Thread.sleep(25)
    session.close()
    val reported = requireNotNull(fault.get())
    return ReportedPolicyFault(reported.first, reported.second, actionCount.get())
}

private object RefusingController : AiPlayerController {
    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = throw ResponsiblePolicyUnavailableException("TEST", "no policy answer")

    override fun decideMulligan(mulliganMessage: MulliganInfo): Boolean = error("unused")
    override fun chooseBottomCards(message: BottomCardsInfo): List<EntityId> = error("unused")
    override fun setDeckList(deckList: Map<String, Int>, archetype: String?) = Unit
    override fun chooseDraftPick(
        pack: List<CardSummary>,
        pickedSoFar: List<CardSummary>,
        packNumber: Int,
        pickNumber: Int,
        picksRequired: Int,
        passDirection: String,
    ): List<String> = error("unused")

    override fun chooseWinstonAction(
        pileCards: List<CardSummary>,
        pileIndex: Int,
        pileSizes: List<Int>,
        pickedSoFar: List<CardSummary>,
    ): Boolean = error("unused")

    override fun chooseGridDraftPick(
        grid: List<CardSummary?>,
        availableSelections: List<String>,
        pickedSoFar: List<CardSummary>,
    ): String = error("unused")
}

private object ExplodingController : AiPlayerController by RefusingController {
    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = error("unexpected controller bug")
}

private object PassingController : AiPlayerController by RefusingController {
    override fun chooseAction(
        state: ClientGameState,
        legalActions: List<LegalActionInfo>,
        pendingDecision: PendingDecision?,
        recentGameLog: List<String>,
    ): ActionResponse = ActionResponse.SubmitAction(PassPriority(state.viewingPlayerId))
}

private fun clientState(player: EntityId): ClientGameState = ClientGameState(
    viewingPlayerId = player,
    cards = emptyMap(),
    zones = emptyList(),
    players = emptyList(),
    currentPhase = Phase.PRECOMBAT_MAIN,
    currentStep = Step.PRECOMBAT_MAIN,
    activePlayerId = player,
    priorityPlayerId = player,
    turnNumber = 1,
    isGameOver = false,
    winnerId = null,
    combat = null,
)
