const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const ts = require('typescript')
const loaded = { exports: {} }
new Function('exports', ts.transpileModule(readFileSync(join(__dirname, '../src/components/userQuestion.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText)(loaded.exports)
const { questionContent, questionTurn } = loaded.exports
const question = { question: '先做什么？', options: [{ label: '界面', description: '先完成界面' }, { label: '后端', description: '' }] }
const image = '\n\n![生成图片](/api/comfyui/images/11111111-1111-1111-1111-111111111111)'
const message = () => ({ content: questionContent(question), trace: [{ type: 'tool', name: 'request_user_input', args: JSON.stringify(question) }] })

test('completed and persisted questions restore without relying on the CoT display setting', () => {
  assert.deepEqual(questionTurn(message()), { question, before: '', after: '' })
  const source = { question: ' 先做什么？ ', options: [{ label: ' 界面 ', description: ' 先完成界面 ' }, { label: '后端' }] }
  assert.deepEqual(questionTurn({ ...message(), trace: [{ type: 'tool', name: 'request_user_input', args: JSON.stringify(source) }] }),
    { question, before: '', after: '' })
})
test('narration before the question and images after it stay around the card', () => {
  assert.deepEqual(questionTurn({ ...message(), content: '先说结论。\n\n' + questionContent(question) }),
    { question, before: '先说结论。', after: '' })
  assert.deepEqual(questionTurn({ ...message(), content: '先说结论。\n\n' + questionContent(question) + image }),
    { question, before: '先说结论。', after: image })
})
test('streaming, failed, cancelled or superseded requests are not interactive questions', () => {
  assert.equal(questionTurn({ ...message(), streaming: true }), null)
  assert.equal(questionTurn({ ...message(), content: '工具参数无效' }), null)
  assert.equal(questionTurn({ ...message(), content: '' }), null)
  const msg = message()
  msg.trace.push({ type: 'tool', name: 'search_documents', args: '{}' })
  assert.equal(questionTurn(msg), null)
})
test('images appended from earlier tool results preserve the question card', () => {
  assert.deepEqual(questionTurn({ ...message(), content: questionContent(question) + image }),
    { question, before: '', after: image })
  assert.equal(questionTurn({ ...message(), content: questionContent(question) + '\n\n模型继续自行决定。' }), null)
})
test('malformed and unbounded history falls back to the normal message', () => {
  for (const args of ['{', 'null', '[]', '{"question":1}', JSON.stringify({ ...question, options: [] }),
    JSON.stringify({ ...question, options: [question.options[0], question.options[0]] }),
    JSON.stringify({ ...question, question: 'x'.repeat(1001) })]) {
    assert.equal(questionTurn({ ...message(), trace: [{ type: 'tool', name: 'request_user_input', args }] }), null)
  }
})
