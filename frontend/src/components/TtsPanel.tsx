import { useEffect, useState } from 'react'
import { ApiError, requestJson } from '../api/client'
import { usePageKit } from '../kit'
import { Select } from './formKit'
import FloatWindow from './FloatWindow'
import Toast, { type ToastKind } from './Toast'
import { btnPrimary, miniBtn } from './ui'

export type TtsEndpoint = { id: string; name: string; baseUrl: string; apiKey: string }
export type TtsProfile = { id: string; name: string; endpointId: string; model: string; referenceId: string; parameters: Record<string, unknown> }
type View = { endpoints: TtsEndpoint[]; profiles: TtsProfile[]; defaultProfileId: string | null }
type TestResult = { ok: boolean; elapsedMs: number; detail: string | null; error: string | null }
const parameters = { temperature: 0.7, top_p: 0.7, format: 'mp3', chunk_length: 300, normalize: true, latency: 'normal', prosody: { speed: 1, volume: 0, normalize_loudness: true } }

function errorMessage(e: unknown): string {
  if (e instanceof SyntaxError) return '参数必须是有效 JSON 对象'
  if (e instanceof ApiError) {
    try { const body = JSON.parse(e.body); if (body.detail || body.message) return body.detail || body.message } catch { /* 非 JSON 响应使用通用提示 */ }
    if (e.status === 400) return '配置无效；删除端点前请先移除引用它的档案'
  }
  return e instanceof Error ? e.message : '操作失败，请检查配置和网络'
}

