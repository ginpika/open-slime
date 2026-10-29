import { useEffect, useState } from 'react'
import { ApiError, loginUrlOf, requestJson } from '../api/client'
import { usePageKit } from '../kit'
import { btnPrimary, btnQuiet } from './ui'
import { useDraftState } from './windowState'
import { DraftNotice } from './DraftNotice'
import './deepseekWeb.css'
import DeepSeekBalance from './DeepSeekBalance'
import { isOfficialDeepSeek } from '../api/deepseekBalance'

type View = {
  baseUrl: string; hasApiKey: boolean; fastEnabled: boolean; ragEnabled: boolean
  fetchEnabled: boolean; count: number; timeoutSeconds: number
}
type Draft = Omit<View, 'hasApiKey'> & { apiKey: string; clearApiKey: boolean }
const initial: Draft = {
  baseUrl: 'https://api.deepseek.com', apiKey: '', clearApiKey: false,
  fastEnabled: false, ragEnabled: false, fetchEnabled: true, count: 5, timeoutSeconds: 15,
}
const root = '/api/settings/deepseek-web'
function fromView(v: View): Draft {
  return { baseUrl: v.baseUrl, apiKey: '', clearApiKey: false, fastEnabled: v.fastEnabled,
    ragEnabled: v.ragEnabled, fetchEnabled: v.fetchEnabled, count: v.count, timeoutSeconds: v.timeoutSeconds }
}
function payload(d: Draft) {
  const { clearApiKey, ...config } = d
  return { ...config, apiKey: clearApiKey ? '' : d.apiKey.trim() || null }
}
function message(e: unknown): string {
  if (e instanceof ApiError) {
    try { return JSON.parse(e.body).message || `请求失败（${e.status}）` } catch { return `请求失败（${e.status}）` }
  }
  return e instanceof Error ? e.message : String(e)
}

