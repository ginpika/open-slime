import type { GraphEdge, GraphNode } from '../api/client'
import type { Pt } from './graphLayout'

export type GraphSel = { kind: 'node'; id: string } | { kind: 'edge'; key: string }

export const edgeKey = (e: GraphEdge) => e.id || JSON.stringify([e.from, e.predicate, e.to])

/** 属性、名称、主题、选中状态不改变拓扑，不能据此重排用户拖好的布局。 */
export function topologyKey(nodes: GraphNode[], edges: GraphEdge[]): string {
  return JSON.stringify([nodes.map(n => n.id).sort(), edges.map(e =>
    [edgeKey(e), e.from, e.to]).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)))])
}

/** 无向实体对共享槽位；预计算索引，避免每帧对每条边线性查找。 */
export function edgeSlots(edges: GraphEdge[]): Map<string, { index: number; count: number }> {
  const pairs = new Map<string, GraphEdge[]>()
  for (const e of edges) {
    const pair = JSON.stringify([e.from, e.to].sort())
    const group = pairs.get(pair) ?? []
    group.push(e)
    pairs.set(pair, group)
  }
  const slots = new Map<string, { index: number; count: number }>()
  for (const group of pairs.values()) {
    group.sort((a, b) => edgeKey(a).localeCompare(edgeKey(b)))
    group.forEach((e, index) => slots.set(edgeKey(e), { index, count: group.length }))
  }
  return slots
}

export function edgeGeometry(
  e: GraphEdge, a: Pt, b: Pt, ra: number, rb: number,
  slot: { index: number; count: number } = { index: 0, count: 1 },
): { path: string; label: Pt; bounds: { minX: number; minY: number; maxX: number; maxY: number } } {
  if (e.from === e.to) {
    const r = Math.max(ra, rb)
    const height = r + 36 + slot.index * 22
    const start = { x: a.x - r * 0.7, y: a.y - r * 0.7 }
    const end = { x: a.x + r * 0.7, y: a.y - r * 0.7 }
    return {
      path: `M ${start.x} ${start.y} C ${a.x - height} ${a.y - height * 2} ${a.x + height} ${a.y - height * 2} ${end.x} ${end.y}`,
      label: { x: a.x, y: start.y * 0.25 + (a.y - height * 2) * 0.75 - 4 },
      bounds: { minX: a.x - height, maxX: a.x + height, minY: a.y - height * 2, maxY: start.y },
    }
  }
  const dx = b.x - a.x
  const dy = b.y - a.y
  const distance = Math.hypot(dx, dy)
  const direction = e.from < e.to ? 1 : -1
  const offset = (slot.index - (slot.count - 1) / 2) * Math.max(84, distance * 0.28) * direction
  const c = {
    x: (a.x + b.x) / 2 - (distance ? dy / distance : 0) * offset,
    y: (a.y + b.y) / 2 + (distance ? dx / distance : 0) * offset,
  }
  // 沿曲线切线裁掉节点半径；节点重叠时不把端点反向延长。
  const trim = (p: Pt, r: number) => {
    const d = Math.hypot(c.x - p.x, c.y - p.y)
    const k = d > 0 ? Math.min(r, distance / 3) / d : 0
    return { x: p.x + (c.x - p.x) * k, y: p.y + (c.y - p.y) * k }
  }
  const start = trim(a, ra)
  const end = trim(b, rb)
  return {
    path: `M ${start.x} ${start.y} Q ${c.x} ${c.y} ${end.x} ${end.y}`,
    label: { x: (start.x + 2 * c.x + end.x) / 4, y: (start.y + 2 * c.y + end.y) / 4 - 4 },
    bounds: { minX: Math.min(start.x, c.x, end.x), minY: Math.min(start.y, c.y, end.y), maxX: Math.max(start.x, c.x, end.x), maxY: Math.max(start.y, c.y, end.y) },
  }
}
