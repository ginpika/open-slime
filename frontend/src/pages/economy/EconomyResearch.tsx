import { useEffect, useRef, useState } from 'react'
import {
  analyzeStep, createStudy, getStudy, retryStep, submitStudy,
  type Observation, type ResearchKind, type ResearchSource, type StudyDetail, type Timeframe,
} from '../../api/economy'
import { listMaPeers, type MaPeer } from '../../api/client'
import { isCodingAgent } from '../../api/agentRuntime'
import { webError } from '../../api/webSnapshots'
import { AppIcon } from '../../components/AppIcon'
import { Select } from '../../components/Select'
import ResearchBrowser from './ResearchBrowser'
import WindowObserver from './WindowObserver'
import { ReportView, StepResult } from './StudyResults'
import { activeStudyId, rememberStudy } from './studySession'
import { STAGES, TIMEFRAMES, TIMEFRAME_LABEL, localTradeDate } from './strategy'
import './economy.css'

const SOURCE_MODE = 'economy.sourceMode'
const STEP_HINTS = ['确认标的与周线周期，观察趋势和市场环境。', '切换到同一标的日线，观察结构、量能与位置。', '切换到当日分时，观察尾盘承接与买入时机。']
type BusyAction = 'restore' | 'submit' | `analyze:${Timeframe}` | `retry:${string}`

