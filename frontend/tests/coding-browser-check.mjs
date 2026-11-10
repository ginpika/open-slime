import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

// Uses the production App, serves assets and APIs through interception, and never connects to a backend.
const frontend = fileURLToPath(new URL('../', import.meta.url))
const dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/coding-browser-check')
await fs.mkdir(output, { recursive: true })
const runtime = process.env.MINIS_PLAYWRIGHT_DIR
const require = createRequire(runtime ? path.join(runtime, 'package.json') : path.join(frontend, 'package.json'))
const { chromium } = require('playwright')
const html = await fs.readFile(path.join(dist, 'index.html'), 'utf8')
const project = { id: 'p1', name: 'personal-assistant-with-a-long-project-name', workspaceId: 'w1', workspaceName: '开发工作空间', kind: 'docker', directory: 'projects/assistant', validationCommand: 'npm run build', enabled: true, createdAt: 0 }
let listedProjects = [project], projectsFail = false, removeRecentFails = false
const workspace = { id: 'w1', name: '开发工作空间', kind: 'docker', workdir: '/workspace', enabled: true }
const codingAgent = { id: 'builtin-coding', name: '编码 Agent', modelId: 'm1' }
let codingPeer = { ...codingAgent, builtin: true, executionKind: 'CODING', terminals: [], sort: 1, note: '工作空间编码与智能体共用任务', avatar: null, role: '编码', roleColor: '#38bdf8' }
const caps = { enabled: true, localExecutionAllowed: false, dockerImage: 'node:22', maxRunMinutes: 30, isolation: 'fixture', agent: codingAgent }
const models = [{ id: 'm1', name: '开发模型', baseUrl: 'http://fixture.invalid/v1', apiKey: 'fixture-key', model: 'coding-model', contextWindow: 262144, builtin: false, maxOutputTokens: null }, { id: 'm2', name: '推理模型', baseUrl: 'http://fixture.invalid/v1', apiKey: 'fixture-key', model: 'reasoning-model', contextWindow: 262144, builtin: false, maxOutputTokens: null }]
const settingsView = () => ({ models, defaultModelId: 'm1', embedding: { mode: 'llm', llmModelId: 'm1', baseUrl: null, apiKey: null, model: 'embedding' }, rerank: { mode: 'off', llmModelId: null, baseUrl: null, apiKey: null, model: '' } })
const makeRun = (id, extra = {}) => ({ id, taskId: 1, repositoryId: 'p1', prompt: '为项目实现任务列表', mode: 'EDIT', runner: 'WORKSPACE', status: 'REVIEWABLE', attempt: 1, round: 2, answer: '已完成任务列表修改。尚未运行项目验证。', error: null, validationStatus: 'NOT_RUN', artifactHash: 'reviewed-hash', modelId: 'm1', createdAt: 0, updatedAt: 1, ...extra })
const checks = []
let phase = 'REVIEWABLE', secondPhase = 'CANCELLING', recovering = false, started = false, diffFails = false, settingsFails = false, capsFail = false, diffHash = 'reviewed-hash', accepted, createBody, modelBody
let traceEvents = [], workflowWorking = false
let composition = false, rootMode = 'EDIT', rootAttempt = 1, sessionLatest = 'r1', followupRun = null, roleBody, resumeBody, childFixtures = []
const sessionFixture = () => ({ id: 's1', repositoryId: 'p1', title: '为项目实现任务列表', lastRunId: sessionLatest, createdAt: 0, updatedAt: followupRun ? 4 : 1 })
const roleFixtures = ['explore', 'planner', 'reviewer'].map((id, i) => ({ id, name: ['Explore · 探索', 'Planner · 规划', 'Reviewer · 审阅'][i], description: ['定位相关代码', '规划实施方案', '独立审阅变更'][i], modelId: null, instructions: '只读完成本角色目标，返回文件与行号证据。', version: 1, tools: ['list_files', 'read_file', 'search_text', 'git_status', 'git_diff'] }))
const traceEvent = (id, type, payload) => ({ id, runId: 'r1', type, payload: JSON.stringify(payload), createdAt: id })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
page.setDefaultTimeout(7000)
const errors = []
page.on('pageerror', error => errors.push(error.message))
await context.addInitScript(() => { if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'dark', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })) })
await context.route('**/*', async route => {
  const url = new URL(route.request().url())
  if (url.origin !== 'http://coding.test') { await route.abort(); return }
  if (!url.pathname.startsWith('/api/')) {
    if (url.pathname === '/') { await route.fulfill({ contentType: 'text/html', body: html }); return }
    const file = path.resolve(dist, `.${url.pathname}`)
    if (!file.startsWith(`${dist}${path.sep}`)) { await route.abort(); return }
    try { await route.fulfill({ path: file }); } catch { await route.fulfill({ status: 404, body: '' }) }
    return
  }
  let value
  const method = route.request().method()
  if (url.pathname === '/api/auth/me') value = { id: 'fixture-user', username: 'fixture', nickname: '验收用户', role: 'user' }
  else if (url.pathname === '/api/terminal') value = { targets: [workspace], commands: [] }
  else if (url.pathname === '/api/agents/peers') value = [{ id: 'builtin', name: '助手', builtin: true, executionKind: 'CHAT', terminals: [], modelId: null, sort: 0, note: null, avatar: null }, codingPeer]
  else if (url.pathname === '/api/agents/workflow') value = { sessionId: null, title: null, topicId: null, topicStatus: null, floorPeer: null, discussing: false, advancing: false, agents: [{ id: codingPeer.id, name: codingPeer.name, communication: null, execution: workflowWorking ? 'RUNNING' : 'READY', working: workflowWorking, pending: 0, doing: workflowWorking ? 1 : 0, done: 0, role: '编码', roleColor: '#38bdf8' }], todos: [] }
  else if (url.pathname === '/api/agents/peers/builtin-coding' && method === 'PUT') {
    codingPeer = { ...codingPeer, ...route.request().postDataJSON() }
    Object.assign(codingAgent, { name: codingPeer.name, modelId: codingPeer.modelId })
    value = codingPeer
  }
  else if (url.pathname === '/api/settings') {
    if (settingsFails) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '模型设置暂时不可用' }) }); return }
    value = settingsView()
  }
  else if (url.pathname === '/api/settings/models/context-window') value = { contextWindow: 262144 }
  else if (url.pathname === '/api/settings/tts') value = { endpoints: [], profiles: [], defaultProfileId: null }
  else if (url.pathname === '/api/settings/models/m1' && method === 'PUT') {
    modelBody = route.request().postDataJSON(); Object.assign(models[0], modelBody); value = settingsView()
  }
  else if (url.pathname === '/api/coding/capabilities') {
    if (capsFail) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '模块状态暂时不可用' }) }); return }
    value = caps
  }
  else if (url.pathname === '/api/coding/workspaces') value = [workspace]
  else if (url.pathname === '/api/coding/roles') value = composition ? roleFixtures : []
  else if (url.pathname.startsWith('/api/coding/roles/') && method === 'PUT') { roleBody = route.request().postDataJSON(); const role = roleFixtures.find(item => item.id === url.pathname.split('/').pop()); Object.assign(role, roleBody, { version: role.version + 1 }); value = role }
  else if (url.pathname === '/api/coding/sessions') value = composition ? [sessionFixture()] : []
  else if (url.pathname.endsWith('/session')) value = composition ? sessionFixture() : []
  else if (url.pathname === '/api/coding/sessions/s1/runs') value = [makeRun('r1', { sessionId: 's1', mode: rootMode, attempt: rootAttempt, status: phase }), ...(followupRun ? [followupRun] : [])]
  else if (url.pathname.endsWith('/executions')) value = childFixtures
  else if (url.pathname.endsWith('/executions/child-1/stop')) { childFixtures[0].status = 'CANCELLED'; value = { stopped: true } }
  else if (url.pathname.endsWith('/executions/child-1/resume')) { childFixtures[0].status = 'RUNNING'; childFixtures[0].attempt++; value = childFixtures[0] }
  else if (url.pathname === '/api/coding/runs/r1/resume') { resumeBody = route.request().postDataJSON(); rootMode = resumeBody.mode || rootMode; rootAttempt++; phase = 'RUNNING'; value = makeRun('r1', { status: phase, mode: rootMode, attempt: rootAttempt, sessionId: composition ? 's1' : null }) }
  else if (url.pathname === '/api/coding/projects/p1/recent' && method === 'DELETE') {
    if (removeRecentFails) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '暂时无法移除' }) }); return }
    listedProjects = listedProjects.map(item => item.id === project.id ? { ...item, recentHidden: true } : item)
    value = { ok: true }
  }
  else if (url.pathname === '/api/coding/projects') {
    if (method === 'POST') {
      createBody = route.request().postDataJSON()
      if (createBody.create) value = { ...project, id: 'p2', name: createBody.name, directory: createBody.name }
      else { value = { ...project, recentHidden: false }; listedProjects = [value] }
    }
    else if (projectsFail) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '项目列表暂时不可用' }) }); return }
    else value = listedProjects
  } else if (url.pathname === '/api/coding/runs') {
    if (method === 'POST') { started = true; const request = route.request().postDataJSON(); assert.equal(request.runner, 'WORKSPACE'); assert.equal(request.mode, 'EDIT'); if (composition && request.sessionId) { assert.equal(request.sessionId, 's1'); sessionLatest = 'r3'; followupRun = makeRun('r3', { status: 'RUNNING', prompt: request.prompt, mode: request.mode, answer: null, artifactHash: null, sessionId: 's1', createdAt: 3, updatedAt: 4 }); value = followupRun } else value = makeRun('r1', { status: phase, modelId: request.modelId || codingAgent.modelId }) }
    else value = [makeRun('r2', { prompt: '另一个正在停止的分析任务', mode: 'PLAN', status: secondPhase, artifactHash: null, answer: null }), ...(started ? [makeRun('r1', { status: phase, mode: composition ? rootMode : 'EDIT', sessionId: composition ? 's1' : null })] : []), ...(followupRun ? [followupRun] : [])]
  } else if (url.pathname.endsWith('/events/stream') || url.pathname === '/api/events/stream') { await route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' }); return }
  else if (url.pathname.endsWith('/events')) value = url.pathname.includes('/coding/') ? (traceEvents.length ? traceEvents.filter(event => event.id > Number(url.searchParams.get('after') || 0)) : recovering ? [{ id: 1, runId: 'r1', type: 'response.truncated', payload: JSON.stringify({ attempt: 1, round: 0, automatic: true, continuation: 1, discardedToolCalls: 1 }), createdAt: 0 }] : []) : []
  else if (url.pathname.endsWith('/diff')) {
    if (diffFails) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '差异暂时不可用' }) }); return }
    value = { artifactHash: diffHash, changes: [{ path: 'src/App.tsx', kind: 'modified', size: 100, binary: false }], patch: 'diff --git a/src/App.tsx b/src/App.tsx\n@@ -1 +1 @@\n-return null\n+return <TaskList />', truncated: false }
  } else if (url.pathname.endsWith('/accept')) { accepted = route.request().postDataJSON(); phase = 'COMPLETED'; value = makeRun('r1', { status: phase }) }
  else if (url.pathname === '/api/coding/runs/r1') value = makeRun('r1', { status: phase, mode: composition ? rootMode : 'EDIT', attempt: composition ? rootAttempt : 1, sessionId: composition ? 's1' : null, ...(recovering || traceEvents.length ? { answer: null, artifactHash: null } : {}) })
  else if (url.pathname === '/api/coding/runs/r3') value = followupRun
  else if (url.pathname === '/api/coding/runs/r2') value = makeRun('r2', { prompt: '另一个正在停止的分析任务', mode: 'PLAN', status: secondPhase, artifactHash: null, answer: null })
  else if (url.pathname.endsWith('/file')) value = { path: 'src/App.tsx', name: 'App.tsx', size: 100, binary: false, truncated: false, content: 'export function App() {\n  return <TaskList />\n}\n' }
  else if (url.pathname.endsWith('/files')) value = { path: url.searchParams.get('path') || '.', entries: url.pathname.startsWith('/api/workspaces/') ? [] : url.searchParams.get('path') === 'src' ? [{ name: 'App.tsx', dir: false, link: false, size: 100, modified: '' }] : [{ name: 'src', dir: true, link: false, size: 0, modified: '' }] }
  else value = []
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) })
})
async function check(name, work) {
  if (process.env.MINIS_BROWSER_CHECK_ONLY && !name.includes(process.env.MINIS_BROWSER_CHECK_ONLY)) return
  await work(); checks.push(name); console.log(`PASS ${name}`)
}
async function no404() { assert.equal(await page.getByText('404 —— 没有这个页面。', { exact: true }).count(), 0) }
async function chooseOption(label, name) { await page.getByRole('combobox', { name: label, exact: true }).click(); await page.getByRole('listbox', { name: label, exact: true }).getByRole('option', { name, exact: true }).click() }
async function openFile() {
  await page.getByRole('button', { name: 'src', exact: true }).click()
  await page.getByRole('button', { name: 'App.tsx', exact: true }).click()
  await page.locator('.coding-code-text').getByText('export function App()', { exact: false }).waitFor()
}
try {
  await check('full App coding route renders landing without 404', async () => {
    await page.goto('http://coding.test/#/coding')
    await page.getByRole('heading', { name: '从一个项目开始' }).waitFor(); await no404()
    await page.screenshot({ path: path.join(output, 'landing.png') })
  })
  await check('recent project removal handles failures, persists and restores when reopened', async () => {
    await page.goto('http://coding.test/#/coding?start=1')
    const remove = page.getByRole('button', { name: `从最近项目移除 ${project.name}`, exact: true })
    await remove.waitFor()
    await page.evaluate(() => { document.cookie = 'minis.coding.project=p1; Path=/; SameSite=Lax' })
    removeRecentFails = true
    await remove.click()
    await page.getByRole('alert').filter({ hasText: '移除失败：暂时无法移除' }).waitFor()
    assert.equal(await page.locator('.coding-project-row').count(), 1)
    assert.equal(await page.locator('.coding-studio').count(), 0)
    removeRecentFails = false
    await remove.click()
    await page.locator('.coding-recent-empty').waitFor()
    assert.equal(await page.locator('.coding-project-row').count(), 0)
    assert.equal((await page.evaluate(() => document.cookie)).includes('minis.coding.project='), false)
    await page.reload(); await page.locator('.coding-recent-empty').waitFor()
    await page.goto('http://coding.test/#/coding?project=p1'); await page.locator('.coding-studio').waitFor()
    await page.goto('http://coding.test/#/coding?workspace=w1&directory=projects/assistant')
    await page.getByRole('button', { name: '打开此目录 →', exact: true }).click()
    await page.locator('.coding-studio').waitFor()
    await page.goto('http://coding.test/#/coding?start=1'); await remove.waitFor()
    await page.screenshot({ path: path.join(output, 'recent-remove.png') })
    const viewport = page.viewportSize()
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await page.locator('.coding-project-row').evaluate(row => row.scrollWidth <= row.clientWidth), true)
    assert.ok(await remove.evaluate(button => parseFloat(getComputedStyle(button).fontSize) >= 14))
    await page.screenshot({ path: path.join(output, 'recent-remove-mobile.png') })
    await page.setViewportSize(viewport)
  })
  await check('project opening, file preview and tab closing', async () => {
    await page.getByRole('button', { name: /personal-assistant-with-a-long-project-name/ }).click()
    await page.getByRole('heading', { name: '一起完成这个项目' }).waitFor(); await no404()
    await openFile(); await page.getByRole('button', { name: '关闭 src/App.tsx' }).click()
    assert.equal(await page.locator('.coding-file-tab').count(), 0)
  })
  await check('IDE sidebar tabs preserve file state and support keyboard navigation', async () => {
    const files = page.getByRole('tab', { name: '项目文件', exact: true }), sessions = page.getByRole('tab', { name: '编程会话', exact: true })
    await files.focus(); await page.keyboard.press('ArrowRight')
    assert.equal(await sessions.getAttribute('aria-selected'), 'true'); assert.equal(await page.locator('.coding-tree').isVisible(), false)
    await page.getByRole('tabpanel', { name: '编程会话', exact: true }).waitFor()
    await page.keyboard.press('Home'); assert.equal(await files.getAttribute('aria-selected'), 'true')
    await page.locator('.coding-tree').getByRole('button', { name: 'App.tsx', exact: true }).waitFor()
    assert.equal(await page.locator('.coding-task-list').isVisible(), false)
  })
  await check('IDE uses styled dropdowns, readable type, thin scrollbars and centered icons', async () => {
    assert.equal(await page.locator('.coding-studio select').count(), 0)
    await page.getByRole('combobox', { name: '文件查看来源', exact: true }).click()
    const list = page.getByRole('listbox', { name: '文件查看来源', exact: true })
    assert.equal(await list.getByRole('option', { name: '当前任务副本', exact: true }).getAttribute('aria-disabled'), 'true')
    await page.keyboard.press('ArrowDown'); await page.keyboard.press('Enter')
    assert.equal(await page.getByRole('combobox', { name: '文件查看来源', exact: true }).textContent(), '项目目录')
    await page.getByRole('combobox', { name: '编程模型', exact: true }).click()
    await page.getByRole('combobox', { name: '搜索编程模型', exact: true }).fill('推理')
    assert.equal(await page.getByRole('listbox', { name: '编程模型', exact: true }).getByRole('option').count(), 1)
    await page.keyboard.press('Escape')
    const measurements = await page.evaluate(() => {
      const size = selector => parseFloat(getComputedStyle(document.querySelector(selector)).fontSize)
      const icon = document.querySelector('[aria-label="运行设置"] svg').getBoundingClientRect(), button = document.querySelector('[aria-label="运行设置"]').getBoundingClientRect()
      const actionIcon = document.querySelector('.coding-pane-heading .ui-btn svg').getBoundingClientRect(), action = document.querySelector('.coding-pane-heading .ui-btn').getBoundingClientRect()
      return { input: size('.coding-composer textarea'), tree: size('.coding-tree-row'), heading: size('.coding-pane-heading > a'), scrollbar: getComputedStyle(document.querySelector('.coding-editor-content')).scrollbarWidth, dx: Math.abs(icon.x + icon.width / 2 - button.x - button.width / 2), dy: Math.abs(icon.y + icon.height / 2 - button.y - button.height / 2), actionContainsIcon: actionIcon.x >= action.x && actionIcon.right <= action.right, actionDy: Math.abs(actionIcon.y + actionIcon.height / 2 - action.y - action.height / 2) }
    })
    assert.equal(measurements.input, 16); assert.ok(measurements.tree >= 14); assert.ok(measurements.heading >= 18); assert.equal(measurements.scrollbar, 'thin'); assert.ok(measurements.dx <= 1 && measurements.dy <= 1, JSON.stringify(measurements))
    assert.ok(measurements.actionContainsIcon && measurements.actionDy <= 1, JSON.stringify(measurements))
    await page.screenshot({ path: path.join(output, 'ide-polish-desktop.png') })
  })
  await check('quick actions choose correct mode and create a workspace task', async () => {
    await page.getByRole('button', { name: /梳理项目结构与启动方式/ }).click()
    assert.equal(await page.getByRole('combobox', { name: '任务模式' }).textContent(), '分析 / 规划')
    await page.getByRole('button', { name: /为项目实现一个新功能/ }).click()
    assert.equal(await page.getByRole('combobox', { name: '任务模式' }).textContent(), '修改')
    await page.getByRole('button', { name: '开始' }).click()
    await page.getByText('修改已准备好', { exact: true }).waitFor()
  })
  await check('diff failure has a visible error and a working retry', async () => {
    diffFails = true; await page.locator('.coding-editor-tabs').getByRole('button', { name: /^变更/ }).click()
    await page.locator('.coding-diff-error').getByText('差异暂时不可用').waitFor()
    assert.equal(await page.getByText('正在读取变更…', { exact: true }).count(), 0)
    diffFails = false; await page.getByRole('button', { name: '重新读取差异' }).click()
    await page.getByText('1 个文件有变更', { exact: true }).waitFor()
  })
  await check('acceptance traps focus, closes on Escape and preserves reviewed hash', async () => {
    await page.getByRole('button', { name: '审阅并应用' }).click(); await page.getByRole('dialog').waitFor()
    await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => document.activeElement.textContent), '继续审阅')
    await page.keyboard.press('Shift+Tab'); assert.equal(await page.evaluate(() => document.activeElement.textContent), '确认应用变更')
    await page.keyboard.press('Tab'); assert.equal(await page.evaluate(() => document.activeElement.textContent), '继续审阅')
    await page.keyboard.press('Escape'); assert.equal(await page.getByRole('dialog').count(), 0)
    await page.getByRole('button', { name: '审阅并应用' }).click(); await page.getByRole('dialog').waitFor()
    diffHash = 'unreviewed-new-hash'
    await page.evaluate(() => document.querySelector('[aria-label="刷新文件"]').click())
    await page.getByRole('button', { name: '确认应用变更' }).click()
    await page.locator('.coding-agent-state').getByText('变更已应用', { exact: true }).waitFor()
    assert.equal(accepted.artifactHash, 'reviewed-hash')
  })
  await check('task switch clears drafts, file tabs and cancellation disables sending', async () => {
    await page.getByRole('textbox', { name: '编程任务或补充指令' }).fill('上一任务的未发送草稿')
    await page.getByRole('tab', { name: '编程会话', exact: true }).click()
    await page.locator('.coding-task-list').getByRole('button', { name: /另一个正在停止的分析任务/ }).click()
    await page.getByText('正在停止', { exact: true }).first().waitFor()
    assert.equal(await page.getByRole('textbox', { name: '编程任务或补充指令' }).inputValue(), '')
    assert.equal(await page.getByRole('button', { name: '发送' }).isDisabled(), true)
    await page.getByRole('button', { name: /梳理项目结构与启动方式/ }).click()
    await page.waitForFunction(() => document.querySelector('textarea[aria-label="编程任务或补充指令"]')?.value === '梳理项目结构与启动方式')
    assert.equal(await page.getByRole('combobox', { name: '任务模式' }).textContent(), '分析 / 规划')
  })
  await check('narrow project title and composer remain inside the viewport', async () => {
    await page.setViewportSize({ width: 390, height: 844 })
    const bounds = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth, composer: document.querySelector('.coding-composer').getBoundingClientRect().right }))
    assert.ok(bounds.scroll <= bounds.width); assert.ok(bounds.composer <= bounds.width)
    assert.ok(await page.locator('header nav > a').first().evaluate(el => el.getBoundingClientRect().height < 30), 'mobile navigation wraps characters vertically')
    await page.screenshot({ path: path.join(output, 'project-mobile.png') })
    await page.setViewportSize({ width: 1440, height: 900 }); await page.screenshot({ path: path.join(output, 'project.png') })
  })
  await check('new project carries selected workspace and true create flag', async () => {
    await page.locator('.coding-back').click()
    await page.getByRole('button', { name: /新建项目 在工作空间/ }).click()
    await page.getByPlaceholder('例如 my-next-app').fill('new-app')
    await page.getByRole('button', { name: '创建并打开项目' }).click()
    await page.getByRole('heading', { name: '一起完成这个项目' }).waitFor()
    assert.equal(createBody.workspaceId, 'w1'); assert.equal(createBody.directory, '.'); assert.equal(createBody.create, true)
  })
  await check('unknown routes retain 404 after coding navigation', async () => {
    await page.evaluate(() => { location.hash = '/missing-page' })
    await page.getByText('404 —— 没有这个页面。', { exact: true }).waitFor()
  })
  await check('a settings outage preserves the project and can recover in place', async () => {
    settingsFails = true
    await page.goto('http://coding.test/#/coding?project=p1')
    await page.getByRole('heading', { name: '一起完成这个项目' }).waitFor()
    await page.getByText('模型设置加载失败：模型设置暂时不可用', { exact: false }).waitFor()
    settingsFails = false; await page.getByRole('button', { name: '重新加载项目配置' }).click()
    await page.getByRole('combobox', { name: '编程模型' }).click()
    await page.getByRole('listbox', { name: '编程模型', exact: true }).getByRole('option', { name: '开发模型', exact: true }).waitFor()
    assert.equal(await page.getByRole('listbox', { name: '编程模型', exact: true }).getByRole('option', { name: '开发模型', exact: true }).count(), 1); await page.keyboard.press('Escape')
  })
  await check('an unavailable capability endpoint shows the real error and disables creation', async () => {
    capsFail = true; await page.goto('http://coding.test/#/coding'); await page.reload()
    await page.getByText('模块状态加载失败：模块状态暂时不可用', { exact: false }).waitFor()
    assert.equal(await page.getByRole('button', { name: /新建项目 在工作空间/ }).isDisabled(), true)
    capsFail = false; await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('.coding-notice[role="alert"]'))
  })
  for (const theme of ['old', 'endfield', 'bluearchive', 'astrolabe']) {
    await check(`${theme} shell contains the coding page at desktop and narrow widths`, async () => {
      await page.evaluate(theme => { localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: theme, schemeMode: 'dark', override: { seed: {} }, mono: { sidebar: true, hideChrome: false } })); location.hash = '/coding?project=p1' }, theme)
      await page.setViewportSize({ width: 1280, height: 900 }); await page.reload()
      await page.getByRole('heading', { name: '一起完成这个项目' }).waitFor(); await no404()
      await page.screenshot({ path: path.join(output, `project-${theme}.png`) })
      for (const width of [900, 390]) {
        await page.setViewportSize({ width, height: 844 })
        const bounds = await page.evaluate(() => { const main = document.querySelector('[data-page="coding"]'); const studio = document.querySelector('.coding-studio'); const composer = document.querySelector('.coding-composer'); return { width: innerWidth, scroll: document.documentElement.scrollWidth, studioRight: studio.getBoundingClientRect().right, composerRight: composer.getBoundingClientRect().right, parentBottom: main.getBoundingClientRect().bottom, studioBottom: studio.getBoundingClientRect().bottom } })
        assert.ok(bounds.scroll <= bounds.width, `${theme}/${width} document overflow`)
        assert.ok(bounds.studioRight <= bounds.width + 1, `${theme}/${width} studio overflow`)
        assert.ok(bounds.composerRight <= bounds.width + 1, `${theme}/${width} composer overflow`)
        assert.ok(bounds.studioBottom <= bounds.parentBottom + 1, `${theme}/${width} vertical overflow`)
        if (theme === 'old' && width === 390) {
          assert.ok(await page.locator('.coding-studio').evaluate(el => el.getBoundingClientRect().width > 300), 'mobile sidebar consumes the coding work area')
          assert.equal(await page.getByRole('button', { name: '用户', exact: true }).isVisible(), true)
        }
      }
    })
  }
  await check('agent roster includes the protected coding agent and opens its shared task panel', async () => {
    await page.setViewportSize({ width: 1440, height: 900 }); await page.goto('http://coding.test/#/multi-agent')
    await page.locator('a[href="#/multi-agent/builtin-coding"]').getByText('编码 Agent', { exact: true }).waitFor()
    await page.locator('a[href="#/multi-agent/builtin-coding"]').click()
    await page.getByRole('region', { name: '编码 Agent 的项目与任务' }).waitFor()
    assert.equal(await page.getByRole('button', { name: '移出圆桌', exact: true }).count(), 0)
    await page.screenshot({ path: path.join(output, 'coding-agent-profile.png') })
  })
  await check('agent task panel follows changing run state without manual refresh', async () => {
    secondPhase = 'CANCELLED'
    await page.getByRole('region', { name: '编码 Agent 的项目与任务' }).getByRole('link', { name: /另一个正在停止的分析任务/ }).getByText('已停止 ↗', { exact: true }).waitFor()
  })
  await check('agent tasks and coding studio navigate to the same run and identity', async () => {
    await page.getByRole('region', { name: '编码 Agent 的项目与任务' }).getByRole('link', { name: /为项目实现任务列表/ }).click()
    await page.locator('.coding-agent-state').getByText('变更已应用', { exact: true }).waitFor()
    assert.ok(page.url().endsWith('#/coding?project=p1&run=r1'))
    await page.locator('.coding-pane-heading a[href="#/multi-agent/builtin-coding"]').click()
    await page.getByRole('region', { name: '编码 Agent 的项目与任务' }).waitFor()
  })
  await check('saving agent model and name updates the coding entrance and new task default', async () => {
    await page.getByPlaceholder('如：张三').fill('项目编码员')
    await page.getByRole('combobox').first().click()
    await page.getByRole('option', { name: '推理模型', exact: true }).click()
    await page.getByRole('button', { name: '保存资料', exact: true }).click()
    await page.getByRole('heading', { name: '项目编码员', exact: true }).waitFor()
    await page.getByRole('region', { name: '编码 Agent 的项目与任务' }).getByRole('link', { name: '进入编程工作台 ↗', exact: true }).click()
    await page.locator('.coding-studio').waitFor()
    await page.locator('.coding-back').click()
    await page.getByRole('link', { name: '项目编码员 ↗', exact: true }).waitFor()
    await page.getByRole('button', { name: /personal-assistant-with-a-long-project-name/ }).click()
    await page.getByRole('heading', { name: '一起完成这个项目' }).waitFor()
    assert.equal(await page.getByRole('combobox', { name: '编程模型' }).textContent(), 'Agent · 推理模型')
    await page.screenshot({ path: path.join(output, 'coding-agent-linked-studio.png') })
  })
  await check('a truncated model response is shown as automatic continuation rather than a paused task', async () => {
    recovering = true; phase = 'RUNNING'
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.locator('.coding-output-recovery').getByText('单次输出达到模型上限，已自动续接（第 1 次）。 本轮工具调用未执行。', { exact: true }).waitFor()
    await page.locator('.coding-agent-state').getByText('Agent 工作中', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, 'output-continuation.png') })
    recovering = false; phase = 'COMPLETED'
  })
  await check('model settings save and reopen a distinct per-response output limit', async () => {
    await page.goto('http://coding.test/#/settings')
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await page.getByRole('textbox', { name: '单次输出上限', exact: true }).fill('32768')
    await page.getByLabel('编辑模型', { exact: true }).getByRole('button', { name: '保存', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('input[aria-label="单次输出上限"]'))
    assert.equal(modelBody.maxOutputTokens, 32768); assert.equal(modelBody.contextWindow, 262144)
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    assert.equal(await page.getByRole('textbox', { name: '单次输出上限', exact: true }).inputValue(), '32768')
    await page.screenshot({ path: path.join(output, 'model-output-setting.png') })
  })
  await check('coding activity shows live reasoning, the file being read and the search scope', async () => {
    phase = 'RUNNING'
    traceEvents = [traceEvent(100, 'reasoning.delta', { attempt: 1, round: 0, text: '先检查任务列表入口，再定位状态更新。' }), traceEvent(101, 'tool.started', { attempt: 1, operationId: 41, tool: 'read_file', args: JSON.stringify({ path: 'src/App.tsx', startLine: 12, lineCount: 20 }) }), traceEvent(102, 'budget.updated', { attempt: 1, totalTokens: 280000, estimated: false, limit: 0 })]
    await page.setViewportSize({ width: 1440, height: 900 }); await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.locator('.coding-reasoning-event').getByText('正在思考', { exact: true }).waitFor()
    await page.locator('.coding-reasoning-event pre').getByText('先检查任务列表入口，再定位状态更新。', { exact: true }).waitFor()
    const read = page.locator('.coding-tool-event').filter({ hasText: '读取代码 · src/App.tsx' })
    await read.getByText('执行中', { exact: true }).waitFor(); await read.getByText('请求第 12–31 行', { exact: true }).waitFor()
    await page.locator('.coding-budget-note').getByText('本次累计使用 280,000 tokens · 未限制累计额度', { exact: true }).waitFor()
    traceEvents.push(traceEvent(103, 'reasoning.finished', { attempt: 1, round: 0, text: '先检查任务列表入口，再定位状态更新。' }), traceEvent(104, 'tool.finished', { attempt: 1, operationId: 41, tool: 'read_file', status: 'DONE', result: 'file content' }), traceEvent(105, 'tool.started', { attempt: 1, operationId: 42, tool: 'search_text', args: JSON.stringify({ query: 'setTasks', glob: 'src/**' }) }))
    await read.getByText('完成 ✓', { exact: true }).waitFor()
    assert.equal(await page.locator('.coding-tool-event').filter({ hasText: '读取代码 · src/App.tsx' }).count(), 1)
    await page.locator('.coding-tool-event').getByText('搜索项目 · src/**', { exact: true }).waitFor()
    await page.getByText('关键词：setTasks · 范围：匹配该文件模式的位置', { exact: true }).waitFor()
    await page.reload()
    await page.locator('.coding-reasoning-event').getByText('思考过程', { exact: true }).waitFor()
    assert.equal(await page.locator('.coding-reasoning-event pre').textContent(), '先检查任务列表入口，再定位状态更新。')
    assert.equal(await page.locator('.coding-reasoning-event').count(), 1)
    await page.screenshot({ path: path.join(output, 'coding-activity-trace.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)
    assert.equal(overflow, false); await page.screenshot({ path: path.join(output, 'coding-activity-mobile.png') })
    traceEvents = []; phase = 'COMPLETED'
  })
  await check('model request retries show progress and replace failed stream fragments', async () => {
    phase = 'RUNNING'
    traceEvents = [traceEvent(160, 'reasoning.delta', { attempt: 1, round: 0, text: '失败请求的思考片段' }), traceEvent(161, 'message.delta', { attempt: 1, round: 0, text: '失败请求的正文片段' }), traceEvent(162, 'model.retry', { attempt: 1, round: 0, retry: 1, maximum: 3, delayMs: 500, error: 'chat 调用失败: HTTP 503' }), traceEvent(163, 'reasoning.finished', { attempt: 1, round: 0, text: '重试后的思考' }), traceEvent(164, 'message.delta', { attempt: 1, round: 0, text: '重试后的正文' })]
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.getByText('模型请求失败，将自动重试（1/3）。工具不会重复执行。', { exact: true }).waitFor()
    await page.locator('.coding-reasoning-event pre').getByText('重试后的思考', { exact: true }).waitFor()
    assert.equal(await page.locator('.coding-agent-scroll > .coding-agent-stream').textContent(), '重试后的正文')
    assert.equal(await page.getByText('失败请求的思考片段', { exact: true }).count(), 0)
    traceEvents = []; phase = 'COMPLETED'
  })
  await check('coding activity updates the workflow agent from working to idle', async () => {
    workflowWorking = true
    await page.setViewportSize({ width: 1440, height: 900 }); await page.goto('http://coding.test/#/workflow')
    await page.getByText('工作中', { exact: true }).waitFor()
    assert.equal(await page.getByText('空闲中', { exact: true }).count(), 0)
    await page.screenshot({ path: path.join(output, 'coding-workflow-running.png') })
    workflowWorking = false
    await page.getByText('空闲中', { exact: true }).waitFor()
    assert.equal(await page.getByText('工作中', { exact: true }).count(), 0)
  })
  await check('composition shows separate child traces and transcript detail levels', async () => {
    composition = true; phase = 'RUNNING'; rootMode = 'EDIT'; sessionLatest = 'r1'; followupRun = null
    childFixtures = [{ id: 'child-1', runId: 'r1', role: 'explore', title: '查找状态更新入口', prompt: '搜索 src 并报告文件证据', modelId: 'm1', status: 'RUNNING', attempt: 1, round: 0, answer: null, error: null, baseHash: 'tree', finalHash: null, configVersion: 1, createdAt: 1, updatedAt: 2 }]
    traceEvents = [traceEvent(200, 'reasoning.finished', { attempt: 1, round: 0, text: '主 Agent 思考' }), traceEvent(201, 'message.delta', { attempt: 1, text: '主会话正文' }), traceEvent(202, 'reasoning.delta', { executionId: 'child-1', executionAttempt: 1, attempt: 1, round: 0, text: '探索角色思考' }), traceEvent(203, 'message.delta', { executionId: 'child-1', executionAttempt: 1, attempt: 1, text: '子任务独立文本' }), traceEvent(204, 'tool.started', { executionId: 'child-1', executionAttempt: 1, attempt: 1, operationId: 1, tool: 'read_file', args: '{"path":"src/state.ts","startLine":1,"lineCount":30}' })]
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    const child = page.getByRole('region', { name: '子 Agent 执行' })
    await child.getByText('Explore · 探索', { exact: true }).waitFor(); await child.locator('summary').first().click()
    await child.getByText('探索角色思考', { exact: true }).waitFor(); await child.getByText('读取代码 · src/state.ts', { exact: true }).waitFor()
    assert.equal(await page.locator('.coding-agent-scroll > .coding-agent-stream').textContent(), '主会话正文')
    assert.equal(await child.locator('.coding-agent-stream').textContent(), '子任务独立文本')
    await chooseOption('过程展示', 'Normal · 摘要'); assert.equal(await page.locator('.coding-reasoning-event').count(), 0)
    await chooseOption('过程展示', 'Verbose · 详细'); assert.equal(await child.locator('.coding-tool-event').getAttribute('open'), '')
    await child.locator('.coding-tool-event').scrollIntoViewIfNeeded(); await page.screenshot({ path: path.join(output, 'composition-children.png') })
    await page.setViewportSize({ width: 390, height: 844 }); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'composition-mobile.png') }); await page.setViewportSize({ width: 1440, height: 900 })
    await child.getByRole('button', { name: '停止此子任务', exact: true }).click(); await child.getByText('已停止', { exact: true }).waitFor()
    await chooseOption('过程展示', 'Thinking · 思考'); childFixtures = []; traceEvents = []
  })
  await check('composition role model and prompts are configured under the shared coding agent', async () => {
    composition = true; await page.goto('http://coding.test/#/multi-agent/builtin-coding')
    const roles = page.getByRole('region', { name: '编码 Agent 的内置角色' })
    await roles.getByText('Explore · 探索', { exact: true }).click()
    await chooseOption('explore 模型', '开发模型')
    await page.getByRole('textbox', { name: 'explore 提示词' }).fill('优先定位启动入口，只读返回文件和行号。')
    await page.getByRole('button', { name: '保存 explore', exact: true }).click(); await roles.getByText('角色配置已保存。', { exact: true }).waitFor()
    assert.equal(roleBody.modelId, 'm1'); assert.match(roleBody.instructions, /只读/); assert.equal(roleFixtures[0].version, 2)
    await page.screenshot({ path: path.join(output, 'composition-role-settings.png') })
  })
  await check('composition approves planning without creating another conversation', async () => {
    composition = true; phase = 'REVIEWABLE'; rootMode = 'PLAN'; rootAttempt = 1; sessionLatest = 'r1'; followupRun = null
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.getByText('分析与规划已完成', { exact: true }).waitFor()
    await chooseOption('任务模式', '修改'); await page.getByRole('button', { name: '批准并实施', exact: true }).click()
    await page.locator('.coding-agent-state').getByText('Agent 工作中', { exact: true }).waitFor()
    assert.equal(resumeBody.mode, 'EDIT'); assert.equal(rootAttempt, 2); assert.ok(page.url().endsWith('run=r1'))
  })
  await check('composition continues an applied task with its session history and protects old turns', async () => {
    composition = true; phase = 'COMPLETED'; rootMode = 'EDIT'; rootAttempt = 1; sessionLatest = 'r1'; followupRun = null; traceEvents = []; childFixtures = []
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.getByText('持续编程会话', { exact: true }).waitFor(); await page.getByRole('textbox', { name: '编程任务或补充指令' }).fill('继续优化筛选功能')
    await page.getByRole('button', { name: '继续', exact: true }).click(); await page.locator('.coding-user-message').getByText('继续优化筛选功能', { exact: true }).waitFor()
    await page.locator('.coding-history').getByText('为项目实现任务列表', { exact: true }).waitFor(); assert.equal(followupRun.sessionId, 's1')
    await page.locator('.coding-history summary').click(); await page.screenshot({ path: path.join(output, 'composition-session.png') })
    await page.locator('.coding-history').getByRole('button', { name: '查看本轮完整过程 ↗', exact: true }).click()
    await page.getByText('这是历史轮次。', { exact: false }).waitFor(); await page.getByRole('textbox', { name: '编程任务或补充指令' }).fill('不能修改旧轮次')
    assert.equal(await page.getByRole('button', { name: '继续', exact: true }).isDisabled(), true)
    await page.getByRole('button', { name: '回到会话最新轮次 ↗', exact: true }).click(); await page.locator('.coding-user-message').getByText('继续优化筛选功能', { exact: true }).waitFor()
  })
  await check('IDE keeps dropdowns inside mobile viewport and remains usable with enlarged text', async () => {
    composition = true; phase = 'RUNNING'; rootMode = 'EDIT'; rootAttempt = 1; sessionLatest = 'r1'; followupRun = null; childFixtures = []; traceEvents = []
    await page.goto('http://coding.test/#/coding?project=p1&run=r1')
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await page.getByRole('textbox', { name: '编程任务或补充指令' }).evaluate(el => getComputedStyle(el).fontSize), '16px')
    await page.getByRole('combobox', { name: '过程展示', exact: true }).click()
    const list = await page.getByRole('listbox', { name: '过程展示', exact: true }).boundingBox()
    assert.ok(list.x >= 0 && list.x + list.width <= 390 && list.y >= 0 && list.y + list.height <= 844)
    await page.screenshot({ path: path.join(output, 'ide-polish-mobile-menu.png') }); await page.keyboard.press('Escape')
    await page.getByRole('button', { name: '文件与代码', exact: true }).click()
    await page.getByRole('tab', { name: '编程会话', exact: true }).click(); await page.screenshot({ path: path.join(output, 'ide-polish-mobile-files.png') })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.getByRole('button', { name: 'Agent 对话', exact: true }).click(); await page.setViewportSize({ width: 1440, height: 900 })
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
    assert.equal(await page.locator('.coding-editor').isVisible(), false)
    assert.equal(await page.getByRole('combobox', { name: '任务模式', exact: true }).evaluate(el => getComputedStyle(el).fontSize), '28px')
    const bounds = await page.getByRole('textbox', { name: '编程任务或补充指令' }).boundingBox()
    assert.ok(bounds.height > 100 && bounds.y + bounds.height <= 900)
    assert.ok(await page.locator('.coding-agent-scroll').evaluate(el => el.clientHeight >= 100), 'enlarged text must leave room to read the conversation')
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'ide-polish-enlarged-text.png') }); await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  })
  await check('session cookie restores the IDE after navigation, reload and opening another tab', async () => {
    composition = false
    await context.clearCookies()
    await page.goto('http://coding.test/#/coding')
    await page.getByRole('heading', { name: '从一个项目开始', exact: true }).waitFor()
    await page.getByRole('button', { name: /personal-assistant-with-a-long-project-name/ }).click()
    await page.locator('.coding-studio').waitFor()
    await page.waitForFunction(() => document.cookie.includes('minis.coding.project=p1'))
    const cookie = (await context.cookies()).find(item => item.name === 'minis.coding.project')
    assert.equal(cookie.value, 'p1'); assert.equal(cookie.expires, -1); assert.equal(cookie.path, '/')
    await page.goto('http://coding.test/#/knowledge')
    await page.goto('http://coding.test/#/coding')
    await page.locator('.coding-studio').waitFor()
    assert.equal(await page.locator('.coding-start').count(), 0)
    await page.reload(); await page.locator('.coding-studio').waitFor()
    const tab = await context.newPage()
    try { await tab.goto('http://coding.test/#/coding'); await tab.locator('.coding-studio').waitFor() }
    finally { await tab.close() }
  })
  await check('project picker and workspace directory links bypass automatic restoration', async () => {
    await page.locator('.coding-back').click()
    await page.getByRole('heading', { name: '从一个项目开始', exact: true }).waitFor()
    await page.reload(); await page.locator('.coding-start').waitFor()
    assert.ok((await page.evaluate(() => document.cookie)).includes('minis.coding.project=p1'))
    await page.goto('http://coding.test/#/coding?workspace=w1&directory=src')
    await page.locator('.coding-picker').waitFor()
    assert.equal(await page.locator('.coding-studio').count(), 0)
    await page.goto('http://coding.test/#/coding'); await page.locator('.coding-studio').waitFor()
  })
  await check('explicit project and task links take precedence and update the remembered project', async () => {
    listedProjects = [project, { ...project, id: 'p2', name: '第二个项目' }]
    await page.goto('http://coding.test/#/coding?project=p2'); await page.reload()
    await page.locator('.coding-studio-toolbar strong').getByText('第二个项目', { exact: true }).waitFor()
    await page.waitForFunction(() => document.cookie.includes('minis.coding.project=p2'))
    await page.goto('http://coding.test/#/coding?run=r2')
    await page.locator('.coding-studio-toolbar strong').getByText(project.name, { exact: true }).waitFor()
    await page.waitForFunction(() => document.cookie.includes('minis.coding.project=p1'))
    await page.goto('http://coding.test/#/coding?project=missing')
    await page.locator('.coding-start').waitFor()
    await page.getByText('这个项目未绑定现有工作空间', { exact: false }).waitFor()
    listedProjects = [project]
  })
  await check('removed or disabled projects clear the stale cookie and return to the picker', async () => {
    await page.evaluate(() => { document.cookie = 'minis.coding.project=missing; Path=/; SameSite=Lax' })
    await page.goto('http://coding.test/#/coding'); await page.reload()
    await page.locator('.coding-start').waitFor()
    await page.waitForFunction(() => !document.cookie.includes('minis.coding.project='))
    listedProjects = [{ ...project, enabled: false }]
    await page.evaluate(() => { document.cookie = 'minis.coding.project=p1; Path=/; SameSite=Lax' })
    await page.reload(); await page.locator('.coding-start').waitFor()
    await page.waitForFunction(() => !document.cookie.includes('minis.coding.project='))
    listedProjects = [project]
  })
  await check('temporary project loading failures preserve the session cookie for retry', async () => {
    await page.evaluate(() => { document.cookie = 'minis.coding.project=p1; Path=/; SameSite=Lax' })
    projectsFail = true
    await page.reload(); await page.getByText('项目列表暂时不可用', { exact: false }).waitFor()
    assert.ok((await page.evaluate(() => document.cookie)).includes('minis.coding.project=p1'))
    projectsFail = false
    await page.getByRole('button', { name: '重新加载', exact: true }).click()
    await page.locator('.coding-studio').waitFor()
  })
  assert.deepEqual(errors, [])
  console.log(`COMPLETE ${checks.length} browser checks. Production App; all assets/APIs intercepted; no real model or workspace mutations.`)
} catch (error) {
  if (errors.length) console.error('Unhandled page errors:', JSON.stringify(errors))
  await page.screenshot({ path: path.join(output, 'failure.png') }).catch(() => {})
  throw error
} finally { await browser.close() }
