import { useState } from 'react'
import type { MaWorkflow, MaWorkflowEvent, MaWorkflowTodo } from '../../api/client'
import { collaborationError, type CollaborationIssue } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'
import FloatWindow from '../../components/FloatWindow'
import { isOpenTask, TASK_LABELS } from './model'

export function IssueCard({ issue, members, running, onOpen }: { issue: CollaborationIssue; members: string[]; running: boolean; onOpen: () => void }) {
  return <button className="collab-issue-card" onClick={onOpen} aria-label={`进入议题：${issue.title}`}><span className="collab-issue-label"><AppIcon name="branch" />议题<span>{issue.status === 'resolved' ? '已形成结论' : running ? '正在讨论' : '待继续'}</span></span><strong>{issue.title}</strong><span className="collab-issue-members">{members.join('、') || '参与成员'} · 用户可加入</span>{issue.conclusion && <span className="collab-issue-conclusion">{issue.conclusion.slice(0, 240)}{issue.conclusion.length > 240 ? '…' : ''}</span>}<span className="collab-issue-enter">{issue.status === 'resolved' ? '查看结论与过程' : '进入讨论'}<AppIcon name="arrow-right" /></span></button>
}
export function GraphActivity({ data, issues, onEvent, onIssue }: { data: MaWorkflow | null; issues: CollaborationIssue[]; onEvent: (event: MaWorkflowEvent) => void; onIssue: (issue: CollaborationIssue) => void }) {
  if (!issues.length && !data?.events.length) return null
  return <div className="collab-graph-activity scrollbar-slim" aria-label="协作记录与议题">{issues.map(issue => <button key={issue.id} onClick={() => onIssue(issue)}><AppIcon name="branch" /><span>{issue.title}</span><small>{issue.status === 'resolved' ? '已有结论' : '议题'}</small></button>)}{data?.events.slice(0, 4).map(event => <button key={event.id} onClick={() => onEvent(event)}><AppIcon name="flow" /><span>{event.text}</span></button>)}</div>
}
export function TodoPanel({ data, loading, error, onRetry, onSelect, onDiscuss, readOnly = false }: { data: MaWorkflow | null; loading: boolean; error: string | null; onRetry: () => void; onSelect: (id: string) => void; onDiscuss: (todo: MaWorkflowTodo) => void; readOnly?: boolean }) {
  const [history, setHistory] = useState(false)
  const tasks = data?.todos || [], active = tasks.filter(isOpenTask), ended = tasks.filter(t => !isOpenTask(t))
  const rows = (list: MaWorkflowTodo[]) => list.map(t => (
    <div className="collab-todo-row" key={t.id}>
      <button className="collab-todo-main" onClick={() => onSelect(t.id)}>
        <AppIcon name={t.status === 'done' ? 'check' : t.status === 'blocked' ? 'alert' : t.status === 'doing' ? 'play' : 'square'} />
        <span>
          <strong>{t.title}</strong>
          <small>
            <span>{t.ownerName || '未分配'}</span>
            {t.status === 'blocked'
              ? <span className="collab-todo-blocked-badge">受阻</span>
              : <span>· {TASK_LABELS[t.status] || t.status}</span>}
          </small>
        </span>
      </button>
      {!readOnly && <button className="collab-todo-discuss" aria-label={`讨论待办：${t.title}`} title="讨论这项待办" onClick={() => onDiscuss(t)}><AppIcon name="chat" /></button>}
    </div>
  ))
  return <aside className="collab-todos" aria-label="任务待办"><div className="collab-todos-heading"><h2>本次工作 <span>{active.length}</span></h2><p>主控派发的具体工作与检查状态</p></div><div className="collab-todos-body scrollbar-slim">{loading && <p role="status">正在载入待办…</p>}{error && <div role="alert"><p>待办更新失败，保留已有内容。</p><button className="ui-btn ui-btn--outline" onClick={onRetry}>重试待办</button></div>}{!loading && !error && !active.length && <div className="collab-todo-empty"><AppIcon name="todo" size={24} /><p>{tasks.length ? '当前没有未结束的待办' : '目标明确后，主控派发的工作会出现在这里。'}</p></div>}{rows(active)}{!!ended.length && <><button className="collab-ended-toggle" onClick={() => setHistory(v => !v)} aria-expanded={history}><AppIcon name={history ? 'chevron-down' : 'chevron-right'} />已结束与放弃 · {ended.length}</button>{history && rows(ended)}<p className="collab-secondary">打开已结束的工作，核对成果、检查意见与执行记录。</p></>}</div></aside>
}
export function ConclusionDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (text: string) => Promise<void> }) {
  const [text, setText] = useState(''), [saving, setSaving] = useState(false), [error, setError] = useState('')
  return <FloatWindow title="将议题结论带回主会话" onClose={() => { if (!saving) onClose() }} width={600}><form className="collab-form" onSubmit={async e => { e.preventDefault(); if (saving || !text.trim()) return; setSaving(true); setError(''); try { await onSubmit(text.trim()); onClose() } catch (err) { setError(collaborationError(err)) } finally { setSaving(false) } }}><label>结论与后续行动<textarea autoFocus required rows={8} maxLength={8000} value={text} disabled={saving} onChange={e => setText(e.target.value)} placeholder="达成了什么决定？依据是什么？哪些待办需要继续推进？" /></label><p>结论会保留在议题卡片中，并写入主会话的消息记录。</p>{error && <p role="alert">{error}</p>}<div className="collab-form-actions"><button type="button" className="ui-btn ui-btn--quiet" onClick={onClose} disabled={saving}>取消</button><button className="ui-btn ui-btn--primary" disabled={saving || !text.trim()}>{saving ? '正在提交…' : '提交结论'}</button></div></form></FloatWindow>
}
