import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/deepseek-web-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const config = { baseUrl: 'https://api.deepseek.com', hasApiKey: false, fastEnabled: false, ragEnabled: false, fetchEnabled: true, count: 5, timeoutSeconds: 15 }
const requests = [], errors = [], checks = []
let secret = '', testFails = false, searchMode = 'web'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => {
  window.__MINIS_LOCAL_TOKEN__ = 'fixture-desktop-token'
  if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({
    v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false },
  }))
})
const page = await context.newPage()
page.setDefaultTimeout(8000)
page.on('pageerror', e => errors.push(e.message))
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url())
  if (url.origin !== 'http://deepseek.test') { await route.abort(); return }
  if (!url.pathname.startsWith('/api/')) {
    if (url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return }
    const file = path.resolve(dist, '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) { await route.abort(); return }
    try { await route.fulfill({ path: file }) } catch { await route.fulfill({ status: 404, body: '' }) }
    return
  }
  const data = request.postData() ? request.postDataJSON() : null
  requests.push({ path: url.pathname, method: request.method(), data })
  if (url.pathname === '/api/auth/me') { await route.fulfill({ json: { id: 'fixture', username: 'fixture', nickname: '测试用户', role: 'user' } }); return }
  if (url.pathname.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: '' }); return }
  if (url.pathname === '/api/settings') { await route.fulfill({ json: { models: [], defaultModelId: 'env', embedding: { mode: 'llm', model: '' }, rerank: { mode: 'off', model: '' } } }); return }
  if (url.pathname === '/api/settings/tts' || url.pathname === '/api/settings/comfyui') { await route.fulfill({ json: { endpoints: [], profiles: [], defaultProfileId: null } }); return }
  if (url.pathname === '/api/settings/deepseek-web/balance') {
    await route.fulfill({ json: { status: 'ready', available: true,
      balances: [{ currency: 'CNY', totalBalance: '12.50', grantedBalance: '2.50', toppedUpBalance: '10.00' }],
      checkedAt: Date.now(), attemptedAt: Date.now(), error: null } }); return
  }
  if (url.pathname === '/api/settings/deepseek-web') {
    if (request.method() === 'PUT') {
      if (data.apiKey !== null) secret = data.apiKey
      Object.assign(config, data); delete config.apiKey; config.hasApiKey = !!secret
    }
    await route.fulfill({ json: config }); return
  }
  if (url.pathname.startsWith('/api/settings/deepseek-web/test/')) {
    await route.fulfill({ json: { ok: !testFails, elapsedMs: 12, detail: '请求成功', error: testFails ? 'DeepSeek search HTTP 402：账号余额不足' : null } }); return
  }
  if (url.pathname === '/api/search') {
    const hit = { kind: 'doc', chunkId: null, entityId: null, title: '本地资料', snippet: '本地正文', source: 'doc', sourceRef: 'doc:1', url: null, score: .016, matched: [], status: null }
    const result = { title: '网页资料 <b>原样显示</b>', url: 'https://example.com/article', snippet: '网页短摘要', summary: '网页长摘要', content: '', siteName: '示例站点', date: '2026-10-18', score: 2.4, authority: 3 }
    await route.fulfill({ json: { query: data.query, graph: { nodes: [], edges: [] },
      hits: searchMode === 'mixed' || searchMode === 'failed' ? [hit] : [],
      web: { enabled: true, results: searchMode === 'web' || searchMode === 'mixed' ? [result] : [],
        error: searchMode === 'failed' ? 'DeepSeek search HTTP 401：API Key 无效' : null,
        warnings: searchMode === 'warning' ? ['搜索词超过 128 字符，已使用前 128 字符检索'] : [] } } }); return
  }
  await route.fulfill({ json: [] })
})
const panel = page.locator('#settings-deepseek-web')
const keyInput = panel.locator('input[type="password"]')
const lastSave = () => requests.filter(r => r.path === '/api/settings/deepseek-web' && r.method === 'PUT').at(-1)
async function noOverflow(label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)
  assert.ok(overflow <= 1, `${label}: overflow ${overflow}px`)
}
try {
  await page.goto('http://deepseek.test/#/settings')
  await panel.getByRole('button', { name: '保存联网检索配置' }).waitFor()
  await keyInput.fill('fixture-secret')
  await panel.getByRole('checkbox', { name: '在首页 FAST 搜索中使用' }).check()
  await panel.getByRole('button', { name: '保存联网检索配置' }).click()
  await panel.getByText('DeepSeek Search / Fetch 配置已保存', { exact: true }).waitFor()
  assert.equal(lastSave().data.fastEnabled, true); assert.equal(lastSave().data.ragEnabled, false)
  assert.equal(await keyInput.inputValue(), ''); assert.equal(config.hasApiKey, true)
  assert.equal(JSON.stringify(config).includes('fixture-secret'), false)
  checks.push('save independent FAST/RAG switches; write-only API key; clear password draft after save')

  await panel.getByRole('checkbox', { name: '在 RAG 中启用' }).check()
  await panel.getByRole('button', { name: '测试 Search', exact: true }).click()
  await panel.getByText('请求成功（12 ms）', { exact: true }).waitFor()
  assert.equal(config.ragEnabled, false)
  await panel.getByRole('button', { name: '测试 Fetch', exact: true }).click()
  await panel.getByText('请求成功（12 ms）', { exact: true }).waitFor()
  testFails = true
  await panel.getByRole('button', { name: '测试 Search', exact: true }).click()
  await panel.getByRole('alert').filter({ hasText: '账号余额不足' }).waitFor()
  testFails = false
  await panel.getByRole('button', { name: '保存联网检索配置' }).click()
  await panel.getByText('DeepSeek Search / Fetch 配置已保存', { exact: true }).waitFor()
  assert.equal(lastSave().data.apiKey, null); assert.equal(config.ragEnabled, true)
  checks.push('Search/Fetch test draft without saving; actionable error display; null preserves saved key')

  await panel.getByRole('button', { name: '清除已保存密钥', exact: true }).click()
  assert.equal(await panel.getByRole('checkbox', { name: '在 RAG 中启用' }).isChecked(), false)
  await panel.getByRole('button', { name: '放弃修改' }).click()
  assert.equal(await panel.getByRole('checkbox', { name: '在 RAG 中启用' }).isChecked(), true)
  await keyInput.fill('pending-draft')
  await page.evaluate(() => { location.hash = '#/search?q=web' })
  await page.getByRole('region', { name: '网页搜索结果' }).waitFor()
  assert.equal(await page.getByRole('link', { name: '网页资料 <b>原样显示</b>' }).getAttribute('href'), 'https://example.com/article')
  assert.equal(await page.getByRole('link', { name: '网页资料 <b>原样显示</b>' }).getAttribute('target'), '_blank')
  assert.equal(await page.getByRole('region', { name: '网页搜索结果' }).locator('b').count(), 0)
  assert.equal(await page.getByText('未找到与「web」相符的结果。').count(), 0)
  await page.evaluate(() => { location.hash = '#/settings' })
  await keyInput.waitFor(); assert.equal(await keyInput.inputValue(), 'pending-draft')
  await panel.getByRole('button', { name: '放弃修改' }).click()
  checks.push('credential clearing is an explicit draft; drafts survive navigation; remote-only results and safe text rendering')

  searchMode = 'mixed'
  await page.evaluate(() => { location.hash = '#/search?q=mixed' })
  await page.getByText('本地资料', { exact: true }).waitFor()
  await page.getByRole('region', { name: '网页搜索结果' }).waitFor()
  await page.getByText('约 2 条结果', { exact: false }).waitFor()
  const searchBefore = requests.filter(r => r.path === '/api/search').length
  const ragRequest = page.waitForRequest(r => new URL(r.url()).pathname === '/api/search' && r.postDataJSON()?.rag === true)
  await page.evaluate(() => { location.hash = '#/search?q=mixed&rag=1' })
  await ragRequest
  await page.getByText('当前处于 RAG 测试模式', { exact: false }).waitFor()
  assert.ok(requests.filter(r => r.path === '/api/search').length > searchBefore)
  assert.equal(requests.filter(r => r.path === '/api/search').at(-1).data.rag, true)
  searchMode = 'failed'
  await page.evaluate(() => { location.hash = '#/search?q=failed' })
  await page.getByText('网页检索失败：', { exact: false }).waitFor()
  await page.getByText('本地资料', { exact: true }).waitFor()
  searchMode = 'warning'
  await page.evaluate(() => { location.hash = '#/search?q=warning' })
  await page.getByText('搜索词超过 128 字符，已使用前 128 字符检索', { exact: true }).waitFor()
  checks.push('mixed source result count; mode-only changes rerun search; web failure preserves local cards; warnings visible')

  for (const theme of ['old', 'endfield', 'bluearchive', 'astrolabe']) {
    await page.evaluate(themeId => {
      const state = JSON.parse(localStorage.getItem('minis.theme.v4')); state.themeId = themeId
      localStorage.setItem('minis.theme.v4', JSON.stringify(state)); location.hash = '#/settings'
    }, theme)
    await page.reload()
    await panel.getByRole('button', { name: '保存联网检索配置' }).waitFor()
    await panel.scrollIntoViewIfNeeded()
    await noOverflow(theme + ' desktop')
    const sizes = await panel.locator('input:not([type="checkbox"]),button').evaluateAll(elements => elements.map(e => parseFloat(getComputedStyle(e).fontSize)))
    assert.ok(sizes.every(n => n >= 14), `${theme} form font sizes ${sizes}`)
    await panel.screenshot({ path: path.join(output, theme + '-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    await noOverflow(theme + ' mobile')
    await panel.getByRole('button', { name: '保存联网检索配置' }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(output, theme + '-mobile.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
  }
  checks.push('four themes: desktop/mobile layout, form typography, reachable save/test actions')
  assert.deepEqual(errors, [])
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify({ checks, errors, requests: requests.length }, null, 2))
  console.log(JSON.stringify({ checks, errors, output }, null, 2))
} finally { await browser.close() }
