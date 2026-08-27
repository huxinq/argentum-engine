import type { CSSProperties } from 'react'
import { useGameStore } from '@/store/gameStore.ts'

/** Minimal live-host recovery surface. A policy fault is intentionally not rendered as GameOver. */
export function PolicyFaultPauseOverlay() {
  const pause = useGameStore((state) => state.policyFaultPause)
  const recover = useGameStore((state) => state.recoverPolicyFault)
  if (!pause) return null

  const choose = (recovery: 'RETRY' | 'CONCEDE') => {
    recover(pause.incidentId, recovery)
    // The authoritative server either sends PolicyFaultRecovered or an error. Do not invent a
    // local timeout/disposition while waiting for it.
  }

  return (
    <div style={backdrop} role="alertdialog" aria-modal="true" aria-label="Policy software paused the game">
      <div style={panel}>
        <h2 style={{ margin: 0 }}>Game paused: policy software fault</h2>
        <p style={copy}>
          No winner has been declared. The authoritative game state is preserved while an explicit
          recovery choice is made.
        </p>
        <p style={detail}>Incident {pause.incidentId} · {pause.code}</p>
        <div style={actions}>
          {pause.canRetry && (
            <button onClick={() => choose('RETRY')} style={primary}>Retry policy once</button>
          )}
          {pause.canConcede && (
            <button onClick={() => choose('CONCEDE')} style={danger}>
              Concede affected policy seat
            </button>
          )}
        </div>
        {!pause.canTransferControl && (
          <p style={note}>Per-seat control transfer is not available on this host; it is not approximated by sharing a player view.</p>
        )}
        <p style={note}>Recovery state is process-lifetime only. There is no automatic timeout or replacement policy.</p>
      </div>
    </div>
  )
}

const backdrop: CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1200, display: 'grid', placeItems: 'center',
  background: 'rgba(8, 10, 20, 0.78)', padding: 20,
}
const panel: CSSProperties = {
  width: 'min(520px, 100%)', borderRadius: 12, border: '1px solid #8f6b2d',
  background: '#1c1a17', color: '#f5eee1', padding: 24, boxShadow: '0 18px 60px rgba(0,0,0,.55)',
}
const copy: CSSProperties = { lineHeight: 1.45, marginBottom: 12 }
const detail: CSSProperties = { fontFamily: 'monospace', color: '#d7c49b', overflowWrap: 'anywhere' }
const actions: CSSProperties = { display: 'flex', gap: 10, flexWrap: 'wrap', margin: '20px 0 12px' }
const primary: CSSProperties = { background: '#315f9b', color: 'white', border: 0, borderRadius: 6, padding: '9px 13px', cursor: 'pointer' }
const danger: CSSProperties = { background: '#8b3030', color: 'white', border: 0, borderRadius: 6, padding: '9px 13px', cursor: 'pointer' }
const note: CSSProperties = { color: '#c4baa8', fontSize: 13, lineHeight: 1.35 }
