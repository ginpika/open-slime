import { useState, type FormEvent } from 'react'
import type { TodoAction, TodoDetail, TodoInput, TodoItem, TodoProject, PersonalFocus } from '../../api/todo'
import FloatWindow from '../../components/FloatWindow'
import { Field, Select } from '../../components/formKit'
import { Button } from '../../components/ui'
import { dateTime, duration, isOpen, localInput, todoError } from './format'
import ProjectEditor from './ProjectEditor'
import type { PersonalProjectInput } from './projectModel'

type Props = {
  detail: TodoDetail | null; projects: TodoProject[]; defaultProject: string; busy: boolean
  onClose: () => void; onSave: (input: TodoInput) => Promise<void>
  onTransition: (item: TodoItem, action: TodoAction) => Promise<void>
  onFocus: (todoId: string, focus?: PersonalFocus) => void
  onStart: (item: TodoItem) => Promise<void>; activeTodoId: string | null
  onCreateProject: (input: PersonalProjectInput) => Promise<TodoProject>
}

export default function TodoEditor({ detail, projects, defaultProject, busy, onClose, onSave, onTransition, onFocus, onStart, activeTodoId, onCreateProject }: Props) {
  const item = detail?.item
  const [title, setTitle] = useState(item?.title ?? '')
  const [note, setNote] = useState(item?.note ?? '')
  const [projectId, setProjectId] = useState(item?.projectId ?? defaultProject)
  const [plannedOn, setPlannedOn] = useState(item?.plannedOn ?? '')
  const [due, setDue] = useState(localInput(item?.dueAt ?? null))
  const [error, setError] = useState('')
  const [creatingProject, setCreatingProject] = useState(false)
  const [createdProjects, setCreatedProjects] = useState<TodoProject[]>([])
  const dirty = !!item && (title !== item.title || note !== item.note || projectId !== (item.projectId ?? '') || plannedOn !== (item.plannedOn ?? '') || due !== localInput(item.dueAt))
  const options = [...new Map([...projects, ...createdProjects].map(p => [p.id, p])).values()].filter(p => p.status === 'active' || p.id === item?.projectId)
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('')
    const dueAt = due ? new Date(due).getTime() : null
    if (dueAt !== null && !Number.isFinite(dueAt)) { setError('请核对截止时间。'); return }
    try { await onSave({ title: title.trim(), note, projectId: projectId || null, plannedOn: plannedOn || null, dueAt, ...(item ? { version: item.version } : {}) }) }
    catch (error) { setError(todoError(error)) }
  }
  const changeState = async (action: TodoAction) => {
    if (!item) return
    setError('')
    try { await onTransition(item, action) } catch (error) { setError(todoError(error)) }
  }
  return <FloatWindow title={item ? '事项详情' : '记一件事'} width={560} onClose={() => { if (!busy) onClose() }}>
    <div className="todo-form">
      {item && <div className="todo-detail-intro"><span className="todo-tag">{{ idea: '想法', ready: '准备做', doing: '进行中', done: '已完成', dropped: '已放下' }[item.state]}</span><span>累计投入 {duration(item.spentMillis)}</span></div>}
      <form onSubmit={submit}>
        <fieldset disabled={busy}>
          <Field label="想做什么"><input autoFocus value={title} onChange={e => setTitle(e.target.value)} maxLength={160} required placeholder="先记下来，不用立刻想清所有细节" /></Field>
          <div className="todo-form-pair">
            <div><Field label="所属项目" hint="可不选"><Select label="所属项目" value={projectId} onChange={setProjectId} disabled={busy} searchable options={[{ value: '', label: '未关联项目' }, ...options.map(p => ({ value: p.id, label: `${p.name}${p.status !== 'active' ? `（${p.status === 'deleted' ? '已删除' : '已停用'}）` : ''}` }))]} /></Field><Button size="sm" variant="quiet" disabled={busy} onClick={() => setCreatingProject(true)}>新建项目</Button></div>
            <Field label="计划哪天做" hint="安排不是截止，可留空"><input type="date" value={plannedOn} onChange={e => setPlannedOn(e.target.value)} /></Field>
          </div>
          <Field label="备注 / 放下的原因"><textarea value={note} onChange={e => setNote(e.target.value)} maxLength={12000} rows={4} placeholder="记下最初为什么想做，或这段尝试留下了什么。成果链接也可以放在这里。" /></Field>
          <details><summary>确有截止日期时，再填写</summary><Field label="截止时间"><input type="datetime-local" value={due} onChange={e => setDue(e.target.value)} /></Field></details>
          <div className="todo-actions"><Button type="submit" variant="primary" disabled={!title.trim()}>{busy ? '正在保存…' : item ? '保存修改' : '收下这个想法'}</Button>{item && <Button onClick={() => onFocus(item.id)} disabled={dirty}>补记投入</Button>}</div>
        </fieldset>
      </form>
      {item && <><div className="todo-actions">
        {isOpen(item) ? <><Button disabled={busy || dirty || activeTodoId === item.id} onClick={() => { setError(''); void onStart(item).catch(error => setError(todoError(error))) }}>{activeTodoId === item.id ? '正在计时' : '开始投入'}</Button><Button disabled={busy || dirty} onClick={() => void changeState('complete')}>标记完成</Button><Button variant="quiet" disabled={busy || dirty} onClick={() => void changeState('drop')}>决定放下</Button></> : <Button disabled={busy || dirty} onClick={() => void changeState('reopen')}>重新打开</Button>}
      </div>{dirty && <p className="todo-secondary">先保存资料，再更改状态或补记时间。</p>}</>}
      {error && <p className="todo-error" role="alert">{error}</p>}
      {detail && <>
        <section className="todo-detail-section"><h2>投入记录</h2>{detail.focus.length ? detail.focus.map(f => <div key={f.id} className="todo-detail-focus"><div><p>{dateTime(f.startedAt)} — {f.endedAt === null ? '计时中' : dateTime(f.endedAt)}</p><small>{f.source === 'manual' ? '补记' : '计时'}{f.note ? ` · ${f.note}` : ''}</small></div>{f.endedAt !== null && <Button size="sm" variant="quiet" disabled={busy || dirty} onClick={() => onFocus(item!.id, f)}>修正</Button>}</div>) : <p className="todo-secondary">还没有投入记录，可以随时开始。</p>}</section>
        <section className="todo-detail-section"><h2>这件事的历程</h2><ol className="todo-event-list">{detail.events.map(e => <li key={e.id}><small>{dateTime(e.occurredAt)}</small><p>{e.detail.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, s => dateTime(new Date(s).getTime()))}</p></li>)}</ol></section>
      </>}
      {creatingProject && <ProjectEditor busy={busy} onClose={() => setCreatingProject(false)} onSave={async input => { const project = await onCreateProject(input); setCreatedProjects(previous => [...previous, project]); setProjectId(project.id); setCreatingProject(false) }} />}
    </div>
  </FloatWindow>
}
