package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.handler.MessageSender
import com.wingedsheep.gameserver.protocol.ServerMessage
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The archived *output* of a legacy recorded game: the `{initialSnapshot, deltas}` stream the replay
 * viewer consumes, serialized once at record time and stored next to its compact input recipe.
 *
 * A v1/v2 input log has to run through engine code that changes over time. [ReplayCardPin] protects
 * card definitions and [ReplayFingerprint] detects other drift, but only this record-time output can
 * still render after a divergence. It is a spectator view, not a complete game state, so it remains
 * a viewing fallback rather than authoritative replay data.
 *
 * Canonical v3 needs no presentation copy: it already stores authoritative full states and lossless
 * patches, reconstructs without executing current engine code, and supports exact frame sharing.
 * [ReplayService] therefore invokes this component only for legacy records.
 *
 * Stored as the already-composed JSON body rather than a DTO: serving it is then a byte passthrough,
 * with no chance of a DTO reshape making an old archive unreadable.
 */
@Component
class ReplayPresentation(
    private val messageSender: MessageSender,
    /**
     * Skip archiving when the *stored* (gzipped) stream exceeds this many bytes. A full game is
     * ~160 KB stored, so the 4 MB default only bites on something pathological — a mill-loop
     * stalemate, an AI grinding 400 turns — where the frames cost far more than they're worth.
     * Those legacy replays keep their input log and degrade to a truncated view if they stop
     * re-simulating. 0 disables archiving entirely.
     */
    @Value("\${game.replay.presentation-max-stored-bytes:4194304}")
    private val maxStoredBytes: Int,
) {
    private val logger = LoggerFactory.getLogger(ReplayPresentation::class.java)

    /**
     * Serialize [reconstructed] into the viewer body, or null when archiving is disabled, the
     * reconstruction is already broken (archiving a truncated stream just freezes the truncation),
     * or the stored form would exceed [maxStoredBytes].
     */
    fun materialize(reconstructed: ReconstructedReplay): String? {
        if (maxStoredBytes <= 0) return null
        if (!reconstructed.isComplete) {
            logger.warn("Not archiving a diverged reconstruction: {}", reconstructed.divergenceReason)
            return null
        }
        val body = compose(reconstructed)
        // Measured on the compressed form, because that is what the column costs — the raw body is
        // ~50x larger and a cap on it would reject perfectly ordinary games. The store re-encodes;
        // one extra gzip per finished game, on a background thread, is not worth avoiding.
        val storedBytes = ReplayCodec.encodeText(body).length
        if (storedBytes > maxStoredBytes) {
            logger.info(
                "Replay presentation is {} stored bytes (> {}) — keeping the legacy input log only",
                storedBytes, maxStoredBytes,
            )
            return null
        }
        return body
    }

    /** The `{"initialSnapshot":…,"deltas":[…]}` body, exactly as the replay endpoints serve it. */
    fun compose(reconstructed: ReconstructedReplay): String {
        val initial = messageSender.json.encodeToString(
            ServerMessage.SpectatorStateUpdate.serializer(),
            reconstructed.initialSnapshot,
        )
        val deltas = messageSender.json.encodeToString(
            ListSerializer(SpectatorReplayDelta.serializer()),
            reconstructed.deltas,
        )
        return """{"initialSnapshot":$initial,"deltas":$deltas}"""
    }
}
