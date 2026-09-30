import { useEffect, useMemo, useRef } from 'react'
import * as echarts from 'echarts/core'
import type { ComposeOption, ECElementEvent } from 'echarts/core'
import { GraphChart, type GraphSeriesOption } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import type { GraphEdge, GraphNode } from '../api/client'
import { useThemeState } from '../theme/store'
import { forceLayout } from './graphLayout'
import type { GraphSel } from './GraphCanvas'

/**
 * 图谱页 ECharts 引擎（graph 系列），两种布局模式：
 * - 物理（physics=true）：force 力导向动态模拟（Graph Dynamic 风格），
 *   layoutAnimation 逐帧迭代——节点受力晃动、拖拽松手回弹。
 * - 静态（physics=false）：layout:'none' + 组件管理坐标。关闭物理即冻结在
 *   当前位置；拖拽走 ECharts 'none' 分支（即时 setItemLayout + 边跟随，
 *   只有被拖节点移动，不触发任何力学重排）；拓扑变化（展开/过滤）时用
 *   确定性 forceLayout（graphLayout.ts，与自研引擎同源算法）以旧位置为
 *   seed 排入新节点。
 * 两种模式坐标无缝衔接：物理→静态读回当前布局冻结；静态→物理把坐标灌回
 * force（x/y + initLayout:'none'，并清除内部 preservedPoints 位置缓存）。
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

/** ECharts series model 内部结构（官方未导出的稳定内部 API：
 *  getData 布局读回、preservedPoints 力布局位置缓存清除） */
type SeriesInternal = {
  getData: () => {
    count: () => number
    getId: (i: number) => string
    getItemLayout: (i: number) => number[] | undefined
  }
  preservedPoints: Record<string, number[]> | null
}

