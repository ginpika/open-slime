import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const dist = path.join(frontend, 'dist'), output = path.resolve(frontend, '../backend/target/workspace-shell-browser-check')
await fs.mkdir(output, { recursive: true })
const target = { id: 'w1', name: '开发环境', kind: 'ssh', enabled: true, workdir: '/work', sshHost: 'fixture', sshUser: 'user', sshPort: 22, allowPatterns: [], denyPatterns: [] }
const terminals = new Map(), tickets = new Map(), inputs = [], resizes = [], requests = [], errors = []
let sequence = 0
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(); page.setDefaultTimeout(10000)
page.on('pageerror', error => errors.push(error.message))
await context.addInitScript(() => localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })))
await page.routeWebSocket('**/ws/workspaces/terminals/*', socket => {
  const token = new URL(socket.url()).pathname.split('/').at(-1), ticket = tickets.get(token)
  tickets.delete(token)
  if (!ticket || !terminals.has(ticket.id)) { socket.close(); return }
  const terminal = terminals.get(ticket.id)
  let after = ticket.after, pending = false
  const flush = () => {
    if (pending || terminal.bytes.length <= after) return
    pending = true; socket.send(JSON.stringify({ type: 'output', offset: terminal.bytes.length, reset: false })); socket.send(terminal.bytes.subarray(after))
  }
  socket.onMessage(data => {
    const message = JSON.parse(String(data))
    if (message.type === 'ack') { after = message.offset; pending = false; flush() }
    if (message.type === 'input') { inputs.push({ id: ticket.id, data: message.data }); terminal.bytes = Buffer.concat([terminal.bytes, Buffer.from(message.data)]); flush() }
    if (message.type === 'resize') resizes.push(message)
  })
  flush()
})
await context.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url()), method = req.method()
  if (url.origin !== 'http://workspace.test') return route.abort()
  if (!url.pathname.startsWith('/api/')) {
    const file = path.resolve(dist, url.pathname === '/' ? 'index.html' : '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) return route.abort()
    try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404, body: '' }) }
  }
  requests.push({ path: url.pathname, method })
  const json = value => route.fulfill({ json: value })
  if (url.pathname === '/api/auth/me') return json({ id: 'fixture', username: 'fixture', nickname: '验收用户', role: 'user' })
  if (url.pathname === '/api/settings') return json({ models: [], embedding: { mode: 'llm' }, rerank: { mode: 'off' } })
  if (url.pathname === '/api/terminal') return json({ targets: [target, { ...target, id: 'disabled', name: '停用环境', enabled: false }] })
  if (url.pathname === '/api/terminal/commands') return json([])
  if (url.pathname === '/api/workspaces/w1/files') return json({ path: '.', entries: [] })
  const match = url.pathname.match(/^\/api\/workspaces\/w1\/terminals(?:\/([^/]+)(\/connect)?)?$/)
  if (match) {
    const [, id, connect] = match
    if (!id && method === 'GET') return json([...terminals.values()].map(({ bytes, ...view }) => view))
    if (!id && method === 'POST') { const terminal = { id: `t${++sequence}`, title: `T${sequence}`, running: true, error: null, bytes: Buffer.from('fixture shell ready\r\n$ ') }; terminals.set(terminal.id, terminal); const { bytes, ...view } = terminal; return json(view) }
    if (connect) { const token = `ticket-${tickets.size}-${Date.now()}`; tickets.set(token, { id, after: req.postDataJSON().after }); return json({ url: `/ws/workspaces/terminals/${token}` }) }
    if (method === 'DELETE') { terminals.delete(id); return json({ ok: true }) }
  }
  return json([])
})
try {
  await page.goto('http://workspace.test/#/workspace')
  await page.getByText('开发环境', { exact: true }).click()
  assert.equal(await page.getByRole('button', { name: /试跑/ }).count(), 0)
  const headerShell = page.getByRole('button', { name: 'Shell', exact: true }).first()
  await headerShell.click()
  const dialog = page.getByRole('dialog').filter({ hasText: 'Shell · 开发环境' })
  await dialog.getByText('Shell · Ctrl+C 中断命令', { exact: true }).waitFor()
  assert.equal(sequence, 1)
  const input = dialog.getByRole('textbox', { name: 'T1命令输入' })
  await input.fill('echo 中文'); await input.press('Enter'); await input.press('Control+c'); await input.press('Escape')
  await page.waitForFunction(() => document.querySelector('.xterm-screen')?.textContent?.includes('echo 中文'))
  assert.ok(await dialog.isVisible(), 'Escape inside shell must reach the terminal')
  assert.ok(inputs.some(item => item.data.includes('echo 中文')))
  assert.ok(inputs.some(item => item.data === '\u0003'))
  assert.ok(resizes.some(item => item.cols > 2 && item.rows > 1))
  await page.screenshot({ path: path.join(output, 'shell-desktop.png'), fullPage: true })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await headerShell.click()
  await dialog.getByText('Shell · Ctrl+C 中断命令', { exact: true }).waitFor()
  assert.equal(sequence, 1, 'reopen resumes existing shell')
  await dialog.getByRole('button', { name: '新建 Shell', exact: true }).click()
  await dialog.getByRole('textbox', { name: 'T2命令输入' }).waitFor()
  assert.equal(terminals.size, 2)
  await dialog.getByRole('button', { name: '结束会话', exact: true }).click()
  await dialog.getByRole('textbox', { name: 'T1命令输入' }).waitFor()
  assert.equal(terminals.size, 1)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: path.join(output, 'shell-mobile.png'), fullPage: true })
  const rect = await dialog.boundingBox(); assert.ok(rect.x >= 0 && rect.x + rect.width <= 391)
  assert.equal(requests.filter(item => item.path.startsWith('/api/coding/')).length, 0, 'workspace shell must not create a coding project')
  assert.deepEqual(errors, [])
  console.log('Workspace Shell browser checks passed: input, Ctrl+C, Escape, resize, resume, multiple sessions, cleanup, mobile layout.')
} finally { await browser.close() }
