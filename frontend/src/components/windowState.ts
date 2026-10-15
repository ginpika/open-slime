import { useMemo, useSyncExternalStore, type Dispatch, type SetStateAction } from 'react'

type Snapshot<T> = { value: T; dirty: boolean }
type Cell<T> = { snapshot: Snapshot<T>; baseline: T; initial: T; generation: number; listeners: Set<() => void> }
// Deliberately memory-only: credentials and private drafts never enter Web Storage.
const cells = new Map<string, Cell<unknown>>()
let unloadInstalled = false

/** Drafts contain plain form data; reverting a field should also clear its dirty flag. */
function sameDraft(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false
  if (a instanceof Set || b instanceof Set) {
    return a instanceof Set && b instanceof Set && a.size === b.size && [...a].every(value => b.has(value))
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((value, i) => sameDraft(value, b[i]))
  }
  if (Object.getPrototypeOf(a) !== Object.prototype || Object.getPrototypeOf(b) !== Object.prototype) return false
  const left = a as Record<string, unknown>, right = b as Record<string, unknown>
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && sameDraft(left[key], right[key]))
}

function cellFor<T>(key: string, initial: T | (() => T)): Cell<T> {
  if (!unloadInstalled && typeof window !== 'undefined') {
    window.addEventListener('beforeunload', event => {
      if (![...cells.values()].some(cell => cell.snapshot.dirty)) return
      event.preventDefault()
      event.returnValue = ''
    })
    unloadInstalled = true
  }
  const existing = cells.get(key)
  if (existing) return existing as Cell<T>
  const value = typeof initial === 'function' ? (initial as () => T)() : initial
  const cell: Cell<T> = { snapshot: { value, dirty: false }, baseline: value, initial: value, generation: 0, listeners: new Set() }
  cells.set(key, cell as Cell<unknown>)
  return cell
}

function publish<T>(cell: Cell<T>, value: T, dirty: boolean) {
  if (Object.is(value, cell.snapshot.value) && dirty === cell.snapshot.dirty) return
  cell.snapshot = { value, dirty }
  cell.listeners.forEach(listener => listener())
}

function useWindowState<T>(key: string, initial: T | (() => T), trackDirty: boolean) {
  const cell = cellFor(key, initial)
  const generation = cell.generation
  const controls = useMemo(() => ({
    subscribe(listener: () => void) { cell.listeners.add(listener); return () => { cell.listeners.delete(listener) } },
    read: () => cell.snapshot,
    set: ((action: SetStateAction<T>) => {
      if (generation !== cell.generation) return
      const value = typeof action === 'function' ? (action as (previous: T) => T)(cell.snapshot.value) : action
      if (!Object.is(value, cell.snapshot.value)) publish(cell, value, trackDirty && !sameDraft(value, cell.baseline))
    }) as Dispatch<SetStateAction<T>>,
    // Refresh the server baseline without overwriting the user's pending edits.
    initialize(value: T) {
      if (generation !== cell.generation) return
      cell.baseline = value
      if (!cell.snapshot.dirty || sameDraft(cell.snapshot.value, value)) publish(cell, value, false)
    },
    // Edits made while a save is in flight belong to the next draft, not this response.
    accept(value: T, submitted: T = cell.snapshot.value) {
      if (generation !== cell.generation) return
      cell.baseline = value
      if (sameDraft(cell.snapshot.value, submitted)) publish(cell, value, false)
      else publish(cell, cell.snapshot.value, trackDirty && !sameDraft(cell.snapshot.value, value))
    },
    discard() { if (generation === cell.generation) publish(cell, cell.baseline, false) },
    // A server-assigned resource id must also carry text typed while creation was pending.
    moveTo(nextKey: string) {
      if (generation !== cell.generation) return
      const next = cellFor(nextKey, cell.baseline)
      if (next === cell || next.snapshot.dirty) return
      next.baseline = cell.baseline
      publish(next, cell.snapshot.value, cell.snapshot.dirty)
      publish(cell, cell.baseline, false)
    },
  }), [cell, generation, trackDirty])
  const snapshot = useSyncExternalStore(controls.subscribe, controls.read)
  return [snapshot.value, controls.set, { ...controls, dirty: snapshot.dirty }] as const
}

/** Key drafts by resource, so navigating between objects never mixes their edits. */
export function useDraftState<T>(key: string, initial: T | (() => T)) {
  return useWindowState(key, initial, true)
}

/** UI selection is retained separately and never produces an unsaved-content warning. */
export function useRememberedState<T>(key: string, initial: T | (() => T)) {
  const [value, set] = useWindowState(key, initial, false)
  return [value, set] as const
}

/** Explicit deletion/reset also clears local drafts for the deleted resources. */
export function resetWindowStates(...prefixes: string[]) {
  for (const [key, cell] of cells) {
    if (!prefixes.some(prefix => prefix.endsWith(':') ? key.startsWith(prefix) : key === prefix)) continue
    cell.generation++
    cell.baseline = cell.initial
    // Notify even if the value matches, so hooks obtain the new generation's controls.
    cell.snapshot = { value: cell.initial, dirty: false }
    cell.listeners.forEach(listener => listener())
  }
}
