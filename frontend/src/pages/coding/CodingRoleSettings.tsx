import { useEffect, useState } from 'react'
import { getSettings, type ModelView } from '../../api/client'
import { codingError, codingRoles, saveCodingRole, type CodingRole } from '../../api/coding'
import { Button } from '../../components/ui'
import { Select } from '../../components/Select'

function RoleForm({ role, models, onSaved }: { role: CodingRole; models: ModelView[]; onSaved: (role: CodingRole) => void }) {
  const [model, setModel] = useState(role.modelId || ''), [prompt, setPrompt] = useState(role.instructions), [busy, setBusy] = useState(false), [error, setError] = useState(''), [saved, setSaved] = useState(false)
  const save = async () => { setBusy(true); setError(''); setSaved(false); try { onSaved(await saveCodingRole(role.id, { modelId: model || null, instructions: prompt })); setSaved(true) } catch (e) { setError(codingError(e)) } finally { setBusy(false) } }
  return <details className="rounded-lg border border-line p-3"><summary className="cursor-pointer text-sm"><strong>{role.name}</strong><span className="ml-2 text-muted">{role.description} · v{role.version}</span></summary><div className="mt-3 flex flex-col gap-3">
    <label className="text-xs">角色模型<Select menuClassName="coding-select-menu" size="compact" searchable label={`${role.id} 模型`} value={model} onChange={value => { setModel(value); setSaved(false) }} options={[{ value: '', label: '继承当前编程会话模型' }, ...models.map(item => ({ value: item.id, label: item.name }))]} /></label>
    <label className="text-xs">角色提示词<textarea aria-label={`${role.id} 提示词`} className="ui-input scrollbar-slim mt-1 min-h-32 w-full text-base" value={prompt} maxLength={20000} onChange={e => { setPrompt(e.target.value); setSaved(false) }} /></label>
    <p className="text-xs text-muted">此角色可浏览目录、读取代码、搜索和查看变更，不能修改文件、运行命令或再派生 Agent。新建子任务保存此配置，继续已有子任务使用原配置。</p>
    {error && <p role="alert" className="text-xs text-red-500">{error}</p>}{saved && <p role="status" className="text-xs text-muted">角色配置已保存。</p>}<Button size="sm" disabled={busy || !prompt.trim()} onClick={() => void save()}>{busy ? '保存中…' : `保存 ${role.id}`}</Button>
  </div></details>
}
export default function CodingRoleSettings() {
  const [roles, setRoles] = useState<CodingRole[]>([]), [models, setModels] = useState<ModelView[]>([]), [error, setError] = useState(''), [loading, setLoading] = useState(true)
  useEffect(() => { let stopped = false; void Promise.allSettled([codingRoles(), getSettings()]).then(([r, m]) => { if (stopped) return; if (r.status === 'fulfilled') setRoles(r.value); if (m.status === 'fulfilled') setModels(m.value.models); setError([r, m].flatMap(item => item.status === 'rejected' ? [codingError(item.reason)] : []).join('；')); setLoading(false) }); return () => { stopped = true } }, [])
  return <section aria-label="编程状态的内置角色" className="coding-role-settings flex flex-col gap-3"><h3 className="ui-heading text-sm">内置子 Agent</h3><p className="text-xs text-muted">主 Agent 按需委派探索、规划和审阅。每个子任务保留独立上下文与过程，角色配置属于当前用户。</p>{loading ? <p className="text-xs">正在读取角色…</p> : roles.map(role => <RoleForm key={role.id} role={role} models={models} onSaved={next => setRoles(previous => previous.map(item => item.id === next.id ? next : item))} />)}{error && <p role="alert" className="text-xs text-red-500">{error}</p>}</section>
}
