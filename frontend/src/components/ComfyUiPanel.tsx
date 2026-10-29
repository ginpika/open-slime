import { useEffect, useState } from 'react'
import { ApiError, requestJson } from '../api/client'
import { usePageKit } from '../kit'
import { Select } from './formKit'
import FloatWindow from './FloatWindow'
import Toast, { type ToastKind } from './Toast'
import { btnPrimary, btnQuiet } from './ui'
import ComfyImage from './ComfyImage'
import './comfyui.css'

type Endpoint = { id: string; name: string; baseUrl: string; apiKey: string }
type Profile = {
  id: string; name: string; description: string; endpointId: string; workflow: Record<string, unknown>
  promptNodeId: string; promptInput: string; seedNodeId: string; seedInput: string; outputNodeId: string; timeoutSeconds: number
}
type View = { endpoints: Endpoint[]; profiles: Profile[]; defaultProfileId: string | null }
type TestResult = { ok: boolean; elapsedMs: number; detail: string | null; error: string | null }
type Generation = { generationId: string; promptId: string; images: { id: string; url: string }[] }
const root = '/api/settings/comfyui'

function errorMessage(error: unknown): string {
  if (error instanceof SyntaxError) return '工作流必须是有效的 JSON 对象'
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body); if (body.message || body.detail) return body.message || body.detail } catch { /* 使用通用错误 */ }
    return `ComfyUI 请求失败（HTTP ${error.status}）`
  }
  return error instanceof Error ? error.message : '操作失败，请检查配置和网络'
}

