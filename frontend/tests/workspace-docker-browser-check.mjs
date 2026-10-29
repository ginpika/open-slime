import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/workspace-docker-browser-check')
await fs.mkdir(output, { recursive: true })
const targets = [], submissions = [], errors = []
let rejectNext = false
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
page.setDefaultTimeout(10000)
page.on('pageerror', error => errors.push(error.message))
await context.addInitScript(() => localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })))
await context.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url()), method = req.method()
  if (url.origin !== 'http://workspace.test') return route.abort()
  if (!url.pathname.startsWith('/api/')) {
    const file = path.resolve(dist, url.pathname === '/' ? 'index.html' : '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) return route.abort()
    try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404, body: '' }) }
  }
  const json = value => route.fulfill({ json: value })
  if (url.pathname === '/api/auth/me') return json({ id: 'fixture', username: 'fixture', nickname: '验收用户', role: 'user' })
  if (url.pathname === '/api/settings') return json({ models: [], embedding: { mode: 'llm' }, rerank: { mode: 'off' } })
  if (url.pathname === '/api/terminal') return json({ targets })
  if (url.pathname === '/api/terminal/commands') return json([])
  if (method === 'POST' || method === 'PUT') {
    const body = req.postDataJSON(); submissions.push(body)
    if (rejectNext) { rejectNext = false; return route.fulfill({ status: 400, json: { message: '宿主机目录必须是绝对路径' } }) }
    const id = method === 'POST' ? `w${targets.length + 1}` : url.pathname.split('/').at(-1)
    const saved = { ...body, id, name: body.name || `docker-${id}` }
    const index = targets.findIndex(t => t.id === id)
    if (index < 0) targets.push(saved); else targets[index] = saved
    return json({ targets })
  }
  return json([])
})

const dialog = page.getByRole('dialog')
const open = () => page.getByRole('button', { name: '新建工作空间', exact: true }).click()
const create = () => dialog.getByRole('button', { name: '创建工作空间', exact: true }).click()
try {
  await page.goto('http://workspace.test/#/workspace')
  await open()
  assert.equal(await dialog.getByRole('textbox').filter({ visible: true }).count(), 0)
  await dialog.getByRole('radio', { name: /Ubuntu/ }).waitFor()
  assert.ok(await dialog.getByRole('radio', { name: /Ubuntu/ }).isChecked())
  for (const name of ['Debian', 'Alpine', 'Arch Linux', 'Ubuntu']) {
    await dialog.getByRole('radio', { name: new RegExp(name) }).check()
    assert.ok(await dialog.getByRole('radio', { name: new RegExp(name) }).isChecked())
  }
  await page.screenshot({ path: path.join(output, 'create-desktop.png'), fullPage: true })
  await create()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(submissions[0].name, '')
  assert.equal(submissions[0].image, 'ubuntu:24.04')
  assert.equal(submissions[0].workdir, '/workspace')
  assert.equal(submissions[0].hostWorkdir, null)

  await open()
  await dialog.getByRole('radio', { name: /Alpine/ }).check()
  await dialog.getByText('绑定工作目录（可选）', { exact: true }).click()
  await dialog.getByRole('textbox', { name: /^宿主机目录/ }).fill('D:\\Projects\\中文 项目')
  await dialog.getByRole('textbox', { name: /^容器内路径/ }).fill('/work/app')
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await dialog.getByRole('textbox', { name: /^宿主机目录/ }).evaluate(el => parseFloat(getComputedStyle(el).fontSize) >= 16))
  const rect = await dialog.boundingBox()
  assert.ok(rect.x >= 0 && rect.x + rect.width <= 391)
  assert.ok(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
  await page.screenshot({ path: path.join(output, 'binding-mobile.png'), fullPage: true })
  rejectNext = true
  await create()
  await page.getByText('宿主机目录必须是绝对路径', { exact: true }).waitFor()
  assert.ok(await dialog.isVisible(), 'failed save preserves the dialog')
  assert.equal(await dialog.getByRole('textbox', { name: /^容器内路径/ }).inputValue(), '/work/app')
  await create()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(submissions.at(-1).hostWorkdir, 'D:\\Projects\\中文 项目')
  assert.equal(submissions.at(-1).workdir, '/work/app')
  assert.equal(submissions.at(-1).image, 'alpine:latest')

  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: '编辑', exact: true }).nth(1).click()
  assert.equal(await dialog.getByRole('textbox', { name: /^宿主机目录/ }).inputValue(), 'D:\\Projects\\中文 项目')
  assert.equal(await dialog.getByRole('textbox', { name: /^容器内路径/ }).inputValue(), '/work/app')
  assert.ok(await dialog.getByRole('radio', { name: /Alpine/ }).isChecked())
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  await open()
  await dialog.getByRole('button', { name: '接入已有容器', exact: true }).click()
  await dialog.getByRole('textbox', { name: /^容器名/ }).fill('my-existing-container')
  assert.equal(await dialog.getByRole('textbox', { name: /^工作目录/ }).inputValue(), '')
  await create()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(submissions.at(-1).container, 'my-existing-container')
  assert.equal(submissions.at(-1).image, null)
  assert.equal(submissions.at(-1).hostWorkdir, null)

  await open()
  await dialog.getByRole('radio', { name: '自定义镜像', exact: true }).check()
  assert.ok(await dialog.getByRole('button', { name: '创建工作空间', exact: true }).isDisabled())
  await dialog.getByRole('textbox', { name: /^镜像地址/ }).fill('local/dev:latest')
  await dialog.getByText('高级设置', { exact: true }).click()
  await dialog.getByRole('textbox', { name: /^工作空间名/ }).fill('我的环境')
  await create()
  await dialog.waitFor({ state: 'hidden' })
  assert.equal(submissions.at(-1).image, 'local/dev:latest')
  assert.equal(submissions.at(-1).name, '我的环境')
  assert.deepEqual(errors, [])
  console.log('Workspace Docker browser checks passed: no-input creation, image cards, optional mount, mobile layout, error retention, edit roundtrip, existing container and custom image.')
} finally { await browser.close() }
