import {
  useCallback,
  useEffect,
  useMemo,
  useId,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import type { GraphEdge, GraphNode } from '../api/client'
import { hslToHex } from '../theme/color'
import { edgeGeometry, edgeKey, edgeSlots, topologyKey, type GraphSel } from './graphRendering'
import {
  forceLayout,
  physicsTick,
  PHYS_ALPHA_MIN,
  type PhysicsState,
  type Pt,
} from './graphLayout'
export type { GraphSel } from './graphRendering'

/**
 * 图谱页交互画布：世界坐标力布局（graphLayout）+ pan/zoom 视口变换。
 * 交互：拖拽节点改位、空白拖拽平移、滚轮以光标为中心缩放、
 * 点击选中、双击节点向外展开一层（onExpand）。
 * physics 开启时由 rAF 实时模拟（斥力 + 边弹簧 + 向心 + 阻尼晃动，沉降后自动停帧）。
 * 力学世界与视口解绑：世界以原点为图心，尺度只由力模型平衡决定（见 FORCE_CONFIG），
 * 窗口 resize 仅影响观察（fit / 平移夹取），不再触发整图重排。
 * 颜色一律走 style（SVG 不认 var() 属性）。
 */

/** contain 适配边距：四周各留视口尺寸的 15%（内容区缩至 70%） */
const FIT_MARGIN = 0.15
/** 缩放范围（相对 contain 基准 fitK 的倍率）：任意图规模手感一致 */
const MIN_K_FACTOR = 0.5
const MAX_K_FACTOR = 12
/** 高连接实体最大半径（直径 32px = 普通节点直径 16px 的 2 倍） */
const R_MAX = 16

/**
 * 实体类型 → 颜色：按当前渲染的类型集合动态生成（类型去重排序后，
 * 黄金角色相 137.508° 逐个旋转 + 固定饱和度/亮度）——视感随机、彼此区分度高，
 * 同一份数据下稳定（隐藏类型再显示颜色不变）。LLM 可自由命名类型，
 * 不再依赖固定色板（旧 --c-entity-1..4 只覆盖 4 种预设类型）。
 *
 * 必须输出 hex（而非 CSS Color 4 的 `hsl(h s% l%)` 空格语法）：zrender 的颜色
 * parser 会先删除所有空格再按逗号分割，空格语法只切出 1 段而解析失败，其
 * lift() 在解析失败时无返回值——ECharts hover 走 liftColor(fill) 会得到
 * undefined，节点失去填充色（表现为 hover 时实体变白/消失并来回闪烁）。
 * hex 与主题令牌格式一致，SVG 与 canvas 两端都能正确解析。
 */
export function typeColorMap(types: Iterable<string>): Map<string, string> {
  const list = [...new Set(types)].sort()
  const m = new Map<string, string>()
  list.forEach((t, i) => {
    m.set(t, hslToHex({ h: (i * 137.508) % 360, s: 0.68, l: 0.55 }))
  })
  return m
}

/** 节点半径：基础 8（中心/选中 11），随度数线性小步增长（每条边 +2），封顶 16 */
const radiusOf = (degree: number, emphasized: boolean) =>
  Math.min(R_MAX, (emphasized ? 11 : 8) + degree * 2)

type Props = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  centerId: string | null
  selected: GraphSel | null
  /** 被过滤掉的关系谓词 */
  hiddenPredicates: Set<string>
  /** 类型 → 颜色（typeColorMap 生成，图例/详情卡片共用同一份，保证跨组件同色） */
  typeColors: Map<string, string>
  onSelect: (sel: GraphSel | null) => void
  /** 双击节点：以它为起点展开一层 */
  onExpand: (id: string) => void
  /** 实时物理模拟开关 */
  physics: boolean
  /** 本次数据更新是否自动适配视角（换中心/改跳数 true；增量展开 false 保持用户视角） */
  fitOnUpdate?: boolean
}

