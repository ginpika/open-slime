import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const code = ts.transpileModule(readFileSync(new URL('../src/pages/todo/workTime.ts', import.meta.url), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { dayTimeline, shiftDay } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
const hour = 3600000
const day = (count = 24) => ({ rangeStart: hour, rangeEnd: (count + 1) * hour, hours: Array.from({ length: count }, (_, i) => ({ label: `${i}`, startedAt: (i + 1) * hour, endedAt: (i + 2) * hour })), focus: [], items: [] })
const focus = (id, todoId, start, end) => ({ id, todoId, title: todoId, projectName: null, startedAt: start * hour, endedAt: end === null ? null : end * hour })

test('positions and widths reflect exact time, repeated work stays on one row with separate ordered bars', () => {
  const data = day(), now = 3.5 * hour
  data.focus = [focus('last', 'a', 3.25, null), focus('first', 'a', 0, 1.25), focus('middle', 'b', 1.75, 2.5)]
  const model = dayTimeline(data, now)
  assert.deepEqual(model.rows.map(r => r.id), ['a', 'b'])
  assert.deepEqual(model.rows[0].bars.map(b => b.order), [1, 3])
  assert.deepEqual(model.bars.map(b => b.focus.id), ['first', 'middle', 'last'])
  assert.equal(model.total, 1.25 * hour)
  assert.equal(model.rows[0].total, 0.5 * hour)
  assert.equal(model.bars[0].left, 0)
  assert.equal(model.bars[0].width, 0.25 / 24 * 100)
  assert.equal(model.bars[1].left, 0.75 / 24 * 100)
  assert.equal(model.bars[1].width, 0.75 / 24 * 100)
  assert.equal(model.bars[2].end, now)
  assert.equal(model.nowPercent, 2.5 / 24 * 100)
  assert.ok(model.bars[0].left + model.bars[0].width < model.bars[1].left, 'gaps stay blank')
})

test('empty historical days and 23/25-hour days use complete actual day geometry', () => {
  for (const count of [23, 24, 25]) {
    const data = day(count), empty = dayTimeline(data, 100 * hour)
    assert.equal(empty.rows.length, 0)
    assert.equal(empty.total, 0)
    data.focus = [focus('crossing', 'work', 0, 100)]
    const model = dayTimeline(data, 100 * hour)
    assert.equal(model.total, count * hour)
    assert.equal(model.bars[0].left, 0)
    assert.equal(model.bars[0].width, 100)
    assert.equal(model.at(data.rangeEnd), 100)
  }
})

test('completion is an independent point, never an invented work duration', () => {
  const data = day()
  data.items = [{ id: 'zero', title: '快速完成', projectName: null, state: 'done', completedAt: 2 * hour }, { id: 'old', state: 'done', completedAt: 0 }, { id: 'open', state: 'doing', completedAt: null }]
  const model = dayTimeline(data, 4 * hour)
  assert.equal(model.rows.length, 1)
  assert.equal(model.rows[0].completedAt, 2 * hour)
  assert.equal(model.total, 0)
  assert.equal(model.bars.length, 0)
})

test('future and zero-length stopped records are excluded; a just-started timer remains visible', () => {
  const data = day()
  data.focus = [focus('future', 'a', 10, null), focus('zero', 'b', 2, 2), focus('active', 'c', 3, null)]
  const model = dayTimeline(data, 3 * hour)
  assert.equal(model.bars.length, 1)
  assert.equal(model.bars[0].focus.id, 'active')
  assert.equal(model.total, 0)
})

test('calendar day navigation crosses month, year and leap-day boundaries', () => {
  assert.equal(shiftDay('2024-03-01', -1), '2024-02-29')
  assert.equal(shiftDay('2026-01-01', -1), '2025-12-31')
  assert.equal(shiftDay('2026-10-31', 1), '2026-11-01')
})
