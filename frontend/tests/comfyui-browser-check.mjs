import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/comfyui-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const view = { endpoints: [], profiles: [], defaultProfileId: null }
const requests = [], errors = [], checks = []
const imageId = '11111111-1111-1111-1111-111111111111'
let imageBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')
const workflow = {
  6: { class_type: 'CLIPTextEncode', inputs: { text: 'template prompt' } },
  3: { class_type: 'KSampler', inputs: { seed: 123, steps: 20 } },
  9: { class_type: 'SaveImage', inputs: { images: ['3', 0] } },
}
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => {
  window.__MINIS_LOCAL_TOKEN__ = 'fixture-desktop-token'
  if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({
    v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false },
  }))
})
const page = await context.newPage()
page.setDefaultTimeout(7000)
page.on('pageerror', error => errors.push(error.message))
let releasePreviewBundle
const previewBundleGate = new Promise(resolve => { releasePreviewBundle = resolve })
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url())
  if (url.protocol === 'blob:') { await route.continue(); return }
  if (url.origin !== 'http://comfyui.test') { await route.abort(); return }
  if (!url.pathname.startsWith('/api/')) {
    if (url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return }
    const file = path.resolve(dist, '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) { await route.abort(); return }
    if (/\/ImageLightbox-[^/]+\.js$/.test(url.pathname)) await previewBundleGate
    try { await route.fulfill({ path: file }) } catch { await route.fulfill({ status: 404, body: '' }) }
    return
  }
  requests.push({ path: url.pathname, method: request.method(), token: request.headers()['x-minis-token'], body: request.postData() })
  if (url.pathname === '/api/auth/me') { await route.fulfill({ json: { id: 'fixture', username: 'fixture', nickname: '验收用户', role: 'user' } }); return }
  if (url.pathname.endsWith('/events')) { await route.fulfill({ contentType: 'text/event-stream', body: '' }); return }
  if (url.pathname === '/api/settings') { await route.fulfill({ json: { models: [], defaultModelId: 'env', embedding: { mode: 'llm', model: '' }, rerank: { mode: 'off', model: '' } } }); return }
  if (url.pathname === '/api/settings/tts') { await route.fulfill({ json: { endpoints: [], profiles: [], defaultProfileId: null } }); return }
  if (url.pathname === '/api/settings/comfyui/endpoints/test') { await route.fulfill({ json: { ok: true, elapsedMs: 12, detail: 'ComfyUI 连接成功', error: null } }); return }
  if (url.pathname === '/api/settings/comfyui/default') { view.defaultProfileId = request.postDataJSON().profileId; await route.fulfill({ json: view }); return }
  if (url.pathname === '/api/settings/comfyui/endpoints' && request.method() === 'POST') {
    view.endpoints.push({ ...request.postDataJSON(), id: 'gpu' }); await route.fulfill({ json: view }); return
  }
  if (url.pathname === '/api/settings/comfyui/profiles' && request.method() === 'POST') {
    const profile = { ...request.postDataJSON(), id: 'profile-' + (view.profiles.length + 1) }
    view.profiles.push(profile); view.defaultProfileId ||= profile.id; await route.fulfill({ json: view }); return
  }
  if (url.pathname.startsWith('/api/settings/comfyui/profiles/') && request.method() === 'PUT') {
    const id = url.pathname.split('/').at(-1)
    view.profiles[view.profiles.findIndex(p => p.id === id)] = { ...request.postDataJSON(), id }
    await route.fulfill({ json: view }); return
  }
  if (url.pathname === '/api/settings/comfyui') { await route.fulfill({ json: view }); return }
  if (url.pathname === '/api/comfyui/generate') {
    await route.fulfill({ json: { generationId: 'generation', profileId: 'profile-1', promptId: 'remote-task', images: [{ id: imageId, url: '/api/comfyui/images/' + imageId }] } }); return
  }
  if (url.pathname === '/api/comfyui/images/' + imageId) {
    assert.equal(request.headers()['x-minis-token'], 'fixture-desktop-token')
    await route.fulfill({ contentType: 'image/png', body: imageBytes }); return
  }
  await route.fulfill({ json: [] })
})

async function noOverflow(label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)
  assert.equal(overflow, false, label)
}
async function formFonts() {
  const small = await page.locator('.comfyui-form').evaluate(root => [...root.querySelectorAll('*')]
    .filter(el => [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim()) && parseFloat(getComputedStyle(el).fontSize) < 12)
    .map(el => el.tagName + ':' + el.textContent.slice(0, 30)))
  assert.deepEqual(small, [])
}

