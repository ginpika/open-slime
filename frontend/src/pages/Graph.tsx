import { AppIcon } from '../components/AppIcon'
import { lazy, Suspense, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  graphAnchors,
  graphCreateEntity,
  graphCreateRelation,
  graphEntities,
  graphNeighbors,
  graphUpdateEntityProps,
  graphUpdateRelationProps,
  loginUrlOf,
  type GraphEdge,
  type GraphNode,
  type Prop,
  type SearchGraph,
} from '../api/client'
import { Card } from '../components/Card'
import GraphCanvas, { typeColorMap, type GraphSel } from '../components/GraphCanvas'
import PropsEditor from '../components/PropsEditor'
import Toast from '../components/Toast'
import { btnOutline, btnPrimary, Control } from '../components/ui'
import { usePageKit } from '../kit'

/* ECharts 引擎懒加载：可选引擎，避免 echarts 体积拖累默认（自研）路径的首屏 */
const GraphECharts = lazy(() => import('../components/GraphECharts'))

/**
 * F3 图谱页：满屏知识图谱画布（渲染引擎可切：自研 SVG / ECharts force graph）。
 * 顶部悬浮工具栏（新增实体 / 实体搜索 / 跳数 / 规模 / 渲染引擎 / 物理开关），右侧悬浮详情卡片——
 * 点击节点或关系 fadeIn，点击空白背景 fadeOut（实体卡片内含新增关系入口）；左下角图例与谓词过滤。
 * 数据源：/api/graph/*。
 *
 * 呈现：满幅画布与悬浮玻璃胶囊（border-line + bg-bg/85 + backdrop-blur）不动；
 * 详情 / 空态卡交给主题化 Card（空态引导文案走 kit.Empty），关系行交给 kit.Row，
 * 实体类型标签走 kit.Badge，输入框包 Control 外壳，微标签走 ui-label。
 */

const edgeKey = (e: GraphEdge) => `${e.from}|${e.predicate}|${e.to}`

/** 毫秒 epoch → 本地时间（YYYY-MM-DD HH:mm），实体时间戳展示用 */
function fmtTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 实体行级时间戳 → Props 只读区行（created_at/updated_at 由后端列维护，不可编辑） */
function timePropsOf(n: GraphNode): Prop[] {
  const out: Prop[] = []
  if (n.createdAt != null) out.push({ k: 'created_at', v: fmtTime(n.createdAt), t: 'text' })
  if (n.updatedAt != null) out.push({ k: 'updated_at', v: fmtTime(n.updatedAt), t: 'text' })
  return out
}

/** 唯一标识行：monospace 展示，点击复制（复制后短暂反馈） */
function IdRow({ id }: { id: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(id)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1400)
    } catch {
      /* 非安全上下文或无剪贴板权限：静默忽略 */
    }
  }
  return (
    <div className="mt-2 flex items-center gap-2 rounded-lg border border-line bg-fg/5 px-2 py-1">
      <span className="ui-label shrink-0">ID</span>
      <span title={id} className="min-w-0 flex-1 truncate font-mono text-xs text-muted">
        {id}
      </span>
      <button
        type="button"
        onClick={() => void copy()}
        title="复制唯一标识"
        className="shrink-0 rounded-full border border-line px-2 py-0.5 text-2xs text-faint transition hover:border-line-strong hover:text-fg"
      >
        {copied ? '已复制' : '复制'}
      </button>
    </div>
  )
}

/** 渲染引擎：自研 SVG 力布局 / ECharts force graph */
type Engine = 'custom' | 'echarts'
const ENGINES: { id: Engine; label: string }[] = [
  { id: 'custom', label: '自研' },
  { id: 'echarts', label: 'ECharts' },
]

/** 401 → 跳登录；返回 true 表示已处理 */
function redirectIf401(e: unknown): boolean {
  const loginUrl = loginUrlOf(e)
  if (loginUrl) {
    window.location.assign(loginUrl)
    return true
  }
  return false
}

/** 读取 hash 查询串中的 entity 参数（#/graph?entity=…）：从搜索页实体命中跳入，1 跳展开 */
function entityFromHash(): string | null {
  const h = window.location.hash
  const qi = h.indexOf('?')
  if (qi < 0) return null
  const v = new URLSearchParams(h.slice(qi + 1)).get('entity')
  return v && v.trim() ? v.trim() : null
}

