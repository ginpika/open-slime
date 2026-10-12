export type SelectOption = { value: string; label: string }
export function filterSelectOptions(options: SelectOption[], query: string) {
  const needle = query.trim().toLocaleLowerCase()
  return options.filter(o => !needle || `${o.label} ${o.value}`.toLocaleLowerCase().includes(needle))
}
export function nextSelectValue(options: SelectOption[], value: string, key: string) {
  if (!options.length) return ''
  if (key === 'Home') return options[0].value
  if (key === 'End') return options[options.length - 1].value
  const index = Math.max(0, options.findIndex(o => o.value === value))
  return options[(index + (key === 'ArrowDown' ? 1 : -1) + options.length) % options.length].value
}
export function selectPosition(rect: { left: number; width: number; top: number; bottom: number }, viewport: { width: number; height: number }) {
  const width = Math.max(0, Math.min(Math.max(rect.width, 180), viewport.width - 16))
  const lower = Math.max(8, Math.min(rect.bottom + 6, viewport.height - 8))
  const upper = Math.max(8, Math.min(rect.top - 6, viewport.height - 8))
  const below = Math.max(0, viewport.height - lower - 8), above = Math.max(0, upper - 8)
  const flip = below < 220 && above > below
  return { left: Math.max(8, Math.min(rect.left, viewport.width - width - 8)), top: flip ? upper : lower, width, maxHeight: Math.min(320, flip ? above : below), above: flip }
}