export default function EconomyResearch() {
  const [mode, setMode] = useState<'browser' | 'window'>(() => {
    try { return sessionStorage.getItem(SOURCE_MODE) === 'window' ? 'window' : 'browser' } catch { return 'browser' }
  })
  useEffect(() => { try { sessionStorage.setItem(SOURCE_MODE, mode) } catch { /* Optional source preference. */ } }, [mode])
  const [source, setSource] = useState<ResearchSource | null>(null)
  const [kind, setKind] = useState<ResearchKind>(() => location.hash.startsWith('#/economy/stock') ? 'stock' : 'market')
  const [symbol, setSymbol] = useState('')
  const [date, setDate] = useState(localTradeDate)
  const [agentId, setAgentId] = useState('')
  const [agents, setAgents] = useState<MaPeer[]>([])
  const [notes, setNotes] = useState('')
  const [fundamentals, setFundamentals] = useState(true)
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [selectedReport, setSelectedReport] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(true)
  const [busy, setBusy] = useState<BusyAction | null>(null)
  const [error, setError] = useState('')
  const [loadError, setLoadError] = useState('')
  const working = useRef(false)

  const applyDetail = (next: StudyDetail) => {
    setDetail(next); setKind(next.study.kind); setSymbol(next.study.symbol); setDate(next.study.tradeDate)
    setSelectedReport(next.reports[0]?.id ?? ''); setNotes(next.observations[0]?.notes ?? '')
    setSettingsOpen(false); rememberStudy(next.study.id)
  }
  useEffect(() => {
    let stopped = false
    const id = activeStudyId()
    working.current = true; setBusy('restore')
    void Promise.allSettled([listMaPeers(), id ? getStudy(id) : Promise.resolve(null)]).then(([peers, study]) => {
      if (stopped) return
      const errors: string[] = []
      if (peers.status === 'fulfilled') {
        const available = peers.value.filter(p => !isCodingAgent(p))
        const previousAgent = study.status === 'fulfilled' ? study.value?.observations[0]?.agentId : undefined
        setAgents(available); setAgentId(available.find(a => a.id === previousAgent)?.id ?? available[0]?.id ?? '')
      } else errors.push(webError(peers.reason))
      if (study.status === 'fulfilled' && study.value) applyDetail(study.value)
      else if (study.status === 'rejected') { rememberStudy(null); errors.push(webError(study.reason)) }
      setLoadError(errors.join('；')); setBusy(null); working.current = false
    })
    return () => { stopped = true }
  }, [])

  const run = async (target: BusyAction, action: () => Promise<void>) => {
    if (working.current) return
    working.current = true; setBusy(target); setError('')
    try { await action() } catch (e) { setError(webError(e)) } finally { working.current = false; setBusy(null) }
  }
  const recordStep = (observation: Observation) => {
    setDetail(previous => previous && { ...previous, observations: [observation, ...previous.observations.filter(o => o.id !== observation.id)] })
    setSettingsOpen(false)
    if (observation.status === 'FAILED') setError('截图已入库，本步分析失败，可重试已保存截图。')
  }
  const analyze = (timeframe: Timeframe) => {
    if (!source) return
    const frame = source
    void run(`analyze:${timeframe}`, async () => {
      let study = detail?.study
      if (!study) {
        study = await createStudy({ kind, symbol: symbol.trim(), tradeDate: date })
        setDetail({ study, observations: [], reports: [] }); rememberStudy(study.id)
      }
      recordStep(await analyzeStep(study.id, { timeframe, agentId, browserId: frame.browserId, screenshot: frame.image, capturedAt: frame.capturedAt, sourceTitle: frame.title, notes }))
    })
  }
  const steps = TIMEFRAMES.map(tf => detail?.observations.find(o => o.timeframe === tf))
  const completed = steps.filter(o => o?.status === 'COMPLETE').length
  const complete = completed === 3
  const currentStep = complete ? 3 : steps.findIndex(o => o?.status !== 'COMPLETE')
  const report = detail?.reports.find(r => r.id === selectedReport) ?? null
  const hasChanges = !!report && steps.some((o, i) => o?.id !== report.observationIds[i])
  const submit = () => void run('submit', async () => {
    if (!detail || !complete) return
    const result = await submitStudy(detail.study.id, { agentId, observationIds: steps.map(o => o!.id), notes, fundamentals })
    setDetail(previous => previous && { ...previous, reports: [result, ...previous.reports] }); setSelectedReport(result.id)
  })
  const reset = () => {
    setDetail(null); setSelectedReport(''); setNotes(''); setError(''); setSettingsOpen(true); setDate(localTradeDate()); rememberStudy(null)
  }
  const jumpTo = (index: number) => {
    const element = document.getElementById(index === 3 ? 'eco-summary' : `eco-step-${TIMEFRAMES[index]}`)
    element?.scrollIntoView({ block: 'nearest' }); element?.focus({ preventScroll: true })
  }
  const missing = !symbol.trim() || !agentId || !date ? '先在观测设置中填写标的、日期并选择分析 Agent。' : !source ? '先打开图表或开始观察窗口，再保存当前周期的截图。' : null

  return <section className="eco-page eco-research scrollbar-slim" aria-labelledby="eco-research-title">
    <header className="eco-header">
      <div><a className="eco-back" href="#/economy"><AppIcon name="arrow-left"/>经济</a><h1 id="eco-research-title">隔夜策略工作台</h1><p>尾盘是否买入 · 下一交易日开盘后立即卖出</p></div>
      <div className="eco-inline-actions"><a className="eco-button" href="#/economy/database"><AppIcon name="archive"/>观测数据库</a><button disabled={!!busy} onClick={reset}><AppIcon name="plus"/>新一轮观测</button></div>
    </header>
    {loadError && <p role="alert" className="eco-error">加载失败：{loadError}<button onClick={() => window.location.reload()}>重新加载</button></p>}
    <div className="eco-layout">
      <section className="eco-chart-pane scrollbar-slim" aria-label="行情观察">
        <div className="eco-pane-heading"><h2>行情观察</h2><span className={`eco-badge${source ? ' is-ready' : ''}`}>{source ? '画面可用' : '等待画面'}</span></div>
        <div role="tablist" aria-label="图表来源" className="eco-tabs" onKeyDown={e => {
          if (busy || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
          e.preventDefault()
          const next = e.key === 'Home' ? 'browser' : e.key === 'End' ? 'window' : mode === 'browser' ? 'window' : 'browser'
          if (next !== mode) { setSource(null); setMode(next) }
          e.currentTarget.querySelector<HTMLButtonElement>(`#eco-tab-${next}`)?.focus()
        }}>
          {([['browser', '内置浏览器'], ['window', '已打开窗口']] as const).map(([value, label]) => <button role="tab" tabIndex={mode === value ? 0 : -1} aria-selected={mode === value} aria-controls="eco-source-panel" id={`eco-tab-${value}`} key={value} disabled={!!busy} onClick={() => { if (value !== mode) { setSource(null); setMode(value) } }}>{label}</button>)}
        </div>
        <div role="tabpanel" id="eco-source-panel" aria-labelledby={`eco-tab-${mode}`}>
          {mode === 'browser' ? <ResearchBrowser onSource={setSource}/> : <WindowObserver onSource={setSource}/>}
        </div>
        <p className="eco-source-caption"><AppIcon name="image"/>每次点击分析，保存当前画面。切换周期后，已保存的证据保持不变。</p>
      </section>
      <aside className="eco-analysis scrollbar-slim" aria-label="Agent 研究">
        <div className="eco-analysis-heading"><div className="eco-pane-heading"><h2>Agent 分析</h2><span className="eco-badge">已完成 {completed} / 3</span></div>
          <ol className="eco-progress" aria-label="观测进度">{['周线', '日线', '分时', '总结'].map((label, i) => <li key={label}><button aria-current={i === currentStep ? 'step' : undefined} onClick={() => jumpTo(i)}><span>{i < 3 && steps[i]?.status === 'COMPLETE' ? <AppIcon name="check" size={14}/> : i + 1}</span>{label}</button></li>)}</ol>
        </div>
        <details className="eco-settings" open={settingsOpen} onToggle={e => setSettingsOpen(e.currentTarget.open)}>
          <summary><span>观测设置</span><small>{symbol ? `${kind === 'market' ? '大盘' : '个股'} · ${symbol} · ${date}` : '选择标的、日期与 Agent'}{agentId && ` · ${agents.find(a => a.id === agentId)?.name ?? ''}`}</small></summary>
          <div className="eco-analysis-form">
            <div className="eco-identity-grid">
              <div className="eco-field"><span>标的类型</span><Select className="eco-select" menuClassName="eco-select-menu" label="标的类型" value={kind} disabled={!!busy || !!detail} onChange={value => setKind(value as ResearchKind)} options={[{value:'market',label:'大盘'},{value:'stock',label:'个股'}]}/></div>
              <label>观测日期<input type="date" aria-label="观测日期" max={localTradeDate()} value={date} disabled={!!busy || !!detail} onChange={e => setDate(e.target.value)}/></label>
            </div>
            <label>标的名称 / 代码<input required maxLength={160} value={symbol} disabled={!!busy || !!detail} onChange={e => setSymbol(e.target.value)} placeholder="例如：上证指数 SSE:000001"/></label>
            <div className="eco-field"><span>分析 Agent</span><Select className="eco-select" menuClassName="eco-select-menu" label="分析 Agent" value={agentId} onChange={setAgentId} disabled={!!busy} searchable options={[{value:'',label:'请选择 Agent'},...agents.map(a => ({value:a.id,label:a.name}))]}/></div>
            <details className="eco-optional-notes"><summary>补充资料与关注点（选填）{notes && ' · 已填写'}</summary><textarea className="scrollbar-slim" aria-label="补充资料与关注点" value={notes} maxLength={8000} onChange={e => setNotes(e.target.value)} placeholder="大盘背景、板块、事件与原始来源。" disabled={!!busy}/></details>
            {detail && <p className="eco-help">本轮标的与日期已锁定。更换标的请开始新一轮观测。</p>}
          </div>
        </details>
        <div className="eco-source-status" role="status">{missing || <>当前来源：{source?.title}</>}</div>
        {error && <p role="alert" className="eco-error">{error}</p>}
        <ol className="eco-workflow" aria-label="三周期分析流程">
          {TIMEFRAMES.map((timeframe, index) => {
            const observation = steps[index]
            const ready = steps.slice(0, index).every(o => o?.status === 'COMPLETE')
            const analyzing = busy === `analyze:${timeframe}`
            return <li id={`eco-step-${timeframe}`} tabIndex={-1} key={timeframe} className={`eco-workflow-step${index === currentStep ? ' is-current' : ''}`}>
              <div className="eco-step-heading"><h3><span className="eco-step-number">{index + 1}</span>{TIMEFRAME_LABEL[timeframe]}分析</h3><span className="eco-badge">{observation ? observation.status === 'COMPLETE' ? (observation.analysis?.sufficientEvidence ? '已完成' : '证据不足') : observation.status === 'FAILED' ? '分析失败' : '分析中' : ready ? '待分析' : '待上一步完成'}</span></div>
              <p className="eco-help">{STEP_HINTS[index]}</p>
              <button className={index === currentStep ? 'eco-primary' : ''} aria-busy={analyzing} disabled={!!busy || !ready || !!missing} onClick={() => analyze(timeframe)}><AppIcon name={analyzing ? 'loading' : observation ? 'refresh' : 'image'} className={analyzing ? 'animate-spin motion-reduce:animate-none' : undefined}/>{observation ? `重新${TIMEFRAME_LABEL[timeframe]}分析` : `${TIMEFRAME_LABEL[timeframe]}分析`}</button>
              {!ready && <p className="eco-meta">先完成上一步分析。</p>}
              {observation && <StepResult key={observation.id} observation={observation} busy={!!busy} retrying={busy === `retry:${observation.id}`} onRetry={() => void run(`retry:${observation.id}`, async () => recordStep(await retryStep(observation.id)))}/>}
            </li>
          })}
        </ol>
        <section id="eco-summary" tabIndex={-1} className="eco-summary-section" aria-label="提交总结">
          <div className="eco-step-heading"><h3><span className="eco-step-number">4</span>三图总结</h3><span className="eco-badge">{complete ? '可以提交' : `还需 ${3 - completed} 步`}</span></div>
          <label className="eco-checkbox"><input type="checkbox" checked={fundamentals} disabled={!!busy} onChange={e => setFundamentals(e.target.checked)}/>总结时检索基本面与隔夜事件</label>
          <button className="eco-primary eco-submit" aria-busy={busy === 'submit'} disabled={!!busy || !complete || !agentId} onClick={submit}><AppIcon name={busy === 'submit' ? 'loading' : 'analyze'} className={busy === 'submit' ? 'animate-spin motion-reduce:animate-none' : undefined}/>提交三图总结</button>
          <p className="eco-help">使用已保存的三张截图与分析，不重新截取当前画面。请使用支持图片的 Agent 模型。</p>
        </section>
        {hasChanges && <p className="eco-notice">有步骤已更新；下方总结仍引用旧版本，可重新提交生成新总结。</p>}
        {detail && detail.reports.length > 0 && <div className="eco-report-picker"><span>总结版本</span><Select className="eco-select" menuClassName="eco-select-menu" label="总结版本" value={selectedReport} onChange={setSelectedReport} searchable options={detail.reports.map((r,i) => ({value:r.id,label:`版本 ${detail.reports.length-i} · ${new Date(r.analyzedAt).toLocaleString()} · ${r.agentName}`}))}/></div>}
        {report && <ReportView key={report.id} report={report} observations={detail!.observations}/>}
        <details className="eco-strategy"><summary>本策略如何评估</summary><p>周线环境 25 分 + 日线结构 30 分 + 尾盘分时 30 分 + 隔夜执行风险 15 分。退出固定为下一交易日开盘后立即卖出。</p><ol>{STAGES.map((label, i) => <li key={label}>{i * 20}–{i === 4 ? 100 : i * 20 + 19}：{label}</li>)}</ol><p>基于当前信息立即评分，并明确是否进场、是否尾盘买入。未来走势未知计入风险；截图证据存在缺口时仅对可核实部分评分，并决定不买入。评分不是获利概率。</p></details>
      </aside>
    </div>
  </section>
}
