import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const output = path.resolve(frontend, '../backend/target/raw-context-browser-check')
await fs.mkdir(output, { recursive: true })
const marker = '系统正文唯一标记😀'
const system = `前言\n<persona mode="a>b">${marker}\n<rules>嵌套规则正文</rules></persona>\n\n   \n<minis-memory>\n  记忆正文\n\n</minis-memory>\n\n<minis-web-search provider="deepseek">\n  <results>\n    <result id="web:1">\n      <title>网页标题</title>\n\n      <summary>网页摘要</summary>\n    </result>\n  </results>\n</minis-web-search>\n尾声`
const plain = '无标签长字符串唯一标记\n' + '长文本验证。'.repeat(45)
const body = { model: 'deepseek-chat', messages: [{ role: 'system', content: system }, { role: 'system', content: plain }, { role: 'user', content: '<short>短内容</short>' }, { role: 'assistant', content: null, tool_calls: [{ id: 'c1', type: 'function', function: { name: 'test', arguments: '{}' } }] }], tools: [{ type: 'function', function: { name: 'search_web', description: '搜索网页', parameters: { type: 'object', properties: { query: { type: 'string' } } } } }], temperature: 0.5 }
const turns = [{ from: 2, to: 4, source: 'USER_INPUT', replyMessageId: 7 }]
const fixture = `import React from 'react'; import {createRoot} from 'react-dom/client';
import {RawContextView, JsonViewer} from '/src/components/JsonViewer.tsx';
import FloatWindow from '/src/components/FloatWindow.tsx';
import {initTheme} from '/src/theme/store'; import '/src/index.css'; import '/src/foundation.css';
initTheme(); window.copied = null; Object.defineProperty(navigator, 'clipboard', {value: {writeText: async text => {window.copied = text}}});
createRoot(document.getElementById('root')).render(<><FloatWindow title="原始上下文" width={960} contentClassName="raw-context-window-body" onClose={()=>{}}><RawContextView sessionId="test" ctx={${JSON.stringify({ body, turns })}}/></FloatWindow><section id="scalars"><JsonViewer value={{empty: {}, array: [], number: 1, boolean: true, nil: null}} openDepth={10}/></section></>);`
const bundle = await build({
  root: frontend,
  build: { write: false, cssCodeSplit: false, rollupOptions: {
    input: path.join(frontend, 'src/__raw_context_fixture.tsx'),
    output: { entryFileNames: 'fixture.js' },
  } },
  plugins: [{ name: 'raw-context-fixture',
    resolveId(id) { if (id.endsWith('__raw_context_fixture.tsx')) return path.join(frontend, 'src/__raw_context_fixture.tsx').replaceAll('\\', '/') },
    load(id) { if (id.endsWith('__raw_context_fixture.tsx')) return fixture },
  }],
})
let browser
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  // 在内存中提供实际组件的构建产物，无需后端或本地网络。
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname.slice(1)
    if (pathname === 'api/sessions/test/turns/7/llm-calls') return route.fulfill({ json: { replyMessageId: 7, calls: [{ round: 0, request: JSON.stringify({ model: 'trace-model', messages: [{ role: 'system', content: '<trace>历史正文</trace>' }] }), response: JSON.stringify({ choices: [{ message: { content: '历史响应' } }] }), withTools: true }] } })
    if (!pathname) {
      const css = bundle.output.filter(item => item.fileName.endsWith('.css')).map(item => `<link rel="stylesheet" href="/${item.fileName}">`).join('')
      return route.fulfill({ contentType: 'text/html', body: `<html>${css}<div id="root"></div><script type="module" src="/fixture.js"></script></html>` })
    }
    const asset = bundle.output.find(item => item.fileName === pathname)
    if (!asset) return route.fulfill({ status: 404, body: '' })
    await route.fulfill({ contentType: pathname.endsWith('.css') ? 'text/css' : 'text/javascript', body: asset.type === 'chunk' ? asset.code : Buffer.from(asset.source) })
  })
  await page.goto('http://raw-context.test/')
  await page.getByRole('button', { name: '展开全部', exact: true }).click()
  const root = page.locator('.raw-context-view')
  assert.equal((await root.innerText()).split(marker).length - 1, 1, 'system text must render exactly once')
  assert.equal((await root.innerText()).split('无标签长字符串唯一标记').length - 1, 1)
  assert.doesNotMatch(await root.innerText(), /\n\s*0: "[<前无]"/, 'strings must never render indexed characters')
  const tag = name => page.getByRole('button', { name, exact: true })
  const tagGaps = await root.locator('.jv-tagged-text').evaluateAll(groups => groups.flatMap(group => {
    const children = [...group.children]
    return children.slice(1).flatMap((child, i) => children[i].classList.contains('jv-tag') && child.classList.contains('jv-tag')
      ? [child.getBoundingClientRect().top - children[i].getBoundingClientRect().bottom] : [])
  }))
  assert.ok(tagGaps.length > 0)
  assert.ok(tagGaps.every(gap => gap <= 8), `XML formatting whitespace must not create blank rows: ${tagGaps}`)
  assert.equal(await root.locator('.jv-text-run').filter({ hasText: '前言' }).innerText(), '前言')
  assert.equal((await root.locator('.jv-text-run').filter({ hasText: '记忆正文' }).innerText()).trimEnd(), '  记忆正文')
  assert.match(await root.getByRole('region', { name: '工具定义' }).innerText(), /search_web/)
  await tag('收起 rules 标签').click()
  assert.ok(!(await root.innerText()).includes('嵌套规则正文'))
  assert.ok((await tag('收起 persona 标签').getAttribute('aria-expanded')) === 'true', 'child toggle must not collapse parent')
  await tag('展开 rules 标签').focus(); await page.keyboard.press('Enter')
  assert.ok((await root.innerText()).includes('嵌套规则正文'))
  await tag('收起 persona 标签').focus(); await page.keyboard.press('Space')
  assert.ok(!(await root.innerText()).includes(marker))
  await tag('展开 persona 标签').click()
  assert.equal((await root.innerText()).split(marker).length - 1, 1)
  await page.getByRole('button', { name: '复制 JSON', exact: true }).click()
  assert.deepEqual(JSON.parse(await page.evaluate(() => window.copied)), body)
  assert.match(await page.locator('#scalars').innerText(), /empty: \{\}/)
  assert.match(await page.locator('#scalars').innerText(), /array: \[\]/)
  assert.ok((await root.innerText()).includes('短内容'), 'short tags also expand')
  await page.getByRole('button', { name: '请求 / 响应', exact: true }).click()
  await page.getByRole('button', { name: /^第 1 轮/ }).click()
  await page.getByRole('button', { name: '请求体', exact: true }).click()
  await page.getByRole('button', { name: '响应体', exact: true }).click()
  assert.ok((await root.innerText()).includes('trace-model'))
  assert.ok((await root.innerText()).includes('choices'))
  await page.getByRole('button', { name: '折叠全部', exact: true }).click()
  assert.equal(await tag('收起 persona 标签').count(), 0)
  await page.getByRole('button', { name: /^system / }).first().click()
  await page.getByRole('button', { name: /^content:/ }).click()
  await tag('展开 persona 标签').click()
  assert.equal((await root.innerText()).split(marker).length - 1, 1)
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.screenshot({ path: path.join(output, `tags-${width}.png`), fullPage: true })
    assert.ok(await root.evaluate(el => el.scrollWidth <= el.clientWidth + 1), 'no horizontal overflow')
  }
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.getByRole('button', { name: '展开全部', exact: true }).click()
  const toolbar = root.locator('.raw-context-toolbar'), scroll = root.locator('.raw-context-scroll')
  const beforeScroll = await toolbar.boundingBox()
  await scroll.evaluate(el => { el.scrollTop = el.scrollHeight })
  assert.deepEqual(await toolbar.boundingBox(), beforeScroll, 'toolbar remains visible while reading long content')
  assert.ok(await scroll.evaluate(el => el.scrollHeight > el.clientHeight), 'long context scrolls inside window')
  for (const themeId of ['old', 'endfield', 'astrolabe', 'bluearchive']) {
    for (const schemeMode of ['light', 'dark']) {
    await page.evaluate(({ themeId, schemeMode }) => {
      const config = JSON.parse(localStorage.getItem('minis.theme.v4'))
      config.themeId = themeId; config.schemeMode = schemeMode
      localStorage.setItem('minis.theme.v4', JSON.stringify(config))
    }, { themeId, schemeMode })
    await page.reload()
    await page.getByRole('button', { name: '展开全部', exact: true }).click()
    assert.equal(await root.locator('.jv-string-body').first().evaluate(el => getComputedStyle(el).fontSize), '16px')
    await page.screenshot({ path: path.join(output, `window-${themeId}-${schemeMode}.png`) })
    }
  }
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  await page.setViewportSize({ width: 390, height: 900 })
  assert.ok(await root.evaluate(el => el.scrollWidth <= el.clientWidth + 1), '200% text remains within narrow window')
  assert.ok(await page.getByRole('button', { name: '复制 JSON', exact: true }).isVisible())
  await page.screenshot({ path: path.join(output, 'window-mobile-200pct.png') })
  assert.deepEqual(errors, [])
  console.log('Raw context: XML spacing, nested/keyboard folding, exact JSON copy, call traces, fixed toolbar, four themes, narrow layout and 200% text passed.')
} finally {
  await browser?.close()
}
