import { useEffect, useState } from 'react'
import { memorySnapshot, memorySnapshots, type MemoryContext, type SnapshotDetail, type SnapshotSummary } from '../../api/memoryObservation'
import type { MemoryItem } from '../../api/client'
import { MemoryButton } from './MemoryControls'
import { errorText, identityNames, status, time } from './model'
/* 异步观察查询开始时必须清除上个上下文的结果，避免旧权限 / 历史留痕短暂冒充新范围。 */
/* oxlint-disable react/set-state-in-effect */

export default function MemoryReplay({ context, items, agentNames, projectNames, inspect, refresh }: {
  context: Partial<MemoryContext>; items: MemoryItem[]; agentNames: Map<string, string>; projectNames: Map<string, string>
  inspect: (id: number) => void; refresh: number
}) {
  const [list, setList] = useState<SnapshotSummary[]>([])
  const [selected, setSelected] = useState('')
  const [detail, setDetail] = useState<SnapshotDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [retry, setRetry] = useState(0)
  const { agentId, projectId, sessionId } = context
  useEffect(() => {
    let live = true
    setLoading(true); setError(''); setSelected(''); setDetail(null)
    memorySnapshots({ agentId, projectId, sessionId }).then(v => { if (live) { setList(v); setSelected(v[0]?.turnId ?? '') } }).catch(e => { if (live) setError(errorText(e)) }).finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [agentId, projectId, sessionId, refresh, retry]) // 查询只支持 Agent / 项目 / 会话
  useEffect(() => {
    let live = true
    setDetail(null); setDetailError('')
    if (!selected) { setDetailLoading(false); return }
    setDetailLoading(true)
    memorySnapshot(selected).then(v => { if (live) setDetail(v) }).catch(e => { if (live) setDetailError(errorText(e)) }).finally(() => { if (live) setDetailLoading(false) })
    return () => { live = false }
  }, [selected, refresh, retry])
  const current = new Map(items.map(i => [i.id, i]))
  return <section className="mem-replay">
    <div className="mem-section-heading"><div><h3>每次执行留下了什么</h3><p className="mem-muted">筛选后最近最多 100 份。启动选入、实际发送、回答有效利用是三种不同证据。</p></div><MemoryButton onClick={() => setRetry(v => v + 1)}>重读回放</MemoryButton></div>
    {loading ? <p role="status">正在读取快照索引…</p> : error ? <p role="alert" className="mem-error">{error}</p> : !list.length ? <div className="mem-empty">此范围还没有保存的快照。实际执行后再来观察；浏览页面不会生成快照。</div> : <div className="mem-replay-layout"><nav className="mem-snapshot-list" aria-label="执行记录">{list.map(s => <button key={s.turnId} className={s.turnId === selected ? 'is-selected' : ''} onClick={() => setSelected(s.turnId)} aria-pressed={s.turnId === selected}><strong>{agentNames.get(s.agentId || 'builtin') ?? s.agentId ?? '内置 Agent'} · {s.turnId}</strong><span>{time(s.builtAt)}</span><span>{s.itemCount} 条记忆 · {s.workCount ?? '未知'} 条工作项</span><small>{s.complete ? '完整启动快照' : '缺少完整快照'} · {s.projectId ? projectNames.get(s.projectId) ?? s.projectId : '无项目'}</small></button>)}</nav>
      <div className="mem-replay-detail">{detailLoading ? <p role="status">正在读取该执行的留痕…</p> : detailError ? <p role="alert" className="mem-error">{detailError}</p> : detail && <>
        <div className="mem-section-heading"><h3>执行 {detail.summary.turnId}</h3><span className="mem-tag">水位 {detail.summary.watermark}</span></div>
        <p className="mem-muted">{time(detail.summary.builtAt)} · 普通预算用量 {detail.summary.budgetTokens} token（不含豁免保护条目，非请求总 token）</p>
        <h4>① 启动时选中的版本</h4>
        {detail.content ? <>{detail.content.items.length ? detail.content.items.map(i => <article className="mem-frozen" key={i.id}><div className="mem-row"><strong>#{i.id} · {i.title || '未命名记忆'}</strong><span className="mem-tag">{i.layer} · v{i.version}</span></div><p className="mem-muted">{identityNames[i.identity ?? ''] ?? i.identity ?? '身份未留存'} · {current.has(i.id) ? `当前 v${current.get(i.id)!.version ?? 0} / ${status(current.get(i.id)!)}` : '当前条目不存在或未读取'}</p><p className="mem-body">{i.body}</p>{current.has(i.id) && <MemoryButton variant="quiet" onClick={() => inspect(i.id)}>检查当前条目</MemoryButton>}</article>) : <p className="mem-muted">启动快照没有长期条目。</p>}
          <h4>启动工作集 · {detail.content.work?.length ?? 0}</h4>{detail.content.work?.map(w => <details className="mem-frozen" key={w.id}><summary>#{w.id} · {w.kind} · {w.title || '工作材料'} · {w.status}</summary><p className="mem-body">{w.body}</p></details>)}
          {!!detail.content.progress?.length && <><h4>跨会话续接进度 · {detail.content.progress.length}</h4>{detail.content.progress.map(p => <details className="mem-frozen" key={p.sourceSessionId}><summary>{p.title || '来源会话'} · {time(p.observedAt)}</summary><p className="mem-muted">来源会话：{p.sourceSessionId} · 消息：{p.messageIds.join(', ')}</p><p className="mem-body">{p.body}</p></details>)}</>}
        </> : <p className="mem-note">这份记录缺少完整快照；已保存的引用 ID：{detail.itemIds.join(', ') || '无条目'}。当时正文与版本不可还原，不用当前正文替代历史。</p>}
        <h4>② 实际模型请求中的 Memory</h4><p className="mem-note">{detail.requestNote}</p>
        {detail.requests.map((r, index) => <details className="mem-request" key={index} open={index === 0}><summary>请求 {index + 1} · round {r.round} · {!r.requestRecorded ? '请求内容缺失 / 不可解析' : r.memorySectionPresent ? '有 Memory 段落' : '未携带 Memory 标记段落'}</summary>{r.memorySectionPresent ? <pre>{r.memoryText || 'Memory 标记段落为空'}</pre> : <p className="mem-muted">{r.requestRecorded ? '该已保存请求中没有 Memory 标记段落。' : '留痕不足，无法判断实际发送内容。'}</p>}<p className="mem-muted">响应{r.responseRecorded ? '已留存' : '未留存'}；不能据此判定回答是否正确使用记忆。</p></details>)}
        <h4>③ 如何评估效果</h4><p className="mem-muted">先核对选入与实际发送，再用你的测试问题检查回答是否正确引用、遵守规则，以及是否串用其他 Agent / 项目的内容。这里展示证据，不生成未经验证的效果分数。</p>
      </>}</div>
    </div>}
  </section>
}
