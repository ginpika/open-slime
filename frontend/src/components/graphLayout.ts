/**
 * SVG 力导向布局（确定性模拟，无外部依赖）—— GraphMini 与图谱页 GraphCanvas 共用。
 * 统一力模型：库仑斥力（1/d 衰减）+ 边弹簧（拉向理想间距）+ 向心引力（随离心距离线性增长）。
 * 无边界纯向心：不设硬边界，靠向心力把全图收拢成以原点为图心的有机圆形结构，
 * 从根本上避免旧实现"外圈节点被矩形边界夹平成点阵"的形态。
 * 静态布局 forceLayout 与实时物理 physicsTick 共用同一套力（布局即快进模拟），
 * 初始形态与开启物理后的形态连续一致。
 * 同一输入（含可选的已有位置 seed）总是产出相同布局，渲染不跳动。
 * 注意：SVG presentation attribute 不支持 var()，颜色一律由调用方走 style 属性。
 */

export type Pt = { x: number; y: number }

type LayoutNode = { id: string }
type LayoutEdge = { from: string; to: string }

export function hashId(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0) % 100000
}

/**
 * 力模型参数（集中调优）。所有力按"每帧速度增量"量纲直接积分进速度。
 * 图的平衡半径 ≈ spacing · sqrt(repulsion / center · n / 2)：
 * 想更紧凑 → 调低 repulsion 或调高 center；想更松散 → 反之。
 */
export const FORCE_CONFIG = {
  /** 理想间距 k（px）：斥力与弹簧的基准尺度，不随视口与节点数变化（物理画布与视口解绑） */
  spacing: 130,
  /** 斥力系数：撑开图的力，越小图越紧凑 */
  repulsion: 0.012,
  /** 弹簧刚度：边两端拉向理想间距的强度 */
  spring: 0.1,
  /** 弹簧目标间距 = spacing × 此系数（<1 让相连簇抱得更紧） */
  springScale: 0.9,
  /** 向心系数：拉回原点的收拢主力，随离心距离线性增长 */
  center: 0.012,
  /** 中心锚定倍率：centerId 节点向心力 × 此值——稳居图心，可拖走、松手缓慢回中 */
  anchorBoost: 10,
  /** 速度阻尼：每帧速度保留比例（越小沉降越快、晃动越少） */
  decay: 0.86,
  /** 单帧最大速度 = spacing × 此系数（数值稳定限速，替代旧边界 clamp） */
  maxSpeed: 0.12,
} as const

type LayoutOpts = {
  iters?: number
  /** 归一化目标视口；不给则返回世界坐标（以原点为图心，图谱页自行 pan/zoom） */
  outW?: number
  outH?: number
  /** 已有节点位置：新布局从它出发（增量展开时旧节点不动） */
  seedById?: Map<string, Pt>
  /** 中心锚定节点（图谱页的浏览中心；向心力 × anchorBoost 稳居图心） */
  centerId?: string | null
}

export function forceLayout(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  opts: LayoutOpts = {},
): Pt[] {
  const n = nodes.length
  if (n === 0) return []
  const ids = nodes.map((nd) => nd.id)
  const idx = new Map<string, number>(nodes.map((nd, i) => [nd.id, i]))

  // 确定性初始位置：seed 节点保持原位；新节点在以原点为圆心的圆盘上均匀散布
  // （散布半径≈理想密度圆盘，起点即接近平衡尺度，无需边界兜底即可平滑收敛）
  const R0 = (FORCE_CONFIG.spacing * Math.sqrt(n)) / 2
  const pos = new Map<string, Pt>()
  for (const nd of nodes) {
    const seed = opts.seedById?.get(nd.id)
    if (seed) {
      pos.set(nd.id, { ...seed })
      continue
    }
    const h1 = hashId(nd.id)
    const h2 = hashId(`${nd.id}#2`)
    const r = R0 * Math.sqrt(h1 / 100000)
    const t = (h2 / 100000) * Math.PI * 2
    pos.set(nd.id, { x: r * Math.cos(t), y: r * Math.sin(t) })
  }

  const springs = edges
    .map((e) => ({ from: e.from, to: e.to }))
    .filter((s) => idx.has(s.from) && idx.has(s.to))

  // 布局 = 快进跑同一套实时物理：alpha 线性退火（力导向衰减），
  // 布局结果与后续开物理的形态同源连续
  const iters = opts.iters ?? 300
  const st: PhysicsState = { pos, vel: new Map(), alpha: 1 }
  for (let it = 0; it < iters; it++) {
    st.alpha = 1 - (it / iters) * (1 - PHYS_ALPHA_MIN)
    physicsTick(st, ids, springs, { centerId: opts.centerId ?? null })
  }

  const pts = ids.map((id) => st.pos.get(id) ?? { x: 0, y: 0 })

  if (opts.outW === undefined || opts.outH === undefined) {
    return pts
  }

  // 归一化进画布（等比缩放、居中）
  const pad = 30
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of pts) {
    minX = Math.min(minX, p.x)
    minY = Math.min(minY, p.y)
    maxX = Math.max(maxX, p.x)
    maxY = Math.max(maxY, p.y)
  }
  const cw = Math.max(1, maxX - minX)
  const ch = Math.max(1, maxY - minY)
  const scale = Math.min((opts.outW - pad * 2) / cw, (opts.outH - pad * 2) / ch)
  const ox = (opts.outW - cw * scale) / 2 - minX * scale
  const oy = (opts.outH - ch * scale) / 2 - minY * scale

  return pts.map((p) => ({ x: p.x * scale + ox, y: p.y * scale + oy }))
}

