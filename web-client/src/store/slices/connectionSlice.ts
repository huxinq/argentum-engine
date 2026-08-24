/**
 * Connection slice - handles WebSocket connection state and authentication.
 */
import type { SliceCreator, EntityId } from './types'
import type { ConnectionStatus } from '@/network/websocket.ts'
import type { AvailableSet } from '@/types'
import { GameWebSocket, getWebSocketUrl } from '@/network/websocket.ts'
import { handleServerMessage, createLoggingHandlers } from '@/network/messageHandlers.ts'
import { createConnectMessage, ErrorCode } from '@/types'
import {
  getWebSocket,
  setWebSocket,
  setReauthHandler,
  clearLobbyId,
  clearDeckState,
} from './shared'
import { createMessageHandlers } from './handlers'
import { useAuthStore } from '@/store/authStore'

export interface ConnectionSliceState {
  connectionStatus: ConnectionStatus
  playerId: EntityId | null
  sessionId: string | null
  pendingTournamentId: string | null
  pendingSpectateGameId: string | null
  aiEnabled: boolean
  aiMode: 'engine' | 'llm' | 'search-teacher'
  availableSets: readonly AvailableSet[]
  onlinePlayers: number | null
  /**
   * True when the server reported this socket's session was taken over by another
   * tab/device. Auto-reconnect is stopped; the user reclaims via the takeover overlay.
   */
  sessionReplaced: boolean
}

export interface ConnectionSliceActions {
  /**
   * Connect to the server. `spectator: true` opens an isolated, ephemeral session — it does NOT
   * reuse or overwrite the stored token/name — so a spectate deep-link in a second tab can't
   * collide with (or clobber) the user's real identity, and each spectate gets a fresh session
   * instead of the server restoring a previous (now-finished) spectating game.
   */
  connect: (playerName: string, options?: { spectator?: boolean }) => void
  disconnect: () => void
  setPendingTournamentId: (lobbyId: string | null) => void
  setPendingSpectateGameId: (gameSessionId: string | null) => void
}

export type ConnectionSlice = ConnectionSliceState & ConnectionSliceActions

export const createConnectionSlice: SliceCreator<ConnectionSlice> = (set, get) => ({
  // Initial state
  connectionStatus: 'disconnected' as ConnectionStatus,
  playerId: null,
  sessionId: null,
  pendingTournamentId: null,
  pendingSpectateGameId: null,
  aiEnabled: false,
  aiMode: 'engine',
  availableSets: [],
  onlinePlayers: null,
  sessionReplaced: false,

  // Actions
  connect: (playerName, options) => {
    const spectator = options?.spectator === true
    const { connectionStatus } = get()
    if (connectionStatus === 'connecting' || connectionStatus === 'connected') {
      return
    }

    set({ sessionReplaced: false })

    const existingWs = getWebSocket()
    if (existingWs) {
      existingWs.disconnect()
    }

    // Store player name for reconnection — but never for an ephemeral spectator session, which
    // must not clobber the user's real identity.
    if (!spectator) {
      localStorage.setItem('argentum-player-name', playerName)
    }

    // Build message handlers from the full store
    const handlers = createMessageHandlers(set, get)
    const wrappedHandlers = import.meta.env.DEV
      ? createLoggingHandlers(handlers)
      : handlers

    // Sends the connect (auth) message over the current socket. Used on every (re)open,
    // and registered as the re-auth handler so a server NOT_CONNECTED error (server no
    // longer recognizes this socket, e.g. after a restart) recovers automatically.
    const sendAuth = () => {
      // Check URL ?token= param first (for dev scenario links), then localStorage
      const urlToken = new URLSearchParams(window.location.search).get('token')
      if (urlToken) {
        localStorage.setItem('argentum-token', urlToken)
      }
      // Spectator sessions connect tokenless (fresh identity every time) so a new spectate tab
      // never reconnects as an existing identity and gets its old spectating game restored.
      const token = spectator ? undefined : (urlToken ?? localStorage.getItem('argentum-token') ?? undefined)
      // When signed in, the account's profile display name is the source of truth for the player's
      // name — prefer it over a (possibly stale) locally-stored name so games show the profile name.
      // The server is authoritative and re-applies this too, but sending it keeps the local UI aligned.
      const accountName = useAuthStore.getState().user?.displayName?.trim() || undefined
      const connectName = spectator
        ? playerName
        : (accountName ?? localStorage.getItem('argentum-player-name') ?? playerName)
      // Durable account token (magic-link login) links this session to the account for stats.
      const authToken = spectator ? undefined : (localStorage.getItem('argentum-auth') ?? undefined)
      getWebSocket()?.send(createConnectMessage(connectName, token, authToken))
    }

    const ws = new GameWebSocket({
      url: getWebSocketUrl(),
      onMessage: (msg) => handleServerMessage(msg, wrappedHandlers),
      onStatusChange: (status) => {
        set({ connectionStatus: status })
        if (status === 'connected' && getWebSocket()) {
          sendAuth()
        }
      },
      onError: () => {
        get().setError({
          code: ErrorCode.INTERNAL_ERROR,
          message: 'WebSocket connection error',
          timestamp: Date.now(),
        })
      },
    })

    setWebSocket(ws)
    setReauthHandler(sendAuth)
    ws.connect()
  },

  disconnect: () => {
    const ws = getWebSocket()
    ws?.disconnect()
    setWebSocket(null)
    setReauthHandler(null)
    localStorage.removeItem('argentum-token')
    localStorage.removeItem('argentum-player-name')
    clearLobbyId()
    clearDeckState()
    set({
      sessionReplaced: false,
      connectionStatus: 'disconnected',
      playerId: null,
      sessionId: null,
      pendingTournamentId: null,
      pendingSpectateGameId: null,
      opponentName: null,
      gameState: null,
      legalActions: [],
      pendingDecision: null,
      mulliganState: null,
      waitingForOpponentMulligan: false,
      gameOverState: null,
      deckBuildingState: null,
      lobbyState: null,
      tournamentState: null,
    })
  },

  setPendingTournamentId: (lobbyId) => {
    set({ pendingTournamentId: lobbyId })
  },

  setPendingSpectateGameId: (gameSessionId) => {
    set({ pendingSpectateGameId: gameSessionId })
  },
})
