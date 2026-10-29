import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

function moduleUrl(relative, rewrite = source => source) {
  const source = readFileSync(new URL(relative, import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
  return `data:text/javascript;base64,${Buffer.from(rewrite(code)).toString('base64')}`
}
const navUrl = moduleUrl('../src/shell/nav.ts')
const { NAV } = await import(navUrl)
const { routeFromHash, isKnownRoute } = await import(moduleUrl('../src/shell/routes.ts', source => source.replace("'./nav'", `'${navUrl}'`)))
const { currentAttemptText } = await import(moduleUrl('../src/pages/coding/state.ts'))
const { codingTimeline, toolPresentation } = await import(moduleUrl('../src/pages/coding/timeline.ts'))
const { nextSelectValue } = await import(moduleUrl('../src/components/selectModel.ts'))
const { readCodingProject, rememberCodingProject, resolveCodingProject } = await import(moduleUrl('../src/pages/coding/projectSession.ts'))
const { sortAgentPeers } = await import(moduleUrl('../src/api/agentRuntime.ts'))
const colorUrl = moduleUrl('../src/theme/color.ts')
const { contrast } = await import(colorUrl)
const { terminalTheme } = await import(moduleUrl('../src/components/terminal/terminalTheme.ts', source => source.replace("'../../theme/color'", `'${colorUrl}'`)))

test('terminal colors follow light, dark and custom theme tokens with readable ANSI text', () => {
  for (const [bg, fg, accent] of [['#ffffff', '#0f0f0f', '#0f0f0f'], ['#101010', '#ededed', '#ededed'], ['#ece3c4', '#303a35', '#a78632']]) {
    const theme = terminalTheme({ '--c-bg': bg, '--c-fg': fg, '--c-accent': accent, '--c-muted': '#8b8f96', '--c-danger': '#ed4444', '--c-ok': '#22cc88', '--c-warn': '#ddaa22' })
    assert.equal(theme.background, bg); assert.equal(theme.foreground, fg); assert.equal(theme.cursorAccent, bg)
    assert.notEqual(theme.black, theme.white)
    for (const key of ['cursor', 'red', 'green', 'yellow', 'blue', 'cyan', 'magenta', 'brightBlack']) assert.ok(contrast(theme[key], bg) >= 4.49, key)
  }
})

test('agent roster pins default then coding agent regardless of sort values or display names', () => {
  const peers = [
    { id: 'custom-b', name: '成员乙', builtin: false, sort: -100 },
    { id: 'builtin-coding', name: '重命名的编程 Agent', builtin: true, sort: -1000 },
    { id: 'custom-a', name: '成员甲', builtin: false, sort: -200 },
    { id: 'builtin', name: '重命名的默认 Agent', builtin: true, sort: 9999 },
    { id: 'custom-c', name: '成员丙', builtin: false, sort: -100 },
  ]
  const original = peers.map(peer => peer.id)
  assert.deepEqual(sortAgentPeers(peers).map(peer => peer.id), ['builtin', 'builtin-coding', 'custom-a', 'custom-b', 'custom-c'])
  assert.deepEqual(peers.map(peer => peer.id), original)
})

test('project restoration yields to explicit project, task, directory and start links', () => {
  const runs = [{ id: 'r1', repositoryId: 'p1' }]
  assert.equal(resolveCodingProject(new URLSearchParams(), runs, 'recent'), 'recent')
  assert.equal(resolveCodingProject(new URLSearchParams('project=chosen&run=r1'), runs, 'recent'), 'chosen')
  assert.equal(resolveCodingProject(new URLSearchParams('run=r1'), runs, 'recent'), 'p1')
  for (const query of ['run=missing', 'project=', 'workspace=w1&directory=src', 'directory=src', 'start=1']) {
    assert.equal(resolveCodingProject(new URLSearchParams(query), runs, 'recent'), '', query)
  }
})

test('project cookie is encoded, session-only and resilient to malformed or blocked cookies', () => {
  const previousDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
  const previousLocation = Object.getOwnPropertyDescriptor(globalThis, 'location')
  let cookie = 'other=value; minis.coding.project=project%2F%E4%B8%AD%E6%96%87%3B%3D'
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { get cookie() { return cookie }, set cookie(value) { cookie = value } } })
  Object.defineProperty(globalThis, 'location', { configurable: true, value: { protocol: 'https:' } })
  try {
    assert.equal(readCodingProject(), 'project/中文;=')
    rememberCodingProject('project/中文;=')
    assert.match(cookie, /^minis\.coding\.project=project%2F%E4%B8%AD%E6%96%87%3B%3D;/)
    assert.match(cookie, /Path=\/; SameSite=Lax; Secure/)
    assert.doesNotMatch(cookie, /Expires|Max-Age/i)
    rememberCodingProject('')
    assert.match(cookie, /Max-Age=0/)
    cookie = 'minis.coding.project=%invalid'
    assert.equal(readCodingProject(), '')
    Object.defineProperty(globalThis, 'document', { configurable: true, value: { get cookie() { throw new Error('blocked') }, set cookie(_) { throw new Error('blocked') } } })
    assert.equal(readCodingProject(), '')
    assert.doesNotThrow(() => rememberCodingProject('p1'))
  } finally {
    if (previousDocument) Object.defineProperty(globalThis, 'document', previousDocument)
    else delete globalThis.document
    if (previousLocation) Object.defineProperty(globalThis, 'location', previousLocation)
    else delete globalThis.location
  }
})

