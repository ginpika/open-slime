import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/deepseek-balance-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const requests = [], errors = [], checks = []
let mode = 'ready', timestamp = Date.now(), total = '110.0012'
const models = [
  { id: 'env', name: 'DeepSeek 官方', baseUrl: 'https://api.deepseek.com/v1/', apiKey: 'fixture-model-key', model: 'deepseek-flash', contextWindow: 262144, builtin: true },
  { id: 'gateway', name: 'DeepSeek 中转', baseUrl: 'https://gateway.example/v1', apiKey: 'fixture-gateway-key', model: 'deepseek-flash', contextWindow: 262144, builtin: false },
  { id: 'missing', name: '尚未配置密钥', baseUrl: 'https://api.deepseek.com', apiKey: '', model: 'deepseek-flash', contextWindow: 262144, builtin: false },
]
const web = { baseUrl: 'https://api.deepseek.com', hasApiKey: true, fastEnabled: false, ragEnabled: false, fetchEnabled: true, count: 5, timeoutSeconds: 15 }
const settings = () => ({ models, defaultModelId: 'env', embedding: { mode: 'llm', model: '' }, rerank: { mode: 'off', model: '' } })
function balance(url) {
  if (url.includes('/missing/')) return { status: 'unconfigured', available: null, balances: [], checkedAt: null, attemptedAt: null, error: '请先保存 API Key' }
  const search = url.includes('deepseek-web')
  return { status: mode === 'stale' || mode === 'auth' ? 'error' : 'ready', available: mode !== 'empty',
    balances: mode === 'auth' ? [] : [{ currency: 'CNY', totalBalance: search ? '22.50' : mode === 'empty' ? '0.0000' : total, grantedBalance: '10.0012', toppedUpBalance: '100.00' }],
    checkedAt: mode === 'auth' ? null : timestamp, attemptedAt: Date.now(),
    error: mode === 'stale' ? 'DeepSeek 余额查询 HTTP 429：请求过于频繁，请稍后刷新' : mode === 'auth' ? 'DeepSeek 余额查询 HTTP 401：API Key 无效，请检查已保存配置' : null }
}
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => {
  window.__MINIS_LOCAL_TOKEN__ = 'fixture-desktop-token'
  if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } }))
})
const page = await context.newPage()
page.setDefaultTimeout(8000)
page.on('pageerror', e => errors.push(e.message))
await page.clock.install()
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url())
  if (url.origin !== 'http://balance.test') { await route.abort(); return }
  if (!url.pathname.startsWith('/api/')) {
    if (url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return }
    const file = path.resolve(dist, '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) { await route.abort(); return }
    try { await route.fulfill({ path: file }) } catch { await route.fulfill({ status: 404, body: '' }) }
    return
  }
  requests.push({ path: url.pathname, query: url.search, method: request.method(), body: request.postData() })
  if (url.pathname === '/api/auth/me') { await route.fulfill({ json: { id: 'fixture', username: 'fixture', nickname: '测试用户', role: 'user' } }); return }
  if (url.pathname.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: '' }); return }
  if (url.pathname === '/api/settings') { await route.fulfill({ json: settings() }); return }
  if (url.pathname === '/api/settings/models' && request.method() === 'POST') {
    models.push({ ...request.postDataJSON(), id: 'new', builtin: false }); await route.fulfill({ json: settings() }); return
  }
  if (url.pathname.endsWith('/balance')) {
    if (mode === 'transport') { await route.fulfill({ status: 503, json: { message: 'unavailable' } }); return }
    await route.fulfill({ json: balance(url.pathname) }); return
  }
  if (url.pathname === '/api/settings/deepseek-web') {
    if (request.method() === 'PUT') Object.assign(web, { ...request.postDataJSON(), hasApiKey: true })
    await route.fulfill({ json: web }); return
  }
  if (url.pathname === '/api/settings/tts' || url.pathname === '/api/settings/comfyui') { await route.fulfill({ json: { endpoints: [], profiles: [], defaultProfileId: null } }); return }
  await route.fulfill({ json: [] })
})
const row = () => page.locator('#settings-models').getByRole('region', { name: 'DeepSeek 官方账户余额' }).first()
const refresh = async () => {
  await row().getByRole('button', { name: '刷新余额', exact: true }).click()
  await row().getByRole('button', { name: '刷新余额', exact: true }).waitFor()
}
const queried = () => requests.filter(r => r.path.endsWith('/balance'))
async function noOverflow(label) {
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth) <= 1, label)
  const overflow = await page.locator('.deepseek-balance').evaluateAll(nodes => nodes.filter(node => node.scrollWidth > node.clientWidth + 1).length)
  assert.equal(overflow, 0, label + ' balance panel')
}
try {
  await page.goto('http://balance.test/#/settings')
  await row().getByText('110.0012', { exact: true }).waitFor()
  await page.locator('#settings-deepseek-web').getByText('22.50', { exact: true }).waitFor()
  await page.getByText('请先保存 API Key', { exact: true }).waitFor()
  assert.equal(queried().some(r => r.path.includes('gateway')), false)
  assert.equal(queried().every(r => r.method === 'GET' && r.body === null && !r.query.includes('key')), true)
  checks.push('saved official root/v1 auto-query; gateway excluded; missing-key state; independent Search/Fetch account; no credentials in balance request')

  mode = 'stale'; await refresh()
  await row().getByText(/刷新失败，当前显示上次成功结果/).waitFor()
  assert.equal(await row().getByText('110.0012', { exact: true }).count(), 1)
  assert.equal(await row().locator('time').getAttribute('datetime'), new Date(timestamp).toISOString())
  mode = 'transport'; await refresh()
  await row().getByText(/HTTP 503/).waitFor()
  assert.equal(await row().getByText('110.0012', { exact: true }).count(), 1)
  mode = 'ready'; total = '109.9912'; timestamp += 60_000; await refresh()
  await row().getByText('109.9912', { exact: true }).waitFor()
  assert.equal(await row().getByRole('alert').count(), 0)
  mode = 'empty'; await refresh()
  await row().getByText('余额不足，无法调用 API', { exact: true }).waitFor()
  mode = 'auth'; await refresh()
  await row().getByText(/HTTP 401/).waitFor()
  assert.equal(await row().locator('.deepseek-balance-total').count(), 0)
  mode = 'ready'; await refresh()
  checks.push('manual refresh; stale snapshot and timestamp retained on API/network failure; recovery; insufficient funds; invalid key never shown as zero')

  const beforePoll = queried().length
  await page.clock.fastForward(30_000)
  await page.waitForFunction(() => ![...document.querySelectorAll('.deepseek-balance')].some(el => el.getAttribute('aria-busy') === 'true'))
  assert.ok(queried().length > beforePoll)
  await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange')) })
  const beforeHidden = queried().length
  await page.clock.fastForward(60_000)
  assert.equal(queried().length, beforeHidden)
  await page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange')) })
  await page.waitForFunction(() => ![...document.querySelectorAll('.deepseek-balance')].some(el => el.getAttribute('aria-busy') === 'true'))
  assert.ok(queried().length > beforeHidden)
  checks.push('30-second polling pauses while hidden and refreshes on return')

  await page.getByRole('button', { name: '添加模型', exact: true }).click()
  await page.getByRole('button', { name: '使用 DeepSeek 官方端点' }).click()
  const dialog = page.getByRole('dialog')
  assert.equal(await dialog.locator('input[type="password"]').count(), 1)
  assert.equal(await dialog.locator('input').nth(1).inputValue(), 'https://api.deepseek.com')
  assert.equal(await dialog.locator('input').nth(3).inputValue(), 'deepseek-flash')
  await dialog.locator('input[type="password"]').fill('fixture-new-key')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await row().getByText('109.9912', { exact: true }).waitFor()
  await page.locator('#settings-models').getByRole('region', { name: 'DeepSeek 官方账户余额' }).last().getByText('109.9912', { exact: true }).waitFor()
  checks.push('official endpoint preset; masked key entry; save mounts a fresh account balance query')

  for (const theme of ['old', 'endfield', 'bluearchive', 'astrolabe']) {
    await page.evaluate(themeId => { const state = JSON.parse(localStorage.getItem('minis.theme.v4')); state.themeId = themeId; localStorage.setItem('minis.theme.v4', JSON.stringify(state)) }, theme)
    await page.reload(); await row().getByText('109.9912', { exact: true }).waitFor()
    await noOverflow(theme + ' desktop')
    const undersized = await page.locator('.deepseek-balance').evaluateAll(roots => roots.flatMap(root => [...root.querySelectorAll('*')].filter(el => [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim()) && parseFloat(getComputedStyle(el).fontSize) < 12).map(el => el.tagName)))
    assert.deepEqual(undersized, [])
    await page.screenshot({ path: path.join(output, theme + '-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 }); await noOverflow(theme + ' mobile')
    await row().scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(output, theme + '-mobile.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
  }
  await page.evaluate(() => {
    const sizes = [...document.querySelectorAll('.deepseek-balance, .deepseek-balance *')].map(el => [el, parseFloat(getComputedStyle(el).fontSize)])
    for (const [el, size] of sizes) { el.style.fontSize = size * 2 + 'px'; el.style.lineHeight = '1.5'; el.style.letterSpacing = '.12em'; el.style.wordSpacing = '.16em' }
  })
  await noOverflow('200% text and expanded spacing')
  await row().scrollIntoViewIfNeeded(); await page.screenshot({ path: path.join(output, 'double-text.png') })
  checks.push('four themes desktop/mobile; no balance text below 12px; 200% text and expanded spacing reflow')

  await page.evaluate(() => { location.hash = '#/search' })
  await page.locator('.deepseek-balance').waitFor({ state: 'detached' })
  const beforeNavigation = queried().length
  await page.clock.fastForward(60_000)
  assert.equal(queried().length, beforeNavigation)
  checks.push('polling stops after leaving settings')
  assert.deepEqual(errors, [])
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify({ checks, balanceRequests: queried().length, errors }, null, 2))
  console.log(JSON.stringify({ ok: true, checks: checks.length, output }))
} finally { await browser.close() }
