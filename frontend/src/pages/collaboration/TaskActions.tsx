import { useState } from 'react'
import { archiveCollaborationTask, deleteCollaborationTask, collaborationError } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'

/** Shared task-level actions; lifecycle changes include owned issues, never project files. */
export default function TaskActions({ id, title, archived, busy, compact = false, onChanged }: {
  id: string; title: string; archived: boolean; busy: boolean; compact?: boolean; onChanged: (deleted: boolean) => Promise<void>
}) {
  const [saving, setSaving] = useState(false), [error, setError] = useState('')
  const act = async (remove: boolean) => {
    if (saving || busy) return
    if (remove && !window.confirm('删除会话「' + title + '」及其工作条目、关联议题与记录？项目资料、项目文件和独立编码副本会保留，此操作无法撤销。')) return
    setSaving(true); setError('')
    try {
      if (remove) await deleteCollaborationTask(id)
      else await archiveCollaborationTask(id, !archived)
      await onChanged(remove)
    } catch (e) { setError(collaborationError(e)) }
    finally { setSaving(false) }
  }
  return <div className={'collab-task-actions' + (compact ? ' is-compact' : '')}>
    <div>
      <button type="button" className="ui-btn ui-btn--quiet" aria-label={archived ? '恢复会话' : '归档会话'} disabled={saving || busy} onClick={() => void act(false)} title={busy ? '先停止会话与关联议题后操作' : archived ? '恢复会话' : '归档会话'}>
        <AppIcon name={archived ? 'unarchive' : 'archive'} /><span className={compact ? 'sr-only' : undefined}>{archived ? '恢复会话' : '归档会话'}</span>
      </button>
      <button type="button" className="ui-btn ui-btn--quiet" aria-label="删除会话" disabled={saving || busy} onClick={() => void act(true)} title={busy ? '先停止会话与关联议题后操作' : '删除会话'}>
        <AppIcon name="trash" /><span className={compact ? 'sr-only' : undefined}>删除会话</span>
      </button>
    </div>
    {error && <p role="alert">{error}</p>}
  </div>
}