test('dropdown keyboard navigation skips unavailable project sources and workspaces', () => {
  const options = [{ value: 'origin', label: '项目目录' }, { value: 'copy', label: '任务副本', disabled: true }, { value: 'other', label: '可用工作空间' }]
  assert.equal(nextSelectValue(options, 'origin', 'ArrowDown'), 'other')
  assert.equal(nextSelectValue(options, 'other', 'ArrowUp'), 'origin')
  assert.equal(nextSelectValue(options, 'copy', 'ArrowDown'), 'origin')
  assert.equal(nextSelectValue(options, 'origin', 'End'), 'other')
  assert.equal(nextSelectValue([{ value: 'copy', label: '不可用', disabled: true }], '', 'ArrowDown'), '')
})

test('every navigation destination is recognized by the application 404 guard', () => {
  for (const item of NAV) assert.equal(isKnownRoute(item.path), true, item.label)
})
test('coding landing, project, workspace picker and task links share a valid app route', () => {
  for (const hash of ['#/coding', '#coding', '#/coding?project=p1', '#/coding?project=p1&run=r1', '#/coding?workspace=w1&directory=src%2Fapp']) {
    assert.equal(routeFromHash(hash), '/coding')
    assert.equal(isKnownRoute(routeFromHash(hash)), true)
  }
})
test('hidden pages and valid details work while missing pages keep their 404', () => {
  for (const route of ['/search', '/subconscious', '/article/a%20b', '/snapshots/s1', '/multi-agent/new']) assert.equal(isKnownRoute(route), true)
  for (const route of ['/missing', '/coding/missing', '/article/', '/snapshots/a/b']) assert.equal(isKnownRoute(route), false)
  assert.equal(routeFromHash(''), '/')
})
const row = (id, type, payload) => ({ id, type, runId: 'r1', payload: JSON.stringify(payload), createdAt: 0 })
test('continuing a task does not reuse prior attempt text as its current answer', () => {
  const events = [row(1, 'message.delta', { text: 'previous answer' }), row(2, 'run.finished', { attempt: 1 }), row(3, 'message.delta', { text: 'new ' }), row(4, 'message.delta', { text: 'answer' })]
  assert.equal(currentAttemptText(events, 2), 'new answer')
  assert.equal(currentAttemptText(events.slice(0, 2), 2), '')
})
test('event replay is ordered and malformed audit payloads do not break the page', () => {
  const events = [row(8, 'message.delta', { text: 'B' }), row(5, 'run.finished', { attempt: 1 }), { ...row(6, 'message.delta', {}), payload: 'broken-json' }, row(7, 'message.delta', { text: 'A' }), row(9, 'run.finished', { attempt: 2 })]
  assert.equal(currentAttemptText(events, 2), 'AB')
  assert.deepEqual(events.map(event => event.id), [8, 5, 6, 7, 9])
})
test('reasoning replay replaces deltas with snapshots and separates attempts and rounds', () => {
  const events = [row(1, 'reasoning.delta', { attempt: 1, round: 0, text: '先检查' }), row(2, 'reasoning.delta', { attempt: 1, round: 0, text: '入口' }), row(3, 'reasoning.finished', { attempt: 1, round: 0, text: '先检查入口' }), row(4, 'reasoning.finished', { attempt: 1, round: 1, text: '继续搜索' }), row(5, 'run.finished', { attempt: 1 }), row(6, 'reasoning.delta', { attempt: 2, round: 0, text: '新的思考' })]
  const thoughts = codingTimeline([...events, events[0]]).filter(item => item.kind === 'reasoning')
  assert.deepEqual(thoughts.map(item => [item.attempt, item.round, item.text, item.finished]), [[1, 0, '先检查入口', true], [1, 1, '继续搜索', true], [2, 0, '新的思考', false]])
})
test('tool starts are visible and a finish updates the same operation while keeping its arguments', () => {
  const start = row(1, 'tool.started', { operationId: 10, tool: 'read_file', args: JSON.stringify({ path: 'src/App.tsx', startLine: 12, lineCount: 20 }) })
  const first = codingTimeline([start])[0]
  assert.equal(first.status, 'RUNNING'); assert.match(toolPresentation(first.tool, first.args).title, /src\/App\.tsx/)
  assert.equal(toolPresentation(first.tool, first.args).detail, '请求第 12–31 行')
  const done = codingTimeline([row(2, 'tool.finished', { operationId: 10, tool: 'read_file', status: 'DONE', result: 'code' }), start])
  assert.equal(done.length, 1); assert.equal(done[0].status, 'DONE'); assert.equal(done[0].args.path, 'src/App.tsx')
})
test('search summaries show the actual file pattern, query and entire-project default', () => {
  assert.deepEqual(toolPresentation('search_text', { glob: 'backend/src/**', query: 'class CodingFacade' }), { title: '搜索项目 · backend/src/**', detail: '关键词：class CodingFacade · 范围：匹配该文件模式的位置' })
  assert.match(toolPresentation('search_text', { query: 'TODO' }).detail, /整个项目/)
  assert.match(toolPresentation('list_files', { directory: 'src/services' }).title, /src\/services/)
})
test('legacy finished-only events and malformed arguments remain reviewable', () => {
  const items = codingTimeline([row(1, 'tool.finished', { tool: 'search_text', status: 'FAILED', args: 'broken-json', result: 'error' }), { ...row(2, 'reasoning.delta', {}), payload: 'broken-json' }])
  assert.equal(items.length, 1); assert.equal(items[0].status, 'FAILED'); assert.deepEqual(items[0].args, {})
})
test('child reasoning and colliding operation IDs stay separate from the main conversation', () => {
  const events = [row(1, 'message.delta', { text: '主会话' }), row(2, 'message.delta', { executionId: 'child-1', executionAttempt: 1, text: '子 Agent 文本' }), row(3, 'reasoning.delta', { attempt: 1, round: 0, text: '主思考' }), row(4, 'reasoning.delta', { executionId: 'child-1', executionAttempt: 1, round: 0, text: '子思考' }), row(5, 'tool.started', { operationId: 1, tool: 'read_file', args: '{"path":"main.ts"}' }), row(6, 'tool.started', { executionId: 'child-1', executionAttempt: 1, operationId: 1, tool: 'read_file', args: '{"path":"child.ts"}' })]
  assert.equal(currentAttemptText(events, 1), '主会话')
  const items = codingTimeline(events)
  assert.deepEqual(items.filter(item => item.kind === 'reasoning').map(item => item.text), ['主思考', '子思考'])
  assert.deepEqual(items.filter(item => item.kind === 'tool').map(item => item.args.path), ['main.ts', 'child.ts'])
})

