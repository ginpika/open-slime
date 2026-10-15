export type BrowserWheel = { x: number; y: number; deltaX: number; deltaY: number }

/** Capture wheel input only over the screenshot, coalescing events into serial browser operations. */
export function bindBrowserWheel(surface: HTMLElement, send: (wheel: BrowserWheel) => Promise<void>) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let pending = false, disposed = false
  let deltaX = 0, deltaY = 0, x = 640, y = 400
  const clamp = (value: number, max: number) => Math.max(-max, Math.min(max, value))
  const schedule = () => {
    if (!disposed && !pending && timer === undefined && (deltaX || deltaY)) timer = setTimeout(() => void flush(), 80)
  }
  const flush = async () => {
    timer = undefined
    if (disposed || pending || (!deltaX && !deltaY)) return
    const movement = { x, y, deltaX: clamp(deltaX, 2000), deltaY: clamp(deltaY, 2000) }
    deltaX -= movement.deltaX; deltaY -= movement.deltaY
    pending = true
    try { await send(movement) }
    catch { deltaX = 0; deltaY = 0 }
    finally { pending = false; schedule() }
  }
  const wheel = (event: WheelEvent) => {
    if (disposed || event.ctrlKey || !event.cancelable || !Number.isFinite(event.deltaX) || !Number.isFinite(event.deltaY)) return
    const bounds = surface.getBoundingClientRect()
    if (!bounds.width || !bounds.height) return
    event.preventDefault()
    // Images may be scaled by the responsive layout; Playwright uses the 1280 × 800 viewport.
    x = Math.max(0, Math.min(1279, Math.round((event.clientX - bounds.left) * 1280 / bounds.width)))
    y = Math.max(0, Math.min(799, Math.round((event.clientY - bounds.top) * 800 / bounds.height)))
    const unitX = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? bounds.width : 1
    const unitY = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? bounds.height : 1
    deltaX = clamp(deltaX + event.deltaX * unitX * 1280 / bounds.width, 8000)
    deltaY = clamp(deltaY + event.deltaY * unitY * 800 / bounds.height, 8000)
    schedule()
  }
  surface.addEventListener('wheel', wheel, { passive: false })
  return () => {
    disposed = true
    if (timer !== undefined) clearTimeout(timer)
    surface.removeEventListener('wheel', wheel)
  }
}
