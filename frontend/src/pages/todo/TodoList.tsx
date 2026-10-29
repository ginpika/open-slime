import type { TodoItem } from '../../api/todo'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'
import { duration } from './format'

export function TodoList({ title, items, empty, activeTodo, today, busy, onDetail, onComplete, onStart, onPause, onPlan }: {
  title: string; items: TodoItem[]; empty: string; activeTodo: string | null; today: string; busy: boolean
  onDetail: (id: string) => void; onComplete: (todo: TodoItem) => void; onStart: (todo: TodoItem) => void
  onPause: () => void; onPlan: (todo: TodoItem) => void
}) {
  return <section className="todo-list-section"><header className="todo-section-heading"><h2>{title}<span>{items.length}</span></h2></header>{items.length ? items.map(t => {
    const done = t.state === 'done', running = activeTodo === t.id, later = !done && t.plannedOn !== today
    const state = running ? '正在投入' : done ? '已完成' : t.firstStartedAt !== null ? '已经开始' : t.plannedOn ? `计划 ${t.plannedOn}` : '还没安排'
    return <article className={`todo-item${done ? ' todo-item--done' : ''}`} key={t.id}>
      <button className="todo-check" type="button" aria-label={`${done ? '重新打开' : '完成'}：${t.title}`} aria-pressed={done} disabled={busy} onClick={() => onComplete(t)}><span>{done && <AppIcon name="check" size={12} />}</span></button>
      <div className="todo-item-body"><button type="button" className="todo-item-title" onClick={() => onDetail(t.id)}>{t.title}</button><div className="todo-item-meta">{t.projectId && <span className="todo-tag">{t.projectName ?? '项目标签'}{t.projectStatus !== 'active' ? ' · 已停用' : ''}</span>}<span>{state}</span>{t.spentMillis > 0 && <span>· {duration(t.spentMillis)}</span>}{t.dueAt !== null && <span className="todo-deadline">截止 {new Date(t.dueAt).toLocaleDateString('zh-CN')}</span>}</div></div>
      <Button size="sm" variant="quiet" disabled={busy} onClick={() => done ? onDetail(t.id) : running ? onPause() : later ? onPlan(t) : onStart(t)}>{done ? '查看' : running ? '暂停' : later ? '今天做' : '开始'}</Button>
    </article>
  }) : <p className="todo-empty">{empty}</p>}</section>
}
