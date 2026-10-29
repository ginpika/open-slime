import { useEffect, useState } from 'react'
import { loginUrlOf } from '../../api/client'
import { todoError } from './format'

type Entry<T> = { data: T; receivedAt: number; revision: number }

/** Cache each query independently; switching scopes cancels only that resource's pending request. */
export function useTodoResource<T>(key: string | null, revision: number, fetcher: (signal: AbortSignal) => Promise<T>) {
  const [cache, setCache] = useState(() => new Map<string, Entry<T>>())
  const [settled, setSettled] = useState<{ request: string; error: string | null } | null>(null)
  const request = JSON.stringify([key, revision])
  const entry = key === null ? undefined : cache.get(key)
  useEffect(() => {
    if (key === null || cache.get(key)?.revision === revision) return
    const controller = new AbortController()
    fetcher(controller.signal).then(data => {
      if (controller.signal.aborted) return
      const receivedAt = performance.now()
      setCache(previous => {
        const next = new Map(previous)
        next.delete(key)
        next.set(key, { data, receivedAt, revision })
        // Bound the page-local cache when browsing many dates/projects.
        if (next.size > 12) next.delete(next.keys().next().value!)
        return next
      })
      setSettled({ request, error: null })
    }).catch(error => {
      if (controller.signal.aborted) return
      const login = loginUrlOf(error)
      if (login) window.location.assign(login)
      else setSettled({ request, error: todoError(error) })
    })
    return () => controller.abort()
  }, [key, revision, request, fetcher, cache])
  return { data: entry?.data ?? null, receivedAt: entry?.receivedAt ?? 0,
    loading: key !== null && entry?.revision !== revision && settled?.request !== request,
    error: key !== null && settled?.request === request ? settled.error : null }
}
