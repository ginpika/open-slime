import { useCallback, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import TocRail, { type TocItem } from './TocRail'

/** The reserved right column keeps the directory fixed without covering the settings. */
export default function SettingsDirectory({ contentRef, items }: { contentRef: RefObject<HTMLDivElement | null>; items: TocItem[] }) {
  const slotRef = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null)
  const scroller = useCallback(() => contentRef.current?.closest('main') ?? null, [contentRef])
  useLayoutEffect(() => {
    const content = contentRef.current
    const slot = slotRef.current
    const main = content?.closest('main')
    if (!content || !main || !slot) return
    const measure = () => {
      const rect = slot.getBoundingClientRect()
      const bounds = main.getBoundingClientRect()
      const left = rect.left
      const width = rect.width
      const top = Math.max(64, bounds.top + 96)
      setPosition({ left, top, width, maxHeight: Math.max(160, Math.min(bounds.bottom, window.innerHeight) - top - 24) })
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(slot); observer.observe(main)
    window.addEventListener('resize', measure)
    return () => { observer.disconnect(); window.removeEventListener('resize', measure) }
  }, [contentRef])
  // Portal avoids transformed and clipped theme cards changing the fixed positioning reference.
  return <div ref={slotRef} className="min-w-0">
    {position && createPortal(<aside className="scrollbar-slim fixed z-20 overflow-y-auto" style={position}>
    <TocRail items={items} label="设置目录" scroller={scroller} expanded />
    </aside>, document.body)}
  </div>
}
