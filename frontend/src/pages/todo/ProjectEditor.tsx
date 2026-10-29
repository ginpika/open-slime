import { useState, type FormEvent } from 'react'
import FloatWindow from '../../components/FloatWindow'
import { Field } from '../../components/formKit'
import { Button } from '../../components/ui'
import { todoError } from './format'
import { Select } from '../../components/Select'
import type { Project } from '../../api/client'
import { PROJECT_KINDS, PROJECT_STATUSES, type PersonalProjectInput } from './projectModel'

export default function ProjectEditor({ project, busy, onClose, onSave }: { project?: Project; busy: boolean; onClose: () => void; onSave: (input: PersonalProjectInput) => Promise<void> }) {
  const [name, setName] = useState(project?.name ?? '')
  const [note, setNote] = useState(project?.note ?? '')
  const [kind, setKind] = useState(project?.kind ?? 'other')
  const [status, setStatus] = useState(project?.status ?? 'active')
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('')
    if (!name.trim()) { setError('给项目起个名字。'); return }
    try { await onSave({ name: name.trim(), note: note.trim(), kind, ...(project ? { status } : {}) }) } catch (error) { setError(todoError(error)) }
  }
  return <FloatWindow title={project ? '编辑项目' : '新建项目'} width={480} onClose={() => { if (!busy) onClose() }}><form className="todo-form" onSubmit={submit}><fieldset disabled={busy}>
    <Field label="项目名称"><input autoFocus value={name} onChange={e => setName(e.target.value)} maxLength={120} required placeholder="例如：个人助手迭代" /></Field>
    <Field label="项目分类"><Select label="项目分类" value={kind} onChange={setKind} disabled={busy} options={PROJECT_KINDS.some(option => option.value === kind) ? PROJECT_KINDS : [...PROJECT_KINDS, { value: kind, label: kind }]} /></Field>
    {project && <Field label="项目状态"><Select label="项目状态" value={status} onChange={setStatus} disabled={busy} options={PROJECT_STATUSES.filter(option => option.value !== 'deleted' || status === 'deleted')} /></Field>}
    <Field label="项目说明" hint="可选，记下这个项目想做成什么"><textarea value={note} onChange={e => setNote(e.target.value)} maxLength={4000} rows={3} /></Field>
    <div className="todo-actions"><Button type="submit" variant="primary" disabled={busy || !name.trim()}>{busy ? '正在保存…' : project ? '保存项目' : '创建项目'}</Button><Button variant="quiet" disabled={busy} onClick={onClose}>取消</Button></div>
    {error && <p className="todo-error" role="alert">{error}</p>}
  </fieldset></form></FloatWindow>
}
