import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import {
  addPersonalFocus, createTodo, deletePersonalFocus, editPersonalFocus, getTodoDetail,
  savePersonalDayNote, saveTodo, startPersonalFocus, stopPersonalFocus, transitionTodo,
  type DayNote, type FocusInput, type PersonalFocus, type TodoAction, type TodoDetail, type TodoInput, type TodoItem,
} from '../api/todo'
import { AppIcon } from '../components/AppIcon'
import { createProject, updateProject, type Project } from '../api/client'
import { Select } from '../components/Select'
import { Button } from '../components/ui'
import FloatWindow from '../components/FloatWindow'
import { LoadState } from '../components/LoadState'
import { usePageKit } from '../kit'
import DayNoteForm from './todo/DayNoteForm'
import DailyEffort from './todo/DailyEffort'
import TodoEditor from './todo/TodoEditor'
import FocusEditor from './todo/FocusEditor'
import TodoHistory from './todo/TodoHistory'
import ProjectWork from './todo/ProjectWork'
import ProjectEditor from './todo/ProjectEditor'
import type { PersonalProjectInput } from './todo/projectModel'
import TimeAllocation from './todo/TimeAllocation'
import { shiftDay } from './todo/workTime'
import { TodoList } from './todo/TodoList'
import { currentMonth, isOpen, shiftMonth, todoError } from './todo/format'
import { useTodos } from './todo/useTodos'
import './todo/todo.css'

type View = 'today' | 'all' | 'projects' | 'time' | 'review'
const VIEWS: { id: View; label: string }[] = [{ id: 'today', label: '今天' }, { id: 'all', label: '全部待办' }, { id: 'projects', label: '项目' }, { id: 'time', label: '时间分配' }, { id: 'review', label: '回望' }]

