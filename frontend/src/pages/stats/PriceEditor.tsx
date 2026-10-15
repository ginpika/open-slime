import { useState, type FormEvent } from 'react'
import { deleteStatsPrice, loginUrlOf, saveStatsPrice, type StatsModel, type StatsPrice, type StatsView } from '../../api/client'
import { Button } from '../../components/ui'
import { amount, statsError } from './format'

export function PriceEditor({ data, target, onSaved }: {
  data: StatsView; target: StatsModel | null; onSaved: () => void
}) {
  const [provider, setProvider] = useState(target?.provider ?? '')
  const [model, setModel] = useState(target?.model ?? '')
  const [effectiveFrom, setEffectiveFrom] = useState(data.today)
  const [currency, setCurrency] = useState('CNY')
  const [input, setInput] = useState('')
  const [output, setOutput] = useState('')
  const [cached, setCached] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [deleting, setDeleting] = useState<number | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null)
  function load(price: StatsPrice) {
    setProvider(price.provider); setModel(price.model); setEffectiveFrom(price.effectiveFrom)
    setCurrency(price.currency); setInput(String(price.inputPerMillion)); setOutput(String(price.outputPerMillion))
    setCached(price.cachedPerMillion === null ? '' : String(price.cachedPerMillion)); setSaved(false); setError(null)
  }
  async function remove(id: number) {
    if (confirmDelete !== id) { setConfirmDelete(id); return }
    setDeleting(id); setError(null); setSaved(false)
    try { await deleteStatsPrice(id); setConfirmDelete(null); onSaved() }
    catch (e) { const login = loginUrlOf(e); if (login) window.location.assign(login); else setError(statsError(e)) }
    finally { setDeleting(null) }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(null); setSaved(false)
    if (!input.trim() || !output.trim()) { setError('请填写输入和输出单价；明确免费时填写 0'); return }
    setSaving(true)
    try {
      await saveStatsPrice({ provider, model, effectiveFrom, currency, inputPerMillion: input,
        outputPerMillion: output, cachedPerMillion: cached.trim() || null })
      setSaved(true); onSaved()
    } catch (e) {
      const login = loginUrlOf(e)
      if (login) { window.location.assign(login); return }
      setError(statsError(e))
    } finally { setSaving(false) }
  }
  return <div className="usage-price">
    <p>填写供应商实际采用的每百万 Token 单价。仅支持按 Token 计费；按请求、订阅或其他规则计费的模型请保持未计价。</p>
    <p className="usage-secondary">价格按 {data.timeZone} 的生效日零点匹配，直到下一版本生效。更正或删除价格版本会重新估算历史；结果不代表实付账单。</p>
    <form onSubmit={save} onChange={() => setSaved(false)} aria-label="模型价格配置">
      <fieldset className="usage-price__fields" disabled={saving || deleting !== null}>
        <label className="usage-field usage-field--wide"><span>供应商端点</span><input value={provider} onChange={e => setProvider(e.target.value)} placeholder="https://api.example.com/v1" maxLength={2000} /><small>留空仅匹配供应商未知的历史记录。不要填写密钥。</small></label>
        <label className="usage-field"><span>模型名</span><input required value={model} onChange={e => setModel(e.target.value)} maxLength={300} /></label>
        <label className="usage-field"><span>生效日期</span><input required type="date" min="1970-01-01" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} /></label>
        <label className="usage-field"><span>币种</span><select value={currency} onChange={e => setCurrency(e.target.value)}>{[...new Set(['CNY', 'USD', 'EUR', 'JPY', 'GBP', 'HKD', currency])].map(c => <option key={c}>{c}</option>)}</select></label>
        <label className="usage-field"><span>输入 / 百万 Token</span><input required type="number" min="0" max="1000000000" step="0.00000001" value={input} onChange={e => setInput(e.target.value)} placeholder="未配置" /></label>
        <label className="usage-field"><span>输出 / 百万 Token</span><input required type="number" min="0" max="1000000000" step="0.00000001" value={output} onChange={e => setOutput(e.target.value)} placeholder="未配置" /></label>
        <label className="usage-field"><span>缓存命中 / 百万 Token</span><input type="number" min="0" max="1000000000" step="0.00000001" value={cached} onChange={e => setCached(e.target.value)} placeholder="未知，可留空" /><small>有缓存用量而缓存单价未知时，该调用不计价。</small></label>
      </fieldset>
      <div className="usage-actions"><Button type="submit" disabled={saving || deleting !== null}>{saving ? '正在保存…' : '保存价格版本'}</Button>{saved && <span role="status">价格已保存，费用估算会随统计刷新。</span>}</div>
      {error && <p role="alert" className="usage-error">{error}</p>}
    </form>
    {data.prices.length > 0 && <details><summary>已保存的价格版本（{data.prices.length}）</summary><div className="usage-table-wrap scrollbar-slim" tabIndex={0} role="region" aria-label="价格版本，可横向滚动"><table className="usage-table">
      <thead><tr><th>模型 / 端点</th><th>生效日期</th><th>币种</th><th>输入</th><th>输出</th><th>缓存命中</th><th>操作</th></tr></thead>
      <tbody>{data.prices.map(p => <tr key={p.id}><th scope="row">{p.model}<small>{p.provider || '供应商未知（历史）'}</small></th><td>{p.effectiveFrom}</td><td>{p.currency}</td><td>{amount(p.inputPerMillion)}</td><td>{amount(p.outputPerMillion)}</td><td>{p.cachedPerMillion === null ? '未配置' : amount(p.cachedPerMillion)}</td><td><div className="usage-actions"><Button disabled={saving || deleting !== null} onClick={() => load(p)}>载入编辑</Button><Button disabled={saving || deleting !== null} onClick={() => remove(p.id)}>{deleting === p.id ? '正在删除…' : confirmDelete === p.id ? '确认删除此版本' : '删除'}</Button>{confirmDelete === p.id && <Button disabled={deleting !== null} onClick={() => setConfirmDelete(null)}>取消</Button>}</div></td></tr>)}</tbody>
    </table></div></details>}
  </div>
}
