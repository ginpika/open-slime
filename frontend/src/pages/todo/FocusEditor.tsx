import { useState, type FormEvent } from 'react'
import type { FocusInput, PersonalFocus, TodoItem } from '../../api/todo'
import FloatWindow from '../../components/FloatWindow'
import { Field, Select } from '../../components/formKit'
import { Button } from '../../components/ui'
import { localInput, todoError } from './format'

export default function FocusEditor({ focus, todoId, items, busy, onClose, onSave, onDelete }: {
  focus?: PersonalFocus; todoId: string; items: TodoItem[]; busy: boolean
  onClose: () => void; onSave: (input: FocusInput, focus?: PersonalFocus) => Promise<void>
  onDelete: (focus: PersonalFocus) => Promise<void>
}) {
  const [selected, setSelected] = useState(focus?.todoId ?? todoId ?? items[0]?.id ?? '')
  const [start, setStart] = useState(() => localInput(focus?.startedAt ?? Date.now() - 30 * 60000))
  const [end, setEnd] = useState(() => localInput(focus?.endedAt ?? Date.now()))
  const [note, setNote] = useState(focus?.note ?? '')
  const [error, setError] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('')
    const startedAt = new Date(start).getTime(), endedAt = new Date(end).getTime()
    if (!selected || !Number.isFinite(startedAt) || !Number.isFinite(endedAt) || endedAt <= startedAt) { setError('请选择事项，且结束时间应晚于开始时间。'); return }
    try { await onSave({ todoId: selected, startedAt, endedAt, note, ...(focus ? { version: focus.version } : {}) }, focus) }
    catch (error) { setError(todoError(error)) }
  }
  const remove = async () => {
    if (!focus) return
    if (!confirmDelete) { setConfirmDelete(true); return }
    setError(''); try { await onDelete(focus) } catch (error) { setError(todoError(error)) }
  }
  return <FloatWindow title={focus ? '修正一段投入' : '补记一段投入'} width={520} onClose={() => { if (!busy) onClose() }}>
    <form className="todo-form" onSubmit={submit}><fieldset disabled={busy}>
      <Field label="投入的事项"><Select label="投入的事项" value={selected} disabled={!!focus || busy} onChange={setSelected} searchable options={[{ value: '', label: '选择事项', disabled: true }, ...items.map(t => ({ value: t.id, label: t.title }))]} /></Field>
      <div className="todo-form-pair"><Field label="开始时间"><input type="datetime-local" value={start} onChange={e => setStart(e.target.value)} required /></Field><Field label="结束时间"><input type="datetime-local" value={end} onChange={e => setEnd(e.target.value)} required /></Field></div>
      <Field label="这一段做了什么"><textarea value={note} onChange={e => setNote(e.target.value)} rows={3} maxLength={2000} /></Field>
      <p className="todo-secondary">按当前时区记录实际投入，允许跨天；不能与已有记录重叠。</p>
      <div className="todo-actions"><Button type="submit" variant="primary">{busy ? '正在保存…' : focus ? '保存修正' : '记下这段时间'}</Button>{focus && <Button variant="danger" onClick={() => void remove()}>{confirmDelete ? '确认删除这段投入' : '删除这段投入'}</Button>}</div>
      {confirmDelete && <p className="todo-secondary">删除后会从投入统计中扣除，历程中保留修正记录。</p>}
      {error && <p className="todo-error" role="alert">{error}</p>}
    </fieldset></form>
  </FloatWindow>
}
