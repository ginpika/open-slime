import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const output = path.resolve(frontend, '../backend/target/graph-browser-check')
await fs.mkdir(output, { recursive: true })
const nodes = ['甲', '乙', '丙'].map((name, i) => ({ id: String(i), name, type: 'CONCEPT' }))
const edges = [{ id: 'ab', from: '0', to: '1', predicate: '关联' }, { id: 'ba', from: '1', to: '0', predicate: '反向' }, { id: 'loop', from: '0', to: '0', predicate: '自环' }]
const fixture = `import React, {useState, StrictMode} from 'react'; import {createRoot} from 'react-dom/client'; import {flushSync} from 'react-dom';
import GraphCanvas, {typeColorMap} from '/src/components/GraphCanvas.tsx'; import GraphECharts from '/src/components/GraphECharts.tsx';
import * as echarts from 'echarts/core'; import {initTheme} from '/src/theme/store'; import '/src/index.css'; import '/src/foundation.css';
initTheme(); const colors=typeColorMap(['CONCEPT']);
const root=createRoot(document.getElementById('root'));
function Fixture(){const [state,set]=useState({nodes:${JSON.stringify(nodes.slice(0, 2))},edges:${JSON.stringify(edges)},physics:false,selected:null,show:true});
window.update=next=>flushSync(()=>set(s=>({...s,...next})));
window.snapshot=()=>{const chart=echarts.getInstanceByDom(document.querySelector('#ec [aria-label]')); const series=chart?.getModel().getSeriesByIndex(0); const data=series?.getData(); const ec={};
if(data) for(let i=0;i<data.count();i++){const p=data.getItemLayout(i);ec[data.getId(i)]={world:[...p],pixel:chart.convertToPixel({seriesIndex:0},p)}}
return {ec,camera:chart?.convertToPixel({seriesIndex:0},[0,0]),selected:state.selected,seriesId:series?.id,custom:[...document.querySelectorAll('#custom circle[cx][stroke-width]')].map(c=>[c.getAttribute('cx'),c.getAttribute('cy')])}};
window.resetCamera=()=>{const chart=echarts.getInstanceByDom(document.querySelector('#ec [aria-label]'));chart.setOption({series:[{id:window.snapshot().seriesId,center:null,zoom:1}]})};
window.checkForce=()=>{const chart=echarts.getInstanceByDom(document.querySelector('#ec [aria-label]')); const force=chart?.getModel().getSeriesByIndex(0)?.forceLayout;const same=force===window.previousForce;window.previousForce=force;return same};
window.traceRelease=()=>{
  const chart=echarts.getInstanceByDom(document.querySelector('#ec [aria-label]'));
  window.releaseFrames=[];
  const sample=()=>{const data=chart.getModel().getSeriesByIndex(0).getData(); const el=data.getItemGraphicEl(0); window.releaseFrames.push(chart.convertToPixel({seriesIndex:0},[el.x,el.y]));};
  chart.on('rendered',sample); window.stopReleaseTrace=()=>chart.off('rendered',sample);
  return window.snapshot().ec['0'].pixel;
};
const props={...state,centerId:'0',fitOnUpdate:false,typeColors:colors,onSelect:selected=>set(s=>({...s,selected})),onExpand:id=>window.expanded=id};
return state.show?<div style={{display:'flex',height:700}}><section id="custom" style={{width:600}}><GraphCanvas {...props} hiddenPredicates={new Set()} fitOnUpdate={false}/></section><section id="ec" style={{width:600}}><GraphECharts {...props}/></section></div>:null}
root.render(<StrictMode><Fixture/></StrictMode>);`
const bundle = await build({
  root: frontend,
  build: { write: false, cssCodeSplit: false, rollupOptions: { input: path.join(frontend, 'src/__graph_fixture.tsx'), output: { entryFileNames: 'fixture.js' } } },
  plugins: [{ name: 'graph-fixture', resolveId(id) { if (id.endsWith('__graph_fixture.tsx')) return path.join(frontend, 'src/__graph_fixture.tsx').replaceAll('\\', '/') }, load(id) { if (id.endsWith('__graph_fixture.tsx')) return fixture } }],
})
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 1240, height: 780 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname.slice(1)
    if (!pathname) return route.fulfill({ contentType: 'text/html', body: `<html><meta charset="utf-8">${bundle.output.filter(a => a.fileName.endsWith('.css')).map(a => `<link rel="stylesheet" href="/${a.fileName}">`).join('')}<div id="root"></div><script type="module" src="/fixture.js"></script></html>` })
    const asset = bundle.output.find(a => a.fileName === pathname)
    return route.fulfill(asset ? { contentType: pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: asset.type === 'chunk' ? asset.code : Buffer.from(asset.source) } : { status: 404, body: '' })
  })
  await page.goto('http://graph.test/')
  await page.waitForFunction(() => window.snapshot?.().ec['0'])
  let initial = await page.evaluate(() => window.snapshot())
  assert.equal(Object.keys(initial.ec).length, 2)
  assert.ok(initial.custom.length > 0)
  assert.equal(await page.locator('#custom text').filter({ hasText: '自环' }).count(), 1)
  assert.equal(await page.locator('#ec svg text').filter({ hasText: '自环' }).count(), 1)
  for (const selector of ['#custom', '#ec']) {
    const loopHit = page.locator(`${selector} svg path[stroke-width="12"]`).last()
    const point = await loopHit.evaluate(el => {
      const p = el.getPointAtLength(el.getTotalLength() / 2)
      const transformed = new DOMPoint(p.x, p.y).matrixTransform(el.getScreenCTM())
      return { x: transformed.x, y: transformed.y }
    })
    await page.mouse.click(point.x, point.y)
    assert.deepEqual((await page.evaluate(() => window.snapshot())).selected, { kind: 'edge', key: 'loop' }, `${selector} self-loop must be selectable`)
  }

  const pan = async (x, y, dx, dy) => {
    const before = await page.evaluate(() => window.snapshot().camera)
    await page.mouse.move(600 + x, y); await page.mouse.down()
    await page.mouse.move(600 + x + dx, y + dy, { steps: 4 }); await page.mouse.up()
    const after = await page.evaluate(() => window.snapshot().camera)
    assert.ok(Math.abs(after[0] - before[0] - dx) < 0.1 && Math.abs(after[1] - before[1] - dy) < 0.1,
      `background pan at (${x},${y}) must cover full canvas: ${JSON.stringify({ before, after, dx, dy })}`)
  }
  for (const physics of [false, true]) {
    await page.evaluate(physics => window.update({ physics }), physics)
    for (const [x, y, dx, dy] of [[8, 8, 36, 24], [592, 8, -36, 24], [8, 692, 36, -24], [592, 692, -36, -24]]) await pan(x, y, dx, dy)
    // 把图移出原始内容矩形后，仍可从背景重新拖回。
    for (let i = 0; i < 5; i++) await pan(8, 8, 120, 0)
    for (let i = 0; i < 5; i++) await pan(592, 692, -120, 0)
  }
  await page.evaluate(() => { window.update({ physics: false }); window.resetCamera() })
  initial = await page.evaluate(() => window.snapshot())

  // 真正拖动两引擎中的节点，随后编辑属性、选中和增量展开。
  const ecPoint = initial.ec['1'].pixel
  await page.mouse.move(600 + ecPoint[0], ecPoint[1])
  await page.mouse.down(); await page.mouse.move(600 + ecPoint[0] + 50, ecPoint[1] + 25, { steps: 5 }); await page.mouse.up()
  const customNode = page.locator('#custom svg > g > g').filter({ has: page.locator('text', { hasText: '乙' }) }).locator('circle').last()
  const box = await customNode.boundingBox()
  await page.mouse.move(box.x + box.width / 2 + 2, box.y + box.height / 2)
  await page.mouse.down(); await page.mouse.move(box.x + box.width / 2 + 52, box.y + box.height / 2 + 25, { steps: 5 }); await page.mouse.up()
  await page.mouse.move(900, 350); await page.mouse.wheel(0, -120)
  await page.waitForTimeout(100)
  const dragged = await page.evaluate(() => window.snapshot())
  assert.notDeepEqual(dragged.ec['1'].world, initial.ec['1'].world, 'ECharts drag must update layout')
  assert.notDeepEqual(dragged.custom, initial.custom, 'custom drag must update layout')
  await page.evaluate(nodes => window.update({ nodes: nodes.map(n => ({ ...n, props: [{ k: 'note', v: 'changed' }] })), selected: { kind: 'node', id: '1' } }), nodes.slice(0, 2))
  assert.deepEqual((await page.evaluate(() => window.snapshot())).ec, dragged.ec, 'metadata and selection must preserve ECharts layout and viewport')
  assert.deepEqual((await page.evaluate(() => window.snapshot())).custom, dragged.custom)
  await page.evaluate(({ nodes, edges }) => window.update({ nodes, edges }), { nodes, edges: [...edges, { id: 'bc', from: '1', to: '2', predicate: '展开' }] })
  const expanded = await page.evaluate(() => window.snapshot())
  for (const id of ['0', '1']) {
    assert.deepEqual(expanded.ec[id].world, dragged.ec[id].world)
    assert.ok(Math.hypot(...expanded.ec[id].pixel.map((v, i) => v - dragged.ec[id].pixel[i])) < 0.1, `expansion must preserve ECharts viewport: ${JSON.stringify({before: dragged, after: expanded})}`)
  }
  assert.deepEqual(expanded.custom.slice(0, dragged.custom.length), dragged.custom)
  await page.evaluate(edges => window.update({ edges: edges.filter(e => e.id !== 'ba') }), [...edges, { id: 'bc', from: '1', to: '2', predicate: '展开' }])
  const filtered = await page.evaluate(() => window.snapshot())
  for (const id of ['0', '1', '2']) {
    assert.deepEqual(filtered.ec[id].world, expanded.ec[id].world)
    assert.ok(Math.hypot(...filtered.ec[id].pixel.map((v, i) => v - expanded.ec[id].pixel[i])) < 0.1, 'filtering must preserve static coordinates and zoom')
  }
  assert.deepEqual(filtered.custom, expanded.custom)

  await page.evaluate(() => window.update({ physics: true }))
  await page.waitForTimeout(150)
  await page.evaluate(() => window.checkForce())
  await page.evaluate(() => window.update({ selected: { kind: 'edge', key: 'ab' } }))
  assert.equal(await page.evaluate(() => window.checkForce()), true, 'selection must not recreate force simulation')
  const freeze = await page.evaluate(() => { const before = window.snapshot(); window.update({ physics: false }); return { before, after: window.snapshot() } })
  for (const id of ['0', '1', '2']) {
    assert.deepEqual(freeze.after.ec[id].world, freeze.before.ec[id].world)
    assert.ok(Math.hypot(...freeze.after.ec[id].pixel.map((v, i) => v - freeze.before.ec[id].pixel[i])) < 0.1, 'disabling physics must not jump')
  }
  await page.waitForTimeout(200)
  assert.deepEqual((await page.evaluate(() => window.snapshot())).ec, freeze.after.ec)
  await page.screenshot({ path: path.join(output, 'graph-engines.png') })

  // 单节点只有向心力：向右拖动后松手，不应该先越过松手点向右弹出一帧。
  await page.evaluate(() => window.update({ nodes: [{ id: '0', name: '甲', type: 'CONCEPT' }], edges: [], physics: true, selected: null }))
  await page.waitForTimeout(100)
  const start = (await page.evaluate(() => window.snapshot())).ec['0'].pixel
  await page.mouse.move(600 + start[0], start[1]); await page.mouse.down()
  await page.mouse.move(600 + start[0] + 160, start[1], { steps: 8 })
  await page.waitForTimeout(100)
  const release = await page.evaluate(() => window.traceRelease())
  await page.mouse.up()
  await page.waitForTimeout(180)
  const releaseFrames = await page.evaluate(() => { window.stopReleaseTrace(); return window.releaseFrames })
  assert.ok(releaseFrames.length >= 3, 'must observe actual rendered frames after release')
  assert.ok(Math.max(...releaseFrames.map(p => p[0])) <= release[0] + 1, `release overshoot: ${JSON.stringify({ release, releaseFrames })}`)
  await page.evaluate(() => { window.update({ physics: true }); window.update({ show: false }); window.rafCalls = 0; const original = window.requestAnimationFrame; window.requestAnimationFrame = cb => { window.rafCalls++; return original.call(window, cb) } })
  await page.waitForTimeout(250)
  const rafCalls = await page.evaluate(() => window.rafCalls)
  await page.waitForTimeout(250)
  assert.equal(await page.evaluate(() => window.rafCalls), rafCalls, 'unmounted engine must stop scheduling animation frames')
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ status: 'passed', cases: ['drag', 'full-canvas pan in both physics modes', 'pan recovery outside initial bounds', 'metadata stability', 'static expansion', 'parallel edges and self-loops', 'selection without force restart', 'physics freeze and viewport', 'rendered-frame drag release without overshoot', 'unmount cleanup'], output }))
} finally { await browser.close() }
