import { useCallback, useEffect, useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import { autoSelectForPower, totalPowerOf } from '@/utils/tapForPower'

/**
 * Compact floating HUD bar for the "tap creatures with total power N" cost, shared by Crew N
 * (Vehicles, CR 702.122a) and Saddle N (Mounts, CR 702.171a).
 *
 * Creatures are picked on the battlefield, not in a list — the board is where their counters,
 * auras and combat state are readable. The bar carries only what the board can't show: distance
 * to N, which picks were going to attack (paying taps them out of combat, marked ⚔), and a
 * one-click pick that spends the creatures that couldn't attack anyway first.
 */
export function TapForPowerSelector() {
  const selection = useGameStore((state) => state.tapForPowerSelectionState)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const cancelSelection = useGameStore((state) => state.cancelTapForPowerSelection)
  const confirmSelection = useGameStore((state) => state.confirmTapForPowerSelection)
  const toggleCreature = useGameStore((state) => state.toggleTapForPowerCreature)
  const setCreatures = useGameStore((state) => state.setTapForPowerCreatures)

  const selectedPower = useMemo(
    () => (selection ? totalPowerOf(selection.validCreatures, selection.selectedCreatures) : 0),
    [selection]
  )

  const selectedCards = useMemo(() => {
    if (!selection) return []
    return selection.selectedCreatures
      .map((id) => selection.validCreatures.find((c) => c.entityId === id))
      .filter((c): c is NonNullable<typeof c> => c !== undefined)
  }, [selection])

  const requiredPower = selection?.requiredPower ?? 0
  const canConfirm = selection !== null && selectedPower >= requiredPower

  const autoPick = useCallback(() => {
    if (!selection) return
    setCreatures(autoSelectForPower(selection.validCreatures, selection.requiredPower))
  }, [selection, setCreatures])

  // Enter confirms, Escape cancels — this bar owns the whole interaction while it is open.
  useEffect(() => {
    if (!selection) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        cancelSelection(interactionEpoch)
      } else if (e.key === 'Enter' && canConfirm) {
        e.preventDefault()
        confirmSelection(interactionEpoch)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [selection, canConfirm, cancelSelection, confirmSelection, interactionEpoch])

  if (!selection) return null

  const { verb, sourceName, alreadySaddled } = selection
  const fillPercent = requiredPower > 0
    ? Math.min(100, (selectedPower / requiredPower) * 100)
    : 100

  return (
    <div style={styles.bar} role="group" aria-label={`${verb} ${sourceName}`}>
      <span style={styles.label}>
        {verb} <strong style={styles.sourceName}>{sourceName}</strong>
        {alreadySaddled && (
          <span
            style={styles.alreadyTag}
            title='Already saddled until end of turn — this activation only adds saddlers for "creatures that saddled it this turn" payoffs.'
          >
            already saddled
          </span>
        )}
      </span>

      <span style={styles.progressTrack} aria-hidden="true">
        <span
          style={{
            ...styles.progressFill,
            width: `${fillPercent}%`,
            backgroundColor: canConfirm ? '#4caf50' : '#ff9800',
          }}
        />
      </span>
      <span style={styles.powerInfo}>
        <strong style={{ color: canConfirm ? '#4caf50' : '#ff9800' }}>{selectedPower}</strong>
        <span style={styles.slash}>/</span>
        {requiredPower}
      </span>

      <span style={styles.divider} />

      {selectedCards.length === 0 ? (
        <span style={styles.emptyHint}>Click creatures to tap them</span>
      ) : (
        selectedCards.map((creature) => (
          <button
            key={creature.entityId}
            onClick={() => toggleCreature(creature.entityId)}
            style={{
              ...styles.chip,
              ...(creature.canAttack !== false ? styles.chipAttacker : styles.chipSpare),
            }}
            title={
              creature.canAttack !== false
                ? `${creature.name} can attack this turn — tapping it takes it out of combat. Click to unselect.`
                : `${creature.name} can't attack this turn, so tapping it costs you nothing. Click to unselect.`
            }
          >
            {creature.canAttack !== false && <span aria-hidden="true">⚔</span>}
            {creature.name}
            <span style={styles.chipPower}>{creature.power}</span>
            <span style={styles.chipRemove} aria-hidden="true">×</span>
          </button>
        ))
      )}

      <span style={styles.divider} />
      <button
        onClick={autoPick}
        style={styles.secondaryButton}
        title="Pick creatures that reach the requirement, spending the ones that can't attack first"
      >
        Auto
      </button>
      <button onClick={() => cancelSelection(interactionEpoch)} style={styles.cancelButton}>
        Cancel
      </button>
      <button
        onClick={() => confirmSelection(interactionEpoch)}
        disabled={!canConfirm}
        style={{
          ...styles.confirmButton,
          opacity: canConfirm ? 1 : 0.5,
          cursor: canConfirm ? 'pointer' : 'not-allowed',
        }}
      >
        {verb}
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
    maxWidth: '92vw',
    flexWrap: 'wrap',
    backgroundColor: 'rgba(20, 20, 40, 0.95)',
    border: '2px solid #4a4a6a',
    borderRadius: 10,
    boxShadow: '0 4px 20px rgba(0, 0, 0, 0.6)',
    zIndex: 1500,
  },
  label: {
    color: '#ccc',
    fontSize: 14,
    whiteSpace: 'nowrap',
  },
  sourceName: {
    color: '#fff',
  },
  alreadyTag: {
    marginLeft: 6,
    color: '#d99a4e',
    fontSize: 11,
  },
  progressTrack: {
    display: 'inline-block',
    width: 70,
    height: 6,
    borderRadius: 3,
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
    overflow: 'hidden',
  },
  progressFill: {
    display: 'block',
    height: '100%',
    borderRadius: 3,
    transition: 'width 120ms ease-out, background-color 120ms ease-out',
  },
  powerInfo: {
    color: '#aaa',
    fontSize: 14,
    whiteSpace: 'nowrap',
  },
  slash: {
    color: '#666',
    margin: '0 2px',
  },
  divider: {
    width: 1,
    height: 20,
    backgroundColor: '#4a4a6a',
  },
  emptyHint: {
    color: '#777',
    fontSize: 12,
    fontStyle: 'italic',
    whiteSpace: 'nowrap',
  },
  chip: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 5,
    padding: '3px 8px',
    fontSize: 12,
    color: '#eee',
    border: '1px solid',
    borderRadius: 12,
    cursor: 'pointer',
    fontFamily: 'inherit',
    whiteSpace: 'nowrap',
  },
  chipAttacker: {
    backgroundColor: 'rgba(255, 152, 0, 0.14)',
    borderColor: 'rgba(255, 152, 0, 0.55)',
  },
  chipSpare: {
    backgroundColor: 'rgba(76, 175, 80, 0.12)',
    borderColor: 'rgba(76, 175, 80, 0.45)',
  },
  chipPower: {
    color: '#bbb',
    fontVariantNumeric: 'tabular-nums',
  },
  chipRemove: {
    color: '#888',
    fontSize: 13,
    lineHeight: 1,
  },
  secondaryButton: {
    padding: '6px 12px',
    fontSize: 13,
    backgroundColor: '#333',
    color: '#ddd',
    border: '1px solid #4a4a6a',
    borderRadius: 6,
    cursor: 'pointer',
    fontFamily: 'inherit',
  },
  cancelButton: {
    padding: '6px 14px',
    fontSize: 13,
    backgroundColor: '#444',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
    fontFamily: 'inherit',
  },
  confirmButton: {
    padding: '6px 14px',
    fontSize: 13,
    backgroundColor: '#0066cc',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    cursor: 'pointer',
    fontFamily: 'inherit',
  },
}
