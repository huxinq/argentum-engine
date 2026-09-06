import { useState, useMemo } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import type { BudgetModalDecision } from '@/types'
import { useResponsive } from '@/hooks/useResponsive.ts'
import { getCardImageUrl } from '@/utils/cardImages.ts'
import { DecisionCardPreview } from './DecisionComponents'
import styles from './DecisionUI.module.css'

/** Renders a single pawprint using mana-font, colored by fill state. */
function Paw({ filled, size = 24 }: { filled: boolean; size?: number }) {
  return (
    <i
      className="ms ms-paw"
      style={{
        fontSize: size,
        color: filled ? '#e8c56d' : 'rgba(255, 255, 255, 0.15)',
        transition: 'color 0.2s ease',
      }}
    />
  )
}

/** Renders a row of pawprint cost indicators for a mode. */
function PawCost({ cost, size = 18 }: { cost: number; size?: number }) {
  return (
    <span style={{ display: 'inline-flex', gap: 1 }}>
      {Array.from({ length: cost }, (_, j) => (
        <i
          key={j}
          className="ms ms-paw"
          style={{ fontSize: size, color: '#e8c56d' }}
        />
      ))}
    </span>
  )
}

/**
 * Budget modal decision UI for Bloomburrow Season cycle.
 * Shows all modes with +/- buttons and a pawprint budget bar.
 */
