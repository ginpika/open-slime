import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium, webkit } = require('playwright')
let PNG
try { ({ PNG } = require('pngjs')) } catch { /* Screenshots remain available for visual inspection. */ }
const engine = process.env.MINIS_BROWSER || 'chromium'
assert.ok(['chromium', 'webkit'].includes(engine))
const browser = await (engine === 'webkit' ? webkit.launch({ headless: true }) : chromium.launch({ channel: 'chrome', headless: true }))
const dist = path.resolve(process.env.MINIS_FRONTEND_DIST || path.join(frontend, 'dist'))
const output = path.resolve(frontend, '../backend/target/home-navigation-browser-check')
await fs.mkdir(output, { recursive: true })
const event = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`

try {
  const appearances = [
    { themeId: 'old', schemeMode: 'light', material: 'paper' },
    { themeId: 'old', schemeMode: 'dark', material: 'paper' },
    ...['paper', 'glass'].flatMap(material => ['light', 'dark'].map(schemeMode => ({ themeId: 'astrolabe', schemeMode, material }))),
  ]
  for (const appearance of appearances) {
    const { themeId, schemeMode, material } = appearance
    const variant = themeId === 'old' ? `old-${schemeMode}` : `astrolabe-${material}-${schemeMode}`
    const context = await browser.newContext({ viewport: { width: 1800, height: 1033 }, deviceScaleFactor: 2 })
    await context.addInitScript(({ themeId, schemeMode, material }) => {
      window.__MINIS_LOCAL_TOKEN__ = 'fixture-token'
      window.__TAURI_INTERNALS__ = {}
      localStorage.setItem('minis.theme.v4', JSON.stringify({
        v: 4, themeId, schemeMode, override: { seed: {} },
        mono: { sidebar: false, hideChrome: true }, astrolabe: { hideChrome: false, material },
      }))
    }, appearance)
    const errors = [], submissions = []
    const sessions = [{ id: 'home-session', title: '首页切换测试', generating: false }]
    let rows = [{ id: 1, role: 'user', content: '保留原来的会话' }, { id: 2, role: 'assistant', content: '历史回复' }]
    let eventConnection = Promise.withResolvers(), chatRequest = Promise.withResolvers()
    const page = await context.newPage()
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url()), p = url.pathname
      if (url.origin !== 'http://home-navigation.test') return route.abort()
      if (!p.startsWith('/api/')) {
        const file = path.resolve(dist, p === '/' ? 'index.html' : '.' + p)
        if (!file.startsWith(dist + path.sep)) return route.abort()
        try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404, body: '' }) }
      }
      const json = data => route.fulfill({ json: data })
      if (p === '/api/auth/me') return json({ id: 'fixture', username: 'fixture', nickname: '测试用户' })
      if (p === '/api/events/stream') { eventConnection.resolve(route); return }
      if (p === '/api/settings') return json({ models: [], defaultModelId: 'env', embedding: { mode: 'llm' }, rerank: { mode: 'off' } })
      if (p === '/api/prompts') return json({ categories: [] })
      if (p === '/api/settings/tts') return json({ profiles: [] })
      if (p === '/api/sessions' && request.method() === 'POST') {
        const session = { id: `new-session-${sessions.length}`, title: '新会话', generating: false }
        sessions.push(session); rows = []
        return json(session)
      }
      if (p === '/api/sessions') return json(sessions)
      if (p.endsWith('/config')) return json(sessions.find(s => p.includes(s.id)) || sessions[0])
      if (p.endsWith('/messages')) return json(rows)
      if (p.endsWith('/live')) return json({ running: false, content: '', trace: [] })
      if (p === '/api/chat/stream') { submissions.push(request.postDataJSON()); chatRequest.resolve(route); return }
      return json([])
    })
    const search = page.locator('[data-home-view="search"]')
    const chat = page.locator('[data-home-view="conversation"]')
    const searchInput = search.getByRole('textbox')
    const composer = page.getByPlaceholder('继续输入…')
    const surfaces = search.locator('.ba-home-inputbar, .ba-home-slider')
    let screenshotIndex = 0, initialInkRatios
    const screenshotHome = async label => page.screenshot({
      path: path.join(output, `${engine}-${variant}-${String(++screenshotIndex).padStart(2, '0')}-${label}.png`),
      animations: 'allow', caret: 'initial',
    })
    const surfaceState = async () => surfaces.evaluateAll(elements => elements.map(el => {
      const rect = el.getBoundingClientRect(), style = getComputedStyle(el)
      return {
        x: rect.x, y: rect.y, width: rect.width, height: rect.height,
        filter: style.backdropFilter || style.webkitBackdropFilter || 'none',
      }
    }))
    const inkRatios = (buffer, boxes) => {
      if (!PNG) return null
      const png = PNG.sync.read(buffer), scale = png.width / page.viewportSize().width
      return boxes.map(box => {
        const colors = [], bounds = {
          left: Math.max(0, Math.ceil((box.x + 3) * scale)),
          top: Math.max(0, Math.ceil((box.y + 3) * scale)),
          right: Math.min(png.width, Math.floor((box.x + box.width - 3) * scale)),
          bottom: Math.min(png.height, Math.floor((box.y + box.height - 3) * scale)),
        }
        for (let y = bounds.top; y < bounds.bottom; y += 2) for (let x = bounds.left; x < bounds.right; x += 2) {
          const i = (y * png.width + x) * 4
          colors.push([png.data[i], png.data[i + 1], png.data[i + 2]])
        }
        assert.ok(colors.length > 0, 'visible surface must occupy screenshot pixels')
        const median = [0, 1, 2].map(channel => colors.map(c => c[channel]).sort((a, b) => a - b)[Math.floor(colors.length / 2)])
        return colors.filter(c => c.reduce((sum, value, channel) => sum + Math.abs(value - median[channel]), 0) > 70).length / colors.length
      })
    }
    const assertPaint = async buffer => {
      const boxes = await surfaceState()
      assert.equal(boxes.length, 2, 'homepage has an input surface and a mode-switch surface')
      for (const [i, box] of boxes.entries()) {
        if (themeId === 'old') assert.equal(box.filter, 'none', `opaque Mono surface ${i} must not create a backdrop-filter layer`)
        if (themeId === 'astrolabe' && material === 'glass') assert.notEqual(box.filter, 'none', `glass surface ${i} must preserve its intended backdrop filter`)
      }
      const ratios = inkRatios(buffer, boxes)
      if (ratios && initialInkRatios) for (const [i, ratio] of ratios.entries()) {
        assert.ok(ratio >= Math.max(.003, initialInkRatios[i] * .3), `surface ${i} was laid out but not painted: ink ratio ${ratio}, initial ${initialInkRatios[i]}`)
      }
      return ratios
    }
    const assertHome = async () => {
      await searchInput.waitFor({ state: 'visible' })
      assert.equal(await chat.isVisible(), false, 'offscreen chat must not be interactive')
      assert.equal(await searchInput.evaluate(el => el === document.activeElement), true, 'focus returns to the homepage')
      assert.equal(await page.locator('main').evaluate(el => el.scrollTop), 0)
      const bounds = await searchInput.boundingBox()
      assert.ok(bounds.y >= 0 && bounds.y + bounds.height <= page.viewportSize().height, 'search input is inside the viewport')
    }
    const returnHome = async () => {
      await page.getByRole('button', { name: '返回搜索界面', exact: true }).click()
      // Capture before querying layout or focusing/clicking anything on the homepage. A DOM-visible
      // element can still be missing from the WebKit compositing surface after this transition.
      const pixels = await screenshotHome('immediate-return')
      await assertHome()
      await assertPaint(pixels)
    }
    const assertChat = async () => {
      await composer.waitFor({ state: 'visible' })
      assert.equal(await searchInput.isVisible(), false)
      assert.equal(await composer.evaluate(el => el === document.activeElement), true)
    }
    await page.goto('http://home-navigation.test/#/')
    await searchInput.waitFor({ state: 'visible' })
    await page.evaluate(() => document.fonts.ready)
    initialInkRatios = await assertPaint(await screenshotHome('initial'))
    await page.getByRole('button', { name: '会话', exact: true }).click()
    await page.getByText(sessions[0].title, { exact: true }).click()
    await assertChat()
    await composer.fill('尚未发送的会话草稿')
    await returnHome()

    // A delivered message changes the history length while the user is on the homepage.
    rows.push({ id: 3, role: 'assistant', content: '后台投递的新消息' })
    const connection = await eventConnection.promise
    eventConnection = Promise.withResolvers()
    await connection.fulfill({ contentType: 'text/event-stream', body: event('message', {
      id: 1, sessionId: sessions[0].id, kind: 'delivered', unread: true, title: sessions[0].title,
    }) })
    await chat.getByText('后台投递的新消息', { exact: true }).waitFor({ state: 'attached' })
    const deliveredPixels = await screenshotHome('background-delivery')
    await assertHome()
    await assertPaint(deliveredPixels)
    await page.getByTitle('点击前往会话').waitFor()
    // Raw viewport coordinates avoid locator auto-scrolling that could conceal a broken return.
    await page.mouse.click(page.viewportSize().width / 2, 180)
    await page.keyboard.press('Tab')
    assert.equal(await searchInput.isVisible(), true, 'clicking the homepage and tabbing must not restore hidden chat focus')
    assert.equal(await chat.isVisible(), false)
    assert.equal(await page.locator('main').evaluate(el => el.scrollTop), 0)

    // Explicitly reopening the same session restores its unsent draft and new messages.
    await page.getByTitle('点击前往会话').click()
    await assertChat()
    assert.equal(await composer.inputValue(), '尚未发送的会话草稿')
    await chat.getByText('后台投递的新消息', { exact: true }).waitFor()
    for (let i = 0; i < 3; i++) {
      await returnHome()
      await page.getByRole('button', { name: '会话', exact: true }).click()
      await assertChat()
      assert.equal(await composer.inputValue(), '尚未发送的会话草稿')
    }

    // A generation finishing after return also must not navigate away from the homepage.
    await composer.fill('开始后台生成')
    await composer.press('Enter')
    const stream = await chatRequest.promise
    await returnHome()
    rows.push({ id: 4, role: 'user', content: submissions[0].input }, { id: 5, role: 'assistant', content: '生成完成，留在首页' })
    await stream.fulfill({ contentType: 'text/event-stream', body:
      event('meta', { sessionId: sessions[0].id, messageId: 4 }) +
      event('done', { sessionId: sessions[0].id, messageId: 4, message: '生成完成，留在首页', success: true }) })
    await chat.getByText('生成完成，留在首页', { exact: true }).waitFor({ state: 'attached' })
    const settledPixels = await screenshotHome('background-complete')
    await assertHome()
    await assertPaint(settledPixels)
    await page.getByTitle('点击前往会话').click()
    await assertChat()

    if (themeId === 'astrolabe') {
      const breadcrumbs = page.getByRole('navigation', { name: '面包屑导航' })
      await breadcrumbs.getByText('会话', { exact: true }).waitFor()
      await breadcrumbs.getByRole('link', { name: '首页', exact: true }).click()
      const breadcrumbPixels = await screenshotHome('breadcrumb-return')
      await assertHome()
      await assertPaint(breadcrumbPixels)
      assert.equal(await breadcrumbs.getByText('会话', { exact: true }).count(), 0)
    } else await returnHome()

    for (const [width, height] of [[1800, 1033], [900, 700]]) {
      await page.setViewportSize({ width, height })
      const resizedPixels = await screenshotHome(`resize-${width}`)
      await assertHome()
      await assertPaint(resizedPixels)
      await page.getByRole('button', { name: '会话', exact: true }).click()
      await assertChat()
      await returnHome()
    }

    // Starting a new empty conversation also switches screens when an older one exists.
    await page.getByRole('button', { name: '展开会话列表', exact: true }).click()
    await page.getByRole('button', { name: '新会话', exact: true }).click()
    await assertChat()
    await returnHome()
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    assert.equal(await chat.count(), 0, 'switching to search mode clears the selected conversation')
    await search.getByRole('textbox').fill('查询资料')
    await search.getByRole('textbox').press('Enter')
    await page.waitForURL('**/#/search?q=*')
    assert.deepEqual(errors, [])
    await context.close()
    console.log(`${engine}/${variant}: immediate screenshots, painted surfaces, backdrop filters, return, focus, background delivery/generation, notification, draft, repeated entry, resize, new session and search passed`)
  }
} finally { await browser.close() }
