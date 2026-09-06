import { useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import { useViewingPlayer } from '@/store/selectors'
import { estimatedShortfall, getRemainingCostSymbols, parseManaCost } from '@/utils/manaCost'
import { ManaSymbol } from './ManaSymbols'

/**
 * Compact floating HUD bar for a **tap-for-generic** payment — improvise (CR 702.126) or a
 * waterbend cost. Mirrors the Convoke selector but generic-only: each tapped permanent pays {1},
 * with no color choice. Permanents are selected directly on the battlefield (the server decides
 * which are eligible: artifacts for improvise, artifacts or creatures for waterbend); this bar
 * shows progress and confirm/cancel. Confirming with nothing selected pays the cost entirely with
 * mana, which is legal for both — the taps are always optional ("you *may* tap").
 *
 * The cost readout is the shared `utils/manaCost` estimate and never disables Confirm: the server
 * validates every tapped permanent and the payment itself, and says why when it declines.
 */
export function TapForGenericSelector() {
  const tapForGenericSelectionState = useGameStore((state) => state.tapForGenericSelectionState)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const cancelTapForGenericSelection = useGameStore((state) => state.cancelTapForGenericSelection)
  const confirmTapForGenericSelection = useGameStore((state) => state.confirmTapForGenericSelection)
  const viewingPlayer = useViewingPlayer()
  const manaPool = viewingPlayer?.manaPool

  const originalSymbols = useMemo(() => {
    if (!tapForGenericSelectionState) return []
    return parseManaCost(tapForGenericSelectionState.manaCost)
  }, [tapForGenericSelectionState?.manaCost])

  const remainingSymbols = useMemo(() => {
    if (!tapForGenericSelectionState) return []
    return getRemainingCostSymbols(originalSymbols, tapForGenericSelectionState.selectedPermanents.length)
  }, [originalSymbols, tapForGenericSelectionState?.selectedPermanents])

  // Conditional mana counts only where the server judged it eligible for this payment.
  const eligibleRestricted = tapForGenericSelectionState?.actionInfo.eligibleRestrictedMana

  const tappedIds = useMemo(
    () => new Set(tapForGenericSelectionState?.selectedPermanents ?? []),
    [tapForGenericSelectionState?.selectedPermanents],
  )

  if (!tapForGenericSelectionState) return null

  const { cardName, selectedPermanents, actionInfo, maxTaps, label } = tapForGenericSelectionState

  const shortfall = estimatedShortfall(
    remainingSymbols, manaPool, eligibleRestricted, actionInfo.availableManaSources, tappedIds,
  )

  return (
    <div style={styles.bar}>
      <span style={styles.label}>
        {/* Card name first, then the payment prompt in parentheses with the amount {N} rendered
            as a proper mana pip (not literal "{N}" text): e.g. "Ruinous Waterbending (waterbend
            {4})", "Ironheart, Clever Champion (improvise {4})". */}
        <strong>{cardName}</strong>
        <span style={{ display: 'inline-flex', alignItems: 'center', marginLeft: 6 }}>
          <span style={{ marginRight: 3 }}>({label}</span>
          <ManaSymbol symbol={String(maxTaps)} size={16} />
          <span>)</span>
        </span>
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
          remainingSymbols.map((symbol, i) => <ManaSymbol key={i} symbol={symbol} size={18} />)
        ) : (
          <span style={styles.freeCast}>Free!</span>
        )}
      </div>
      <span style={styles.count}>({selectedPermanents.length} tapped)</span>
      {shortfall > 0 && (
        <span style={styles.shortfall} title="An estimate — the server has the final say">
          may be {shortfall} short
        </span>
      )}
      <span style={styles.divider} />
      <button onClick={() => cancelTapForGenericSelection(interactionEpoch)} style={styles.cancelButton}>
        Cancel
      </button>
      <button onClick={() => confirmTapForGenericSelection(interactionEpoch)} style={styles.confirmButton}>
        Confirm
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
    backgroundColor: 'rgba(20, 30, 48, 0.95)',
    border: '2px solid #3a6a8a',
    borderRadius: 10,
    boxShadow: '0 4px 20px rgba(0, 0, 0, 0.6)',
    zIndex: 1500,
    whiteSpace: 'nowrap',
  },
  label: { color: '#cce', fontSize: 14 },
  divider: { width: 1, height: 20, backgroundColor: '#3a6a8a' },
  costLabel: { color: '#88a', fontSize: 13 },
  manaSymbols: { display: 'flex', alignItems: 'center', gap: 3 },
  arrow: { color: '#668', fontSize: 14 },
  freeCast: { color: '#4caf50', fontWeight: 'bold', fontSize: 13 },
  count: { color: '#668', fontSize: 12 },
  shortfall: {
    color: '#e0a83a',
    fontSize: 12,
  },
  cancelButton: {
    padding: '6px 14px', fontSize: 13, backgroundColor: '#444', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer',
  },
  confirmButton: {
    padding: '6px 14px', fontSize: 13, backgroundColor: '#0088cc', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer',
  },
}
