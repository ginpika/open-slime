import { useCallback, useEffect, useId, useState } from 'react'
import { maTodoRuns, type MaWorkflowTodo } from '../../api/client'
import { collaborationTaskQuality, type TaskQuality } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'
import { ReviewSection, ReviewTabs, ReviewText } from './ReviewContent'
import WorkRunReader from './WorkRunReader'
import { WORK_STATUS } from './workReviewModel'
import './workReview.css'

/** 刷新失败保留上一份记录；取消后的响应不能覆盖下一次选择。 */
function useReviewSnapshot<T>(load: () => Promise<T>) {
  const [snapshot, setSnapshot] = useState<{ value: T; at: number } | null>(null)
  const [error, setError] = useState<string>()
  const [version, setVersion] = useState(0)
  const [settled, setSettled] = useState<{ load: () => Promise<T>; version: number } | null>(null)
  const loading = settled?.load !== load || settled?.version !== version
  useEffect(() => {
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    const update = async () => {
      try {
        const value = await load()
        if (alive) { setSnapshot({ value, at: Date.now() }); setError(undefined) }
      } catch (e) { if (alive) setError(e instanceof Error ? e.message : String(e)) }
      finally {
        if (alive) { setSettled({ load, version }); timer = setTimeout(update, 3000) }
      }
    }
    void update()
    return () => { alive = false; clearTimeout(timer) }
  }, [load, version])
  return { snapshot, error, loading, retry: () => setVersion(v => v + 1) }
}

type ReviewTab = 'overview' | 'quality' | 'runs'

export default function WorkReview({ todo, sessionId, todoOf, onSelect }: {
  todo: MaWorkflowTodo; sessionId: string; todoOf: (id: string) => MaWorkflowTodo | undefined; onSelect: (s: { kind: 'agent' | 'todo'; id: string }) => void
}) {
  const [tab, setTab] = useState<ReviewTab>('overview'), [limit, setLimit] = useState(20)
  const panelId = useId()
  const loadRuns = useCallback(() => maTodoRuns(todo.id, limit), [todo.id, limit])
  const loadQuality = useCallback(() => collaborationTaskQuality(sessionId, todo.id), [sessionId, todo.id])
  const runs = useReviewSnapshot(loadRuns), quality = useReviewSnapshot(loadQuality)
  const openFindings = quality.snapshot?.value.findings?.filter(f => f.status === 'OPEN').length ?? 0
  const dependencies = todo.dependsOn ?? []

  return <div className="work-review">
    <header className="work-review-header">
      <div className="work-review-eyebrow">工作审阅<span>#{todo.seq}</span></div>
      <h2>{todo.title}</h2>
      <div className="work-review-meta"><span className="work-review-pill" data-state={todo.status}>{WORK_STATUS[todo.status] ?? todo.status}</span><span><AppIcon name="agent" />{todo.ownerName ? `负责人 ${todo.ownerName}` : '尚未指定负责人'}</span>{todo.createdByName && <span>创建人 {todo.createdByName}</span>}</div>
    </header>
    <ReviewTabs label="工作审阅内容" tabs={[{ id: 'overview', label: '成果概览' }, { id: 'quality', label: '验收与问题', count: openFindings || undefined }, { id: 'runs', label: '执行记录', count: runs.snapshot?.value.length }]} value={tab} onChange={setTab} panelId={panelId} />
    <div className={`work-review-panel ${tab === 'runs' ? 'work-review-panel-runs' : 'scrollbar-slim'}`} role="tabpanel" id={panelId} aria-label={{ overview: '成果概览', quality: '验收与问题', runs: '执行记录' }[tab]} tabIndex={0} key={tab}>
      {tab === 'overview' && <div className="work-review-document">
        {todo.blockReason && <ReviewSection title="当前受阻原因" tone="work-review-warning" text={todo.blockReason} />}
        <ReviewSection title={todo.status === 'done' ? '工作结论' : '已记录的成果'} text={todo.result ?? undefined}>
          {!todo.result && <div className="work-review-awaiting"><AppIcon name="doc" /><p>这项工作尚未记录成果。</p><button type="button" className="work-review-button" onClick={() => setTab('runs')}>查看执行记录<AppIcon name="chevron-right" /></button></div>}
        </ReviewSection>
        {openFindings > 0 && <button type="button" className="work-review-attention" onClick={() => setTab('quality')}><AppIcon name="alert" />{openFindings} 项检查意见待解决<span>查看验收与问题 →</span></button>}
        <ReviewSection title="工作说明" text={todo.detail ?? undefined}>{!todo.detail && <p className="work-review-empty">未填写工作说明。</p>}</ReviewSection>
        {dependencies.length > 0 && <ReviewSection title="前置工作"><ul className="work-review-dependencies">{dependencies.map(id => {
          const dependency = todoOf(id)
          return <li key={id}>{dependency ? <button type="button" onClick={() => onSelect({ kind: 'todo', id })}><span>{dependency.title}</span><span className="work-review-pill" data-state={dependency.status}>{WORK_STATUS[dependency.status] ?? dependency.status}</span><AppIcon name="chevron-right" /></button> : <span>{id}（工作已不可用）</span>}</li>
        })}</ul></ReviewSection>}
      </div>}
      {tab === 'quality' && <div className="work-review-document collab-task-quality">
        {quality.error && <p role="alert" className="work-review-notice">验收记录更新失败：{quality.error}。<button type="button" onClick={quality.retry}>重试</button></p>}
        {quality.loading && !quality.snapshot && <p role="status" className="work-review-empty">正在载入验收记录…</p>}
        {quality.snapshot && <QualityReader quality={quality.snapshot.value} />}
      </div>}
      {tab === 'runs' && <WorkRunReader runs={runs.snapshot?.value ?? []} now={runs.snapshot?.at ?? 0} loading={runs.loading} error={runs.error} onRetry={runs.retry} limit={limit} onMore={() => setLimit(v => Math.min(v + 20, 50))} />}
    </div>
  </div>
}

