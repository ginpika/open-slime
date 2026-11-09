const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const ts = require('typescript')
const source = readFileSync(join(__dirname, '../src/components/settledChat.ts'), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } })
const loaded = { exports: {} }
new Function('exports', compiled.outputText)(loaded.exports)
const { backfillSettledReply, mergeLiveChat } = loaded.exports
const live = { sessionId: 's', userText: 'question', userMessageId: 10, content: 'answer', reasoning: '', trace: [], steers: [], interims: [], running: false, error: null }
const history = () => [{ id: 10, role: 'user', content: 'question' }, { role: 'assistant', content: 'answer', streaming: false }]
const row = (id, role, content, extra = {}) => ({ id, role, content, reasoning: null, trace: null, kind: null, subagent: null, createdAt: null, ...extra })

test('completed reply receives its persisted ID immediately without leaving the conversation', () => {
  const messages = history()
  assert.equal(messages[1].id, undefined)
  const trace = [{ type: 'reasoning', text: 'thought' }]
  const result = backfillSettledReply(messages, live, [row(10, 'user', 'question'), row(11, 'assistant', 'answer', { trace })])
  assert.equal(result[1].id, 11)
  assert.equal(result[1].streaming, false)
  assert.deepEqual(result[1].trace, trace)
  assert.equal(result.length, 2)
  assert.equal(messages[1].id, undefined)
})
test('identical replies and asynchronous subagent messages do not select the wrong branch point', () => {
  const rows = [row(1, 'user', 'question'), row(2, 'assistant', 'answer'), row(10, 'user', 'question'), row(12, 'assistant', 'report', { kind: 'subagent' }), row(13, 'assistant', 'answer')]
  assert.deepEqual(backfillSettledReply(history(), live, rows).map(message => message.id), [10, 12, 13])
})
test('a subagent batch arriving before the reply remains separate during reconnect and settlement', () => {
  const subagent = { title: 'Findings', summary: 'Short excerpt', status: 'done', execId: 42 }
  const report = row(11, 'assistant', 'Full report', { kind: 'subagent', subagent })
  const batch = row(12, 'assistant', 'Batch summary', { kind: 'subagent_batch' })
  const user = row(10, 'user', 'question')
  const reconnected = mergeLiveChat([user, report, batch], live)
  assert.deepEqual(reconnected.map(message => message.content), ['question', 'Full report', 'Batch summary', 'answer'])

  const rows = [user, report, batch, row(13, 'assistant', 'answer')]
  assert.equal(mergeLiveChat(rows, live), rows)
  const settled = backfillSettledReply(history(), live, rows)
  assert.deepEqual(settled.map(message => message.id), [10, 11, 12, 13])
  assert.equal(settled[1].content, 'Full report')
  assert.deepEqual(settled[1].subagent, subagent)
  assert.equal(settled[2].kind, 'subagent_batch')
  assert.deepEqual(backfillSettledReply(reconnected, live, rows).map(message => message.id), [10, 11, 12, 13])
})
test('late synchronization does not resurrect a rewound reply or overwrite a replacement', () => {
  const rows = [row(10, 'user', 'question'), row(11, 'assistant', 'answer')]
  const rewound = []
  assert.equal(backfillSettledReply(rewound, live, rows), rewound)
  const replaced = [{ id: 10, role: 'user', content: 'question' }, { id: 22, role: 'assistant', content: 'answer' }]
  assert.equal(backfillSettledReply(replaced, live, rows), replaced)
})
test('running or not yet persisted replies stay unavailable for branching', () => {
  const messages = history()
  const rows = [row(10, 'user', 'question'), row(11, 'assistant', 'answer')]
  assert.equal(backfillSettledReply(messages, { ...live, running: true }, rows), messages)
  assert.equal(backfillSettledReply(messages, live, [row(10, 'user', 'question'), row(20, 'user', 'next'), row(21, 'assistant', 'answer')]), messages)
})
test('backfilling the old turn preserves newer messages', () => {
  const messages = [...history(), { id: 20, role: 'user', content: 'next' }, { role: 'assistant', content: 'partial', streaming: true }]
  const result = backfillSettledReply(messages, live, [row(10, 'user', 'question'), row(11, 'assistant', 'answer')])
  assert.equal(result[1].id, 11)
  assert.equal(result[2], messages[2])
  assert.equal(result[3], messages[3])
})