/** 渲染内容边界（世界坐标，含节点圆 / 高亮环 / 下方标签） */
type Bounds = { minX: number; minY: number; maxX: number; maxY: number }

/** 完整可见变换（contain）：包围盒整体装入视口，四周各留 FIT_MARGIN 比例
 *  边距（可用区 = 视口的 70%），内容居中。布局稳定后以它为自动适配目标。 */
function containTransform(
  b: Bounds,
  vw: number,
  vh: number,
): { x: number; y: number; k: number } {
  const bw = Math.max(1, b.maxX - b.minX)
  const bh = Math.max(1, b.maxY - b.minY)
  const availW = vw * (1 - FIT_MARGIN * 2)
  const availH = vh * (1 - FIT_MARGIN * 2)
  const k = Math.min(availW / bw, availH / bh)
  return {
    x: vw / 2 - ((b.minX + b.maxX) / 2) * k,
    y: vh / 2 - ((b.minY + b.maxY) / 2) * k,
    k,
  }
}

/** 平移/缩放不夹取（自由探索无边界世界）：缩放最小倍率为 contain 基准的一半，
 *  缩到底全图必可见，不会真正迷路 */

export default function GraphCanvas({
  nodes,
  edges,
  centerId,
  selected,
  hiddenPredicates,
  typeColors,
  onSelect,
  onExpand,
  physics,
  fitOnUpdate = true,
}: Props) {
  const markerId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const selNodeId = selected?.kind === 'node' ? selected.id : null
  const boxRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const [size, setSize] = useState<{ w: number; h: number } | null>(null)
  const [positions, setPositions] = useState<Map<string, Pt>>(new Map())
  const [t, setT] = useState({ x: 0, y: 0, k: 1 })
  const [needFit, setNeedFit] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [hoverEdge, setHoverEdge] = useState<string | null>(null)
  const visibleEdges = useMemo(
    () => edges.filter((e) => !hiddenPredicates.has(e.predicate)),
    [edges, hiddenPredicates],
  )
  const pairSlots = useMemo(() => edgeSlots(visibleEdges), [visibleEdges])
  const labelWidths = useMemo(() => {
    const ctx = document.createElement('canvas').getContext('2d')
    if (ctx) ctx.font = `14px ${getComputedStyle(document.body).fontFamily}`
    return new Map([...nodes.map(n => n.name), ...visibleEdges.map(e => e.predicate)]
      .map(text => [text, ctx?.measureText(text).width ?? Array.from(text).length * 14]))
  }, [nodes, visibleEdges])

  /* 实体度数（全量边，过滤不影响大小）：高连接实体按比例变大 */
  const degreeOf = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of edges) {
      m.set(e.from, (m.get(e.from) ?? 0) + 1)
      m.set(e.to, (m.get(e.to) ?? 0) + 1)
    }
    return m
  }, [edges])

  /* 渲染内容精确边界（节点圆 + 高亮环 + 下方标签）：fit / 缩放基准共用 */
  const boundsOf = useCallback(
    (pos: Map<string, Pt>): Bounds | null => {
      let minX = Infinity
      let minY = Infinity
      let maxX = -Infinity
      let maxY = -Infinity
      for (const node of nodes) {
        const p = pos.get(node.id)
        if (!p) continue
        const r = radiusOf(
          degreeOf.get(node.id) ?? 0,
          node.id === centerId || node.id === selNodeId,
        )
        const ext = r + (node.id === centerId ? 5 : node.id === selNodeId ? 3 : 0)
        const yMax = p.y + ext + 27 // 标签绘制在节点下方
        const halfWidth = Math.max(ext, (labelWidths.get(node.name) ?? 0) / 2 + 4)
        if (p.x - halfWidth < minX) minX = p.x - halfWidth
        if (p.x + halfWidth > maxX) maxX = p.x + halfWidth
        if (p.y - ext < minY) minY = p.y - ext
        if (yMax > maxY) maxY = yMax
      }
      for (const e of visibleEdges) {
        const a = pos.get(e.from), b = pos.get(e.to)
        if (!a || !b) continue
        const geometry = edgeGeometry(e, a, b, R_MAX + 2, R_MAX + 7, pairSlots.get(edgeKey(e)))
        const halfWidth = (labelWidths.get(e.predicate) ?? 0) / 2 + 4
        minX = Math.min(minX, geometry.bounds.minX, geometry.label.x - halfWidth)
        maxX = Math.max(maxX, geometry.bounds.maxX, geometry.label.x + halfWidth)
        minY = Math.min(minY, geometry.bounds.minY, geometry.label.y - 14)
        maxY = Math.max(maxY, geometry.bounds.maxY, geometry.label.y + 4)
      }
      return minX === Infinity ? null : { minX, minY, maxX, maxY }
    },
    [nodes, degreeOf, centerId, selNodeId, labelWidths, visibleEdges, pairSlots],
  )
  const bounds = useMemo(() => boundsOf(positions), [positions, boundsOf])

  /* 浏览中心 id 的镜像 ref：物理循环每帧读取，向心力 × anchorBoost 锚定图心 */
  const centerIdRef = useRef(centerId)
  useEffect(() => {
    centerIdRef.current = centerId
  }, [centerId])
  /* 视角适配开关的镜像 ref：拓扑 effect 读取（不进依赖——它单独变化不触发重排） */
  const fitOnUpdateRef = useRef(fitOnUpdate)
  useEffect(() => {
    fitOnUpdateRef.current = fitOnUpdate
  }, [fitOnUpdate])
  /* 视角初始化标记 + 物理沉降后的二次校准定时器 */
  const tInitRef = useRef(false)
  const settleTimerRef = useRef(0)

  /* positions 的镜像 ref：物理循环 / 拖拽都在其上读写，再统一落到 state */
  const posRef = useRef<Map<string, Pt>>(new Map())
  const applyPos = useCallback((m: Map<string, Pt>) => {
    posRef.current = m
    setPositions(m)
  }, [])

  /* 实时物理运行态：rAF 循环驱动，alpha 冷却到阈值后自动停帧 */
  const simRef = useRef<PhysicsState & { running: boolean; raf: number }>({
    pos: new Map(),
    vel: new Map(),
    alpha: 0,
    running: false,
    raf: 0,
  })
  const idsRef = useRef<string[]>([])
  const springsRef = useRef<{ from: string; to: string }[]>([])
  const physOnRef = useRef(physics)
  useEffect(() => {
    physOnRef.current = physics
  }, [physics])

  const dragRef = useRef<
    | { kind: 'node'; pointerId: number; id: string; startClient: Pt; origin: Pt; moved: boolean }
    | { kind: 'pan'; pointerId: number; startClient: Pt; startT: { x: number; y: number }; moved: boolean }
    | null
  >(null)
  const suppressClickRef = useRef(false)

  /* 容器尺寸（ResizeObserver，供 fit / 光标换算）。
   * 注意：组件不能在无数据时 return null，否则容器未挂载、observer 失效。 */
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const sync = () => {
      const w = box.clientWidth
      const h = box.clientHeight
      setSize({ w, h })
      /* 首次拿到尺寸：世界原点（图心）对齐视口中心，从中间开始渲染 */
      if (!tInitRef.current && w > 0 && h > 0) {
        tInitRef.current = true
        setT({ x: w / 2, y: h / 2, k: 1 })
      }
    }
    sync()
    const ro = new ResizeObserver(sync)
    ro.observe(box)
    return () => ro.disconnect()
  }, [])

  /* ids / 可见边弹簧：物理循环每帧经 ref 读取最新拓扑 */
  useEffect(() => {
    idsRef.current = nodes.map((n) => n.id)
    springsRef.current = visibleEdges.map((e) => ({ from: e.from, to: e.to }))
  }, [nodes, visibleEdges])

  /* 物理循环：每帧推进一步并同步渲染；能量耗尽（沉降）自动停帧 */
  const ensureLoop = useCallback(() => {
    const s = simRef.current
    if (s.running) return
    s.running = true
    const step = () => {
      const cur = simRef.current
      const fixedId = dragRef.current?.kind === 'node' ? dragRef.current.id : null
      const moved = physicsTick(cur, idsRef.current, springsRef.current, {
        centerId: centerIdRef.current,
        fixedId,
      })
      cur.alpha *= 0.98
      applyPos(new Map(cur.pos))
      if (physOnRef.current && cur.alpha > PHYS_ALPHA_MIN && moved) {
        cur.raf = requestAnimationFrame(step)
      } else {
        cur.running = false
      }
    }
    s.raf = requestAnimationFrame(step)
  }, [applyPos])

  const stopLoop = useCallback(() => {
    const s = simRef.current
    if (s.running) {
      cancelAnimationFrame(s.raf)
      s.running = false
    }
  }, [])
  useEffect(() => stopLoop, [stopLoop])

  /* 物理开关：开启时从当前位置重新加热启动；关闭时停帧冻结在原地 */
  useEffect(() => {
    if (!physics) {
      stopLoop()
      return
    }
    const s = simRef.current
    s.pos = new Map(posRef.current)
    for (const id of idsRef.current) if (!s.vel.has(id)) s.vel.set(id, { x: 0, y: 0 })
    for (const id of [...s.vel.keys()]) if (!posRef.current.has(id)) s.vel.delete(id)
    s.alpha = 1
    ensureLoop()
  }, [physics, ensureLoop, stopLoop])

  /* 拓扑变化 → 重排（旧节点位置作 seed，保持稳定）。
   * 整图替换（fitOnUpdate）→ 自动适配视角；增量展开 → 保持用户当前视角，
   * 新节点在视野外由用户自行缩放/平移探索。
   * 视口尺寸不在此依赖中：resize 只改变观察方式，不再重排整图 */
  const topology = topologyKey(nodes, visibleEdges)
  const layoutRef = useRef({ topology: '', centerId })
  useEffect(() => {
    if (layoutRef.current.topology === topology && layoutRef.current.centerId === centerId) return
    const reset = layoutRef.current.centerId !== centerId
    layoutRef.current = { topology, centerId }
    if (nodes.length === 0) {
      stopLoop()
      simRef.current.alpha = 0
      window.clearTimeout(settleTimerRef.current)
      applyPos(new Map())
      simRef.current.pos = new Map()
      simRef.current.vel = new Map()
      return
    }
    const seed = !reset && posRef.current.size > 0 ? posRef.current : undefined
    const pts = forceLayout(nodes, visibleEdges, {
      seedById: seed,
      preserveSeed: true,
      centerId,
    })
    const m = new Map<string, Pt>()
    nodes.forEach((node, i) => m.set(node.id, pts[i]))
    applyPos(m)
    setNeedFit(fitOnUpdateRef.current)
    /* 同步实时物理：新节点速度归零；开启状态则重新加热晃动 */
    const s = simRef.current
    s.pos = new Map(m)
    for (const id of m.keys()) if (!s.vel.has(id)) s.vel.set(id, { x: 0, y: 0 })
    for (const id of [...s.vel.keys()]) if (!m.has(id)) s.vel.delete(id)
    if (physOnRef.current) {
      s.alpha = 1
      ensureLoop()
    }
  }, [nodes, visibleEdges, topology, applyPos, ensureLoop, centerId, stopLoop])

  /* 适应视图：包围盒完整装入视口（contain 语义，四周留 15% 边距，居中） */
  const fit = useCallback(() => {
    if (!size || size.w <= 0 || size.h <= 0) return
    const b = boundsOf(posRef.current)
    if (!b) return
    setT(containTransform(b, size.w, size.h))
  }, [size, boundsOf])
  const fitRef = useRef(fit)
  useEffect(() => {
    fitRef.current = fit
  }, [fit])

  /* 缩放基准 fitK：contain 适配的缩放系数——布局稳定后自动 fit 的目标尺度，
   * 用户缩放范围限制在其倍率区间内，任意图规模手感一致 */
  const fitK = useMemo(() => {
    if (!size || size.w <= 0 || size.h <= 0 || !bounds) return 1
    return containTransform(bounds, size.w, size.h).k
  }, [size, bounds])
  const fitKRef = useRef(fitK)
  useEffect(() => {
    fitKRef.current = fitK
  }, [fitK])

  useEffect(() => {
    if (!needFit || !size || size.w <= 0 || size.h <= 0) return
    window.clearTimeout(settleTimerRef.current)
    fit()
    setNeedFit(false)
    /* 物理模拟还会继续推挤节点：布局稳定（约 2s 沉降）后重算包围盒二次适配，
     * 用户交互（缩放 / 平移 / 拖拽）会取消该校准 */
    if (physOnRef.current) settleTimerRef.current = window.setTimeout(() => fitRef.current(), 2000)
  }, [needFit, fit, size])

  /* 卸载时清理校准定时器 */
  useEffect(() => () => window.clearTimeout(settleTimerRef.current), [])

  /* 滚轮缩放（以光标为锚点；非 passive 才能 preventDefault） */
  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return
    const onWheel = (ev: WheelEvent) => {
      ev.preventDefault()
      /* 用户接管视角后取消沉降校准 */
      window.clearTimeout(settleTimerRef.current)
      const rect = svg.getBoundingClientRect()
      const cx = ev.clientX - rect.left
      const cy = ev.clientY - rect.top
      setT((cur) => {
        const base = fitKRef.current
        const k2 = Math.min(
          base * MAX_K_FACTOR,
          Math.max(base * MIN_K_FACTOR, cur.k * Math.exp(-ev.deltaY * 0.0015)),
        )
        const wx = (cx - cur.x) / cur.k
        const wy = (cy - cur.y) / cur.k
        /* 光标锚点缩放，平移不夹取（自由探索） */
        return { k: k2, x: cx - wx * k2, y: cy - wy * k2 }
      })
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
  }, [])

  const onNodeDown = (id: string, ev: ReactPointerEvent<SVGGElement>) => {
    if (ev.button !== 0 || dragRef.current) return
    ev.stopPropagation()
    /* 拖拽节点时禁用浏览器默认行为（选中文本 / 拖动选择等） */
    ev.preventDefault()
    suppressClickRef.current = false
    window.clearTimeout(settleTimerRef.current)
    const origin = posRef.current.get(id)
    if (!origin) return
    ;(ev.currentTarget as Element).setPointerCapture(ev.pointerId)
    dragRef.current = {
      kind: 'node',
      pointerId: ev.pointerId,
      id,
      startClient: { x: ev.clientX, y: ev.clientY },
      origin,
      moved: false,
    }
    setDragging(true)
    /* 物理开启时拖拽即重新加热：邻居被弹簧拽动，产生跟手晃动 */
    if (physOnRef.current) {
      simRef.current.alpha = Math.max(simRef.current.alpha, 0.3)
      ensureLoop()
    }
  }

  const onBgDown = (ev: ReactPointerEvent<SVGSVGElement>) => {
    if (ev.button !== 0 || dragRef.current) return
    if (ev.target !== ev.currentTarget) return
    /* 平移画布时禁用浏览器默认行为（选中文本 / 松开触发搜索等） */
    ev.preventDefault()
    window.clearTimeout(settleTimerRef.current)
    svgRef.current?.setPointerCapture(ev.pointerId)
    dragRef.current = {
      kind: 'pan',
      pointerId: ev.pointerId,
      startClient: { x: ev.clientX, y: ev.clientY },
      startT: { x: t.x, y: t.y },
      moved: false,
    }
    setDragging(true)
  }

  const onPointerMove = (ev: ReactPointerEvent) => {
    const d = dragRef.current
    if (!d || d.pointerId !== ev.pointerId) return
    /* 拖拽进行中持续阻止默认行为（文本选择 / 自动滚动等） */
    if (ev.cancelable) ev.preventDefault()
    if (d.kind === 'node') {
      const wx = d.origin.x + (ev.clientX - d.startClient.x) / t.k
      const wy = d.origin.y + (ev.clientY - d.startClient.y) / t.k
      if (Math.hypot(ev.clientX - d.startClient.x, ev.clientY - d.startClient.y) > 3) {
        d.moved = true
      }
      /* 物理开启：位置直接写入模拟态（tick 跳过该节点），保持其余节点联动 */
      if (physOnRef.current) {
        const s = simRef.current
        s.pos.set(d.id, { x: wx, y: wy })
        s.vel.set(d.id, { x: 0, y: 0 })
        s.alpha = Math.max(s.alpha, 0.3)
        ensureLoop()
      }
      const next = new Map(posRef.current)
      next.set(d.id, { x: wx, y: wy })
      applyPos(next)
    } else {
      if (Math.hypot(ev.clientX - d.startClient.x, ev.clientY - d.startClient.y) > 3) {
        d.moved = true
      }
      /* 自由平移：无边界世界，不夹取——迷路时「适应」按钮一键回正 */
      setT((cur) => ({
        ...cur,
        x: d.startT.x + (ev.clientX - d.startClient.x),
        y: d.startT.y + (ev.clientY - d.startClient.y),
      }))
    }
  }

  const endDrag = (ev: ReactPointerEvent) => {
    const d = dragRef.current
    if (!d || d.pointerId !== ev.pointerId) return
    if (d.kind === 'node' && d.moved && ev.type === 'pointerup') {
      suppressClickRef.current = true
    }
    /* 空白点击（未拖动）→ 取消选中 */
    if (d.kind === 'pan' && !d.moved && ev.type === 'pointerup') {
      onSelect(null)
    }
    dragRef.current = null
    setDragging(false)
    if (ev.currentTarget.hasPointerCapture(ev.pointerId)) ev.currentTarget.releasePointerCapture(ev.pointerId)
  }

  const onNodeClick = (id: string) => {
    if (suppressClickRef.current) {
      suppressClickRef.current = false
      return
    }
    onSelect({ kind: 'node', id })
  }

  const showAllLabels = visibleEdges.length <= 18

  return (
    <div
      ref={boxRef}
      className="relative h-full w-full select-none overflow-hidden"
      style={{ cursor: dragging ? 'grabbing' : undefined }}
    >
      <svg
        ref={svgRef}
        width="100%"
        height="100%"
        onPointerDown={onBgDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onLostPointerCapture={endDrag}
        role="img"
        aria-label="知识图谱"
        style={{ touchAction: 'none' }}
      >
        <defs>
          <marker
            id={`gc-arr-${markerId}-weak`}
            viewBox="0 0 8 8"
            refX="8"
            refY="4"
            markerWidth="6.5"
            markerHeight="6.5"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L8,4 L0,8 z" style={{ fill: 'var(--c-faint)' }} />
          </marker>
          <marker
            id={`gc-arr-${markerId}-strong`}
            viewBox="0 0 8 8"
            refX="8"
            refY="4"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path d="M0,0 L8,4 L0,8 z" style={{ fill: 'var(--c-fg)' }} />
          </marker>
        </defs>

        <g transform={`translate(${t.x},${t.y}) scale(${t.k})`}>
          {visibleEdges.map((e) => {
            const a = positions.get(e.from)
            const b = positions.get(e.to)
            if (!a || !b) return null
            const key = edgeKey(e)
            const hi =
              (selected?.kind === 'edge' && selected.key === key) ||
              (selNodeId !== null && (e.from === selNodeId || e.to === selNodeId))
            const ra =
              radiusOf(
                degreeOf.get(e.from) ?? 0,
                e.from === centerId || e.from === selNodeId,
              ) + 2
            const rb =
              radiusOf(degreeOf.get(e.to) ?? 0, e.to === centerId || e.to === selNodeId) + 7
            const { path, label } = edgeGeometry(e, a, b, ra, rb, pairSlots.get(key))
            const showLabel = showAllLabels || hi || hoverEdge === key
            return (
              <g key={key}>
                <path
                  d={path}
                  fill="none"
                  strokeWidth={hi ? 1.6 : 1}
                  markerEnd={`url(#gc-arr-${markerId}-${hi ? 'strong' : 'weak'})`}
                  style={{ stroke: hi ? 'var(--c-fg)' : 'var(--c-line)' }}
                />
                {/* 加宽透明命中区：悬停显示标签，点击选中该关系 */}
                <path
                  d={path}
                  fill="none"
                  strokeWidth={12}
                  style={{ stroke: 'transparent', cursor: 'pointer' }}
                  onPointerEnter={() => setHoverEdge(key)}
                  onPointerLeave={() => setHoverEdge(null)}
                  onClick={() => onSelect({ kind: 'edge', key })}
                />
                {showLabel && (
                  <text
                    x={label.x}
                    y={label.y}
                    fontSize="14"
                    textAnchor="middle"
                    style={{
                      pointerEvents: 'none',
                      paintOrder: 'stroke',
                      stroke: 'var(--c-bg)',
                      strokeWidth: 4,
                      fill: hi ? 'var(--c-fg)' : 'var(--c-faint)',
                    }}
                  >
                    {e.predicate}
                  </text>
                )}
              </g>
            )
          })}

          {nodes.map((node) => {
            const p = positions.get(node.id)
            if (!p) return null
            const isCenter = node.id === centerId
            const isSel = node.id === selNodeId
            const r = radiusOf(degreeOf.get(node.id) ?? 0, isCenter || isSel)
            return (
              <g
                key={node.id}
                style={{ cursor: dragging ? 'grabbing' : 'grab' }}
                onPointerDown={(ev) => onNodeDown(node.id, ev)}
                onPointerUp={endDrag}
                onClick={() => onNodeClick(node.id)}
                onDoubleClick={() => onExpand(node.id)}
              >
                {isCenter && (
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r={r + 5}
                    style={{
                      fill: 'none',
                      stroke: 'var(--c-fg)',
                      strokeOpacity: 0.45,
                    }}
                  />
                )}
                {isSel && !isCenter && (
                  <circle
                    cx={p.x}
                    cy={p.y}
                    r={r + 3}
                    style={{ fill: 'none', stroke: 'var(--c-fg)', strokeWidth: 1.2 }}
                  />
                )}
                <circle
                  cx={p.x}
                  cy={p.y}
                  r={r}
                  strokeWidth={isSel || isCenter ? 1.5 : 1}
                  style={{
                    fill: typeColors.get(node.type || 'CONCEPT') ?? 'var(--c-faint)',
                    stroke: 'var(--c-bg)',
                  }}
                />
                <text
                  x={p.x}
                  y={p.y + r + 14}
                  fontSize="14"
                  fontWeight={isSel || isCenter ? 700 : 400}
                  textAnchor="middle"
                  style={{
                    pointerEvents: 'none',
                    paintOrder: 'stroke',
                    stroke: 'var(--c-bg)',
                    strokeWidth: 4,
                    fill: isSel || isCenter ? 'var(--c-fg)' : 'var(--c-muted)',
                  }}
                >
                  {node.name}
                </text>
              </g>
            )
          })}
        </g>
      </svg>
    </div>
  )
}