function internalSeries(chart: echarts.ECharts): SeriesInternal | null {
  try {
    const api = chart as unknown as {
      getModel?: () => { getSeriesByIndex?: (i: number) => unknown }
    }
    const s = api.getModel?.().getSeriesByIndex?.(0)
    return typeof s === 'object' && s !== null ? (s as SeriesInternal) : null
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
  const pts = forceLayout(nodes, edges, { seedById: seedWorld, centerId })
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
  typeColors,
}: Props) {
  const boxRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)
  /* 主题令牌快照：主题切换时 vars 变化 → option 重建 → 颜色实时跟随 */
  const vars = useThemeState().vars
  /* 事件回调镜像：chart 事件只绑定一次，经 ref 读最新回调（渲染后同步） */
  const cbRef = useRef({ onSelect, onExpand })
  useEffect(() => {
    cbRef.current = { onSelect, onExpand }
  })

  const selNodeId = selected?.kind === 'node' ? selected.id : null
  const selEdgeKey = selected?.kind === 'edge' ? selected.key : null

  /* 静态模式坐标（id → view 坐标）：layout:'none' 的位置来源 */
  const posRef = useRef<Map<string, Pt> | null>(null)
  /* 上次 setOption 的拓扑：判断本次是否拓扑变化（决定静态布局是否重排） */
  const topoRef = useRef<{ nodes: GraphNode[]; edges: GraphEdge[] }>({ nodes, edges })

  const option = useMemo<ECOption>(() => {
    const c = {
      bg: vars['--c-bg'] ?? '#ffffff',
      fg: vars['--c-fg'] ?? '#000000',
      muted: vars['--c-muted'] ?? '#666666',
      faint: vars['--c-faint'] ?? '#999999',
      line: vars['--c-line'] ?? '#cccccc',
    }
    const typeColor = (t: string) => typeColors.get(t || 'CONCEPT') ?? c.faint

    /* 度数决定节点大小：高连接实体按比例变大（与自研引擎同思路，封顶直径 44） */
    const degree = new Map<string, number>()
    for (const e of edges) {
      degree.set(e.from, (degree.get(e.from) ?? 0) + 1)
      degree.set(e.to, (degree.get(e.to) ?? 0) + 1)
    }
    const edgeKey = (e: GraphEdge) => `${e.from}|${e.predicate}|${e.to}`
    /* 边较少时显示全部谓词标签，多时仅选中/邻接显示 + hover 显示 */
    const showEdgeLabel = edges.length <= 18

    const data: KgNode[] = nodes.map((n) => {
      const em = n.id === centerId || n.id === selNodeId
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
        label: {
          show: true,
          position: 'bottom' as const,
          color: em ? c.fg : c.muted,
          fontWeight: em ? 700 : 400,
          fontSize: 12,
          textBorderColor: c.bg,
          textBorderWidth: 3,
        },
      }
    })

    const links: KgLink[] = edges.map((e) => {
      const hi =
        edgeKey(e) === selEdgeKey ||
        (selNodeId !== null && (e.from === selNodeId || e.to === selNodeId))
      return {
        source: e.from,
        target: e.to,
        predicate: e.predicate,
        lineStyle: { color: hi ? c.fg : c.line, width: hi ? 1.8 : 1, opacity: 1 },
        label: {
          show: showEdgeLabel || hi,
          formatter: (p: { data?: unknown }) =>
            (p.data as { predicate?: string } | undefined)?.predicate ?? '',
          color: hi ? c.fg : c.faint,
          fontSize: 11,
          textBorderColor: c.bg,
          textBorderWidth: 3,
        },
        emphasis: { label: { show: true } },
      }
    })

    return {
      series: [
        {
          id: 'kg',
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
          draggable: true,
          /* 同一对实体间的多重边自动错开弯曲 */
          autoCurveness: true,
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 6,
          /* hover 节点：突出其邻接子图，其余淡出 */
          emphasis: { focus: 'adjacency' as const, scale: 1.15 },
          data,
          links,
        },
      ],
    }
  }, [nodes, edges, centerId, selNodeId, selEdgeKey, physics, vars, typeColors])

  /* chart 生命周期 + 交互事件（一次绑定，回调经 ref 读最新） */
  useEffect(() => {
    const box = boxRef.current
    if (!box) return
    const chart = echarts.init(box)
    chartRef.current = chart

    const selNode = (p: ECElementEvent) => {
      const d = p.data as { id?: string } | undefined
      if (d?.id) cbRef.current.onSelect({ kind: 'node', id: d.id })
    }
    const selEdge = (p: ECElementEvent) => {
      const d = p.data as { source?: string; target?: string; predicate?: string } | undefined
      if (d?.source && d?.target) {
        cbRef.current.onSelect({ kind: 'edge', key: `${d.source}|${d.predicate ?? ''}|${d.target}` })
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

    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(box)
    return () => {
      ro.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  /* setOption 编排（双模式坐标衔接的核心）：
   * - 物理：正常 force setOption；若来自静态模式（posRef 有坐标），把坐标
   *   灌入作为力模拟起点（x/y + initLayout:'none'，并清除 preservedPoints
   *   位置缓存——它优先级高于 x/y，不清除会让节点跳回旧模拟位置）。
   * - 静态：layout:'none' 需要全量 x/y——先读回 chart 当前布局（含内建
   *   拖拽后的位置）；拓扑未变则原样冻结，拓扑变化（展开/过滤）用确定性
   *   forceLayout 以旧位置为 seed 重排（与自研引擎同语义）。 */
  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return
    const series = option.series
    const base = (Array.isArray(series) ? series[0] : series) as GraphSeriesOption
    const raw = base.data
    const dataArr = (Array.isArray(raw) ? raw : []) as KgNode[]
    const topoChanged =
      topoRef.current.nodes !== nodes || topoRef.current.edges !== edges

    if (physics) {
      const pos = posRef.current
      if (pos && pos.size > 0) {
        /* 静态→物理：坐标灌入，force 从当前位置继续模拟 */
        const sm = internalSeries(chart)
        if (sm) sm.preservedPoints = null
        const seededData = dataArr.map((d) => {
          const p = pos.get(d.id)
          return p ? { ...d, x: p[0], y: p[1] } : d
        })
        chart.setOption({
          ...option,
          series: [
            {
              ...base,
              force: { ...base.force, initLayout: 'none' },
              data: seededData,
            },
          ],
        })
        posRef.current = null
      } else {
        chart.setOption(option)
      }
    } else {
      /* 物理→静态：读回当前布局冻结；拓扑变化才重排 */
      const read = readLayout(chart)
      let pos: Map<string, Pt>
      if (!topoChanged && read && read.size > 0) {
        pos = read
      } else {
        const seed = (read && read.size > 0 ? read : posRef.current) ?? null
        pos = layoutStatic(nodes, edges, seed, centerId)
      }
      posRef.current = pos
      const seededData = dataArr.map((d) => {
        const p = pos.get(d.id)
        return p ? { ...d, x: p[0], y: p[1] } : d
      })
      chart.setOption({ ...option, series: [{ ...base, data: seededData }] })
    }
    topoRef.current = { nodes, edges }
  }, [option, physics, nodes, edges, centerId])

  return <div ref={boxRef} className="h-full w-full" />
}
