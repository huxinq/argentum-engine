import { useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import { useViewingPlayer } from '@/store/selectors'
import { estimatedShortfall, getRemainingCostSymbols, parseManaCost } from '@/utils/manaCost'
import { ManaSymbol } from './ManaSymbols'

/**
 * Floating HUD bar for the Harmonize creature-tap step. The player may tap one creature
 * (selected directly on the battlefield) to reduce the generic harmonize cost by its power;
 * {X} is already expanded into the displayed cost. Tapping is optional — "Cast" is always
 * live; the reduced cost shown is the shared `utils/manaCost` estimate and the server, which
 * validates the tapped creature and the payment, has the final say.
 */
export function HarmonizeSelector() {
  const harmonizeSelectionState = useGameStore((state) => state.harmonizeSelectionState)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const cancelHarmonizeSelection = useGameStore((state) => state.cancelHarmonizeSelection)
  const confirmHarmonizeSelection = useGameStore((state) => state.confirmHarmonizeSelection)
  const viewingPlayer = useViewingPlayer()
  const manaPool = viewingPlayer?.manaPool

  const originalSymbols = useMemo(
    () => (harmonizeSelectionState ? parseManaCost(harmonizeSelectionState.manaCost) : []),
    [harmonizeSelectionState?.manaCost],
  )

  const reduction = useMemo(() => {
    if (!harmonizeSelectionState?.selectedCreature) return 0
    return (
      harmonizeSelectionState.validCreatures.find(
        (c) => c.entityId === harmonizeSelectionState.selectedCreature,
      )?.power ?? 0
    )
  }, [harmonizeSelectionState?.selectedCreature, harmonizeSelectionState?.validCreatures])

  const remainingSymbols = useMemo(
    () => getRemainingCostSymbols(originalSymbols, reduction),
    [originalSymbols, reduction],
  )

  // Conditional mana counts only where the server judged it eligible for this payment.
  const eligibleRestricted = harmonizeSelectionState?.actionInfo.eligibleRestrictedMana

  if (!harmonizeSelectionState) return null

  const { cardName, selectedCreature, actionInfo } = harmonizeSelectionState
  // The tapped creature pays with its power, not its mana ability.
  const shortfall = estimatedShortfall(
    remainingSymbols, manaPool, eligibleRestricted, actionInfo.availableManaSources,
    new Set(selectedCreature ? [selectedCreature] : []),
  )

  return (
    <div style={styles.bar}>
      <span style={styles.label}>
        Harmonize <strong>{cardName}</strong>
      </span>
      <span style={styles.divider} />
      <span style={styles.hint}>
        {selectedCreature ? `Tapping for ${reduction} generic` : 'Tap a creature to reduce (optional)'}
      </span>
      <span style={styles.divider} />
      <span style={styles.costLabel}>Cost:</span>
      <div style={styles.manaSymbols}>
        {originalSymbols.map((symbol, i) => (
          <ManaSymbol key={i} symbol={symbol} size={18} />
        ))}
      </div>
      {reduction > 0 && (
        <>
          <span style={styles.arrow}>→</span>
          <div style={styles.manaSymbols}>
            {remainingSymbols.length > 0 ? (
              remainingSymbols.map((symbol, i) => <ManaSymbol key={i} symbol={symbol} size={18} />)
            ) : (
              <span style={styles.freeCast}>Free!</span>
            )}
          </div>
        </>
      )}
      {shortfall > 0 && (
        <span style={styles.shortfall} title="An estimate — the server has the final say">
          may be {shortfall} short
        </span>
      )}
      <span style={styles.divider} />
      <button onClick={() => cancelHarmonizeSelection(interactionEpoch)} style={styles.cancelButton}>
        Cancel
      </button>
      <button onClick={() => confirmHarmonizeSelection(interactionEpoch)} style={styles.confirmButton}>
        Cast
      </button>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  bar: {
    position: 'absolute',
    bottom: 12,
    left: '50%',
    transform: 'translateX(-50%)',
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '10px 20px',
    backgroundColor: 'rgba(20, 20, 40, 0.95)',
    border: '2px solid #4a4a6a',
    borderRadius: 10,
    boxShadow: '0 4px 20px rgba(0, 0, 0, 0.6)',
    zIndex: 1500,
    whiteSpace: 'nowrap',
  },
  label: { color: '#ccc', fontSize: 14 },
  hint: { color: '#9a9', fontSize: 12 },
  divider: { width: 1, height: 20, backgroundColor: '#4a4a6a' },
  costLabel: { color: '#888', fontSize: 13 },
  manaSymbols: { display: 'flex', alignItems: 'center', gap: 3 },
  arrow: { color: '#666', fontSize: 14 },
  freeCast: { color: '#4caf50', fontWeight: 'bold', fontSize: 13 },
  shortfall: {
    color: '#e0a83a',
    fontSize: 12,
  },
  cancelButton: {
    padding: '6px 14px', fontSize: 13, backgroundColor: '#444', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer',
  },
  confirmButton: {
    padding: '6px 14px', fontSize: 13, backgroundColor: '#0066cc', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer',
  },
}
