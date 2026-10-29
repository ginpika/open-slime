import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/user-question-browser-check')
await fs.mkdir(output, { recursive: true })
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const question = { question: '你希望先完成哪一部分？', options: [
  { label: '先完成界面（推荐）', description: '先确认交互，再接入真实服务。' },
  { label: '先完成后端', description: '先完成数据和工具协议。' },
] }
const content = question.question + question.options.map((o, i) => `\n\n${i + 1}. ${o.label}：${o.description}`).join('') + '\n\n也可以自定义回复。'
const trace = [{ type: 'tool', name: 'request_user_input', args: JSON.stringify(question), round: 0 }]
let rows = [], nextId = 1, streamCount = 0
const submissions = [], errors = []
const session = { id: 'question-session', title: '询问工具测试', showCot: false, showRounds: false, generating: false }
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } })
  await context.addInitScript(() => {
    window.__MINIS_LOCAL_TOKEN__ = 'fixture-token'
    localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } }))
  })
  const page = await context.newPage()
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname
    if (url.origin !== 'http://question.test') return route.abort()
    if (!p.startsWith('/api/')) {
      if (p === '/') return route.fulfill({ contentType: 'text/html', body: html })
      const file = path.resolve(dist, '.' + p)
      if (!file.startsWith(dist + path.sep)) return route.abort()
      try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404, body: '' }) }
    }
    const json = data => route.fulfill({ json: data })
    if (p === '/api/auth/me') return json({ id: 'fixture', username: 'fixture', nickname: '测试用户', role: 'user' })
    if (p.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    if (p === '/api/settings') return json({ models: [], defaultModelId: 'env', embedding: { mode: 'llm', model: '' }, rerank: { mode: 'off', model: '' } })
    if (p === '/api/prompts') return json({ categories: [] })
    if (p === '/api/settings/tts') return json({ endpoints: [], profiles: [] })
    if (p === '/api/sessions' && request.method() === 'POST') return json({ id: session.id, projectId: null })
    if (p === '/api/sessions') return json([session])
    if (p.endsWith('/config')) return json(session)
    if (p.endsWith('/messages')) return json(rows)
    if (p.endsWith('/live')) return json({ sessionId: session.id, running: false, content: '', trace: [] })
    if (p === '/api/chat/stream') {
      const data = request.postDataJSON()
      submissions.push(data.input)
      const userId = nextId++
      rows.push({ id: userId, role: 'user', content: data.input })
      const asking = streamCount++ % 2 === 0
      const text = asking ? content : '收到，按照你的选择继续。'
      rows.push({ id: nextId++, role: 'assistant', content: text, trace: asking ? trace : [] })
      const event = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`
      return route.fulfill({ contentType: 'text/event-stream', body:
        event('meta', { sessionId: session.id, messageId: userId }) + event('round', { round: 0 }) +
        (asking ? event('tool', { name: 'request_user_input', args: JSON.stringify(question) }) : '') +
        event('done', { sessionId: session.id, messageId: userId, message: text, success: true }) })
    }
    return json([])
  })
  await page.goto('http://question.test/#/')
  await page.getByRole('button', { name: '会话', exact: true }).click()
  await page.getByText(session.title, { exact: true }).click()
  const composer = page.getByPlaceholder('继续输入…')
  await composer.fill('帮我安排实现顺序')
  await composer.press('Enter')
  const confirm = page.getByRole('button', { name: '确认并继续' })
  await confirm.waitFor()
  assert.equal(await confirm.isDisabled(), true)
  assert.equal(await page.getByText('调用工具 - request_user_input', { exact: true }).count(), 0)
  await page.getByRole('radio', { name: /先完成界面/ }).check()
  assert.equal(submissions.length, 1, 'selection must not auto submit')
  await confirm.click()
  await page.getByText('收到，按照你的选择继续。', { exact: true }).waitFor()
  assert.equal(submissions[1], question.options[0].label)
  assert.equal(await page.getByRole('radio').count(), 0, 'historical question is read only')
  await composer.fill('再问我一个问题')
  await composer.press('Enter')
  await confirm.waitFor()
  await page.reload()
  await page.getByRole('button', { name: '会话', exact: true }).click()
  await page.getByText(session.title, { exact: true }).click()
  await confirm.waitFor()
  assert.equal(await page.getByRole('radio').count(), 3, 'pending question restores from persisted trace')
  const card = confirm.locator('xpath=ancestor::form')
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await card.scrollIntoViewIfNeeded()
    assert.equal(await card.locator('legend').evaluate(el => getComputedStyle(el).fontSize), '16px')
    assert.equal(await card.locator('textarea').evaluate(el => getComputedStyle(el).fontSize), '16px')
    assert.ok(await card.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
    await card.screenshot({ path: path.join(output, `question-${width}.png`) })
  }
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  assert.equal(await card.locator('legend').evaluate(el => getComputedStyle(el).fontSize), '32px')
  assert.ok(await card.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
  await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  await page.getByRole('textbox', { name: '自定义回复内容' }).fill('先完成移动端，并保留现有接口。')
  assert.equal(await page.getByRole('radio', { name: '自定义回复', exact: true }).isChecked(), true)
  await confirm.dblclick()
  await page.getByText('收到，按照你的选择继续。', { exact: true }).last().waitFor()
  assert.equal(submissions.length, 4, 'double click only submits one answer')
  assert.equal(submissions[3], '先完成移动端，并保留现有接口。')
  assert.equal(await confirm.count(), 0)
  assert.deepEqual(errors, [])
  console.log('User questions: Home SSE, CoT hidden, explicit option submit, custom answer, refresh/history, duplicate prevention, desktop/mobile and 200% font passed.')
} finally { await browser.close() }
