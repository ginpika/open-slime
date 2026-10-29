import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { getStats, loginUrlOf, type StatsBucket, type StatsModel, type StatsQuery, type StatsView } from '../api/client'
import StatsTrendChart from '../components/StatsTrendChart'
import { LoadState } from '../components/LoadState'
import { Button } from '../components/ui'
import { Select } from '../components/Select'
import { usePageKit } from '../kit'
import { PriceEditor } from './stats/PriceEditor'
import { amount, dateTime, downloadStats, fmtNum, money, qualityLabel, sourceLabel, statsError, statusLabel, validateRange } from './stats/format'
import './stats/stats.css'

const RANGES = [{ days: 7, label: '7 天' }, { days: 30, label: '30 天' }, { days: 90, label: '90 天' }, { days: -1, label: '本月' }]
const modelKey = (m: StatsModel) => JSON.stringify([m.provider, m.model])
const modelTitle = (m: StatsModel) => (m.model || '未记录模型') + ' · ' + (m.provider || '供应商未知（历史）')

function Breakdown({ buckets, total, kind, onPick, onPrice }: {
  buckets: StatsBucket[]; total: number; kind: 'source' | 'model'
  onPick: (bucket: StatsBucket) => void; onPrice?: (model: StatsModel) => void
}) {
  if (!buckets.length) return <p>当前范围暂无记录。</p>
  return <div className="usage-table-wrap scrollbar-slim" role="region" aria-label={kind === 'source' ? '来源用量明细，可横向滚动' : '模型用量明细，可横向滚动'} tabIndex={0}>
    <table className="usage-table"><thead><tr><th>{kind === 'source' ? '来源' : '模型 / 端点'}</th><th>输入</th><th>输出</th><th>总 Token / 占比</th><th>调用</th><th>估算费用</th></tr></thead>
      <tbody>{buckets.map(b => <tr key={b.key}>
        <th scope="row"><button type="button" className="usage-link" onClick={() => onPick(b)} aria-label={'筛选' + (kind === 'source' ? sourceLabel(b.key) : modelTitle(b))}>{kind === 'source' ? sourceLabel(b.key) : b.model || '未记录模型'}</button>{kind === 'model' && <small>{b.provider || '供应商未知（历史）'}</small>}</th>
        <td>{fmtNum(b.promptTokens)}</td><td>{fmtNum(b.completionTokens)}</td>
        <td>{fmtNum(b.totalTokens)}<small>{total > 0 ? (b.totalTokens / total * 100).toFixed(1) : '0'}%</small></td>
        <td>{fmtNum(b.calls)}</td><td>{money(b.costs)}{b.unpricedCalls > 0 && <small>{fmtNum(b.unpricedCalls)} 次未计价</small>}{kind === 'model' && onPrice && <button type="button" className="usage-link" onClick={() => onPrice(b)}>配置价格</button>}</td>
      </tr>)}</tbody>
    </table>
  </div>
}

