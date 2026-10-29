import { useState, type CSSProperties } from 'react'
import type { PersonalFocus, TodoDayWork } from '../../api/todo'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'
import { duration } from './format'
import { dayTimeline, type WorkBar } from './workTime'
import { useServerClock } from './useServerClock'

const COLORS = ['#4285c5', '#278c7c', '#a475bc', '#b87b32', '#bc627d', '#6778b5']

export default function TimeAllocation({ day, receivedAt, filtered, busy, onDetail, onEdit }: {
  day: TodoDayWork; receivedAt: number; filtered: boolean; busy: boolean
  onDetail: (id: string) => void; onEdit: (focus: PersonalFocus) => void
}) {
  const [zoom, setZoom] = useState(1)
  const [selected, setSelected] = useState<string | null>(null)
  const now = useServerClock(day.now, receivedAt, day.focus.some(f => f.endedAt === null) ? 1000 : 60000)
  const model = dayTimeline(day, now)
  const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: day.zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
  const time = (at: number) => at === day.rangeEnd ? '24:00' : timeFormat.format(at)
  const selectedBar = model.bars.find(bar => bar.focus.id === selected)
  const colors = new Map(model.rows.map((row, index) => [row.id, COLORS[index % COLORS.length]]))
  const description = (bar: WorkBar) => `第 ${bar.order} 段 · ${bar.focus.title} · ${time(bar.start)}–${time(bar.end)} · ${duration(bar.end - bar.start)}${bar.focus.endedAt === null ? ' · 计时中' : ''}`
  const isToday = now >= day.rangeStart && now < day.rangeEnd
  const ticks = [...day.hours.map(h => ({ at: h.startedAt, label: time(h.startedAt) })), { at: day.rangeEnd, label: '24:00' }]
  const grid = () => <>
    {ticks.slice(0, -1).map(tick => <i key={tick.at} className="todo-timeline-gridline" style={{ left: `${model.at(tick.at)}%` }} />)}
    {isToday && <><span className="todo-timeline-future" style={{ left: `${model.nowPercent}%`, width: `${100 - model.nowPercent}%` }} /><span className="todo-timeline-now" style={{ left: `${model.nowPercent}%` }} /></>}
  </>
  const barButton = (bar: WorkBar) => <button key={bar.focus.id} type="button" className={`todo-timeline-bar${bar.focus.endedAt === null ? ' todo-timeline-bar--active' : ''}`} aria-label={description(bar)} aria-pressed={selected === bar.focus.id} title={description(bar)} style={{ left: `${bar.left}%`, width: `${bar.width}%`, '--todo-bar-color': colors.get(bar.focus.todoId) } as CSSProperties} onClick={() => setSelected(bar.focus.id)}>
    {bar.width * zoom > 2.3 && <span>{bar.order}{bar.width * zoom > 12 ? ` · ${time(bar.start)}–${time(bar.end)}` : ''}</span>}
    {bar.width * zoom <= 2.3 && <span className="todo-timeline-bar-number">{bar.order}</span>}
  </button>

  return <section className="todo-time-allocation">
    <header className="todo-section-heading"><div><h2>一天的时间分配</h2><p className="todo-secondary">{day.day} · {day.zone} · 00:00–24:00</p></div><div className="todo-timeline-zoom" role="group" aria-label="时间轴缩放">{[1, 2, 4].map(value => <button key={value} type="button" aria-pressed={zoom === value} onClick={() => setZoom(value)}>{value === 1 ? '全天' : `${value}×`}</button>)}</div></header>
    <div className="todo-time-summary"><p><strong>{duration(model.total)}</strong><span>{filtered ? '此项目已记录投入' : '已记录投入'}</span></p><p><strong>{model.rows.length}</strong><span>个任务</span></p><p><strong>{model.bars.length}</strong><span>段投入</span></p><p><strong>{duration(model.bars.length ? model.total / model.bars.length : 0)}</strong><span>平均每段</span></p></div>
    <div className="todo-timeline-caption"><p>任务按首次投入排序，长条编号表示投入顺序。同一任务再次开始，会保留为另一段。</p><p className="todo-secondary">已记录占已过去时间 {model.elapsed ? (model.total / model.elapsed * 100).toFixed(1) : '0'}%。{filtered ? '当前仅显示所选项目，空白可能包含其他工作。' : '空白表示没有记录，不推断休息。'}{day.hours.length !== 24 ? ` 此日实际为 ${day.hours.length} 小时，重复小时保留时区偏移。` : ''}</p></div>
    <div className="todo-timeline-scroll" role="region" aria-label="全天任务时间分布，可横向滚动" tabIndex={0} style={{ '--todo-timeline-axis-width': `${720 * zoom}px` } as CSSProperties}>
      <table className="todo-timeline-table"><caption className="sr-only">{day.day} 任务时间分布，上方为时间，左侧为任务</caption><colgroup><col className="todo-timeline-task-col" /><col /></colgroup><thead><tr><th scope="col" className="todo-timeline-corner">任务 / 当日投入</th><th scope="col" className="todo-timeline-axis"><span className="sr-only">时间轴 00:00 至 24:00</span>{ticks.map((tick, index) => <span key={tick.at} className={`todo-timeline-tick${index === 0 ? ' todo-timeline-tick--first' : index === ticks.length - 1 ? ' todo-timeline-tick--last' : ''}`} style={{ left: `${model.at(tick.at)}%` }}>{(zoom > 1 || index % 2 === 0 || index === ticks.length - 1) ? `${tick.label}${day.hours.length !== 24 && index < day.hours.length ? ` ${day.hours[index].label.match(/\((.*)\)/)?.[1] ?? ''}` : ''}` : ''}</span>)}</th></tr></thead><tbody>
        {model.rows.map(row => <tr key={row.id} data-todo-id={row.id}><th scope="row"><button type="button" onClick={() => onDetail(row.id)}>{row.title}</button><small>{row.projectName || '未分类'} · {duration(row.total)} · {row.bars.length} 段</small>{row.completedAt !== null && <small className="todo-timeline-completed-text"><AppIcon name="check" size={12} />{time(row.completedAt)} 完成</small>}</th><td><div className="todo-timeline-track">{grid()}{row.bars.map(bar => barButton(bar))}{row.completedAt !== null && <button type="button" className="todo-timeline-completed" aria-label={`${row.title}，${time(row.completedAt)} 完成`} title={`${time(row.completedAt)} 完成`} style={{ left: `${model.at(row.completedAt)}%` }} onClick={() => onDetail(row.id)}><AppIcon name="check" size={12} /></button>}</div></td></tr>)}
      </tbody></table>
    </div>
    <div className="todo-timeline-legend"><span><i className="todo-timeline-legend-bar" />一段实际投入</span><span><AppIcon name="check" size={12} />完成时间</span>{isToday && <span><i className="todo-timeline-legend-now" />现在；浅色区域尚未发生</span>}<span>点击长条查看时段，放大可看更细的投入。</span></div>
    {!model.rows.length && <p className="todo-empty">这一天还没有{filtered ? '此项目的' : ''}投入或完成记录。开始计时，或在事项详情里补记，长条就会出现在对应时段。</p>}
    {selectedBar && <article className="todo-timeline-selection" aria-label="选中投入记录"><div><h3>{selectedBar.focus.title}</h3><p>第 {selectedBar.order} 段 · {time(selectedBar.start)}–{time(selectedBar.end)} · {duration(selectedBar.end - selectedBar.start)}{selectedBar.focus.endedAt === null ? ' · 计时中' : ''}</p>{selectedBar.focus.note && <p>{selectedBar.focus.note}</p>}{selectedBar.focus.startedAt < day.rangeStart && <small>从前一天续接，图中只计入这一天的部分。</small>}</div><div className="todo-actions"><Button size="sm" onClick={() => onDetail(selectedBar.focus.todoId)}>查看事项</Button><Button size="sm" disabled={busy || selectedBar.focus.endedAt === null} onClick={() => onEdit(selectedBar.focus)}>修正投入</Button></div></article>}
  </section>
}
