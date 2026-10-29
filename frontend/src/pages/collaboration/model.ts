import type { MaMessage, MaPeer, MaSession, MaWorkflow, MaWorkflowTodo } from '../../api/client'

export type CollaborationView = 'discussion' | 'graph'
export type WorkSelection = { kind: 'agent' | 'todo'; id: string }
export const TASK_LABELS: Record<string, string> = { pending: '待执行', doing: '执行中', reviewing: '待检查', checking: '检查中', blocked: '受阻', done: '已结束', dropped: '已放弃' }
export const isOpenTask = (task: MaWorkflowTodo) => ['pending', 'doing', 'reviewing', 'checking', 'blocked'].includes(task.status)

/** 旧版 rally 将路由状态与完整报告拼在一起；只提取系统状态，不推断报告是否通过。 */
export function rallySummary(text: string) {
  const status = text.split(/[：:\n]/, 1)[0]
    .replace(/（只你们沟通一轮，其余成员无需参与）$/, '').trim()
  if (status && status.length <= 80) return status
  if (/^已定向请 /.test(text)) return '已请相关成员回应'
  if (/^全桌已收到 /.test(text)) return '报告已收到，其他成员开始处理'
  if (status.includes('需要你拍板')) return '需要你拍板 · 等待你的回应'
  return '协作状态已更新'
}

export function projectMemberIds(projectId: string, configured: Record<string, string[]>, sessions: MaSession[], peers: MaPeer[]) {
  const known = new Set(peers.map(p => p.id))
  const ids = configured[projectId] ?? [...new Set(sessions.filter(s => s.projectId === projectId).flatMap(s => s.memberIds))]
  return (ids.length ? ids : peers.map(p => p.id)).filter(id => known.has(id))
}

export function projectStatus(data: MaWorkflow | null, busy: boolean, awaiting: boolean) {
  if (awaiting || data?.topicStatus === 'waiting') return '等待你回应'
  if (data?.todos.some(t => t.status === 'blocked')) return '有工作受阻'
  if (busy || data?.agents.some(a => a.working)) return '正在推进'
  if (data?.advancing) return '集结讨论'
  if (data?.todos.some(isOpenTask)) return '有待推进工作'
  if (data?.todos.some(t => t.status === 'done')) return '执行已结束 · 待审阅'
  return '等待新的方向'
}

/** 已结束任务默认退出画布；选中一项时，带入它的完整前置链和直接下游。 */
export function graphTasks(todos: MaWorkflowTodo[], selected: string | null, history: boolean) {
  if (history) return todos
  const ids = new Set(todos.filter(isOpenTask).map(t => t.id))
  if (selected) {
    ids.add(selected)
    todos.filter(t => t.dependsOn.includes(selected)).forEach(t => ids.add(t.id))
  }
  const byId = new Map(todos.map(t => [t.id, t]))
  const queue = [...ids]
  for (let i = 0; i < queue.length; i++) {
    byId.get(queue[i])?.dependsOn.forEach(id => {
      if (byId.has(id) && !ids.has(id)) { ids.add(id); queue.push(id) }
    })
  }
  return todos.filter(t => ids.has(t.id))
}

/** 合并同成员、同话题的相邻正文；事件保持边界，完整记录保留原始上下文。 */
export function readingMessages(messages: MaMessage[], preservedKinds: Set<string> = new Set()): MaMessage[] {
  const result: MaMessage[] = []
  let separated = false
  for (const message of messages) {
    if (message.role === 'event' && !preservedKinds.has(message.kind ?? '') && !['topic_open', 'turn_end', 'rally'].includes(message.kind ?? '')) { separated = true; continue }
    const previous = result.at(-1)
    const gap = message.createdAt && previous?.createdAt ? Date.parse(message.createdAt) - Date.parse(previous.createdAt) : NaN
    if (!separated && previous?.role === 'peer' && message.role === 'peer' && message.peerId && message.peerId === previous.peerId
      && message.topicId === previous.topicId && !message.kind && !previous.kind && Number.isFinite(gap) && gap >= 0 && gap < 120000
      && !message.trace?.length && !previous.trace?.length && !message.reasoning && !previous.reasoning) {
      result[result.length - 1] = { ...previous, content: `${previous.content}\n\n${message.content}` }
    } else result.push(message)
    separated = false
  }
  return result
}

export function eventTime(value: number | string | null | undefined) {
  const date = value == null ? null : new Date(value)
  return date && Number.isFinite(date.getTime()) ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''
}