function QualityReader({ quality }: { quality: TaskQuality }) {
  const { contract, findings, diagnosis } = quality
  const ordered = [...(findings ?? [])].sort((a, b) => Number(b.status === 'OPEN') - Number(a.status === 'OPEN'))
  return <>
    {diagnosis && <ReviewSection title={!diagnosis.state || diagnosis.state === 'OPEN' ? '已交回主控诊断' : diagnosis.state === 'REASSIGNED' ? '主控已调整派工' : '诊断问题已解决'} tone={!diagnosis.state || diagnosis.state === 'OPEN' ? 'work-review-warning' : ''}>
      <ReviewText text={diagnosis.detail} />
      {diagnosis.decision && <div className="work-review-evidence"><h4>处理依据</h4><ReviewText text={diagnosis.decision} /></div>}
      {diagnosis.runId && <a className="work-review-link" href={`#/coding?run=${encodeURIComponent(diagnosis.runId)}`}><AppIcon name="code" />查看保留的编码成果<AppIcon name="external" /></a>}
    </ReviewSection>}
    {contract && <ReviewSection title="成果与验收标准">
      <ReviewText text={contract.outcome} />
      <ol className="work-review-criteria">{contract.acceptanceCriteria.map(c => <li key={c.id}><span>{c.id}</span><div className="work-review-prose">{c.text}</div></li>)}</ol>
      <div className="work-review-evidence"><h4>收工条件</h4><div className="work-review-prose">{contract.stopCondition}</div></div>
    </ReviewSection>}
    <ReviewSection title={`检查意见（${ordered.filter(f => f.status === 'OPEN').length} 项待解决）`}>
      {ordered.length ? <ol className="work-review-findings">{ordered.map(f => <li key={f.id}>
        <div className="work-review-finding-head"><span className="work-review-pill" data-state={f.status === 'OPEN' && f.severity === 'BLOCKER' ? 'blocked' : f.status.toLowerCase()}>{f.status === 'RESOLVED' ? '已解决' : f.status === 'ADVISORY' ? '一般建议' : f.severity === 'BLOCKER' ? '阻碍验收' : '待解决'}</span><span>{f.id}</span><code>{f.location}</code></div>
        <ReviewText text={f.expected} />
        <div className="work-review-evidence"><h4>检查依据</h4><div className="work-review-prose">{f.basis}</div></div>
        {f.resolutionEvidence && <details className="work-review-resolution"><summary>解决证据</summary><ReviewText text={f.resolutionEvidence} /></details>}
      </li>)}</ol> : <p className="work-review-empty">尚未记录检查意见。</p>}
    </ReviewSection>
    {!contract && <p className="work-review-empty">尚未记录成果与验收标准。</p>}
  </>
}
