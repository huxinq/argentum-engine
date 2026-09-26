package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.HexFormat
import java.util.Locale

class StateDigestCompatibilityTest : FunSpec({
    // SHA-256 of the pre-change canonical encoding for this literal observation.
    val golden = "cbb91c58d4cbf3080bedf2eb72a9c7f3df535a3f6d0df6b1f758c505e0995b44"
    val me = EntityId.of("me")
    val foe = EntityId.of("foe")
    val card = EntityFeatures(
        entityId = EntityId.of("e1"),
        cardDefinitionId = "card:é",
        name = "Éclair|雪",
        zone = Zone.HAND,
        ownerId = me,
        controllerId = null,
        types = linkedSetOf("SORCERY", "INSTANT"),
        subtypes = emptySet(),
        colors = linkedSetOf("RED", "BLUE"),
        keywords = emptySet(),
        manaCost = "{U}",
        manaValue = 1,
        oracleText = "Excluded printed text",
        power = null,
        toughness = null,
        counters = mapOf("charge" to 2),
    )
    val observation = TrainingObservation(
        schemaHash = "fixture-v1",
        perspectivePlayerId = me,
        agentToAct = me,
        turnNumber = 7,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = me,
        priorityPlayerId = foe,
        players = listOf(
            PlayerView(foe, "Other", 18, 2, 30, 0, 0, ManaPoolView(red = 1), false, false, true, false),
            PlayerView(me, "Self", 20, 3, 29, 1, 0, ManaPoolView(white = 1), true, true, false, false),
        ),
        zones = listOf(
            ZoneView(me, Zone.HAND, false, 1, listOf(card)),
            ZoneView(foe, Zone.GRAVEYARD, false, 0, emptyList()),
        ),
        stack = listOf(StackItemView(EntityId.of("s"), me, "雪|🔥", StackItemKind.SPELL, "Draw:\nÉ", listOf(card.entityId, foe))),
        pendingDecision = PendingDecisionView("d|1", PendingDecisionKind.YES_NO, me, "Choose?"),
        legalActions = emptyList(),
        terminated = false,
        winnerId = null,
        stateDigest = "ignored",
    )

    test("frozen digest preserves canonical UTF-8 payload and lowercase hex") {
        StateDigest.compute(observation) shouldBe golden
    }

    test("unordered views and excluded fields do not affect digest") {
        val reordered = observation.copy(
            players = observation.players.reversed().map { it.copy(name = "Excluded ${it.id.value}") },
            zones = observation.zones.reversed().map { zone ->
                zone.copy(cards = zone.cards.map { it.copy(
                    types = linkedSetOf("INSTANT", "SORCERY"),
                    colors = linkedSetOf("BLUE", "RED"),
                    oracleText = "Changed printed text",
                ) }) }
            },
            legalActions = listOf(LegalActionView(42, "Pass", "Pass priority", true)),
            pendingDecision = observation.pendingDecision?.copy(prompt = "Changed prompt"),
            stateDigest = "different",
        )
        StateDigest.compute(reordered) shouldBe StateDigest.compute(observation)
        StateDigest.compute(observation.copy(stack = observation.stack.map { it.copy(targets = it.targets.reversed()) }))
            .shouldNotBe(golden)
    }

    test("digest is independent of default locale") {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-EG"))
                .forEach { locale ->
                    Locale.setDefault(locale)
                    StateDigest.compute(observation) shouldBe golden
                }
        } finally {
            Locale.setDefault(previous)
        }
    }

    test("JDK hex encoding matches the former formatter for every byte") {
        val allBytes = (0..255).map { it.toByte() }.toByteArray()
        HexFormat.of().formatHex(allBytes) shouldBe allBytes.joinToString("") { "%02x".format(it) }
        HexFormat.of().formatHex(byteArrayOf(0, 1, 15, 127, -128, -1)) shouldBe "00010f7f80ff"
        HexFormat.of().formatHex(byteArrayOf()) shouldBe ""
    }
})
