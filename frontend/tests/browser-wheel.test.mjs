import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

const tempRoot = resolve(tmpdir()), output = mkdtempSync(join(tempRoot, 'minis-wheel-tests-'))
if (dirname(resolve(output)) !== tempRoot || !basename(output).startsWith('minis-wheel-tests-')) throw new Error('临时测试目录校验失败')
let bindBrowserWheel
try {
  const source = readFileSync(fileURLToPath(new URL('../src/components/browserWheel.ts', import.meta.url)), 'utf8')
  writeFileSync(join(output, 'wheel.mjs'), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText)
  ;({ bindBrowserWheel } = await import(pathToFileURL(join(output, 'wheel.mjs')).href))
} finally { rmSync(output, { recursive: true, force: true }) }

class Surface extends EventTarget {
  getBoundingClientRect() { return { left: 20, top: 30, width: 640, height: 400 } }
}
function wheel(surface, input = {}) {
  const event = new Event('wheel', { cancelable: true })
  Object.assign(event, { clientX: 120, clientY: 80, deltaX: 0, deltaY: 0, deltaMode: 0, ctrlKey: false, ...input })
  surface.dispatchEvent(event)
  return event
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

test('wheel bursts combine, preserve direction, and map scaled coordinates', async () => {
  const surface = new Surface(), calls = []
  const dispose = bindBrowserWheel(surface, async movement => { calls.push(movement) })
  try {
    assert.equal(wheel(surface, { deltaY: 40 }).defaultPrevented, true)
    wheel(surface, { deltaY: 60, deltaX: -20 })
    await wait(130)
    assert.deepEqual(calls, [{ x: 200, y: 100, deltaX: -40, deltaY: 200 }])
    assert.equal(wheel(surface, { ctrlKey: true, deltaY: 50 }).defaultPrevented, false)
    await wait(100)
    assert.equal(calls.length, 1)
  } finally { dispose() }
})
test('slow responses serialize input and cancellation drops queued wheel events', async () => {
  const surface = new Surface(), calls = []
  let resolveRequest
  const dispose = bindBrowserWheel(surface, movement => { calls.push(movement); return new Promise(resolve => { resolveRequest = resolve }) })
  wheel(surface, { deltaY: 80 })
  await wait(130)
  wheel(surface, { deltaY: -50 })
  await wait(100)
  assert.equal(calls.length, 1)
  resolveRequest()
  await wait(130)
  assert.equal(calls.length, 2)
  assert.equal(calls[1].deltaY, -100)
  wheel(surface, { deltaY: 100 })
  dispose(); resolveRequest()
  await wait(130)
  assert.equal(calls.length, 2)
  assert.equal(wheel(surface, { deltaY: 50 }).defaultPrevented, false)
})
test('line and page units normalize, and failed requests stop pending input', async () => {
  const surface = new Surface(), calls = []
  const dispose = bindBrowserWheel(surface, async movement => { calls.push(movement); throw new Error('offline') })
  try {
    wheel(surface, { deltaY: 3, deltaMode: 1 })
    wheel(surface, { deltaX: 1, deltaMode: 2 })
    await wait(130)
    assert.deepEqual(calls[0], { x: 200, y: 100, deltaX: 1280, deltaY: 96 })
    assert.equal(wheel(surface, { deltaY: NaN }).defaultPrevented, false)
  } finally { dispose() }
})
