import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/agent-capabilities-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const peers = ['builtin', 'member'].map((id, sort) => ({
  id, sort, name: ['核心助手', '专业成员'][sort], builtin: id !== 'member',
  tools: [], terminals: [], modelId: null, avatar: null, note: null, role: null, roleColor: null,
  executionKind: 'CHAT',
}))
const tools = [
  { name: 'read_doc', description: '读取文档内容。', defaultGranted: true, writes: false, domain: 'doc', domainLabel: '文档' },
  { name: 'write_doc', description: '写入文档，保存实际成果。', defaultGranted: false, writes: true, domain: 'doc', domainLabel: '文档' },
  { name: 'create_plan', description: '创建后台职责并安排执行。', defaultGranted: false, writes: false, domain: 'other', domainLabel: '调度' },
  { name: 'run_command', description: '在工作空间执行命令。', defaultGranted: false, writes: false, domain: 'terminal', domainLabel: '终端' },
]
const targets = [{ id: 'one', name: '开发环境', kind: 'ssh', enabled: true }, { id: 'new', name: '新增环境', kind: 'docker', enabled: false }]
const saves = [], queries = [], errors = []
const browser = await chromium.launch({ channel: 'chrome', headless: true })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.addInitScript(() => {
    window.__MINIS_LOCAL_TOKEN__ = 'fixture-desktop-token'
    localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } }))
  })
  const page = await context.newPage()
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await context.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url())
    if (url.origin !== 'http://agent-capabilities.test') return route.abort()
    if (!url.pathname.startsWith('/api/')) {
      const file = path.resolve(dist, url.pathname === '/' ? 'index.html' : '.' + url.pathname)
      if (!file.startsWith(dist + path.sep)) return route.abort()
      try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404, body: '' }) }
    }
    const json = value => route.fulfill({ json: value })
    if (url.pathname === '/api/auth/me') return json({ id: 'fixture', username: 'fixture', nickname: '验收用户', role: 'user' })
    if (url.pathname.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    if (url.pathname === '/api/settings') return json({ models: [], embedding: { mode: 'llm' }, rerank: { mode: 'off' } })
    if (url.pathname === '/api/terminal') return json({ targets })
    if (url.pathname === '/api/agents/peers') return json(peers)
    if (url.pathname === '/api/coding/capabilities') return json({ enabled: true, agent: peers.find(p => p.id === 'builtin') })
    if (url.pathname === '/api/coding/projects') return json([{ id: 'p1', name: '角色配置验收', workspaceId: 'one', workspaceName: '开发环境', directory: '.', kind: 'ssh', enabled: true }])
    if (url.pathname.endsWith('/files')) return json({ entries: [], path: '.' })
    if (url.pathname === '/api/agents/tools') {
      const id = url.searchParams.get('peerId'); queries.push(id)
      return json(id === 'builtin' ? tools : tools.slice(0, 2))
    }
    if (url.pathname.endsWith('/prompts')) return json({ categories: [
      { key: 'chat', name: '对话', description: '', contexts: [] },
      ...(url.pathname.includes('/builtin/') ? [{ key: 'skills', name: 'Skills · 状态化提示词', description: '仅在编程 IDE 中启用。', contexts: [{ key: 'ide', name: '编程 IDE', description: '普通聊天不加载。', parts: [{ key: 'coding_skill', name: '编程 Skill', description: 'IDE 状态规程', builtin: '保留调查、实施、验证与交付规程。', builtinSource: 'code', versions: [] }], runtime: [] }] }] : []),
    ] })
    if (req.method() === 'PUT' && url.pathname.startsWith('/api/agents/peers/')) {
      const id = url.pathname.split('/').at(-1), body = req.postDataJSON()
      saves.push({ id, body }); Object.assign(peers.find(p => p.id === id), body)
      return json(peers.find(p => p.id === id))
    }
    return json([])
  })
  const open = async id => {
    await page.goto(`http://agent-capabilities.test/#/multi-agent/${id}`)
    await page.getByRole('heading', { name: peers.find(p => p.id === (id === 'builtin-coding' ? 'builtin' : id)).name, exact: true }).waitFor()
    await page.getByRole('button', { name: '能力', exact: true }).click()
    await page.getByText('可用工具', { exact: true }).waitFor()
  }
  await open('builtin')
  await page.getByText('全部开放', { exact: true }).waitFor()
  const firstDomain = page.getByRole('tab', { name: /文档/ })
  await firstDomain.focus()
  await page.keyboard.press('End')
  assert.equal(await page.getByRole('tab', { name: /终端/ }).getAttribute('aria-selected'), 'true')
  await page.keyboard.press('Home')
  assert.equal(await firstDomain.getAttribute('aria-selected'), 'true')
  assert.equal(await page.getByText('显示所有工具', { exact: true }).count(), 0)
  for (const name of ['开发环境', '新增环境']) {
    const card = page.getByRole('button', { name: new RegExp(name) })
    assert.equal(await card.getAttribute('aria-pressed'), 'true')
    assert.equal(await card.getAttribute('aria-disabled'), 'true')
  }
  for (const tab of await page.getByRole('tablist', { name: '工具领域' }).getByRole('tab').all()) {
    await tab.click()
    for (const card of await page.getByRole('tabpanel').getByRole('button').all()) {
      assert.equal(await card.getAttribute('aria-pressed'), 'true')
      assert.equal(await card.getAttribute('aria-disabled'), 'true')
    }
  }
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByText('「核心助手」的资料已保存', { exact: true }).waitFor()
  assert.equal(Object.hasOwn(saves.at(-1).body, 'tools'), false)
  assert.equal(Object.hasOwn(saves.at(-1).body, 'terminals'), false)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.getByRole('tab', { name: /文档/ }).click()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `overflow at ${width}px`)
    await page.screenshot({ path: path.join(output, `core-${width}.png`), fullPage: true })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await open('member')
  assert.equal(await page.getByText('显示所有工具', { exact: true }).count(), 1)
  const write = page.getByRole('button', { name: /write_doc/ })
  assert.equal(await write.getAttribute('aria-pressed'), 'false')
  assert.equal(await write.getAttribute('aria-disabled'), null)
  await write.click()
  await page.getByRole('button', { name: /开发环境/ }).click()
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByText('「专业成员」的资料已保存', { exact: true }).waitFor()
  assert.deepEqual(saves.at(-1).body.tools, ['write_doc'])
  assert.deepEqual(saves.at(-1).body.terminals, ['one'])
  await open('builtin-coding')
  assert.equal(await page.getByText('全部开放', { exact: true }).count(), 1)
  assert.equal(await page.getByRole('button', { name: /write_doc/ }).getAttribute('aria-pressed'), 'true')
  assert.deepEqual(new Set(queries), new Set(['builtin', 'member']))

  await page.goto('http://agent-capabilities.test/#/coding?project=p1')
  await page.getByRole('link', { name: '编辑核心助手的头像、名字与人设', exact: true }).click()
  await page.getByRole('heading', { name: '核心助手', exact: true }).waitFor()
  await page.getByRole('button', { name: '提示词 & 上下文工程', exact: true }).click()
  await page.getByRole('button', { name: 'Skills · 状态化提示词', exact: true }).click()
  await page.getByText('编程 Skill', { exact: true }).waitFor()
  await page.getByText('普通聊天不加载。', { exact: true }).waitFor()
  await page.getByRole('textbox', { name: /^名字/ }).fill('牧濑红莉栖')
  await page.locator('input[type=file]').setInputFiles({ name: 'character.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=', 'base64') })
  await page.getByRole('button', { name: '移除', exact: true }).waitFor()
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByText('「牧濑红莉栖」的资料已保存', { exact: true }).waitFor()
  assert.equal(saves.at(-1).body.name, '牧濑红莉栖')
  assert.ok(saves.at(-1).body.avatar.startsWith('data:image/jpeg;base64,'))
  const savedAvatar = saves.at(-1).body.avatar
  await page.getByRole('link', { name: '编程工作台 ↗', exact: true }).click()
  const profileLink = page.getByRole('link', { name: '编辑牧濑红莉栖的头像、名字与人设', exact: true })
  await profileLink.waitFor()
  assert.equal(await profileLink.locator('img').getAttribute('src'), savedAvatar)
  await page.reload()
  await profileLink.waitFor()
  assert.equal(await profileLink.locator('img').getAttribute('src'), savedAvatar)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    assert.ok(await profileLink.isVisible())
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `profile overflow at ${width}px`)
    await page.screenshot({ path: path.join(output, `coding-profile-${width}.png`), fullPage: true })
  }
  // An explicit empty project opens the start page instead of the remembered project.
  await page.goto('http://agent-capabilities.test/#/coding?project=')
  await profileLink.waitFor()
  assert.equal(await profileLink.locator('img').getAttribute('src'), savedAvatar)
  await profileLink.click()
  await page.getByRole('button', { name: '移除', exact: true }).click()
  await page.getByRole('button', { name: '保存资料', exact: true }).click()
  await page.getByText('「牧濑红莉栖」的资料已保存', { exact: true }).waitFor()
  assert.equal(saves.at(-1).body.avatar, '')
  await page.getByRole('link', { name: '编程工作台 ↗', exact: true }).click()
  await profileLink.waitFor()
  assert.equal(await profileLink.locator('img').count(), 0)
  assert.equal(await profileLink.locator('.coding-agent-name').textContent(), '牧濑红莉栖')
  assert.deepEqual(errors, [])
  console.log('Agent capabilities: core grants, coding profile upload/rename/save/return/reload/remove, start/studio and mobile layout passed.')
} finally {
  await browser.close()
}
