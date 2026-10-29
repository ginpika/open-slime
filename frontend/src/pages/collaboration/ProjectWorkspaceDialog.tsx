import { useEffect, useState } from 'react'
import { listWorkspaceFiles, type WorkspaceDirView } from '../../api/client'
import { bindCollaborationWorkspace, collaborationError, collaborationWorkspaces, type ProjectWorkspaceBinding } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'
import FloatWindow from '../../components/FloatWindow'
import { Select } from '../../components/formKit'

export default function ProjectWorkspaceDialog({ projectId, projectName, binding, onSaved, onClose }: {
  projectId: string; projectName: string; binding?: ProjectWorkspaceBinding; onSaved: () => Promise<void>; onClose: () => void
}) {
  const [workspaces, setWorkspaces] = useState<Awaited<ReturnType<typeof collaborationWorkspaces>>>([])
  const [workspaceId, setWorkspace] = useState(binding?.workspaceId || '')
  const [directory, setDirectory] = useState(binding?.directory || '.')
  const [view, setView] = useState<WorkspaceDirView | null>(null)
  const [create, setCreate] = useState(false), [name, setName] = useState(projectName)
  const [loading, setLoading] = useState(true), [saving, setSaving] = useState(false), [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    let stale = false
    setLoading(true); setError('')
    void collaborationWorkspaces().then(rows => { if (!stale) setWorkspaces(rows) }).catch(e => { if (!stale) setError(collaborationError(e)) }).finally(() => { if (!stale) setLoading(false) })
    return () => { stale = true }
  }, [refresh])
  useEffect(() => {
    let stale = false
    setView(null)
    if (!workspaceId) return
    void listWorkspaceFiles(workspaceId, directory).then(rows => { if (!stale) setView(rows) }).catch(e => { if (!stale) setError(collaborationError(e)) })
    return () => { stale = true }
  }, [workspaceId, directory, refresh])
  const pick = (path: string) => { setDirectory(path); setError('') }
  const parent = directory.includes('/') ? directory.slice(0, directory.lastIndexOf('/')) : '.'
  return <FloatWindow title="设置项目工作目录" width={620} onClose={() => { if (!saving) onClose() }}><form className="collab-form" onSubmit={async e => {
    e.preventDefault(); if (saving || !workspaceId || !view || (create && !name.trim())) return
    setSaving(true); setError('')
    try { await bindCollaborationWorkspace(projectId, { workspaceId, directory, create, name: create ? name.trim() : undefined }); await onSaved(); onClose() }
    catch (err) { setError(collaborationError(err)) } finally { setSaving(false) }
  }}>
    <p>为「{projectName}」明确选择代码位置。主控、执行者和检查者共用此目录；未设置时只进行讨论。</p>
    <label>工作空间<Select label="项目工作空间" value={workspaceId} disabled={saving || loading} onChange={id => { setWorkspace(id); pick('.') }} options={[{ value: '', label: '请选择工作空间' }, ...workspaces.map(w => ({ value: w.id, label: `${w.name} · ${w.kind.toUpperCase()}`, disabled: !w.enabled }))]} /></label>
    {loading && <p role="status">正在载入工作空间…</p>}
    {!loading && !workspaces.some(w => w.enabled) && <p>先<a href="#/workspace">添加并启用工作空间</a>。<button type="button" className="ui-btn ui-btn--quiet" onClick={() => setRefresh(v => v + 1)}>重新加载</button></p>}
    {workspaceId && <><label>{create ? '新目录的上级位置' : '项目目录'}<input aria-label="项目相对目录" value={directory} disabled={saving} onChange={e => pick(e.target.value)} placeholder="相对工作空间起点，如 projects/my-app" /></label>
      <div className="collab-workspace-picker" aria-label="选择项目目录"><button type="button" className="ui-btn ui-btn--quiet" disabled={saving || directory === '.'} onClick={() => pick(parent)}><AppIcon name="arrow-left" />上一级</button><button type="button" className="ui-btn ui-btn--quiet" disabled={saving} onClick={() => setRefresh(v => v + 1)}>刷新目录</button>
        {!view && !error && <p role="status">正在读取目录…</p>}
        {view?.entries.filter(entry => entry.dir && !entry.link).map(entry => <button type="button" disabled={saving} className="collab-workspace-directory" key={entry.name} onClick={() => pick(directory === '.' ? entry.name : `${directory}/${entry.name}`)}><AppIcon name="folder" />{entry.name}<AppIcon name="chevron-right" /></button>)}
        {view && !view.entries.some(e => e.dir && !e.link) && <p>当前目录没有可进入的子目录。</p>}
      </div>
      <label className="collab-workspace-create"><input type="checkbox" checked={create} disabled={saving} onChange={e => setCreate(e.target.checked)} />在所选位置新建独立目录</label>
      {create && <label>新目录名称<input value={name} maxLength={100} disabled={saving} required onChange={e => setName(e.target.value)} /></label>}
      <p className="collab-description">将使用：{workspaces.find(w => w.id === workspaceId)?.name} / {directory}{create ? ` / ${name || '新目录'}` : ''}</p>
    </>}
    {error && <p role="alert">{error}</p>}
    <div className="collab-form-actions"><button type="button" className="ui-btn ui-btn--quiet" disabled={saving} onClick={onClose}>取消</button><button type="submit" className="ui-btn ui-btn--primary" disabled={saving || !workspaceId || !view || (create && !name.trim())}>{saving ? '正在设置…' : create ? '创建并绑定目录' : '绑定所选目录'}</button></div>
  </form></FloatWindow>
}