export default function ComfyUiPanel() {
  const kit = usePageKit()
  const [view, setView] = useState<View>({ endpoints: [], profiles: [], defaultProfileId: null })
  const [loaded, setLoaded] = useState(false)
  const [selected, setSelected] = useState('')
  const [endpoint, setEndpoint] = useState<Endpoint | null>(null)
  const [draft, setDraft] = useState<Profile | null>(null)
  const [workflow, setWorkflow] = useState('')
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: ToastKind; message: string } | null>(null)
  const [preview, setPreview] = useState<Profile | null>(null)
  const [prompt, setPrompt] = useState('')
  const [result, setResult] = useState<Generation | null>(null)
  function refresh(value: View) { setView(value); setSelected(value.defaultProfileId ?? '') }
  useEffect(() => {
    let alive = true
    void requestJson<View>(root).then(value => { if (alive) { refresh(value); setLoaded(true) } })
      .catch(error => { if (alive) setToast({ kind: 'error', message: errorMessage(error) }) })
    return () => { alive = false }
  }, [])
  async function run(action: () => Promise<void>, message = '已保存') {
    setBusy(true); setToast(null)
    try { await action(); setToast({ kind: 'success', message }) }
    catch (error) { setToast({ kind: 'error', message: errorMessage(error) }) }
    finally { setBusy(false) }
  }
  async function test(value: Endpoint) {
    setBusy(true); setToast(null)
    try {
      const response = await requestJson<TestResult>(root + '/endpoints/test', { method: 'POST', body: JSON.stringify(value) }, 10000)
      setToast({ kind: response.ok ? 'success' : 'error', message: `${response.detail || response.error}（${response.elapsedMs}ms）` })
    } catch (error) { setToast({ kind: 'error', message: errorMessage(error) }) }
    finally { setBusy(false) }
  }
  function edit(profile: Profile) { setDraft(profile); setWorkflow(JSON.stringify(profile.workflow, null, 2)) }
  return <kit.Panel title="ComfyUI 绘图" className="comfyui-panel">
    <Toast message={toast?.message ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    <p className="mb-4 text-base leading-6 text-muted">配置远程绘图服务，将不同模型的工作流保存为档案。会话中的 AI 可以选择档案生成图片。</p>
    {!loaded && <p role="status" className="mb-3 text-sm text-muted">配置尚未加载完成。</p>}
    <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-48 flex-1"><Select label="默认绘图档案" disabled={busy || !loaded} value={selected} onChange={setSelected}
        options={[{ value: '', label: '未选择默认档案' }, ...view.profiles.map(p => ({ value: p.id, label: p.name }))]} menuClassName="comfyui-select" searchable /></div>
      <button className={btnQuiet()} disabled={busy || !loaded} onClick={() => void run(async () => refresh(await requestJson<View>(root + '/default', { method: 'PUT', body: JSON.stringify({ profileId: selected || null }) })))}>保存默认档案</button>
    </div>
    <div className="mt-6 flex flex-wrap items-center justify-between gap-3"><h3 className="text-lg font-semibold">API 端点</h3>
      <button className={btnQuiet()} disabled={busy || !loaded} onClick={() => setEndpoint({ id: '', name: '', baseUrl: '', apiKey: '' })}>添加 ComfyUI 端点</button>
    </div>
    {!view.endpoints.length && loaded && <p className="mt-2 text-sm text-muted">尚未配置端点。填写后端可访问的 ComfyUI 服务根地址。</p>}
    {view.endpoints.map(e => {
      const references = view.profiles.filter(p => p.endpointId === e.id).length
      return <div key={e.id} className="flex flex-wrap items-center gap-3 border-b border-line py-3 text-base">
        <span className="min-w-0 flex-1"><span className="block">{e.name}</span><span className="block break-all text-sm text-muted">{e.baseUrl} · {references} 份档案</span></span>
        <button className={btnQuiet()} disabled={busy} onClick={() => setEndpoint(e)}>编辑</button>
        <button className={btnQuiet()} disabled={busy} onClick={() => void test(e)}>测试连接</button>
        <button className={btnQuiet()} disabled={busy || references > 0} title={references ? '请先修改或删除引用此端点的档案' : '删除端点'}
          onClick={() => void run(async () => refresh(await requestJson<View>(root + '/endpoints/' + e.id, { method: 'DELETE' })), '端点已删除')}>删除</button>
      </div>
    })}
    <div className="mt-6 flex flex-wrap items-center justify-between gap-3"><h3 className="text-lg font-semibold">工作流档案</h3>
      <button className={btnQuiet()} disabled={busy || !loaded || !view.endpoints.length} onClick={() => edit({ id: '', name: '', description: '', endpointId: view.endpoints[0].id,
        workflow: {}, promptNodeId: '', promptInput: 'text', seedNodeId: '', seedInput: 'seed', outputNodeId: '', timeoutSeconds: 600 })}>添加工作流档案</button>
    </div>
    {!view.profiles.length && loaded && <p className="mt-2 text-sm text-muted">每份档案固定模型和参数，AI 只提供提示词。先添加端点，再导入工作流。</p>}
    {view.profiles.map(p => <div key={p.id} className="flex flex-wrap items-center gap-3 border-b border-line py-3 text-base">
      <span className="min-w-0 flex-1"><span className="block">{p.name}{p.id === view.defaultProfileId ? ' · 默认' : ''}</span>
        <span className="block break-words text-sm text-muted">{view.endpoints.find(e => e.id === p.endpointId)?.name}{p.description ? ` · ${p.description}` : ''}</span></span>
      <button className={btnQuiet()} disabled={busy} onClick={() => edit(p)}>编辑</button>
      <button className={btnQuiet()} disabled={busy} onClick={() => { setPreview(p); setPrompt(''); setResult(null) }}>试生成</button>
      <button className={btnQuiet()} disabled={busy} onClick={() => void run(async () => refresh(await requestJson<View>(root + '/profiles/' + p.id, { method: 'DELETE' })), '档案已删除')}>删除</button>
    </div>)}
    {endpoint && <FloatWindow title={<span className="text-lg">{endpoint.id ? '编辑 ComfyUI 端点' : '添加 ComfyUI 端点'}</span>} width={540} onClose={() => { if (!busy) setEndpoint(null) }}>
      <form className="comfyui-form space-y-4 text-base" onSubmit={event => { event.preventDefault(); void run(async () => {
        refresh(await requestJson<View>(root + '/endpoints' + (endpoint.id ? '/' + endpoint.id : ''), { method: endpoint.id ? 'PUT' : 'POST', body: JSON.stringify(endpoint) }))
        setEndpoint(null)
      }) }}>
        <kit.Field label="端点名称"><input required autoFocus disabled={busy} value={endpoint.name} onChange={e => setEndpoint({ ...endpoint, name: e.target.value })} /></kit.Field>
        <kit.Field label="服务根地址" hint="例如 https://comfy.example.com；请求由应用后端发出，可包含网关路径前缀。">
          <input required type="url" disabled={busy} placeholder="https://comfy.example.com" value={endpoint.baseUrl} onChange={e => setEndpoint({ ...endpoint, baseUrl: e.target.value })} />
        </kit.Field>
        <kit.Field label="API Key（可选）" hint="配置后以 Authorization: Bearer 发送；自托管服务无需认证时留空。">
          <input type="password" disabled={busy} autoComplete="new-password" value={endpoint.apiKey} onChange={e => setEndpoint({ ...endpoint, apiKey: e.target.value })} />
        </kit.Field>
        <div className="flex flex-wrap justify-end gap-2"><button type="button" className={btnQuiet()} disabled={busy || !endpoint.baseUrl || !endpoint.name} onClick={() => void test(endpoint)}>测试连接</button>
          <button type="button" className={btnQuiet()} disabled={busy} onClick={() => setEndpoint(null)}>取消</button><button type="submit" className={btnPrimary()} disabled={busy}>保存端点</button></div>
      </form>
    </FloatWindow>}
    {draft && <FloatWindow title={<span className="text-lg">{draft.id ? '编辑工作流档案' : '添加工作流档案'}</span>} width={760} onClose={() => { if (!busy) setDraft(null) }}>
      <form className="comfyui-form space-y-4 text-base" onSubmit={event => { event.preventDefault(); void run(async () => {
        const parsed: unknown = JSON.parse(workflow)
        if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new SyntaxError()
        refresh(await requestJson<View>(root + '/profiles' + (draft.id ? '/' + draft.id : ''), { method: draft.id ? 'PUT' : 'POST', body: JSON.stringify({ ...draft, workflow: parsed }) }))
        setDraft(null)
      }) }}>
        <div className="grid items-start gap-3 sm:grid-cols-2">
          <kit.Field label="档案名称"><input required autoFocus disabled={busy} placeholder="例如 FLUX 插画" value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} /></kit.Field>
          <kit.Field label="API 端点" wrap={false}><Select label="工作流 API 端点" disabled={busy} value={draft.endpointId} onChange={endpointId => setDraft({ ...draft, endpointId })} options={view.endpoints.map(e => ({ value: e.id, label: e.name }))} menuClassName="comfyui-select" /></kit.Field>
        </div>
        <kit.Field label="用途说明（可选）" hint="AI 根据档案名称和说明选择适合的工作流。"><input disabled={busy} value={draft.description} onChange={e => setDraft({ ...draft, description: e.target.value })} /></kit.Field>
        <kit.Field label="工作流 JSON" hint="从 ComfyUI 导出 API 格式 JSON。模型、尺寸、步数等保留工作流原值。">
          <textarea required rows={8} disabled={busy} className="w-full font-mono text-sm leading-5" value={workflow} onChange={e => setWorkflow(e.target.value)} spellCheck={false} />
        </kit.Field>
        <label className="block text-sm">从文件导入
          <input type="file" accept=".json,application/json" disabled={busy} className="mt-1 block w-full text-sm" onChange={event => {
            const file = event.target.files?.[0]
            if (!file) return
            if (file.size > 2_000_000) { setToast({ kind: 'error', message: '工作流 JSON 不能超过 2 MB' }); return }
            void file.text().then(setWorkflow).catch(error => setToast({ kind: 'error', message: errorMessage(error) }))
          }} />
        </label>
        <div className="grid gap-3 sm:grid-cols-2">
          <kit.Field label="提示词节点 ID" hint="例如正向 CLIPTextEncode 节点的 ID。"><input required disabled={busy} value={draft.promptNodeId} onChange={e => setDraft({ ...draft, promptNodeId: e.target.value })} /></kit.Field>
          <kit.Field label="提示词输入字段"><input required disabled={busy} value={draft.promptInput} onChange={e => setDraft({ ...draft, promptInput: e.target.value })} /></kit.Field>
          <kit.Field label="随机种子节点 ID（可选）" hint="填写后每次生成随机种子；留空保留工作流种子。"><input disabled={busy} value={draft.seedNodeId} onChange={e => setDraft({ ...draft, seedNodeId: e.target.value })} /></kit.Field>
          <kit.Field label="种子输入字段"><input required={!!draft.seedNodeId} disabled={busy} value={draft.seedInput} onChange={e => setDraft({ ...draft, seedInput: e.target.value })} /></kit.Field>
          <kit.Field label="图片输出节点 ID（可选）" hint="留空接收所有 output 类型图片；填写可指定 SaveImage 等节点。"><input disabled={busy} value={draft.outputNodeId} onChange={e => setDraft({ ...draft, outputNodeId: e.target.value })} /></kit.Field>
          <kit.Field label="等待超时（秒）"><input required type="number" min={10} max={3600} disabled={busy} value={draft.timeoutSeconds} onChange={e => setDraft({ ...draft, timeoutSeconds: Number(e.target.value) })} /></kit.Field>
        </div>
        <div className="flex justify-end gap-2"><button type="button" className={btnQuiet()} disabled={busy} onClick={() => setDraft(null)}>取消</button><button type="submit" className={btnPrimary()} disabled={busy}>保存档案</button></div>
      </form>
    </FloatWindow>}
    {preview && <FloatWindow title={<span className="text-lg">{'试生成 · ' + preview.name}</span>} width={640} onClose={() => { if (!busy) setPreview(null) }}>
      <form className="comfyui-form space-y-4 text-base" onSubmit={event => { event.preventDefault(); setResult(null); void run(async () => {
        setResult(await requestJson<Generation>('/api/comfyui/generate', { method: 'POST', body: JSON.stringify({ profileId: preview.id, prompt }) }))
      }, '图片生成完成') }}>
        <kit.Field label="提示词"><textarea required rows={4} maxLength={20000} disabled={busy} className="w-full" value={prompt} onChange={e => setPrompt(e.target.value)} /></kit.Field>
        <p className="text-sm leading-5 text-muted">将实际调用远程服务并保存生成图片。超时或关闭页面后，远程任务可能继续运行。</p>
        {busy && <p role="status" className="text-base">正在等待 ComfyUI 生成图片…</p>}
        {result?.images.map(image => <ComfyImage key={image.id} src={image.url} alt="试生成图片" />)}
        <div className="flex justify-end gap-2"><button type="button" className={btnQuiet()} disabled={busy} onClick={() => setPreview(null)}>关闭</button><button type="submit" className={btnPrimary()} disabled={busy || !prompt.trim()}>{busy ? '生成中…' : '生成图片'}</button></div>
      </form>
    </FloatWindow>}
  </kit.Panel>
}
