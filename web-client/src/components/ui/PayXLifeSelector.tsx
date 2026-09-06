import { useGameStore } from '@/store/gameStore.ts'

/**
 * Selector overlay for the "pay X life" variable additional cost
 * (AdditionalCost.PayXLife, e.g. Vicious Rivalry). The player picks X
 * (0..maxX, capped at their current life total) and that single X both
 * pays the life and drives the spell's effect. Unlike Blight X, there is
 * no follow-up target step — confirming submits the cast directly.
 */
export function PayXLifeSelector() {
  const state = useGameStore((s) => s.payXLifeSelectionState)
  const updateX = useGameStore((s) => s.updatePayXLifeX)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const cancel = useGameStore((s) => s.cancelPayXLifeSelection)
  const confirm = useGameStore((s) => s.confirmPayXLifeSelection)

  if (!state) return null

  const { cardName, maxX, selectedX } = state

  const handleSlider = (e: React.ChangeEvent<HTMLInputElement>) => {
    updateX(parseInt(e.target.value, 10))
  }
  const inc = () => updateX(selectedX + 1)
  const dec = () => updateX(selectedX - 1)

  return (
    // Floating panel — no full-screen backdrop, so the battlefield stays visible
    // behind the modal while the player decides on X.
    <div style={styles.anchor}>
      <div style={styles.container}>
        <h2 style={styles.title}>Pay X Life</h2>
        <p style={styles.cardName}>{cardName}</p>

        <div style={styles.valueDisplay}>
          <span style={styles.xLabel}>X =</span>
          <span style={styles.xValue}>{selectedX}</span>
        </div>

        <div style={styles.controls}>
          <button
            onClick={dec}
            disabled={selectedX <= 0}
            style={{
              ...styles.controlButton,
              opacity: selectedX <= 0 ? 0.5 : 1,
              cursor: selectedX <= 0 ? 'not-allowed' : 'pointer',
            }}
          >
            -
          </button>

          <input
            type="range"
            min={0}
            max={maxX}
            value={selectedX}
            onChange={handleSlider}
            style={styles.slider}
            disabled={maxX === 0}
          />

          <button
            onClick={inc}
            disabled={selectedX >= maxX}
            style={{
              ...styles.controlButton,
              opacity: selectedX >= maxX ? 0.5 : 1,
              cursor: selectedX >= maxX ? 'not-allowed' : 'pointer',
            }}
          >
            +
          </button>
        </div>

        <p style={styles.cap}>
          {maxX === 0
            ? 'You have no life to pay — X must be 0.'
            : `Cap: ${maxX} (your current life total)`}
        </p>

        <p style={styles.hint}>
          {selectedX > 0
            ? `You'll pay ${selectedX} life as an additional cost.`
            : 'No life will be paid.'}
        </p>

        <div style={styles.buttonRow}>
          <button onClick={() => cancel(interactionEpoch)} style={styles.cancelButton}>
            Cancel
          </button>
          <button onClick={() => confirm(interactionEpoch)} style={styles.confirmButton}>
            Cast
          </button>
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  // Anchor pins the floating panel to the bottom-center of the viewport without
  // intercepting clicks elsewhere — players can still see and inspect their
  // battlefield while choosing X.
  anchor: {
    position: 'fixed',
    left: '50%',
    bottom: 32,
    transform: 'translateX(-50%)',
    zIndex: 1500,
    pointerEvents: 'none',
  },
  container: {
    backgroundColor: 'rgba(26, 26, 46, 0.96)',
    borderRadius: 12,
    padding: 20,
    minWidth: 360,
    maxWidth: 480,
    border: '2px solid #4a4a6a',
    boxShadow: '0 8px 32px rgba(0, 0, 0, 0.6)',
    backdropFilter: 'blur(6px)',
    pointerEvents: 'auto',
  },
  title: {
    margin: '0 0 8px 0',
    color: '#fff',
    fontSize: 20,
    textAlign: 'center',
  },
  cardName: {
    margin: '0 0 24px 0',
    color: '#aaa',
    fontSize: 16,
    textAlign: 'center',
    fontStyle: 'italic',
  },
  valueDisplay: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    marginBottom: 16,
  },
  xLabel: {
    color: '#888',
    fontSize: 24,
    fontWeight: 'bold',
  },
  xValue: {
    color: '#ffcc00',
    fontSize: 48,
    fontWeight: 'bold',
    minWidth: 60,
    textAlign: 'center',
  },
  controls: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    marginBottom: 8,
  },
  controlButton: {
    width: 40,
    height: 40,
    borderRadius: 20,
    border: 'none',
    backgroundColor: '#333',
    color: '#fff',
    fontSize: 24,
    fontWeight: 'bold',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  slider: {
    flex: 1,
    height: 8,
    appearance: 'none',
    backgroundColor: '#333',
    borderRadius: 4,
    cursor: 'pointer',
  },
  cap: {
    margin: '0 0 12px 0',
    color: '#888',
    fontSize: 13,
    textAlign: 'center',
  },
  hint: {
    margin: '0 0 24px 0',
    color: '#bbb',
    fontSize: 13,
    textAlign: 'center',
    fontStyle: 'italic',
  },
  buttonRow: {
    display: 'flex',
    gap: 12,
    justifyContent: 'center',
  },
  cancelButton: {
    padding: '10px 24px',
    fontSize: 16,
    backgroundColor: '#444',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
  },
  confirmButton: {
    padding: '10px 24px',
    fontSize: 16,
    backgroundColor: '#0066cc',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
  },
}
