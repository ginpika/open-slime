/**
 * 数据流向图（关于页 · 「数据流向」Tab 的内容）。
 *
 * 一张自顶向下逐层生长的树状图（分层 DAG）：
 *   每个圆点 = 一个源码文件（类），连线 = 数据/调用流向，自上而下即「从产生到落库」。
 *   由于多个入口共享底层类（如三个 Controller 都写 DocumentStore），图实为分层有向无环图——
 *   这正是数据流的真实形状：多路汇聚、向下生长。
 *
 * 交互：
 *   hover 圆点 → 淡出无关部分、点亮邻接边，悬浮卡片展示该类的设计含义（为何存在）与数据流；
 *   滚轮缩放（光标锚点）· 拖拽平移 · 右下角 +/−/适配。
 * 布局：层内按「邻居重心」迭代排序（barycenter）减少连线交叉。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { usePageKit } from '../kit'
import { BACKEND_GRAPH, FRONTEND_GRAPH, type FlowGraph, type FlowKind, type GraphNode } from './dataflowData'

/* 角色 → 徽章文案 + 圆点配色（CSS 变量，主题切换实时跟随） */
const KIND_META: Record<FlowKind, { label: string; color: string }> = {
  root: { label: '入口 / 出口', color: 'var(--c-accent)' },
  config: { label: '配置', color: 'var(--c-muted)' },
  sso: { label: '认证', color: 'var(--c-hint)' },
  controller: { label: 'Controller', color: 'var(--c-entity-1)' },
  service: { label: '服务编排', color: 'var(--c-entity-2)' },
  hub: { label: '检索 / 图算法', color: 'var(--c-entity-3)' },
  llm: { label: 'LLM 客户端', color: 'var(--c-entity-4)' },
  store: { label: 'Store', color: 'var(--c-hint)' },
  table: { label: '表 / 落点', color: 'var(--c-fg)' },
  dto: { label: 'DTO', color: 'var(--c-muted)' },
  page: { label: '页面', color: 'var(--c-entity-1)' },
  component: { label: '组件', color: 'var(--c-entity-2)' },
  api: { label: 'API 层', color: 'var(--c-entity-3)' },
  theme: { label: '主题', color: 'var(--c-entity-4)' },
}

/** 节点半径：入口/出口略大，表/落点略小，其余统一 */
const nodeR = (n: GraphNode) => (n.kind === 'root' ? 15 : n.kind === 'table' ? 8 : 12.5)

/* ---------------- 分层布局 ---------------- */

type Pos = { x: number; y: number }

const COL = 132 // 同层列距
const LVL = 128 // 层间距
const PAD_L = 132 // 左侧层标签预留
const PAD_T = 30
const PAD_R = 36
const PAD_B = 54

/** 分层布局 + barycenter 迭代：同层节点按邻居平均 x 重排，减少连线交叉 */
function layout(g: FlowGraph) {
  const byLayer = new Map<number, GraphNode[]>()
  for (const n of g.nodes) {
    const arr = byLayer.get(n.layer) ?? []
    arr.push(n)
    byLayer.set(n.layer, arr)
  }
  const layers = [...byLayer.keys()].sort((a, b) => a - b)
  const maxCount = Math.max(...layers.map((l) => byLayer.get(l)!.length))
  const totalW = PAD_L + maxCount * COL + PAD_R
  const maxLayer = layers[layers.length - 1] ?? 0
  const totalH = PAD_T + maxLayer * LVL + PAD_B

  const pos = new Map<string, Pos>()
  const place = (l: number, order: GraphNode[]) => {
    const off = ((maxCount - order.length) * COL) / 2 // 少节点的层整体居中
    order.forEach((n, i) => pos.set(n.id, { x: PAD_L + off + (i + 0.5) * COL, y: PAD_T + l * LVL }))
  }
  layers.forEach((l) => place(l, byLayer.get(l)!))

  const nb = new Map<string, string[]>()
  for (const e of g.edges) {
    nb.set(e.from, [...(nb.get(e.from) ?? []), e.to])
    nb.set(e.to, [...(nb.get(e.to) ?? []), e.from])
  }
  for (let it = 0; it < 6; it++) {
    for (const l of layers) {
      const scored = (byLayer.get(l)!).map((n) => {
        const ns = nb.get(n.id) ?? []
        const b = ns.length ? ns.reduce((s, id) => s + (pos.get(id)?.x ?? 0), 0) / ns.length : (pos.get(n.id)!.x)
        return { n, b }
      })
      scored.sort((a, b) => a.b - b.b)
      place(l, scored.map((s) => s.n))
    }
  }
  return { pos, width: totalW, height: totalH, layers }
}