export default function Todos() {
  const kit = usePageKit()
  const [view, setView] = useState<View>('today')
  const [project, setProject] = useState('')
  const [selectedProject, setSelectedProject] = useState('')
  const [editingProject, setEditingProject] = useState<Project | null>(null)
  const [month, setMonth] = useState(currentMonth)
  const [selectedDay, setSelectedDay] = useState('')
  const isTaskView = view === 'today' || view === 'all'
  const scopedProject = isTaskView ? project : ''
  const data = useTodos(scopedProject, month, { review: view === 'review', projects: view === 'projects', day: view === 'time' ? selectedDay : null })
  const { overview, review, loading, reload } = data
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const [quick, setQuick] = useState('')
  const [adding, setAdding] = useState(false)
  const [creatingProject, setCreatingProject] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detailResult, setDetailResult] = useState<{ key: string; data: TodoDetail | null; error: string | null } | null>(null)
  const [detailRevision, setDetailRevision] = useState(0)
  const [focusEditor, setFocusEditor] = useState<{ todoId: string; focus?: PersonalFocus } | null>(null)
  const [pastNote, setPastNote] = useState<DayNote | null>(null)
  const detailKey = `${detailId}:${detailRevision}`
  const detail = detailId && detailResult?.data?.item.id === detailId ? detailResult.data : null
  const detailLoading = !!detailId && detailResult?.key !== detailKey
  const detailError = detailResult?.key === detailKey ? detailResult.error : null
  const mutation = useRef(false)
  useEffect(() => {
    if (!detailId) return
    const controller = new AbortController()
    getTodoDetail(detailId, controller.signal).then(value => { if (!controller.signal.aborted) setDetailResult({ key: detailKey, data: value, error: null }) })
      .catch(error => { if (!controller.signal.aborted) setDetailResult(previous => ({ key: detailKey, data: previous?.data?.item.id === detailId ? previous.data : null, error: todoError(error) })) })
    return () => controller.abort()
  }, [detailId, detailKey])
  useEffect(() => { if (!notice) return; const timeout = window.setTimeout(() => setNotice(''), 5000); return () => window.clearTimeout(timeout) }, [notice])

  async function mutate<T>(work: () => Promise<T>, message = ''): Promise<T> {
    if (mutation.current) throw new Error('上一项操作尚未完成，请稍候。')
    mutation.current = true; setBusy(true); setActionError(null)
    try {
      const result = await work(); reload(); if (detailId) setDetailRevision(v => v + 1)
      if (message) setNotice(message)
      return result
    } catch (error) { setActionError(todoError(error)); reload(); throw error }
    finally { mutation.current = false; setBusy(false) }
  }
  const quiet = (work: Promise<unknown>) => { void work.catch(() => {}) }
  const disabled = busy || !overview
  const openDetail = (id: string) => { setAdding(false); setDetailResult(null); setDetailId(id); setDetailRevision(v => v + 1) }
  const closeDetail = () => { if (!busy) { setDetailId(null); setDetailResult(null) } }
  const state = async (t: TodoItem, action: TodoAction) => { await mutate(() => transitionTodo(t, action), action === 'complete' ? '又留下一件做过的事。' : action === 'drop' ? '这段尝试与投入已保留。' : '已重新打开，原历程保留。') }
  const save = async (input: TodoInput) => {
    if (adding) { await mutate(() => createTodo(input), '想法已收下。'); setAdding(false) }
    else if (detailId) await mutate(() => saveTodo(detailId, input), '事项已保存。')
  }
  const plan = (t: TodoItem, plannedOn: string | null) => mutate(() => saveTodo(t.id, { ...t, plannedOn }), plannedOn ? '已安排到今天。' : '暂不安排，想法还在。')
  const pause = () => { if (overview?.active) quiet(mutate(() => stopPersonalFocus(overview.active!.id), '这一段投入已记录。')) }
  const start = (t: TodoItem) => quiet(mutate(() => startPersonalFocus(t.id, overview?.active?.id ?? null), `开始投入：${t.title}`))
  const capture = async (event: FormEvent) => {
    event.preventDefault(); if (!quick.trim()) return
    try { await mutate(() => createTodo({ title: quick.trim(), note: '', projectId: defaultProject || null, plannedOn: null, dueAt: null }), '想法已收下，不必现在就做。'); setQuick(''); setView('all') } catch { /* inline action error */ }
  }
  const saveFocus = async (input: FocusInput, focus?: PersonalFocus) => {
    await mutate(() => focus ? editPersonalFocus(focus.id, input) : addPersonalFocus(input), '投入记录已保存。'); setFocusEditor(null)
  }
  const removeFocus = async (focus: PersonalFocus) => { await mutate(() => deletePersonalFocus(focus), '已扣除这段投入，修正记录仍保留。'); setFocusEditor(null) }
  const saveNote = async (note: DayNote, body: string) => {
    const result = await mutate(() => savePersonalDayNote(note, body)); if (pastNote?.day === result.day) setPastNote(result); return result
  }
  const newProject = (input: PersonalProjectInput) => mutate(() => createProject(input), '项目已就绪，可以开始记录想做的事。')
  const tabKeys = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? VIEWS.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + VIEWS.length) % VIEWS.length
    setView(VIEWS[next].id); document.getElementById(`todo-tab-${VIEWS[next].id}`)?.focus()
  }
  const list = (title: string, items: TodoItem[], empty: string) => <TodoList title={title} items={items} empty={empty} activeTodo={overview?.active?.todoId ?? null} today={overview?.today ?? ''} busy={disabled} onDetail={openDetail} onComplete={t => quiet(state(t, t.state === 'done' ? 'reopen' : 'complete'))} onStart={start} onPause={pause} onPlan={t => quiet(plan(t, overview!.today))} />
  const items = overview?.items ?? []
  const open = items.filter(isOpen)
  const todayItems = open.filter(t => t.plannedOn === overview?.today)
  const overdue = open.filter(t => t.plannedOn && t.plannedOn < (overview?.today ?? ''))
  const active = overview?.active
  const focusItems = [...new Map([...items, ...(data.dayWork?.items ?? []), ...(detail ? [detail.item] : [])].map(t => [t.id, t])).values()]
  const defaultProject = data.projects.some(p => p.id === scopedProject && p.status === 'active') ? scopedProject : ''
  const shownDay = selectedDay || overview?.today || ''
  const showProjectTodos = (id: string) => { setProject(id); setView('all') }

  return <div className="personal-todo-page"><kit.Page title="待办" en="PERSONAL TODO" meta={<div className="todo-actions"><Button disabled={busy} onClick={reload}>刷新</Button>{view === 'projects' ? <Button variant="primary" disabled={busy} onClick={() => setCreatingProject(true)}><AppIcon name="plus" />新建项目</Button> : <Button variant="primary" disabled={disabled} onClick={() => { setDetailId(null); setAdding(true) }}><AppIcon name="plus" />记一件事</Button>}</div>}>
    <p className="todo-intro">{view === 'today' && overview ? `${new Date(overview.dayStart).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })} · 今天，想把精力留给什么？` : view === 'review' ? '那些认真度过的日子，都有迹可循。' : view === 'projects' ? '从一个项目，看见想法如何一步步成为现实。' : view === 'time' ? '看看一天的时间，留给了哪些事。' : '先接住想法，再慢慢把它做成现实。'}</p>
    <div className="todo-view-tabs" role="tablist" aria-label="待办视图">{VIEWS.map((tab, index) => <button key={tab.id} id={`todo-tab-${tab.id}`} type="button" role="tab" aria-controls="todo-view" aria-selected={view === tab.id} onClick={() => setView(tab.id)} onKeyDown={event => tabKeys(event, index)}>{tab.label}{overview && (tab.id === 'today' || tab.id === 'all') && <span>{tab.id === 'today' ? todayItems.length : open.length}</span>}</button>)}</div>
    {view !== 'projects' && <div className="todo-filters">
      {isTaskView && <><div className="todo-project-filter"><span>项目筛选</span><Select label="项目筛选" value={project} onChange={setProject} disabled={busy} size="compact" searchable className="todo-project-select" options={[{ value: '', label: '全部事项' }, { value: 'unassigned', label: '未关联项目' }, ...data.projects.map(p => ({ value: p.id, label: `${p.name}${p.status !== 'active' ? '（已停用）' : ''}` }))]} /></div>
      {project && <Button size="sm" variant="quiet" disabled={busy} onClick={() => setProject('')}>清除筛选</Button>}</>}
      {view === 'review' && <div className="todo-month-picker"><Button size="sm" disabled={busy} aria-label="上个月" onClick={() => setMonth(shiftMonth(month, -1))}><AppIcon name="chevron-left" /></Button><label><span className="sr-only">回顾月份</span><input type="month" value={month} onChange={e => { if (e.target.value) setMonth(e.target.value) }} /></label><Button size="sm" disabled={busy || month >= currentMonth()} aria-label="下个月" onClick={() => setMonth(shiftMonth(month, 1))}><AppIcon name="chevron-right" /></Button></div>}
      {view === 'time' && <div className="todo-month-picker"><Button size="sm" disabled={busy || !shownDay || shownDay <= '1970-01-01'} aria-label="前一天" onClick={() => setSelectedDay(shiftDay(shownDay, -1))}><AppIcon name="chevron-left" /></Button><label><span className="sr-only">时间分配日期</span><input type="date" value={shownDay} min="1970-01-01" max={overview?.today} onChange={e => setSelectedDay(e.target.value === overview?.today ? '' : e.target.value)} /></label><Button size="sm" disabled={busy || !shownDay || shownDay >= (overview?.today ?? '')} aria-label="后一天" onClick={() => { const next = shiftDay(shownDay, 1); setSelectedDay(next === overview?.today ? '' : next) }}><AppIcon name="chevron-right" /></Button><Button size="sm" variant="quiet" disabled={busy || !selectedDay} onClick={() => setSelectedDay('')}>今天</Button></div>}
    </div>}
    <LoadState loading={loading && !overview} error={data.error} hasData={!!overview} onRetry={reload} label="个人待办" />
    {actionError && <div className="todo-error" role="alert"><p>{actionError}</p><Button size="sm" onClick={() => { setActionError(null); reload(); if (detailId) setDetailRevision(v => v + 1) }}>重新读取</Button></div>}
    {notice && <p className="todo-notice" role="status">{notice}</p>}
    {detailError && <FloatWindow title="事项读取失败" onClose={closeDetail}><div className="todo-form"><p className="todo-error" role="alert">{detailError}</p><Button onClick={() => setDetailRevision(v => v + 1)}>重试读取</Button></div></FloatWindow>}
    {detailId && detailLoading && !detail && !detailError && <FloatWindow title="事项详情" onClose={closeDetail}><p role="status">正在读取事项…</p></FloatWindow>}
    <div id="todo-view" role="tabpanel" aria-labelledby={`todo-tab-${view}`}>
      {overview && (view === 'today' || view === 'all') && <div className="todo-columns"><div className="todo-primary">
        <form className="todo-capture" onSubmit={event => void capture(event)}><AppIcon name="plus" /><label className="sr-only" htmlFor="todo-quick-input">记下一个想法</label><input id="todo-quick-input" value={quick} onChange={e => setQuick(e.target.value)} maxLength={160} placeholder="有什么想做的？先记下来。" disabled={disabled} required /><Button type="submit" size="sm" variant="quiet" disabled={disabled || !quick.trim()}>记下</Button></form>
        {view === 'today' ? <>
          {overdue.length > 0 && <section className="todo-overdue"><h2>之前安排的事 <span>{overdue.length}</span></h2>{overdue.map(t => <div key={t.id}><button type="button" onClick={() => openDetail(t.id)}>{t.title}<small>原计划 {t.plannedOn}</small></button><div className="todo-actions"><Button size="sm" disabled={disabled} onClick={() => quiet(plan(t, overview.today))}>移到今天</Button><Button size="sm" variant="quiet" disabled={disabled} onClick={() => quiet(plan(t, null))}>暂不安排</Button></div></div>)}</section>}
          {list('今天想推进', todayItems, '今天还没有安排。选一件想推进的事，或给自己留白。')}
          {list('今天已完成', items.filter(t => t.state === 'done' && t.completedAt !== null && t.completedAt >= overview.dayStart && t.completedAt < overview.dayEnd), '完成的事会留在这里。')}
          {list('留给以后', open.filter(t => !t.plannedOn || t.plannedOn > overview.today).slice(0, 3), '暂时没有留待以后的想法。')}
        </> : <>{list('进行中', open.filter(t => t.state === 'doing'), '开始投入的事会出现在这里。')}{list('准备做', open.filter(t => t.state === 'ready'), '准备好了，再选一天开始。')}{list('想法', open.filter(t => t.state === 'idea'), '一闪而过的想法，也值得被记下。')}</>}
      </div><aside className="todo-support">
        <DailyEffort overview={overview} receivedAt={data.receivedAt} project={project} busy={disabled} canBackfill={!!focusItems.length} onDetail={openDetail} onPause={pause} onBackfill={() => setFocusEditor({ todoId: focusItems[0]?.id ?? '' })} onTimeline={() => { setSelectedDay(''); setView('time') }} />
        <DayNoteForm note={overview.dayNote} busy={busy} onSave={saveNote} />
      </aside></div>}
      {view === 'projects' && <><LoadState loading={data.projectLoading && !data.summaries} error={data.projectError} hasData={!!data.summaries} onRetry={reload} label="项目概览" />{data.summaries && <ProjectWork projects={data.summaries} selected={selectedProject} disabled={busy} onSelect={setSelectedProject} onTodos={showProjectTodos} onEdit={setEditingProject} />}</>}
      {view === 'review' && <div className="todo-review-region" aria-busy={data.reviewLoading}><LoadState loading={data.reviewLoading && !review} error={data.reviewError} hasData={!!review} onRetry={reload} label="月度回顾" />{review && <TodoHistory review={review} onDetail={openDetail} onNote={day => setPastNote(review.notes.find(n => n.day === day) ?? null)} />}</div>}
      {view === 'time' && <div className="todo-timeline-region" aria-busy={data.dayLoading}><LoadState loading={data.dayLoading && !data.dayWork} error={data.dayError} hasData={!!data.dayWork} onRetry={reload} label="时间分配" />{data.dayWork && <TimeAllocation day={data.dayWork} receivedAt={data.dayReceivedAt} filtered={false} busy={disabled} onDetail={openDetail} onEdit={focus => setFocusEditor({ todoId: focus.todoId, focus })} />}</div>}
    </div>
    {(adding || (detail && detailId === detail.item.id && !detailError)) && <TodoEditor key={adding ? 'new' : `${detail!.item.id}:${detail!.item.version}`} detail={adding ? null : detail} projects={data.projects} defaultProject={defaultProject} busy={busy || detailLoading} onClose={() => { if (!busy) { setAdding(false); closeDetail() } }} onSave={save} onTransition={state} onFocus={(todoId, focus) => setFocusEditor({ todoId, focus })} onCreateProject={newProject} activeTodoId={active?.todoId ?? null} onStart={async item => { await mutate(() => startPersonalFocus(item.id, active?.id ?? null), `开始投入：${item.title}`) }} />}
    {creatingProject && <ProjectEditor busy={busy} onClose={() => setCreatingProject(false)} onSave={async input => { const created = await newProject(input); setCreatingProject(false); setSelectedProject(created.id); setView('projects') }} />}
    {editingProject && <ProjectEditor key={editingProject.id} project={editingProject} busy={busy} onClose={() => setEditingProject(null)} onSave={async input => { await mutate(() => updateProject(editingProject.id, input), '项目资料已保存。'); setEditingProject(null) }} />}
    {focusEditor && <FocusEditor key={focusEditor.focus?.id ?? `new-${focusEditor.todoId}`} {...focusEditor} items={focusItems} busy={busy} onClose={() => { if (!busy) setFocusEditor(null) }} onSave={saveFocus} onDelete={removeFocus} />}
    {pastNote && <FloatWindow title={`${pastNote.day} · 每日回顾`} onClose={() => { if (!busy) setPastNote(null) }}><div className="todo-form"><DayNoteForm key={pastNote.day} note={pastNote} busy={busy} onSave={saveNote} /></div></FloatWindow>}
  </kit.Page></div>
}