export default function TtsPanel({ agentId }: { agentId?: string }) {
  const kit = usePageKit()
  const [view, setView] = useState<View>({ endpoints: [], profiles: [], defaultProfileId: null })
  const [selected, setSelected] = useState('')
  const [draft, setDraft] = useState<TtsProfile | null>(null)
  const [endpointDraft, setEndpointDraft] = useState<TtsEndpoint | null>(null)
  const [json, setJson] = useState('')
  const [busy, setBusy] = useState(false)
  const [testing, setTesting] = useState<string | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [toast, setToast] = useState<{ kind: ToastKind; message: string } | null>(null)
  useEffect(() => {
    let alive = true
    void Promise.all([requestJson<View>('/api/settings/tts'), agentId ? requestJson<{ profileId: string }>('/api/agents/' + encodeURIComponent(agentId) + '/tts') : Promise.resolve(null)])
      .then(([v, s]) => { if (alive) { setView(v); setSelected(agentId ? s?.profileId ?? '' : v.defaultProfileId ?? ''); setLoaded(true) } })
      .catch(() => { if (alive) setToast({ kind: 'error', message: 'TTS 配置加载失败' }) })
    return () => { alive = false }
  }, [agentId])
  async function run(action: () => Promise<void>, success = '已保存') {
    setBusy(true); setToast(null)
    try { await action(); setToast({ kind: 'success', message: success }) }
    catch (e) { setToast({ kind: 'error', message: errorMessage(e) }) }
    finally { setBusy(false) }
  }
  function refresh(v: View) { setView(v); if (!agentId) setSelected(v.defaultProfileId ?? '') }
  async function testEndpoint(endpoint: TtsEndpoint, draft = false) {
    setBusy(true); setTesting(draft ? 'draft' : endpoint.id); setToast(null)
    try {
      const result = await requestJson<TestResult>(draft ? '/api/settings/tts/endpoints/test' : '/api/settings/tts/endpoints/' + encodeURIComponent(endpoint.id) + '/test', {
        method: 'POST', ...(draft ? { body: JSON.stringify(endpoint) } : {}),
      }, 10000)
      setToast({ kind: result.ok ? 'success' : 'error', message: endpoint.name + '：' + (result.ok ? result.detail : result.error) + '（' + result.elapsedMs + 'ms）' })
    } catch (e) { setToast({ kind: 'error', message: errorMessage(e) }) }
    finally { setBusy(false); setTesting(null) }
  }
  function edit(p: TtsProfile) { setDraft(p); setJson(JSON.stringify(p.parameters, null, 2)) }
  const profileOptions = [{ value: '', label: agentId ? '使用默认档案' : '未选择' }, ...view.profiles.map(p => ({ value: p.id, label: p.name }))]
  return <kit.Panel title={agentId ? '语音配置' : 'Text To Speech · Fish Audio'}>
    <Toast message={toast?.message ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    <p className="mb-3 text-sm text-muted">{agentId ? '留空时使用默认 TTS 档案；未配置任何档案时不启用语音。' : 'API 端点保存地址和 Key；语音档案引用端点，独立保存合成模型、语音模型 ID 和参数。'}</p>
    <div className="flex flex-wrap items-center gap-2">
      <div className="min-w-48 flex-1"><Select label={agentId ? 'Agent TTS 档案' : '默认 TTS 档案'} disabled={busy || !loaded} value={selected} onChange={setSelected} options={profileOptions} searchable /></div>
      <button className={miniBtn} disabled={busy || !loaded || (!agentId && !selected)} onClick={() => void run(async () => {
        const path = agentId ? '/api/agents/' + encodeURIComponent(agentId) + '/tts' : '/api/settings/tts/default'
        await requestJson(path, { method: 'PUT', body: JSON.stringify({ profileId: selected || null }) })
      })}>保存{agentId ? '选择' : '默认'}</button>
    </div>
    {!agentId && <>
      <div className="mt-5 flex items-center justify-between gap-2"><h3 className="text-sm font-semibold">API 端点</h3>
        <button className={miniBtn} disabled={busy || !loaded} onClick={() => setEndpointDraft({ id: '', name: '', baseUrl: 'https://api.fish.audio', apiKey: '' })}>添加 API 端点</button>
      </div>
      <div className="mt-2 space-y-2">{view.endpoints.map(e => {
        const references = view.profiles.filter(p => p.endpointId === e.id).length
        return <div key={e.id} className="flex flex-wrap items-center gap-3 border-b border-line py-2">
          <span className="min-w-0 flex-1"><span className="block">{e.name}</span><span className="break-all text-xs text-muted">{e.baseUrl} · {references} 份档案</span></span>
          <button className={miniBtn} disabled={busy} onClick={() => setEndpointDraft(e)}>编辑</button>
          <button className={miniBtn} disabled={busy} onClick={() => void testEndpoint(e)}>{testing === e.id ? '测试中…' : '测试'}</button>
          <button className={miniBtn} disabled={busy || references > 0} title={references ? '请先修改或删除引用此端点的档案' : '删除 API 端点'} onClick={() => void run(async () => refresh(await requestJson<View>('/api/settings/tts/endpoints/' + e.id, { method: 'DELETE' })), 'API 端点已删除')}>删除</button>
        </div>
      })}</div>
      <div className="mt-5 flex items-center justify-between gap-2"><h3 className="text-sm font-semibold">语音档案</h3>
        <button className={miniBtn} disabled={busy || !loaded} onClick={() => {
          if (!view.endpoints.length) { setToast({ kind: 'error', message: '请先添加 TTS API 端点' }); return }
          edit({ id: '', name: '', endpointId: view.endpoints[0].id, model: 's2.1-pro', referenceId: '', parameters })
        }}>添加 TTS 档案</button>
      </div>
      <div className="mt-2 space-y-2">{view.profiles.map(p => <div key={p.id} className="flex flex-wrap items-center gap-3 border-b border-line py-2">
        <span className="min-w-0 flex-1"><span className="block">{p.name}</span><span className="break-all text-xs text-muted">{view.endpoints.find(e => e.id === p.endpointId)?.name} · {p.model} · {p.referenceId}</span></span>
        <button className={miniBtn} disabled={busy} onClick={() => edit(p)}>编辑</button>
        <button className={miniBtn} disabled={busy} onClick={() => void run(async () => refresh(await requestJson<View>('/api/settings/tts/' + p.id, { method: 'DELETE' })), 'TTS 档案已删除')}>删除</button>
      </div>)}</div>
      {endpointDraft && <FloatWindow title={endpointDraft.id ? '编辑 TTS API 端点' : '添加 TTS API 端点'} onClose={() => { if (!busy) setEndpointDraft(null) }} width={480}>
        <form className="space-y-3" onSubmit={e => { e.preventDefault(); void run(async () => {
          refresh(await requestJson<View>('/api/settings/tts/endpoints' + (endpointDraft.id ? '/' + endpointDraft.id : ''), { method: endpointDraft.id ? 'PUT' : 'POST', body: JSON.stringify(endpointDraft) }))
          setEndpointDraft(null)
        }) }}>
          {(['name', 'baseUrl', 'apiKey'] as const).map(key => <kit.Field key={key} label={{ name: '端点名称', baseUrl: 'Base URL', apiKey: 'API Key' }[key]}>
            <input required disabled={busy} autoFocus={key === 'name'} type={key === 'apiKey' ? 'password' : 'text'} value={endpointDraft[key]} onChange={e => setEndpointDraft({ ...endpointDraft, [key]: e.target.value })} />
          </kit.Field>)}
          <div className="flex justify-end gap-2"><button type="button" className={miniBtn} disabled={busy || !endpointDraft.baseUrl.trim() || !endpointDraft.apiKey.trim()} onClick={() => void testEndpoint(endpointDraft, true)}>{testing === 'draft' ? '测试中…' : '测试'}</button><button type="button" className={miniBtn} disabled={busy} onClick={() => setEndpointDraft(null)}>取消</button><button type="submit" className={btnPrimary()} disabled={busy}>保存端点</button></div>
        </form>
      </FloatWindow>}
      {draft && <FloatWindow title={draft.id ? '编辑 TTS 档案' : '添加 TTS 档案'} onClose={() => { if (!busy) setDraft(null) }} width={540}>
        <form className="space-y-3" onSubmit={e => { e.preventDefault(); void run(async () => {
          const parsed: unknown = JSON.parse(json)
          if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new SyntaxError()
          refresh(await requestJson<View>('/api/settings/tts' + (draft.id ? '/' + draft.id : ''), { method: draft.id ? 'PUT' : 'POST', body: JSON.stringify({ ...draft, parameters: parsed }) }))
          setDraft(null)
        }) }}>
          <kit.Field label="档案名称"><input required autoFocus disabled={busy} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} /></kit.Field>
          <div className="grid grid-cols-2 items-start gap-3">
            <kit.Field label="API 端点" wrap={false} className="min-w-0"><Select label="API 端点" disabled={busy} searchable value={draft.endpointId} onChange={endpointId => setDraft({ ...draft, endpointId })} options={view.endpoints.map(e => ({ value: e.id, label: e.name }))} /></kit.Field>
            <kit.Field label="合成模型" className="min-w-0"><input required disabled={busy} value={draft.model} onChange={e => setDraft({ ...draft, model: e.target.value })} /></kit.Field>
          </div>
          <kit.Field label="语音模型 ID（reference_id）"><input required disabled={busy} value={draft.referenceId} onChange={e => setDraft({ ...draft, referenceId: e.target.value })} /></kit.Field>
          <kit.Field label="合成参数（JSON）" hint="可设置 temperature、top_p、prosody.speed / volume、format 等 Fish Audio 参数。">
            <textarea rows={10} disabled={busy} className="w-full font-mono" value={json} onChange={e => setJson(e.target.value)} />
          </kit.Field>
          <div className="flex justify-end gap-2"><button type="button" className={miniBtn} disabled={busy} onClick={() => setDraft(null)}>取消</button><button type="submit" className={btnPrimary()} disabled={busy || !draft.endpointId}>保存档案</button></div>
        </form>
      </FloatWindow>}
    </>}
  </kit.Panel>
}