export default function Graph() {
  const kit = usePageKit()
  const [q, setQ] = useState('')
  const [sugs, setSugs] = useState<GraphNode[] | null>(null)
  const [sugOpen, setSugOpen] = useState(false)

  const [center, setCenter] = useState<GraphNode | null>(null)
  const [nodes, setNodes] = useState<GraphNode[]>([])
  const [edges, setEdges] = useState<GraphEdge[]>([])
  const [depth, setDepth] = useState(2)
  const [sel, setSel] = useState<GraphSel | null>(null)
  const [hidden, setHidden] = useState<Set<string>>(new Set())
  /* 隐藏的实体类型（左下角 TYPES 可点击过滤） */
  const [hiddenTypes, setHiddenTypes] = useState<Set<string>>(new Set())
  const [hubs, setHubs] = useState<GraphNode[]>([])
  const [busy, setBusy] = useState(false)
  /* 加载失败标记（仅用于隐藏空态引导；错误文案走 message box 轻提示） */
  const [err, setErr] = useState<string | null>(null)
  /** 错误轻提示（message box，替代画布上方悬浮错误条） */
  const [toast, setToast] = useState<string | null>(null)
  /* 实时物理模拟：默认开启（受力晃动 / 拖拽回弹） */
  const [physics, setPhysics] = useState(true)
  /* 本次数据更新是否自动适配视角：整图替换 true；增量展开 false（保持用户视角） */
  const [fitNext, setFitNext] = useState(true)
  /* 渲染引擎：默认 ECharts force graph，可切自研 SVG */
  const [engine, setEngine] = useState<Engine>('echarts')

  /* 新增实体弹窗（名称必填 / 类型留空默认 CONCEPT） */
  const [createOpen, setCreateOpen] = useState(false)
  const [newName, setNewName] = useState('')
  const [newType, setNewType] = useState('')
  const [creating, setCreating] = useState(false)

  /* 新增关系表单（relFor 记录所属实体 id：切换选中实体时自动收起） */
  const [relFor, setRelFor] = useState<string | null>(null)
  const [relName, setRelName] = useState('')
  const [relPred, setRelPred] = useState('')
  const [relSaving, setRelSaving] = useState(false)

  /* 换中心 / 改跳数：整图替换（自动适配视角） */
  const loadGraph = async (entity: GraphNode, d: number) => {
    setBusy(true)
    setErr(null)
    setFitNext(true)
    try {
      const g: SearchGraph = await graphNeighbors(entity.id, d)
      /* 中心实体以返回数据为准（深链跳入时只携带 id，名称/类型由此补全） */
      setCenter(g.nodes.find((n) => n.id === entity.id) ?? entity)
      setNodes(g.nodes)
      setEdges(g.edges)
      setSel({ kind: 'node', id: entity.id })
      setHidden(new Set())
      setHiddenTypes(new Set())
    } catch (e: unknown) {
      if (!redirectIf401(e)) {
        const msg = e instanceof Error ? e.message : String(e)
        setErr(msg)
        setToast(`图谱加载失败：${msg}`)
      }
    } finally {
      setBusy(false)
    }
  }

  const chooseCenter = (entity: GraphNode) => {
    setSugOpen(false)
    void loadGraph(entity, depth)
  }

  /* 锚点视图：一次拉取系统中全部锚点（含锚点间关系），整图替换并适配视角 */
  const showAnchors = async () => {
    if (busy) return
    setBusy(true)
    setErr(null)
    setFitNext(true)
    try {
      const g: SearchGraph = await graphAnchors()
      const first = g.nodes[0] ?? null
      setCenter(first)
      setNodes(g.nodes)
      setEdges(g.edges)
      setSel(null)
      setHidden(new Set())
      setHiddenTypes(new Set())
    } catch (e: unknown) {
      if (!redirectIf401(e)) {
        const msg = e instanceof Error ? e.message : String(e)
        setErr(msg)
        setToast(`锚点加载失败：${msg}`)
      }
    } finally {
      setBusy(false)
    }
  }

  /* 打开新增实体弹窗（清空上次输入） */
  const openCreate = () => {
    setNewName('')
    setNewType('')
    setCreateOpen(true)
  }

  /* 新增实体：成功后关闭弹窗并以新实体为中心（无关系时画布只显示该节点） */
  const submitCreate = async (ev: FormEvent) => {
    ev.preventDefault()
    const name = newName.trim()
    if (!name || creating) return
    setCreating(true)
    try {
      const node = await graphCreateEntity(name, newType.trim())
      setCreateOpen(false)
      chooseCenter(node)
    } catch (e: unknown) {
      if (!redirectIf401(e)) {
        const msg = e instanceof Error ? e.message : String(e)
        setToast(`新增实体失败：${msg}`)
      }
    } finally {
      setCreating(false)
    }
  }

  /* 打开新增关系表单（清空上次输入） */
  const openRel = (id: string) => {
    setRelFor(id)
    setRelName('')
    setRelPred('')
  }

  /* 新增关系：目标实体不存在时后端自动创建，成功后增量合并 1 跳（保持当前视角） */
  const submitRel = async (ev: FormEvent, subjectId: string) => {
    ev.preventDefault()
    const name = relName.trim()
    if (!name || relSaving) return
    setRelSaving(true)
    try {
      await graphCreateRelation(subjectId, name, relPred.trim())
      setRelFor(null)
      await expandFrom(subjectId)
    } catch (e: unknown) {
      if (!redirectIf401(e)) {
        const msg = e instanceof Error ? e.message : String(e)
        setToast(`新增关系失败：${msg}`)
      }
    } finally {
      setRelSaving(false)
    }
  }

  /* 首次进入：带 entity 参数（搜索页实体命中跳入）时以该实体为中心 1 跳展开，否则取度数最高的实体 */
  useEffect(() => {
    let alive = true
    const focusId = entityFromHash()
    graphEntities('')
      .then((hs) => {
        if (!alive) return
        setHubs(hs)
        if (focusId) {
          const found = hs.find((h) => h.id === focusId)
          setDepth(1)
          void loadGraph(found ?? { id: focusId, name: focusId, type: '' }, 1)
          return
        }
        if (hs.length > 0) void loadGraph(hs[0], 2)
      })
      .catch((e: unknown) => {
        if (alive && !redirectIf401(e)) {
          const msg = e instanceof Error ? e.message : String(e)
          setErr(msg)
          setToast(`图谱加载失败：${msg}`)
        }
      })
    return () => {
      alive = false
    }
  }, [])

  /* 实体搜索（防抖） */
  useEffect(() => {
    if (!q.trim()) {
      setSugs(null)
      return
    }
    const timer = setTimeout(() => {
      graphEntities(q)
        .then((list) => {
          setSugs(list)
          setSugOpen(true)
        })
        .catch(() => setSugs([]))
    }, 200)
    return () => clearTimeout(timer)
  }, [q])

  /* 选中节点向外合并一层（不替换已浏览的图；保持用户当前视角） */
  const expandFrom = async (id: string) => {
    if (busy) return
    setBusy(true)
    setErr(null)
    setFitNext(false)
    try {
      const g = await graphNeighbors(id, 1)
      setNodes((prev) => {
        const m = new Map(prev.map((n) => [n.id, n]))
        for (const n of g.nodes) m.set(n.id, n)
        return [...m.values()]
      })
      setEdges((prev) => {
        const m = new Map(prev.map((e) => [edgeKey(e), e]))
        for (const e of g.edges) m.set(edgeKey(e), e)
        return [...m.values()]
      })
      setSel({ kind: 'node', id })
    } catch (e: unknown) {
      if (!redirectIf401(e)) {
        const msg = e instanceof Error ? e.message : String(e)
        setErr(msg)
        setToast(`图谱展开失败：${msg}`)
      }
    } finally {
      setBusy(false)
    }
  }

  const setDepthAndReload = (d: number) => {
    setDepth(d)
    if (center) void loadGraph(center, d)
  }

  /* KV 附加数据：覆盖式保存后同步刷新本地 nodes / edges */
  const saveNodeProps = async (id: string, next: Prop[]): Promise<Prop[]> => {
    const clean = await graphUpdateEntityProps(id, next)
    setNodes((prev) => prev.map((n) => (n.id === id ? { ...n, props: clean } : n)))
    return clean
  }
  const saveEdgeProps = async (id: string, next: Prop[]): Promise<Prop[]> => {
    const clean = await graphUpdateRelationProps(id, next)
    setEdges((prev) => prev.map((e) => (e.id === id ? { ...e, props: clean } : e)))
    return clean
  }

  /* 派生：可见节点/边（类型过滤 + 谓词过滤） / 谓词计数 / 度数 / 索引 */
  const nodeVisible = useMemo(() => {
    const s = new Set<string>()
    for (const n of nodes) if (!hiddenTypes.has(n.type || 'CONCEPT')) s.add(n.id)
    return s
  }, [nodes, hiddenTypes])
  const visibleNodes = useMemo(
    () => nodes.filter((n) => nodeVisible.has(n.id)),
    [nodes, nodeVisible],
  )
  const visibleEdges = useMemo(
    () =>
      edges.filter(
        (e) => !hidden.has(e.predicate) && nodeVisible.has(e.from) && nodeVisible.has(e.to),
      ),
    [edges, hidden, nodeVisible],
  )
  const predicateCounts = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of edges) m.set(e.predicate, (m.get(e.predicate) ?? 0) + 1)
    return [...m.entries()].sort((a, b) => b[1] - a[1])
  }, [edges])
  const degreeOf = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of visibleEdges) {
      m.set(e.from, (m.get(e.from) ?? 0) + 1)
      m.set(e.to, (m.get(e.to) ?? 0) + 1)
    }
    return m
  }, [visibleEdges])
  const byId = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])
  const edgeByKey = useMemo(() => new Map(edges.map((e) => [edgeKey(e), e])), [edges])
  const typesPresent = useMemo(
    () => [...new Set(nodes.map((n) => n.type || 'CONCEPT'))],
    [nodes],
  )
  /* 类型 → 颜色：按当前全部类型动态生成（黄金角分布），画布/图例/详情卡片共用同一份 */
  const typeColors = useMemo(
    () => typeColorMap(nodes.map((n) => n.type || 'CONCEPT')),
    [nodes],
  )

  const togglePredicate = (p: string) => {
    setHidden((prev) => {
      const next = new Set(prev)
      if (next.has(p)) next.delete(p)
      else next.add(p)
      return next
    })
  }

  const toggleType = (tp: string) => {
    setHiddenTypes((prev) => {
      const next = new Set(prev)
      if (next.has(tp)) next.delete(tp)
      else next.add(tp)
      return next
    })
  }
  /* 批量显示 / 隐藏（图例标题下的两个按钮） */
  const setAllTypesHidden = (v: boolean) => setHiddenTypes(v ? new Set(typesPresent) : new Set())
  const setAllPredsHidden = (v: boolean) =>
    setHidden(v ? new Set(predicateCounts.map(([p]) => p)) : new Set())

  /* 详情卡片：选中即 fadeIn；清空选中先 fadeOut，动画结束后再卸载内容 */
  const [card, setCard] = useState<GraphSel | null>(null)
  const [cardOpen, setCardOpen] = useState(false)
  useEffect(() => {
    if (sel) {
      setCard(sel)
      setCardOpen(true)
      return
    }
    setCardOpen(false)
    const t = window.setTimeout(() => setCard(null), 240)
    return () => window.clearTimeout(t)
  }, [sel])

  const cardNode = card?.kind === 'node' ? (byId.get(card.id) ?? null) : null
  const cardEdge = card?.kind === 'edge' ? (edgeByKey.get(card.key) ?? null) : null
  const nameOf = (id: string) => byId.get(id)?.name ?? id
  const neighborsOf = (id: string) => {
    const out: { node: GraphNode; predicate: string; rev: boolean }[] = []
    for (const e of visibleEdges) {
      if (e.from === id) {
        const n = byId.get(e.to)
        if (n) out.push({ node: n, predicate: e.predicate, rev: false })
      } else if (e.to === id) {
        const n = byId.get(e.from)
        if (n) out.push({ node: n, predicate: e.predicate, rev: true })
      }
    }
    return out
  }
  /* 当前卡片实体的可见关系（新增关系成功后随 nodes / edges 一并刷新） */
  const cardRels = cardNode ? neighborsOf(cardNode.id) : []

  const submitSearch = (e: FormEvent) => {
    e.preventDefault()
    if (sugs && sugs.length > 0) chooseCenter(sugs[0])
    else setSugOpen(false)
  }

  /* 右下角 HUD 统计行：加载中给同步反馈，平时显示节点/边规模（数字宽度由 HUD 定宽吸收） */
  const stats = busy ? '同步中 …' : `${nodes.length} 节点 · ${visibleEdges.length} 边`

  return (
    <div className="relative h-full w-full overflow-hidden">
      {engine === 'custom' ? (
        <GraphCanvas
          nodes={visibleNodes}
          edges={visibleEdges}
          centerId={center?.id ?? null}
          selected={sel}
          hiddenPredicates={hidden}
          typeColors={typeColors}
          physics={physics}
          fitOnUpdate={fitNext}
          onSelect={setSel}
          onExpand={(id) => void expandFrom(id)}
        />
      ) : (
        <Suspense fallback={null}>
          <GraphECharts
            nodes={visibleNodes}
            edges={visibleEdges}
            centerId={center?.id ?? null}
            selected={sel}
            typeColors={typeColors}
            physics={physics}
            onSelect={setSel}
            onExpand={(id) => void expandFrom(id)}
          />
        </Suspense>
      )}

      {/* 右下角统计胶囊：两引擎共用，按钮式外观（min-w 吸收数字宽度变化，不抖动） */}
      {nodes.length > 0 && (
        <div className="absolute right-4 bottom-4 z-10 rounded-full border border-line bg-bg/85 px-4 py-1.5 font-mono text-xs leading-none text-muted shadow-lg backdrop-blur-md">
          <span className="inline-block min-w-[110px] text-center tabular-nums">{stats}</span>
        </div>
      )}

      {/* 左上角：锚点视图入口——点击列出并渲染系统中全部锚点 */}
      <div className="absolute left-4 top-4 z-30 flex items-center rounded-full border border-line bg-bg/85 p-1.5 shadow-lg backdrop-blur-md">
        <button
          type="button"
          onClick={() => void showAnchors()}
          disabled={busy}
          title="列出所有锚点：在画布中渲染系统中全部锚点"
          className="flex items-center gap-1 rounded-full border border-line px-3 py-1 text-sm font-semibold text-muted transition hover:border-line-strong hover:text-fg disabled:cursor-not-allowed disabled:opacity-50"
        >
          <AppIcon name="center" className="h-3.5 w-3.5" />
          全部锚点
        </button>
      </div>

      {/* 顶部悬浮工具栏：新增实体 / 搜索 / 跳数 / 引擎 / 物理（统计在右下角 HUD，避免数字宽度变化抖动） */}
      <div className="absolute left-1/2 top-4 z-30 flex max-w-[calc(100%-2rem)] -translate-x-1/2 items-center gap-1.5 rounded-full border border-line bg-bg/85 py-1 pl-1.5 pr-1.5 shadow-lg backdrop-blur-md">
        {/* 新增实体：打开弹窗录入名称 / 类型 */}
        <button
          type="button"
          onClick={openCreate}
          title="新增实体"
          className="flex items-center gap-1 rounded-full border border-line px-3 py-1 text-sm font-semibold text-muted transition hover:border-line-strong hover:text-fg"
        >
          <AppIcon name="plus" className="h-3.5 w-3.5" />
          新增实体
        </button>

        <span className="h-4 w-px bg-line" />

        <div className="relative">
          <form onSubmit={submitSearch}>
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onBlur={() => setTimeout(() => setSugOpen(false), 120)}
              onKeyDown={(e) => e.key === 'Escape' && setSugOpen(false)}
              placeholder="搜索实体…"
              className="w-40 bg-transparent py-1 text-base text-fg outline-none placeholder:text-hint"
            />
          </form>
          {sugOpen && sugs !== null && (
            <div className="absolute left-0 top-full z-40 mt-2 w-64 overflow-hidden rounded-xl border border-line bg-bg/95 shadow-xl backdrop-blur-md">
              {sugs.length === 0 ? (
                <p className="px-4 py-2.5 text-sm text-faint">没有匹配的实体。</p>
              ) : (
                sugs.map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    onMouseDown={() => chooseCenter(s)}
                    className="block w-full px-4 py-2 text-left text-base text-fg transition hover:bg-fg/5"
                  >
                    {s.name}
                    <span className="ml-2 text-xs text-faint">{s.type}</span>
                  </button>
                ))
              )}
            </div>
          )}
        </div>

        <span className="h-4 w-px bg-line" />

        <div className="flex items-center rounded-full bg-fg/5 p-0.5">
          {[1, 2].map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDepthAndReload(d)}
              className={
                d === depth
                  ? 'rounded-full bg-accent px-3 py-1 text-sm font-semibold text-accent-fg shadow-sm'
                  : 'rounded-full px-3 py-1 text-sm text-faint transition hover:text-fg'
              }
            >
              {d} 跳
            </button>
          ))}
        </div>

        <span className="h-4 w-px bg-line" />

        {/* 渲染引擎：自研 SVG 力布局 / ECharts force graph */}
        <div className="flex items-center rounded-full bg-fg/5 p-0.5">
          {ENGINES.map((eng) => (
            <button
              key={eng.id}
              type="button"
              onClick={() => setEngine(eng.id)}
              title={
                eng.id === 'custom'
                  ? '自研引擎：SVG + 世界坐标力布局'
                  : 'ECharts 引擎：force 力导向动态图'
              }
              className={
                eng.id === engine
                  ? 'rounded-full bg-accent px-3 py-1 text-sm font-semibold text-accent-fg shadow-sm'
                  : 'rounded-full px-3 py-1 text-sm text-faint transition hover:text-fg'
              }
            >
              {eng.label}
            </button>
          ))}
        </div>

        <span className="h-4 w-px bg-line" />

        {/* 物理开关（工具栏最右）：开启时节点受力晃动、拖拽回弹 */}
        <button
          type="button"
          onClick={() => setPhysics((v) => !v)}
          aria-pressed={physics}
          title="物理模拟：开启后节点受力晃动，拖拽松手回弹"
          className="flex items-center gap-2 rounded-full py-1 pl-3 pr-1.5 text-sm text-muted transition hover:text-fg"
        >
          物理
          <span
            className={`relative inline-block h-4 w-7 shrink-0 rounded-full transition-colors ${
              physics ? 'bg-accent' : 'bg-fg/20'
            }`}
          >
            <span
              className={`absolute top-0.5 h-3 w-3 rounded-full shadow-sm transition-all ${
                physics ? 'left-3.5 bg-bg' : 'left-0.5 bg-fg/60'
              }`}
            />
          </span>
        </button>
      </div>

      {/* 错误轻提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />

      {/* 新增实体弹窗：名称必填，类型自由输入、留空默认 CONCEPT（无阴影，与页面浮层同风格） */}
      {createOpen && (
        <div className="absolute inset-0 z-40 flex items-center justify-center px-4">
          {/* 透明遮罩：只用于点击空白处关闭，不压暗画布 */}
          <div className="absolute inset-0" onClick={() => setCreateOpen(false)} />
          <form
            onSubmit={(ev) => void submitCreate(ev)}
            onKeyDown={(ev) => ev.key === 'Escape' && setCreateOpen(false)}
            className="relative w-[340px] rounded-2xl border border-line bg-bg/95 p-5 backdrop-blur-md"
          >
            <h3 className="ui-heading text-md">新增实体</h3>
            <p className="mt-1 text-xs text-faint">
              同名实体不会重复创建，将更新其类型。
            </p>
            <label htmlFor="new-entity-name" className="ui-label mt-4 block">
              Name
            </label>
            <Control className="mt-1.5">
              <input
                id="new-entity-name"
                autoFocus
                value={newName}
                onChange={(ev) => setNewName(ev.target.value)}
                placeholder="实体名称"
                spellCheck={false}
              />
            </Control>
            <label htmlFor="new-entity-type" className="ui-label mt-3 block">
              Type
            </label>
            <Control className="mt-1.5">
              <input
                id="new-entity-type"
                value={newType}
                onChange={(ev) => setNewType(ev.target.value)}
                placeholder="自由输入，留空默认 CONCEPT"
                spellCheck={false}
              />
            </Control>
            <div className="mt-5 flex justify-end gap-2">
              <button type="button" onClick={() => setCreateOpen(false)} className={btnOutline()}>
                取消
              </button>
              <button
                type="submit"
                disabled={creating || !newName.trim()}
                className={`${btnPrimary()} disabled:cursor-not-allowed disabled:opacity-50`}
              >
                {creating ? '创建中 …' : '创建'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* 空态：从入口建议开始 */}
      {center === null && !busy && !err && (
        <div className="absolute inset-0 z-10 flex items-center justify-center px-4">
          <Card className="w-[380px] text-center" pad="lg">
            <p className="ui-heading text-md">知识图谱</p>
            <p className="mt-1.5 text-sm leading-relaxed text-muted">
              从关系最多的实体开始，或在上方搜索框定位任一实体。
            </p>
            {hubs.length === 0 ? (
              <kit.Empty>知识库还没有实体——先在检索页搜索，或通过管理接口灌入知识。</kit.Empty>
            ) : (
              <div className="mt-4 flex flex-wrap justify-center gap-2">
                {hubs.map((h) => (
                  <button
                    key={h.id}
                    type="button"
                    onClick={() => chooseCenter(h)}
                    className="rounded-full border border-line px-3 py-1.5 text-sm text-fg transition hover:border-line-strong hover:bg-fg/5"
                  >
                    {h.name}
                  </button>
                ))}
              </div>
            )}
          </Card>
        </div>
      )}

      {/* 右侧悬浮详情卡片：点击节点 / 关系 fadeIn，点击空白 fadeOut */}
      <div
        className={`absolute right-4 top-1/2 z-20 w-72 -translate-y-1/2 rounded-2xl border border-line bg-bg/90 p-4 shadow-xl backdrop-blur-md transition-all duration-200 ${
          cardOpen ? 'translate-x-0 opacity-100' : 'pointer-events-none translate-x-2 opacity-0'
        }`}
      >
        {cardNode && (
          <div>
            <div className="flex items-start gap-2">
              <span
                className="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ background: typeColors.get(cardNode.type || 'CONCEPT') ?? 'var(--c-faint)' }}
              />
              <div className="min-w-0">
                <h3 className="truncate text-md font-bold text-fg">{cardNode.name}</h3>
                <p className="mt-1 flex flex-wrap items-center gap-1.5 text-2xs text-faint">
                  <kit.Badge>{cardNode.type || 'CONCEPT'}</kit.Badge>
                  <span className="mono">{degreeOf.get(cardNode.id) ?? 0} 条关系</span>
                </p>
              </div>
            </div>
            <IdRow id={cardNode.id} />
            <div className="mt-3 flex gap-1.5">
              <button
                type="button"
                onClick={() => chooseCenter(cardNode)}
                className="rounded-full bg-accent px-3 py-1 text-xs font-semibold text-accent-fg transition hover:opacity-80"
              >
                以此为中心
              </button>
              <button
                type="button"
                onClick={() => void expandFrom(cardNode.id)}
                className="rounded-full border border-line px-3 py-1 text-xs text-muted transition hover:border-line-strong hover:text-fg"
              >
                展开一层
              </button>
              {/* 新增关系：展开下方 Relations 区块内的内联表单 */}
              <button
                type="button"
                onClick={() => openRel(cardNode.id)}
                title="为当前实体新增一条关系"
                className="rounded-full border border-line px-3 py-1 text-xs text-muted transition hover:border-line-strong hover:text-fg"
              >
                新增关系
              </button>
            </div>
            <PropsEditor
              props={cardNode.props ?? []}
              readOnlyProps={timePropsOf(cardNode)}
              onSave={(next) => saveNodeProps(cardNode.id, next)}
            />
            <div className="mt-3">
              <p className="ui-label">Relations</p>
              {relFor === cardNode.id ? (
                /* 内联表单：目标实体不存在时后端按 CONCEPT 自动创建 */
                <form
                  onSubmit={(ev) => void submitRel(ev, cardNode.id)}
                  onKeyDown={(ev) => ev.key === 'Escape' && setRelFor(null)}
                  className="mt-2 space-y-1.5"
                >
                  <input
                    autoFocus
                    value={relName}
                    onChange={(ev) => setRelName(ev.target.value)}
                    placeholder="目标实体名称"
                    spellCheck={false}
                    className="w-full rounded-xl border border-line bg-bg px-2.5 py-1.5 text-sm text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25"
                  />
                  <input
                    value={relPred}
                    onChange={(ev) => setRelPred(ev.target.value)}
                    placeholder="谓词，留空默认 relates_to"
                    spellCheck={false}
                    className="w-full rounded-xl border border-line bg-bg px-2.5 py-1.5 text-sm text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25"
                  />
                  <div className="flex justify-end gap-1.5 pt-0.5">
                    <button
                      type="button"
                      onClick={() => setRelFor(null)}
                      className="rounded-full px-2.5 py-1 text-xs text-faint transition hover:text-fg"
                    >
                      取消
                    </button>
                    <button
                      type="submit"
                      disabled={relSaving || !relName.trim()}
                      className="rounded-full bg-accent px-3 py-1 text-xs font-semibold text-accent-fg transition hover:opacity-80 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {relSaving ? '添加中 …' : '添加'}
                    </button>
                  </div>
                </form>
              ) : cardRels.length === 0 ? (
                <p className="mt-1.5 text-xs text-faint">暂无关系。</p>
              ) : (
                /* 关系行交给 kit.Row：悬停黄晕染 + 左侧读数条，行内「谓词 + 实体名」 */
                <div className="scrollbar-accent mt-1.5 max-h-44 overflow-y-auto">
                  {cardRels.map(({ node, predicate, rev }) => (
                    <kit.Row
                      key={`${node.id}-${predicate}`}
                      onClick={() => setSel({ kind: 'node', id: node.id })}
                      className="items-baseline gap-2"
                    >
                      <span className="mono shrink-0 text-2xs text-faint">
                        {predicate}
                        {rev ? ' (R)' : ''}
                      </span>
                      <span className="min-w-0 flex-1 truncate text-right text-sm text-fg">
                        {node.name}
                      </span>
                    </kit.Row>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {cardEdge && (
          <div>
            <p className="ui-label">Relation</p>
            <p className="mt-2 inline-block rounded-full bg-fg/5 px-2.5 py-1 text-base font-semibold text-fg">
              {cardEdge.predicate}
            </p>
            <IdRow id={cardEdge.id} />
            <div className="mt-3 flex items-center gap-2">
              <button
                type="button"
                onClick={() => setSel({ kind: 'node', id: cardEdge.from })}
                className="flex min-w-0 items-center gap-1.5 rounded-lg border border-line px-2 py-1 text-sm text-fg transition hover:border-line-strong"
              >
                <span
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{
                    background:
                      typeColors.get(byId.get(cardEdge.from)?.type || 'CONCEPT') ??
                      'var(--c-faint)',
                  }}
                />
                <span className="truncate">{nameOf(cardEdge.from)}</span>
              </button>
              <span className="shrink-0 text-faint">→</span>
              <button
                type="button"
                onClick={() => setSel({ kind: 'node', id: cardEdge.to })}
                className="flex min-w-0 items-center gap-1.5 rounded-lg border border-line px-2 py-1 text-sm text-fg transition hover:border-line-strong"
              >
                <span
                  className="h-2 w-2 shrink-0 rounded-full"
                  style={{
                    background:
                      typeColors.get(byId.get(cardEdge.to)?.type || 'CONCEPT') ??
                      'var(--c-faint)',
                  }}
                />
                <span className="truncate">{nameOf(cardEdge.to)}</span>
              </button>
            </div>
            <p className="mt-2.5 text-xs text-faint">点击端点查看实体详情。</p>
            <PropsEditor
              props={cardEdge.props ?? []}
              onSave={(next) => saveEdgeProps(cardEdge.id, next)}
            />
          </div>
        )}
      </div>

      {/* 左下角：类型图例 + 谓词过滤 */}
      {center !== null && (
        <div className="absolute bottom-4 left-4 z-20 w-60 rounded-2xl border border-line bg-bg/85 p-3.5 shadow-lg backdrop-blur-md">
          <p className="ui-label">Types</p>
          <div className="mt-1 flex gap-2.5">
            <button
              type="button"
              onClick={() => setAllTypesHidden(true)}
              className="text-2xs text-faint transition hover:text-fg"
            >
              全部隐藏
            </button>
            <button
              type="button"
              onClick={() => setAllTypesHidden(false)}
              className="text-2xs text-faint transition hover:text-fg"
            >
              全部显示
            </button>
          </div>
          <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1">
            {typesPresent.map((tp) => (
              <button
                key={tp}
                type="button"
                onClick={() => toggleType(tp)}
                title="点击显示 / 隐藏该类型实体"
                className={`flex items-center gap-1.5 text-xs transition ${
                  hiddenTypes.has(tp)
                    ? 'text-faint line-through opacity-60'
                    : 'text-muted hover:text-fg'
                }`}
              >
                <span
                  className="h-2 w-2 rounded-full"
                  style={{ background: typeColors.get(tp) ?? 'var(--c-faint)' }}
                />
                {tp}
              </button>
            ))}
          </div>
          {predicateCounts.length > 0 && (
            <>
              <p className="ui-label mt-3">Relations</p>
              <div className="mt-1 flex gap-2.5">
                <button
                  type="button"
                  onClick={() => setAllPredsHidden(true)}
                  className="text-2xs text-faint transition hover:text-fg"
                >
                  全部隐藏
                </button>
                <button
                  type="button"
                  onClick={() => setAllPredsHidden(false)}
                  className="text-2xs text-faint transition hover:text-fg"
                >
                  全部显示
                </button>
              </div>
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {predicateCounts.map(([p, count]) => (
                  <button
                    key={p}
                    type="button"
                    onClick={() => togglePredicate(p)}
                    className={`rounded-full border border-line px-2 py-0.5 text-xs transition ${
                      hidden.has(p)
                        ? 'text-faint line-through opacity-60'
                        : 'text-fg hover:border-line-strong'
                    }`}
                  >
                    {p}
                    <span className="ml-1 font-mono text-2xs text-faint">{count}</span>
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
