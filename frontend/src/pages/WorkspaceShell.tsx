import { useCallback, useEffect, useRef, useState } from 'react'
import { type TerminalTarget } from '../api/client'
import { connectWorkspaceTerminal, createWorkspaceTerminal, deleteWorkspaceTerminal, listWorkspaceTerminals, terminalError, type TerminalView } from '../api/terminal'
import { AppIcon } from '../components/AppIcon'
import FloatWindow from '../components/FloatWindow'
import TerminalSession from '../components/TerminalSession'
import { Select } from '../components/Select'
import { Button } from '../components/ui'

function Shell({ workspace }: { workspace: TerminalTarget }) {
  const [terminals, setTerminals] = useState<TerminalView[]>([]), [active, setActive] = useState('')
  const [busy, setBusy] = useState(true), [error, setError] = useState(''), [revision, setRevision] = useState(0)
  const alive = useRef(false)
  useEffect(() => {
    let stopped = false
    alive.current = true
    void (async () => {
      try {
        const items = await listWorkspaceTerminals(workspace.id)
        if (stopped) return
        const running = items.filter(item => item.running)
        // Do not create a second session when reopening the floating window.
        if (!running.length && workspace.enabled) running.push(await createWorkspaceTerminal(workspace.id))
        if (stopped) return
        setTerminals(running); setActive(previous => running.some(item => item.id === previous) ? previous : running[0]?.id || '')
        await Promise.all(items.filter(item => !item.running).map(item => deleteWorkspaceTerminal(workspace.id, item.id)))
      } catch (e) { if (!stopped) setError(terminalError(e)) }
      finally { if (!stopped) setBusy(false) }
    })()
    return () => { stopped = true; alive.current = false }
  }, [workspace.id, workspace.enabled, revision])
  const exited = useCallback((id: string, message?: string) => {
    setTerminals(previous => previous.filter(item => item.id !== id)); setActive(previous => previous === id ? '' : previous)
    setError(message || 'Shell 已退出，可新建会话继续。')
  }, [])
  const create = async () => {
    if (busy) return
    setBusy(true); setError('')
    try { const terminal = await createWorkspaceTerminal(workspace.id); if (alive.current) { setTerminals(items => [...items, terminal]); setActive(terminal.id) } }
    catch (e) { if (alive.current) setError(terminalError(e)) }
    finally { if (alive.current) setBusy(false) }
  }
  const remove = async () => {
    if (busy || !active) return
    setBusy(true); setError('')
    try {
      await deleteWorkspaceTerminal(workspace.id, active)
      if (alive.current) { const remaining = terminals.filter(item => item.id !== active); setTerminals(remaining); setActive(remaining[0]?.id || '') }
    } catch (e) { if (alive.current) setError(terminalError(e)) }
    finally { if (alive.current) setBusy(false) }
  }
  const terminal = terminals.find(item => item.id === active)
  return <>
    <div className="my-3 flex flex-wrap items-center gap-2">
      <div className="min-w-28 flex-1"><Select label="Shell 会话" value={active} disabled={busy} onChange={setActive} options={[{ value: '', label: '选择会话' }, ...terminals.map(item => ({ value: item.id, label: item.title }))]} /></div>
      <Button variant="outline" disabled={busy || !workspace.enabled} onClick={() => void create()}><AppIcon name="terminal" />新建 Shell</Button>
      <Button variant="quiet" disabled={busy || !terminal} onClick={() => void remove()}>结束会话</Button>
    </div>
    {error && <div role="alert" className="mb-3 flex flex-wrap items-center gap-2 text-sm text-danger">{error}<Button size="sm" variant="quiet" disabled={busy} onClick={() => { setBusy(true); setError(''); setRevision(value => value + 1) }}>重新加载</Button></div>}
    <div className="h-[min(52dvh,32rem)] min-h-40 overflow-hidden rounded-md border border-line">
      {terminal ? <TerminalSession scope={workspace.id} terminal={terminal} active onExit={exited} connect={connectWorkspaceTerminal} /> : <p role="status" className="p-4 text-sm text-muted">{busy ? '正在打开 Shell…' : workspace.enabled ? '新建 Shell，即可在工作空间中输入命令。' : '工作空间未启用，请先启用。'}</p>}
    </div>
    <p className="mt-3 text-xs leading-5 text-muted">关闭窗口会保留会话，离线 30 分钟后自动回收；“结束会话”会关闭 Shell。交互命令不计入“最近执行”。</p>
  </>
}

export default function WorkspaceShell({ targets, initialTarget, onClose }: { targets: TerminalTarget[]; initialTarget: string; onClose: () => void }) {
  const [targetId, setTargetId] = useState(initialTarget)
  const workspace = targets.find(item => item.id === targetId)
  return <FloatWindow title={<span className="inline-flex items-center gap-2"><AppIcon name="terminal" />Shell{workspace ? ` · ${workspace.name}` : ''}</span>} width={960} onClose={onClose}>
    <Select label="Shell 工作空间" value={targetId} onChange={setTargetId} options={[{ value: '', label: '选择工作空间' }, ...targets.map(item => ({ value: item.id, label: `${item.name}${item.enabled ? '' : '（未启用）'}`, disabled: !item.enabled }))]} />
    {workspace ? <Shell key={`${workspace.id}:${workspace.enabled}`} workspace={workspace} /> : <p className="mt-4 text-sm text-muted">选择已启用的工作空间，即可打开 Shell。</p>}
  </FloatWindow>
}
