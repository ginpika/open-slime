import { useId, useMemo } from 'react'
import type { GraphEdge, GraphNode } from '../api/client'
import { forceLayout } from './graphLayout'

/**
 * 首页图谱小预览：≤ 数十节点的只读 SVG（确定性模拟力布局，无外部依赖）。
 * 交互/大图浏览在 /graph 页（GraphCanvas）。布局算法见 ./graphLayout。
 */

const VIEW_W = 360
const VIEW_H = 250

type Props = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** 命中实体 id 集合（图谱锚点放大高亮） */
  emphasized?: Set<string>
  /** 拟新增的关系（知识提取预览）：虚线 accent 高亮渲染，from/to 需在 nodes 中 */
  pendingEdges?: GraphEdge[]
  /** 外层尺寸类（默认撑满容器宽度）；放进定高的小容器时可传 'h-full w-auto' 收成缩略图 */
  className?: string
}

export default function GraphMini({ nodes, edges, emphasized, pendingEdges, className }: Props) {
  const uid = useId().replace(/[^a-zA-Z0-9]/g, '')
  const points = useMemo(
    () => forceLayout(nodes, edges, { outW: VIEW_W, outH: VIEW_H }),
    [nodes, edges],
  )
  const em = emphasized ?? new Set<string>()
  const pending = pendingEdges ?? []

  if (nodes.length === 0 || points.length === 0) return null
  const byId = new Map(nodes.map((node, i) => [node.id, i]))
  const showEdgeLabel = edges.length + pending.length <= 14
  const arrowId = `arr-${uid}`

  return (
    <svg
      className={className ?? 'h-auto w-full'}
      viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
      role="img"
      aria-label="关联图谱预览"
    >
      <defs>
        <marker
          id={arrowId}
          viewBox="0 0 8 8"
          refX="9"
          refY="4"
          markerWidth="7"
          markerHeight="7"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L8,4 L0,8 z" style={{ fill: 'var(--c-faint)' }} />
        </marker>
      </defs>

      {edges.map((edge, i) => {
        const u = byId.get(edge.from)
        const v = byId.get(edge.to)
        if (u === undefined || v === undefined) return null
        const a = points[u]
        const b = points[v]
        const mx = (a.x + b.x) / 2
        const my = (a.y + b.y) / 2
        return (
          <g key={`e${i}`}>
            <line
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              strokeWidth="1"
              markerEnd={`url(#${arrowId})`}
              style={{ stroke: 'var(--c-line)' }}
            />
            {showEdgeLabel && (
              <text
                x={mx}
                y={my - 4}
                fontSize="8"
                textAnchor="middle"
                style={{
                  paintOrder: 'stroke',
                  stroke: 'var(--c-bg)',
                  strokeWidth: 3,
                  fill: 'var(--c-faint)',
                }}
              >
                {edge.predicate}
              </text>
            )}
          </g>
        )
      })}

      {pending.map((edge, i) => {
        const u = byId.get(edge.from)
        const v = byId.get(edge.to)
        if (u === undefined || v === undefined) return null
        const a = points[u]
        const b = points[v]
        const mx = (a.x + b.x) / 2
        const my = (a.y + b.y) / 2
        return (
          <g key={`p${i}`}>
            <line
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              strokeWidth="1.5"
              strokeDasharray="5 3"
              markerEnd={`url(#${arrowId})`}
              style={{ stroke: 'var(--c-accent)' }}
            />
            {showEdgeLabel && (
              <text
                x={mx}
                y={my - 5}
                fontSize="8.5"
                fontWeight="600"
                textAnchor="middle"
                style={{
                  paintOrder: 'stroke',
                  stroke: 'var(--c-bg)',
                  strokeWidth: 3,
                  fill: 'var(--c-accent)',
                }}
              >
                {edge.predicate}
              </text>
            )}
          </g>
        )
      })}

      {nodes.map((node, i) => {
        const isEm = em.has(node.id)
        const p = points[i]
        return (
          <g key={node.id}>
            <circle
              cx={p.x}
              cy={p.y}
              r={isEm ? 8 : 5.5}
              strokeWidth={isEm ? 2 : 1.5}
              style={{
                fill: isEm ? 'var(--c-accent)' : 'var(--c-faint)',
                stroke: 'var(--c-bg)',
              }}
            />
            <text
              x={p.x}
              y={p.y + (isEm ? 20 : 16)}
              fontSize={isEm ? 11 : 10}
              fontWeight={isEm ? 700 : 400}
              textAnchor="middle"
              style={{
                paintOrder: 'stroke',
                stroke: 'var(--c-bg)',
                strokeWidth: 3,
                fill: isEm ? 'var(--c-fg)' : 'var(--c-muted)',
              }}
            >
              {node.name}
            </text>
          </g>
        )
      })}
    </svg>
  )
}
