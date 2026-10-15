import type { CodingEvent } from '../../api/coding'

export function eventData(event: CodingEvent): Record<string, unknown> {
  try { const value: unknown = JSON.parse(event.payload); return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {} } catch { return {} }
}
function argumentsOf(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') { try { return argumentsOf(JSON.parse(value)) } catch { return {} } }
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}
export type TimelineItem =
  | { kind: 'reasoning'; id: number; attempt: number; round: number; text: string; finished: boolean }
  | { kind: 'tool'; id: number; attempt: number; tool: string; args: Record<string, unknown>; status: string; result: string }
  | { kind: 'event'; id: number; event: CodingEvent }

/** Replay deltas once, replace them with authoritative snapshots, and merge each tool operation. */
export function codingTimeline(events: CodingEvent[]): TimelineItem[] {
  const items: TimelineItem[] = [], thoughts = new Map<string, Extract<TimelineItem, { kind: 'reasoning' }>>(), tools = new Map<string, Extract<TimelineItem, { kind: 'tool' }>>()
  let attempt = 1
  const unique = [...new Map(events.map(event => [event.id, event])).values()].sort((a, b) => a.id - b.id)
  for (const event of unique) {
    const row = eventData(event)
    const eventAttempt = Number(row.executionId ? row.executionAttempt : row.attempt)
    if (eventAttempt > 0) attempt = eventAttempt
    if (event.type === 'run.finished') { attempt = Math.max(attempt, Number(row.attempt) || attempt) + 1; continue }
    if (event.type === 'model.retry') {
      const thought = thoughts.get(`${String(row.executionId || 'main')}:${attempt}:${Number(row.round) || 0}`)
      if (thought) { thought.text = ''; thought.finished = false }
      items.push({ kind: 'event', id: event.id, event })
    } else if (event.type === 'reasoning.delta' || event.type === 'reasoning.finished') {
      const key = `${String(row.executionId || 'main')}:${attempt}:${Number(row.round) || 0}`
      let thought = thoughts.get(key)
      if (!thought) { thought = { kind: 'reasoning', id: event.id, attempt, round: Number(row.round) || 0, text: '', finished: false }; thoughts.set(key, thought); items.push(thought) }
      const text = typeof row.text === 'string' ? row.text : ''
      if (event.type === 'reasoning.finished') { thought.text = text; thought.finished = true }
      else if (!thought.finished) thought.text += text
    } else if (event.type === 'tool.started' || event.type === 'tool.finished' || event.type === 'tool.denied') {
      const key = row.operationId == null ? `event:${event.id}` : `operation:${String(row.executionId || 'main')}:${row.operationId}`
      let tool = tools.get(key)
      if (!tool) { tool = { kind: 'tool', id: event.id, attempt, tool: String(row.tool || ''), args: {}, status: 'RUNNING', result: '' }; tools.set(key, tool); items.push(tool) }
      if (row.args != null) tool.args = argumentsOf(row.args)
      if (event.type !== 'tool.started') { tool.status = String(row.status || 'DONE'); tool.result = String(row.result || row.error || '') }
    } else if (['input.received', 'context.compacted', 'validation.finished', 'response.truncated', 'plan.approved'].includes(event.type)) {
      items.push({ kind: 'event', id: event.id, event })
    }
  }
  return items.filter(item => item.kind !== 'reasoning' || item.text.trim())
}

const TOOLS: Record<string, string> = { list_files: '浏览文件', read_file: '读取代码', search_text: '搜索项目', apply_patch: '修改文件', git_diff: '查看差异', git_status: '核对变更', start_process: '执行命令', read_process: '读取输出', stop_process: '停止命令', run_validation: '验证项目' }
export function toolPresentation(tool: string, args: Record<string, unknown>) {
  const name = TOOLS[tool] || tool
  switch (tool) {
    case 'delegate_agent': return { title: `委派子 Agent · ${String(args.role || '')}`, detail: String(args.title || args.prompt || '').slice(0, 160) }
    case 'read_agent': return { title: '读取子 Agent 状态', detail: String(args.executionId || '') }
    case 'resume_agent': return { title: '继续子 Agent', detail: String(args.executionId || '') }
    case 'stop_agent': return { title: '停止子 Agent', detail: String(args.executionId || '') }
    case 'read_file': {
      const start = Math.max(1, Number(args.startLine) || 1), count = Math.max(1, Math.min(500, Number(args.lineCount) || 200))
      return { title: `${name} · ${String(args.path || '未指定文件')}`, detail: `请求第 ${start}–${start + count - 1} 行` }
    }
    case 'list_files': return { title: `${name} · ${String(args.directory || '.')}`, detail: '当前项目任务副本' }
    case 'search_text': return { title: `${name} · ${String(args.glob || '**')}`, detail: `关键词：${String(args.query || '')} · ${!args.glob || args.glob === '**' ? '范围：整个项目' : '范围：匹配该文件模式的位置'}` }
    case 'apply_patch': return { title: `${name} · ${Array.isArray(args.patches) ? args.patches.map(patch => String(patch?.path || '')).filter(Boolean).join('、') : ''}`, detail: '当前项目任务副本' }
    case 'start_process': return { title: name, detail: String(args.command || '') }
    case 'read_process': case 'stop_process': return { title: name, detail: `进程 ${String(args.processId || '')}` }
    default: return { title: name, detail: '' }
  }
}
