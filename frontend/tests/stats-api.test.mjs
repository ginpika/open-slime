import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const desktop = 'data:text/javascript;base64,' + Buffer.from('export const apiFetch = (...args) => globalThis.__statsFetch(...args)').toString('base64')
const source = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText.replace("'./desktop'", "'" + desktop + "'")
const { getStats, saveStatsPrice, deleteStatsPrice } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
let timerId = 0
const timers = new Map()
globalThis.window = { setTimeout: callback => { timers.set(++timerId, callback); return timerId }, clearTimeout: id => timers.delete(id) }
test('stats query keeps empty legacy model/provider filters and cleans request timeout after success', async () => {
  let request
  globalThis.__statsFetch = async (path, init) => { request = { path, init }; return { ok: true, json: async () => ({ calls: 0 }) } }
  await getStats({ from: '2026-10-01', to: '2026-10-15', model: '', provider: '', source: 'coding' })
  const url = new URL(request.path, 'http://fixture.invalid')
  assert.equal(url.searchParams.get('model'), '')
  assert.equal(url.searchParams.get('provider'), '')
  assert.equal(url.searchParams.get('source'), 'coding')
  assert.equal(request.init.credentials, 'same-origin')
  assert.equal(timers.size, 0)
})
test('request replacement cancels promptly and timeout surfaces an actionable error', async () => {
  globalThis.__statsFetch = async (_path, init) => new Promise((_resolve, reject) => {
    const abort = () => reject(new DOMException('Aborted', 'AbortError'))
    if (init.signal.aborted) abort()
    else init.signal.addEventListener('abort', abort, { once: true })
  })
  const parent = new AbortController()
  const cancelled = getStats(7, parent.signal)
  parent.abort()
  await assert.rejects(cancelled, { name: 'AbortError' })
  assert.equal(timers.size, 0)
  const timeout = getStats(30)
  for (const callback of timers.values()) callback()
  await assert.rejects(timeout, /15 秒/)
  assert.equal(timers.size, 0)
})
test('manual pricing preserves decimal strings, unknown cache rates and deletion endpoint', async () => {
  const requests = []
  globalThis.__statsFetch = async (path, init) => { requests.push({ path, init }); return { ok: true, json: async () => ({ saved: true }) } }
  await saveStatsPrice({ provider: '', model: 'm', effectiveFrom: '2026-10-01', currency: 'CNY', inputPerMillion: '0.00000001', outputPerMillion: '0', cachedPerMillion: null })
  const body = JSON.parse(requests[0].init.body)
  assert.equal(body.inputPerMillion, '0.00000001'); assert.equal(body.cachedPerMillion, null)
  await deleteStatsPrice(42)
  assert.equal(requests[1].path, '/api/stats/prices/42')
  assert.equal(requests[1].init.method, 'DELETE')
  assert.equal(timers.size, 0)
})

