import { AppIcon } from './AppIcon'
import { useRef, useState, type FormEvent } from 'react'
import { loginUrlOf } from '../api/client'
import { renameSnapshot, webError } from '../api/webSnapshots'
import { WEB_BUTTON, WEB_FIELD, WEB_PRIMARY } from './BrowserWorkspace'

export default function SnapshotRename({ id, title, disabled, onRenamed, onError }: {
  id: string; title: string; disabled: boolean; onRenamed: (title: string) => void; onError: (error: unknown) => void
}) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(title)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const working = useRef(false)
  const cancel = () => { if (!working.current) { setEditing(false); setError(null) } }
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const next = name.trim()
    if (!next || next.length > 500 || working.current || disabled) return
    if (next === title) { cancel(); return }
    working.current = true; setBusy(true); setError(null)
    try {
      const result = await renameSnapshot(id, next)
      onRenamed(result.title); setEditing(false)
    } catch (e) {
      if (loginUrlOf(e)) onError(e)
      else setError(webError(e))
    } finally { working.current = false; setBusy(false) }
  }
  if (!editing) return <button type="button" className={WEB_BUTTON} disabled={disabled} onClick={() => { setName(title); setError(null); setEditing(true) }}>
    <AppIcon name="edit" width="15" height="15" />改名
  </button>
  return <form aria-label="修改快照名称" onSubmit={event => void submit(event)} className="w-full space-y-3 rounded-lg border border-accent/30 bg-accent/5 p-4">
    <label className="block text-sm font-medium text-fg">快照名称<input autoFocus className={`${WEB_FIELD} mt-2`} value={name} maxLength={500} disabled={busy || disabled} onChange={event => setName(event.target.value)} onKeyDown={event => { if (event.key === 'Escape') { event.preventDefault(); cancel() } }} /></label>
    {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
    <div className="flex items-center justify-between gap-3"><span className="text-xs tabular-nums text-faint">{name.length} / 500</span><div className="flex gap-2"><button type="button" className={WEB_BUTTON} disabled={busy} onClick={cancel}>取消</button><button type="submit" className={WEB_PRIMARY} disabled={busy || disabled || !name.trim()}>{busy ? '保存中…' : '保存名称'}</button></div></div>
  </form>
}
