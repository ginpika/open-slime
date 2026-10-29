import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

async function source(name) {
  const code = ts.transpileModule(readFileSync(new URL(`../src/components/${name}.ts`, import.meta.url), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 },
  }).outputText
  return import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
}
const { forceLayout, physicsTick, FORCE_CONFIG } = await source('graphLayout')
const { edgeKey, topologyKey, edgeSlots, edgeGeometry } = await source('graphRendering')
const { syncForceDragHistory } = await source('echartsForceDrag')
const nodes = ['a', 'b', 'c'].map(id => ({ id, name: id, type: 'CONCEPT' }))
const edge = (id, from = 'a', to = 'b') => ({ id, from, to, predicate: id })

test('static expansion and filtering preserve dragged seed positions exactly', () => {
  const seeds = new Map([['a', { x: 412, y: -132 }], ['b', { x: -58, y: 47 }]])
  const positions = forceLayout(nodes, [edge('ab'), edge('bc', 'b', 'c')], { seedById: seeds, preserveSeed: true })
  assert.deepEqual(positions.slice(0, 2), [...seeds.values()])
  assert.ok(positions.every(p => Number.isFinite(p.x) && Number.isFinite(p.y)))
  assert.deepEqual(forceLayout(nodes.slice(0, 2), [], { seedById: seeds, preserveSeed: true }), [...seeds.values()])
  assert.deepEqual(seeds.get('a'), { x: 412, y: -132 }, 'layout must not mutate caller seeds')
})

test('layout is deterministic, finite for coincident nodes and ignores invalid seeds', () => {
  assert.deepEqual(forceLayout(nodes, [edge('ab')]), forceLayout(nodes, [edge('ab')]))
  const bad = new Map([['a', { x: NaN, y: Infinity }]])
  assert.ok(forceLayout(nodes, [edge('bad', 'missing')], { seedById: bad }).every(p => Number.isFinite(p.x) && Number.isFinite(p.y)))
  const state = { pos: new Map(nodes.map(n => [n.id, { x: 0, y: 0 }])), vel: new Map(), alpha: 1 }
  physicsTick(state, nodes.map(n => n.id), [edge('loop', 'a', 'a')], { fixedId: 'a' })
  assert.deepEqual(state.pos.get('a'), { x: 0, y: 0 })
  assert.ok([...state.pos.values()].every(p => Number.isFinite(p.x) && Number.isFinite(p.y)))
})

test('single-node preview is centered and topology excludes editable metadata', () => {
  assert.deepEqual(forceLayout(nodes.slice(0, 1), [], { outW: 360, outH: 250 }), [{ x: 180, y: 125 }])
  const edges = [edge('ab')]
  assert.equal(topologyKey(nodes, edges), topologyKey(nodes.map(n => ({ ...n, props: [{ k: 'changed' }], name: 'new' })).reverse(), edges))
  assert.notEqual(topologyKey(nodes, edges), topologyKey(nodes, [edge('ab', 'b', 'c')]))
  assert.notEqual(edgeKey({ ...edge(''), from: 'a|b', predicate: 'c' }), edgeKey({ ...edge(''), from: 'a', predicate: 'b|c' }))
})

test('self-loops and reciprocal parallel edges have distinct finite paths', () => {
  const edges = [edge('ab'), edge('ba', 'b', 'a'), edge('self1', 'a', 'a'), edge('self2', 'a', 'a')]
  const slots = edgeSlots(edges)
  const point = { a: { x: 0, y: 0 }, b: { x: 120, y: 0 } }
  const paths = edges.map(e => edgeGeometry(e, point[e.from], point[e.to], 12, 17, slots.get(edgeKey(e))).path)
  assert.equal(new Set(paths).size, 4)
  assert.ok(paths.slice(2).every(p => p.includes(' C ')))
  assert.ok(paths.every(p => !/NaN|Infinity/.test(p)))
  assert.equal(edgeGeometry(edge('ab'), point.a, point.a, 12, 17).path.includes('NaN'), false)
})

test('large-graph approximate repulsion stays close to exact forces', () => {
  const ids = Array.from({ length: 256 }, (_, i) => String(i))
  const points = ids.map((_, i) => ({ x: Math.cos(i * 2.39996) * Math.sqrt(i + 1) * 60, y: Math.sin(i * 2.39996) * Math.sqrt(i + 1) * 60 }))
  const state = { pos: new Map(ids.map((id, i) => [id, points[i]])), vel: new Map(), alpha: 1 }
  physicsTick(state, ids, [])
  let totalError = 0, totalForce = 0
  for (let i = 0; i < ids.length; i++) {
    let fx = 0, fy = 0
    for (let j = 0; j < ids.length; j++) {
      if (i === j) continue
      const dx = points[j].x - points[i].x, dy = points[j].y - points[i].y
      const d = Math.hypot(dx, dy)
      const f = FORCE_CONFIG.spacing ** 2 * FORCE_CONFIG.repulsion / (d * d)
      fx -= dx * f; fy -= dy * f
    }
    const velocity = state.vel.get(ids[i])
    let vx = (fx - points[i].x * FORCE_CONFIG.center) * FORCE_CONFIG.decay
    let vy = (fy - points[i].y * FORCE_CONFIG.center) * FORCE_CONFIG.decay
    const speed = Math.hypot(vx, vy), max = FORCE_CONFIG.spacing * FORCE_CONFIG.maxSpeed
    if (speed > max) { vx *= max / speed; vy *= max / speed }
    totalError += Math.hypot(velocity.x - vx, velocity.y - vy)
    totalForce += Math.hypot(vx, vy)
  }
  assert.ok(totalError / totalForce < 0.03, `aggregate velocity error: ${totalError / totalForce}`)
  const same = { pos: new Map(ids.map(id => [id, { x: 0, y: 0 }])), vel: new Map(), alpha: 1 }
  physicsTick(same, ids, [])
  assert.ok([...same.pos.values()].every(p => Number.isFinite(p.x) && Number.isFinite(p.y)))
})

test('force drag clears stale inertia on release, including the last move between ticks', () => {
  const nodes = [
    { fixed: true, p: [1, 2], pp: [-100, -50] },
    { fixed: false, p: [3, 4], pp: [0, 0] },
  ]
  let beforeStep, latest = [30, 40], installations = 0, unfixCalls = 0
  const force = {
    beforeStep(callback) { beforeStep = callback; installations++ },
    setUnfixed(index) { nodes[index].fixed = false; unfixCalls++ },
  }
  syncForceDragHistory(force, () => latest)
  syncForceDragHistory(force, () => [999, 999])
  assert.equal(installations, 1, 'updated events must not stack wrappers')
  beforeStep(nodes)
  assert.deepEqual(nodes[0].p, [30, 40])
  assert.deepEqual(nodes[0].pp, [30, 40])
  assert.deepEqual(nodes[1].pp, [0, 0], 'other nodes retain their natural velocity')
  latest = [35, 45] // 最后一次 pointermove 在上次 tick 之后。
  force.setUnfixed(0)
  beforeStep(nodes)
  assert.equal(unfixCalls, 1)
  assert.deepEqual(nodes[0].p, latest)
  assert.deepEqual(nodes[0].pp, latest)
  latest = [90, 90]
  beforeStep(nodes)
  assert.deepEqual(nodes[0].pp, [35, 45], 'only the first release tick resets history')
  for (const unavailable of [null, {}, { beforeStep: 1 }]) assert.doesNotThrow(() => syncForceDragHistory(unavailable, () => undefined))
})
