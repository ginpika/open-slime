type ForceNode = { fixed?: boolean; p: number[]; pp: number[] }
type ForceLayout = {
  beforeStep: (callback: (nodes: ForceNode[]) => void) => void
  setUnfixed: (index: number) => void
}

const patched = new WeakSet<ForceLayout>()

/**
 * ECharts 6.1 的 force 在拖拽期间只同步 p，未同步 Verlet 历史位置 pp。
 * 松手时 p - pp 会被误当成速度，造成单帧向外跳跃。
 * 限于当前图表实例安装兼容处理，保留原 beforeStep 的固定节点坐标同步，
 * 并在拖拽及首次释放积分前清除该节点的历史位移。升级 ECharts 须回归此处。
 */
export function syncForceDragHistory(
  candidate: unknown,
  readPosition: (index: number) => number[] | undefined,
): void {
  if (!candidate || typeof candidate !== 'object') return
  const force = candidate as ForceLayout
  if (typeof force.beforeStep !== 'function' || typeof force.setUnfixed !== 'function' || patched.has(force)) return
  patched.add(force)
  const released = new Set<number>()
  const setUnfixed = force.setUnfixed.bind(force)
  force.setUnfixed = index => {
    released.add(index)
    setUnfixed(index)
  }
  force.beforeStep(nodes => {
    for (let i = 0; i < nodes.length; i++) {
      const node = nodes[i]
      if (!node.fixed && !released.has(i)) continue
      const p = readPosition(i)
      if (!p || !Number.isFinite(p[0]) || !Number.isFinite(p[1]) || !node.p || !node.pp) continue
      node.p[0] = node.pp[0] = p[0]
      node.p[1] = node.pp[1] = p[1]
    }
    released.clear()
  })
}
