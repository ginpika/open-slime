import { usePageKit } from '../../kit'
import { useEffect, useRef, useState } from 'react'
import { memoryRevisions, pinMemoryItem, purgeMemory, revokeMemoryShare, shareMemoryItem, updateMemoryItem, type MemoryItem, type MemoryRevision, type MemoryShare } from '../../api/client'
import { MemoryButton, MemoryField, MemorySelect } from './MemoryControls'
import type { MemoryAccess } from '../../api/memoryObservation'
import { domainNames, errorText, identityNames, occurrence, ownerNames, scopeKind, scopeNames, status, time } from './model'
import MemorySources from './MemorySources'
/* 查询开始重置读取状态；旧审计不能作为新条目的查询结果。 */
/* oxlint-disable react/set-state-in-effect */

type Props = {
  item: MemoryItem; shares: MemoryShare[]; sharesAvailable: boolean; access?: MemoryAccess; selectedCount: number | null
  agentNames: Map<string, string>; projectNames: Map<string, string>; sessionNames: Map<string, string>
  changed: () => void; close: () => void
}
export default function MemoryInspector({ item, shares, sharesAvailable, access, selectedCount, agentNames, projectNames, sessionNames, changed, close }: Props) {
  const kit = usePageKit()
  const [revisions, setRevisions] = useState<MemoryRevision[]>([])
  const [auditError, setAuditError] = useState('')
  const [auditLoading, setAuditLoading] = useState(true)
  const [auditTick, setAuditTick] = useState(0)
  const [action, setAction] = useState<'edit' | 'share' | 'forget' | null>(null)
  const [title, setTitle] = useState(item.title ?? '')
  const [body, setBody] = useState(item.body)
  const [editVersion, setEditVersion] = useState(item.version)
  const [kind, setKind] = useState('agent')
  const [target, setTarget] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [revokeId, setRevokeId] = useState<number | null>(null)
  const actionForm = useRef<HTMLFormElement>(null)
  const mounted = useRef(true)
  const panel = useRef<HTMLElement>(null)
  const closeRef = useRef(close)
  useEffect(() => { closeRef.current = close }, [close])
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panel.current?.focus()
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current()
      if (event.key !== 'Tab') return
      const controls = [...panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled), input, select, textarea, summary, [tabindex="0"]') ?? []].filter(e => e.getClientRects().length)
      const first = controls[0], last = controls[controls.length - 1]
      if (!first) { event.preventDefault(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.current)) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', key)
    return () => { document.removeEventListener('keydown', key); document.body.style.overflow = overflow; previous?.focus() }
  }, [])
  useEffect(() => {
    let live = true
    setAuditLoading(true); setAuditError('')
    memoryRevisions({ itemId: item.id, limit: 100 }).then(v => { if (live) setRevisions(v) }).catch(e => { if (live) setAuditError(errorText(e)) }).finally(() => { if (live) setAuditLoading(false) })
    return () => { live = false }
  }, [item.id, item.version, auditTick])
  useEffect(() => { if (action) actionForm.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }) }, [action])
  const grants = shares.filter(s => s.itemId === item.id && s.status === 'published')
  const names = kind === 'agent' ? agentNames : kind === 'project' ? projectNames : sessionNames
  async function run(fn: () => Promise<unknown>, text: string) {
    setBusy(true); setError(''); setMessage('')
    try {
      await fn(); changed()
      if (mounted.current) { setAction(null); setRevokeId(null); setMessage(text); setAuditTick(v => v + 1) }
    } catch (e) { if (mounted.current) setError(errorText(e)) }
    finally { if (mounted.current) setBusy(false) }
  }
  const save = () => run(() => updateMemoryItem(item.id, {
    scope: item.scope, title: title.trim() || null, body, version: editVersion,
    owner: item.owner, domain: item.domain, layer: item.layer, agentId: item.agentId, projectId: item.projectId,
    sessionId: item.sessionId, taskId: item.taskId, retention: item.retention, identity: item.identity, validUntil: item.validUntil,
  }), '已保存新版本；历史快照保留原内容。')
  return <aside ref={panel} tabIndex={-1} role="dialog" aria-modal="true" className="mem-inspector" aria-label="记忆详情">
    <header className="mem-inspector-header">
    <div className="mem-row"><span className="mem-eyebrow">条目 #{item.id} · v{item.version ?? 0}</span><MemoryButton variant="quiet" onClick={close}>关闭详情</MemoryButton></div>
    <h3 title={item.title ?? undefined}>{item.title || '未命名记忆'}</h3>
    <div className="mem-tags"><kit.Badge>{item.layer ?? '未分类'}</kit.Badge><kit.Badge>{status(item)}</kit.Badge><kit.Badge tone={item.identity === 'FICTION' ? 'warn' : 'default'}>{identityNames[item.identity ?? ''] ?? item.identity ?? '身份未标记'}</kit.Badge>{item.pinned && <kit.Badge tone="accent">已固定</kit.Badge>}</div>
    <div className="mem-actions"><MemoryButton disabled={busy} onClick={() => void run(() => pinMemoryItem(item.id, !item.pinned), item.pinned ? '已取消固定。' : '已固定；固定不改变共享范围。')}>{item.pinned ? '取消固定' : '固定'}</MemoryButton><MemoryButton disabled={busy} onClick={() => { setTitle(item.title ?? ''); setBody(item.body); setEditVersion(item.version); setAction('edit'); setError(''); setRevokeId(null) }}>编辑</MemoryButton><MemoryButton disabled={busy || status(item) !== '有效'} onClick={() => { setAction('share'); setTarget(''); setNote(''); setError(''); setRevokeId(null) }}>定向发布</MemoryButton><MemoryButton variant="danger" disabled={busy} onClick={() => { setAction('forget'); setNote(''); setError(''); setRevokeId(null) }}>遗忘</MemoryButton></div>
    </header>
    {action && <form ref={actionForm} className="mem-action-form" onSubmit={e => { e.preventDefault(); if (action === 'share' && kind !== 'user' && !target) { setError('请选择具体授权目标'); return } if (action === 'edit') void save(); else if (action === 'share') void run(() => shareMemoryItem(item.id, { toKind: kind, toRef: kind === 'user' ? null : target, note: note || null }), '发布成功；层级不变，可读范围通过授权扩展。'); else void run(() => purgeMemory({ itemIds: [item.id], reason: note || '用户从记忆详情遗忘' }), '已遗忘：停用条目并记录撤销墓碑与审计。') }}>
      <h4>{action === 'edit' ? `编辑 #${item.id} · 基于 v${editVersion ?? 0}` : action === 'share' ? `发布 #${item.id}` : `遗忘 #${item.id}`}</h4>
      {action === 'edit' ? <>
        <MemoryField label="标题"><input value={title} disabled={busy} onChange={e => setTitle(e.target.value)} /></MemoryField>
        <MemoryField label="正文" hint="保留归属与身份；发生版本冲突时保留草稿，请刷新后核对。"><textarea required disabled={busy} value={body} onChange={e => setBody(e.target.value)} rows={7} /></MemoryField>
      </> : action === 'share' ? <>
        <MemorySelect label="授权目标" value={kind} disabled={busy} onChange={value => { setKind(value); setTarget('') }} options={[{ value: 'agent', label: '指定 Agent' }, { value: 'project', label: '指定项目' }, { value: 'session', label: '指定会话' }, { value: 'user', label: '全部 Agent（用户范围）' }]} />
        {kind !== 'user' && <MemorySelect label="目标名称" value={target} searchable disabled={busy} onChange={setTarget} options={[{ value: '', label: '请选择目标' }, ...[...names].map(([value, name]) => ({ value, label: `${name} · ${value}` }))]} />}
        <p className="mem-note">{kind === 'user' ? '所有 Agent 将获得该条目的读取授权。' : `仅向所选 ${kind} 增加读取授权。`}条目的 Owner 与 Layer 不变。</p>
      </> : <p className="mem-note">将停用此条目，阻止同指纹自动重建并写入审计。历史快照与审计仍保留。</p>}
      {action !== 'edit' && <MemoryField label="备注 / 原因"><textarea disabled={busy} value={note} onChange={e => setNote(e.target.value)} rows={2} /></MemoryField>}
      <div className="mem-row"><MemoryButton type="submit" disabled={busy || (action === 'edit' && !body.trim()) || (action === 'share' && kind !== 'user' && !target)} variant={action === 'forget' ? 'danger' : 'primary'}>{busy ? '提交中…' : action === 'forget' ? '确认遗忘此条目' : '确认提交'}</MemoryButton><MemoryButton type="button" disabled={busy}  onClick={() => setAction(null)}>取消</MemoryButton></div>
    </form>}
    {error && <p role="alert" className="mem-error">{error}</p>}{message && <p role="status" className="mem-note">{message}</p>}
    <p className="mem-body">{item.body}</p>
    <div className="mem-detail-time"><strong>记住于 {time(item.createdAt)}</strong><p>{occurrence(item) || (item.temporalJson ? '长期信息，无特定发生时间。' : '发生时间尚未标定，原文中的相对日期不能按今天理解。')}</p>{item.pinned && <p>固定可避免数量归档，已有有效期仍然生效。</p>}{item.validUntil!=null && <p>有效期至 {time(item.validUntil)}，之后停止自动使用。</p>}{item.expiresSoft!=null && <p>软保留期至 {time(item.expiresSoft)}，到期保留历史。</p>}</div>
    <dl className="mem-facts">
      <dt>归属 Owner</dt><dd>{ownerNames[item.owner ?? ''] ?? item.owner ?? '未标记'}</dd>
      <dt>内容域 Domain</dt><dd>{domainNames[item.domain ?? ''] ?? item.domain ?? '未标记'}</dd>
      <dt>默认范围 / 授权</dt><dd>{item.layer === 'L4' ? 'L4 仅通过显式发布授权读取' : scopeNames[scopeKind(item, [])]}；{sharesAvailable ? `${grants.length} 项显式授权（见下方目标）` : '授权未读取，完整共享范围未知'}{status(item) !== '有效' && '（条目失效，当前不可注入）'}</dd>
      <dt>所属 Agent</dt><dd>{item.agentId ? agentNames.get(item.agentId) ?? item.agentId : '未绑定'}</dd>
      <dt>项目</dt><dd>{item.projectId ? projectNames.get(item.projectId) ?? item.projectId : '无项目绑定'}</dd>
      <dt>会话 / 任务</dt><dd>{item.sessionId ? sessionNames.get(item.sessionId) ?? item.sessionId : '无会话'} / {item.taskId ?? '无任务'}</dd>
    </dl>
    {access && <section className="mem-note"><strong>当前 Agent 为什么能读</strong>{access.reasons.map(r => <p key={r}>{r}</p>)}<p>Agent {access.editableByAgent ? '可按自身权限编辑' : '不可自行编辑'}；你以用户管理身份操作。</p></section>}
    <MemorySources key={item.id} id={item.id} />
    <details className="mem-metadata"><summary>生命周期与来源 · 保留 / 期限 / 生产者</summary><dl className="mem-facts">
      <dt>生产者</dt><dd>{item.producerAgent ? agentNames.get(item.producerAgent) ?? item.producerAgent : '未留存'}</dd>
      <dt>类别 / 保留</dt><dd>{item.scopeLabel} / {item.retention ?? '未设置'}</dd>
      <dt>来源 / 证据级别</dt><dd>{item.sourceKind ?? item.source} / {item.evidenceLevel ?? '未标记'}</dd>
      <dt>模型置信度</dt><dd>{item.confidence.toFixed(2)}（不是事实准确率）</dd>
      <dt>有效期 / 软过期</dt><dd>{time(item.validUntil)} / {time(item.expiresSoft)}</dd>
      <dt>学习 / 失效时间</dt><dd>{time(item.learnedAt)} / {time(item.invalidAt)}</dd>
      <dt>更新 / 创建</dt><dd>{time(item.updatedAt)} / {time(item.createdAt)}</dd>
      <dt>取代 / 提升来源</dt><dd>{item.supersededBy ? `被 #${item.supersededBy} 取代` : '无取代'} / {item.promotedFrom ? `#${item.promotedFrom}` : '无提升来源'}</dd>
      <dt>最近快照选入</dt><dd>{selectedCount == null ? '快照索引读取失败' : `${selectedCount} 次 · 最近最多 100 份全局启动快照`}</dd>
    </dl></details>
    <section><h4>显式发布授权 · {sharesAvailable ? grants.length : '未读取'}</h4>{!sharesAvailable && <p role="alert" className="mem-error">授权读取失败，现有列表仅供参考；刷新成功后再判断共享范围。</p>}<p className="mem-muted">撤回仅移除此目标的授权。其他授权、全局规则和项目共享权限仍然生效。</p>{grants.map(s => <div className="mem-grant" key={s.id}><strong>{s.toKind === 'user' ? '全部 Agent（用户范围）' : `${s.toKind} · ${(s.toKind === 'agent' ? agentNames : s.toKind === 'project' ? projectNames : sessionNames).get(s.toRef ?? '') ?? s.toRef}`}</strong><span>{s.note}</span>{revokeId === s.id ? <div className="mem-row"><MemoryButton variant="danger" disabled={busy} onClick={() => void run(() => revokeMemoryShare(s.id), '已撤回该目标的发布授权。')}>确认撤回</MemoryButton><MemoryButton disabled={busy} onClick={() => setRevokeId(null)}>取消</MemoryButton></div> : <MemoryButton disabled={busy} onClick={() => { setAction(null); setRevokeId(s.id) }}>撤回授权</MemoryButton>}</div>)}</section>
    <section><h4>变更审计 · 最近最多 100 条</h4>{auditLoading ? <p>正在读取审计…</p> : auditError ? <div className="mem-error" role="alert">{auditError}<MemoryButton onClick={() => setAuditTick(v => v + 1)}>重试</MemoryButton></div> : revisions.length ? <ol className="mem-audit">{revisions.map(r => <li key={r.id}><strong>{r.op}</strong><span>{time(r.createdAt)} · {r.actorKind}:{r.actorId ?? '未留存'}</span><p>{r.reason || '未留存原因'}{r.turnId && ` · 执行 ${r.turnId}`}</p><details><summary>查看变更前后</summary><pre>{r.beforeJson || '无前版本'}</pre><pre>{r.afterJson || '无后版本'}</pre></details></li>)}</ol> : <p className="mem-muted">暂无审计记录。</p>}</section>
  </aside>
}
