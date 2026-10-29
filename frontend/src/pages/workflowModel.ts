import type { MaWorkflow, MaWorkflowEvent, MaWorkflowTodo } from '../api/client'

export const NODE_W = 280
export const NODE_H = 140
export type WorkflowNode = { id: string; kind: 'agent' | 'todo' | 'topic' | 'user'; x: number; y: number }
export type WorkflowEdge = { from: string; to: string; kind: 'participation' | 'ownership' | 'dependency' | 'discussion'; accent?: boolean; dashed?: boolean }

export function topoOrder(todos: MaWorkflowTodo[]): MaWorkflowTodo[] {
  const ids = new Set(todos.map(t => t.id))
  const result: MaWorkflowTodo[] = []
  const seen = new Set<string>()
  const sorted = [...todos].sort((a, b) => a.seq - b.seq)
  while (result.length < sorted.length) {
    const ready = sorted.filter(t => !seen.has(t.id) && t.dependsOn.every(id => id === t.id || !ids.has(id) || seen.has(id)))
    if (!ready.length) { result.push(...sorted.filter(t => !seen.has(t.id))); break }
    ready.forEach(t => { seen.add(t.id); result.push(t) })
  }
  return result
}

/** 连线只来自参与、归属、声明的依赖和用户选中的历史定向事件。 */
export function workflowLayout(data: MaWorkflow | null, view: 'agents' | 'todos', event?: MaWorkflowEvent | null) {
  const nodes: WorkflowNode[] = [], edges: WorkflowEdge[] = []
  if (!data) return { nodes, edges }
  const todos = topoOrder(data.todos)
  const ids = new Set(todos.map(t => t.id))
  if (view === 'agents') {
    const stride = NODE_W + 100
    if (data.sessionId) nodes.push({ id: 'topic:current', kind: 'topic', x: 40 + stride, y: 0 })
    if (event?.targets.includes('user')) nodes.push({ id: 'user:current', kind: 'user', x: 40 + stride * 2, y: 0 })
    // 每组最多三个成员，名下任务垂直落在同一列；状态刷新不会改变分组。
    let groupY = data.sessionId ? 220 : 0
    for (let start = 0; start < data.agents.length; start += 3) {
      const group = data.agents.slice(start, start + 3)
      let taskRows = 0
      group.forEach((a, column) => {
        const x = 40 + column * stride
        const mine = todos.filter(t => t.ownerPeer === a.id)
        taskRows = Math.max(taskRows, mine.length)
        nodes.push({ id: `agent:${a.id}`, kind: 'agent', x, y: groupY })
        if (data.sessionId) edges.push({ from: `agent:${a.id}`, to: 'topic:current', kind: 'participation', accent: data.floorPeer === a.id })
        mine.forEach((t, row) => {
          nodes.push({ id: `todo:${t.id}`, kind: 'todo', x, y: groupY + 210 + row * 190 })
          edges.push({ from: `agent:${a.id}`, to: `todo:${t.id}`, kind: 'ownership', accent: t.status === 'doing' })
        })
      })
      groupY += 220 + taskRows * 190 + 60
    }
    const owned = new Set(data.agents.map(a => a.id))
    todos.filter(t => !t.ownerPeer || !owned.has(t.ownerPeer)).forEach((t, i) => {
      nodes.push({ id: `todo:${t.id}`, kind: 'todo', x: 40 + (i % 3) * stride, y: groupY + Math.floor(i / 3) * 190 })
    })
    if (event && ['DISCUSSION_REQUESTED', 'AGENT_REPORT'].includes(event.kind ?? '') && data.agents.some(a => a.id === event.source)) {
      event.targets.forEach(target => {
        if (target !== event.source && (target === 'user' || data.agents.some(a => a.id === target))) {
          edges.push({ from: `agent:${event.source}`, to: target === 'user' ? 'user:current' : `agent:${target}`, kind: 'discussion', dashed: true })
        }
      })
    }
  } else {
    const levels = new Map<string, number>(), occupied = new Map<number, number>()
    todos.forEach(t => {
      const level = t.dependsOn.reduce((max, id) => Math.max(max, (levels.get(id) ?? -1) + 1), 0)
      const row = occupied.get(level) ?? 0
      levels.set(t.id, level); occupied.set(level, row + 1)
      nodes.push({ id: `todo:${t.id}`, kind: 'todo', x: level * (NODE_W + 110), y: row * 190 })
    })
  }
  if (view === 'todos') todos.forEach(t => [...new Set(t.dependsOn)].forEach(id => {
    if (ids.has(id) && id !== t.id) edges.push({ from: `todo:${id}`, to: `todo:${t.id}`, kind: 'dependency', accent: t.status === 'doing' })
  }))
  return { nodes, edges }
}

const EVENT_LABELS: Record<string, string> = {
  TASK_CREATED: '创建任务', TASK_BLOCKED: '任务受阻', TASK_COMPLETED: '任务完成', WORK_STARTED: '开始协作执行',
  ALL_WORK_DONE: '任务完成，集结讨论', DISCUSSION_REQUESTED: '请求讨论', AGENT_REPORT: '提交报告', CANCEL_REQUESTED: '用户请求停止',
  speech: '公开发言', user: '用户消息', topic_open: '话题开始', turn_end: '本轮结束', rally: '重新组织讨论',
  joined: '加入讨论', silent: '暂不发言', propose_end: '提议结束', close: '话题结束', addressed: '被点名',
  prompt: '请求表态', revived: '重新参与', reopen: '重新讨论',
}
export const eventLabel = (event: MaWorkflowEvent) => EVENT_LABELS[event.kind ?? ''] ?? '圆桌动态'

export function agentActivity(data: MaWorkflow, id: string) {
  const agent = data.agents.find(a => a.id === id)
  if (data.floorPeer === id) return '当前发言者'
  const doing = data.todos.find(t => t.ownerPeer === id && t.status === 'doing')
  if (doing) return `正在执行：${doing.title}`
  const blocked = data.todos.find(t => t.ownerPeer === id && t.status === 'blocked')
  if (blocked) return `受阻：${blocked.blockReason || blocked.title}`
  if (agent?.execution === 'WAITING_EVENT') return '等待用户回应'
  const byId = new Map(data.todos.map(t => [t.id, t]))
  const dependency = data.todos.filter(t => t.ownerPeer === id && t.status === 'pending')
    .flatMap(t => t.dependsOn.map(dep => byId.get(dep))).find(t => t && !['done', 'dropped'].includes(t.status))
  if (dependency) return `等待前置：${dependency.title}`
  if (agent?.working) return '工作中'
  if (agent?.pending) return '任务待执行'
  return data.discussing ? '参与本轮讨论' : '当前空闲'
}
