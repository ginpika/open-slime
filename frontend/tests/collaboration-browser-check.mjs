import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const frontend = fileURLToPath(new URL('../', import.meta.url)), dist = path.join(frontend, 'dist')
const output = path.resolve(frontend, '../backend/target/collaboration-browser-check')
await fs.mkdir(output, { recursive: true })
const require = createRequire(path.join(process.env.MINIS_PLAYWRIGHT_DIR || frontend, 'package.json'))
const { chromium } = require('playwright')
const now = Date.now(), errors = [], requests = [], sent = [], checks = []
const peers = ['规划成员', '研究成员', '执行成员'].map((name, i) => ({ id: ['a', 'b', 'c'][i], name, role: ['产品经理', '研究员', '工程师'][i], roleColor: '#2563eb', builtin: false, tools: [], terminals: [], modelId: null, sort: i, avatar: null, note: null }))
const agents = peers.map(p => ({ ...p, communication: 'LISTENING', execution: 'READY', working: false, pending: 1, doing: 0, done: 1 }))
const projects = [{ id: 'p1', name: '多人塔防', kind: 'software', status: 'active', narrative: false }, { id: 'p2', name: '第二个项目', kind: 'research', status: 'active', narrative: false }]
projects.push({ id: 'empty', name: '尚未开始的项目', kind: 'other', status: 'active', narrative: false })
const session = (id, projectId, title, members = ['a', 'b', 'c']) => ({ id, projectId, title, modelId: null, memberIds: members, topicId: null, topicStatus: 'closed', floorPeer: null, createdAt: now, updatedAt: now })
const sessions = [session('s1', 'p1', '第一轮方案与实现'), session('s2', 'p2', '另一份研究'), session('s3', null, '历史圆桌')]
const task = (id, status, dependsOn = []) => ({ id, title: id === 't2' ? '核对联机协议' : `工作 ${id}`, detail: '在当前项目中实现并提供验证依据', status, ownerPeer: 'a', ownerName: '规划成员', createdBy: 'b', createdByName: '研究成员', dependsOn, result: status === 'done' ? '成员提交的历史结论' : null, blockReason: status === 'blocked' ? '需要确认版本' : null, seq: Number(id.slice(1)) })
const todos = [task('t1', 'done'), task('t2', 'blocked', ['t1']), task('t3', 'doing', ['t1']), task('t4', 'pending', ['t2', 't3']), ...Array.from({ length: 53 }, (_, i) => task('t' + (i + 5), 'done'))]
todos[1].detail = '## 工作范围\n\n核对断线重连协议并记录限制。\n\n- 客户端核对序号\n- 服务端保留会话\n\n文件路径：`src/network/protocol.ts`。'
todos[1].result = '## 阶段性成果\n\n已保留协议核对结果，仍需主控处理版本冲突。\n\n### 验证证据\n\n| 检查 | 结果 |\n| --- | --- |\n| 单元验证 | 已完成 |\n| 实际联机 | 等待版本确认 |\n\n> 这份记录只反映本次核对范围。'
const runReport = '# 协议核对结果\n\n本次产出包含验证证据与限制。\n\n## 已完成\n\n- 保留断线前的序号\n- 对齐服务端协议定义\n\n```typescript\nconst version = "v2"\nexport const session = { version }\n```\n\n## 验证记录\n\n' + '已执行序号核对；本地验证通过，实际联机仍需确认部署版本。\n\n'.repeat(60) + '## 后续限制\n\n长报告末尾：实际联机尚未完成。'
const reviewRuns = Array.from({ length: 25 }, (_, i) => ({ id: 100 - i, todoId: 't2', peerId: i % 2 ? 'b' : 'a', peerName: i % 2 ? '研究成员' : '规划成员', status: i === 0 ? 'submitted' : i === 1 ? 'failed' : i === 2 ? 'incomplete' : 'done', output: i === 0 ? runReport : i === 1 ? '上一次执行的原始产出\n\n尚未确认部署版本。' : i === 2 ? null : `历史执行 ${i + 1} 的验证记录`, reasoning: i === 0 ? '分析记录只在单独页签中展示。\n\n需要核对运行版本再做结论。' : null, trace: i === 0 ? [{ type: 'tool', name: 'verify_protocol', args: '{"path":"协议.ts"}', round: 1 }] : [], toolRounds: i === 0 ? [{ role: 'assistant', tool_calls: [{ id: 'read-1', function: { name: 'read_file', arguments: '{"path":"src/network/协议.ts"}' } }, { id: 'verify-1', function: { name: 'verify_protocol', arguments: '{"version":"v2"}' } }] }, { role: 'tool', tool_call_id: 'verify-1', content: '{"ok":false,"reason":"验证返回的内容：部署版本尚未确认"}' }, { role: 'tool', tool_call_id: 'read-1', content: '原始文件内容\nconst version = "v2"\n' }] : [], error: i === 1 ? '连接服务失败：version-mismatch' : null, startedAt: i === 1 ? null : now - i * 120000 - 20000, finishedAt: now - i * 120000 }))
let failRuns = false
const events = [{ id: 'event:1', kind: 'DISCUSSION_REQUESTED', source: 'b', targets: ['a', 'user'], taskId: 't2', text: '需要你和规划成员确认协议版本', createdAt: 'invalid-history-date' }]
const message = (id, content, role = 'peer', extra = {}) => ({ id, sessionId: 's1', topicId: 'topic1', role, peerId: role === 'peer' ? 'a' : null, peerName: role === 'peer' ? '规划成员' : null, content, reasoning: null, trace: null, createdAt: new Date(now + id * 100).toISOString(), ...extra })
const messages = [message(1, '实现一个可联机的塔防游戏', 'user'), ...Array.from({ length: 600 }, (_, i) => message(i + 2, `连续发言片段 ${i + 1}`))]
const issues = [{ id: 'i1', parentSessionId: 's1', sessionId: 'issue1', title: '联机协议的重连策略如何确定？', status: 'open', conclusion: null, messageId: 800, createdAt: now, resolvedAt: null }]
const projectMembers = { p1: ['a', 'b', 'c'] }
const workspaceBindings = [], bindingRequests = []
const projectDocs = [
  { key: 'overview', kind: 'overview', title: '塔防项目现状', content: '# 当前阶段\n\n已验证联机协议，客户端重连尚未完成。\n\n## 下一步\n先确认协议版本，再继续重连验证。', version: 1, retired: false, sourceSessionId: 's1', sourceTitle: '第一轮方案与实现', author: 'a', updatedAt: now },
  { key: 'decision-storage', kind: 'decision', title: '存储方案决策', content: '决定使用 SQLite。依据：本地部署与备份简单。替代方案 PostgreSQL 留作多用户部署评估。', version: 1, retired: false, sourceSessionId: 's1', sourceTitle: '第一轮方案与实现', author: 'a', updatedAt: now },
]
const documentHistory = new Map(projectDocs.map(d => [d.key, [{ ...d }]]))
let failDocumentSave = false
const plans = [{ sessionId: 's1', coordinatorId: 'b', phase: 'planning', criteria: '保留联机行为，构建和断线重连测试通过', decision: '先核对实际协议成果，再决定原条目返工', evidence: null, todoId: null, executorId: null, reviewerId: null, continuationStatus: 'active', pauseReason: null, scope: { deliverables: '本次仅核对联机协议', exclusions: '不扩张到完整游戏实现', baseline: '已读取当前协议文件与测试记录', roadmap: '本次通过后再决定下阶段' } }]
sessions.push(session('issue1', 'p1', issues[0].title, ['a', 'b']))
messages.push(message(800, '发起议题', 'event', { kind: 'issue_open' }))
const reportStatus = '已定向请 研究成员 回应 执行成员 的报告'
const longReport = reportStatus + '：条目已提交，待独立检查。\n' + '构建与测试记录：npm run build 已完成；文件变更与检查依据保留。\n'.repeat(120) + '报告结束（只你们沟通一轮，其余成员无需参与）'
messages.push(message(801, longReport, 'event', { kind: 'rally' }))
let failWorkflow = false, failCreate = false, patchFails = false, holdS1 = false, releaseMessages
let failMessages = false, disconnectStream = false
let failRewind = false, failLifecycle = false
const archivedTasks = [], lifecycleRequests = []
const rewindRequests = []
const sessionTopics = new Map(), extraMessages = new Map()
let gateRelease, workflowGate = new Promise(resolve => { gateRelease = resolve }), releaseStream
const streamGate = new Promise(resolve => { releaseStream = resolve })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
await context.addInitScript(() => { if (!localStorage.getItem('minis.theme.v4')) localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: 'old', schemeMode: 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })) })
await context.route('**/*', async route => {
  const url = new URL(route.request().url()), method = route.request().method()
  if (url.origin !== 'http://collaboration.test') return route.abort()
  if (!url.pathname.startsWith('/api/')) {
    const file = path.resolve(dist, url.pathname === '/' ? 'index.html' : '.' + url.pathname)
    if (!file.startsWith(dist + path.sep)) return route.abort()
    try { return await route.fulfill({ path: file }) } catch { return route.fulfill({ status: 404 }) }
  }
  requests.push(url.pathname + url.search)
  const json = value => route.fulfill({ json: value })
  if (url.pathname === '/api/auth/me') return json({ id: 'test', username: 'test', nickname: '测试用户', role: 'user' })
  if (url.pathname === '/api/settings') return json({ models: [], embedding: { mode: 'llm' }, rerank: { mode: 'off' } })
  if (url.pathname === '/api/projects') {
    if (method === 'POST') { const p = { id: 'p' + (projects.length + 1), ...route.request().postDataJSON(), status: 'active' }; projects.push(p); return json(p) }
    return json(projects)
  }
  if (url.pathname === '/api/agents/collaboration') return json({ issues, projectMembers, plans, workspaceBindings, archivedTasks })
  const knowledge = url.pathname.match(/^\/api\/projects\/([^/]+)\/knowledge(?:\/(.*))?$/)
  if (knowledge) {
    const project = knowledge[1], action = knowledge[2] || '', rows = project === 'p1' ? projectDocs : []
    if (action === 'progress') return json({ completedSessions: 1, openTodos: project === 'p1' ? 1 : 0, blockedTodos: project === 'p1' ? 1 : 0, work: { items: project === 'p1' ? [{ sessionId: 's1', sessionTitle: '第一轮方案与实现', todoId: 't2', title: '核对联机协议', status: 'blocked', blocker: '需要确认版本', paused: true }] : [], total: project === 'p1' ? 1 : 0, offset: 0, limit: 20 } })
    if (!action) {
      const query = url.searchParams.get('query') || '', retired = url.searchParams.get('retired') === 'true', offset = Number(url.searchParams.get('offset') || 0)
      const found = rows.filter(d => (retired || !d.retired) && `${d.title}\n${d.content}`.includes(query))
      return json({ items: found.slice(offset, offset + 20).map(d => ({ ...d, excerpt: d.content.slice(0, 320) })), total: found.length, offset, limit: 20 })
    }
    const key = decodeURIComponent(action.split('/')[1]), doc = rows.find(d => d.key === key)
    if (action.endsWith('/history')) return json(documentHistory.get(key) || [])
    if (method === 'PUT') {
      const body = route.request().postDataJSON()
      if (failDocumentSave || body.expectedVersion !== (doc?.version || 0)) return route.fulfill({ status: 409, json: { message: '文档已有新版本，请重新读取后合并，不能覆盖他人的更新' } })
      const saved = { key, ...body, version: (doc?.version || 0) + 1, sourceSessionId: null, sourceTitle: null, author: 'user:test', updatedAt: now + 1000 }
      if (doc) rows.splice(rows.indexOf(doc), 1, saved); else rows.push(saved)
      documentHistory.set(key, [saved, ...(documentHistory.get(key) || [])]); return json(saved)
    }
    const version = Number(url.searchParams.get('version') || doc?.version)
    return json(documentHistory.get(key)?.find(d => d.version === version) || doc)
  }
  if (url.pathname === '/api/agents/collaboration/workspaces') return json([{ id: 'w1', name: '研发工作空间', kind: 'ssh', enabled: true, workdir: '/workspace' }])
  if (url.pathname === '/api/workspaces/w1/files') return json({ path: url.searchParams.get('path') || '.', entries: url.searchParams.get('path') === 'projects' ? [{ name: 'existing-app', dir: true, link: false }] : url.searchParams.get('path') === '.' ? [{ name: 'projects', dir: true, link: false }] : [] })
  if (/^\/api\/projects\/[^/]+\/collaboration-workspace$/.test(url.pathname)) {
    const body = route.request().postDataJSON(), projectId = url.pathname.split('/')[3]
    bindingRequests.push({ projectId, ...body })
    const directory = body.create ? body.directory + '/' + body.name : body.directory
    const binding = { projectId, repositoryId: 'bound-code', workspaceId: body.workspaceId, workspaceName: '研发工作空间', directory, root: '/workspace/' + directory, enabled: true }
    workspaceBindings.splice(0, workspaceBindings.length, binding); return json(binding)
  }
  if (/^\/api\/projects\/[^/]+\/collaboration-members$/.test(url.pathname)) { projectMembers[url.pathname.split('/')[3]] = route.request().postDataJSON().memberIds; return json(projectMembers[url.pathname.split('/')[3]]) }
  if (/^\/api\/projects\/[^/]+$/.test(url.pathname) && method === 'PUT') { const p = projects.find(p => url.pathname.endsWith('/' + p.id)); Object.assign(p, route.request().postDataJSON()); return json(p) }
  const lifecycle = url.pathname.match(/^\/api\/agents\/collaboration\/tasks\/([^/]+)(\/archive)?$/)
  if (lifecycle) {
    if (failLifecycle) return route.fulfill({ status: 409, json: { message: '任务或关联议题仍在执行，请先停止后再操作' } })
    const id = lifecycle[1]
    lifecycleRequests.push({ id, method, body: method === 'PUT' ? route.request().postDataJSON() : null })
    if (method === 'PUT') {
      const archived = route.request().postDataJSON().archived
      if (archived && !archivedTasks.includes(id)) archivedTasks.push(id)
      if (!archived && archivedTasks.includes(id)) archivedTasks.splice(archivedTasks.indexOf(id), 1)
      return json({ archived })
    }
    if (method === 'DELETE') {
      const owned = new Set([id, ...issues.filter(i => i.parentSessionId === id).map(i => i.sessionId)])
      for (let i = sessions.length - 1; i >= 0; i--) if (owned.has(sessions[i].id)) sessions.splice(i, 1)
      for (let i = issues.length - 1; i >= 0; i--) if (issues[i].parentSessionId === id) issues.splice(i, 1)
      return json({ deleted: true })
    }
  }
  if (url.pathname === '/api/agents/collaboration/tasks') {
    if (failCreate) return route.fulfill({ status: 503, json: { message: '创建暂时不可用' } })
    const body = route.request().postDataJSON(), id = 'new' + sessions.length
    assert.ok(body.memberIds.includes(body.coordinatorId), '主控必须来自本任务成员')
    plans.push({ ...plans[0], sessionId: id, coordinatorId: body.coordinatorId, criteria: null, decision: null, scope: null })
    sessions.unshift(session(id, body.projectId, body.title, body.memberIds)); return json({ sessionId: id })
  }
  if (/\/api\/agents\/sessions\/[^/]+\/issues$/.test(url.pathname)) {
    const body = route.request().postDataJSON(), parentSessionId = url.pathname.split('/')[4], id = 'i' + (issues.length + 1), child = 'issue' + (issues.length + 1)
    const issue = { id, parentSessionId, sessionId: child, title: body.title, status: 'open', conclusion: null, messageId: 810 + issues.length, createdAt: now, resolvedAt: null }
    issues.push(issue); sessions.push(session(child, 'p1', body.title, body.memberIds)); messages.push(message(issue.messageId, '发起议题', 'event', { kind: 'issue_open' })); return json(issue)
  }
  if (/\/api\/agents\/issues\/[^/]+\/conclusion$/.test(url.pathname)) {
    const issue = issues.find(i => url.pathname.includes('/' + i.id + '/')); issue.status = 'resolved'; issue.conclusion = route.request().postDataJSON().conclusion
    messages.push(message(850, '议题结论：' + issue.conclusion, 'event', { kind: 'issue_resolved' })); return json(issue)
  }
  if (url.pathname.includes('/organize')) { const s = sessions.find(s => url.pathname.includes('/' + s.id + '/')); s.projectId = 'p1'; return json(projects[0]) }
  if (url.pathname === '/api/agents/peers') return json(peers)
  if (url.pathname === '/api/agents/sessions') {
    if (method === 'POST') {
      if (failCreate) return route.fulfill({ status: 503, json: { message: '创建暂时不可用' } })
      const body = route.request().postDataJSON(), id = 's' + (sessions.length + 1)
      sessions.unshift(session(id, body.projectId, '新协作')); return json({ sessionId: id })
    }
    return json(sessions)
  }
  const rewind = url.pathname.match(/^\/api\/agents\/sessions\/([^/]+)\/rewind$/)
  if (rewind) {
    if (failRewind) return route.fulfill({ status: 503, json: { message: '回退暂时不可用' } })
    const id = rewind[1], body = route.request().postDataJSON(), preserved = ['task_context', 'issue_context', 'issue_open', 'issue_resolved']
    const rows = id === 's1' ? messages : extraMessages.get(id) || []
    const before = rows.length
    const kept = rows.filter(m => m.id < body.messageId || preserved.includes(m.kind))
    if (id === 's1') messages.splice(0, messages.length, ...kept)
    else extraMessages.set(id, kept)
    sessionTopics.set(id, (sessionTopics.get(id) || []).map(t => ({ ...t, status: 'closed', floorPeer: null })))
    rewindRequests.push({ id, ...body })
    return json({ sessionId: id, removed: before - kept.length })
  }
  const match = url.pathname.match(/\/api\/agents\/sessions\/([^/]+)\/(messages|topics)/)
  if (match) {
    if (match[2] === 'topics') return json(sessionTopics.get(match[1]) || [])
    if (failMessages && match[1] === 's1') return route.fulfill({ status: 503, json: { message: '网络暂时不可用' } })
    if (match[1] === 's1' && holdS1) await new Promise(resolve => { releaseMessages = resolve })
    const base = match[1] === 's1' ? messages : match[1].startsWith('issue') ? [message(901, '议题内部的独立讨论内容', 'peer', { sessionId: match[1] })] : [message(900, `当前会话 ${match[1]} 的内容`, 'user', { sessionId: match[1] })]
    return json([...base, ...(extraMessages.get(match[1]) || [])])
  }
  if (url.pathname === '/api/agents/workflow') {
    await workflowGate
    if (failWorkflow) return route.fulfill({ status: 503, json: { message: '状态服务暂时不可用' } })
    const id = url.searchParams.get('sessionId')
    return json({ sessionId: id, title: sessions.find(s => s.id === id)?.title ?? null, topicId: null, topicStatus: 'closed', floorPeer: null, discussing: false, advancing: false, agents, todos: id === 's1' ? todos : [], events: id === 's1' ? events : [] })
  }
  if (/\/api\/agents\/todos\/[^/]+\/runs/.test(url.pathname)) return failRuns ? route.fulfill({ status: 503, json: { message: '执行记录暂时不可用' } }) : json(url.pathname.includes('/t2/') ? reviewRuns.slice(0, Number(url.searchParams.get('limit') || 20)) : [])
  if (/^\/api\/agents\/collaboration\/tasks\/[^/]+\/todos\/[^/]+\/quality$/.test(url.pathname)) return json({
    contract: { outcome: '用实测证据确定重连策略', acceptanceCriteria: [{ id: 'C1', text: '断线重连测试通过，并记录限制' }], stopCondition: '证据足以支持当前决策即收工' },
    findings: [{ id: 'F1', severity: 'BLOCKER', location: 'protocol.ts:20', expected: '补齐断线后的序号核对', basis: '验收标准 C1', status: 'RESOLVED', resolutionEvidence: '已复跑断线重连测试' }, { id: 'N1', severity: 'MINOR', location: 'docs/protocol.md:12', expected: '后续可补充示意图', basis: '阅读便利，不阻碍协议正确性', status: 'ADVISORY' }],
    diagnosis: { code: 'APPLICATION_CONFLICT', detail: '项目文件已变化，保留编码副本并交回主控处理', runId: 'coding-evidence', artifactHash: 'current-hash', createdAt: now },
  })
  if (url.pathname.startsWith('/api/agents/todos/')) return patchFails ? route.fulfill({ status: 503, json: { message: '任务更新失败' } }) : json({ ok: true })
  if (url.pathname === '/api/agents/chat/stream') {
    const body = route.request().postDataJSON(); sent.push(body); if (body.sessionId === 's1') messages.push(message(1000, body.input, 'user'))
    if (disconnectStream) {
      disconnectStream = false
      // A state frame followed by EOF without done reproduces a broken transport with a stale local topic.
      return route.fulfill({ contentType: 'text/event-stream', body: 'event: state\ndata: ' + JSON.stringify({ id: 'recovery-topic', sessionId: body.sessionId, status: 'open', states: { 规划成员: 'SPEAKING' } }) + '\n\n' })
    }
    await streamGate
    return route.fulfill({ contentType: 'text/event-stream', body: `event: done\ndata: ${JSON.stringify({ sessionId: body.sessionId, topicId: 'topic1', topicStatus: 'closed', speeches: 0 })}\n\n` })
  }
  if (url.pathname.includes('/stream')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
  return json([])
})
const page = await context.newPage(); page.setDefaultTimeout(12000)
page.on('pageerror', e => errors.push(e.message))
const tab = name => page.getByRole('tab', { name, exact: true }).click()
try {
  await page.goto('http://collaboration.test/#/collaboration')
  await page.getByRole('heading', { name: '从一个方向开始', exact: true }).waitFor()
  await page.locator('.collab-project-row').first().waitFor()
  assert.equal(await page.locator('.collab-v2 > .ui-card').count(), 0)
  assert.equal(await page.locator('.collab-todos').count(), 0)
  await page.screenshot({ path: path.join(output, 'start-desktop.png') })
  await page.locator('.collab-project-row').filter({ hasText: '多人塔防' }).click()
  await page.getByRole('tab', { name: '当前推进', exact: true }).waitFor()
  assert.equal(await page.getByRole('tabpanel').count(), 1, '项目每次只展示一个分页')
  assert.equal(await page.getByRole('tab', { name: '当前推进', exact: true }).getAttribute('aria-selected'), 'true')
  await tab('成果与进度')
  await page.getByRole('heading', { name: /^项目资料与进度/ }).waitFor()
  await page.getByRole('button', { name: /项目现状 塔防项目现状/ }).click()
  await page.locator('.collab-document-markdown').getByText('已验证联机协议，客户端重连尚未完成。', { exact: true }).waitFor()
  await page.locator('.collab-project-progress summary').click()
  await page.locator('.collab-progress-row').getByText('受阻 · 已暂停', { exact: true }).waitFor()
  await page.getByRole('button', { name: '修订', exact: true }).click()
  await page.getByRole('textbox', { name: '内容（Markdown）', exact: true }).fill('# 当前阶段\n\n接口与客户端已通过独立检查，下一步验证重连。')
  failDocumentSave = true
  await page.getByRole('button', { name: '保存版本', exact: true }).click()
  await page.getByRole('dialog').getByRole('alert').waitFor()
  assert.match(await page.getByRole('textbox', { name: '内容（Markdown）', exact: true }).inputValue(), /客户端已通过/)
  failDocumentSave = false
  await page.getByRole('button', { name: '保存版本', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.locator('.collab-document-markdown').getByText('接口与客户端已通过独立检查，下一步验证重连。', { exact: true }).waitFor()
  await page.getByRole('combobox', { name: '文档历史版本', exact: true }).click()
  await page.getByRole('option', { name: /^v1 ·/ }).click()
  await page.locator('.collab-document-markdown').getByText('已验证联机协议，客户端重连尚未完成。', { exact: true }).waitFor()
  assert.ok(await page.getByRole('button', { name: '修订', exact: true }).isDisabled(), '历史版本不能覆盖最新版')
  await page.evaluate(() => { const create = URL.createObjectURL.bind(URL); URL.createObjectURL = blob => { window.__projectExport = blob; return create(blob) } })
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Markdown', exact: true }).click()
  const file = await download
  assert.equal(file.suggestedFilename(), 'overview-v1.md')
  const exported = await page.evaluate(() => window.__projectExport.text())
  assert.match(exported, /客户端重连尚未完成/)
  const downloadFailure = await file.failure()
  if (downloadFailure) {
    checks.push('测试浏览器下载被环境取消：' + downloadFailure + '；已验证下载文件名及实际Blob全文')
    await fs.writeFile(path.join(output, 'overview-v1.md'), exported)
  } else await file.saveAs(path.join(output, 'overview-v1.md'))
  assert.match(await fs.readFile(path.join(output, 'overview-v1.md'), 'utf8'), /客户端重连尚未完成/)
  await page.getByRole('textbox', { name: '搜索项目资料', exact: true }).fill('PostgreSQL')
  await page.locator('.collab-document-row').filter({ hasText: '存储方案决策' }).waitFor()
  await page.waitForFunction(() => document.querySelectorAll('.collab-document-row').length === 1)
  await page.getByRole('textbox', { name: '搜索项目资料', exact: true }).fill('')
  await page.waitForFunction(() => document.querySelectorAll('.collab-document-row').length === 2)
  await page.getByRole('button', { name: '新建文档', exact: true }).click()
  await page.getByRole('textbox', { name: '文档标题', exact: true }).fill('后续核对备忘')
  await page.getByRole('textbox', { name: '内容（Markdown）', exact: true }).fill('尚未验证重连，下一步先检查现有成果。')
  await page.getByRole('button', { name: '保存版本', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.locator('.collab-document-reader h3').getByText('后续核对备忘', { exact: true }).waitFor()
  await page.getByRole('button', { name: '修订', exact: true }).click()
  await page.getByRole('checkbox', { name: '已过期或被替代，保留历史供追溯', exact: true }).check()
  await page.getByRole('button', { name: '保存版本', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.waitForFunction(() => document.querySelectorAll('.collab-document-row').length === 2)
  await page.getByRole('checkbox', { name: '包含过期资料', exact: true }).check()
  await page.locator('.collab-document-row').filter({ hasText: '后续核对备忘' }).waitFor()
  await page.getByRole('checkbox', { name: '包含过期资料', exact: true }).uncheck()
  await page.waitForFunction(() => document.querySelectorAll('.collab-document-row').length === 2)
  await page.getByRole('button', { name: /项目现状 塔防项目现状/ }).click()
  await page.locator('.collab-document-markdown').getByText('接口与客户端已通过独立检查，下一步验证重连。', { exact: true }).waitFor()
  const delivery = { ...projectDocs[0], key: 'delivery-long', kind: 'delivery', title: '完整交付记录', content: '# 验证与交付\n\n' + '已完成构建与独立检查，保留成果位置及验证依据。\n\n'.repeat(100) + '交付记录全文结束' }
  projectDocs.push(delivery); documentHistory.set(delivery.key, [delivery])
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.locator('.collab-document-row').filter({ hasText: delivery.title }).click()
  await page.locator('.collab-document-reader h3').getByText(delivery.title, { exact: true }).waitFor()
  const reader = page.getByRole('region', { name: '文档正文', exact: true })
  const bounds = await reader.evaluate(el => ({ height: el.clientHeight, content: el.scrollHeight }))
  assert.ok(bounds.height <= 540 && bounds.content > bounds.height * 3, '长交付正文限高并保留全文')
  await reader.evaluate(el => { el.scrollTop = el.scrollHeight })
  await reader.getByText('交付记录全文结束', { exact: true }).waitFor()
  const scrollTop = await reader.evaluate(el => el.scrollTop)
  await tab('项目设置')
  assert.equal(await reader.isVisible(), false)
  await tab('成果与进度')
  assert.equal(await reader.evaluate(el => el.scrollTop), scrollTop, '切换分页保留文档和阅读位置')
  await tab('当前推进')
  await page.getByRole('tab', { name: '当前推进', exact: true }).press('ArrowRight')
  assert.equal(await page.getByRole('tab', { name: '成果与进度', exact: true }).getAttribute('aria-selected'), 'true')
  checks.push('项目Tab一次显示一个面板，支持键盘切换，保留文档和阅读位置；长交付正文限高滚动且全文可达')
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.getByRole('heading', { name: /^项目资料与进度/ }).scrollIntoViewIfNeeded()
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '项目资料不能横向溢出')
    assert.equal(await page.locator('.collab-document-markdown').evaluate(el => getComputedStyle(el).fontSize), '16px')
    await page.screenshot({ path: path.join(output, 'project-knowledge-' + width + '.png') })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  checks.push('项目资料支持当前进度、全文搜索、修订冲突保留草稿、历史版本只读、导出真实Markdown；桌面和320/390窄屏正文16px且无横向溢出')
  await page.getByRole('button', { name: '设置工作目录', exact: true }).click()
  assert.equal(await page.getByRole('button', { name: '绑定所选目录', exact: true }).isDisabled(), true, '未选择工作空间时不能绑定默认位置')
  await page.getByRole('combobox', { name: '项目工作空间', exact: true }).click()
  await page.getByRole('option', { name: '研发工作空间 · SSH', exact: true }).click()
  await page.getByRole('button', { name: 'projects', exact: true }).click()
  await page.getByRole('button', { name: 'existing-app', exact: true }).waitFor()
  await page.getByRole('checkbox', { name: '在所选位置新建独立目录' }).check()
  await page.getByLabel('新目录名称', { exact: true }).fill('new-collaboration')
  await page.getByRole('button', { name: '创建并绑定目录', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  assert.deepEqual(bindingRequests[0], { projectId: 'p1', workspaceId: 'w1', directory: 'projects', create: true, name: 'new-collaboration' })
  await page.locator('.collab-workspace-status').getByText('研发工作空间 / projects/new-collaboration', { exact: true }).waitFor()
  checks.push('项目必须显式选择工作空间与目录，可新建独立目录，未绑定不会自动选择已有项目')
  assert.equal(await page.locator('.collab-project-tasks .collab-task-row').count(), 1, '议题不能混进项目任务列表')
  await tab('项目设置')
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByLabel('工作方向', { exact: true }).fill('让三位成员协作推进可联机的塔防游戏，持续核对真实运行结果。')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  assert.match(projects[0].note, /持续核对/)
  await page.getByRole('button', { name: '调整成员', exact: true }).click()
  await page.getByRole('checkbox').last().uncheck()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  assert.deepEqual(projectMembers.p1, ['a', 'b'])
  await page.screenshot({ path: path.join(output, 'project-desktop.png') })
  checks.push('起始页、项目页独立导航，项目方向与成员可持久化，议题不混入任务列表')

  await tab('当前推进')
  await page.locator('.collab-task-row').filter({ hasText: '第一轮方案与实现' }).click()
  await page.getByText('正在载入待办…', { exact: true }).waitFor()
  gateRelease()
  await page.locator('.collab-todo-main').filter({ hasText: '核对联机协议' }).waitFor()
  await page.locator('.collab-plan summary').click()
  await page.locator('.collab-plan').getByText(/保留联机行为，构建和断线重连测试通过/).waitFor()
  await page.locator('.collab-plan').getByText(/本次仅核对联机协议/).waitFor()
  await page.locator('.collab-plan').getByText(/不扩张到完整游戏实现/).waitFor()
  await page.screenshot({ path: path.join(output, 'plan-desktop.png') })
  await page.locator('.collab-plan summary').click()
  await page.getByRole('button', { name: '进入议题：联机协议的重连策略如何确定？', exact: true }).waitFor()
  const mainBox = await page.locator('.collab-main').boundingBox(), todoBox = await page.locator('.collab-todos').boundingBox()
  assert.ok(todoBox.x >= mainBox.x + mainBox.width - 1)
  assert.ok(await page.locator('[id=collab-panel-discussion] [data-index]').count() < 35)
  await page.getByRole('button', { name: '完整记录', exact: true }).click()
  assert.ok(await page.locator('[id=collab-panel-discussion] [data-index]').count() < 35, '完整记录也必须保持虚拟化')
  await page.getByRole('button', { name: '连续阅读', exact: true }).click()
  const rally = page.locator('.collab-rally-notice')
  await rally.waitFor()
  const waitReportLayout = () => page.waitForFunction(() => {
    const row = document.querySelector('.collab-rally-notice')?.closest('[data-index]')
    const previous = row?.previousElementSibling
    return row && previous && row.getBoundingClientRect().top >= previous.getBoundingClientRect().bottom - 1
  })
  await waitReportLayout()
  assert.equal(await rally.locator('.collab-rally-status').innerText(), reportStatus)
  assert.ok((await rally.boundingBox()).height < 100, '长报告默认只占简短状态与展开入口')
  assert.equal(await rally.locator('p').isVisible(), false)
  await page.screenshot({ path: path.join(output, 'report-collapsed.png') })
  await rally.locator('summary').click()
  assert.equal(await rally.locator('p').innerText(), longReport, '展开必须保留完整原始报告')
  assert.equal(await rally.locator('p').evaluate(el => getComputedStyle(el).fontSize), '16px')
  await rally.locator('summary').click()
  await page.setViewportSize({ width: 390, height: 844 })
  await waitReportLayout()
  assert.ok((await rally.locator('.collab-rally-status').boundingBox()).width <= 390)
  await rally.locator('summary').click()
  assert.equal(await rally.locator('p').evaluate(el => el.scrollWidth <= el.clientWidth), true, '窄屏展开报告不能横向溢出')
  await rally.locator('summary').click()
  await page.screenshot({ path: path.join(output, 'report-mobile.png') })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await waitReportLayout()
  checks.push('历史长报告只在徽章中显示转交状态，完整原文可展开收起，正文16px且390窄屏无横向溢出')
  await page.screenshot({ path: path.join(output, 'session-desktop.png') })
  await page.locator('textarea').fill('@')
  const mentionList = page.getByRole('listbox', { name: '提及成员' })
  await mentionList.waitFor()
  assert.deepEqual(await mentionList.getByRole('option').locator('span.flex-1').allTextContents(), ['规划成员', '研究成员', '执行成员'])
  await page.locator('textarea').fill('主任务独立草稿')
  const workIntent = page.getByRole('checkbox', { name: '调整任务', exact: false })
  assert.equal(await workIntent.isChecked(), false)
  await workIntent.check()
  await page.getByText('停止当前执行，按本条要求重新规划', { exact: true }).waitFor()
  await page.getByRole('button', { name: '进入议题：联机协议的重连策略如何确定？', exact: true }).click()
  await page.getByText('议题内部的独立讨论内容', { exact: true }).waitFor()
  assert.equal(await page.locator('textarea').inputValue(), '')
  await page.locator('textarea').fill('@')
  await mentionList.waitFor()
  assert.deepEqual(await mentionList.getByRole('option').locator('span.flex-1').allTextContents(), ['规划成员', '研究成员'])
  await page.locator('textarea').fill('@执行')
  assert.equal(await page.getByRole('listbox', { name: '提及成员' }).count(), 0, '搜索不得带出议题外成员')
  await page.locator('textarea').fill('@研究')
  await page.locator('textarea').press('Enter')
  assert.equal(await page.locator('textarea').inputValue(), '@研究成员 ')
  await page.locator('textarea').fill('议题独立草稿')
  await page.screenshot({ path: path.join(output, 'issue-desktop.png') })
  await page.getByRole('button', { name: '返回主会话', exact: true }).click()
  await page.getByRole('button', { name: '进入议题：联机协议的重连策略如何确定？', exact: true }).waitFor()
  assert.equal(await workIntent.isChecked(), false, '任务调整只作用于当前输入，不跨会话携带')
  assert.equal(await page.locator('textarea').inputValue(), '主任务独立草稿')
  assert.equal(await page.getByText('议题内部的独立讨论内容', { exact: true }).count(), 0)
  await page.getByRole('button', { name: '进入议题：联机协议的重连策略如何确定？', exact: true }).click()
  await page.getByText('议题内部的独立讨论内容', { exact: true }).waitFor()
  assert.equal(await page.locator('textarea').inputValue(), '议题独立草稿')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('button', { name: '停止协作', exact: true }).waitFor()
  assert.equal(sent[0].sessionId, 'issue1')
  assert.equal(sent[0].redirectWork, false)
  await tab('协作图')
  await page.locator('.wf-canvas').waitFor()
  assert.equal(await page.getByRole('button', { name: '发送', exact: true }).count(), 0)
  releaseStream()
  await tab('消息')
  await page.getByRole('button', { name: '停止协作', exact: true }).waitFor({ state: 'detached' })
  await page.getByRole('button', { name: '提交结论', exact: true }).click()
  await page.getByLabel('结论与后续行动').fill('采用短期重连令牌，并在联机验证中覆盖断线恢复。')
  await page.getByRole('dialog').getByRole('button', { name: '提交结论', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.getByRole('button', { name: '进入议题：联机协议的重连策略如何确定？', exact: true }).waitFor()
  assert.equal(issues[0].status, 'resolved')
  assert.equal(await page.getByText('议题内部的独立讨论内容', { exact: true }).count(), 0)
  checks.push('双栏会话与虚拟长消息正常；议题消息、发送目标和草稿隔离；切图不中断发言；结论回到主任务')

  await page.locator('.collab-todo-main').filter({ hasText: '核对联机协议' }).click()
  const review = page.getByRole('dialog', { name: '工作详情与执行记录', exact: true })
  await review.getByRole('heading', { name: '阶段性成果', exact: true }).waitFor()
  assert.equal(await review.getByRole('tabpanel', { name: '成果概览', exact: true }).count(), 1)
  assert.equal(await review.getByText('检查意见（0 项待解决）', { exact: true }).count(), 0)
  assert.equal(await review.locator('.work-review-prose').first().evaluate(el => getComputedStyle(el).fontSize), '16px')
  await page.screenshot({ path: path.join(output, 'work-review-overview-desktop.png') })
  await review.getByRole('tab', { name: '验收与问题', exact: true }).click()
  await page.getByText('用实测证据确定重连策略', { exact: true }).waitFor()
  await page.getByText('检查意见（0 项待解决）', { exact: true }).waitFor()
  await page.getByText('一般建议', { exact: true }).waitFor()
  assert.equal(await page.getByText('补齐断线后的序号核对', { exact: true }).evaluate(el => getComputedStyle(el).fontSize), '16px')
  await page.getByText('已交回主控诊断', { exact: true }).waitFor()
  await page.screenshot({ path: path.join(output, 'task-quality-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.locator('.collab-detail').evaluate(el => el.scrollWidth <= el.clientWidth), true)
  await page.screenshot({ path: path.join(output, 'task-quality-mobile.png') })
  await page.setViewportSize({ width: 1440, height: 1000 })
  checks.push('工作详情展示验收标准、收工条件、问题解决证据与主控诊断，正文16px且窄屏无横向溢出')
  await review.getByRole('tab', { name: /^执行记录/ }).click()
  await review.getByRole('heading', { name: '协议核对结果', exact: true }).waitFor()
  assert.equal(await review.getByText('分析记录只在单独页签中展示。', { exact: true }).count(), 0)
  const runDocument = review.getByRole('tabpanel', { name: '产出与错误', exact: true })
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.reviewClipboard = text } } }))
  await runDocument.getByRole('button', { name: '复制原文', exact: true }).click()
  assert.equal(await page.evaluate(() => window.reviewClipboard), runReport)
  await runDocument.getByRole('button', { name: '查看原文', exact: true }).click()
  assert.equal(await runDocument.locator('.work-review-raw').textContent(), runReport)
  await runDocument.getByRole('button', { name: '阅读排版', exact: true }).click()
  await runDocument.evaluate(el => { el.scrollTop = 850 })
  const scrollBefore = await runDocument.evaluate(el => el.scrollTop)
  await review.getByRole('button', { name: '刷新', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.work-runs-toolbar button')?.textContent === '刷新')
  assert.equal(await runDocument.evaluate(el => el.scrollTop), scrollBefore, '刷新不能把长报告跳回顶部')
  await review.getByRole('tab', { name: /^工具调用/ }).click()
  await review.locator('.work-tool-list summary').filter({ hasText: 'verify_protocol' }).click()
  await review.locator('pre[aria-label="返回内容"]').waitFor()
  assert.match(await review.locator('.work-tool-list details[open]').innerText(), /"ok": false/)
  assert.match(await review.locator('.work-tool-list details[open]').innerText(), /部署版本尚未确认/)
  await page.screenshot({ path: path.join(output, 'work-review-tools-desktop.png') })
  await review.getByRole('tab', { name: '分析记录', exact: true }).click()
  await review.getByText('分析记录只在单独页签中展示。', { exact: false }).waitFor()
  await review.getByRole('tab', { name: '产出与错误', exact: true }).click()
  await review.getByRole('button', { name: '全屏阅读', exact: true }).click()
  assert.equal(await review.evaluate(el => Math.round(el.getBoundingClientRect().height)), 984)
  await page.screenshot({ path: path.join(output, 'work-review-fullscreen.png') })
  await review.getByRole('button', { name: '退出全屏阅读', exact: true }).click()
  await review.getByRole('button').filter({ hasText: '#99' }).click()
  await runDocument.getByText('连接服务失败：version-mismatch', { exact: true }).waitFor()
  assert.match(await review.locator('.work-run-reader-head').innerText(), /时间未记录/)
  reviewRuns.unshift({ ...reviewRuns[3], id: 101, output: '后台新增记录' })
  await review.getByRole('button', { name: '刷新', exact: true }).click()
  await review.getByRole('button').filter({ hasText: '#101' }).waitFor()
  assert.match(await review.locator('.work-run-reader-head').innerText(), /执行 #99/, '新增记录不抢走正在审阅的旧记录')
  failRuns = true
  await review.getByRole('button', { name: '刷新', exact: true }).click()
  await review.getByRole('alert').waitFor()
  assert.match(await review.locator('.work-run-reader-head').innerText(), /执行 #99/)
  failRuns = false
  await review.getByRole('button', { name: '重试', exact: true }).click()
  await review.getByRole('alert').waitFor({ state: 'detached' })
  await review.getByRole('searchbox', { name: '搜索执行记录', exact: true }).fill('验证返回的内容')
  assert.equal(await review.locator('.work-run-item').count(), 1)
  await review.getByRole('searchbox', { name: '搜索执行记录', exact: true }).fill('没有这条记录')
  assert.equal(await review.locator('.work-run-item').count(), 0)
  await review.getByRole('button', { name: '清除筛选', exact: true }).click()
  await review.getByRole('combobox', { name: '筛选执行状态', exact: true }).selectOption('failed')
  assert.equal(await review.locator('.work-run-item').count(), 1)
  await review.getByRole('combobox', { name: '筛选执行状态', exact: true }).selectOption('')
  await review.getByRole('button', { name: '载入更早记录', exact: true }).click()
  await page.waitForFunction(() => document.querySelectorAll('.work-run-item').length === 26)
  await page.setViewportSize({ width: 390, height: 844 })
  await review.getByRole('combobox', { name: '选择执行记录', exact: true }).selectOption('100')
  await review.getByRole('heading', { name: '协议核对结果', exact: true }).waitFor()
  assert.ok(await review.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
  assert.ok((await runDocument.boundingBox()).height > 100, '窄屏仍有可阅读的正文区域')
  await page.screenshot({ path: path.join(output, 'work-review-runs-mobile.png') })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  assert.ok(await review.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
  await review.getByRole('button', { name: '全屏阅读', exact: true }).click()
  await page.screenshot({ path: path.join(output, 'work-review-200pct.png') })
  await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  await review.getByRole('button', { name: '退出全屏阅读', exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  await review.getByText('长报告末尾：实际联机尚未完成。', { exact: true }).scrollIntoViewIfNeeded()
  assert.ok(await review.getByText('长报告末尾：实际联机尚未完成。', { exact: true }).isVisible(), '窄屏200%文字仍能抵达全文末尾')
  assert.ok(await review.evaluate(el => el.scrollWidth <= el.clientWidth + 1))
  await page.screenshot({ path: path.join(output, 'work-review-mobile-200pct.png') })
  await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await review.getByRole('tab', { name: '成果概览', exact: true }).focus()
  await page.keyboard.press('ArrowRight')
  assert.equal(await review.getByRole('tab', { name: '验收与问题', exact: true }).getAttribute('aria-selected'), 'true')
  checks.push('审阅窗口：长报告排版与原文复制、按调用 ID 展示工具结果、分析独立分页、全屏、旧记录选择和滚动在刷新后保留、失败重试、搜索筛选和更早记录、移动端选择与200%文字通过')
  await page.getByRole('button', { name: '工作操作', exact: true }).click()
  patchFails = true; await page.getByRole('button', { name: '立刻检查', exact: true }).click(); await page.locator('[data-todo-menu] [role=alert]').waitFor()
  patchFails = false; await page.getByRole('button', { name: '立刻检查', exact: true }).click(); await page.locator('[data-todo-menu]').waitFor({ state: 'detached' })
  await page.keyboard.press('Escape')
  await tab('协作图')
  await page.getByRole('checkbox', { name: '展开已结束工作', exact: true }).check()
  assert.equal(await page.locator('[data-node^="todo:"]').count(), 57)
  const canvas = page.locator('.wf-canvas')
  for (let i = 0; i < 8; i++) {
    const b = await canvas.boundingBox()
    await page.mouse.move(b.x + 30, b.y + 30); await page.mouse.down(); await page.mouse.move(b.x + 90, b.y + 80, { steps: 5 }); await page.mouse.up()
    assert.deepEqual(errors, [])
  }
  await page.screenshot({ path: path.join(output, 'graph-desktop.png') })
  await page.getByRole('checkbox', { name: '展开已结束工作', exact: true }).uncheck()
  await tab('消息')
  checks.push('保留待办干预与错误重试，57 项历史图拖拽正常')

  await page.getByRole('button', { name: '发起议题', exact: true }).click()
  await page.getByLabel('需要一起解决什么问题？').fill('验证失败后由谁处理返工？')
  await page.getByRole('checkbox').last().uncheck()
  await page.getByRole('button', { name: '创建并讨论', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.getByRole('heading', { name: '验证失败后由谁处理返工？', exact: true }).waitFor()
  assert.equal(sent.at(-1).sessionId, issues.at(-1).sessionId)
  assert.deepEqual(sessions.at(-1).memberIds, ['a', 'b'])
  await page.getByRole('button', { name: '返回主会话', exact: true }).click()
  await page.locator('.collab-session-header nav').getByRole('button', { name: '多人塔防', exact: true }).click()
  await page.locator('.collab-project-tasks .collab-task-row').first().waitFor()
  assert.equal(await page.locator('.collab-project-tasks .collab-task-row').count(), 1)
  await page.getByRole('button', { name: '新建会话', exact: true }).click()
  await page.getByLabel('这次希望推进什么？').fill('第二阶段：验证真实联机')
  await page.getByRole('combobox', { name: '主控成员' }).click()
  await page.getByRole('option', { name: '研究成员', exact: true }).click()
  assert.equal(await page.getByRole('checkbox', { checked: true }).count(), 2, '默认采用项目成员')
  failCreate = true
  await page.getByRole('button', { name: '创建会话', exact: true }).click()
  await page.getByRole('dialog').getByRole('alert').waitFor()
  assert.equal(await page.getByLabel('这次希望推进什么？').inputValue(), '第二阶段：验证真实联机')
  failCreate = false; await page.getByRole('button', { name: '创建会话', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.getByRole('heading', { name: '第二阶段：验证真实联机', exact: true }).waitFor()
  await page.locator('.collab-plan').getByText('主控 · 研究成员', { exact: true }).waitFor()
  checks.push('可创建真实的定向议题和项目内任务；失败保留表单；项目成员只影响后续任务')

  todos.find(t => t.id === 't3').status = 'reviewing'
  todos.find(t => t.id === 't4').status = 'checking'
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
  await page.reload()
  await page.locator('.collab-todos').getByText(/待检查/).waitFor()
  await page.locator('.collab-todos').getByText(/检查中/).waitFor()
  checks.push('待检查与检查中仍显示在主任务 TODO 内，检查状态使用中文标签')

  for (const theme of ['old', 'astrolabe', 'endfield', 'bluearchive']) {
    for (const width of [1440, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.evaluate(theme => localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: theme, schemeMode: theme === 'endfield' ? 'dark' : 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })), theme)
      await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
      await page.reload(); await page.getByRole('button', { name: '发送', exact: true }).waitFor()
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, theme + '/' + width + ': 不能横向溢出')
      const send = await page.getByRole('button', { name: '发送', exact: true }).boundingBox()
      assert.ok(send.y + send.height <= 1001 && send.y >= 0, theme + '/' + width + ': 输入操作在视口内')
      if (width < 760) {
        await page.getByRole('button', { name: /^工作 / }).click()
        await page.locator('.collab-todos').waitFor({ state: 'visible' })
        await page.keyboard.press('Escape')
        await page.locator('.collab-todos').waitFor({ state: 'hidden' })
      }
      await page.screenshot({ path: path.join(output, theme + '-' + width + '.png') })
      if (width < 760) await page.getByRole('button', { name: /^工作 / }).click()
      await page.locator('.collab-todo-main').filter({ hasText: '核对联机协议' }).click()
      const themedReview = page.getByRole('dialog', { name: '工作详情与执行记录', exact: true })
      await themedReview.getByRole('heading', { name: '阶段性成果', exact: true }).waitFor()
      assert.ok(await themedReview.evaluate(el => el.scrollWidth <= el.clientWidth + 1), `${theme}/${width}: 审阅窗口无横向溢出`)
      const reviewContrast = await themedReview.locator('.work-review-prose').first().evaluate(el => {
        const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1
        const ctx = canvas.getContext('2d')
        const rgba = value => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data].map((v, i) => i === 3 ? v / 255 : v) }
        const blend = (over, under) => over.slice(0, 3).map((v, i) => v * over[3] + under[i] * (1 - over[3]))
        const ancestors = []; for (let p = el; p; p = p.parentElement) ancestors.unshift(p)
        let bg = [255, 255, 255]
        for (const p of ancestors) bg = blend(rgba(getComputedStyle(p).backgroundColor), bg)
        const fg = blend(rgba(getComputedStyle(el).color), bg)
        const lum = rgb => rgb.map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4 }).reduce((v, n, i) => v + n * [.2126, .7152, .0722][i], 0)
        return (Math.max(lum(bg), lum(fg)) + .05) / (Math.min(lum(bg), lum(fg)) + .05)
      })
      assert.ok(reviewContrast >= 4.5, `${theme}/${width}: 审阅正文对比度 ${reviewContrast.toFixed(2)}`)
      await themedReview.getByRole('tab', { name: /^执行记录/ }).click()
      await themedReview.getByRole('tab', { name: '产出与错误', exact: true }).waitFor()
      assert.ok(await themedReview.evaluate(el => el.scrollWidth <= el.clientWidth + 1), `${theme}/${width}: 执行记录无横向溢出`)
      await page.screenshot({ path: path.join(output, `work-review-${theme}-${width}.png`) })
      await page.keyboard.press('Escape')
      if (width < 760) await page.keyboard.press('Escape')
    }
  }
  checks.push('四种主题、桌面与 390/320 窄屏均无页面溢出，输入框可见，窄屏 TODO 按需查看；工作审阅与执行记录适配四主题且正文对比度不低于4.5')
  await page.setViewportSize({ width: 720, height: 500 })
  // 半高视口下现有会话标题、页签与输入区总高可超出可用区，验证真实的滚动访问。
  await page.getByRole('button', { name: '发送', exact: true }).scrollIntoViewIfNeeded()
  const compactSend = await page.getByRole('button', { name: '发送', exact: true }).boundingBox()
  assert.ok(compactSend.y >= 0 && compactSend.y + compactSend.height <= 501, `半尺寸逻辑视口仍可滚动访问发送操作: ${JSON.stringify(compactSend)}`)
  await page.goto('http://collaboration.test/#/collaboration?project=p2&task=s1&issue=issue1')
  await page.getByText('这个会话或议题不属于当前位置', { exact: true }).waitFor()
  assert.equal(await page.locator('textarea').count(), 0)
  await page.goto('http://collaboration.test/#/workflow')
  await page.locator('.wf-canvas').waitFor()
  await tab('消息')
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  const sentBeforeRecovery = sent.length
  disconnectStream = true
  await page.getByRole('checkbox', { name: '调整任务', exact: false }).check()
  await page.locator('textarea').fill('请核对断线后的交付范围')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('button', { name: '停止协作', exact: true }).waitFor({ state: 'detached' })
  assert.equal(sent.at(-1).redirectWork, true, '明确选择调整才发送取消旧派工的意图')
  assert.equal(await page.getByRole('checkbox', { name: '调整任务', exact: false }).isChecked(), false)
  checks.push('普通消息默认不调整工作，调整任务显式发送且仅作用于本条消息，切换会话复位')
  failMessages = true
  await page.evaluate(() => window.dispatchEvent(new Event('online')))
  await page.getByText(/讨论记录加载失败/).waitFor()
  const waitingTopic = { id: 'recovery-topic', sessionId: 's1', status: 'waiting', floorPeer: null, states: { 规划成员: 'AWAIT_USER' }, resolveRound: 0 }
  sessionTopics.set('s1', [waitingTopic])
  messages.push(message(1501, '断线期间保存的 Agent 发言：范围核对已完成。'))
  messages.push(message(1502, '规划成员 请用户表态（请确认是否只交付最小版本）', 'event', { kind: 'ask_user', peerId: 'a', peerName: '规划成员' }))
  failMessages = false
  await page.locator('textarea').fill('尚未发送的恢复草稿')
  await page.evaluate(() => window.dispatchEvent(new Event('online')))
  await page.getByText('规划成员 在等你表态', { exact: true }).waitFor()
  await page.getByText('规划成员 请用户表态（请确认是否只交付最小版本）', { exact: true }).waitFor()
  assert.equal(await page.locator('textarea').inputValue(), '尚未发送的恢复草稿')
  assert.equal(sent.length, sentBeforeRecovery + 1, '恢复只补载记录，不重发用户消息')
  await page.goto('http://collaboration.test/#/collaboration?project=p1')
  await page.reload()
  messages.push(message(1503, '离开页面期间保存的后续 Agent 发言。'))
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
  await page.getByText('离开页面期间保存的后续 Agent 发言。', { exact: true }).waitFor()
  await page.getByText('规划成员 在等你表态', { exact: true }).waitFor()
  const child = issues.find(i => i.status === 'open')
  await page.goto(`http://collaboration.test/#/collaboration?project=p1&task=s1&issue=${child.sessionId}`)
  await page.getByText('议题内部的独立讨论内容', { exact: true }).waitFor()
  extraMessages.set(child.sessionId, [message(1600, '议题独立补载的 Agent 发言', 'peer', { sessionId: child.sessionId, peerId: 'b' })])
  await page.getByText('议题独立补载的 Agent 发言', { exact: true }).waitFor()
  assert.equal(sent.length, sentBeforeRecovery + 1)
  checks.push('断流及消息读取失败后可恢复；旧直播状态不覆盖等待表态；重新进入可补齐发言；议题独立轮询；恢复保留草稿且不重发消息')
  const parentBeforeRewind = JSON.stringify(messages)
  extraMessages.get(child.sessionId).push(
    message(1700, '需要改写后重新发送的议题问题', 'user', { sessionId: child.sessionId }),
    message(1701, '回退后应删除的议题回复', 'peer', { sessionId: child.sessionId }))
  sessionTopics.set(child.sessionId, [{ ...waitingTopic, sessionId: child.sessionId }])
  await page.evaluate(() => window.dispatchEvent(new Event('online')))
  await page.getByText('回退后应删除的议题回复', { exact: true }).waitFor()
  await page.locator('textarea').fill('回退失败时保留的草稿')
  failRewind = true
  await page.getByRole('button', { name: '回到这条提问之前', exact: true }).click()
  await page.getByText(/回退失败：/).waitFor()
  assert.equal(await page.locator('textarea').inputValue(), '回退失败时保留的草稿')
  assert.equal(await page.getByText('回退后应删除的议题回复', { exact: true }).count(), 1)
  failRewind = false
  await page.getByRole('button', { name: '回到这条提问之前', exact: true }).click()
  await page.getByText('回退后应删除的议题回复', { exact: true }).waitFor({ state: 'detached' })
  assert.equal(await page.locator('textarea').inputValue(), '需要改写后重新发送的议题问题')
  assert.deepEqual(rewindRequests.at(-1), { id: child.sessionId, messageId: 1700 })
  assert.equal(JSON.stringify(messages), parentBeforeRewind)
  assert.equal(sent.length, sentBeforeRecovery + 1, '回退不会自动发送')
  await page.reload()
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  assert.equal(await page.getByText('规划成员 在等你表态', { exact: true }).count(), 0)
  assert.equal(await page.getByText('回退后应删除的议题回复', { exact: true }).count(), 0)
  checks.push('用户消息可回退并回填草稿；失败保留记录与草稿；议题回退不影响主任务；刷新后不恢复已撤回回复或等待状态')
  sessions.push(session('managed-task', 'p1', '待整理的协作任务'), session('managed-issue', 'p1', '随任务管理的议题'))
  issues.push({ id: 'managed-issue-link', parentSessionId: 'managed-task', sessionId: 'managed-issue', title: '随任务管理的议题', status: 'open', conclusion: null, messageId: 9900, createdAt: now, resolvedAt: null })
  extraMessages.set('managed-task', [message(9900, '发起议题：随任务管理的议题', 'event', { sessionId: 'managed-task', kind: 'issue_open' })])
  await page.goto('http://collaboration.test/#/collaboration?project=p1')
  await page.reload()
  const managed = page.locator('.collab-task-entry').filter({ hasText: '待整理的协作任务' })
  await managed.waitFor()
  failLifecycle = true
  await managed.getByRole('button', { name: '归档会话', exact: true }).click()
  await managed.getByRole('alert').waitFor()
  assert.ok(sessions.some(s => s.id === 'managed-task'))
  failLifecycle = false
  await managed.getByRole('button', { name: '归档会话', exact: true }).click()
  await managed.waitFor({ state: 'detached' })
  await page.getByRole('button', { name: /已归档会话/ }).click()
  await managed.getByRole('button', { name: '恢复会话', exact: true }).waitFor()
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '归档会话列表不能溢出')
    await managed.getByRole('button', { name: '恢复会话', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(output, 'archived-tasks-' + width + '.png') })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await managed.locator('.collab-task-row').click()
  await page.getByText('会话已归档，消息、工作条目和议题仍可查阅。', { exact: true }).waitFor()
  assert.equal(await page.locator('textarea').count(), 0)
  assert.ok(await page.getByRole('button', { name: '发起议题', exact: true }).isDisabled())
  await page.getByRole('button', { name: '进入议题：随任务管理的议题', exact: true }).click()
  assert.equal(await page.locator('textarea').count(), 0, '关联议题随主任务只读')
  await page.locator('.collab-archived-notice').getByRole('button', { name: '恢复会话', exact: true }).click()
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=managed-task')
  await page.getByRole('button', { name: '会话设置与成员', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '归档会话', exact: true }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.reload()
  await page.locator('.collab-archived-notice').waitFor()
  const beforeCancel = lifecycleRequests.length
  page.once('dialog', d => d.dismiss())
  await page.locator('.collab-archived-notice').getByRole('button', { name: '删除会话', exact: true }).click()
  assert.equal(lifecycleRequests.length, beforeCancel)
  failLifecycle = true
  page.once('dialog', d => d.accept())
  await page.locator('.collab-archived-notice').getByRole('button', { name: '删除会话', exact: true }).click()
  await page.locator('.collab-archived-notice').getByRole('alert').waitFor()
  assert.ok(sessions.some(s => s.id === 'managed-issue'))
  failLifecycle = false
  page.once('dialog', d => d.accept())
  await page.locator('.collab-archived-notice').getByRole('button', { name: '删除会话', exact: true }).click()
  await page.getByRole('heading', { name: '多人塔防', exact: true }).waitFor()
  assert.ok(!sessions.some(s => ['managed-task', 'managed-issue'].includes(s.id)))
  assert.ok(sessions.some(s => s.id === 's1'))
  checks.push('项目列表和任务设置支持归档、恢复、确认删除；归档会话及议题只读；失败保留记录；删除后回到项目且其他任务保留；归档列表适配桌面与320/390窄屏')
  const typography = []
  for (const theme of ['old', 'astrolabe', 'endfield', 'bluearchive']) {
    await page.evaluate(theme => localStorage.setItem('minis.theme.v4', JSON.stringify({ v: 4, themeId: theme, schemeMode: theme === 'endfield' ? 'dark' : 'light', override: { seed: {} }, mono: { sidebar: false, hideChrome: false } })), theme)
    await page.goto('http://collaboration.test/#/collaboration?project=p1')
    await page.reload()
    await tab('成果与进度')
    await page.locator('.collab-document-markdown').waitFor()
    for (const width of [1440, 850, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 })
      await page.locator('.collab-project-page').evaluate(el => { el.scrollTop = 0 })
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${theme}/${width}: 项目页无横向溢出`)
      const fonts = await page.evaluate(() => {
        const size = selector => parseFloat(getComputedStyle(document.querySelector(selector)).fontSize)
        return { title: size('.collab-page-heading h1'), description: size('.collab-description'), reading: size('.collab-document-markdown'), search: size('.collab-search input'), metadata: size('.collab-document-row small') }
      })
      assert.ok(fonts.title >= 24 && fonts.title <= 32)
      assert.ok(fonts.description >= (width <= 760 ? 16 : 14))
      assert.equal(fonts.reading, 16); assert.ok(fonts.search >= (width <= 760 ? 16 : 14)); assert.ok(fonts.metadata >= 12)
      typography.push({ theme, width, ...fonts })
      await tab('当前推进')
      assert.equal(await page.getByRole('tabpanel').count(), 1, '项目页只展示当前分页')
      await page.screenshot({ path: path.join(output, `project-audit-${theme}-${width}.png`) })
      await tab('成果与进度')
      if (width <= 850) assert.equal(await page.locator('.collab-document-list').isVisible(), false, '窄屏默认直接显示选中文档，列表按需展开')
      await page.locator('.collab-document-reader').scrollIntoViewIfNeeded()
      await page.screenshot({ path: path.join(output, `knowledge-audit-${theme}-${width}.png`) })
    }
    const contrast = await page.evaluate(() => {
      const context = document.createElement('canvas').getContext('2d')
      const rgba = color => { context.clearRect(0, 0, 1, 1); context.fillStyle = color; context.fillRect(0, 0, 1, 1); return [...context.getImageData(0, 0, 1, 1).data] }
      const blend = (front, back) => { const a = front[3] / 255; return [0, 1, 2].map(i => front[i] * a + back[i] * (1 - a)).concat(255) }
      const luminance = color => color.slice(0, 3).map(v => { const s = v / 255; return s <= .04045 ? s / 12.92 : ((s + .055) / 1.055) ** 2.4 }).reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0)
      return [...document.querySelectorAll('.collab-description,.collab-document-row small,.collab-document-reader .collab-secondary,.collab-knowledge-note,.collab-project-meta small')].filter(el => el.getBoundingClientRect().width > 0).map(el => {
        const chain = []; let parent = el
        while (parent) { chain.push(parent); parent = parent.parentElement }
        let background = [255, 255, 255, 255]
        for (const ancestor of chain.reverse()) background = blend(rgba(getComputedStyle(ancestor).backgroundColor), background)
        const text = blend(rgba(getComputedStyle(el).color), background), a = luminance(text), b = luminance(background)
        return { text: el.textContent.slice(0, 32), ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) }
      })
    })
    for (const item of contrast) assert.ok(item.ratio >= 4.5, `${theme}: ${item.text} 对比度 ${item.ratio.toFixed(2)}`)
    typography.push({ theme, contrast })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.locator('.collab-document-row').filter({ hasText: '存储方案决策' }).click()
  await page.locator('.collab-document-source').waitFor()
  await page.locator('.collab-document-source').click()
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  await page.getByRole('button', { name: '项目资料', exact: true }).click()
  await page.locator('.collab-document-markdown').waitFor()
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  await tab('成果与进度')
  assert.ok(await page.locator('.collab-project-page').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '项目页支持200%文字放大')
  assert.equal(await page.locator('.collab-document-markdown').evaluate(el => getComputedStyle(el).fontSize), '32px')
  assert.equal(await page.getByRole('combobox', { name: '文档历史版本', exact: true }).evaluate(el => getComputedStyle(el).fontSize), '28px')
  await page.screenshot({ path: path.join(output, 'project-200pct-text.png') })
  await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  const spacing = await page.addStyleTag({ content: '.collab-v2 p { line-height:1.5 !important; margin-bottom:2em !important; letter-spacing:.12em !important; word-spacing:.16em !important; }' })
  await page.setViewportSize({ width: 390, height: 1000 })
  assert.ok(await page.locator('.collab-project-page').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '用户自定义文本间距不能使项目页横向溢出')
  await page.screenshot({ path: path.join(output, 'project-custom-text-spacing.png') })
  await spacing.evaluate(el => el.remove())
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%' })
  assert.ok(await page.locator('.collab-v2').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '会话页支持200%文字放大且不横向溢出')
  await page.getByRole('button', { name: '发送', exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(output, 'session-200pct-text.png') })
  await page.setViewportSize({ width: 390, height: 1000 })
  assert.ok(await page.locator('.collab-v2').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '窄屏会话页支持200%文字放大')
  await page.getByRole('button', { name: '发送', exact: true }).scrollIntoViewIfNeeded()
  const zoomedSend = await page.getByRole('button', { name: '发送', exact: true }).boundingBox()
  assert.ok(zoomedSend.y >= 0 && zoomedSend.y + zoomedSend.height <= 1001, '窄屏放大文字后仍能访问发送操作')
  await page.screenshot({ path: path.join(output, 'session-mobile-200pct-text.png') })
  await page.evaluate(() => { document.documentElement.style.fontSize = '' })
  await page.getByRole('button', { name: '项目资料', exact: true }).click()
  await page.locator('.collab-document-markdown').waitFor()
  assert.ok(page.url().includes('section=knowledge'), '会话中可直接回到项目资料')
  checks.push('整体UI审查：项目Tab分页、会话资料入口、窄屏文档选择、四主题1440/850/390/320布局与字号、正文对比度、200%文字和自定义文本间距通过')
  plans[0].phase = 'executing'
  plans[0].assignments = [
    { todoId: 'parallel-ui', title: '完善协作界面中的并行执行状态与可读性', executorId: 'c', reviewerId: 'b', phase: 'executing', version: 2 },
    { todoId: 'parallel-api', title: '独立检查并合入接口层的修改', executorId: 'c', reviewerId: 'a', phase: 'checking', version: 2 },
  ]
  await page.goto('http://collaboration.test/#/collaboration?project=p1&task=s1')
  await page.locator('.collab-plan summary').getByText('执行中 · 2 项在途', { exact: true }).waitFor()
  await page.locator('.collab-plan summary').click()
  await page.locator('.collab-plan-body').getByText(plans[0].assignments[0].title, { exact: true }).waitFor()
  await page.locator('.collab-plan-body').getByText(plans[0].assignments[1].title, { exact: true }).waitFor()
  assert.match(await page.locator('.collab-plan-body').innerText(), /独立检查中/)
  assert.ok(await page.locator('.collab-v2').evaluate(el => el.scrollWidth <= el.clientWidth + 1), '390窄屏并行派工无横向溢出')
  await page.screenshot({ path: path.join(output, 'parallel-assignments-mobile.png') })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: path.join(output, 'parallel-assignments-desktop.png') })
  checks.push('并行派工显示本批在途数、逐项执行与独立检查、同一编码成员的多个实例；390窄屏保持可读')
  await fs.writeFile(path.join(output, 'ui-typography-audit.json'), JSON.stringify(typography, null, 2))
  assert.deepEqual(errors, [])
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify({ checks, errors, requests: requests.length }, null, 2))
  console.log(JSON.stringify({ checks, errors, output }, null, 2))
} finally { await browser.close() }
