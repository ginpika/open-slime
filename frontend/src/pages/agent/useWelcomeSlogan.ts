import { useCallback, useEffect, useRef, useState } from 'react'

const FALLBACK = '今天，一起做点什么？'

/** Runtime JSON lets the welcome copy change without modifying application code. */
export function useWelcomeSlogan() {
  const choices = useRef<string[]>([FALLBACK])
  const [slogan, setSlogan] = useState(FALLBACK)
  const randomize = useCallback(() => {
    setSlogan(previous => {
      const candidates = choices.current.filter(text => text !== previous)
      const pool = candidates.length ? candidates : choices.current
      return pool[Math.floor(Math.random() * pool.length)] ?? FALLBACK
    })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void fetch(`${import.meta.env.BASE_URL}config/welcome.json`, { cache: 'no-store', signal: controller.signal })
      .then(response => {
        if (!response.ok) throw new Error('Welcome configuration unavailable')
        return response.json() as Promise<{ slogans?: unknown }>
      })
      .then(config => {
        if (controller.signal.aborted || !Array.isArray(config?.slogans)) return
        const valid = [...new Set(config.slogans.filter((item): item is string => typeof item === 'string')
          .map(text => text.trim()).filter(Boolean))]
        if (!valid.length) return
        choices.current = valid
        setSlogan(valid[Math.floor(Math.random() * valid.length)])
      })
      .catch(() => { /* Keep the welcome usable if the file is missing or malformed. */ })
    return () => controller.abort()
  }, [])

  return { slogan, randomize }
}