export default function Stats() {
  const kit = usePageKit()
  const [range, setRange] = useState<StatsQuery>({ days: 30 })
  const [source, setSource] = useState('')
  const [selectedModel, setSelectedModel] = useState('')
  const [customOpen, setCustomOpen] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [rangeError, setRangeError] = useState<string | null>(null)
  const [data, setData] = useState<StatsView | null>(null)
  const [snapshot, setSnapshot] = useState({ source: '全部来源', model: '全部模型' })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [pricingOpen, setPricingOpen] = useState(false)
  const [priceTarget, setPriceTarget] = useState<StatsModel | null>(null)

  const reload = useCallback(() => { setLoading(true); setError(null); setRetry(n => n + 1) }, [])
  function changeSource(next: string) { setSource(next); reload() }
  function changeModel(next: string) { setSelectedModel(next); reload() }

  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    const query = { ...range, ...(source ? { source } : {}) }
    let currentModel: StatsModel | null = null
    if (selectedModel) {
      const [provider, model] = JSON.parse(selectedModel) as [string, string]
      Object.assign(query, { provider, model }); currentModel = { provider, model }
    }
    getStats(query, controller.signal).then(result => {
      if (!alive) return
      setData(result)
      setSnapshot({ source: source ? sourceLabel(source) : '全部来源', model: currentModel ? modelTitle(currentModel) : '全部模型' })
    }).catch((e: unknown) => {
      if (!alive) return
      const login = loginUrlOf(e)
      if (login) { window.location.assign(login); return }
      setError(statsError(e))
    }).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false; controller.abort() }
  }, [range, source, selectedModel, retry])

  useEffect(() => {
    const refresh = () => { if (document.visibilityState === 'visible') reload() }
    const timer = window.setInterval(refresh, 60000)
    window.addEventListener('focus', refresh)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [reload])

  function applyRange(event: FormEvent) {
    event.preventDefault()
    const problem = validateRange(from, to, data?.today)
    setRangeError(problem)
    if (!problem) { setRange({ from, to }); reload() }
  }
  function openPrice(target?: StatsModel) {
    if (target) setPriceTarget({ provider: target.provider, model: target.model })
    setPricingOpen(true)
    window.setTimeout(() => document.getElementById('usage-pricing')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 0)
  }
  const total = data?.total
  const unallocated = total ? Math.max(0, total.totalTokens - total.promptTokens - total.completionTokens) : 0

  return <div className="usage-page"><kit.Page title="用量 & 统计" en="USAGE & STATS" meta={
    <div className="usage-actions"><Button disabled={loading} onClick={reload}>{loading ? '正在更新…' : '刷新'}</Button><Button disabled={!data} onClick={() => data && downloadStats(data, snapshot.source, snapshot.model)}>导出汇总 CSV</Button></div>
  }>
    <p className="usage-intro">查看模型消耗、估算费用与主要来源，帮助判断成本花在哪里。</p>
    <div className="usage-filters">
      <div className="usage-ranges" role="group" aria-label="统计日期范围">
        {RANGES.map(r => <button key={r.days} type="button" aria-pressed={range.days === r.days} onClick={() => { setRange({ days: r.days }); setCustomOpen(false); setRangeError(null); reload() }}>{r.label}</button>)}
        <button type="button" aria-pressed={!!range.from} onClick={() => { setCustomOpen(v => !v); if (!from && data) { setFrom(data.from); setTo(data.to) } }}>自定义日期</button>
      </div>
      <div className="usage-field"><span>来源</span><Select label="来源" value={source} onChange={changeSource} size="compact" options={[{ value: '', label: '全部来源' }, ...[...new Set([...(data?.sources ?? []), ...(source ? [source] : [])])].map(s => ({ value: s, label: sourceLabel(s) }))]} /></div>
      <div className="usage-field usage-filter-model"><span>模型与端点</span><Select label="模型与端点" value={selectedModel} onChange={changeModel} size="compact" searchable options={[{ value: '', label: '全部模型' }, ...(data?.models ?? []).map(m => ({ value: modelKey(m), label: modelTitle(m) })), ...(selectedModel && !data?.models.some(m => modelKey(m) === selectedModel) ? [{ value: selectedModel, label: modelTitle({ provider: JSON.parse(selectedModel)[0], model: JSON.parse(selectedModel)[1] }) }] : [])]} /></div>
      {(source || selectedModel) && <Button onClick={() => { setSource(''); setSelectedModel(''); reload() }}>清除筛选</Button>}
    </div>
    {customOpen && <form className="usage-custom-range" onSubmit={applyRange}>
      <label className="usage-field"><span>开始日期</span><input type="date" required min="1970-01-01" max={data?.today} value={from} onChange={e => setFrom(e.target.value)} /></label>
      <label className="usage-field"><span>结束日期</span><input type="date" required min={from || '1970-01-01'} max={data?.today} value={to} onChange={e => setTo(e.target.value)} /></label>
      <Button type="submit">应用日期</Button><span className="usage-secondary">最多 366 天，以统计时区为准。</span>
      {rangeError && <p className="usage-error" role="alert">{rangeError}</p>}
    </form>}

    <LoadState loading={loading} error={error} hasData={!!data} label="用量 & 统计" onRetry={reload}
      stale={data ? '下方保留 ' + data.from + ' 至 ' + data.to + '（' + snapshot.source + ' / ' + snapshot.model + '）的上次结果，尚未更新。' : undefined} />

    {data && total && <>
      <div className="usage-snapshot" aria-live="polite"><span>{data.from} 至 {data.to} · {data.days} 天 · {data.timeZone}</span><span>{snapshot.source} / {snapshot.model}</span><span>读取于 {dateTime(data.generatedAt, data.timeZone)} · 可见时每分钟刷新</span></div>
      <div className="usage-metrics">
        <section className="usage-metric usage-metric--cost"><h2>估算费用{total.unpricedCalls > 0 ? '（已计价部分）' : ''}</h2><div className="usage-metric__value">{total.costs.length ? total.costs.map(c => <strong key={c.currency}><span>{c.currency}</span> {amount(c.amount)}</strong>) : <strong>未计价</strong>}</div><p>计价覆盖 {fmtNum(total.calls - total.unpricedCalls)} / {fmtNum(total.calls)} 次调用</p><button type="button" className="usage-link" onClick={() => openPrice()}>配置模型价格</button></section>
        <section className="usage-metric"><h2>总 Token</h2><strong className="usage-metric__value">{fmtNum(total.totalTokens)}</strong><p>输入 {fmtNum(total.promptTokens)} · 输出 {fmtNum(total.completionTokens)}</p><p>已记录缓存命中 {fmtNum(total.cachedTokens)} · 已记录推理输出 {fmtNum(total.reasoningTokens)}</p>{unallocated > 0 && <p>未拆分 {fmtNum(unallocated)} Token</p>}</section>
        <section className="usage-metric"><h2>调用次数</h2><strong className="usage-metric__value">{fmtNum(total.calls)}</strong><p>成功 {fmtNum(total.calls - total.unsuccessfulCalls)} · 中断或失败 {fmtNum(total.unsuccessfulCalls)}</p><p>含聊天、向量化、精排及每次重试请求</p></section>
        <section className="usage-metric"><h2>用量数据质量</h2><strong className="usage-metric__value">{total.calls ? (total.reportedCalls / total.calls * 100).toFixed(1) + '%' : '—'}</strong><p>完整实测 {fmtNum(total.reportedCalls)} 次</p><p>文本估算 {fmtNum(total.estimatedCalls)} · 不完整或未知 {fmtNum(total.partialCalls)}</p></section>
      </div>
      <div className="usage-notice"><p>费用按调用时生效的手工价格估算，不是实付账单。各币种独立展示，不含税费、订阅及非 Token 计费。缓存和推理是输入、输出的子集，不重复累加；未回传或历史缺失的细分用量无法还原，估算可能与实际计费不同。</p>
        {(total.unpricedCalls > 0 || total.partialCalls > 0) && <p><strong>{fmtNum(total.unpricedCalls)} 次调用未计价。</strong> 可能缺少单价、缓存单价或完整用量，也可能为中断 / 失败请求；未知消耗不按零费用处理。</p>}
        {total.estimatedCalls > 0 && <p>{fmtNum(total.estimatedCalls)} 次调用使用文本估算，实际 Token 数以供应商记录为准。</p>}
        {data.recordingFailures > 0 && <p className="usage-error">本次服务启动后已有 {fmtNum(data.recordingFailures)} 次用量记录写入失败，统计可能缺失，请检查服务日志。</p>}
      </div>
      {total.calls === 0 && <p className="usage-notice" role="status">当前日期与筛选条件下没有用量记录。可以调整范围，或发起一次模型调用后刷新。</p>}
      <kit.Panel title="每日用量" meta="单位：Token">
        <div className="usage-chart"><StatsTrendChart labels={data.daily.map(b => b.key)} prompt={data.daily.map(b => b.promptTokens)} completion={data.daily.map(b => b.completionTokens)} unknown={data.daily.map(b => Math.max(0, b.totalTokens - b.promptTokens - b.completionTokens))} /></div>
        <details><summary>查看每日数据表</summary><div className="usage-table-wrap scrollbar-slim" role="region" aria-label="每日数据，可横向滚动" tabIndex={0}><table className="usage-table"><thead><tr><th>日期</th><th>输入</th><th>输出</th><th>总 Token</th><th>调用</th><th>未计价</th><th>估算费用</th></tr></thead><tbody>{data.daily.map(b => <tr key={b.key}><th scope="row">{b.key}</th><td>{fmtNum(b.promptTokens)}</td><td>{fmtNum(b.completionTokens)}</td><td>{fmtNum(b.totalTokens)}</td><td>{fmtNum(b.calls)}</td><td>{fmtNum(b.unpricedCalls)}</td><td>{b.calls === 0 ? '无调用' : money(b.costs)}</td></tr>)}</tbody></table></div></details>
      </kit.Panel>
      <kit.Panel title="按来源" meta="点击来源筛选全页"><Breakdown buckets={data.bySource} total={total.totalTokens} kind="source" onPick={b => changeSource(b.key)} /></kit.Panel>
      <kit.Panel title="按模型与供应商" meta="占比按总 Token 计算"><Breakdown buckets={data.byModel} total={total.totalTokens} kind="model" onPick={b => changeModel(modelKey(b))} onPrice={openPrice} /></kit.Panel>
      <kit.Panel title="调用明细" meta={'最近 ' + data.recentCalls.length + ' 条'}>
        <p className="usage-secondary">按当前日期和筛选条件显示最近 100 条；上方汇总与 CSV 包含范围内全部记录。历史未记录的供应商无法自动追溯。表格内可上下、左右滚动。</p>
        {!data.recentCalls.length ? <p>暂无调用记录。</p> : <div className="usage-table-wrap usage-call-records scrollbar-slim" role="region" aria-label="调用记录，可上下及横向滚动" tabIndex={0}><table className="usage-table"><thead><tr><th>时间（{data.timeZone}）</th><th>模型 / 端点</th><th>来源</th><th>输入 / 输出</th><th>总 Token</th><th>用量质量</th><th>状态</th><th>估算费用</th></tr></thead><tbody>{data.recentCalls.map(c => <tr key={c.id}>
          <td>{dateTime(c.createdAt, data.timeZone)}</td><th scope="row">{c.model || '未记录模型'}<small>{c.provider || '供应商未知（历史）'}</small></th><td>{sourceLabel(c.source)}</td><td>{fmtNum(c.promptTokens)} / {fmtNum(c.completionTokens)}{c.cachedTokens > 0 && <small>缓存命中 {fmtNum(c.cachedTokens)}</small>}{c.reasoningTokens > 0 && <small>推理输出 {fmtNum(c.reasoningTokens)}</small>}</td><td>{fmtNum(c.totalTokens)}</td><td>{qualityLabel(c.quality)}</td><td>{statusLabel(c.status)}</td><td>{c.cost === null ? '未计价' : c.currency + ' ' + amount(c.cost)}</td>
        </tr>)}</tbody></table></div>}
      </kit.Panel>
      <details id="usage-pricing" className="usage-pricing-panel" open={pricingOpen} onToggle={e => setPricingOpen(e.currentTarget.open)}><summary>模型价格配置</summary>
        <PriceEditor key={priceTarget ? modelKey(priceTarget) : 'manual'} data={data} target={priceTarget} onSaved={reload} />
      </details>
    </>}
  </kit.Page></div>
}