test('model retries reset only the failed request text and reasoning for the matching execution', () => {
  const events = [row(1, 'message.delta', { attempt: 1, round: 0, text: 'earlier ' }), row(2, 'message.delta', { attempt: 1, round: 1, text: 'discard' }), row(3, 'reasoning.delta', { attempt: 1, round: 1, text: 'old thought' }), row(4, 'message.delta', { executionId: 'child-1', executionAttempt: 1, round: 1, text: 'child discard' }), row(5, 'model.retry', { attempt: 1, round: 1, retry: 1, maximum: 3 }), row(6, 'message.delta', { attempt: 1, round: 1, text: 'new answer' }), row(7, 'reasoning.finished', { attempt: 1, round: 1, text: 'new thought' }), row(8, 'model.retry', { executionId: 'child-1', executionAttempt: 1, round: 1, retry: 1, maximum: 3 }), row(9, 'message.delta', { executionId: 'child-1', executionAttempt: 1, round: 1, text: 'child answer' })]
  assert.equal(currentAttemptText([...events, events[5]], 1), 'earlier new answer')
  assert.equal(currentAttemptText(events, 1, 'child-1'), 'child answer')
  const timeline = codingTimeline(events)
  assert.deepEqual(timeline.filter(item => item.kind === 'reasoning').map(item => item.text), ['new thought'])
  assert.equal(timeline.filter(item => item.kind === 'event' && item.event.type === 'model.retry').length, 2)
})
