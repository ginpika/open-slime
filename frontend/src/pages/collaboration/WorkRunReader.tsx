import { useId, useMemo, useState } from 'react'
import type { MaWorkRun } from '../../api/client'
import { AppIcon } from '../../components/AppIcon'
import { ReviewPayload, ReviewSection, ReviewTabs } from './ReviewContent'
import { RUN_STATUS, runDuration, runMatches, runTime, runTools, type RunTool } from './workReviewModel'

type RunTab = 'output' | 'tools' | 'reasoning'

export default function WorkRunReader({ runs, now, loading, error, onRetry, limit, onMore }: {
  runs: MaWorkRun[]; now: number; loading: boolean; error?: string; onRetry: () => void; limit: number; onMore: () => void
}) {
  const [query, setQuery] = useState(''), [status, setStatus] = useState('')
  const [selectedRun, setSelectedRun] = useState<MaWorkRun | null>(null)
  const [tab, setTab] = useState<RunTab>('output')
  const panelId = useId()
  const filtered = useMemo(() => runs.filter(run => runMatches(run, query, status)), [runs, query, status])
  // 后台刷新不追着最新一条跳动；主动更改筛选后才选取该结果集中的记录。
  const selected = runs.find(run => run.id === selectedRun?.id) ?? selectedRun ?? filtered[0]
  // 首次数据到达时固定选择；即使新记录把它挤出最近一页，也保留正在阅读的副本。
  if (selected && selected !== selectedRun) setSelectedRun(selected)
  const tools = useMemo(() => selected ? runTools(selected) : [], [selected])
  const reasoning = selected?.reasoning || selected?.trace?.filter(s => s.type === 'reasoning').map(s => s.text).filter(Boolean).join('\n\n')
  const choose = (id: number) => { setSelectedRun(runs.find(run => run.id === id) ?? null); setTab('output') }
  const statuses = [...new Set(runs.map(r => r.status))]

  return <div className="work-runs">
    <div className="work-runs-toolbar">
      <label className="work-runs-search"><AppIcon name="search" /><input type="search" aria-label="搜索执行记录" placeholder="搜索成员、产出、错误或工具内容" value={query} onChange={e => { setQuery(e.target.value); setSelectedRun(null) }} /></label>
      <select aria-label="筛选执行状态" value={status} onChange={e => { setStatus(e.target.value); setSelectedRun(null) }}><option value="">全部状态</option>{statuses.map(s => <option key={s} value={s}>{RUN_STATUS[s] ?? s}</option>)}</select>
      <button type="button" className="work-review-button" onClick={onRetry} disabled={loading}><AppIcon name="refresh" />{loading ? '载入中' : '刷新'}</button>
    </div>
    {error && <p role="alert" className="work-review-notice">执行记录更新失败：{error}。{runs.length > 0 && '已载入的记录仍可阅读。'}<button type="button" onClick={onRetry}>重试</button></p>}
    <div className="work-runs-mobile-select">
      <select aria-label="选择执行记录" value={selected?.id ?? ''} onChange={e => choose(Number(e.target.value))} disabled={!filtered.length}>
        {!filtered.length && <option value="">{loading ? '正在载入…' : runs.length ? '没有匹配的记录' : '还没有执行记录'}</option>}
        {selected && !filtered.some(run => run.id === selected.id) && <option value={selected.id}>#{selected.id} · 正在审阅的记录</option>}
        {filtered.map(run => <option key={run.id} value={run.id}>#{run.id} · {run.peerName || run.peerId || '成员未记录'} · {RUN_STATUS[run.status] ?? run.status} · {runTime(run.startedAt)}</option>)}
      </select>
      {runs.length >= limit && <button type="button" className="work-review-button" onClick={onMore} disabled={loading || limit >= 50}>{limit >= 50 ? '已达 50 次' : '更早记录'}</button>}
    </div>
    <div className="work-runs-layout">
      <nav className="work-runs-list scrollbar-slim" aria-label="执行历史">
        <div className="work-runs-list-heading"><span>{query || status ? `找到 ${filtered.length} 次` : `最近 ${runs.length} 次执行`}</span><span>新 → 旧</span></div>
        {loading && !runs.length && <p role="status" className="work-review-empty">正在载入执行记录…</p>}
        {!loading && !runs.length && !error && <p className="work-review-empty">还没有执行记录，它可能还未开工。</p>}
        {!!runs.length && !filtered.length && <p className="work-review-empty">没有匹配的记录。<button type="button" onClick={() => { setQuery(''); setStatus(''); setSelectedRun(null) }}>清除筛选</button></p>}
        {filtered.map(run => <button type="button" key={run.id} className="work-run-item" aria-current={selected?.id === run.id ? 'true' : undefined} onClick={() => choose(run.id)}>
          <span className="work-run-item-top"><strong>{run.peerName || run.peerId || '成员未记录'}</strong><span className="work-review-pill" data-state={run.status}>{RUN_STATUS[run.status] ?? run.status}</span></span>
          <span className="work-run-item-meta">#{run.id} · {runTime(run.startedAt)}</span>
          <span className="work-run-item-summary">{run.error || run.output?.replace(/[#*`]/g, '') || (run.status === 'running' ? '执行中，等待产出' : '未记录产出')}</span>
        </button>)}
        {runs.length >= limit && <button type="button" className="work-runs-more" onClick={onMore} disabled={loading || limit >= 50}>{limit >= 50 ? '已显示最近 50 次记录' : '载入更早记录'}</button>}
      </nav>
      <div className="work-run-reader">
        {selected ? <>
          <div className="work-run-reader-head"><div><h3>{selected.peerName || selected.peerId || '成员未记录'}<span className="work-review-pill" data-state={selected.status}>{RUN_STATUS[selected.status] ?? selected.status}</span></h3>
            <p>执行 #{selected.id} · {runTime(selected.startedAt)} · {runDuration(selected, now)}{selected.status === 'running' ? '（持续更新）' : ''}</p></div>
          </div>
          <ReviewTabs label="本次执行内容" tabs={[{ id: 'output', label: '产出与错误' }, { id: 'tools', label: '工具调用', count: tools.length }, { id: 'reasoning', label: '分析记录' }]} value={tab} onChange={setTab} panelId={panelId} />
          <div className="work-run-document scrollbar-slim" role="tabpanel" id={panelId} aria-label={{ output: '产出与错误', tools: '工具调用', reasoning: '分析记录' }[tab]} key={`${selected.id}:${tab}`} tabIndex={0}>
            {tab === 'output' && <>
              {selected.error && <ReviewSection title="错误与受阻原因" tone="work-review-warning" text={selected.error} />}
              {selected.output ? <ReviewSection title="本次产出" text={selected.output} /> : <p className="work-review-empty">{selected.status === 'running' ? '本次执行尚未返回产出，可查看工具调用和分析记录。' : '本次执行未记录产出。'}</p>}
            </>}
            {tab === 'tools' && (tools.length ? <ol className="work-tool-list">{tools.map((tool, index) => <ToolReview key={tool.key} tool={tool} index={index} />)}</ol> : <p className="work-review-empty">本次执行没有工具调用记录。</p>)}
            {tab === 'reasoning' && (reasoning ? <ReviewSection title="分析记录" text={reasoning} /> : <p className="work-review-empty">本次执行未保存分析记录。</p>)}
          </div>
        </> : <div className="work-review-empty work-run-placeholder"><AppIcon name="doc" /><p>选择一次执行，审阅产出和过程。</p></div>}
      </div>
    </div>
  </div>
}

function ToolReview({ tool, index }: { tool: RunTool; index: number }) {
  const [open, setOpen] = useState(false)
  return <li><details onToggle={e => setOpen(e.currentTarget.open)}>
    <summary><span className="work-tool-number">{index + 1}</span><strong>{tool.name}</strong><span className="work-tool-state">{tool.result === undefined ? '未记录返回内容' : '已记录返回内容'}</span><AppIcon name="chevron-down" /></summary>
    {open && <>
      {tool.args && <ReviewSection title="调用参数"><ReviewPayload text={tool.args} label="调用参数" /></ReviewSection>}
      {tool.result !== undefined && <ReviewSection title="返回内容"><ReviewPayload text={tool.result} label="返回内容" /></ReviewSection>}
    </>}
  </details></li>
}
