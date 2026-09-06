import { useState } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import type { ClientCard, EntityId } from '@/types'
import { ZoneType } from '@/types'
import {
  calculateFittingCardWidth,
  type ResponsiveSizes,
} from '@/hooks/useResponsive.ts'
import { DecisionCard, DecisionCardPreview } from './DecisionComponents'
import styles from './DecisionUI.module.css'

/**
 * Cross-zone selection overlay for the Craft activated-ability cost (CR 702.167a-b).
 *
 * Saheeli's Lattice and every other Craft card lets the player exile materials from a
 * **single combined pool** of permanents they control and cards in their graveyard. The
 * existing per-zone targeting overlays (battlefield / graveyard) can't show both at once,
 * so this overlay renders the two pools side-by-side and submits the chosen IDs through
 * the standard `confirmTargeting` pipeline (which routes them to
 * `ActivateAbility.costPayment.exiledCards`).
 *
 * Triggered when `targetingState.isCraftMaterialSelection` is set by the costPayment
 * phase in [pipelinePhases.ts]; the rest of the pipeline (priority release, mana payment
 * preview, activation submission) is unchanged.
 */
export function CraftMaterialOverlay({
  responsive,
}: {
  responsive: ResponsiveSizes
}) {
  const targetingState = useGameStore((s) => s.targetingState)
  const gameState = useGameStore((s) => s.gameState)
  const addTarget = useGameStore((s) => s.addTarget)
  const removeTarget = useGameStore((s) => s.removeTarget)
  const interactionEpoch = useGameStore((state) => state.interactionEpoch)
  const confirmTargeting = useGameStore((s) => s.confirmTargeting)
  const cancelTargeting = useGameStore((s) => s.cancelTargeting)

  const [hoveredCardId, setHoveredCardId] = useState<EntityId | null>(null)

  if (!targetingState || !targetingState.isCraftMaterialSelection) return null

  const { validTargets, selectedTargets, minTargets, maxTargets } = targetingState

  // Partition candidates by zone. Anything that isn't on the battlefield falls into the
  // graveyard bucket (the server only enumerates BF + GY for Craft, so this is safe).
  const battlefieldCards: { id: EntityId; card: ClientCard | undefined }[] = []
  const graveyardCards: { id: EntityId; card: ClientCard | undefined }[] = []
  for (const id of validTargets) {
    const card = gameState?.cards[id]
    if (card?.zone?.zoneType === ZoneType.BATTLEFIELD) {
      battlefieldCards.push({ id, card })
    } else {
      graveyardCards.push({ id, card })
    }
  }

  const selectedCount = selectedTargets.length
  const canConfirm = selectedCount >= minTargets && selectedCount <= maxTargets

  const toggle = (cardId: EntityId) => {
    if (selectedTargets.includes(cardId)) {
      removeTarget(cardId)
    } else if (selectedCount < maxTargets) {
      addTarget(cardId)
    }
  }

  const groups = [
    { label: 'Battlefield', cards: battlefieldCards },
    { label: 'Graveyard', cards: graveyardCards },
  ].filter((g) => g.cards.length > 0)

  const availableWidth =
    responsive.viewportWidth - responsive.containerPadding * 2 - 32
  const gap = responsive.isMobile ? 4 : 8
  const maxCardWidth = responsive.isMobile ? 90 : 130
  const maxCardsInAnyGroup = Math.max(...groups.map((g) => g.cards.length), 1)
  const cardWidth = calculateFittingCardWidth(
    maxCardsInAnyGroup,
    availableWidth,
    gap,
    maxCardWidth,
    45,
  )

  const hoveredCard = hoveredCardId ? gameState?.cards[hoveredCardId] : null
  const sourceCardName = targetingState.sourceCardName

  const selectionLabel =
    minTargets === maxTargets
      ? `Selected: ${selectedCount} / ${minTargets}`
      : `Selected: ${selectedCount} (${minTargets}+ required)`

  return (
    <div className={styles.overlay}>
      <h2 className={styles.title}>
        {targetingState.targetDescription ?? 'Choose Craft materials'}
      </h2>
      {sourceCardName && <p className={styles.sourceLabel}>{sourceCardName}</p>}
      <p className={styles.hint}>{selectionLabel}</p>

      <div className={styles.multiZoneContainer}>
        {groups.map((group) => (
          <div key={group.label} className={styles.zoneSection}>
            <div className={styles.zoneSectionHeader}>
              <span className={styles.zoneSectionLabel}>{group.label}</span>
              <span className={styles.zoneSectionCount}>
                {group.cards.length} card{group.cards.length !== 1 ? 's' : ''}
              </span>
            </div>
            <div className={styles.cardContainer} style={{ gap }}>
              {group.cards.map(({ id, card }) => {
                const cardName = card?.name ?? 'Unknown Card'
                return (
                  <DecisionCard
                    key={id}
                    cardId={id}
                    cardName={cardName}
                    imageUri={card?.imageUri}
                    isSelected={selectedTargets.includes(id)}
                    onClick={() => toggle(id)}
                    cardWidth={cardWidth}
                    onMouseEnter={() => setHoveredCardId(id)}
                    onMouseLeave={() => setHoveredCardId(null)}
                  />
                )
              })}
            </div>
          </div>
        ))}
      </div>

      <div className={styles.optionButtonRow}>
        <button onClick={() => cancelTargeting(interactionEpoch)} className={styles.viewBattlefieldButton}>
          Cancel
        </button>
        <button
          onClick={() => confirmTargeting(interactionEpoch)}
          disabled={!canConfirm}
          className={styles.confirmButton}
        >
          Confirm Selection ({selectedCount})
        </button>
      </div>

      {hoveredCard && !responsive.isMobile && (
        <DecisionCardPreview
          cardName={hoveredCard.name}
          imageUri={hoveredCard.imageUri}
        />
      )}
    </div>
  )
}
