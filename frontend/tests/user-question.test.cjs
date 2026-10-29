const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const ts = require('typescript')
const loaded = { exports: {} }
new Function('exports', ts.transpileModule(readFileSync(join(__dirname, '../src/components/userQuestion.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText)(loaded.exports)
const { questionContent, userQuestion } = loaded.exports
const question = { question: '先做什么？', options: [{ label: '界面', description: '先完成界面' }, { label: '后端', description: '' }] }
const message = () => ({ content: questionContent(question), trace: [{ type: 'tool', name: 'request_user_input', args: JSON.stringify(question) }] })

test('completed and persisted questions restore without relying on the CoT display setting', () => {
  assert.deepEqual(userQuestion(message()), question)
  const source = { question: ' 先做什么？ ', options: [{ label: ' 界面 ', description: ' 先完成界面 ' }, { label: '后端' }] }
  assert.deepEqual(userQuestion({ ...message(), trace: [{ type: 'tool', name: 'request_user_input', args: JSON.stringify(source) }] }), question)
})
test('streaming, failed, cancelled or superseded requests are not interactive questions', () => {
  assert.equal(userQuestion({ ...message(), streaming: true }), null)
  assert.equal(userQuestion({ ...message(), content: '工具参数无效' }), null)
  assert.equal(userQuestion({ ...message(), content: '' }), null)
  const msg = message()
  msg.trace.push({ type: 'tool', name: 'search_documents', args: '{}' })
  assert.equal(userQuestion(msg), null)
})
test('images appended from earlier tool results preserve the question card', () => {
  assert.deepEqual(userQuestion({ ...message(), content: questionContent(question) + '\n\n![生成图片](/api/comfyui/images/11111111-1111-1111-1111-111111111111)' }), question)
  assert.equal(userQuestion({ ...message(), content: questionContent(question) + '\n\n模型继续自行决定。' }), null)
})
test('malformed and unbounded history falls back to the normal message', () => {
  for (const args of ['{', 'null', '[]', '{"question":1}', JSON.stringify({ ...question, options: [] }),
    JSON.stringify({ ...question, options: [question.options[0], question.options[0]] }),
    JSON.stringify({ ...question, question: 'x'.repeat(1001) })]) {
    assert.equal(userQuestion({ ...message(), trace: [{ type: 'tool', name: 'request_user_input', args }] }), null)
  }
})
