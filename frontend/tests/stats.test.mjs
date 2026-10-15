import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../src/pages/stats/format.ts', import.meta.url), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { amount, csvCell, statsCsv, validateRange, dateTime } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))

test('custom dates validate real calendar dates, bounds and leap years independently of browser timezone', () => {
  assert.equal(validateRange('2024-01-01', '2024-12-31', '2026-10-15'), null)
  assert.match(validateRange('2024-01-01', '2025-01-01', '2026-10-15'), /366/)
  assert.match(validateRange('2026-02-30', '2026-03-01'), /有效/)
  assert.match(validateRange('2026-10-15', '2026-10-14'), /结束/)
  assert.match(validateRange('2026-10-01', '2026-10-16', '2026-10-15'), /今天/)
  assert.match(validateRange('', '2026-10-15'), /同时/)
  assert.match(validateRange('1969-12-31', '1970-01-01'), /1970/)
  assert.match(dateTime(Date.parse('2026-10-14T20:00:00Z'), 'Asia/Shanghai'), /2026\/10\/15/)
})
test('small fees are never rounded to a misleading zero and explicit free remains zero', () => {
  assert.equal(amount(0), '0')
  assert.notEqual(amount(0.000000001), '0')
  assert.equal(amount(0.0001), '0.0001')
})
test('CSV neutralizes formulas and preserves quotes, line breaks, currencies and unknown cost', () => {
  assert.equal(csvCell(' =SUM(A1)'), '"\' =SUM(A1)"')
  assert.equal(csvCell('@SUM(A1)'), '"\'@SUM(A1)"')
  assert.equal(csvCell('a"b\nc'), '"a""b\nc"')
  const bucket = { key: '=danger', provider: 'https://a.test', model: '+model', promptTokens: 10, completionTokens: 5, totalTokens: 15, calls: 2, unpricedCalls: 1, costs: [{ currency: 'CNY', amount: 0.0001 }, { currency: 'USD', amount: 0.0002 }] }
  const view = { from: '2026-10-01', to: '2026-10-15', timeZone: 'Asia/Shanghai', generatedAt: 1,
    daily: [{ ...bucket, key: '2026-10-15', costs: [] }], byModel: [bucket], bySource: [bucket],
    total: { reportedCalls: 1, estimatedCalls: 1, partialCalls: 0, unsuccessfulCalls: 0 } }
  const csv = statsCsv(view)
  assert.equal(csv.charCodeAt(0), 0xfeff)
  assert.ok(csv.includes('"含未计价调用"'))
  assert.ok(csv.includes('"估算费用（CNY）"') && csv.includes('"估算费用（USD）"'))
  assert.equal(csv.split('\r\n').filter(row => row.startsWith('"模型",')).length, 1, 'multiple currencies do not duplicate token counts')
  assert.ok(csv.includes('"\'=danger"') && csv.includes('"\'+model"'))
  assert.ok(csv.includes('"Asia/Shanghai"'))
})