export default function DeepSeekWebPanel() {
  const kit = usePageKit()
  const [draft, setDraft, controls] = useDraftState<Draft>('settings:deepseek-web', initial)
  const initialize = controls.initialize
  const [view, setView] = useState<View | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [query, setQuery] = useState('DeepSeek API')
  const [url, setUrl] = useState('https://api-docs.deepseek.com/zh-cn/guides/search/')
  useEffect(() => {
    let alive = true
    requestJson<View>(root).then(v => {
      if (!alive) return
      setView(v); initialize(fromView(v))
    }).catch(e => {
      if (!alive) return
      const login = loginUrlOf(e)
      if (login) window.location.assign(login)
      else setLoadError(message(e))
    })
    return () => { alive = false }
  }, [initialize, retry])
  function fail(e: unknown) {
    const login = loginUrlOf(e)
    if (login) window.location.assign(login)
    else setNotice({ error: true, text: message(e) })
  }
  async function save() {
    setBusy(true); setNotice(null)
    const submitted = draft
    try {
      const v = await requestJson<View>(root, { method: 'PUT', body: JSON.stringify(payload(submitted)) })
      setView(v); controls.accept(fromView(v), submitted)
      setNotice({ error: false, text: 'DeepSeek Search / Fetch 配置已保存' })
    } catch (e) { fail(e) }
    finally { setBusy(false) }
  }
  async function test(tool: 'search' | 'fetch') {
    setBusy(true); setNotice(null)
    try {
      const result = await requestJson<{ ok: boolean; elapsedMs: number; detail: string | null; error: string | null }>(root + '/test/' + tool, {
        method: 'POST', body: JSON.stringify({ config: payload(draft), query, url }),
      }, (draft.timeoutSeconds + 3) * 1000)
      setNotice({ error: !result.ok, text: `${result.ok ? result.detail : result.error}（${result.elapsedMs} ms）` })
    } catch (e) { fail(e) }
    finally { setBusy(false) }
  }
  function toggle(key: 'fastEnabled' | 'ragEnabled' | 'fetchEnabled', label: string, hint: string) {
    return <label className="flex items-start gap-3 py-2 text-base">
      <input className="mt-1 h-4 w-4 shrink-0 accent-accent" type="checkbox" checked={draft[key]}
        disabled={busy || !view} onChange={e => setDraft({ ...draft, [key]: e.target.checked })} />
      <span><span className="block">{label}</span><span className="mt-1 block text-sm leading-6 text-muted">{hint}</span></span>
    </label>
  }
  return <kit.Panel title="Deepseek Search">
    <div className="deepseek-web-panel space-y-4">
    <p className="text-sm leading-6 text-muted">为首页搜索和 RAG 提供实时网页资料，独立于聊天模型。查询会发送给所配置的服务；网页资料不会自动写入知识库。
      {' '}<a className="underline hover:text-accent" href="https://api-docs.deepseek.com/zh-cn/guides/search/" target="_blank" rel="noreferrer">接口说明</a></p>
    {loadError && <div role="alert" className="text-sm text-danger">加载失败：{loadError} <button className={btnQuiet()} onClick={() => { setLoadError(null); setRetry(v => v + 1) }}>重试加载</button></div>}
    {!view && !loadError && <p role="status" className="text-sm text-muted">正在加载联网检索配置…</p>}
    {view && isOfficialDeepSeek(view.baseUrl) && view.hasApiKey && <DeepSeekBalance path={`${root}/balance`} revision={view} />}
    <form className="space-y-4" onSubmit={e => { e.preventDefault(); void save() }}>
      <fieldset disabled={busy || !view} className="space-y-4">
        <kit.Field label="API 根地址" hint="默认官方地址，也支持以 /v1 结尾的地址或兼容网关。">
          <input type="url" required value={draft.baseUrl} onChange={e => setDraft({ ...draft, baseUrl: e.target.value })} />
        </kit.Field>
        <kit.Field label="DeepSeek API Key" hint={view?.hasApiKey ? '已配置密钥；留空保留，填写新值可替换。密钥不会从后端回传。' : '填写 DeepSeek API Key，不需要填写模型名。'}>
          <input type="password" autoComplete="new-password" value={draft.apiKey}
            placeholder={view?.hasApiKey ? '已配置，留空保留' : '请输入 API Key'}
            onChange={e => setDraft({ ...draft, apiKey: e.target.value, clearApiKey: false })} />
        </kit.Field>
        {view?.hasApiKey && <button type="button" className={btnQuiet()} onClick={() => setDraft({ ...draft, apiKey: '', clearApiKey: true, fastEnabled: false, ragEnabled: false })}>
          {draft.clearApiKey ? '保存后将清除密钥并关闭联网检索' : '清除已保存密钥'}
        </button>}
        <div className="border-y border-line py-2">
          {toggle('fastEnabled', '在首页 FAST 搜索中使用', '保留本地文档和实体结果，另外展示网页标题、链接和短摘要。')}
          {toggle('ragEnabled', '在 RAG 中启用', '对话前自动搜索网页补充上下文，并允许智能体按授权调用 search_web / fetch_web；独立于本地知识库自动召回。')}
          {toggle('fetchEnabled', '允许 Fetch 正文抓取', 'RAG 首条结果缺少正文时补抓取一次，也允许模型按需读取网页。关闭后仍可使用 Search 返回的摘要和正文。')}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <kit.Field label="搜索结果条数" hint="1–50；RAG 自动召回最多使用 6 条。"><input type="number" min={1} max={50} required value={draft.count} onChange={e => setDraft({ ...draft, count: Number(e.target.value) })} /></kit.Field>
          <kit.Field label="单接口超时（秒）" hint="2–60 秒，重试包含在该接口的超时预算内。"><input type="number" min={2} max={60} required value={draft.timeoutSeconds} onChange={e => setDraft({ ...draft, timeoutSeconds: Number(e.target.value) })} /></kit.Field>
        </div>
        <p className="text-sm leading-6 text-muted">Search / Fetch 按请求次数计费。短时间重复搜索会复用结果；接口失败时保留本地检索。测试使用当前表单草稿，会发送真实请求。</p>
        <div className="grid gap-4 sm:grid-cols-2">
          <kit.Field label="Search 测试词"><input value={query} onChange={e => setQuery(e.target.value)} maxLength={128} /></kit.Field>
          <kit.Field label="Fetch 测试网址"><input type="url" value={url} onChange={e => setUrl(e.target.value)} /></kit.Field>
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" className={btnQuiet()} disabled={!query.trim()} onClick={() => void test('search')}>测试 Search</button>
          <button type="button" className={btnQuiet()} disabled={!url.trim()} onClick={() => void test('fetch')}>测试 Fetch</button>
          <button type="submit" className={btnPrimary()}>{busy ? '处理中…' : '保存联网检索配置'}</button>
        </div>
      </fieldset>
      <DraftNotice dirty={controls.dirty} onDiscard={controls.discard} disabled={busy} />
    </form>
    {notice && <p role={notice.error ? 'alert' : 'status'} className={`text-sm leading-6 ${notice.error ? 'text-danger' : 'text-muted'}`}>{notice.text}</p>}
  </div>
  </kit.Panel>
}
