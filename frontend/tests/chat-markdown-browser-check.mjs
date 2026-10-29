import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const output = path.resolve(frontend, '../backend/target/chat-markdown-browser-check')
await fs.mkdir(output, { recursive: true })
const content = [
  '正文保持 16px。<small>补充 **重点** <small>嵌套说明</small></small> 后续正文。',
  '<small>独立补充说明</small>',
  '换行前<br>换行后，H<sub>2</sub>O，x<sup>2</sup>。',
  '`<small>行内代码</small>`',
  '```html\n<small>代码块示例</small>\n```',
  '| 名称 | 说明 |\n| --- | --- |\n| small | <small>表格说明</small> |',
  '- [x] 已完成\n- [ ] 待办',
  '[网页链接](https://example.com) 与 <a href="javascript:alert(1)">危险链接</a>',
  '<small style="font-size:1px;color:transparent" class="hidden" onclick="window.pwned=1">清洗后的说明</small>',
  '<script>window.pwned=1</script><iframe src="https://evil.test"></iframe><svg onload="window.pwned=1"></svg>',
  '<img src="javascript:alert(1)" onerror="window.pwned=1" alt="危险图片">',
  '![生成图片](/api/comfyui/images/12345678-1234-1234-1234-123456789abc)',
  '<small>未闭合但保留正文',
].join('\n\n')
const userText = '<small>用户原文</small>'
const fixture = `import React from 'react'; import {createRoot} from 'react-dom/client';
import ChatMarkdown from '/src/components/ChatMarkdown';
import {initTheme} from '/src/theme/store'; import '/src/index.css';
initTheme(); createRoot(document.getElementById('root')).render(<main className="p-4 font-sans text-md leading-relaxed text-fg bg-bg">
<section id="answer"><ChatMarkdown content={${JSON.stringify(content)}}/></section>
<section id="user"><ChatMarkdown content={${JSON.stringify(userText)}} onBubble/></section>
</main>);`
const bundle = await build({
  root: frontend,
  build: { write: false, cssCodeSplit: false, rollupOptions: {
    input: path.join(frontend, 'src/__chat_markdown_fixture.tsx'),
    output: { entryFileNames: 'fixture.js' },
  } },
  plugins: [{ name: 'chat-markdown-fixture',
    resolveId(id) { if (id.endsWith('__chat_markdown_fixture.tsx')) return path.join(frontend, 'src/__chat_markdown_fixture.tsx').replaceAll('\\', '/') },
    load(id) { if (id.endsWith('__chat_markdown_fixture.tsx')) return fixture },
  }],
})
let browser
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } })
  const errors = [], external = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== 'http://chat-markdown.test') { external.push(url.href); return route.abort() }
    const pathname = url.pathname.slice(1)
    if (pathname.startsWith('api/comfyui/images/')) {
      return route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1d8AAAAASUVORK5CYII=', 'base64') })
    }
    if (!pathname) {
      const css = bundle.output.filter(item => item.fileName.endsWith('.css')).map(item => `<link rel="stylesheet" href="/${item.fileName}">`).join('')
      return route.fulfill({ contentType: 'text/html', body: `<html><meta name="viewport" content="width=device-width,initial-scale=1">${css}<div id="root"></div><script type="module" src="/fixture.js"></script></html>` })
    }
    const asset = bundle.output.find(item => item.fileName === pathname)
    if (!asset) return route.fulfill({ status: 404, body: '' })
    return route.fulfill({ contentType: pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: asset.type === 'chunk' ? asset.code : Buffer.from(asset.source) })
  })
  await page.goto('http://chat-markdown.test')
  const answer = page.locator('#answer')
  await answer.locator('small').first().waitFor()
  assert.equal(await answer.locator('small strong').innerText(), '重点')
  assert.equal(await answer.locator('small small').innerText(), '嵌套说明')
  assert.equal(await answer.locator('sub').innerText(), '2')
  assert.equal(await answer.locator('sup').innerText(), '2')
  assert.equal(await answer.locator('br').count(), 1)
  assert.equal(await answer.locator('code').first().innerText(), '<small>行内代码</small>')
  assert.equal(await answer.locator('pre code').innerText(), '<small>代码块示例</small>\n')
  assert.equal(await answer.locator('td small').innerText(), '表格说明')
  assert.equal(await answer.locator('input[type="checkbox"]:disabled').count(), 2)
  assert.equal(await answer.getByRole('link', { name: '网页链接' }).getAttribute('target'), '_blank')
  assert.equal(await answer.locator('a').filter({ hasText: '危险链接' }).getAttribute('href'), null)
  assert.equal(await answer.locator('script,iframe,svg,style,[onclick],[onerror],[style],.hidden').count(), 0)
  assert.equal(await page.evaluate(() => window.pwned), undefined)
  assert.deepEqual(external, [])
  assert.equal(await page.locator('#user').innerText(), userText)
  assert.equal(await page.locator('#user small').count(), 0)
  await answer.getByRole('button', { name: '预览生成图片' }).waitFor()
  await answer.getByRole('button', { name: '预览生成图片' }).click()
  await page.getByRole('button', { name: '关闭图片预览' }).click()
  await page.getByRole('button', { name: '关闭图片预览' }).waitFor({ state: 'hidden' })
  assert.ok((await answer.innerText()).includes('未闭合但保留正文'))
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    assert.equal(await answer.locator('p').first().evaluate(el => getComputedStyle(el).fontSize), '16px')
    const expected = width === 1280 ? '14px' : '16px'
    for (const small of await answer.locator('small').all()) {
      assert.equal(await small.evaluate(el => getComputedStyle(el).fontSize), expected)
    }
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    await page.screenshot({ path: path.join(output, `answer-${width}.png`), fullPage: true })
  }
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  assert.equal(await answer.locator('small').first().evaluate(el => getComputedStyle(el).fontSize), '32px')
  assert.ok((await answer.innerText()).includes('后续正文'))
  assert.deepEqual(errors, [])
  console.log('Chat Markdown: safe HTML, nested small, code literals, GFM, links, authenticated image preview, mobile and 200% text passed.')
} finally {
  await browser?.close()
}
