/** 仅用于 Vite 开发验收。所有 /api 请求在内存中模拟，不访问用户数据库，不随主入口构建。 */
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import MemoryPanel from '../src/pages/MemoryPanel'
import { initTheme, themeActions } from '../src/theme/store'
import '../src/index.css'
import type { MemoryItem, MemoryShare, MemoryRevision } from '../src/api/client'

initTheme()
const setSchemeMode = themeActions.setSchemeMode
const now = Date.now()
let failure = false, delay = false
const items: MemoryItem[] = Array.from({ length: 31 }, (_, n) => ({ id: n + 1, agentId: n === 0 || n === 1 ? null : n % 2 ? 'a' : 'b', owner: n === 0 ? 'USER' : n === 1 ? 'PROJECT' : n === 4 ? 'TASK' : 'AGENT', domain: n === 0 ? 'USER_PROFILE' : n === 1 ? 'PROJECT' : n === 4 ? 'TASK_EPISODIC' : 'AGENT', layer: n < 2 ? 'L1' : n === 4 ? 'L3' : n === 5 ? 'L4' : 'L2', projectId: n === 0 ? null : n < 6 ? 'p' : null, sessionId: n === 4 ? 's' : null, taskId: n === 4 ? 9 : null, scope: n === 0 ? 'directive' : 'fact', scopeLabel: '事实', title: ['请使用中文回答', '项目使用 PostgreSQL', '数据库 Agent 的私有经验', '设计 Agent 的私有经验', '本会话临时待办', '已发布的项目结论'][n] ?? `Agent 经验 ${n}`, body: n === 0 ? '用户明确要求始终使用中文。' : `可检查的正文 ${n + 1}。这条记忆具有独立归属、证据和版本。`, confidence: 0.88, source: 'manual', sourceKind: 'real', evidenceLevel: 'explicit', status: n === 30 ? 'revoked' : 'active', retention: 'long', identity: n === 7 ? 'FICTION' : 'REAL', version: 0, pinned: n === 0, active: n !== 30, validUntil: n === 29 ? now - 1000 : null, supersededBy: null, createdAt: now - n * 60000, updatedAt: now - n * 60000, producerAgent: n === 0 ? 'a' : n % 2 ? 'a' : 'b', sourceRefs: JSON.stringify([{ ref: `chat_message:${n + 1}`, text: '用户提供的原文证据。' }]), expiresSoft: null }))
const shares: MemoryShare[] = [{ id: 1, itemId: 6, fromOwner: 'AGENT', fromAgent: 'a', toKind: 'agent', toRef: 'b', status: 'published', note: '交接给数据库 Agent', createdAt: now }, { id: 2, itemId: 4, fromOwner: 'AGENT', fromAgent: 'a', toKind: 'agent', toRef: 'b', status: 'published', note: 'L2 也可显式共享', createdAt: now }]
const work = [{ id: 1, sessionId: 's', taskId: 9, projectId: 'p', agentId: 'a', kind: 'progress', title: '等待用户验收', body: '临时工作材料不应当混入长期画像。', status: 'open', expiresAt: now + 3600000, createdAt: now, updatedAt: now }]
const frozen = structuredClone(items.slice(0, 4))
const snapshots = [{ turnId: '7', sessionId: 's', agentId: 'a', projectId: 'p', watermark: 12, itemCount: 4, itemIds: [1, 2, 3, 4], workCount: 1, budgetTokens: 42, builtAt: now, complete: true }, { turnId: 'legacy', sessionId: null, agentId: null, projectId: null, watermark: 1, itemCount: 1, itemIds: [3], workCount: null, budgetTokens: 0, builtAt: now - 100000, complete: false }]
const audits: MemoryRevision[] = items.map(i => ({ id: i.id, itemId: i.id, op: 'insert', actorKind: 'human', actorId: null, turnId: null, beforeJson: null, afterJson: JSON.stringify(i), reason: '用户确认原文', createdAt: now }))
const originalFetch = window.fetch
window.fetch = async (input, init) => {
  const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href)
  const path = url.pathname
  if (!path.startsWith('/api/')) return originalFetch(input, init)
  const q = url.searchParams, method = init?.method ?? 'GET', req = init?.body ? JSON.parse(String(init.body)) : {}
  const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
  if (failure && ['/api/memory/health', '/api/memory/metrics', '/api/memory/shared'].includes(path)) return response({ message: '模拟接口不可用，不能当作零或正常' }, 503)
  const itemId = Number(path.match(/\/items\/(\d+)/)?.[1]), item = items.find(i => i.id === itemId)
  if (path.endsWith('/pin') && item) { item.pinned = req.pinned; return response(item) }
  if (method === 'PUT' && item) {
    if (req.version !== item.version) return response({ message: '版本冲突' }, 409)
    const before = JSON.stringify(item); item.body = req.body; item.title = req.title; item.version = (item.version ?? 0) + 1; item.updatedAt = Date.now()
    audits.unshift({ id: Date.now(), itemId, op: 'edit', actorKind: 'human', actorId: null, turnId: null, beforeJson: before, afterJson: JSON.stringify(item), reason: '版本化编辑', createdAt: Date.now() }); return response(item)
  }
  if (path.endsWith('/share') && item) { const s = { id: Date.now(), itemId, fromOwner: item.owner, fromAgent: item.agentId, status: 'published', ...req, createdAt: Date.now() }; shares.push(s); return response(s) }
  if (path.match(/\/shared\/\d+\/revoke$/)) { const share = shares.find(s => s.id === Number(path.split('/')[4])); if (share) share.status = 'revoked'; return response({ changed: 1 }) }
  if (path === '/api/memory/purge') { for (const i of items) if (req.itemIds.includes(i.id)) { i.status = 'revoked'; i.active = false } return response({ changed: 1 }) }
  if (path === '/api/memory/items') return response(items)
  if (path === '/api/memory/summary') return response({ scopes: [], active: items.filter(i => i.active).length, total: items.length, pendingCandidates: 1, publishedShares: shares.length })
  if (path === '/api/memory/shared') return response(shares.filter(s => s.status === 'published'))
  if (path === '/api/agents/peers') return response([{ id: 'builtin', name: '内置 Agent', builtin: true }, { id: 'a', name: '设计 Agent', builtin: false }, { id: 'b', name: '数据库 Agent', builtin: false }])
  if (path === '/api/projects') return response([{ id: 'p', name: 'Memory 项目' }, { id: 'other', name: '其他项目' }])
  if (path === '/api/sessions') return response([{ id: 's', title: 'Memory 测试会话', agentId: 'a', projectId: 'p' }])
  if (path === '/api/memory/observation/working' || path === '/api/memory/working-set') return response(work)
  if (path === '/api/memory/health') return response({ generatedAt: now, grade: 'ok', pollution: { fictionInProfile: 0, blockedFictionCandidates: 0, rejectedCandidates: 0, alerts: [] }, crowding: { domains: [], alerts: [] }, drift: { superseded: 0, recent: [], alerts: [] } })
  if (path === '/api/memory/metrics') return response({ scopeMissing: 0, invariantViolation: 0, writeConflict: 0, budgetEvicted: 2, crossDomainBlocked: 1, snapshotBuilt: 2, snapshotSaved: 2, snapshotCoverage: 1 })
  if (path === '/api/memory/revisions') return response(audits.filter(r => r.itemId === Number(q.get('itemId'))))
  if (path === '/api/memory/observation/visibility') {
    const id = q.get('agentId'), project = q.get('projectId'), session = q.get('sessionId'), task = Number(q.get('taskId'))
    const entries = items.filter(i => i.active && (!i.validUntil || i.validUntil > Date.now()) && (i.owner === 'USER' || i.owner === 'PROJECT' && i.layer === 'L1' && i.projectId === project || i.layer === 'L2' && i.agentId === id && (!i.projectId || i.projectId === project) || i.layer === 'L3' && (i.sessionId === session || i.taskId === task) || shares.some(s => s.itemId === i.id && s.status === 'published' && (s.toKind === 'user' || s.toKind === 'agent' && s.toRef === id)))).map(i => ({ itemId: i.id, reasons: [i.owner === 'USER' ? '正式用户规则，所有 Agent 可读' : i.layer === 'L1' ? '当前项目的已确认共享记忆' : i.agentId === id ? '归属当前 Agent 的私有记忆' : '显式发布授权：agent:' + id], editableByAgent: i.agentId === id && i.layer === 'L2' }))
    if (delay && id === 'a') await new Promise(r => setTimeout(r, 1800))
    return response({ context: Object.fromEntries(q), checkedAt: Date.now(), entries, work: session === 's' && project === 'p' ? work : [] })
  }
  if (path === '/api/memory/observation/snapshots') return response(snapshots.filter(s => (!q.get('agentId') || (s.agentId ?? 'builtin') === q.get('agentId')) && (!q.get('projectId') || s.projectId === q.get('projectId')) && (!q.get('sessionId') || s.sessionId === q.get('sessionId'))))
  if (path.startsWith('/api/memory/observation/snapshots/')) {
    const s = snapshots.find(s => s.turnId === decodeURIComponent(path.split('/').at(-1)!))!
    return response({ summary: s, itemIds: s.itemIds, content: s.complete ? { items: frozen, work } : null, requests: s.complete ? [{ round: 0, requestRecorded: true, memorySectionPresent: true, memoryText: '[L1] 请使用中文回答\n实际请求中的正文可能已刷新', responseRecorded: true }, { round: 1, requestRecorded: true, memorySectionPresent: false, memoryText: null, responseRecorded: true }, { round: 2, requestRecorded: false, memorySectionPresent: false, memoryText: null, responseRecorded: false }] : [], requestNote: s.complete ? '来自隔离验收 fixture 的实际请求结构。' : '该旧记录没有可关联的逐次请求留痕。' })
  }
  if (path === '/api/memory/candidates') return response([{ id: 1, scope: 'profile', scopeLabel: '画像', title: '使用中文', body: '用户喜欢中文交流', sourceKind: 'real', evidenceLevel: 'explicit', evidenceCount: 1, evidenceRefs: '[{"ref":"chat_message:1","text":"请一直用中文回答。"}]', status: 'pending', createdAt: now, updatedAt: now }])
  return response([])
}
export default function Preview() {
  const [failed, setFailed] = useState(false), [slow, setSlow] = useState(false)
  return <><div style={{ padding: 12, borderBottom: '1px solid var(--c-line)', color: 'var(--c-fg)', display: 'flex', gap: 12, flexWrap: 'wrap' }}><strong>隔离验收 · 模拟数据 · 不连接实际数据库</strong><button onClick={() => setSchemeMode('light')}>浅色</button><button onClick={() => setSchemeMode('dark')}>深色</button><button onClick={() => { failure = !failed; setFailed(!failed) }}>接口失败：{failed ? '开' : '关'}（再刷新）</button><button onClick={() => { delay = !slow; setSlow(!slow) }}>慢权限：{slow ? '开' : '关'}</button></div><MemoryPanel /></>
}
createRoot(document.getElementById('root')!).render(<Preview />)
