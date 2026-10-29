import { useEffect, useId, useMemo, useRef, useState } from 'react'
import * as echarts from 'echarts/core'
import type { ComposeOption, ECElementEvent } from 'echarts/core'
import { GraphChart, type GraphSeriesOption } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import type { GraphEdge, GraphNode } from '../api/client'
import { useThemeState } from '../theme/store'
import { forceLayout } from './graphLayout'
import { syncForceDragHistory } from './echartsForceDrag'
import { edgeGeometry, edgeKey, edgeSlots, topologyKey, type GraphSel } from './graphRendering'

/**
 * 图谱页 ECharts 引擎（graph 系列），两种布局模式：
 * - 物理（physics=true）：force 力导向动态模拟（Graph Dynamic 风格），
 *   layoutAnimation 逐帧迭代——节点受力晃动、拖拽松手回弹。
 * - 静态（physics=false）：layout:'none' + 组件管理坐标。关闭物理即冻结在
 *   当前位置；拖拽走 ECharts 'none' 分支（即时 setItemLayout + 边跟随，
 *   只有被拖节点移动，不触发任何力学重排）；拓扑变化（展开/过滤）时用
 *   确定性 forceLayout（graphLayout.ts，与自研引擎同源算法）以旧位置为
 *   seed 排入新节点。
 * 两种模式切换时读回当前布局，用新 series 接续坐标，不修改内部缓存。
 * 交互对齐自研引擎：点击节点/关系选中、双击节点展开一层、点击空白取消、
 * roam 缩放平移。颜色经主题 store 取实际 hex（canvas 不认 CSS 变量），
 * 主题切换实时跟随。
 */

/* 按需注册：graph 系列 + canvas 渲染器（体积远小于全量 echarts） */
echarts.use([GraphChart, CanvasRenderer])

type ECOption = ComposeOption<GraphSeriesOption>
/** 关系数据项：ECharts 封闭类型 + predicate 自定义字段（选中 key / label 回调用） */
type KgLink = NonNullable<GraphSeriesOption['links']>[number] & { predicate: string }
/** view 坐标系下的点 [x, y] */
type Pt = [number, number]

/** series data 里的节点项（组件构造，含 id；静态模式注入 x/y） */
type KgNode = { id: string } & Record<string, unknown>

/** ECharts series model 适配边界，升级时须回归拖拽与模式切换。 */
type SeriesInternal = {
  forceLayout?: unknown
  getData: () => {
    count: () => number
    getId: (i: number) => string
    getItemLayout: (i: number) => number[] | undefined
  }
}

function internalSeries(chart: echarts.ECharts): SeriesInternal | null {
  try {
    const api = chart as unknown as {
      getModel?: () => { getSeriesByIndex?: (i: number) => unknown }
    }
    const s = api.getModel?.().getSeriesByIndex?.(0)
    return typeof s === 'object' && s !== null && typeof (s as SeriesInternal).getData === 'function' ? s as SeriesInternal : null
  } catch {
    return null
  }
}

/** 读回 chart 当前节点布局（view 坐标；含内建拖拽后的最新位置） */
function readLayout(chart: echarts.ECharts): Map<string, Pt> | null {
  const sm = internalSeries(chart)
  if (!sm) return null
  try {
    const data = sm.getData()
    const m = new Map<string, Pt>()
    for (let i = 0, n = data.count(); i < n; i++) {
      const p = data.getItemLayout(i)
      const id = data.getId(i)
      if (id && p && Number.isFinite(p[0]) && Number.isFinite(p[1])) {
        m.set(id, [p[0], p[1]])
      }
    }
    return m.size > 0 ? m : null
  } catch {
    return null
  }
}

/** 静态布局：确定性 forceLayout（与自研引擎同源算法）。
 *  力布局世界以原点为图心，与 view 坐标系（画布中心原点）同构——
 *  坐标直接进出、无需平移换算；视口尺寸不参与布局（物理画布与视口解绑）。 */
