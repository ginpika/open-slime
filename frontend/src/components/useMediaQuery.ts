import { useMemo, useSyncExternalStore } from 'react'

export function useMediaQuery(query: string): boolean {
  const media = useMemo(() => window.matchMedia(query), [query])
  const subscribe = useMemo(() => (listener: () => void) => {
    media.addEventListener('change', listener)
    return () => media.removeEventListener('change', listener)
  }, [media])
  return useSyncExternalStore(subscribe, () => media.matches)
}
