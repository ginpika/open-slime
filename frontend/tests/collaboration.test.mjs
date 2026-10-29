import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
function moduleUrl(relative, rewrite = s => s) {
  const code = ts.transpileModule(readFileSync(new URL(relative, import.meta.url), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
  return 'data:text/javascript;base64,' + Buffer.from(rewrite(code)).toString('base64')
}
const { graphTasks, readingMessages, projectStatus, eventTime, projectMemberIds, rallySummary } = await import(moduleUrl('../src/pages/collaboration/model.ts'))
const { runTools, runMatches, runTime, runDuration } = await import(moduleUrl('../src/pages/collaboration/workReviewModel.ts'))
test('review pairs parallel tool results by call ID and preserves missing or historical evidence honestly', () => {
  const run = { id: 1, status: 'done', trace: [], toolRounds: [
    { role: 'assistant', tool_calls: [{ id: 'a', function: { name: 'read', arguments: '{"path":"协议.ts"}' } }, { id: 'b', function: { name: 'test', arguments: '{}' } }] },
    { role: 'tool', tool_call_id: 'b', content: '测试失败' }, { role: 'tool', tool_call_id: 'a', content: '' },
    { role: 'assistant', tool_calls: [{ id: 'c', function: { name: 'write', arguments: '原始参数' } }] },
    { role: 'tool', tool_call_id: 'orphan', content: '未配对的历史结果' }, null,
  ] }
  const tools = runTools(run)
  assert.deepEqual(tools.map(t => [t.name, t.result]), [['read', ''], ['test', '测试失败'], ['write', undefined], ['工具返回内容', '未配对的历史结果']])
  assert.equal(tools[0].args, '{"path":"协议.ts"}')
  assert.deepEqual(runTools({ trace: [{ type: 'tool', name: 'legacy', args: 'x' }] }), [{ key: 'trace-0', name: 'legacy', args: 'x' }])
  assert.equal(runMatches(run, '测试失败', ''), true)
  assert.equal(runMatches(run, 'READ', 'done'), true)
  assert.equal(runMatches(run, '', 'failed'), false)
  assert.equal(runMatches(run, '不存在', ''), false)
  assert.equal(runTime(NaN), '时间未记录')
  assert.equal(runDuration({ startedAt: 5000, finishedAt: 1000 }, 6000), '耗时未记录')
})
const nav = moduleUrl('../src/shell/nav.ts')
const { NAV, activePathOf, breadcrumbsOf } = await import(nav)
const { isKnownRoute } = await import(moduleUrl('../src/shell/routes.ts', s => s.replace("'./nav'", `'${nav}'`)))
const task = (id, status, dependsOn = []) => ({ id, status, dependsOn })
test('rally summary separates routing from reports without claiming review acceptance', () => {
  const status = '已定向请 主控 回应 执行成员 的报告'
  assert.equal(rallySummary(status + '：条目已提交。' + '检查记录与代码详情。'.repeat(500) + '（只你们沟通一轮，其余成员无需参与）'), status)
  assert.equal(rallySummary(status + '（只你们沟通一轮，其余成员无需参与）'), status)
  assert.equal(rallySummary('全桌已收到 执行成员 的受阻报告，其他成员开始处理：缺少访问权限'), '全桌已收到 执行成员 的受阻报告，其他成员开始处理')
  assert.equal(rallySummary('执行成员 需要你拍板：请选择方案 —— 全桌已暂停，等你发言。'), '执行成员 需要你拍板')
  assert.equal(rallySummary('简短状态'), '简短状态')
  assert.equal(rallySummary('没有约定格式的长报告'.repeat(100)), '协作状态已更新')
  assert.equal(rallySummary('已定向请 ' + '很长的成员名'.repeat(100)), '已请相关成员回应')
})
test('one navigation entry, legacy links resolve to the same page and breadcrumb', () => {
  assert.equal(NAV.filter(n => ['/collaboration', '/roundtable', '/workflow'].includes(n.path)).length, 1)
  for (const route of ['/collaboration', '/roundtable', '/workflow']) {
    assert.ok(isKnownRoute(route)); assert.equal(activePathOf(route), '/collaboration')
    assert.deepEqual(breadcrumbsOf(route).map(c => c.label), ['协作'])
  }
})
test('default graph excludes unrelated history and preserves transitive dependencies including cycles', () => {
  const tasks = [task('a', 'done'), task('b', 'done', ['a']), task('c', 'doing', ['b']), task('d', 'done'), task('e', 'pending', ['f']), task('f', 'done', ['e'])]
  assert.deepEqual(graphTasks(tasks, null, false).map(t => t.id), ['a', 'b', 'c', 'e', 'f'])
  assert.deepEqual(graphTasks(tasks, 'd', false).map(t => t.id), ['a', 'b', 'c', 'd', 'e', 'f'])
  assert.equal(graphTasks(tasks, null, true), tasks)
  assert.deepEqual(graphTasks([task('a', 'done')], null, false), [])
})
test('submitted and checking work stays visible with its upstream evidence', () => {
  const tasks = [task('upstream', 'done'), task('submitted', 'reviewing', ['upstream']), task('review', 'checking'), task('history', 'done')]
  assert.deepEqual(graphTasks(tasks, null, false).map(t => t.id), ['upstream', 'submitted', 'review'])
  assert.notEqual(projectStatus({ todos: tasks, agents: [] }, false, false), '执行已结束 · 待审阅')
})
const message = (id, content, extra = {}) => ({ id, role: 'peer', peerId: 'a', topicId: 'topic', content, createdAt: new Date(id * 1000).toISOString(), trace: null, reasoning: null, ...extra })
test('reading coalesces body fragments without changing originals or crossing event, topic and tool boundaries', () => {
  const original = [message(1, '第一段'), message(2, '第二段'), message(3, '状态', { role: 'event', kind: 'silent' }), message(4, '新的发言'), message(5, '其他话题', { topicId: 'other' }), message(6, '工具内容', { trace: [{ type: 'tool', name: 'read' }] })]
  const result = readingMessages(original)
  assert.equal(result.length, 4); assert.equal(result[0].content, '第一段\n\n第二段')
  assert.equal(result[1].content, '新的发言'); assert.equal(original[0].content, '第一段')
  assert.equal(result[3].trace[0].name, 'read')
})
test('ended work does not claim acceptance and malformed historical timestamps remain readable', () => {
  assert.equal(projectStatus({ todos: [task('a', 'done')], agents: [] }, false, false), '执行已结束 · 待审阅')
  assert.equal(eventTime('invalid-history-time'), '')
  assert.equal(projectStatus({ todos: [], agents: [] }, false, true), '等待你回应')
})

test('issue cards and conclusions survive continuous reading without merging surrounding speeches', () => {
  const messages = [message(1, '发起前的说明'), message(2, '议题入口', { role: 'event', kind: 'issue_open' }), message(3, '主线后续消息'), message(4, '议题结论', { role: 'event', kind: 'issue_resolved' })]
  const result = readingMessages(messages, new Set(['issue_open', 'issue_resolved']))
  assert.deepEqual(result.map(m => m.id), [1, 2, 3, 4])
  assert.equal(result[0].content, '发起前的说明')
})

test('project membership prefers saved settings, otherwise infers task members and excludes deleted agents', () => {
  const peers = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
  const sessions = [{ projectId: 'project', memberIds: ['a', 'deleted'] }, { projectId: 'other', memberIds: ['c'] }]
  assert.deepEqual(projectMemberIds('project', {}, sessions, peers), ['a'])
  assert.deepEqual(projectMemberIds('project', { project: ['b'] }, sessions, peers), ['b'])
  assert.deepEqual(projectMemberIds('empty', {}, sessions, peers), ['a', 'b', 'c'])
})
