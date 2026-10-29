import type { MaWorkRun } from '../../api/client'

export const WORK_STATUS: Record<string, string> = { pending: '待办', doing: '进行中', reviewing: '待检查', checking: '检查中', blocked: '受阻', done: '已结束', dropped: '已放弃' }
export const RUN_STATUS: Record<string, string> = { running: '执行中', done: '本次调用结束', submitted: '已提交检查', revised: '已要求返工', incomplete: '未提交成果', failed: '失败', blocked: '受阻' }

export function runTime(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '时间未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未记录' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

export function runDuration(run: MaWorkRun, now: number) {
  if (!Number.isFinite(run.startedAt)) return '耗时未记录'
  const ms = (run.finishedAt ?? now) - run.startedAt
  if (!Number.isFinite(ms) || ms < 0) return '耗时未记录'
  const seconds = Math.floor(ms / 1000)
  return seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : `${seconds} 秒`
}

export function valueText(value: unknown): string {
  return typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value, null, 2)
}

export function prettyText(value: string): string {
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

export type RunTool = { key: string; name: string; args: string; result?: string }
function object(value: unknown): Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

/** 原始消息按调用 ID 配对；不把调用过工具误报成调用成功。旧记录仍可阅读 trace。 */
export function runTools(run: MaWorkRun): RunTool[] {
  const tools: RunTool[] = []
  const waiting = new Map<string, RunTool>()
  for (const value of run.toolRounds ?? []) {
    const message = object(value)
    if (message.role === 'assistant' && Array.isArray(message.tool_calls)) {
      for (const raw of message.tool_calls) {
        const call = object(raw), fn = object(call.function)
        const entry = { key: `call-${tools.length}`, name: valueText(fn.name) || '未命名工具', args: valueText(fn.arguments) }
        tools.push(entry)
        if (typeof call.id === 'string') waiting.set(call.id, entry)
      }
    } else if (message.role === 'tool') {
      const id = valueText(message.tool_call_id), entry = waiting.get(id)
      if (entry) { entry.result = valueText(message.content); waiting.delete(id) }
      else tools.push({ key: `result-${tools.length}`, name: valueText(message.name) || '工具返回内容', args: '', result: valueText(message.content) })
    }
  }
  if (!tools.length) for (const [index, segment] of (run.trace ?? []).entries()) {
    if (segment.type === 'tool') tools.push({ key: `trace-${index}`, name: segment.name || '未命名工具', args: segment.args || '' })
  }
  return tools
}

export function runMatches(run: MaWorkRun, query: string, status: string) {
  if (status && run.status !== status) return false
  const needle = query.trim().toLocaleLowerCase()
  return !needle || [run.id, run.peerName, run.peerId, RUN_STATUS[run.status] ?? run.status, run.output, run.error, run.reasoning, JSON.stringify(run.trace), JSON.stringify(run.toolRounds)].join('\n').toLocaleLowerCase().includes(needle)
}
