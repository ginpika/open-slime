const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const script = fs.readFileSync(path.join(__dirname, '../ui/mail-appearance.js'), 'utf8')

function bridge(snapshot) {
  const listeners = new Map()
  const frames = []
  const navigations = []
  const window = {
    __HOSTID_APPEARANCE__: snapshot,
    location: { origin: 'https://mail.example.test' },
    addEventListener: (name, callback) => {
      const entries = listeners.get(name) || []
      entries.push(callback)
      listeners.set(name, entries)
    },
    requestAnimationFrame: callback => { frames.push(callback); return frames.length }
  }
  Object.defineProperty(window.location, 'href', { set: url => navigations.push(new URL(url)) })
  const context = vm.createContext({ window })
  const run = () => vm.runInContext(script, context)
  const emit = appearance => {
    window.__HOSTID_APPEARANCE__ = appearance
    for (const listener of listeners.get('hostid:appearance') || []) listener({ detail: appearance })
  }
  const flush = () => { while (frames.length) frames.shift()() }
  run()
  return { window, listeners, navigations, emit, flush, run }
}
const appearance = backgroundColor => ({ version: 1, backgroundColor })

test('initial snapshot and later dark/light changes reach the native bridge', () => {
  const b = bridge(appearance('#F4F6FA'))
  b.flush()
  assert.equal(b.navigations[0].pathname, '/__minis_mail_appearance')
  assert.equal(b.navigations[0].searchParams.get('version'), '1')
  assert.equal(b.navigations[0].searchParams.get('backgroundColor'), '#f4f6fa')
  b.emit(appearance('#10151e'))
  b.flush()
  assert.equal(b.navigations[1].searchParams.get('backgroundColor'), '#10151e')
  b.emit(appearance('#f4f6fa'))
  b.flush()
  assert.equal(b.navigations[2].searchParams.get('backgroundColor'), '#f4f6fa')
})

test('client can attach before HostID and coalesces rapid initial updates', () => {
  const b = bridge()
  b.flush()
  assert.equal(b.navigations.length, 0)
  b.emit(appearance('#f4f6fa'))
  b.emit(appearance('#10151e'))
  b.flush()
  assert.equal(b.navigations.length, 1)
  assert.equal(b.navigations[0].searchParams.get('backgroundColor'), '#10151e')
})

test('reopening mail resends the snapshot without adding duplicate listeners', () => {
  const b = bridge(appearance('#f4f6fa'))
  b.flush()
  b.emit(appearance('#f4f6fa'))
  b.flush()
  assert.equal(b.navigations.length, 1)
  b.run()
  b.flush()
  assert.equal(b.navigations.length, 2)
  assert.equal(b.listeners.get('hostid:appearance').length, 1)
})

test('unrecognized versions and invalid colors never initiate a navigation', () => {
  const b = bridge()
  for (const value of [null, {}, {version: 2, backgroundColor: '#f4f6fa'}, appearance('transparent'), appearance('#fff'), appearance('#f4f6fa;alert(1)')]) b.emit(value)
  b.flush()
  assert.equal(b.navigations.length, 0)
})

test('rapid changes back to the previous color keep the latest appearance', () => {
  const b = bridge(appearance('#f4f6fa'))
  b.flush()
  b.emit(appearance('#10151e'))
  b.emit(appearance('#f4f6fa'))
  b.flush()
  assert.equal(b.navigations.at(-1).searchParams.get('backgroundColor'), '#f4f6fa')
})
