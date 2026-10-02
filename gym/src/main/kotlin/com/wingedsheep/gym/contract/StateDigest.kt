package com.wingedsheep.gym.contract

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Deterministic hash of a [TrainingObservation]'s stable fields, suitable for
 * MCTS transposition tables.
 *
 * Two observations with the same digest describe the same information-set
 * from the same perspective. Derived fields ([TrainingObservation.legalActions],
 * [TrainingObservation.stateDigest] itself) are intentionally excluded — they
 * do not affect game identity.
 *
 * The canonical encoding sorts all unordered collections (sets, map keys) so
 * iteration-order variations cannot produce different digests for equivalent
 * states. Ordered collections (zone contents, stack) are encoded as-is: their
 * order is semantically meaningful. Free-form strings (names, oracle text) are
 * length-prefixed by [appendText] so a value containing the encoding's own
 * delimiters cannot make two different observations serialize alike.
 */
object StateDigest {

    fun compute(obs: TrainingObservation): String {
        val sb = StringBuilder(2048)
        encode(sb, obs)
        val bytes = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return HexFormat.of().formatHex(bytes)
    }

    private fun encode(sb: StringBuilder, obs: TrainingObservation) {
        sb.append("h=").append(obs.schemaHash).append('|')
        sb.append("tn=").append(obs.turnNumber).append('|')
        sb.append("ph=").append(obs.phase.name).append('|')
        sb.append("st=").append(obs.step.name).append('|')
        sb.append("ap=").append(obs.activePlayerId?.value).append('|')
        sb.append("pp=").append(obs.priorityPlayerId?.value).append('|')
        sb.append("me=").append(obs.perspectivePlayerId.value).append('|')
        sb.append("ag=").append(obs.agentToAct?.value).append('|')
        sb.append("te=").append(obs.terminated).append('|')
        sb.append("wi=").append(obs.winnerId?.value).append('|')

        // Players — sort by ID for determinism.
        obs.players.sortedBy { it.id.value }.forEach { p ->
            sb.append("P[").append(p.id.value).append("]")
                .append(":life=").append(p.lifeTotal)
                .append(":h=").append(p.handSize)
                .append(":l=").append(p.librarySize)
                .append(":g=").append(p.graveyardSize)
                .append(":e=").append(p.exileSize)
                .append(":lost=").append(p.hasLost)
                .append(":mp=").append(p.manaPool.white).append(',').append(p.manaPool.blue).append(',')
                .append(p.manaPool.black).append(',').append(p.manaPool.red).append(',')
                .append(p.manaPool.green).append(',').append(p.manaPool.colorless)
                .append('|')
        }

        // Zones — sort by (owner, zoneType) for determinism. Entity order
        // within a zone is preserved; it matters (library order, etc.).
        obs.zones
            .sortedWith(compareBy({ it.ownerId.value }, { it.zoneType.name }))
            .forEach { z ->
                sb.append("Z[").append(z.ownerId.value).append('/').append(z.zoneType.name)
                    .append("]:hidden=").append(z.hidden)
                    .append(":size=").append(z.size)
                    .append(":ids=")
                // Every card [ZoneView.cards] lists — which for a hidden zone is the subset this
                // perspective has been shown, and for a fully concealed one is nothing at all.
                z.cards.forEach { c ->
                    sb.append(c.entityId.value).append(',')
                    encodeEntity(sb, c)
                    sb.append(';')
                }
                sb.append('|')
            }

        // Stack — order is meaningful (bottom → top).
        obs.stack.forEachIndexed { i, s ->
            sb.append("S[").append(i).append("]=").append(s.entityId.value)
                .append(":ctl=").append(s.controllerId?.value)
                .append(":kind=").append(s.kind.name)
                .append(":name=").appendText(s.name)
                .append(":text=").appendText(s.oracleText)
                .append(":targets=").append(s.targets.joinToString(",") { it.value })
                .append('|')
        }

        // Pending decision — identity + kind only; per-option IDs are not
        // part of game identity.
        obs.pendingDecision?.let { d ->
            sb.append("D=").appendText(d.decisionId)
                .append(':').append(d.kind.name)
                .append(':').append(d.requiresStructuredResponse).append('|')
        }
    }

    private fun encodeEntity(sb: StringBuilder, e: EntityFeatures) {
        sb.append("n=").appendText(e.name)
            .append(",def=").appendText(e.cardDefinitionId)
            .append(",own=").append(e.ownerId?.value)
            .append(",ctl=").append(e.controllerId?.value)
            .append(",ty=").append(e.types.sorted().joinToString("/"))
            .append(",sub=").append(e.subtypes.sorted().joinToString("/"))
            .append(",col=").append(e.colors.sorted().joinToString("/"))
            .append(",kw=").append(e.keywords.sorted().joinToString("/"))
            .append(",mc=").appendText(e.manaCost)
            .append(",mv=").append(e.manaValue)
            .append(",p=").append(e.power)
            .append(",t=").append(e.toughness)
            .append(",tap=").append(e.tapped)
            .append(",sick=").append(e.summoningSick)
            .append(",fd=").append(e.faceDown)
            .append(",dmg=").append(e.damageMarked)
            .append(",cnt=").append(
                e.counters.entries.sortedBy { it.key }.joinToString("/") { "${it.key}=${it.value}" }
            )
            .append(",att=").append(e.attachedTo?.value)
            .append(",eqp=").append(e.attachments.joinToString("/") { it.value })
    }

    /**
     * Append a free-form string as `<length>:<value>`.
     *
     * Card names and oracle text are author-supplied and can contain every delimiter this
     * encoding uses (`|`, `:`, `,`, `;`, newlines), so appending them raw would let two
     * observations that differ — in how many stack items they hold, say — serialize to the
     * same bytes. The length prefix makes the field self-delimiting. `null` is a bare `~`,
     * which no length-prefixed value can spell (those always start with a digit).
     */
    private fun StringBuilder.appendText(value: String?): StringBuilder =
        if (value == null) append('~') else append(value.length).append(':').append(value)
}
