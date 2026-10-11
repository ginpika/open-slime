import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent } from 'react'
import {
  listMaSessions,
  listMaPeers,
  maWorkflow,
  maWorkflowOverview,
  maTodoRuns,
  dropMaTodo,
  deferMaTodo,
  checkMaTodo,
  MA_EXEC_LABELS,
  type MaSession,
  type MaPeer,
  type MaWorkflow,
  type MaWorkflowAgent,
  type MaWorkflowTodo,
  type MaWorkRun,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import PeerAvatar from '../components/PeerAvatar'
import { Select } from '../components/formKit'
import { pickText } from '../theme/color'

/**
 * 执行态：把智能体的工作状态画成一张「无限画布」上的 workflow 图（ComfyUI 那种手感）。
 *
 * <p>两个视角，各自的数据来源不同：
 * <ul>
 *   <li><b>Agent 视角</b>——全局名册上的每位成员一个节点，看它此刻在不在跑、手上有什么活；
 *       成员是全局的，<b>不随所选圆桌会话变化</b>，所以这一视角不显示会话选择器；</li>
 *   <li><b>任务视角</b>——每位成员一行，行内是它接到的 TODO，按处理顺序从左到右排：
 *       已完成在左（半透明）、正在处理居中（卡片带负责人头像 + 名字）、待办在右；
 *       受阻的卡片用一圈粗红内描边高亮。共享任务区按会话隔离，所以只有这一视角需要选择圆桌会话。</li>
 * </ul>
 *
 * <p>点任意节点，右侧回放它的**执行过程**（思考链 + 过程分段 + 产出）——
 * 这是「Agent 到底在干嘛」的唯一出口：卡在死循环里能一眼看见，而不是等账单出来才发现。
 *
 * <p>画布可拖拽平移、滚轮缩放，底部是随变换移动的网格。
 */

/* ---------- 布局常量（世界坐标，单位 px） ---------- */
const NODE_W = 268
const NODE_H = 104
const GAP_X = 88
const GAP_Y = 22
const LANE_GAP = 120

/* 任务视角：一个成员一行，行内是它接到的 TODO（从左到右 = 处理顺序）。
   行首一列留给成员名字（行头），TODO 卡片从 TODO_ROW_X 起横向排开 */
const TODO_ROW_GAP = 26
const TODO_HEADER_W = 168
const TODO_GAP_X = 28
const TODO_ROW_X = TODO_HEADER_W + 24

const COMM_LABELS: Record<string, string> = {
  SPEAKING: '发言中',
  LISTENING: '在听',
  SILENT: '不发言',
}
const TODO_LABELS: Record<string, string> = {
  pending: '待办',
  doing: '进行中',
  blocked: '受阻',
  done: '已完成',
  dropped: '已放弃',
}
const RUN_LABELS: Record<string, string> = {
  running: '执行中',
  done: '已完成',
  failed: '失败',
  blocked: '受阻',
}

type Node = { id: string; kind: 'agent' | 'todo'; x: number; y: number }
type Edge = { from: string; to: string; accent?: boolean; dashed?: boolean }
/** 任务视角的一行：一位成员（或公共池）与它接到的 TODO */
type TodoRow = { key: string; agent: MaWorkflowAgent | null; y: number }

const ACCENT = 'var(--c-accent, #eab308)'
const LINE = 'var(--c-line, rgba(128,128,128,.35))'
const RED = '#ef4444'
/** 连线：统一用醒目的绿（在跑的那条更亮更粗） */
const EDGE = '#22c55e'
const EDGE_HOT = '#4ade80'
/** Agent 卡片头像右下角的在岗状态点：绿=空闲、蓝=工作中（一眼看清谁在跑） */
const IDLE_DOT = '#22c55e'
const WORKING_DOT = '#3b82f6'

/**
 * 卡片浮起：层次感一律靠投影表达，不用描边——画布比卡片深，卡片再叠一层影，
 * 有状态（在跑 / 受阻 / 选中）时在影里掺一道同色光晕，取代原先的彩色描边。
 */
function cardShadow(tone: string | null, selected: boolean): string {
  const lift = selected
    ? '0 2px 4px rgb(0 0 0 / .3), 0 8px 20px rgb(0 0 0 / .24)'
    : '0 1px 2px rgb(0 0 0 / .28), 0 3px 10px rgb(0 0 0 / .16)'
  const color = tone ?? (selected ? ACCENT : null)
  if (!color) return lift
  return `${lift}, 0 0 ${selected ? 22 : 15}px color-mix(in srgb, ${color} ${selected ? 62 : 42}%, transparent)`
}

function todoTone(status: string): string {
  switch (status) {
    case 'doing':
      return ACCENT
    case 'blocked':
      return RED
    default:
      return LINE
  }
}

/**
 * 依赖优先的展示顺序：前置永远排在使用它的任务之前（同层按 seq 稳定）。
 * 于是「绿色连线」表达的是「先做谁」，而不是录入顺序。
 *
 * <p>前置缺失（已删 / 不在本会话）不算约束；成环理论上不该出现，兜底按 seq 追加，绝不丢条目。
 */
function topoOrder(todos: MaWorkflowTodo[]): MaWorkflowTodo[] {
  const ids = new Set(todos.map((t) => t.id))
  const byId = new Map(todos.map((t) => [t.id, t]))
  const indeg = new Map<string, number>()
  const outs = new Map<string, string[]>()
  for (const t of todos) {
    indeg.set(t.id, 0)
  }
  for (const t of todos) {
    for (const dep of t.dependsOn) {
      if (!ids.has(dep) || dep === t.id) {
        continue
      }
      indeg.set(t.id, (indeg.get(t.id) ?? 0) + 1)
      const list = outs.get(dep)
      if (list) list.push(t.id)
      else outs.set(dep, [t.id])
    }
  }
  const ready = todos.filter((t) => (indeg.get(t.id) ?? 0) === 0).sort((a, b) => a.seq - b.seq)
  const out: MaWorkflowTodo[] = []
  const seen = new Set<string>()
  while (ready.length > 0) {
    const t = ready.shift() as MaWorkflowTodo
    out.push(t)
    seen.add(t.id)
    for (const nid of outs.get(t.id) ?? []) {
      const left = (indeg.get(nid) ?? 0) - 1
      indeg.set(nid, left)
      if (left !== 0) {
        continue
      }
      const node = byId.get(nid)
      if (!node) {
        continue
      }
      /* 同层按 seq 落位，次序稳定 */
      const at = ready.findIndex((r) => r.seq > node.seq)
      if (at < 0) ready.push(node)
      else ready.splice(at, 0, node)
    }
  }
  for (const t of [...todos].sort((a, b) => a.seq - b.seq)) {
    if (!seen.has(t.id)) out.push(t)
  }
  return out
}

/** 状态徽章（Tailwind 风格胶囊）：用状态色的淡底 + 同色字 + 一圈内描边，动态色故着色走内联 style */
function Badge({ text, color }: { text: string; color?: string }) {
  const tint = color ?? 'var(--c-fg-2, #888)'
  return (
    <span
      className="inline-flex shrink-0 items-center whitespace-nowrap rounded-full px-2 py-0.5 text-2xs font-semibold leading-none"
      style={{
        background: `color-mix(in srgb, ${tint} 16%, transparent)`,
        color: tint,
        boxShadow: `inset 0 0 0 1px color-mix(in srgb, ${tint} 28%, transparent)`,
      }}
    >
      {text}
    </span>
  )
}

/** 世界坐标 → 贝塞尔路径（水平进出） */
function bezier(a: Node, b: Node): string {
  const x1 = a.x + NODE_W
  const y1 = a.y + NODE_H / 2
  const x2 = b.x
  const y2 = b.y + NODE_H / 2
  const dx = Math.max(48, Math.abs(x2 - x1) * 0.45)
  return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
}

/** 卡片四条边之一 */
type Side = 'top' | 'right' | 'bottom' | 'left'

/** 卡片某条边的中点（连线一律从这些点出入，不穿卡片——已完成卡片半透明，穿过去会露出来） */
function edgeMid(n: Node, side: Side): { x: number; y: number } {
  const cx = n.x + NODE_W / 2
  const cy = n.y + NODE_H / 2
  switch (side) {
    case 'top':
      return { x: cx, y: n.y }
    case 'bottom':
      return { x: cx, y: n.y + NODE_H }
    case 'left':
      return { x: n.x, y: cy }
    default:
      return { x: n.x + NODE_W, y: cy }
  }
}

/** 沿某条边的法线方向往外推 k（控制点用：线于是垂直进出卡片） */
function pushOut(p: { x: number; y: number }, side: Side, k: number): { x: number; y: number } {
  switch (side) {
    case 'top':
      return { x: p.x, y: p.y - k }
    case 'bottom':
      return { x: p.x, y: p.y + k }
    case 'left':
      return { x: p.x - k, y: p.y }
    default:
      return { x: p.x + k, y: p.y }
  }
}

/**
 * 任务链的连线：从一张卡的**边的中点**连到另一张卡的边的中点。
 *
 * <p>按两卡中心的相对方位选一对相向的边（右→左 / 下→上 …），控制点沿边的法线外推，
 * 于是线垂直进出卡片、且绝不穿过卡片内部——已完成卡片是半透明的，穿心线会在卡片上露出来。
 */
function chainPath(a: Node, b: Node): string {
  const dx = b.x + NODE_W / 2 - (a.x + NODE_W / 2)
  const dy = b.y + NODE_H / 2 - (a.y + NODE_H / 2)
  let from: Side
  let to: Side
  if (Math.abs(dx) >= Math.abs(dy)) {
    from = dx >= 0 ? 'right' : 'left'
    to = dx >= 0 ? 'left' : 'right'
  } else {
    from = dy >= 0 ? 'bottom' : 'top'
    to = dy >= 0 ? 'top' : 'bottom'
  }
  const p1 = edgeMid(a, from)
  const p2 = edgeMid(b, to)
  /* 控制点按「两个出入点之间」的实际距离取，别按卡片中心距——否则同排相邻两卡会绕出 S 形 */
  const gap = Math.hypot(p2.x - p1.x, p2.y - p1.y)
  const k = Math.min(90, Math.max(18, gap * 0.4))
  const c1 = pushOut(p1, from, k)
  const c2 = pushOut(p2, to, k)
  return `M ${p1.x} ${p1.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${p2.x} ${p2.y}`
}

export default function Workflow() {
  const [sessions, setSessions] = useState<MaSession[]>([])
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [sessionId, setSessionId] = useState('')
  const [data, setData] = useState<MaWorkflow | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<'agents' | 'todos'>('agents')
  const [tf, setTf] = useState({ x: 48, y: 48, k: 1 })
  const [sel, setSel] = useState<{ kind: 'agent' | 'todo'; id: string } | null>(null)
  /** 打开了 ⋯ 菜单的那条任务（同时至多一个） */
  const [menuFor, setMenuFor] = useState<string | null>(null)

  const canvasRef = useRef<HTMLDivElement>(null)
  const panRef = useRef<{ x: number; y: number; tx: number; ty: number } | null>(null)
  const movedRef = useRef(false)

  useEffect(() => {
    listMaSessions()
      .then((list) => {
        setSessions(list)
        setSessionId((cur) => cur || list[0]?.id || '')
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  /* 卡片上的头像取自智能体名册——与「智能体」页同一份头像，不另存一份 */
  useEffect(() => {
    listMaPeers()
      .then(setPeers)
      .catch(() => undefined)
  }, [])

  const avatarOf = useCallback(
    (peerId: string | null | undefined) =>
      peerId ? (peers.find((p) => p.id === peerId)?.avatar ?? null) : null,
    [peers],
  )

  /* 卡片上的 ⋯ 菜单：点菜单外面就收起（⋯ 按钮自带 data-todo-menu，交给它自己 toggle） */
  useEffect(() => {
    if (!menuFor) return
    const onDown = (e: MouseEvent) => {
      const el = e.target as HTMLElement | null
      if (el?.closest?.('[data-todo-menu]')) return
      setMenuFor(null)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [menuFor])

  /* 轮询：执行态变化快（有人在跑 / 任务在推进），2.5s 一次足够顺滑又不压后端。
     Agent 视角取全局总览（不挂会话），任务视角取所选会话的任务区。 */
  useEffect(() => {
    if (view === 'todos' && !sessionId) {
      setData(null)
      return
    }
    let alive = true
    const load = () =>
      (view === 'agents' ? maWorkflowOverview() : maWorkflow(sessionId))
        .then((d) => {
          if (alive) {
            setData(d)
            setError(null)
          }
        })
        .catch((e: unknown) => {
          if (alive) setError(e instanceof Error ? e.message : String(e))
        })
    load()
    const timer = setInterval(load, 2500)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [view, sessionId])

  /* 滚轮缩放：必须用原生监听才能 preventDefault（React 的 onWheel 是被动监听） */
  useEffect(() => {
    const el = canvasRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      /* 悬浮卡片里在滚自己的内容：别顺手把画布也缩放了 */
      if (e.target instanceof Element && e.target.closest('[data-wf-overlay]')) return
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      const mx = e.clientX - rect.left
      const my = e.clientY - rect.top
      setTf((t) => {
        const k = Math.min(2.5, Math.max(0.3, t.k * (e.deltaY < 0 ? 1.1 : 0.9)))
        return { k, x: mx - (mx - t.x) * (k / t.k), y: my - (my - t.y) * (k / t.k) }
      })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  const onPointerDown = (e: ReactPointerEvent) => {
    if (e.button !== 0 && e.button !== 1) return
    panRef.current = { x: e.clientX, y: e.clientY, tx: tf.x, ty: tf.y }
    movedRef.current = false
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: ReactPointerEvent) => {
    const p = panRef.current
    if (!p) return
    if (Math.abs(e.clientX - p.x) + Math.abs(e.clientY - p.y) > 3) movedRef.current = true
    setTf((t) => ({ ...t, x: p.tx + e.clientX - p.x, y: p.ty + e.clientY - p.y }))
  }
  const onPointerUp = (e: ReactPointerEvent) => {
    panRef.current = null
    ;(e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId)
  }
  const pick = useCallback((kind: 'agent' | 'todo', id: string) => {
    if (movedRef.current) return
    setSel({ kind, id })
  }, [])

  /* ---------- 布局 ---------- */
  const { nodes, edges, rows } = useMemo(() => {
    const ns: Node[] = []
    const es: Edge[] = []
    const rs: TodoRow[] = []
    if (!data) return { nodes: ns, edges: es, rows: rs }
    if (view === 'agents') {
      data.agents.forEach((a, i) => {
        ns.push({ id: `agent:${a.id}`, kind: 'agent', x: 0, y: i * (NODE_H + GAP_Y) })
      })
      /* 正在做 / 受阻的任务放在成员右侧的独立列里，各自占一行——绝不叠在一起（叠了就数不清几条） */
      const col = NODE_W + GAP_X + LANE_GAP
      let row = 0
      data.todos.forEach((t) => {
        if (t.status !== 'doing' && t.status !== 'blocked') return
        ns.push({ id: `todo:${t.id}`, kind: 'todo', x: col, y: row * (NODE_H + GAP_Y) })
        const owner = data.agents.find((a) => a.id === t.ownerPeer)
        if (owner) {
          es.push({ from: `agent:${owner.id}`, to: `todo:${t.id}`, accent: t.status === 'doing' })
        }
        row++
      })
      return { nodes: ns, edges: es, rows: rs }
    }

    /* 任务视角：按成员分组成行，行内按依赖顺序从左到右排——成员依次啃它们，
       「已完成」自然留在左手边、「正在处理」居中、「待办」在右；整条链再用绿色粗线串起来 */
    const stepY = NODE_H + TODO_ROW_GAP
    const stepX = NODE_W + TODO_GAP_X
    const known = new Set(data.agents.map((a) => a.id))
    /* 全局依赖顺序：行内排序 + 绿色连线都用它（前置在前、同层按 seq） */
    const ordered = topoOrder(data.todos)
    const groups = new Map<string, MaWorkflowTodo[]>()
    for (const t of ordered) {
      const k = t.ownerPeer && known.has(t.ownerPeer) ? t.ownerPeer : ''
      const list = groups.get(k)
      if (list) list.push(t)
      else groups.set(k, [t])
    }
    const pushRow = (key: string, agent: MaWorkflowAgent | null, todos: MaWorkflowTodo[]) => {
      const y = rs.length * stepY
      rs.push({ key, agent, y })
      todos.forEach((t, i) => {
        ns.push({ id: `todo:${t.id}`, kind: 'todo', x: TODO_ROW_X + i * stepX, y })
      })
    }
    data.agents.forEach((a) => {
      const mine = groups.get(a.id)
      if (mine && mine.length > 0) pushRow(`row:${a.id}`, a, mine)
    })
    /* 公共任务（无主）与负责人已不在名册的，归到最后一行 */
    const loose = groups.get('')
    if (loose && loose.length > 0) pushRow('row:public', null, loose)
    /* 绿色粗线：按依赖顺序把任务卡一张张串起来（跨行那几段就是成员之间的交接） */
    for (let i = 1; i < ordered.length; i++) {
      es.push({
        from: `todo:${ordered[i - 1].id}`,
        to: `todo:${ordered[i].id}`,
        accent: ordered[i].status === 'doing',
      })
    }
    return { nodes: ns, edges: es, rows: rs }
  }, [data, view])

  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])

  /* 悬浮总览里点某一行：把该任务的卡片平移到画布视口正中央（缩放不变） */
  const focusTodo = useCallback(
    (id: string) => {
      const n = byId.get(`todo:${id}`)
      const el = canvasRef.current
      if (!n || !el) return
      const rect = el.getBoundingClientRect()
      setTf((t) => ({
        k: t.k,
        x: rect.width / 2 - (n.x + NODE_W / 2) * t.k,
        y: rect.height / 2 - (n.y + NODE_H / 2) * t.k,
      }))
    },
    [byId],
  )

  const maxY = nodes.reduce((m, n) => Math.max(m, n.y + NODE_H), 0)
  const maxX = nodes.reduce((m, n) => Math.max(m, n.x + NODE_W), 0)

  const agentOf = (id: string) => data?.agents.find((a) => a.id === id)
  const todoOf = (id: string) => data?.todos.find((t) => t.id === id)
  /** 这位成员此刻在啃哪条任务（取名下第一条进行中的）——递给卡片当「在处理什么」那句话 */
  const doingTaskOf = (peerId: string) =>
    data?.todos.find((t) => t.status === 'doing' && t.ownerPeer === peerId)?.title ?? null

  /* 这条还在等哪些前置（标题）：只列尚未完成的前置——那就是「它为什么还没开工」的那句话 */
  const waitingOf = useCallback(
    (t: MaWorkflowTodo) =>
      t.dependsOn
        .map((id) => data?.todos.find((x) => x.id === id))
        .filter((d): d is MaWorkflowTodo => !!d)
        .filter((d) => d.status !== 'done' && d.status !== 'dropped')
        .map((d) => d.title),
    [data],
  )

  return (
    <div className="flex h-full flex-col">
      {/* 工具条 */}
      <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-2.5">
        <span className="text-sm font-medium text-fg">执行态</span>
        {/* 会话选择只服务任务视角：共享任务区按会话隔离；Agent 视角是全局名册，不随会话变化 */}
        {view === 'todos' ? (
          <div className="min-w-[12rem]">
            <Select
              value={sessionId}
              onChange={setSessionId}
              options={
                sessions.length === 0
                  ? [{ value: '', label: '（还没有会话）' }]
                  : sessions.map((s) => ({ value: s.id, label: s.title || '未命名会话' }))
              }
            />
          </div>
        ) : (
          <span className="text-xs text-faint">全部成员（跨会话汇总）</span>
        )}
        <div className="flex overflow-hidden rounded border border-line text-xs">
          {(
            [
              ['agents', 'Agent 视角'],
              ['todos', '任务视角'],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setView(key)}
              className={`px-3 py-1 transition ${
                view === key ? 'text-fg' : 'text-faint hover:text-fg-2'
              }`}
              style={view === key ? { background: 'var(--c-accent-soft, rgba(128,128,128,.16))' } : undefined}
            >
              {label}
            </button>
          ))}
        </div>
        {/* 整桌推进集结：全员顺利做完，正等共识决定交付还是开新一轮 */}
        {view === 'todos' && data?.advancing && (
          <span
            className="flex items-center gap-1.5 rounded px-2 py-0.5 text-xs leading-none"
            style={{
              background: 'color-mix(in srgb, var(--c-accent, #eab308) 18%, transparent)',
              color: ACCENT,
            }}
          >
            <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full" style={{ background: ACCENT }} />
            全员就绪 · 推进集结
          </span>
        )}
        <div className="flex items-center gap-1 text-xs text-faint">
          <button type="button" className="px-2 py-1 hover:text-fg" onClick={() => setTf((t) => ({ ...t, k: Math.max(0.3, t.k * 0.9) }))}>
            −
          </button>
          <span className="w-10 text-center">{Math.round(tf.k * 100)}%</span>
          <button type="button" className="px-2 py-1 hover:text-fg" onClick={() => setTf((t) => ({ ...t, k: Math.min(2.5, t.k * 1.1) }))}>
            +
          </button>
          <button type="button" className="ml-1 px-2 py-1 hover:text-fg" onClick={() => setTf({ x: 48, y: 48, k: 1 })}>
            复位
          </button>
        </div>
        <div className="ml-auto flex items-center gap-3 text-xs text-faint">
          <span className="flex items-center gap-1">
            <span className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: ACCENT }} />
            在跑
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block h-1.5 w-1.5 rounded-full bg-red-500" />
            受阻
          </span>
          <span>拖拽平移 · 滚轮缩放 · 点节点看过程</span>
        </div>
      </div>

      {error && <p className="shrink-0 px-4 py-2 text-sm text-red-500">{error}</p>}

      <div className="flex min-h-0 flex-1">
        {/* 无限画布 */}
        <div
          ref={canvasRef}
          className="relative min-h-0 flex-1 cursor-grab select-none overflow-hidden active:cursor-grabbing"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          style={{
            /* 画布比卡片底色略深/深灰一档，卡片再靠投影浮起——层次感由深浅 + 阴影给出，不靠描边。
               分档值见 index.css 的 --wf-canvas-bg（深色主题取深灰） */
            backgroundColor: 'var(--wf-canvas-bg)',
            backgroundImage:
              'linear-gradient(to right, var(--c-line, rgba(128,128,128,.18)) 1px, transparent 1px), linear-gradient(to bottom, var(--c-line, rgba(128,128,128,.18)) 1px, transparent 1px)',
            backgroundSize: `${24 * tf.k}px ${24 * tf.k}px`,
            backgroundPosition: `${tf.x}px ${tf.y}px`,
          }}
        >
          <div
            className="absolute left-0 top-0"
            style={{ transform: `translate(${tf.x}px, ${tf.y}px) scale(${tf.k})`, transformOrigin: '0 0' }}
          >
            {/* 任务视角的行头：成员头像 + 名字（公共池没有主） */}
            {view === 'todos' &&
              rows.map((r) => (
                <div
                  key={r.key}
                  className="absolute flex items-center gap-2.5"
                  style={{ left: 0, top: r.y, width: TODO_HEADER_W, height: NODE_H }}
                >
                  {r.agent ? (
                    <>
                      <PeerAvatar
                        name={r.agent.name || r.agent.id}
                        avatar={avatarOf(r.agent.id)}
                        size="h-9 w-9"
                        textClass="text-[13px]"
                        bordered={false}
                      />
                      <span className="truncate text-sm font-medium text-fg">
                        {r.agent.name || r.agent.id}
                      </span>
                    </>
                  ) : (
                    <span className="truncate text-sm text-faint">公共池（谁有空谁做）</span>
                  )}
                </div>
              ))}

            {/* 边 */}
            <svg
              className="pointer-events-none absolute left-0 top-0"
              width={maxX + 200}
              height={maxY + 200}
              style={{ overflow: 'visible' }}
            >
              {edges.map((e, i) => {
                const a = byId.get(e.from)
                const b = byId.get(e.to)
                if (!a || !b) return null
                /* 任务→任务：依赖链，走「卡片中心到中心」的粗绿线；
                   成员→任务（Agent 视角）：仍是水平进出的细连线 */
                const chain = a.kind === 'todo' && b.kind === 'todo'
                return (
                  <path
                    key={i}
                    d={chain ? chainPath(a, b) : bezier(a, b)}
                    fill="none"
                    stroke={e.accent ? EDGE_HOT : EDGE}
                    strokeWidth={chain ? (e.accent ? 5 : 4) : e.accent ? 4 : 3}
                    strokeLinecap="round"
                    strokeDasharray={e.dashed ? '4 4' : undefined}
                  />
                )
              })}
            </svg>

            {/* 节点 */}
            {nodes.map((n) => {
              const isSel = !!(sel && sel.id === n.id.split(':')[1] && sel.kind === n.kind)
              const id = n.id.split(':')[1]
              const common = {
                onPointerDown: (e: ReactPointerEvent) => {
                  /* 落点就在卡片上：这一下不是平移。必须自己清掉「移动过」标记——
                     卡片的 pointerdown 会 stopPropagation，画布那次不会触发，
                     否则拖过画布之后再单击卡片，pick 会一直被 movedRef 挡住、窗口打不开 */
                  e.stopPropagation()
                  movedRef.current = false
                },
                onClick: () => pick(n.kind, id),
                className: 'absolute flex cursor-pointer flex-col justify-center gap-1.5 rounded-md px-3 py-2 transition',
                style: {
                  left: n.x,
                  top: n.y,
                  width: NODE_W,
                  height: NODE_H,
                  background: 'var(--ui-card-fill, var(--c-bg, transparent))',
                  boxShadow: cardShadow(null, isSel),
                } as CSSProperties,
              }
              if (n.kind === 'agent') {
                const a = agentOf(id)
                if (!a) return null
                return (
                  <div
                    key={n.id}
                    {...common}
                    style={{ ...common.style, boxShadow: cardShadow(a.working ? ACCENT : null, isSel) }}
                  >
                    {/* 角色标签：右上角一枚胶囊，底色是成员自己挑的颜色，文字色按底色自动取黑/白保证对比 */}
                    {a.role && (
                      <span
                        title={a.role}
                        className="absolute right-2 top-2 max-w-[50%] truncate rounded-full px-2 py-0.5 text-2xs font-semibold leading-none shadow-sm"
                        style={{
                          background: a.roleColor || 'var(--c-line, rgba(128,128,128,.35))',
                          color: pickText(a.roleColor || '#808080'),
                        }}
                      >
                        {a.role}
                      </span>
                    )}
                    <AgentHead a={a} avatar={avatarOf(a.id)} task={doingTaskOf(a.id)} />
                  </div>
                )
              }
              const t = todoOf(id)
              if (!t) return null
              const blocked = t.status === 'blocked'
              const finished = t.status === 'done' || t.status === 'dropped'
              return (
                <div
                  key={n.id}
                  {...common}
                  className={`${common.className} group`}
                  style={{
                    ...common.style,
                    /* 菜单打开时把这张卡抬到同层最上，免得被后画的卡片盖住 */
                    zIndex: menuFor === t.id ? 20 : undefined,
                    /* 受阻：卡片内侧描一圈粗红边高亮（内投影，不吃外层投影的层次）；
                       在跑：外投影掺黄光晕 */
                    boxShadow: blocked
                      ? `${cardShadow(null, isSel)}, inset 0 0 0 3px ${RED}`
                      : cardShadow(t.status === 'doing' ? ACCENT : null, isSel),
                    /* 已完成 / 已放弃：半透明，退到视觉后景 */
                    opacity: finished ? 0.45 : 1,
                  }}
                >
                  {/* 悬浮时才出现的 ⋯：人对这条任务的三项干预 */}
                  <button
                    type="button"
                    data-todo-menu
                    aria-label="任务操作"
                    onPointerDown={(e) => e.stopPropagation()}
                    onClick={(e) => {
                      e.stopPropagation()
                      setMenuFor((cur) => (cur === t.id ? null : t.id))
                    }}
                    className={`absolute right-1 top-1 h-5 w-5 items-center justify-center rounded text-base leading-none text-faint transition hover:text-fg ${
                      menuFor === t.id ? 'flex' : 'hidden group-hover:flex'
                    }`}
                  >
                    ⋯
                  </button>
                  {menuFor === t.id && <TodoMenu t={t} onClose={() => setMenuFor(null)} />}
                  <TodoHead t={t} avatar={avatarOf(t.ownerPeer)} waiting={waitingOf(t)} />
                </div>
              )
            })}

            {nodes.length === 0 && (
              <div className="absolute text-xs text-faint" style={{ left: 0, top: 8 }}>
                {view === 'agents'
                  ? '名册里还没有成员——去「智能体」页添一位。'
                  : '这里还没有内容——去「圆桌」开一场讨论，任务会出现在画布上。'}
              </div>
            )}
          </div>

          {/* 任务视图的全局悬浮总览：整张桌子的 TODO 竖排一条流水 */}
          {view === 'todos' && (
            <TodoOverview todos={data?.todos ?? []} avatarOf={avatarOf} onFocus={focusTodo} />
          )}
        </div>

        {/* 详情：点卡片弹出窗口看过程（不占画布） */}
        {sel && (
          <FloatWindow
            title={sel.kind === 'todo' ? '任务执行过程' : '成员'}
            onClose={() => setSel(null)}
            width={560}
          >
            <Detail sel={sel} data={data} onSelect={setSel} agentOf={agentOf} todoOf={todoOf} />
          </FloatWindow>
        )}
      </div>
    </div>
  )
}

function AgentHead({
  a,
  avatar,
  task,
}: {
  a: MaWorkflowAgent
  avatar?: string | null
  /** 此刻在啃的任务标题（没有具体任务时为 null） */
  task?: string | null
}) {
  const working = a.working
  return (
    /* 左头像（右下角一枚在岗状态点）、右名字与「此刻在做什么」——状态一律用那一枚点表达，不铺一排徽章 */
    <div className="flex items-center gap-3">
      <span className="relative shrink-0">
        <PeerAvatar
          name={a.name || a.id}
          avatar={avatar}
          size="h-14 w-14"
          textClass="text-[22px]"
          bordered={false}
        />
        {/* 像 QQ 头像那样：绿=空闲、蓝=工作中，一眼看清这位成员有没有在跑 */}
        <span
          className="absolute -bottom-0.5 -right-0.5 h-3.5 w-3.5 rounded-full"
          style={{
            background: working ? WORKING_DOT : IDLE_DOT,
            /* 环用卡片底色，把点从头像上「抠」出来 */
            boxShadow: '0 0 0 2px var(--ui-card-fill, var(--c-bg, transparent))',
          }}
        />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="min-w-0 truncate text-md font-medium text-fg">{a.name || a.id}</span>
        <span
          className="min-w-0 truncate text-sm"
          style={{ color: working ? WORKING_DOT : 'var(--c-faint)' }}
        >
          {working ? (task ? `正在处理：${task}` : '工作中') : '空闲中'}
        </span>
      </div>
    </div>
  )
}

function TodoHead({ t, avatar, waiting }: { t: MaWorkflowTodo; avatar?: string | null; waiting: string[] }) {
  const tone = todoTone(t.status)
  const note = t.status === 'blocked' ? t.blockReason : t.status === 'done' ? t.result : t.detail
  const doing = t.status === 'doing'
  return (
    <>
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-sm text-fg">{t.title}</span>
        <span className="shrink-0">
          <Badge text={TODO_LABELS[t.status] ?? t.status} color={tone} />
        </span>
      </div>
      {note && <div className="truncate text-xs text-fg-2">{note}</div>}
      {/* 还在等前置：把「为什么它还没开工」写在卡上（未完成的前置标题） */}
      {waiting.length > 0 && (
        <div className="truncate text-xs text-faint">等待：{waiting.join('、')}</div>
      )}
      {/* 正在处理：把负责人（头像 + 名字）摆上卡片——一眼看出「这位成员此刻在啃它」 */}
      {doing && (
        <div className="flex items-center gap-1.5">
          <PeerAvatar
            name={t.ownerName || '公共'}
            avatar={avatar}
            size="h-6 w-6"
            textClass="text-[10px]"
            bordered={false}
          />
          <span className="truncate text-xs font-medium" style={{ color: ACCENT }}>
            {t.ownerName || '公共'}
          </span>
          <span className="shrink-0 text-2xs text-faint">正在处理</span>
        </div>
      )}
    </>
  )
}

/**
 * 任务卡右上角 ⋯ 弹出的菜单：人对这条任务的三项干预。
 *
 * <p>都<b>不打断</b>正在跑的那次执行（不抢占）：放弃 = 置 dropped（下游依赖随之解锁）；
 * 暂时跳过 = 压到最低优先级（进行中的先放回队列，跑完那条不被收成完成、转而排到最后）；
 * 立刻检查 = 拉满优先级插到该成员队首并叫起它的执行线程（受阻的会先放回待做，才跑得起来）。
 */
function TodoMenu({ t, onClose }: { t: MaWorkflowTodo; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  const run = (action: (id: string) => Promise<unknown>) => (e: ReactMouseEvent) => {
    e.stopPropagation()
    if (busy) return
    setBusy(true)
    void action(t.id)
      .catch(() => undefined)
      .finally(() => {
        setBusy(false)
        onClose()
      })
  }
  const pending = t.status === 'pending'
  const doing = t.status === 'doing'
  const blocked = t.status === 'blocked'
  return (
    <div
      data-todo-menu
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
      className="absolute right-1 top-7 z-30 w-28 overflow-hidden rounded-md py-1 text-sm"
      style={{
        background: 'var(--ui-card-fill, var(--c-bg))',
        boxShadow: 'var(--ui-e4, 0 12px 28px rgb(0 0 0 / .35))',
      }}
    >
      <MenuBtn label="放弃" disabled={t.status === 'dropped'} onClick={run(dropMaTodo)} />
      <MenuBtn label="暂时跳过" disabled={!(pending || doing)} onClick={run(deferMaTodo)} />
      <MenuBtn label="立刻检查" disabled={!(pending || blocked)} onClick={run(checkMaTodo)} />
    </div>
  )
}

function MenuBtn({
  label,
  disabled,
  onClick,
}: {
  label: string
  disabled?: boolean
  onClick: (e: ReactMouseEvent) => void
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="block w-full px-3 py-1.5 text-left text-fg-2 transition hover:bg-fg/[0.08] hover:text-fg disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
    >
      {label}
    </button>
  )
}

/** 绿色对勾：这条任务已完成 */
function GreenCheck() {
  return (
    <svg viewBox="0 0 16 16" className="h-3 w-3" aria-hidden="true">
      <path
        d="M3 8.4 6.2 11.6 13 4.7"
        fill="none"
        stroke={EDGE}
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/**
 * 任务视图的悬浮总览：整张桌子的 TODO 竖排一条流水（全局，不按成员分行）。
 *
 * <p>四列——正在处理这条的成员头像 / 完成的绿勾 / 编号（1.2.3.）/ 任务名，
 * 顺序即画布上那条绿色依赖链的顺序。刻意做得极简，尽量少占视觉面积。
 */
function TodoOverview({
  todos,
  avatarOf,
  onFocus,
}: {
  todos: MaWorkflowTodo[]
  avatarOf: (peerId?: string | null) => string | null
  onFocus: (id: string) => void
}) {
  const ordered = useMemo(() => topoOrder(todos), [todos])
  if (ordered.length === 0) {
    return null
  }
  return (
    /* 悬浮层：别让它上面的按下冒泡到画布（否则会顺手平移画布） */
    <div
      data-wf-overlay
      onPointerDown={(e) => e.stopPropagation()}
      className="scrollbar-slim absolute right-3 top-3 z-10 max-h-[calc(100%-1.5rem)] w-[15rem] overflow-y-auto rounded-md px-2 py-1.5"
      style={{
        background: 'var(--ui-card-fill, var(--c-bg))',
        boxShadow: 'var(--ui-e3, 0 8px 20px rgb(0 0 0 / .28))',
      }}
    >
      {ordered.map((t, i) => (
        <button
          key={t.id}
          type="button"
          onClick={() => onFocus(t.id)}
          className="flex w-full items-center gap-1.5 rounded px-1 py-[3px] text-left text-xs leading-none transition hover:bg-fg/[0.06]"
        >
          {/* 1. agent 位置：此刻正在处理这条的成员（没人在做则留空） */}
          <span className="flex h-4 w-4 shrink-0 items-center justify-center">
            {t.status === 'doing' && (
              <PeerAvatar
                name={t.ownerName || t.ownerPeer || '?'}
                avatar={avatarOf(t.ownerPeer)}
                size="h-4 w-4"
                textClass="text-[8px]"
                bordered={false}
              />
            )}
          </span>
          {/* 2. 完成：搞定的打一个绿勾，没搞定什么都不显示 */}
          <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center">
            {t.status === 'done' && <GreenCheck />}
          </span>
          {/* 3. 编号 */}
          <span className="w-4 shrink-0 text-right font-mono text-2xs text-faint">{i + 1}</span>
          {/* 4. 任务名（已放弃的划掉标题） */}
          <span
            className={`min-w-0 flex-1 truncate ${t.status === 'dropped' ? 'line-through' : ''}`}
            style={{ color: t.status === 'done' || t.status === 'dropped' ? 'var(--c-faint)' : 'var(--c-fg-2)' }}
          >
            {t.title}
          </span>
        </button>
      ))}
    </div>
  )
}

/** 右侧详情：Agent 看它的活；任务看它的执行过程（思考链）——这是「它在干嘛」的出口 */
function Detail({
  sel,
  data,
  onSelect,
  agentOf,
  todoOf,
}: {
  sel: { kind: 'agent' | 'todo'; id: string } | null
  data: MaWorkflow | null
  onSelect: (s: { kind: 'agent' | 'todo'; id: string }) => void
  agentOf: (id: string) => MaWorkflowAgent | undefined
  todoOf: (id: string) => MaWorkflowTodo | undefined
}) {
  const [runs, setRuns] = useState<MaWorkRun[]>([])

  const todoId = sel?.kind === 'todo' ? sel.id : null
  useEffect(() => {
    if (!todoId) {
      setRuns([])
      return
    }
    let alive = true
    const load = () =>
      maTodoRuns(todoId, 8)
        .then((r) => alive && setRuns(r))
        .catch(() => undefined)
    load()
    const timer = setInterval(load, 3000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [todoId])

  return (
    <div className="scrollbar-slim max-h-[60vh] overflow-y-auto px-4 py-3">
      {!sel && <p className="text-sm text-faint">点画布上的成员或任务，这里会显示它的过程。</p>}

      {sel?.kind === 'agent' &&
        (() => {
          const a = agentOf(sel.id)
          if (!a) return <p className="text-sm text-faint">这个成员已经不在了。</p>
          const mine = data?.todos.filter((t) => t.ownerPeer === a.id) ?? []
          return (
            <div className="space-y-3">
              <div>
                <h3 className="text-sm font-medium text-fg">{a.name || a.id}</h3>
                <div className="mt-1 flex flex-wrap gap-1">
                  {a.communication && <Badge text={COMM_LABELS[a.communication] ?? a.communication} />}
                  <Badge text={MA_EXEC_LABELS[a.execution] ?? a.execution} color={a.working ? ACCENT : undefined} />
                </div>
              </div>
              <div>
                <p className="mb-1 text-xs uppercase tracking-wider text-faint">它名下的任务</p>
                {mine.length === 0 && <p className="text-xs text-faint">暂时没有任务。</p>}
                <div className="space-y-1">
                  {mine.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => onSelect({ kind: 'todo', id: t.id })}
                      className="flex w-full items-center gap-2 rounded border border-line px-2 py-1.5 text-left transition hover:border-line-strong"
                    >
                      <span className="truncate text-xs text-fg">{t.title}</span>
                      <span className="ml-auto shrink-0">
                        <Badge text={TODO_LABELS[t.status] ?? t.status} color={todoTone(t.status)} />
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )
        })()}

      {sel?.kind === 'todo' &&
        (() => {
          const t = todoOf(sel.id)
          const header = t ? (
            <div className="space-y-1.5">
              <h3 className="text-sm font-medium text-fg">{t.title}</h3>
              <div className="flex flex-wrap gap-1">
                <Badge text={TODO_LABELS[t.status] ?? t.status} color={todoTone(t.status)} />
                <Badge text={t.ownerName ? `负责人 ${t.ownerName}` : '公共'} />
              </div>
              {t.detail && <p className="text-xs text-fg-2">{t.detail}</p>}
              {t.blockReason && <p className="text-xs text-red-500">受阻：{t.blockReason}</p>}
              {t.result && <p className="text-xs text-fg-2">结论：{t.result}</p>}
            </div>
          ) : (
            <p className="text-sm text-faint">这条任务已经不在了。</p>
          )
          return (
            <div className="space-y-3">
              {header}
              <div>
                <p className="mb-1 text-xs uppercase tracking-wider text-faint">
                  执行过程（{runs.length}）
                </p>
                {runs.length === 0 && (
                  <p className="text-xs text-faint">还没有执行记录——它可能还没开工。</p>
                )}
                <div className="space-y-2">
                  {runs.map((r) => (
                    <RunBlock key={r.id} run={r} />
                  ))}
                </div>
              </div>
            </div>
          )
        })()}
    </div>
  )
}

/** 一次执行的过程回放：状态 / 耗时 / 思考链 / 过程分段 / 产出 */
function RunBlock({ run }: { run: MaWorkRun }) {
  const [open, setOpen] = useState(run.status === 'running' || run.status === 'blocked' || run.status === 'failed')
  const tone =
    run.status === 'running'
      ? ACCENT
      : run.status === 'blocked' || run.status === 'failed'
        ? '#ef4444'
        : LINE
  const ms = (run.finishedAt ?? Date.now()) - run.startedAt
  return (
    <div className="rounded border border-line">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 px-2 py-1.5 text-left"
      >
        <Badge text={RUN_LABELS[run.status] ?? run.status} color={tone} />
        <span className="truncate text-xs text-faint">
          {run.peerName ?? run.peerId ?? ''} · {new Date(run.startedAt).toLocaleTimeString()} ·{' '}
          {(ms / 1000).toFixed(1)}s
        </span>
        <span className="ml-auto shrink-0 text-xs text-faint">{open ? '收起' : '展开'}</span>
      </button>
      {open && (
        <div className="space-y-2 border-t border-line px-2 py-2">
          {run.error && <p className="text-xs text-red-500">{run.error}</p>}
          {run.trace.length > 0 ? (
            <ol className="space-y-1.5">
              {run.trace.map((seg, i) => (
                <li key={i} className="text-xs">
                  {seg.type === 'reasoning' ? (
                    <div className="whitespace-pre-wrap text-fg-2">{seg.text}</div>
                  ) : (
                    <div className="text-faint">
                      <span className="text-fg-2">调用工具</span> {seg.name}
                      {seg.args && <span className="ml-1 opacity-70">{seg.args}</span>}
                    </div>
                  )}
                </li>
              ))}
            </ol>
          ) : (
            run.reasoning && (
              <p className="whitespace-pre-wrap text-xs text-fg-2">{run.reasoning}</p>
            )
          )}
          {run.output && (
            <div>
              <p className="mb-0.5 text-xs text-faint">产出</p>
              <p className="whitespace-pre-wrap text-xs text-fg-2">{run.output}</p>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
