import type { TodoReview } from '../../api/todo'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'
import { dayLabel, duration } from './format'

export default function TodoHistory({ review, onDetail, onNote }: { review: TodoReview; onDetail: (id: string) => void; onNote: (day: string) => void }) {
  return <>
    <section className="todo-review-summary"><p className="todo-secondary">{review.month.replace('-', ' 年 ')} 月 · 走过的日子</p><h2>{review.closed.length ? '那些做过的事，都有迹可循。' : '这一页，留给慢慢发生的事。'}</h2><div className="todo-review-stats"><div><strong>{review.completedCount}</strong><span>件事，已经完成</span></div><div><strong>{duration(review.spentMillis)}</strong><span>有记录的投入</span></div><div><strong>{review.activeDays}</strong><span>天，留下投入记录</span></div></div><p className="todo-secondary">统计只覆盖已记录的投入。{review.droppedCount > 0 ? `另有 ${review.droppedCount} 件事，在尝试后主动放下。` : '没有记录的日子，也可以自在度过。'}</p></section>
    <div className="todo-columns"><div><section className="todo-list-section"><header className="todo-section-heading"><h2>这个月留下了什么</h2></header>{review.closed.length ? <ol className="todo-memory-list">{review.closed.map(t => <li key={t.id}><small>{dayLabel(t.closedAt!)} · {t.state === 'done' ? '完成' : '主动放下'}</small><h3><button type="button" onClick={() => onDetail(t.id)}>{t.title}</button></h3>{t.note && <p className="todo-prose">{t.note}</p>}<div className="todo-item-meta">{t.projectId && <span className="todo-tag">{t.projectName}</span>}<span>累计投入 {duration(t.spentMillis)}</span></div></li>)}</ol> : <p className="todo-empty">这个月还没有结束的事项，已记录的投入仍会显示在右侧。</p>}</section>
      {review.notes.length > 0 && <section className="todo-list-section"><header className="todo-section-heading"><h2>留给日子的几句话</h2></header>{review.notes.map(note => <article className="todo-past-note" key={note.day}><button type="button" onClick={() => onNote(note.day)}>{note.day}</button><p className="todo-prose">{note.body}</p></article>)}</section>}
    </div><aside className="todo-support"><section><h2>这一月，精力去了哪里</h2><div className="todo-month-allocation">{review.allocations.length ? review.allocations.map(a => <div key={a.todoId} className="todo-allocation"><div><button type="button" onClick={() => onDetail(a.todoId)}>{a.title}</button><span>{duration(a.millis)}</span></div><div className="todo-allocation-track"><span style={{ width: `${review.spentMillis ? a.millis / review.spentMillis * 100 : 0}%` }} /></div></div>) : <p className="todo-empty">还没有投入记录。</p>}</div></section>
      {review.footprints.length > 0 && <section className="todo-footprints"><h2>走过的足迹</h2>{review.footprints.map(f => <article key={`${f.todoId}:${f.title}`}><AppIcon name="success" size={20} /><h3>{f.title}</h3><p>{f.detail}</p><Button size="sm" variant="quiet" onClick={() => onDetail(f.todoId)}>看看这段历程 <AppIcon name="arrow-right" /></Button></article>)}</section>}
    </aside></div>
  </>
}
