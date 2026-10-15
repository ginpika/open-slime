import type { CodingEvent } from '../../api/coding'
import { codingTimeline, eventData, toolPresentation } from './timeline'

const VALIDATION: Record<string, string> = { NOT_RUN: '尚未验证', PASSED: '验证通过', FAILED: '验证失败', BLOCKED: '验证受阻', STALE: '验证版本已变化' }

export type TranscriptView = 'normal' | 'thinking' | 'verbose'
export default function CodingTimeline({ events, working, attempt, directory, view = 'thinking' }: { events: CodingEvent[]; working: boolean; attempt: number; directory: string; view?: TranscriptView }) {
  const items = codingTimeline(events)
  return <div className="coding-tool-timeline">{items.map(item => {
    if (item.kind === 'reasoning') {
      if (view === 'normal') return null
      const live = working && item.attempt === attempt && !item.finished
      return <details key={item.id} className="coding-reasoning-event" open><summary><span>{live ? '正在思考' : '思考过程'}</span><small>第 {item.attempt} 次 · {item.round + 1} 轮</small></summary><pre className="scrollbar-slim">{item.text}</pre></details>
    }
    if (item.kind === 'tool') {
      const presentation = toolPresentation(item.tool, item.args)
      const running = item.status === 'RUNNING' && working && item.attempt === attempt
      const status = running ? '执行中' : item.status === 'DONE' ? '完成 ✓' : item.status === 'FAILED' ? '失败 !' : item.status === 'DENIED' ? '拒绝执行 !' : '未完成'
      return <details className={`coding-tool-event ${running ? 'is-running' : ''}`} key={item.id} open={view === 'verbose' ? true : undefined}><summary><span>{presentation.title}</span><small>{status}</small>{presentation.detail && <span className="coding-tool-detail">{presentation.detail}</span>}</summary>{['read_file', 'list_files', 'search_text'].includes(item.tool) && <p className="coding-tool-location">项目位置：{directory}（任务副本）</p>}<pre className="scrollbar-slim">{JSON.stringify(item.args, null, 2)}{item.result ? `\n\n${item.result}` : '\n等待工具结果…'}</pre></details>
    }
    const event = item.event, row = eventData(event)
    if (event.type === 'model.retry') return <div className="coding-output-recovery" key={item.id} role="status">模型请求失败，将自动重试（{Number(row.retry)}/{Number(row.maximum)}）。工具不会重复执行。{view === 'verbose' && <pre className="scrollbar-slim">{String(row.error || '')}</pre>}</div>
    if (event.type === 'plan.approved') return <p className="coding-budget-note" key={item.id}>已由你确认本轮权限：{String(row.mode)}，继续当前会话。</p>
    if (event.type === 'response.truncated') return <div className="coding-output-recovery" key={item.id} role="status">{row.automatic ? `单次输出达到模型上限，已自动续接（第 ${Number(row.continuation)} 次）。` : '连续输出截断，已保存检查点。'} 本轮工具调用未执行。</div>
    if (event.type === 'input.received') return <div className="coding-user-message" key={item.id}><span>补充指令</span><p>{String(row.text)}</p></div>
    return <details className="coding-tool-event" key={item.id}><summary><span>{event.type === 'context.compacted' ? '已整理上下文' : '项目验证'}</span><small>{event.type === 'validation.finished' ? VALIDATION[String(row.status)] : ''}</small></summary><pre className="scrollbar-slim">{String(row.result || row.error || JSON.stringify(row, null, 2))}</pre></details>
  })}</div>
}
