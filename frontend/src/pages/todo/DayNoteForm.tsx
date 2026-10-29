import { useState, type FormEvent } from 'react'
import type { DayNote } from '../../api/todo'
import { Button } from '../../components/ui'
import { todoError } from './format'

export default function DayNoteForm({ note, busy, onSave }: { note: DayNote; busy: boolean; onSave: (note: DayNote, body: string) => Promise<DayNote> }) {
  const [draft, setDraft] = useState(() => ({ base: note, body: note.body }))
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const dirty = draft.body !== draft.base.body
  const ownDraft = dirty || (draft.base.day === note.day && draft.base.version > note.version)
  const base = ownDraft ? draft.base : note
  const body = ownDraft ? draft.body : note.body
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError(''); setSaved(false)
    try { const next = await onSave(base, body); setDraft({ base: next, body: next.body }); setSaved(true) }
    catch (error) { setError(todoError(error)) }
  }
  return <form className="todo-day-note" onSubmit={submit}>
    <label><span>{base.day === note.day ? '给这一天留一句话' : `给 ${base.day} 留一句话`}</span><textarea value={body} disabled={busy} maxLength={4000} rows={3} onChange={e => { setDraft({ base, body: e.target.value }); setSaved(false) }} placeholder="今天有什么值得记住？也可以留白。" /></label>
    <div className="todo-actions"><Button size="sm" type="submit" disabled={busy || !dirty}>保存回顾</Button><small role="status">{saved ? '已保存' : dirty ? `尚未保存 · ${base.day}` : note.day}</small></div>
    {error && <div className="todo-error" role="alert"><p>{error}</p><Button size="sm" onClick={() => { setDraft({ base: note, body: note.body }); setError('') }}>读取最新回顾，舍弃当前修改</Button></div>}
  </form>
}