try {
  await page.goto('http://comfyui.test/#/settings')
  imageBytes = Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement('canvas')
    canvas.width = 1600; canvas.height = 1000
    const ctx = canvas.getContext('2d')
    const gradient = ctx.createLinearGradient(0, 0, 1600, 1000)
    gradient.addColorStop(0, '#224b78'); gradient.addColorStop(1, '#dcb88b')
    ctx.fillStyle = gradient; ctx.fillRect(0, 0, 1600, 1000)
    ctx.fillStyle = '#fff'; ctx.font = '60px sans-serif'; ctx.textAlign = 'center'
    ctx.fillText('ComfyUI Image Preview', 800, 480)
    ctx.font = '30px sans-serif'; ctx.fillText('1600 × 1000 · Zoom and drag', 800, 550)
    return canvas.toDataURL('image/png').split(',')[1]
  }), 'base64')
  const panel = page.locator('#settings-comfyui')
  await panel.getByRole('button', { name: '添加 ComfyUI 端点' }).click()
  let dialog = page.getByRole('dialog', { name: '添加 ComfyUI 端点' })
  await dialog.getByLabel('端点名称', { exact: true }).fill('远程 GPU')
  await dialog.getByLabel(/^服务根地址/).fill('https://gpu.example.com/comfy')
  await dialog.getByLabel(/^API Key（可选）/).fill('gateway-key')
  await dialog.getByRole('button', { name: '测试连接', exact: true }).click()
  await page.getByText('ComfyUI 连接成功（12ms）', { exact: true }).waitFor()
  await dialog.getByRole('button', { name: '保存端点', exact: true }).click()
  await dialog.waitFor({ state: 'detached' })
  assert.equal(view.endpoints.length, 1)
  checks.push('endpoint create, optional gateway authentication, connectivity test')

  await panel.getByRole('button', { name: '添加工作流档案' }).click()
  dialog = page.getByRole('dialog', { name: '添加工作流档案' })
  await dialog.getByLabel('档案名称', { exact: true }).fill('FLUX 插画')
  await dialog.getByLabel(/^用途说明（可选）/).fill('适合柔和插画和中文提示词')
  await dialog.locator('input[type=file]').setInputFiles({ name: 'workflow-api.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(workflow)) })
  await dialog.getByLabel(/^提示词节点 ID/).fill('6')
  await dialog.getByLabel(/^随机种子节点 ID（可选）/).fill('3')
  await dialog.getByLabel(/^图片输出节点 ID（可选）/).fill('9')
  await formFonts()
  await page.screenshot({ path: path.join(output, 'workflow-desktop.png') })
  await dialog.getByRole('button', { name: '保存档案', exact: true }).click()
  await dialog.waitFor({ state: 'detached' })
  assert.deepEqual(view.profiles[0].workflow, workflow)
  assert.equal(view.profiles[0].promptNodeId, '6')
  assert.equal(await panel.getByRole('button', { name: '删除', exact: true }).first().isDisabled(), true)
  checks.push('API workflow import, field mapping, reused endpoint deletion guard')

  await panel.getByRole('button', { name: '试生成', exact: true }).click()
  dialog = page.getByRole('dialog', { name: '试生成 · FLUX 插画' })
  await dialog.getByLabel('提示词', { exact: true }).fill('雪地里的一只狐狸')
  await dialog.getByRole('button', { name: '生成图片', exact: true }).click()
  await dialog.getByRole('img', { name: '试生成图片' }).waitFor()
  assert.equal(await dialog.getByRole('img').evaluate(img => img.complete && img.naturalWidth > 0), true)
  assert.ok((await dialog.getByRole('img').getAttribute('src')).startsWith('blob:'))
  assert.ok(requests.some(r => r.path === '/api/comfyui/images/' + imageId && r.token === 'fixture-desktop-token'))
  const imageRequests = requests.filter(r => r.path === '/api/comfyui/images/' + imageId).length
  const originalPageCount = context.pages().length
  const previewButton = dialog.getByRole('button', { name: '预览试生成图片', exact: true })
  const thumbnailSource = await dialog.getByRole('img').getAttribute('src')
  const thumbnailBox = await previewButton.boundingBox()
  await previewButton.click()
  const previewLoading = dialog.getByRole('status', { name: '正在加载图片预览', exact: true })
  await previewLoading.waitFor()
  assert.equal(await previewLoading.innerText(), '')
  assert.deepEqual(await previewButton.boundingBox(), thumbnailBox)
  const loadingBox = await previewLoading.boundingBox()
  for (const key of ['x', 'y', 'width', 'height']) assert.ok(Math.abs(loadingBox[key] - thumbnailBox[key]) <= 1, 'loading overlay stays on the image: ' + key)
  await dialog.screenshot({ path: path.join(output, 'image-preview-loading.png') })
  releasePreviewBundle()
  const viewer = page.getByRole('dialog', { name: '图片预览', exact: true })
  await viewer.waitFor()
  await previewLoading.waitFor({ state: 'detached' })
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.image-lightbox')).opacity === '1')
  assert.equal(context.pages().length, originalPageCount)
  assert.equal(await viewer.getByRole('img').getAttribute('src'), thumbnailSource)
  await page.screenshot({ path: path.join(output, 'image-preview-desktop.png') })
  await viewer.getByRole('button', { name: '放大图片', exact: true }).click()
  await page.waitForFunction(() => Number(document.querySelector('.yarl__slide_wrapper')?.style.transform.match(/scale\(([^)]+)\)/)?.[1]) > 1)
  const zoomBeforeWheel = await viewer.locator('.yarl__slide_wrapper').evaluate(el => Number(el.style.transform.match(/scale\(([^)]+)\)/)?.[1]))
  await viewer.getByRole('img').hover()
  await page.mouse.wheel(0, -180)
  await page.waitForFunction(previous => Number(document.querySelector('.yarl__slide_wrapper')?.style.transform.match(/scale\(([^)]+)\)/)?.[1]) > previous, zoomBeforeWheel)
  const beforeDrag = await viewer.locator('.yarl__slide_wrapper').getAttribute('style')
  await page.mouse.move(720, 500); await page.mouse.down(); await page.mouse.move(830, 560, { steps: 8 }); await page.mouse.up()
  await page.waitForFunction(previous => document.querySelector('.yarl__slide_wrapper')?.getAttribute('style') !== previous, beforeDrag)
  await page.screenshot({ path: path.join(output, 'image-preview-desktop-zoom.png') })
  await page.keyboard.press('Escape')
  await viewer.waitFor({ state: 'detached' })
  await dialog.waitFor()
  assert.equal(await previewButton.evaluate(el => document.activeElement === el), true)
  await previewButton.press('Enter')
  await viewer.getByRole('button', { name: '关闭图片预览', exact: true }).click()
  await viewer.waitFor({ state: 'detached' })
  await dialog.waitFor()
  await previewButton.click()
  await viewer.waitFor()
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.image-lightbox')).opacity === '1')
  await viewer.locator('.yarl__slide').click({ position: { x: 4, y: 80 } })
  await viewer.waitFor({ state: 'detached' })
  await dialog.waitFor()
  assert.equal(requests.filter(r => r.path === '/api/comfyui/images/' + imageId).length, imageRequests)
  assert.equal(context.pages().length, originalPageCount)
  await dialog.getByRole('button', { name: '关闭', exact: true }).last().click()
  checks.push('generation request and authenticated image fetch, blob display')
  checks.push('in-app preview without new tabs or repeated image downloads, zoom/scroll/drag, Escape, keyboard reopen and focus restoration')
  checks.push('slow preview bundle shows an animated overlay on the image without visible text or layout shift')

  for (const theme of ['old', 'endfield', 'bluearchive', 'astrolabe']) {
    await page.evaluate(themeId => {
      const state = JSON.parse(localStorage.getItem('minis.theme.v4'))
      state.themeId = themeId; localStorage.setItem('minis.theme.v4', JSON.stringify(state))
    }, theme)
    await page.reload()
    await panel.getByRole('button', { name: '添加工作流档案' }).waitFor()
    await panel.scrollIntoViewIfNeeded()
    await noOverflow(theme + ' desktop')
    await panel.screenshot({ path: path.join(output, theme + '-desktop.png') })
    await panel.getByRole('button', { name: '编辑', exact: true }).last().click()
    await formFonts()
    await page.setViewportSize({ width: 390, height: 844 })
    await noOverflow(theme + ' mobile form')
    await formFonts()
    await page.getByRole('button', { name: '保存档案', exact: true }).scrollIntoViewIfNeeded()
    const box = await page.getByRole('button', { name: '保存档案', exact: true }).boundingBox()
    assert.ok(box.y >= 0 && box.y + box.height <= 844)
    await page.screenshot({ path: path.join(output, theme + '-mobile-form.png') })
    await page.getByRole('button', { name: '取消', exact: true }).click()
    await panel.scrollIntoViewIfNeeded()
    await noOverflow(theme + ' mobile')
    await panel.getByRole('button', { name: '试生成', exact: true }).click()
    dialog = page.getByRole('dialog', { name: '试生成 · FLUX 插画' })
    await dialog.getByLabel('提示词', { exact: true }).fill('mobile image preview')
    await dialog.getByRole('button', { name: '生成图片', exact: true }).click()
    await dialog.getByRole('button', { name: '预览试生成图片', exact: true }).click()
    await viewer.waitFor()
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.image-lightbox')).opacity === '1')
    await noOverflow(theme + ' mobile image preview')
    const closeButton = viewer.getByRole('button', { name: '关闭图片预览', exact: true })
    const closeBox = await closeButton.boundingBox()
    assert.ok(closeBox.width >= 44 && closeBox.height >= 44 && closeBox.x + closeBox.width <= 390)
    await page.screenshot({ path: path.join(output, theme + '-mobile-preview.png') })
    await viewer.getByRole('button', { name: '放大图片', exact: true }).click()
    await closeButton.click()
    await viewer.waitFor({ state: 'detached' })
    await dialog.getByRole('button', { name: '关闭', exact: true }).last().click()
    await page.setViewportSize({ width: 1440, height: 1000 })
  }
  assert.deepEqual(errors, [])
  checks.push('all four themes, desktop/mobile overflow, field typography, reachable form actions')
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify({ checks, errors, requests: requests.length }, null, 2))
  console.log(JSON.stringify({ checks, errors, output }, null, 2))
} finally { releasePreviewBundle(); await browser.close() }