export function BudgetModalDecisionUI({
  decision,
}: {
  decision: BudgetModalDecision
}) {
  const [modeCounts, setModeCounts] = useState<number[]>(
    () => new Array(decision.modes.length).fill(0) as number[]
  )
  const [minimized, setMinimized] = useState(false)
  const [isHoveringSource, setIsHoveringSource] = useState(false)

  const submitBudgetModalDecision = useGameStore((s) => s.submitBudgetModalDecision)
  const gameState = useGameStore((s) => s.gameState)
  const responsive = useResponsive()

  const sourceCard = decision.context.sourceId
    ? gameState?.cards[decision.context.sourceId]
    : undefined
  const sourceCardName = decision.context.sourceName ?? sourceCard?.name
  const sourceCardImageUrl = sourceCard
    ? getCardImageUrl(sourceCard.name, sourceCard.imageUri)
    : undefined

  const totalSpent = useMemo(
    () => modeCounts.reduce((sum, count, i) => sum + count * decision.modes[i]!.cost, 0),
    [modeCounts, decision.modes]
  )
  const remaining = decision.budget - totalSpent

  const canAddMode = (modeIndex: number) => decision.modes[modeIndex]!.cost <= remaining
  const canRemoveMode = (modeIndex: number) => modeCounts[modeIndex]! > 0

  const addMode = (modeIndex: number) => {
    if (!canAddMode(modeIndex)) return
    setModeCounts((prev) => prev.map((c, i) => (i === modeIndex ? c + 1 : c)))
  }

  const removeMode = (modeIndex: number) => {
    if (!canRemoveMode(modeIndex)) return
    setModeCounts((prev) => prev.map((c, i) => (i === modeIndex ? c - 1 : c)))
  }

  const handleConfirm = () => {
    const selected: number[] = []
    for (let i = 0; i < modeCounts.length; i++) {
      for (let j = 0; j < modeCounts[i]!; j++) {
        selected.push(i)
      }
    }
    submitBudgetModalDecision(decision.id, selected)
  }

  if (minimized) {
    return (
      <button
        className={styles.floatingReturnButton}
        onClick={() => setMinimized(false)}
      >
        Return to {decision.prompt}
      </button>
    )
  }

  return (
    <div className={styles.overlay}>
      {/* Source card image */}
      {sourceCardImageUrl && (
        <img
          src={sourceCardImageUrl}
          alt={`Source: ${sourceCardName ?? 'card'}`}
          className={styles.bannerCardImage}
          onMouseEnter={() => setIsHoveringSource(true)}
          onMouseLeave={() => setIsHoveringSource(false)}
        />
      )}

      <h2 className={styles.title}>{decision.prompt}</h2>

      {/* Pawprint budget bar — filled paws drain right-to-left as budget is spent */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {Array.from({ length: decision.budget }, (_, i) => (
          <Paw key={i} filled={i < remaining} size={28} />
        ))}
      </div>

      {/* Mode list */}
      <div style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        width: '100%',
        maxWidth: 520,
      }}>
        {decision.modes.map((mode, i) => {
          const count = modeCounts[i]!
          const affordable = canAddMode(i)

          return (
            <div
              key={i}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '12px 16px',
                borderRadius: 'var(--radius-md)',
                background: count > 0
                  ? 'rgba(232, 197, 109, 0.1)'
                  : 'rgba(255, 255, 255, 0.04)',
                border: count > 0
                  ? '1px solid rgba(232, 197, 109, 0.35)'
                  : '1px solid rgba(255, 255, 255, 0.08)',
                transition: 'all 0.15s ease',
              }}
            >
              {/* Pawprint cost */}
              <PawCost cost={mode.cost} />

              {/* Separator */}
              <span style={{
                color: 'rgba(255, 255, 255, 0.2)',
                fontSize: 'var(--font-md)',
                userSelect: 'none',
              }}>
                —
              </span>

              {/* Description */}
              <div style={{
                flex: 1,
                color: affordable || count > 0
                  ? 'var(--text-primary)'
                  : 'var(--text-disabled)',
                fontSize: 'var(--font-md)',
                lineHeight: 1.4,
              }}>
                {mode.description}
              </div>

              {/* Count + buttons */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                <button
                  onClick={() => removeMode(i)}
                  disabled={!canRemoveMode(i)}
                  style={{
                    width: 30,
                    height: 30,
                    borderRadius: '50%',
                    border: 'none',
                    background: canRemoveMode(i)
                      ? 'rgba(255, 100, 100, 0.25)'
                      : 'rgba(255, 255, 255, 0.04)',
                    color: canRemoveMode(i) ? '#ff8888' : 'var(--text-disabled)',
                    cursor: canRemoveMode(i) ? 'pointer' : 'default',
                    fontSize: 18,
                    fontWeight: 700,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    lineHeight: 1,
                  }}
                >
                  -
                </button>

                <span style={{
                  minWidth: 24,
                  textAlign: 'center',
                  fontWeight: 700,
                  fontSize: 'var(--font-lg)',
                  color: count > 0 ? '#e8c56d' : 'var(--text-disabled)',
                }}>
                  {count}
                </span>

                <button
                  onClick={() => addMode(i)}
                  disabled={!affordable}
                  style={{
                    width: 30,
                    height: 30,
                    borderRadius: '50%',
                    border: 'none',
                    background: affordable
                      ? 'rgba(100, 200, 100, 0.25)'
                      : 'rgba(255, 255, 255, 0.04)',
                    color: affordable ? '#88cc88' : 'var(--text-disabled)',
                    cursor: affordable ? 'pointer' : 'default',
                    fontSize: 18,
                    fontWeight: 700,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    lineHeight: 1,
                  }}
                >
                  +
                </button>
              </div>
            </div>
          )
        })}
      </div>

      {/* Hover preview */}
      {isHoveringSource && sourceCardName && !responsive.isMobile && (
        <DecisionCardPreview cardName={sourceCardName} imageUri={sourceCard?.imageUri} />
      )}

      {/* Action buttons */}
      <div className={styles.optionButtonRow}>
        <button
          onClick={() => setMinimized(true)}
          className={styles.viewBattlefieldButton}
        >
          View Battlefield
        </button>
        <button
          onClick={handleConfirm}
          className={styles.confirmButton}
        >
          {totalSpent === 0 ? 'Skip' : `Confirm (${totalSpent}/${decision.budget})`}
        </button>
      </div>
    </div>
  )
}
