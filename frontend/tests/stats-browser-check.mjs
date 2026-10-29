import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/stats-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const endpoint = 'https://fixture.invalid/v1'
const model = 'reasoning-model-with-a-long-name-for-usage-review'
const modelInfo = { provider: endpoint, model }
let fail = false, empty = false, savedPrice = null
const requested = [], errors = [], checks = []
function view(url) {
  const days = Number(url.searchParams.get('days') || 30)
  const from = url.searchParams.get('from') || (days === -1 ? '2026-10-01' : days === 7 ? '2026-10-09' : days === 90 ? '2026-07-18' : '2026-09-16')
  const to = url.searchParams.get('to') || '2026-10-15'
  const bucket = { key: 'chat', ...modelInfo, promptTokens: empty ? 0 : 800, completionTokens: empty ? 0 : 200, totalTokens: empty ? 0 : 1020, calls: empty ? 0 : 3, unpricedCalls: empty ? 0 : 1, costs: empty ? [] : [{ currency: 'CNY', amount: savedPrice ? 0.0003 : 0.0001 }, { currency: 'USD', amount: 0.0000001 }] }
  const daily = []
  for (let time = Date.parse(from + 'T00:00:00Z'); time <= Date.parse(to + 'T00:00:00Z'); time += 86400000)
    daily.push({ ...bucket, key: new Date(time).toISOString().slice(0, 10), ...(time !== Date.parse(to + 'T00:00:00Z') ? { promptTokens: 0, completionTokens: 0, totalTokens: 0, calls: 0, unpricedCalls: 0, costs: [] } : {}) })
  return {
    total: { ...bucket, reportedCalls: empty ? 0 : 1, estimatedCalls: empty ? 0 : 1, partialCalls: empty ? 0 : 1, unsuccessfulCalls: empty ? 0 : 1, cachedTokens: 100, reasoningTokens: 20 },
    bySource: empty ? [] : [bucket], byModel: empty ? [] : [{ ...bucket, key: endpoint + '\n' + model }],
    daily, from, to, days: daily.length, timeZone: 'Asia/Shanghai', today: '2026-10-15', generatedAt: 1792036800000,
    sources: ['chat', 'coding'], models: [modelInfo], recordingFailures: 0,
    prices: savedPrice ? [{ id: 1, ...savedPrice, inputPerMillion: Number(savedPrice.inputPerMillion), outputPerMillion: Number(savedPrice.outputPerMillion), cachedPerMillion: savedPrice.cachedPerMillion === null ? null : Number(savedPrice.cachedPerMillion) }] : [],
    recentCalls: empty ? [] : ['reported', 'estimated', 'partial'].map((quality, i) => ({
      id: i + 1, createdAt: 1792036800000 - i * 1000, source: 'chat', ...modelInfo,
      promptTokens: 100, completionTokens: 50, totalTokens: quality === 'partial' ? 170 : 150,
      quality, status: quality === 'partial' ? 'interrupted' : 'success', cachedTokens: 10, reasoningTokens: 5,
      currency: quality === 'partial' ? null : 'CNY', cost: quality === 'partial' ? null : 0.00001,
    })),
  }
}
const browser = await chromium.launch({ channel: 'chrome', headless: true, downloadsPath: path.join(output, 'downloads') })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true })
const page = await context.newPage()
page.setDefaultTimeout(7000)
page.on('pageerror', error => errors.push(error.message))
await context.addInitScript(() => {
  if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({
    v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false },
  }))
})
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.protocol === 'blob:') { await route.continue(); return }
  if (url.origin !== 'http://usage.test') { await route.abort(); return }
  if (!url.pathname.startsWith('/api/')) {
    if (url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return }
    const file = path.resolve(dist, '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) { await route.abort(); return }
    try { await route.fulfill({ path: file }) } catch { await route.fulfill({ status: 404, body: '' }) }
    return
  }
  if (url.pathname === '/api/stats/prices') {
    savedPrice = route.request().postDataJSON()
    await route.fulfill({ json: { saved: true } }); return
  }
  if (url.pathname.startsWith('/api/stats/prices/') && route.request().method() === 'DELETE') {
    savedPrice = null; await route.fulfill({ json: { deleted: true } }); return
  }
  if (url.pathname === '/api/stats') {
    requested.push(Object.fromEntries(url.searchParams))
    await route.fulfill(fail ? { status: 503, json: { message: 'fixture unavailable' } } : { json: view(url) }); return
  }
  if (url.pathname === '/api/auth/me') { await route.fulfill({ json: { id: 'fixture', username: 'fixture', nickname: '验收用户', role: 'user' } }); return }
  if (url.pathname.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: '' }); return }
  await route.fulfill({ json: [] })
})
async function loaded() {
  await page.locator('.usage-snapshot').waitFor()
  const expected = view(new URL('http://usage.test/api/stats?' + new URLSearchParams(requested.at(-1))))
  await page.waitForFunction(({ from, to }) => {
    const snapshot = document.querySelector('.usage-snapshot')
    const button = [...document.querySelectorAll('.usage-page button')].find(b => b.textContent === '刷新')
    return snapshot?.textContent.includes(from + ' 至 ' + to) && button && !button.disabled && !document.querySelector('.load-state__refresh,.load-state__loading')
  }, expected)
}
async function noPageOverflow(label) {
  const bounds = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }))
  assert.ok(bounds.scroll <= bounds.width + 2, label + ': ' + JSON.stringify(bounds))
}
try {
  await page.goto('http://usage.test/#/stats'); await loaded()
  assert.equal(await page.locator('.usage-page h1').innerText(), '用量 & 统计')
  assert.ok(await page.locator('.usage-metric--cost').innerText().then(t => t.includes('1.00e-7')))
  assert.ok(await page.locator('.usage-notice').innerText().then(t => t.includes('未计价')))
  await page.getByText('查看每日数据表', { exact: true }).click()
  assert.equal(await page.getByRole('region', { name: '每日数据，可横向滚动' }).locator('tbody tr').count(), 30)
  checks.push('initial totals, small fees, quality, accessible daily table')
  await page.getByRole('button', { name: '7 天', exact: true }).click(); await loaded()
  assert.equal(requested.at(-1).days, '7')
  assert.match(await page.locator('.usage-snapshot').innerText(), /2026-10-09/)
  await page.getByRole('button', { name: '本月', exact: true }).click(); await loaded()
  assert.equal(requested.at(-1).days, '-1')
  await page.getByRole('button', { name: '自定义日期', exact: true }).click()
  await page.getByLabel('开始日期', { exact: true }).fill('2026-10-01')
  await page.getByLabel('结束日期', { exact: true }).fill('2026-10-05')
  await page.getByRole('button', { name: '应用日期', exact: true }).click(); await loaded()
  assert.equal(requested.at(-1).from, '2026-10-01'); assert.equal(requested.at(-1).to, '2026-10-05')
  await page.getByRole('combobox', { name: '来源', exact: true }).click()
  await page.getByRole('option', { name: '编程任务', exact: true }).click(); await loaded()
  assert.equal(requested.at(-1).source, 'coding')
  await page.getByRole('combobox', { name: '模型与端点', exact: true }).click()
  await page.getByRole('option', { name: model + ' · ' + endpoint, exact: true }).click(); await loaded()
  assert.equal(requested.at(-1).provider, endpoint); assert.equal(requested.at(-1).model, model)
  checks.push('preset, calendar month, custom range and server-side source/model filters')
  await page.getByRole('button', { name: '清除筛选', exact: true }).click(); await loaded()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出汇总 CSV', exact: true }).click()
  const download = await downloadPromise
  await download.saveAs(path.join(output, download.suggestedFilename()))
  checks.push('CSV download from displayed snapshot')
  await page.getByRole('button', { name: '配置价格', exact: true }).click()
  const form = page.getByRole('form', { name: '模型价格配置' })
  await page.waitForFunction(endpoint => document.querySelector('[aria-label="模型价格配置"] input')?.value === endpoint, endpoint)
  assert.equal(await form.getByLabel('供应商端点', { exact: false }).inputValue(), endpoint)
  await form.getByLabel('输入 / 百万 Token', { exact: true }).fill('1')
  await form.getByLabel('输出 / 百万 Token', { exact: true }).fill('2')
  await form.getByLabel('缓存命中 / 百万 Token', { exact: false }).fill('0.5')
  await page.getByRole('button', { name: '保存价格版本', exact: true }).click()
  await page.getByText('价格已保存，费用估算会随统计刷新。', { exact: true }).waitFor()
  await loaded()
  assert.equal(savedPrice.model, model); assert.equal(savedPrice.inputPerMillion, '1'); assert.equal(savedPrice.cachedPerMillion, '0.5')
  checks.push('provider/model price draft, explicit rates and saved refresh')
  await page.getByText('已保存的价格版本（1）', { exact: true }).click()
  await page.getByRole('button', { name: '删除', exact: true }).click()
  assert.notEqual(savedPrice, null, 'first delete click asks for confirmation')
  await page.getByRole('button', { name: '确认删除此版本', exact: true }).click()
  await page.waitForFunction(() => !document.querySelector('.usage-price details'))
  assert.equal(savedPrice, null)
  checks.push('price deletion requires a second click and refreshes coverage')
  fail = true
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.getByRole('alert').waitFor()
  assert.match(await page.getByRole('alert').innerText(), /尚未更新/)
  fail = false
  await page.getByRole('button', { name: '重试读取', exact: true }).click(); await loaded()
  checks.push('failed refresh retains clearly labelled snapshot and retries')
  await page.locator('#usage-pricing > summary').click()
  await page.getByRole('button', { name: '30 天', exact: true }).click(); await loaded()
  for (const theme of ['old', 'endfield', 'bluearchive', 'astrolabe']) {
    await page.evaluate(themeId => {
      const state = JSON.parse(localStorage.getItem('minis.theme.v4'))
      state.themeId = themeId; localStorage.setItem('minis.theme.v4', JSON.stringify(state))
    }, theme)
    await page.reload(); await loaded()
    await page.locator('.usage-page h1').scrollIntoViewIfNeeded()
    await noPageOverflow(theme + ' desktop')
    const smallFonts = await page.locator('.usage-page').evaluate(root => [...root.querySelectorAll('*')].filter(el => [...el.childNodes].some(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim()) && parseFloat(getComputedStyle(el).fontSize) < 12).map(el => el.tagName + ':' + el.textContent.slice(0, 30)))
    assert.deepEqual(smallFonts, [], theme + ' has no routine text below 12px')
    await page.screenshot({ path: path.join(output, theme + '-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await noPageOverflow(theme + ' mobile')
    await page.screenshot({ path: path.join(output, theme + '-mobile.png'), fullPage: true })
    await page.getByRole('heading', { name: '按模型与供应商', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(output, theme + '-mobile-models.png') })
    await page.getByRole('button', { name: '配置价格', exact: true }).click()
    await page.getByRole('button', { name: '保存价格版本', exact: true }).scrollIntoViewIfNeeded()
    await noPageOverflow(theme + ' mobile price form')
    await page.screenshot({ path: path.join(output, theme + '-mobile-prices.png') })
    await page.setViewportSize({ width: 1440, height: 1000 })
  }
  checks.push('four themes on desktop and mobile without page overflow')
  await page.setViewportSize({ width: 720, height: 1000 })
  await noPageOverflow('200% equivalent viewport')
  checks.push('reflow at equivalent 200% viewport width')
  await page.evaluate(() => {
    const sizes = [...document.querySelectorAll('.usage-page *')].map(el => [el, parseFloat(getComputedStyle(el).fontSize), el.style.fontSize])
    window.__usageFontSizes = sizes
    for (const [el, size] of sizes) el.style.fontSize = size * 2 + 'px'
  })
  await noPageOverflow('200% text size')
  await page.getByRole('button', { name: '保存价格版本', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(output, 'double-text-prices.png') })
  await page.evaluate(() => { for (const [el, _size, oldSize] of window.__usageFontSizes) el.style.fontSize = oldSize })
  checks.push('all usage-page text enlarged to 200% without page overflow')
  empty = true
  await page.getByRole('button', { name: '刷新', exact: true }).click(); await loaded()
  await page.getByText('当前日期与筛选条件下没有用量记录。可以调整范围，或发起一次模型调用后刷新。', { exact: true }).waitFor()
  assert.match(await page.locator('.usage-metric--cost').innerText(), /未计价/)
  checks.push('empty state does not imply zero cost')
  assert.deepEqual(errors, [])
  await fs.writeFile(path.join(output, 'checks.json'), JSON.stringify({ checks, requested, errors }, null, 2))
  console.log(JSON.stringify({ checks, errors, output }, null, 2))
} catch (error) {
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true })
  console.log(JSON.stringify({ labels: await page.locator('.usage-page label').allTextContents(), errors, requested: requested.slice(-4) }))
  throw error
} finally { await browser.close() }