/** 边路径：跨层用垂直贝塞尔；同层（横向协作）用水平弧 */
function edgePath(a: Pos, b: Pos, ra: number, rb: number) {
  if (Math.abs(a.y - b.y) < 1) {
    const dir = b.x >= a.x ? 1 : -1
    const x1 = a.x + dir * (ra + 3)
    const x2 = b.x - dir * (rb + 6)
    const c = Math.max(34, Math.abs(b.x - a.x) * 0.35) * dir
    return `M ${x1} ${a.y} C ${x1 + c} ${a.y}, ${x2 - c} ${b.y}, ${x2} ${b.y}`
  }
  const y1 = a.y + ra + 1
  const y2 = b.y - rb - 5
  const dy = Math.max(22, (b.y - a.y) * 0.45)
  return `M ${a.x} ${y1} C ${a.x} ${y1 + dy}, ${b.x} ${y2 - dy}, ${b.x} ${y2}`
}

/* ---------------- 组件 ---------------- */

export default function DataFlow() {
  const kit = usePageKit()
  const [side, setSide] = useState<'frontend' | 'backend'>('backend')
  const g = side === 'backend' ? BACKEND_GRAPH : FRONTEND_GRAPH
  const { pos, width, height, layers } = useMemo(() => layout(g), [g])
  const nodesById = useMemo(() => new Map(g.nodes.map((n) => [n.id, n])), [g])

  /* 视口：平移 + 缩放 */
  const wrapRef = useRef<HTMLDivElement>(null)
  const [view, setView] = useState({ x: 0, y: 0, k: 1 })
  const [hover, setHover] = useState<string | null>(null)
  const [tip, setTip] = useState<{ n: GraphNode; x: number; y: number } | null>(null)
  const dragRef = useRef<{ px: number; py: number; vx: number; vy: number } | null>(null)

  const fit = useCallback(() => {
    const el = wrapRef.current
    if (!el) return
    const k = Math.min(Math.min(el.clientWidth / width, el.clientHeight / height) * 0.96, 1.15)
    setView({ k, x: (el.clientWidth - width * k) / 2, y: (el.clientHeight - height * k) / 2 })
  }, [width, height])

  useEffect(() => {
    fit()
  }, [fit])

  /* 滚轮缩放（需非被动监听以 preventDefault），以光标为锚点 */
  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      const mx = e.clientX - rect.left
      const my = e.clientY - rect.top
      const f = Math.exp(-e.deltaY * 0.0016)
      setView((v) => {
        const k = Math.min(2.4, Math.max(0.08, v.k * f))
        const ratio = k / v.k
        return { k, x: mx - (mx - v.x) * ratio, y: my - (my - v.y) * ratio }
      })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  const zoomBy = (f: number) => {
    const el = wrapRef.current
    if (!el) return
    const mx = el.clientWidth / 2
    const my = el.clientHeight / 2
    setView((v) => {
      const k = Math.min(2.4, Math.max(0.08, v.k * f))
      const ratio = k / v.k
      return { k, x: mx - (mx - v.x) * ratio, y: my - (my - v.y) * ratio }
    })
  }

  /* hover 邻接：点亮相关节点与边，其余淡出 */
  const adjSet = useMemo(() => {
    const s = new Set<string>()
    if (hover) {
      for (const e of g.edges) {
        if (e.from === hover) s.add(e.to)
        if (e.to === hover) s.add(e.from)
      }
    }
    return s
  }, [g, hover])

  const legendKinds = useMemo(() => {
    const seen: FlowKind[] = []
    for (const n of g.nodes) if (!seen.includes(n.kind)) seen.push(n.kind)
    return seen
  }, [g])

  const switchSide = (s: 'frontend' | 'backend') => {
    setSide(s)
    setHover(null)
    setTip(null)
  }

  /* tooltip 定位：跟随鼠标并防止溢出视口 */
  const tipLeft = tip ? Math.min(tip.x + 16, window.innerWidth - 336) : 0
  const tipTop = tip ? Math.min(tip.y + 18, window.innerHeight - 260) : 0

  return (
    <div className="flex flex-col gap-4">
      {/* 前端/后端子 Tab：经索引映射走 kit.Range（side 状态保持字符串键不动） */}
      <div className="flex flex-wrap items-center justify-start gap-3">
        <kit.Range
          options={[
            { value: 0, label: '前端' },
            { value: 1, label: '后端' },
          ]}
          value={side === 'frontend' ? 0 : 1}
          onChange={(v) => switchSide(v === 0 ? 'frontend' : 'backend')}
        />
      </div>

      {/* 图画布 */}
      <div
        ref={wrapRef}
        className="relative h-[64vh] min-h-[460px] touch-none select-none overflow-hidden rounded-md border border-line bg-bg cursor-grab active:cursor-grabbing"
        onDragStart={(e) => e.preventDefault()}
        onPointerDown={(e) => {
          if (e.button !== 0) return
          e.preventDefault() // 屏蔽浏览器默认的「按下即选中文本」行为
          ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
          dragRef.current = { px: e.clientX, py: e.clientY, vx: view.x, vy: view.y }
        }}
        onPointerMove={(e) => {
          const d = dragRef.current
          if (!d) return
          setView((v) => ({ ...v, x: d.vx + (e.clientX - d.px) / v.k, y: d.vy + (e.clientY - d.py) / v.k }))
        }}
        onPointerUp={() => (dragRef.current = null)}
        onPointerCancel={() => (dragRef.current = null)}
      >
        <svg className="h-full w-full">
          <defs>
            <marker id="df-arrow" viewBox="0 0 8 8" refX="6.5" refY="4" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0,1 L6.5,4 L0,7 z" style={{ fill: 'var(--c-line-strong)' }} />
            </marker>
            <marker id="df-arrow-hi" viewBox="0 0 8 8" refX="6.5" refY="4" markerWidth="7.5" markerHeight="7.5" orient="auto">
              <path d="M0,1 L6.5,4 L0,7 z" style={{ fill: 'var(--c-accent)' }} />
            </marker>
          </defs>

          <g transform={`translate(${view.x},${view.y}) scale(${view.k})`}>
            {/* 层背景带 + 层标签 */}
            {layers.map((l) => {
              const y = PAD_T + l * LVL
              return (
                <g key={`layer-${l}`}>
                  <rect
                    x={8}
                    y={y - LVL / 2 + 14}
                    width={width - 16}
                    height={LVL - 28}
                    rx={12}
                    style={{ fill: 'var(--c-fg)' }}
                    opacity={0.03}
                  />
                  <text x={18} y={y - LVL / 2 + 34} style={{ fill: 'var(--c-faint)', fontSize: 11 }}>
                    L{l} · {g.layerLabels[l]}
                  </text>
                </g>
              )
            })}

            {/* 边：数据流向（hover 时点亮邻接边） */}
            {g.edges.map((e, i) => {
              const a = pos.get(e.from)
              const b = pos.get(e.to)
              const na = nodesById.get(e.from)
              const nb2 = nodesById.get(e.to)
              if (!a || !b || !na || !nb2) return null
              const lit = hover === e.from || hover === e.to
              const dim = hover !== null && !lit
              return (
                <path
                  key={`e-${i}`}
                  d={edgePath(a, b, nodeR(na), nodeR(nb2))}
                  fill="none"
                  stroke={lit ? 'var(--c-accent)' : 'var(--c-line-strong)'}
                  strokeWidth={lit ? 2 : 1}
                  markerEnd={lit ? 'url(#df-arrow-hi)' : 'url(#df-arrow)'}
                  opacity={dim ? 0.08 : lit ? 1 : 0.5}
                  style={{ transition: 'opacity .15s, stroke-width .15s' }}
                />
              )
            })}

            {/* 节点：圆 + 类名 */}
            {g.nodes.map((n) => {
              const p = pos.get(n.id)
              if (!p) return null
              const r = nodeR(n)
              const dim = hover !== null && hover !== n.id && !adjSet.has(n.id)
              const isHover = hover === n.id
              return (
                <g
                  key={n.id}
                  transform={`translate(${p.x},${p.y})`}
                  opacity={dim ? 0.18 : 1}
                  style={{ cursor: 'pointer', transition: 'opacity .15s' }}
                  onMouseEnter={(e) => {
                    setHover(n.id)
                    setTip({ n, x: e.clientX, y: e.clientY })
                  }}
                  onMouseMove={(e) => setTip((t) => (t ? { ...t, x: e.clientX, y: e.clientY } : t))}
                  onMouseLeave={() => {
                    setHover(null)
                    setTip(null)
                  }}
                >
                  {isHover && (
                    <circle r={r + 6} fill="none" stroke="var(--c-accent)" strokeWidth="1.5" opacity={0.55} />
                  )}
                  <circle
                    r={r}
                    style={{ fill: KIND_META[n.kind].color }}
                    stroke="var(--c-bg)"
                    strokeWidth={2.5}
                  />
                  <text
                    y={r + 14}
                    textAnchor="middle"
                    className="select-none"
                    style={{
                      fill: 'var(--c-fg)',
                      fontSize: n.kind === 'table' ? 9.5 : 10.5,
                      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    }}
                  >
                    {n.name}
                  </text>
                </g>
              )
            })}
          </g>
        </svg>

        {/* 图例 */}
        <div className="pointer-events-none absolute left-3 bottom-3 flex max-w-[70%] flex-wrap gap-x-3.5 gap-y-1 rounded-md border border-line bg-bg/90 px-3 py-2 backdrop-blur-sm">
          {legendKinds.map((k) => (
            <span key={k} className="flex items-center gap-1.5 text-[10.5px] text-muted">
              <span className="h-2 w-2 rounded-full" style={{ background: KIND_META[k].color }} />
              {KIND_META[k].label}
            </span>
          ))}
        </div>

        {/* 缩放控制 */}
        <div className="absolute right-3 bottom-3 flex flex-col gap-1.5">
          <button
            type="button"
            onClick={() => zoomBy(1.25)}
            className="flex h-7 w-7 items-center justify-center rounded border border-line bg-bg text-[14px] font-semibold text-muted transition hover:border-line-strong hover:text-fg"
            aria-label="放大"
          >
            +
          </button>
          <button
            type="button"
            onClick={() => zoomBy(1 / 1.25)}
            className="flex h-7 w-7 items-center justify-center rounded border border-line bg-bg text-[14px] font-semibold text-muted transition hover:border-line-strong hover:text-fg"
            aria-label="缩小"
          >
            −
          </button>
          <button
            type="button"
            onClick={fit}
            className="flex h-7 w-7 items-center justify-center rounded border border-line bg-bg text-[12px] font-semibold text-muted transition hover:border-line-strong hover:text-fg"
            aria-label="适配视口"
          >
            ⤢
          </button>
        </div>

        {/* 操作提示 */}
        <span className="pointer-events-none absolute right-3 top-2.5 text-[10.5px] text-faint">
          滚轮缩放 · 拖拽平移 · 悬停查看设计含义
        </span>
      </div>

      {/* 悬浮卡片：设计含义 + 数据流 + 落点 */}
      {tip && (
        <div
          className="pointer-events-none fixed z-[80] w-80 max-w-[85vw] rounded-md border border-line bg-bg p-3.5 shadow-xl"
          style={{ left: tipLeft, top: tipTop }}
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: KIND_META[tip.n.kind].color }} />
            <span className="font-mono text-[13px] font-semibold text-fg">{tip.n.name}</span>
            <span className="rounded bg-fg/8 px-1.5 py-px text-[10px] text-muted">{KIND_META[tip.n.kind].label}</span>
            <span className="ml-auto shrink-0 text-[10px] text-faint">L{tip.n.layer} · {g.layerLabels[tip.n.layer]}</span>
          </div>
          <p className="mt-2.5 text-[11.5px] leading-relaxed text-muted">
            <span className="font-medium text-fg">为何存在 · </span>
            {tip.n.why}
          </p>
          {tip.n.flow && (
            <p className="mt-1.5 text-[11.5px] leading-relaxed text-muted">
              <span className="font-medium text-fg">数据流 · </span>
              {tip.n.flow}
            </p>
          )}
          {tip.n.sink && (
            <p className="mt-1.5 font-mono text-[10.5px] leading-relaxed text-faint">
              <span className="font-medium text-fg">落点 · </span>
              {tip.n.sink}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
