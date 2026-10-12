// 由 npm run test:memory 编译并提供隔离输出目录。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { scopeKind, status, evidence } = require(process.env.MEMORY_MODEL_BUILD)
const item = (overrides = {}) => ({ id: 1, owner: 'AGENT', domain: 'AGENT', layer: 'L2', active: true, status: 'active', validUntil: null, expiresSoft: null, supersededBy: null, ...overrides })
test('项目 L1 是项目共享，用户画像 L1 才是全局', () => {
  assert.equal(scopeKind(item({ owner: 'PROJECT', domain: 'PROJECT', layer: 'L1' }), []), 'project')
  assert.equal(scopeKind(item({ owner: 'USER', domain: 'USER_PROFILE', layer: 'L1' }), []), 'global')
})
test('L2 默认独享，但显式发布可扩展共享范围，撤销授权不再算已发布', () => {
  assert.equal(scopeKind(item(), []), 'private')
  assert.equal(scopeKind(item(), [{ itemId: 1, status: 'published' }]), 'published')
  assert.equal(scopeKind(item(), [{ itemId: 1, status: 'revoked' }]), 'private')
})
test('L4 未授权不公开，L3 明示绑定且不冒充私有长期经验', () => {
  assert.equal(scopeKind(item({ layer: 'L4' }), []), 'unpublished')
  assert.equal(scopeKind(item({ layer: 'L3' }), []), 'bound')
})
test('有效性服从期限与软过期，撤销和取代不会被 active 布尔值掩盖', () => {
  assert.equal(status(item({ validUntil: 10 }), 10), '已过期')
  assert.equal(status(item({ expiresSoft: 9 }), 10), '已过期')
  assert.equal(status(item({ status: 'revoked' }), 10), '已撤销')
  assert.equal(status(item({ supersededBy: 2 }), 10), '已取代')
  assert.equal(status(item({ validUntil: 11 }), 10), '有效')
})
test('证据保留原文，旧引用和坏 JSON 不能编造证据文字', () => {
  assert.deepEqual(evidence('[{"ref":"chat_message:1","text":"用户原话"}]'), [{ ref: 'chat_message:1', text: '用户原话' }])
  assert.deepEqual(evidence('[1,2]'), [{ ref: '1', text: null }, { ref: '2', text: null }])
  assert.deepEqual(evidence('坏 JSON'), [{ ref: '坏 JSON', text: null }])
  assert.deepEqual(evidence(null), [])
})
