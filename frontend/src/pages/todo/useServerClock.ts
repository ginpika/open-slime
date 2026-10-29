import { useEffect, useState } from 'react'

/** Keep ticking inside the component that displays time, never at the page root. */
export function useServerClock(serverNow: number, receivedAt: number, intervalMs: number) {
  const [tick, setTick] = useState(() => performance.now())
  useEffect(() => {
    if (!intervalMs) return
    const interval = window.setInterval(() => setTick(performance.now()), intervalMs)
    return () => window.clearInterval(interval)
  }, [intervalMs])
  return serverNow + (intervalMs ? Math.max(0, tick - receivedAt) : 0)
}
