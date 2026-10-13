import { useState, type FormEvent } from 'react'
import FloatWindow from './FloatWindow'
import { Select } from './formKit'
import type { Project } from '../api/client'

export type ProjectChoice = { projectId?: string; name: string; kind: string; note: string }
type Props = {
  organize: boolean
  initialName: string
  projects: Project[]
  onConfirm: (choice: ProjectChoice) => Promise<void>
  onClose: () => void
}

export default function ProjectDialog({ organize, initialName, projects, onConfirm, onClose }: Props) {
  const [projectId, setProjectId] = useState('')
  const [name, setName] = useState(initialName)
  const [kind, setKind] = useState('other')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const fiction = ['novel', 'story', 'rpg'].includes(kind)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (saving || (!projectId && !name.trim())) return
    setSaving(true)
    setError('')
    try {
      await onConfirm({ projectId: projectId || undefined, name: name.trim(), kind, note: note.trim() })
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '项目操作失败，请重试')
    } finally { setSaving(false) }
  }
  const inputClass = 'w-full rounded border border-line bg-bg px-3 py-2 text-sm text-fg outline-none focus:border-accent'
  return <FloatWindow title={organize ? '将当前会话整理为项目' : '新建项目并绑定'} width={430} onClose={() => { if (!saving) onClose() }}>
    <form onSubmit={submit} className="space-y-4 p-4">
      <p className="text-xs leading-relaxed text-muted">把相关会话放进同一个项目，在新会话中可以让同一位 Agent 接上最近进度。</p>
      {organize && <label className="block space-y-1 text-xs text-muted">整理到
        <Select value={projectId} onChange={setProjectId} options={[{ value: '', label: '新建项目' }, ...projects.map(p => ({ value: p.id, label: p.name }))]} />
      </label>}
      {!projectId && <>
        <label className="block space-y-1 text-xs text-muted">项目名称
          <input autoFocus required maxLength={120} value={name} onChange={e => setName(e.target.value)} className={inputClass} placeholder="例如：我的小说、个人网站" />
        </label>
        <label className="block space-y-1 text-xs text-muted">项目类型
          <Select value={kind} onChange={setKind} options={[
            { value: 'other', label: '通用项目' }, { value: 'software', label: '软件开发' },
            { value: 'research', label: '研究 / 学习' }, { value: 'novel', label: '小说' },
            { value: 'story', label: '剧本 / 故事' }, { value: 'rpg', label: '角色扮演' },
          ]} />
        </label>
        <label className="block space-y-1 text-xs text-muted">简介（可选）
          <textarea value={note} onChange={e => setNote(e.target.value)} maxLength={2000} rows={2} className={inputClass} />
        </label>
        {fiction && <p className="text-xs text-muted">作品设定与真实用户信息分开保存。</p>}
      </>}
      {organize && <p className="text-xs text-muted">保留原会话和历史记忆，将最近对话作为有来源的续接进度。</p>}
      {error && <p role="alert" className="text-xs text-red-500">{error}</p>}
      <div className="flex justify-end gap-2">
        <button type="button" disabled={saving} onClick={onClose} className="rounded px-3 py-2 text-sm text-muted hover:bg-fg/5">取消</button>
        <button type="submit" disabled={saving || (!projectId && !name.trim())} className="rounded bg-accent px-3 py-2 text-sm text-accent-fg disabled:opacity-50">
          {saving ? '正在保存…' : organize ? '整理并绑定' : '创建并绑定'}
        </button>
      </div>
    </form>
  </FloatWindow>
}
