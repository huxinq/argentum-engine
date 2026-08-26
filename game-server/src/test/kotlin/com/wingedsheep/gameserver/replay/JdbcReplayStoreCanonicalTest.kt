package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.replay.CanonicalReplayRecord
import com.wingedsheep.engine.replay.CanonicalReplayRecorder
import com.wingedsheep.engine.replay.ReplayTransitionOrigin
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.persistence.GameReplayChunkRepository
import com.wingedsheep.gameserver.persistence.GameReplayChunkRow
import com.wingedsheep.gameserver.persistence.GameReplayRepository
import com.wingedsheep.gameserver.persistence.GameReplayRow
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class JdbcReplayStoreCanonicalTest : FunSpec({
    fun canonicalPrefix(gameId: String): List<CanonicalReplayRecord> {
        val initial = GameState(initialSeed = 41L)
        val recorder = CanonicalReplayRecorder(
            gameId = gameId,
            createdAtUtc = "2026-08-26T00:00:00Z",
            engineVersion = "test",
            producer = "jdbc-test",
            players = listOf("p0", "p1"),
            initialState = initial,
        )
        val transition = recorder.appendAction(
            origin = ReplayTransitionOrigin.PLAYER,
            action = PassPriority(EntityId("p0")),
            accepted = true,
            resultingState = initial.copy(turnNumber = 1),
        )
        return listOf(recorder.header, transition)
    }

    fun replay(gameId: String, records: List<CanonicalReplayRecord>) = CompactReplay(
        gameId = gameId,
        players = listOf(ReplayPlayerInfo("p0", "Alice"), ReplayPlayerInfo("p1", "Bob")),
        startedAt = "2026-08-26T00:00:00Z",
        endedAt = "",
        winnerName = null,
        setup = ReplaySetup(
            seed = 41L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            players = listOf(
                ReplayPlayerSetup("p0", "Alice", Deck(cards = listOf("Forest"))),
                ReplayPlayerSetup("p1", "Bob", Deck(cards = listOf("Forest"))),
            ),
            seatRoster = emptyList(),
        ),
        // These v2 shadows prove the JDBC v3 envelope does not persist duplicate transition data.
        actions = listOf(PassPriority(EntityId("p0"))),
        canonicalRecords = records,
    )

    fun storeHarness(): Triple<JdbcReplayStore, MutableMap<String, GameReplayRow>, MutableList<GameReplayChunkRow>> {
        val parents = linkedMapOf<String, GameReplayRow>()
        val batches = mutableListOf<GameReplayChunkRow>()
        val replayRepository = mockk<GameReplayRepository>()
        val chunkRepository = mockk<GameReplayChunkRepository>()

        every { replayRepository.findByGameId(any()) } answers { parents[firstArg()] }
        every { replayRepository.save(any()) } answers {
            val row = firstArg<GameReplayRow>()
            row.copy(id = row.id ?: 1L).also { parents[it.gameId] = it }
        }
        every {
            replayRepository.updateCanonicalRecording(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val gameId = arg<String>(0)
            val current = requireNotNull(parents[gameId])
            if (current.status != ReplayStatus.IN_PROGRESS.name || current.canonicalRecordCount != arg<Int>(5)) {
                return@answers 0
            }
            parents[gameId] = current.copy(
                status = arg(1),
                resumeFingerprint = arg(2),
                frameCount = arg(3),
                canonicalRecordCount = arg(4),
                endedAt = arg(6),
                engineVersion = arg(7),
            )
            1
        }
        every { chunkRepository.save(any()) } answers {
            val row = firstArg<GameReplayChunkRow>().copy(id = (batches.size + 1).toLong())
            batches += row
            row
        }
        every { chunkRepository.findByReplayIdOrderByFirstRecordAsc(any()) } answers {
            val replayId = firstArg<Long>()
            batches.filter { it.replayId == replayId }.sortedBy { it.firstRecord }
        }
        every { chunkRepository.findFirstByReplayIdOrderByFirstRecordDesc(any()) } answers {
            val replayId = firstArg<Long>()
            batches.filter { it.replayId == replayId }.maxByOrNull { it.firstRecord }
        }
        return Triple(JdbcReplayStore(replayRepository, chunkRepository), parents, batches)
    }

    test("canonical checkpoints append only their new suffix and load as one exact stream") {
        val (store, parents, batches) = storeHarness()
        val records = canonicalPrefix("append")

        store.save(StoredReplay(replay("append", records.take(1)), ReplayStatus.IN_PROGRESS, resumeFingerprint = "a"))
        store.save(StoredReplay(replay("append", records), ReplayStatus.IN_PROGRESS, resumeFingerprint = "b"))

        batches.map { it.firstRecord } shouldContainExactly listOf(0, 1)
        batches.flatMap { ReplayCodec.decodeCanonicalRecords(it.data) } shouldBe records
        ReplayCodec.decode(requireNotNull(parents["append"]).data).let { envelope ->
            envelope.actions shouldBe emptyList()
            envelope.canonicalRecords shouldBe emptyList()
        }
        store.find("append")!!.replay.let { loaded ->
            loaded.canonicalRecords shouldBe records
            loaded.actions shouldBe listOf(PassPriority(EntityId("p0")))
        }
    }

    test("an append that changes the persisted prefix is rejected") {
        val (store, _, _) = storeHarness()
        val records = canonicalPrefix("stable")
        store.save(StoredReplay(replay("stable", records.take(1)), ReplayStatus.IN_PROGRESS))

        val changed = canonicalPrefix("stable").first().let { header ->
            (header as com.wingedsheep.engine.replay.CanonicalReplayHeader).copy(recordDigest = "changed")
        }
        shouldThrow<IllegalArgumentException> {
            store.save(StoredReplay(replay("stable", listOf(changed) + records.drop(1)), ReplayStatus.IN_PROGRESS))
        }
    }

    test("a corrupt canonical chunk is rejected at the storage boundary") {
        val (store, _, batches) = storeHarness()
        val records = canonicalPrefix("corrupt")
        store.save(StoredReplay(replay("corrupt", records), ReplayStatus.IN_PROGRESS))

        val decoded = ReplayCodec.decodeCanonicalRecords(batches.single().data)
        val header = decoded.first() as com.wingedsheep.engine.replay.CanonicalReplayHeader
        batches[0] = batches.single().copy(
            data = ReplayCodec.encodeCanonicalRecords(
                listOf(header.copy(recordDigest = "tampered")) + decoded.drop(1)
            )
        )

        store.find("corrupt") shouldBe null
    }

    test("a late checkpoint cannot downgrade a finished replay") {
        val (store, parents, batches) = storeHarness()
        val records = canonicalPrefix("finished")
        store.save(StoredReplay(replay("finished", records), ReplayStatus.FINISHED))
        val chunkCount = batches.size

        store.save(StoredReplay(replay("finished", records), ReplayStatus.IN_PROGRESS))

        parents.getValue("finished").status shouldBe ReplayStatus.FINISHED.name
        batches.size shouldBe chunkCount
    }
})
