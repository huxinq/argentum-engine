import { useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import { useViewingPlayer } from '@/store/selectors'
import {
  estimatedShortfall,
  getRemainingCostAfterConvoke,
  parseManaCost,
} from '@/utils/manaCost'
import { ManaSymbol } from './ManaSymbols'

/**
 * Compact floating HUD bar for convoke selection.
 * Shows mana cost progress and confirm/cancel buttons while
 * creatures are selected directly on the battlefield.
 *
 * The cost arithmetic here is a *readout*, not a rule: the remaining-cost and shortfall numbers
 * come from the shared `utils/manaCost` estimate, and the Cast button is never disabled on them.
 * The server decides whether the selection is payable and rejects one that isn't with a message
 * naming the creature (see `AlternativePaymentHandler.validateForSpell`).
 */
export function ConvokeSelector() {
  const convokeSelectionState = useGameStore((state) => state.convokeSelectionState)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const cancelConvokeSelection = useGameStore((state) => state.cancelConvokeSelection)
  const confirmConvokeSelection = useGameStore((state) => state.confirmConvokeSelection)
  const viewingPlayer = useViewingPlayer()
  const manaPool = viewingPlayer?.manaPool

  const originalSymbols = useMemo(() => {
    if (!convokeSelectionState) return []
    return parseManaCost(convokeSelectionState.manaCost)
  }, [convokeSelectionState?.manaCost])

  const remainingSymbols = useMemo(() => {
    if (!convokeSelectionState) return []
    const convoked: Record<string, { color: string | null }> = {}
    for (const c of convokeSelectionState.selectedCreatures) convoked[c.entityId] = { color: c.payingColor }
    return getRemainingCostAfterConvoke(originalSymbols, convoked)
  }, [originalSymbols, convokeSelectionState?.selectedCreatures])

  // Conditional mana ("spend this mana only to …") counts only when the server flagged it
  // eligible for this exact cast — e.g. Ashling, Rimebound's mana on an MV4+ spell.
  const eligibleRestricted = convokeSelectionState?.actionInfo.eligibleRestrictedMana

  const convokedIds = useMemo(
    () => new Set(convokeSelectionState?.selectedCreatures.map(c => c.entityId) ?? []),
    [convokeSelectionState?.selectedCreatures]
  )

  if (!convokeSelectionState) return null

  const { cardName, selectedCreatures, actionInfo } = convokeSelectionState
  const isAbility = actionInfo.action.type === 'ActivateAbility'

  const shortfall = estimatedShortfall(
    remainingSymbols, manaPool, eligibleRestricted, actionInfo.availableManaSources, convokedIds
  )

  return (
    <div style={styles.bar}>
      <span style={styles.label}>
        {isAbility ? 'Tap creatures for' : 'Convoke'} <strong>{cardName}</strong>
      </span>
      <span style={styles.divider} />
      <span style={styles.costLabel}>Cost:</span>
      <div style={styles.manaSymbols}>
        {originalSymbols.map((symbol, i) => (
          <ManaSymbol key={i} symbol={symbol} size={18} />
        ))}
      </div>
      <span style={styles.arrow}>→</span>
      <div style={styles.manaSymbols}>
        {remainingSymbols.length > 0 ? (
          remainingSymbols.map((symbol, i) => (
            <ManaSymbol key={i} symbol={symbol} size={18} />
          ))
        ) : (
          <span style={styles.freeCast}>Free!</span>
        )}
      </div>
      <span style={styles.count}>
        ({selectedCreatures.length} tapped)
      </span>
      {shortfall > 0 && (
        <span style={styles.shortfall} title="An estimate — the server has the final say">
          may be {shortfall} short
        </span>
      )}
      <span style={styles.divider} />
      <button onClick={() => cancelConvokeSelection(interactionEpoch)} style={styles.cancelButton}>
        Cancel
      </button>
      <button onClick={() => confirmConvokeSelection(interactionEpoch)} style={styles.confirmButton}>
        {isAbility ? 'Activate' : 'Cast'}
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
  label: {
    color: '#ccc',
    fontSize: 14,
  },
  divider: {
    width: 1,
    height: 20,
    backgroundColor: '#4a4a6a',
  },
  costLabel: {
    color: '#888',
    fontSize: 13,
  },
  manaSymbols: {
    display: 'flex',
    alignItems: 'center',
    gap: 3,
  },
  arrow: {
    color: '#666',
    fontSize: 14,
  },
  freeCast: {
    color: '#4caf50',
    fontWeight: 'bold',
    fontSize: 13,
  },
  count: {
    color: '#666',
    fontSize: 12,
  },
  shortfall: {
    color: '#e0a83a',
    fontSize: 12,
  },
  cancelButton: {
    padding: '6px 14px',
    fontSize: 13,
    backgroundColor: '#444',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
  },
  confirmButton: {
    padding: '6px 14px',
    fontSize: 13,
    backgroundColor: '#0066cc',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
  },
}
