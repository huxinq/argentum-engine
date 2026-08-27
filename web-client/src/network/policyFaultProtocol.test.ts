import { describe, expect, it, vi } from 'vitest'
import { handleServerMessage, type MessageHandlers } from './messageHandlers'
import { createRecoverPolicyFaultMessage, type PolicyFaultPausedMessage, type PolicyFaultRecoveredMessage } from '@/types'

describe('policy fault protocol', () => {
  it('constructs an incident-bound recovery command', () => {
    expect(createRecoverPolicyFaultMessage('incident-17', 'RETRY')).toEqual({
      type: 'recoverPolicyFault', incidentId: 'incident-17', recovery: 'RETRY',
    })
  })

  it('dispatches both policy-fault server messages exhaustively', () => {
    const onPolicyFaultPaused = vi.fn()
    const onPolicyFaultRecovered = vi.fn()
    const handlers = { onPolicyFaultPaused, onPolicyFaultRecovered } as unknown as MessageHandlers
    const paused: PolicyFaultPausedMessage = {
      type: 'policyFaultPaused', gameId: 'game-1', incidentId: 'incident-17',
      failingSeatId: 'ai-1' as never, code: 'RESPONSIBLE_POLICY_UNAVAILABLE',
      canRetry: true, canTransferControl: false, canConcede: true,
      recoveryPersistence: 'PROCESS_LIFETIME',
    }
    const recovered: PolicyFaultRecoveredMessage = {
      type: 'policyFaultRecovered', gameId: 'game-1', incidentId: 'incident-17', recovery: 'RETRY',
    }

    handleServerMessage(paused, handlers)
    handleServerMessage(recovered, handlers)

    expect(onPolicyFaultPaused).toHaveBeenCalledWith(paused)
    expect(onPolicyFaultRecovered).toHaveBeenCalledWith(recovered)
  })
})
