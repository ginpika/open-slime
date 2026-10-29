import { ApiError } from '../../api/client'
import type { PersonalFocus, TodoItem } from '../../api/todo'

export const isOpen = (todo: TodoItem) => todo.state !== 'done' && todo.state !== 'dropped'
export function duration(millis: number): string {
  const minutes = Math.floor(Math.max(0, millis) / 60000)
  if (!minutes) return millis > 0 ? '不到 1 分钟' : '0 分钟'
  return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时${minutes % 60 ? ` ${minutes % 60} 分` : ''}` : `${minutes} 分钟`
}
export function timer(millis: number): string {
  const seconds = Math.floor(Math.max(0, millis) / 1000)
  return [Math.floor(seconds / 3600), Math.floor(seconds % 3600 / 60), seconds % 60].map(n => String(n).padStart(2, '0')).join(':')
}
export function focusMillis(focus: PersonalFocus, now: number, from = 0, to = Infinity): number {
  return Math.max(0, Math.min(focus.endedAt ?? now, to) - Math.max(focus.startedAt, from))
}
export const dayLabel = (millis: number) => new Date(millis).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric' })
export const dateTime = (millis: number) => new Date(millis).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
export const clockTime = (millis: number) => new Date(millis).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
export function localInput(millis: number | null): string {
  if (millis === null) return ''
  const d = new Date(millis), two = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`
}
export function currentMonth(): string { return localInput(Date.now()).slice(0, 7) }
export function shiftMonth(month: string, delta: number): string {
  const [year, m] = month.split('-').map(Number), d = new Date(year, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
export function todoError(error: unknown): string {
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body); if (typeof body.error === 'string' && body.error) return body.error; if (body.message) return body.message } catch { /* use status fallback */ }
    if (error.status === 409) return '记录已变更或时段重叠，请刷新后核对。'
    if (error.status === 404) return '记录不存在，请刷新后重试。'
    if (error.status === 401) return '登录已过期，请重新登录。'
    return `请求未完成（${error.status}），请稍后重试。`
  }
  return error instanceof Error ? error.message : '请求未完成，请重试。'
}