function layoutStatic(
  nodes: GraphNode[],
  edges: GraphEdge[],
  seed: Map<string, Pt> | null,
  centerId: string | null,
): Map<string, Pt> {
  const seedWorld = seed
    ? new Map([...seed].map(([id, p]) => [id, { x: p[0], y: p[1] }] as const))
    : undefined
  const pts = forceLayout(nodes, edges, { seedById: seedWorld, centerId, preserveSeed: true })
  const m = new Map<string, Pt>()
  nodes.forEach((n, i) => m.set(n.id, [pts[i].x, pts[i].y]))
  return m
}

type Props = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  centerId: string | null
  selected: GraphSel | null
  onSelect: (sel: GraphSel | null) => void
  /** 双击节点：以它为起点展开一层 */
  onExpand: (id: string) => void
  /** 力导向实时模拟开关（true=force 动态模拟；false=静态冻结、拖拽不重排） */
  physics: boolean
  /** 整图替换时自动适配；增量展开保持观察中心和比例。 */
  fitOnUpdate?: boolean
  /** 类型 → 颜色（typeColorMap 动态生成，与 GraphCanvas/图例共用同一份，保证同色） */
  typeColors: Map<string, string>
}

export default function GraphECharts({
  nodes,
  edges,
  centerId,
  selected,
  onSelect,
  onExpand,
  physics,
  fitOnUpdate = true,
  typeColors,
}: Props) {
  const markerId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const boxRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)
  /* 主题令牌快照：主题切换时 vars 变化 → option 重建 → 颜色实时跟随 */
  const vars = useThemeState().vars
  // ECharts graph 的自环端点重合，原生曲线退化；只为自环叠加 SVG，跟随其实际像素坐标。
  const chartEdges = useMemo(() => edges.filter(e => e.from !== e.to), [edges])
  const loops = useMemo(() => edges.filter(e => e.from === e.to), [edges])
  const loopSlots = useMemo(() => edgeSlots(loops), [loops])
  const [loopPositions, setLoopPositions] = useState<Map<string, Pt>>(new Map())
  /* 事件回调镜像：chart 事件只绑定一次，经 ref 读最新回调（渲染后同步） */
  const cbRef = useRef({ onSelect, onExpand })
  useEffect(() => {
    cbRef.current = { onSelect, onExpand }
  })

  /* 静态模式坐标（id → view 坐标）：layout:'none' 的位置来源 */
  const posRef = useRef<Map<string, Pt> | null>(null)
  /* 上次 setOption 的拓扑：判断本次是否拓扑变化（决定静态布局是否重排） */
  const topoRef = useRef<{ key: string; centerId: string | null; physics: boolean } | null>(null)
  const appliedOptionRef = useRef<ECOption | null>(null)
  const generationRef = useRef(0)
  const topology = topologyKey(nodes, edges)
  // 附加属性变化不重新 setOption，避免 force 重启和静态拖拽位置丢失。
  const appearance = JSON.stringify({
    nodes: nodes.map(({ id, name, type }) => ({ id, name, type })),
    edges: edges.map(e => ({ id: edgeKey(e), from: e.from, to: e.to, predicate: e.predicate })),
    centerId, typeColors: [...typeColors],
  })
  const renderData = useMemo<{
    nodes: GraphNode[]; edges: GraphEdge[]; centerId: string | null; typeColors: [string, string][]
  }>(() => JSON.parse(appearance), [appearance])

  const option = useMemo<ECOption>(() => {
    const { nodes, edges, centerId } = renderData
    const typeColors = new Map(renderData.typeColors)
    const c = {
      bg: vars['--c-bg'] ?? '#ffffff',
      fg: vars['--c-fg'] ?? '#000000',
      muted: vars['--c-muted'] ?? '#666666',
      faint: vars['--c-faint'] ?? '#999999',
      line: vars['--c-line'] ?? '#cccccc',
    }
    const typeColor = (t: string) => typeColors.get(t || 'CONCEPT') ?? c.faint

    /* 度数决定节点大小，高连接实体逐步变大，封顶直径 24。 */
    const degree = new Map<string, number>()
    for (const e of edges) {
      degree.set(e.from, (degree.get(e.from) ?? 0) + 1)
      degree.set(e.to, (degree.get(e.to) ?? 0) + 1)
    }
    /* 边较少时显示全部谓词标签，多时仅选中/邻接显示 + hover 显示 */
    const showEdgeLabel = edges.length <= 18

    const data: KgNode[] = nodes.map((n) => {
      const em = n.id === centerId
      return {
        id: n.id,
        name: n.name,
        /* 度数决定节点大小：线性小步增长，封顶 24（普通 12 的 2 倍，与自研引擎同规则） */
        symbolSize: Math.min(24, (em ? 14 : 12) + (degree.get(n.id) ?? 0) * 2),
        itemStyle: {
          color: typeColor(n.type),
          borderColor: em ? c.fg : c.bg,
          borderWidth: em ? 2.5 : 1.5,
        },
        emphasis: { itemStyle: { borderColor: c.fg, borderWidth: 2.5 }, label: { color: c.fg, fontWeight: 700 } },
        label: {
          show: true,
          position: 'bottom' as const,
          color: em ? c.fg : c.muted,
          fontWeight: em ? 700 : 400,
          fontSize: 14,
          textBorderColor: c.bg,
          textBorderWidth: 3,
        },
      }
    })

    const links: KgLink[] = edges.filter(e => e.from !== e.to).map((e) => {
      return {
        id: edgeKey(e),
        source: e.from,
        target: e.to,
        predicate: e.predicate,
        lineStyle: { color: c.line, width: 1, opacity: 1 },
        label: {
          show: showEdgeLabel,
          formatter: (p: { data?: unknown }) =>
            (p.data as { predicate?: string } | undefined)?.predicate ?? '',
          color: c.faint,
          fontSize: 12,
          textBorderColor: c.bg,
          textBorderWidth: 3,
        },
        emphasis: { lineStyle: { color: c.fg, width: 1.8 }, label: { show: true, color: c.fg } },
      }
    })

    return {
      series: [
        {
          id: 'kg',
          animation: false,
          left: '15%', right: '15%', top: '15%', bottom: '15%',
          preserveAspect: 'contain',
          type: 'graph' as const,
          /* 物理=force 动态模拟；静态=none（坐标由组件注入，拖拽不触发力学重排） */
          layout: physics ? ('force' as const) : ('none' as const),
          force: {
            initLayout: 'circular' as const,
            repulsion: 280,
            edgeLength: [50, 120],
            gravity: 0.12,
            friction: 0.6,
            layoutAnimation: true,
          },
          roam: true,
          // 镜头操作覆盖整个画布，不受 contain 后的内容矩形与漫游变换限制。
          roamTrigger: 'global',
          draggable: true,
          /* 同一对实体间的多重边自动错开弯曲 */
          autoCurveness: true,
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 6,
          /* hover 节点：仅放大自身，不淡出其他实体（避免来回移动时画面闪烁） */
          emphasis: { scale: 1.15 },
          data,
          links,
        },
      ],
    }
  }, [renderData, physics, vars])

  /* chart 生命周期 + 交互事件（一次绑定，回调经 ref 读最新） */
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const chart = echarts.init(box)
    chartRef.current = chart
    topoRef.current = null
    appliedOptionRef.current = null
    // setOption 和 resize 都可能重建 force 实例，更新完成后再安装实例级兼容处理。
    chart.on('updated', () => {
      const series = internalSeries(chart)
      if (series?.forceLayout) {
        const data = series.getData()
        syncForceDragHistory(series.forceLayout, index => data.getItemLayout(index))
      }
    })

    const selNode = (p: ECElementEvent) => {
      const d = p.data as { id?: string } | undefined
      if (d?.id) cbRef.current.onSelect({ kind: 'node', id: d.id })
    }
    const selEdge = (p: ECElementEvent) => {
      const d = p.data as { id?: string } | undefined
      if (d?.id) {
        cbRef.current.onSelect({ kind: 'edge', key: d.id })
      }
    }
    chart.on('click', (p: ECElementEvent) => {
      if (p.componentType !== 'series' || p.seriesType !== 'graph') return
      if (p.dataType === 'node') selNode(p)
      else if (p.dataType === 'edge') selEdge(p)
    })
    chart.on('dblclick', (p: ECElementEvent) => {
      if (p.componentType === 'series' && p.dataType === 'node') {
        const d = p.data as { id?: string } | undefined
        if (d?.id) cbRef.current.onExpand(d.id)
      }
    })

    /* 空白点击 → 取消选中。roam 平移拖拽松手不触发：
     * 记录空白 mousedown 位置，click 时位移 >3px 视为拖拽忽略 */
    let downPt: { x: number; y: number } | null = null
    chart.getZr().on('mousedown', (e: { target?: unknown; offsetX: number; offsetY: number }) => {
      downPt = e.target ? null : { x: e.offsetX, y: e.offsetY }
    })
    chart.getZr().on('click', (e: { target?: unknown; offsetX: number; offsetY: number }) => {
      if (e.target || !downPt) return
      const moved = Math.hypot(e.offsetX - downPt.x, e.offsetY - downPt.y)
      if (moved <= 3) cbRef.current.onSelect(null)
    })

    const ro = new ResizeObserver(() => {
      if (box.clientWidth > 0 && box.clientHeight > 0) chart.resize()
    })
    ro.observe(box)
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  /* 模式变化用新的 series 替换旧实例，避免写入 preservedPoints 私有缓存。
   * 坐标读回集中在只读适配器；静态增量布局只安置新节点。 */
  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    const series = option.series
    const base = (Array.isArray(series) ? series[0] : series) as GraphSeriesOption
    const data = base.data as KgNode[]
    const previous = topoRef.current
    if (appliedOptionRef.current === option && previous?.key === topology && previous.centerId === centerId) return
    const reset = !previous || previous.centerId !== centerId
    const changed = reset || previous.key !== topology
    const modeChanged = !previous || previous.physics !== physics
    const read = reset ? null : readLayout(chart)
    const seed = read ?? (reset ? null : posRef.current)
    const pos = changed || !seed ? layoutStatic(nodes, edges, seed, centerId) : seed
    posRef.current = pos

    // 读回的模拟位置会改变 dataRect。用公开像素转换保留观察中心和比例，
    // 避免关物理、改标签、增量展开时画面突然缩放/偏移。
    let view: { center: number[]; zoom: number } | undefined
    if (!reset && !(changed && fitOnUpdate) && previous && chart.getWidth() > 0 && chart.getHeight() > 0) {
      const origin = chart.convertToPixel({ seriesIndex: 0 }, [0, 0]) as number[]
      const unit = chart.convertToPixel({ seriesIndex: 0 }, [1, 0]) as number[]
      const center = chart.convertFromPixel({ seriesIndex: 0 }, [chart.getWidth() / 2, chart.getHeight() / 2]) as number[]
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
      for (const [x, y] of pos.values()) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y) }
      const rawScale = Math.min(chart.getWidth() * 0.7 / (maxX === minX ? 2 : maxX - minX), chart.getHeight() * 0.7 / (maxY === minY ? 2 : maxY - minY))
      const zoom = origin && unit ? Math.abs(unit[0] - origin[0]) / rawScale : NaN
      if (center?.every(Number.isFinite) && Number.isFinite(zoom) && zoom > 0) view = { center, zoom }
    }
    if (modeChanged || changed) generationRef.current++
    chart.setOption({ ...option, series: [{ ...base,
      id: 'kg-' + generationRef.current,
      ...(view ?? { center: null, zoom: 1 }),
      force: { ...base.force, initLayout: 'none' },
      data: data.map(d => { const p = pos.get(d.id); return p ? { ...d, x: p[0], y: p[1] } : d }),
    }] }, { replaceMerge: ['series'] })
    topoRef.current = { key: topology, centerId, physics }
    appliedOptionRef.current = option
  }, [option, topology, physics, centerId, nodes, edges, fitOnUpdate])

  // 高亮动作不经过布局流水线，选中不再重新加热 force 模拟。
  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    for (const dataType of ['node', 'edge']) {
      chart.dispatchAction({ type: 'downplay', seriesIndex: 0, dataType, highlightKey: 'kg-selection' })
    }
    if (selected?.kind === 'node') {
      const index = nodes.findIndex(n => n.id === selected.id)
      if (index >= 0) chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataType: 'node', dataIndex: index, highlightKey: 'kg-selection' })
    }
    const indices = chartEdges.flatMap((e, i) => selected && (selected.kind === 'edge'
      ? selected.key === edgeKey(e) : e.from === selected.id || e.to === selected.id) ? [i] : [])
    if (indices.length) chart.dispatchAction({ type: 'highlight', seriesIndex: 0, dataType: 'edge', dataIndex: indices, highlightKey: 'kg-selection' })
  }, [selected, option, nodes, chartEdges])

  useEffect(() => {
    const chart = chartRef.current
    if (!chart || loops.length === 0) return
    let raf = 0
    const update = () => {
      raf = 0
      const layout = readLayout(chart)
      const positions = new Map<string, Pt>()
      for (const e of loops) {
        const p = layout?.get(e.from)
        if (p) positions.set(e.from, chart.convertToPixel({ seriesIndex: 0 }, p) as Pt)
      }
      setLoopPositions(positions)
    }
    const schedule = () => { if (!raf) raf = requestAnimationFrame(update) }
    chart.on('rendered', schedule)
    update()
    return () => { chart.off('rendered', schedule); cancelAnimationFrame(raf) }
  }, [loops, option])

  return <div className="relative h-full w-full">
    <div ref={boxRef} className="h-full w-full" aria-label="知识图谱（ECharts）" />
    {loops.length > 0 && <svg className="pointer-events-none absolute inset-0 h-full w-full" aria-label="自环关系">
      <defs><marker id={`ec-loop-${markerId}`} viewBox="0 0 8 8" refX="8" refY="4" markerWidth="6" markerHeight="6" orient="auto">
        <path d="M0,0 L8,4 L0,8 z" style={{ fill: 'var(--c-muted)' }} />
      </marker></defs>
      {loops.map(e => {
        const p = loopPositions.get(e.from)
        if (!p) return null
        const key = edgeKey(e)
        const hi = selected?.kind === 'edge' ? selected.key === key : selected?.id === e.from
        const { path, label } = edgeGeometry(e, { x: p[0], y: p[1] }, { x: p[0], y: p[1] }, 14, 17, loopSlots.get(key))
        return <g key={key}>
          <path d={path} fill="none" strokeWidth={hi ? 1.8 : 1} markerEnd={`url(#ec-loop-${markerId})`} style={{ stroke: hi ? 'var(--c-fg)' : 'var(--c-muted)' }} />
          <path d={path} fill="none" strokeWidth={12} style={{ stroke: 'transparent', pointerEvents: 'stroke', cursor: 'pointer' }} onClick={() => onSelect({ kind: 'edge', key })} />
          <text x={label.x} y={label.y} fontSize={12} textAnchor="middle" style={{ fill: 'var(--c-muted)', stroke: 'var(--c-bg)', paintOrder: 'stroke', strokeWidth: 3 }}>{e.predicate}</text>
        </g>
      })}
    </svg>}
  </div>
}
