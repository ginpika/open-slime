import type { PersonalFocus, TodoDayWork } from '../../api/todo'

export type WorkBar = { focus: PersonalFocus; start: number; end: number; order: number; left: number; width: number }
export type WorkRow = { id: string; title: string; projectName: string | null; bars: WorkBar[]; total: number; completedAt: number | null; firstAt: number }

/** Use real elapsed milliseconds for geometry, including 23/25-hour days. Keep separate sessions separate. */
export function dayTimeline(day: TodoDayWork, now: number) {
  const span = day.rangeEnd - day.rangeStart
  const at = (time: number) => Math.max(0, Math.min(100, (time - day.rangeStart) / span * 100))
  const bars: WorkBar[] = []
  const rows = new Map<string, WorkRow>()
  const items = new Map((day.items ?? []).map(t => [t.id, t]))
  for (const focus of [...day.focus].sort((a, b) => a.startedAt - b.startedAt || a.id.localeCompare(b.id))) {
    const start = Math.max(day.rangeStart, focus.startedAt), end = Math.min(day.rangeEnd, focus.endedAt ?? now, now)
    if (end < start || (end === start && focus.endedAt !== null) || start >= day.rangeEnd) continue
    const bar = { focus, start, end, order: bars.length + 1, left: at(start), width: (end - start) / span * 100 }
    bars.push(bar)
    const item = items.get(focus.todoId)
    const row = rows.get(focus.todoId) ?? { id: focus.todoId, title: item?.title ?? focus.title, projectName: item?.projectName ?? focus.projectName, bars: [], total: 0, completedAt: null, firstAt: start }
    row.bars.push(bar); row.total += end - start; rows.set(row.id, row)
  }
  for (const item of items.values()) {
    if (item.state !== 'done' || item.completedAt === null || item.completedAt < day.rangeStart || item.completedAt >= day.rangeEnd || item.completedAt > now) continue
    const row = rows.get(item.id) ?? { id: item.id, title: item.title, projectName: item.projectName, bars: [], total: 0, completedAt: null, firstAt: item.completedAt }
    row.completedAt = item.completedAt; row.firstAt = Math.min(row.firstAt, item.completedAt); rows.set(row.id, row)
  }
  const total = bars.reduce((sum, bar) => sum + bar.end - bar.start, 0)
  const elapsed = Math.max(0, Math.min(now, day.rangeEnd) - day.rangeStart)
  return { bars, rows: [...rows.values()].sort((a, b) => a.firstAt - b.firstAt || a.id.localeCompare(b.id)), total, elapsed, nowPercent: at(now), at }
}

export function shiftDay(day: string, delta: number) {
  const d = new Date(`${day}T12:00:00Z`)
  d.setUTCDate(d.getUTCDate() + delta)
  return d.toISOString().slice(0, 10)
}
