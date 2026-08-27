package com.wingedsheep.gameserver.handler

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.lobby.LobbyGameMode
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.lobby.TournamentLobby
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.InMemoryLobbyRepository
import com.wingedsheep.gameserver.session.SessionRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/** A policy-fault terminal path must not enter the FFA play-again/standings lifecycle. */
class FreeForAllPolicyFaultNoContestTest : FunSpec({
    test("held FFA no-contest preserves prior standings and does not count a game") {
        val lobbies = InMemoryLobbyRepository()
        val lobby = TournamentLobby(
            lobbyId = "ffa-policy-fault",
            setCodes = listOf("TST"),
            setNames = listOf("Test"),
            boosterGenerator = mockk<BoosterGenerator>(),
            format = TournamentFormat.PREMADE_DECKS,
            gameMode = LobbyGameMode.FREE_FOR_ALL,
        ).apply {
            ffaGameSessionId = "faulted-ffa-game"
            ffaGamesPlayed = 3
            ffaLastStandings = listOf(ServerMessage.FfaStandingInfo("alice", "Alice", 1))
        }
        lobbies.saveLobby(lobby)
        val handler = FreeForAllHandler(
            ctx = LobbySharedContext(
                sessionRegistry = mockk<SessionRegistry>(relaxed = true),
                gameRepository = mockk<GameRepository>(relaxed = true),
                lobbyRepository = lobbies,
                sender = mockk<MessageSender>(relaxed = true),
                aiGameManager = mockk<AiGameManager>(relaxed = true),
            ),
            cardRegistry = mockk<CardRegistry>(),
            printingRegistry = mockk<PrintingRegistry>(),
            tokenArtRegistry = mockk<TokenArtRegistry>(),
            gamePlayHandler = mockk<GamePlayHandler>(relaxed = true),
            gameProperties = GameProperties(),
            gameRepository = mockk<GameRepository>(relaxed = true),
        )

        handler.handlePolicyFaultNoContest(
            lobbyId = lobby.lobbyId,
            gameSessionId = "faulted-ffa-game",
            incidentId = "incident-ffa-1",
            code = "AI_POLICY_PROCESSING_EXCEPTION",
        )
        // Duplicate delivery cannot replace the original incident or create a result.
        handler.handlePolicyFaultNoContest(
            lobbyId = lobby.lobbyId,
            gameSessionId = "faulted-ffa-game",
            incidentId = "incident-ffa-1",
            code = "different-code",
        )

        lobby.ffaGameSessionId shouldBe null
        lobby.ffaHeldNoContestIncidentId shouldBe "incident-ffa-1"
        lobby.ffaHeldNoContestCode shouldBe "AI_POLICY_PROCESSING_EXCEPTION"
        lobby.ffaGamesPlayed shouldBe 3
        lobby.ffaLastStandings shouldBe listOf(ServerMessage.FfaStandingInfo("alice", "Alice", 1))
    }
})
