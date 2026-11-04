import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
const source = await fs.readFile(new URL('../chrome-extension/browser.js', import.meta.url), 'utf8')
const { createBrowserController, httpUrl } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
const scope = 'a'.repeat(64)
function fixture() {
  const tabs = new Map([[1, { id: 1, title: 'personal', url: 'https://private.test', active: true, status: 'complete' }]])
  let stored = {}, sequence = 2
  const calls = []
  const api = {
    windows: { getAll: async () => [{ id: 10, focused: true, incognito: false }] },
    storage: { session: { get: async key => ({ [key]: structuredClone(stored[key]) }), set: async value => { stored = { ...stored, ...structuredClone(value) } } } },
    tabs: {
      get: async id => { if (!tabs.has(id)) throw new Error('closed'); return { ...tabs.get(id) } },
      create: async options => { calls.push(['create', options]); const tab = { id: sequence++, status: 'complete', title: 'tool', ...options }; tabs.set(tab.id, tab); return tab },
      update: async (id, options) => { calls.push(['update', id, options]); Object.assign(tabs.get(id), options) },
      remove: async id => { calls.push(['remove', id]); tabs.delete(id) },
    },
    scripting: { executeScript: async options => { calls.push(['script', options]); return [{ result: { title: 'tool', url: tabs.get(options.target.tabId).url, text: 'page text', elements: [] } }] } },
  }
  return { run: createBrowserController(api, async () => {}), tabs, calls, api }
}
test('reuses Chrome profile by creating inactive tabs, never touches personal tabs or focus', async () => {
  const f = fixture()
  const opened = await f.run(scope, { action: 'open', url: 'https://example.test' })
  assert.equal(opened.tab_id, 'user:2')
  assert.deepEqual(f.calls[0], ['create', { url: 'https://example.test/', active: false, windowId: 10 }])
  assert.equal(f.tabs.get(1).active, true)
  await f.run(scope, { action: 'open', tab_id: 'user:2', url: 'https://second.test' })
  assert.deepEqual(f.calls.find(c => c[0] === 'update'), ['update', 2, { url: 'https://second.test/' }])
  const list = await f.run(scope, { action: 'list' })
  assert.deepEqual(list.tabs.map(t => t.tab_id), ['user:2'])
})
test('rejects personal tabs, other conversations and tabs the user is currently using', async () => {
  const f = fixture()
  await assert.rejects(f.run(scope, { action: 'close', tab_id: 'user:1' }), /原有标签页/)
  await f.run(scope, { action: 'open', url: 'https://example.test' })
  await assert.rejects(f.run('b'.repeat(64), { action: 'read', tab_id: 'user:2' }), /其他会话/)
  f.tabs.get(2).active = true
  for (const action of ['click', 'fill', 'select', 'close', 'open']) await assert.rejects(f.run(scope, { action, tab_id: 'user:2', url: 'https://example.test' }), /前台使用/)
  assert.equal(f.calls.filter(c => ['remove', 'update'].includes(c[0])).length, 0)
  assert.equal((await f.run(scope, { action: 'read', tab_id: 'user:2' })).tab_id, 'user:2')
})
test('rejects expired, arbitrary-script, screenshot and non-web commands before execution', async () => {
  const f = fixture()
  for (const command of [{ action: 'eval' }, { action: 'screenshot' }, { action: 'open', url: 'file:///etc/passwd' }, { action: 'open', url: 'https://user:pass@example.test' }]) await assert.rejects(f.run(scope, command))
  await assert.rejects(f.run(scope, { action: 'open', url: 'https://example.test' }, 0), /过期/)
  assert.equal(f.calls.length, 0)
  assert.throws(() => httpUrl('javascript:alert(1)'))
})
test('does not retry uncertain mutations and recovers after a closed tab', async () => {
  const f = fixture()
  await f.run(scope, { action: 'open', url: 'https://example.test' })
  let attempts = 0
  f.api.scripting.executeScript = async () => { attempts++; throw new Error('navigation disconnected') }
  await assert.rejects(f.run(scope, { action: 'click', tab_id: 'user:2', ref: 'r' }), /disconnected/)
  assert.equal(attempts, 1)
  f.tabs.delete(2)
  assert.deepEqual((await f.run(scope, { action: 'list' })).tabs, [])
})
test('extension requests neither cookies nor debugger permissions', async () => {
  const manifest = JSON.parse(await fs.readFile(new URL('../chrome-extension/manifest.json', import.meta.url)))
  assert.deepEqual(manifest.permissions, ['tabs', 'scripting', 'storage'])
})
test('does not create a browser window when Chrome has no ordinary window', async () => {
  const f = fixture()
  f.api.windows.getAll = async () => []
  await assert.rejects(f.run(scope, { action: 'open', url: 'https://example.test' }), /不会自行创建或激活/)
  assert.equal(f.calls.length, 0)
})
test('a user takeover permanently releases the tab even after it returns to the background', async () => {
  const f = fixture()
  await f.run(scope, { action: 'open', url: 'https://example.test' })
  await f.run.release(2)
  f.tabs.get(2).active = false
  await assert.rejects(f.run(scope, { action: 'read', tab_id: 'user:2' }), /原有标签页/)
  assert.deepEqual((await f.run(scope, { action: 'list' })).tabs, [])
})
