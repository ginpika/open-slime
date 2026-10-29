import type { TodoOverview } from '../../api/todo'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'
import { clockTime, duration, focusMillis, timer } from './format'
import { useServerClock } from './useServerClock'

export default function DailyEffort({ overview, receivedAt, project, busy, canBackfill, onDetail, onPause, onBackfill, onTimeline }: {
  overview: TodoOverview; receivedAt: number; project: string; busy: boolean; canBackfill: boolean
  onDetail: (id: string) => void; onPause: () => void; onBackfill: () => void; onTimeline: () => void
}) {
  const active = overview.active
  const now = useServerClock(overview.now, receivedAt, active ? 1000 : 0)
  const daily = overview.todayFocus
  const dailyTotal = daily.reduce((sum, f) => sum + focusMillis(f, now, overview.dayStart, overview.dayEnd), 0)
  const allocation = new Map<string, { title: string; millis: number }>()
  daily.forEach(f => { const value = allocation.get(f.todoId) ?? { title: f.title, millis: 0 }; value.millis += focusMillis(f, now, overview.dayStart, overview.dayEnd); allocation.set(f.todoId, value) })
  return <>
    <section className="todo-current-focus"><p className="todo-secondary"><AppIcon name="clock" />{active ? `当前专注${active.projectName ? ` · ${active.projectName}` : ''}` : '留一点专注给自己'}</p><h2>{active ? <button type="button" onClick={() => onDetail(active.todoId)}>{active.title}</button> : '准备好，再开始'}</h2><div className="todo-timer" aria-label="当前投入时长">{timer(active ? now - active.startedAt : 0)}</div>{active ? <Button disabled={busy} onClick={onPause}><AppIcon name="pause" />暂停，记下这一段</Button> : <p className="todo-secondary">在事项旁点击「开始」，记录实际投入。</p>}{active && now - active.startedAt > 8 * 3600000 && <p className="todo-long-running">这段计时已超过 8 小时，请核对是否包含休息；暂停后可修正。</p>}{project && active && active.projectId !== project && <small>当前计时属于其他标签；筛选不影响它继续记录。</small>}</section>
    <section className="todo-daily-effort"><header className="todo-section-heading"><h2>精力去向</h2><Button size="sm" variant="quiet" disabled={busy || !canBackfill} onClick={onBackfill}>补记</Button></header>
      <div className="todo-daily-summary">
        <div className="todo-daily-metric"><p className="todo-secondary">{project ? '今天已记录的投入（当前筛选）' : '今天已记录的投入'}</p><p className="todo-daily-total">{duration(dailyTotal)}</p></div>
        <Button size="sm" variant="quiet" onClick={onTimeline}>查看全天时间表 <AppIcon name="arrow-right" /></Button>
      </div>
      <div className="todo-daily-allocations">{[...allocation].map(([id, value]) => <div key={id} className="todo-allocation"><div><button type="button" onClick={() => onDetail(id)}>{value.title}</button><span>{duration(value.millis)}</span></div><div className="todo-allocation-track"><span style={{ width: `${dailyTotal ? value.millis / dailyTotal * 100 : 0}%` }} /></div></div>)}</div>
      <ol className="todo-focus-log">{daily.map(f => <li key={f.id}><small>{clockTime(Math.max(f.startedAt, overview.dayStart))} — {f.endedAt === null ? '计时中' : clockTime(Math.min(f.endedAt, overview.dayEnd))}{f.startedAt < overview.dayStart ? ' · 跨天续接' : ''}</small><button type="button" onClick={() => onDetail(f.todoId)}>{f.title}</button><p>{f.note || duration(focusMillis(f, now, overview.dayStart, overview.dayEnd))}</p></li>)}</ol>{!daily.length && <p className="todo-empty">还没有记录。做过的投入，也可以事后补记。</p>}
    </section>
  </>
}
