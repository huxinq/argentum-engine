import React from 'react'
import { createPortal } from 'react-dom'

export const START_YOUR_ENGINES_REMINDER =
  'Your speed increases by 1 the first time one or more opponents lose life during each of your turns, up to a maximum of 4. It never decreases. Max speed abilities are active at speed 4.'

const COLORS = ['#ffc94d', '#ffa22b', '#ff6b2b', '#ff3b30'] as const

/** Store-free rendering of authoritative public speed. It never derives rules state. */
export function SpeedGauge({ speed, compact = false }: { speed: number; compact?: boolean }) {
  const ref = React.useRef<HTMLSpanElement>(null)
  const [anchor, setAnchor] = React.useState<DOMRect | null>(null)
  if (speed <= 0) return null
  const level = Math.min(speed, 4)
  const color = COLORS[level - 1] ?? COLORS[3]
  return <span ref={ref} role="img" tabIndex={0} aria-label={`Speed ${level} of 4${level === 4 ? ', max speed' : ''}`}
    onMouseEnter={() => setAnchor(ref.current?.getBoundingClientRect() ?? null)} onMouseLeave={() => setAnchor(null)}
    onFocus={() => setAnchor(ref.current?.getBoundingClientRect() ?? null)} onBlur={() => setAnchor(null)}
    style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: compact ? '1px 5px' : '3px 8px', borderRadius: 5,
      cursor: 'help', color, background: 'linear-gradient(150deg,#26140af2,#0c0806f7)', border: `1px solid ${color}88`, fontSize: compact ? 9 : 11 }}>
    <span aria-hidden>⏱</span><span style={{ display: 'flex', alignItems: 'flex-end', gap: 2 }}>{COLORS.map((step, index) =>
      <i key={step} style={{ display: 'block', width: 4, height: 7 + index * 2, borderRadius: 1, background: index < level ? step : '#ffffff22' }} />)}</span>
    <b>{level === 4 ? 'MAX' : level}</b>
    {anchor && createPortal(<span role="tooltip" style={{ position: 'fixed', zIndex: 2500, width: 268,
      left: Math.max(8, Math.min(anchor.left + anchor.width / 2 - 134, window.innerWidth - 276)),
      ...(anchor.top > window.innerHeight / 2 ? { bottom: window.innerHeight - anchor.top + 6 } : { top: anchor.bottom + 6 }),
      padding: '10px 12px', borderRadius: 6, pointerEvents: 'none', background: '#120d09fa', border: `1px solid ${color}88`, color: '#eadfd2', boxShadow: '0 4px 18px #0009' }}>
      <b style={{ color }}>Start your engines! · Speed {level}/4</b><br />{START_YOUR_ENGINES_REMINDER}
    </span>, document.body)}
  </span>
}

export interface ReadOnlyTypedCard { types: readonly string[] }

/** Argentum's battlefield convention: nonlands in front, lands in back. */
export function partitionBattlefield<T extends ReadOnlyTypedCard>(cards: readonly T[]) {
  return {
    nonlands: cards.filter((card) => !card.types.includes('LAND')),
    lands: cards.filter((card) => card.types.includes('LAND')),
  }
}
