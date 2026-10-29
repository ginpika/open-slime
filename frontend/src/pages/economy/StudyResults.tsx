import { useState } from 'react'
import type { EconomyReport, Observation } from '../../api/economy'
import ChatMarkdown from '../../components/ChatMarkdown'
import { AppIcon } from '../../components/AppIcon'
import ObservationImage from './ObservationImage'
import Overnight from './Overnight'
import { ENTRY_LABEL, OVERNIGHT_LABEL, STAGES, TIMEFRAMES, TIMEFRAME_LABEL } from './strategy'

export function StepResult({ observation: o, busy = false, retrying = false, onRetry }: { observation: Observation; busy?: boolean; retrying?: boolean; onRetry?: () => void }) {
  const [show, setShow] = useState(false)
  return <div className="eco-step-result">
    <p className="eco-meta">{new Date(o.capturedAt).toLocaleString()} · {o.agentName} · {o.model}</p>
    {o.analysis && <ChatMarkdown content={o.analysis.summary}/>}
    {o.error && <p role="alert" className="eco-error">{o.error}</p>}
    <div className="eco-inline-actions">
      {o.status === 'FAILED' && onRetry && <button aria-busy={retrying} disabled={busy} onClick={onRetry}><AppIcon name={retrying ? 'loading' : 'refresh'} className={retrying ? 'animate-spin motion-reduce:animate-none' : undefined}/>重试已保存{TIMEFRAME_LABEL[o.timeframe]}截图</button>}
      <button onClick={() => setShow(!show)} aria-expanded={show}><AppIcon name="image"/>{show ? '收起' : '查看'}{TIMEFRAME_LABEL[o.timeframe]}证据</button>
    </div>
    {show && <div className="eco-step-evidence"><ObservationImage id={o.id} label={`已保存${TIMEFRAME_LABEL[o.timeframe]}截图`}/>
      <p className="eco-meta">{o.sourceTitle}{o.sourceUrl && <> · <a href={o.sourceUrl} target="_blank" rel="noreferrer">原始来源</a></>}</p>
      {o.analysis && <><h4>技术面依据</h4><ChatMarkdown content={o.analysis.technical}/><h4>风险与待核对项</h4><ChatMarkdown content={o.analysis.risks}/></>}
      {o.notes && <><h4>当时的补充资料</h4><p>{o.notes}</p></>}
    </div>}
  </div>
}

export function ReportView({ report: r, observations }: { report: EconomyReport; observations: Observation[] }) {
  return <article className="eco-report" aria-label="三图总结报告">
    <header className="eco-report-heading">
      <div><p className="eco-meta">{r.symbol} · {r.tradeDate}</p><h2>总结报告</h2><p className="eco-meta">{r.agentName} · {r.model} · {new Date(r.analyzedAt).toLocaleString()}</p></div>
      <div className="eco-score"><strong>{r.score === null ? '—' : r.score}<small>{r.score === null ? '证据不足' : '/ 100'}</small></strong><span>{r.stage ? STAGES[r.stage - 1] : '暂不评分'}</span></div>
    </header>
    <dl className="eco-decisions"><div><dt>{r.kind === 'market' ? '市场环境是否支持进场' : '是否具备进场条件'}</dt><dd>{ENTRY_LABEL[r.entryDecision]}</dd></div><div><dt>是否尾盘买入隔夜</dt><dd>{OVERNIGHT_LABEL[r.overnightDecision]}</dd></div></dl>
    {!r.sufficientEvidence && <p className="eco-error">{r.score === null ? '历史报告：三图证据不足，未形成评分和买入结论。' : '截图证据存在缺口，评分仅基于可核实部分；本次决策为不进场、不买入。'}</p>}
    <ChatMarkdown content={r.summary}/>
    {r.score !== null && <ol className="eco-stages" aria-label="机会评分五档">{STAGES.map((label, i) => <li key={label} className={r.stage === i + 1 ? 'is-active' : ''} aria-current={r.stage === i + 1 ? 'step' : undefined}><span>{i * 20}–{i === 4 ? 100 : i * 20 + 19}</span><small>{label}</small></li>)}</ol>}
    <p className="eco-help">机会评分用于比较本次条件，不代表获利概率。</p>
    <div className="eco-factors">{r.factors.map(f => <div className="eco-factor" key={f.name}><div><strong>{f.name}</strong><span>{f.points} / {f.maximum}</span></div><meter min={0} max={f.maximum} value={f.points} aria-label={`${f.name}评分`}/><p>{f.reason}</p></div>)}</div>
    {([['三周期判断依据', r.technical], ['当前进场与尾盘条件', r.conditions], ['次日退出计划', r.exitPlan], ['基本面与事件', r.fundamentals], ['风险与待核对项', r.risks]] as const).map(([title, body]) => <section className="eco-section" key={title}><h3>{title}</h3><ChatMarkdown content={body}/></section>)}
    <details className="eco-strategy"><summary>本总结引用的三张截图</summary>{r.observationIds.map((id, i) => {
      const observation = observations.find(o => o.id === id)
      return <div key={id}><h3>{TIMEFRAME_LABEL[TIMEFRAMES[i]]}</h3><ObservationImage id={id} label={`总结引用${TIMEFRAME_LABEL[TIMEFRAMES[i]]}截图`}/>{observation && <p className="eco-meta">{new Date(observation.capturedAt).toLocaleString()} · {observation.agentName}</p>}</div>
    })}</details>
    <p className="eco-meta">{r.retrievalStatus}</p>
    <details className="eco-strategy"><summary>尾盘买入 / 次日早盘卖出收益试算</summary><Overnight/></details>
  </article>
}
