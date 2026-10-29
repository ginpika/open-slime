import { useEffect, useRef, useState } from 'react'
import { getStudy, legacyReports, listStudies, type HistoryFilter, type LegacyReport, type StudyDetail, type StudyPage } from '../../api/economy'
import { webError } from '../../api/webSnapshots'
import ChatMarkdown from '../../components/ChatMarkdown'
import { AppIcon } from '../../components/AppIcon'
import { Select } from '../../components/Select'
import { ReportView, StepResult } from './StudyResults'
import { rememberStudy } from './studySession'
import { TIMEFRAME_LABEL } from './strategy'
import './economy.css'

const emptyFilter: HistoryFilter = { kind: '', symbol: '', from: '', to: '', page: 1 }
export default function EconomyDatabase() {
  const [form, setForm] = useState(emptyFilter)
  const [filter, setFilter] = useState(emptyFilter)
  const [data, setData] = useState<StudyPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [formError, setFormError] = useState('')
  const [revision, setRevision] = useState(0)
  const [selectedId, setSelectedId] = useState('')
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [detailError, setDetailError] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailRevision, setDetailRevision] = useState(0)
  const detailPanel = useRef<HTMLElement>(null)
  const [reportId, setReportId] = useState('')
  const [legacyOpen, setLegacyOpen] = useState(false)
  const [legacy, setLegacy] = useState<LegacyReport[] | null>(null)
  const [legacyError, setLegacyError] = useState('')
  const [legacyRevision, setLegacyRevision] = useState(0)

  useEffect(() => {
    let stopped = false
    setLoading(true); setError('')
    void listStudies(filter).then(result => { if (!stopped) setData(result) })
      .catch(e => { if (!stopped) setError(webError(e)) }).finally(() => { if (!stopped) setLoading(false) })
    return () => { stopped = true }
  }, [filter, revision])
  useEffect(() => {
    if (!selectedId) return
    detailPanel.current?.focus({ preventScroll: true })
    if (matchMedia('(max-width:75rem)').matches) detailPanel.current?.scrollIntoView({ block: 'start' })
    let stopped = false
    setDetail(null); setDetailError(''); setDetailLoading(true)
    void getStudy(selectedId).then(result => {
      if (!stopped) { setDetail(result); setReportId(result.reports[0]?.id ?? '') }
    }).catch(e => { if (!stopped) setDetailError(webError(e)) }).finally(() => { if (!stopped) setDetailLoading(false) })
    return () => { stopped = true }
  }, [selectedId, detailRevision])
  useEffect(() => {
    if (!legacyOpen) return
    let stopped = false
    setLegacyError('')
    void legacyReports().then(rows => { if (!stopped) setLegacy(rows) }).catch(e => { if (!stopped) setLegacyError(webError(e)) })
    return () => { stopped = true }
  }, [legacyOpen, legacyRevision])

  const newStudy = () => { rememberStudy(null); location.hash = '/economy/research' }
  const report = detail?.reports.find(r => r.id === reportId)
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1
  return <section className="eco-page eco-database-page scrollbar-slim" aria-labelledby="eco-database-title">
    <header className="eco-header">
      <div><a className="eco-back" href="#/economy"><AppIcon name="arrow-left"/>经济</a><h1 id="eco-database-title">观测数据库</h1><p>让每次判断留下证据，按标的与日期持续回看。</p></div>
      <div className="eco-inline-actions"><a className="eco-button" href="#/economy/research">返回工作台</a><button className="eco-primary" onClick={newStudy}><AppIcon name="plus"/>新一轮观测</button></div>
    </header>
    <div className={`eco-database-layout${selectedId ? ' is-inspecting' : ''}`}>
      <div className="eco-database-list scrollbar-slim">
        <form className="eco-database-filters" noValidate onSubmit={e => {
          e.preventDefault()
          if (form.from && form.to && form.from > form.to) { setFormError('开始日期不能晚于结束日期。'); return }
          setFormError(''); setFilter({ ...form, symbol: form.symbol.trim(), page: 1 })
        }}>
          <div className="eco-field"><span>标的类型</span><Select className="eco-select" menuClassName="eco-select-menu" label="查询标的类型" value={form.kind} onChange={value => setForm({ ...form, kind: value })} options={[{value:'',label:'全部标的'},{value:'market',label:'大盘'},{value:'stock',label:'个股'}]}/></div>
          <label>标的名称 / 代码<input aria-label="查询标的" placeholder="搜索名称或代码" value={form.symbol} maxLength={160} onChange={e => setForm({ ...form, symbol: e.target.value })}/></label>
          <label>开始日期<input type="date" aria-label="开始日期" value={form.from} onChange={e => setForm({ ...form, from: e.target.value })}/></label>
          <label>结束日期<input type="date" aria-label="结束日期" value={form.to} onChange={e => setForm({ ...form, to: e.target.value })}/></label>
          <div className="eco-filter-actions"><button className="eco-primary" disabled={loading}><AppIcon name="search"/>查询观测</button><button type="button" disabled={loading} onClick={() => { setForm(emptyFilter); setFilter({ ...emptyFilter }); setFormError('') }}>重置筛选</button></div>
        </form>
        {formError && <p role="alert" className="eco-error">{formError}</p>}
        <section aria-label="观测记录" aria-busy={loading}>
          <div className="eco-list-heading"><h2>观测记录</h2><button disabled={loading} onClick={() => setRevision(v => v + 1)}><AppIcon name="refresh"/>刷新记录</button></div>
          {error && <div role="alert" className="eco-error">记录读取失败：{error}<button onClick={() => setRevision(v => v + 1)}>重试读取</button></div>}
          {loading && <p role="status" className="eco-help">正在读取观测记录…</p>}
          {data && !loading && !error && <>
            <p className="eco-meta">共 {data.total} 轮观测 · 第 {data.page} / {pages} 页</p>
            {data.items.length ? <ul className="eco-history">{data.items.map(row => <li key={row.study.id}><button aria-label={`查看 ${row.study.symbol} ${row.study.tradeDate} 观测`} aria-pressed={selectedId === row.study.id} onClick={() => { setDetail(null); setSelectedId(row.study.id) }}>
              <div className="eco-history-title"><strong>{row.study.symbol}</strong><span className="eco-badge">{row.study.kind === 'market' ? '大盘' : '个股'}</span><time>{row.study.tradeDate}</time></div>
              <div className="eco-history-stats"><span>{row.completedSteps}/3 步完成</span><span>{row.observationCount} 张截图</span><span>{row.reportCount} 份总结</span>{row.latestScore !== null && <strong>机会评分 {row.latestScore}</strong>}</div>
              {row.latestSummary && <p>{row.latestSummary}</p>}
              <div className="eco-history-footer"><small>创建于 {new Date(row.study.createdAt).toLocaleString()}</small><span>查看证据与总结 <AppIcon name="arrow-right" size={14}/></span></div>
            </button></li>)}</ul> : <div className="eco-empty eco-database-empty"><AppIcon name="archive" size={28}/><h3>还没有匹配的观测</h3><p>调整筛选条件，或到工作台完成第一次周线分析。</p><button onClick={newStudy}>开始新观测</button></div>}
            <nav className="eco-pagination" aria-label="观测记录分页"><button disabled={data.page <= 1} onClick={() => setFilter({ ...filter, page: data.page - 1 })}><AppIcon name="chevron-left"/>上一页</button><span>{data.page} / {pages}</span><button disabled={data.page >= pages} onClick={() => setFilter({ ...filter, page: data.page + 1 })}>下一页<AppIcon name="chevron-right"/></button></nav>
          </>}
        </section>
        <details className="eco-strategy" open={legacyOpen} onToggle={e => setLegacyOpen(e.currentTarget.open)}>
          <summary>旧版单图分析记录</summary><p className="eco-help">原有记录保留供回看，不作为三图策略报告。</p>
          {legacyError && <p role="alert" className="eco-error">{legacyError}<button onClick={() => setLegacyRevision(v => v + 1)}>重试旧版记录</button></p>}
          {legacyOpen && !legacy && !legacyError && <p role="status" className="eco-help">正在读取旧版记录…</p>}
          {legacy?.map(r => <details className="eco-strategy" key={r.id}><summary>{r.symbol} · {new Date(r.analyzedAt).toLocaleString()}</summary><ChatMarkdown content={r.summary}/><ChatMarkdown content={r.technical}/></details>)}
          {legacy?.length === 0 && <p className="eco-help">没有旧版记录。</p>}
        </details>
      </div>
      {selectedId && <section ref={detailPanel} tabIndex={-1} className="eco-record-detail scrollbar-slim" aria-label="观测详情" aria-busy={detailLoading}>
        <div className="eco-list-heading"><h2>观测详情</h2><div className="eco-inline-actions">{detail && <button className="eco-primary" onClick={() => { rememberStudy(detail.study.id); location.hash = '/economy/research' }}><AppIcon name="arrow-right"/>继续本轮观测</button>}<button aria-label="关闭观测详情" onClick={() => {
          const selected = document.querySelector<HTMLButtonElement>('.eco-history button[aria-pressed=true]')
          selected?.focus({ preventScroll: true }); selected?.scrollIntoView({ block: 'nearest' })
          setSelectedId(''); setDetail(null)
        }}><AppIcon name="close"/>关闭</button></div></div>
        {detailLoading && <p role="status" className="eco-help">正在读取截图与分析记录…</p>}
        {detailError && <p role="alert" className="eco-error">{detailError}<button onClick={() => setDetailRevision(v => v + 1)}>重试观测详情</button></p>}
        {detail && <>
          <div className="eco-record-heading"><span className="eco-badge">{detail.study.kind === 'market' ? '大盘' : '个股'} · {detail.study.tradeDate}</span><h3>{detail.study.symbol}</h3><p className="eco-help">{detail.observations.length} 张已保存截图 · {detail.reports.length} 份总结</p></div>
          {detail.reports.length > 0 ? <><div className="eco-report-picker"><span>总结版本</span><Select className="eco-select" menuClassName="eco-select-menu" label="总结版本" value={reportId} onChange={setReportId} searchable options={detail.reports.map((r,i) => ({value:r.id,label:`版本 ${detail.reports.length-i} · ${new Date(r.analyzedAt).toLocaleString()} · ${r.agentName}`}))}/></div>{report && <ReportView report={report} observations={detail.observations}/>}</> : <p className="eco-help">本轮尚无总结，可继续完成三步分析后提交。</p>}
          <section className="eco-section"><h3>全部截图与分步分析</h3><p className="eco-help">包含重做前的截图，每条记录保留当时的分析。</p>
            {detail.observations.map(o => <details className="eco-strategy" key={o.id}><summary>{TIMEFRAME_LABEL[o.timeframe]} · {new Date(o.capturedAt).toLocaleString()} · {o.status === 'COMPLETE' ? '已完成' : o.status === 'FAILED' ? '分析失败' : '分析中'}</summary><StepResult key={o.id} observation={o}/></details>)}
            {!detail.observations.length && <p className="eco-help">尚未保存截图。</p>}
          </section>
        </>}
      </section>}
    </div>
  </section>
}
