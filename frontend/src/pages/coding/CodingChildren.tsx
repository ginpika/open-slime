import { useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { codingError, resumeCodingChild, stopCodingChild, type CodingChild, type CodingEvent } from '../../api/coding'
import { Button } from '../../components/ui'
import CodingTimeline, { type TranscriptView } from './CodingTimeline'
import { eventData } from './timeline'
import { currentAttemptText } from './state'

const STATUS: Record<string, string> = { QUEUED: '排队中', RUNNING: '工作中', DONE: '已完成', STALE: '版本已变化', PARTIAL: '可继续', INTERRUPTED: '已中断', CANCELLED: '已停止', FAILED: '失败' }
const NAMES: Record<string, string> = { explore: 'Explore · 探索', planner: 'Planner · 规划', reviewer: 'Reviewer · 审阅' }
export default function CodingChildren({ runId, executions, events, directory, parentWorking, view, error, refresh }: { runId: string; executions: CodingChild[]; events: CodingEvent[]; directory: string; parentWorking: boolean; view: TranscriptView; error: string; refresh: () => void }) {
  const [busy, setBusy] = useState(''), [actionError, setActionError] = useState(''), [input, setInput] = useState<Record<string, string>>({})
  const act = async (id: string, work: () => Promise<unknown>) => { setBusy(id); setActionError(''); try { await work(); refresh() } catch (e) { setActionError(codingError(e)) } finally { setBusy('') } }
  if (!executions.length && !error) return null
  return <section className="coding-children" aria-label="子 Agent 执行"><div className="coding-child-heading"><strong>子 Agent</strong><small>{executions.filter(child => ['QUEUED', 'RUNNING'].includes(child.status)).length} 个在途 · {executions.length} 个任务</small></div>
    {(error || actionError) && <p role="alert" className="coding-inline-error">{error || actionError}</p>}
    {executions.map(child => {
      const working = ['QUEUED', 'RUNNING'].includes(child.status)
      const trace = events.filter(event => eventData(event).executionId === child.id)
      const liveText = currentAttemptText(trace, child.attempt, child.id)
      return <details className="coding-child" key={child.id}><summary><span><strong>{NAMES[child.role] || child.role}</strong><span className="coding-child-title">{child.title}</span></span><small className={working ? 'is-working' : ''}>{STATUS[child.status] || child.status}</small></summary>
        <div className="coding-child-body"><p className="coding-child-prompt">{child.prompt}</p><small>第 {child.attempt} 次 · {child.round + 1} 轮 · 配置 v{child.configVersion} · 只读</small>
          <CodingTimeline events={trace} working={working} attempt={child.attempt} directory={directory} view={view} />
          {child.answer && <div className="coding-markdown"><Markdown remarkPlugins={[remarkGfm]}>{child.answer}</Markdown></div>}
          {!child.answer && liveText && <div className="coding-agent-stream">{liveText}</div>}
          {child.error && <p className="coding-notice">{child.error}</p>}
          {working ? <Button size="sm" variant="quiet" disabled={!!busy} onClick={() => void act(child.id, () => stopCodingChild(runId, child.id))}>停止此子任务</Button> : <div className="coding-child-resume"><input aria-label={`继续 ${child.title}`} value={input[child.id] || ''} onChange={e => setInput(previous => ({ ...previous, [child.id]: e.target.value }))} placeholder="补充目标或继续核查…" /><Button size="sm" disabled={!!busy || !parentWorking} onClick={() => void act(child.id, () => resumeCodingChild(runId, child.id, input[child.id] || '继续原任务，重新核对当前版本。'))}>继续子任务</Button>{!parentWorking && <small>先继续主会话，再接续此子任务。</small>}</div>}
        </div></details>
    })}
  </section>
}
