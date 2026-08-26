package com.wingedsheep.gameserver.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway

/** Always-on real-PostgreSQL proof for canonical replay persistence. */
class ReplayChunkMigrationTest : FunSpec({
    test("V13 migrates and enforces ordered append-only replay chunks without Docker") {
        EmbeddedPostgres.start().use { postgres ->
            val dataSource = postgres.postgresDatabase
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO game_replays(" +
                            "id, game_id, data, status, canonical_record_count, ended_at" +
                            ") VALUES (13, 'canonical', 'IMMUTABLE_ENVELOPE', 'IN_PROGRESS', 1, now())"
                    )
                    statement.execute(
                        "INSERT INTO game_replay_chunks(replay_id, first_record, record_count, data) " +
                            "VALUES (13, 0, 1, 'HEADER'), (13, 1, 2, 'TRANSITIONS')"
                    )
                    statement.execute(
                        "UPDATE game_replays SET canonical_record_count = 3, frame_count = 3 " +
                            "WHERE game_id = 'canonical'"
                    )

                    statement.executeQuery(
                        "SELECT data, canonical_record_count FROM game_replays WHERE id = 13"
                    ).use { result ->
                        result.next() shouldBe true
                        result.getString(1) shouldBe "IMMUTABLE_ENVELOPE"
                        result.getInt(2) shouldBe 3
                        result.next() shouldBe false
                    }
                    statement.executeQuery(
                        "SELECT first_record, record_count FROM game_replay_chunks " +
                            "WHERE replay_id = 13 ORDER BY first_record"
                    ).use { result ->
                        result.next() shouldBe true
                        result.getInt(1) shouldBe 0
                        result.getInt(2) shouldBe 1
                        result.next() shouldBe true
                        result.getInt(1) shouldBe 1
                        result.getInt(2) shouldBe 2
                        result.next() shouldBe false
                    }

                    val duplicateRejected = runCatching {
                        statement.execute(
                            "INSERT INTO game_replay_chunks(replay_id, first_record, record_count, data) " +
                                "VALUES (13, 1, 1, 'DUPLICATE')"
                        )
                    }.isFailure
                    duplicateRejected shouldBe true

                    statement.execute("DELETE FROM game_replays WHERE id = 13")
                    statement.executeQuery(
                        "SELECT count(*) FROM game_replay_chunks WHERE replay_id = 13"
                    ).use { result ->
                        result.next() shouldBe true
                        result.getInt(1) shouldBe 0
                    }
                }
            }
        }
    }
})
