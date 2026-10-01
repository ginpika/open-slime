import { useEffect, useState } from 'react'

/** 本地时间（HH:MM:SS）：Endfield 的 HUD 读数条用 */
export function useClock(): string {
  const [t, setT] = useState('--:--:--')
  useEffect(() => {
    const p = (n: number) => String(n).padStart(2, '0')
    const tick = () => {
      const d = new Date()
      setT(`${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`)
    }
    tick()
    const id = window.setInterval(tick, 1000)
    return () => window.clearInterval(id)
  }, [])
  return t
}