/* ---------------- 实时物理（图谱页开关式晃动模拟） ---------------- */

/** 实时模拟的运行态：位置 / 速度 / 能量（alpha 越低越接近静止） */
export type PhysicsState = {
  pos: Map<string, Pt>
  vel: Map<string, Pt>
  alpha: number
}

/** alpha 低于此值视为沉降，可停止动画循环 */
export const PHYS_ALPHA_MIN = 0.02

/**
 * 推进一帧统一力模型：库仑斥力 + 边弹簧 + 向心引力（无边界）。
 * 世界以原点为图心：向心力把全图收拢成圆形结构，外圈不会贴任何矩形边界。
 * centerId 为浏览中心（向心力 × anchorBoost 锚定图心）；
 * fixedId 为正在拖拽的节点（位置由指针直接控制，积分时跳过）。
 * 返回本帧是否有节点产生可见位移（false 时调用方可停帧）。
 */
export function physicsTick(
  st: PhysicsState,
  ids: string[],
  springs: { from: string; to: string }[],
  opts: { centerId?: string | null; fixedId?: string | null } = {},
): boolean {
  const n = ids.length
  if (n === 0 || st.alpha <= PHYS_ALPHA_MIN) return false

  const { spacing: k, repulsion, spring, springScale, center, anchorBoost, decay, maxSpeed } =
    FORCE_CONFIG
  const a = st.alpha
  const idx = new Map<string, number>(ids.map((id, i) => [id, i]))
  const fx = new Float64Array(n)
  const fy = new Float64Array(n)

  // 斥力（全对库仑，1/d 衰减）
  for (let i = 0; i < n; i++) {
    const pi = st.pos.get(ids[i])
    if (!pi) continue
    for (let j = i + 1; j < n; j++) {
      const pj = st.pos.get(ids[j])
      if (!pj) continue
      let dx = pj.x - pi.x
      let dy = pj.y - pi.y
      let d = Math.hypot(dx, dy)
      if (d < 0.001) {
        dx = ((hashId(ids[i]) + j) % 17) - 8
        dy = 13
        d = Math.hypot(dx, dy)
      }
      const f = ((k * k) / Math.max(d, 1)) * repulsion * a
      const ux = (dx / d) * f
      const uy = (dy / d) * f
      fx[i] -= ux
      fy[i] -= uy
      fx[j] += ux
      fy[j] += uy
    }
  }

  // 弹簧：沿边拉向理想间距（spacing × springScale），被拖拽 / 松手时产生回弹晃动
  const rest = k * springScale
  for (const s of springs) {
    const i = idx.get(s.from)
    const j = idx.get(s.to)
    if (i === undefined || j === undefined || i === j) continue
    const pi = st.pos.get(ids[i])
    const pj = st.pos.get(ids[j])
    if (!pi || !pj) continue
    const dx = pj.x - pi.x
    const dy = pj.y - pi.y
    const d = Math.max(0.001, Math.hypot(dx, dy))
    const f = (d - rest) * spring * a
    const ux = (dx / d) * f
    const uy = (dy / d) * f
    fx[i] += ux
    fy[i] += uy
    fx[j] -= ux
    fy[j] -= uy
  }

  // 向心引力：随离心距离线性增长收拢全图；中心节点 × anchorBoost 锚定图心
  for (let i = 0; i < n; i++) {
    const p = st.pos.get(ids[i])
    if (!p) continue
    const c = center * (ids[i] === opts.centerId ? anchorBoost : 1) * a
    fx[i] += -p.x * c
    fy[i] += -p.y * c
  }

  // 积分：速度阻尼产生回弹晃动；限速保证数值稳定（无硬边界，靠向心力回收远飞节点）
  const MAX_V = k * maxSpeed
  let moved = false
  for (let i = 0; i < n; i++) {
    const id = ids[i]
    if (id === opts.fixedId) {
      st.vel.set(id, { x: 0, y: 0 })
      continue
    }
    const p = st.pos.get(id)
    if (!p) continue
    const v = st.vel.get(id) ?? { x: 0, y: 0 }
    let vx = (v.x + fx[i]) * decay
    let vy = (v.y + fy[i]) * decay
    const sp = Math.hypot(vx, vy)
    if (sp > MAX_V) {
      vx = (vx / sp) * MAX_V
      vy = (vy / sp) * MAX_V
    }
    if (Math.abs(vx) > 0.05 || Math.abs(vy) > 0.05) moved = true
    st.pos.set(id, { x: p.x + vx, y: p.y + vy })
    st.vel.set(id, { x: vx, y: vy })
  }
  return moved
}
