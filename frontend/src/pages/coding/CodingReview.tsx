import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { CodingEvent } from '../../api/coding'
import { AppIcon } from '../../components/AppIcon'
import { eventData } from './timeline'

export default function CodingReview({ events, attempt, hash, working }: { events: CodingEvent[]; attempt: number; hash: string | null; working: boolean }) {
  const event = [...events].reverse().find(event => event.type.startsWith('review.') && Number(eventData(event).attempt) === attempt)
  if (!event) return null
  const row = eventData(event), pending = event.type === 'review.started' && working
  const stale = row.status === 'STALE' || !!hash && row.hash !== hash
  const done = event.type === 'review.finished' && row.status === 'DONE' && !stale
  return <details className="coding-review-report" open><summary><AppIcon name="permissions" /><strong>自动代码审查</strong><span role="status">{pending ? 'Reviewer 正在检查变更…' : stale ? '代码版本已变化，报告仅供参考' : done ? '审查完成' : '审查未完成'}</span></summary>
    {row.answer ? <div className="coding-markdown"><Markdown remarkPlugins={[remarkGfm]}>{String(row.answer)}</Markdown></div> : null}
    {row.error ? <p role="alert">{String(row.error)}</p> : null}
    {!pending && <p>审查报告供你判断；应用变更仍需确认。{!done && '请核对变更并重新审查。'}</p>}
  </details>
}
