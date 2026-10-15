import { AppIcon } from '../../components/AppIcon'
import { useEffect, useState } from 'react'
import { listWorkspaceFiles, type WorkspaceDirView } from '../../api/client'
import { codingError, openCodingProject, type CodingAgent, type CodingProject, type CodingRun, type CodingWorkspace } from '../../api/coding'
import { agentProfileHref } from '../../api/agentRuntime'
import { Button } from '../../components/ui'
import { Select } from '../../components/Select'
import CodingIcon from './CodingIcon'
import { Field, INPUT } from '../../components/formKit'

type Props = { projects: CodingProject[]; workspaces: CodingWorkspace[]; runs: CodingRun[]; onOpen: (project: CodingProject) => void; onReload: () => Promise<void>; initialWorkspace: string; initialDirectory: string; error: string; enabled: boolean; agent?: CodingAgent | null }
const parentOf = (path: string) => path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '.'

export default function CodingStart({ projects, workspaces, runs, onOpen, onReload, initialWorkspace, initialDirectory, error: pageError, enabled, agent }: Props) {
  const [choice, setChoice] = useState<'create' | 'open' | null>(initialWorkspace ? 'open' : null)
  const [workspaceId, setWorkspaceId] = useState(initialWorkspace || workspaces.find(w => w.enabled)?.id || '')
  const [directory, setDirectory] = useState(initialDirectory)
  const [view, setView] = useState<WorkspaceDirView | null>(null)
  const [name, setName] = useState('')
  const [validation, setValidation] = useState('')
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  const workspace = workspaces.find(w => w.id === workspaceId)
  useEffect(() => {
    if (!choice || !workspaceId) { setView(null); setLoading(false); return }
    let stopped = false
    setLoading(true); setView(null); setError('')
    void listWorkspaceFiles(workspaceId, directory).then(result => { if (!stopped) setView(result) }).catch(e => { if (!stopped) setError(codingError(e)) }).finally(() => { if (!stopped) setLoading(false) })
    return () => { stopped = true }
  }, [choice, workspaceId, directory, refresh])
  const choose = (next: 'create' | 'open') => { setChoice(next); setError('') }
  const submit = async () => {
    if (busy) return
    setBusy(true); setError('')
    try { const project = await openCodingProject({ workspaceId, directory, name: name.trim() || undefined, create: choice === 'create', validationCommand: validation.trim() || undefined }); onOpen(project) }
    catch (e) { setError(codingError(e)) } finally { setBusy(false) }
  }
  return <div className="coding-start scrollbar-slim">
    <div className="coding-start-top"><span className="coding-brand"><span><AppIcon name="code" size={18} /></span> 编程</span><div className="coding-start-links"><a href={agentProfileHref(agent?.id)}>{agent?.name || '编码 Agent'} ↗</a><a href="#/workspace">管理工作空间 ↗</a></div></div>
    <main className="coding-start-main">
      <div className="coding-start-intro"><span className="coding-eyebrow">YOUR NEXT PROJECT</span><h1>从一个项目开始</h1><p>选择工作空间里的目录，和 Agent 一起把想法变成代码。</p></div>
      {(pageError || !enabled) && <div role="alert" className="coding-notice">{pageError || '编程模块未启用'}<Button size="sm" onClick={() => void onReload()}>重新加载</Button></div>}
      <div className="coding-start-actions">
        <button className={`coding-action-card ${choice === 'create' ? 'is-selected' : ''}`} disabled={!enabled || busy} onClick={() => choose('create')}><span className="coding-action-icon"><CodingIcon name="plus" /></span><span><strong>新建项目</strong><small>在工作空间中创建新的项目目录</small></span><span className="coding-action-arrow"><CodingIcon name="external" /></span></button>
        <button className={`coding-action-card ${choice === 'open' ? 'is-selected' : ''}`} disabled={!enabled || busy} onClick={() => choose('open')}><span className="coding-action-icon"><CodingIcon name="folder" /></span><span><strong>打开项目</strong><small>选择现有目录，继续你的工作</small></span><span className="coding-action-arrow"><CodingIcon name="external" /></span></button>
      </div>
      {choice && <section className="coding-picker" aria-label={choice === 'create' ? '新建项目' : '选择项目目录'}>
        <div className="coding-section-heading"><h2>{choice === 'create' ? '项目放在哪里？' : '选择项目目录'}</h2><Button size="sm" variant="quiet" disabled={busy} onClick={() => setChoice(null)}>收起</Button></div>
        {!workspaces.some(w => w.enabled) ? <div className="coding-picker-empty"><p>先添加并启用一个工作空间。</p><a className="ui-btn ui-btn--primary" href="#/workspace">前往工作空间</a></div> : <>
          <Field label="工作空间"><Select menuClassName="coding-select-menu" size="compact" label="工作空间" value={workspaceId} disabled={busy} onChange={value => { setWorkspaceId(value); setDirectory('.'); setName('') }} options={[{ value: '', label: '选择工作空间' }, ...workspaces.map(w => ({ value: w.id, label: `${w.name} · ${w.kind.toUpperCase()}${w.enabled ? '' : '（未启用）'}`, disabled: !w.enabled }))]} /></Field>
          <div className="coding-directory-head"><div className="coding-breadcrumb"><button disabled={busy} onClick={() => setDirectory('.')}>{workspace?.name || '工作空间'}</button>{directory !== '.' && directory.split('/').map((part, i, parts) => <span key={i}> / <button disabled={busy} onClick={() => setDirectory(parts.slice(0, i + 1).join('/'))}>{part}</button></span>)}</div><Button size="sm" variant="quiet" onClick={() => setRefresh(value => value + 1)} disabled={loading || busy}>刷新</Button></div>
          <div className="coding-directory-list scrollbar-slim" aria-busy={loading}>
            {directory !== '.' && <button disabled={busy} onClick={() => setDirectory(parentOf(directory))}><span>↰</span>上一级目录</button>}
            {loading ? <p role="status">正在读取目录…</p> : view?.entries.filter(entry => entry.dir && !entry.link && !['.git', '.minis-coding', 'node_modules', 'target', 'dist', '.ssh', '.aws'].includes(entry.name)).map(entry => <button key={entry.name} disabled={busy} onClick={() => setDirectory(directory === '.' ? entry.name : `${directory}/${entry.name}`)}><span className="coding-folder"><CodingIcon name="folder" /></span><span>{entry.name}</span><span className="coding-directory-arrow"><CodingIcon name="chevron" /></span></button>)}
            {!loading && view && !view.entries.some(entry => entry.dir && !entry.link) && <p>当前目录没有子目录，可以直接{choice === 'create' ? '在这里新建项目' : '打开这个目录'}。</p>}
          </div>
          <p className="coding-selected-path"><span>{choice === 'create' ? '父目录' : '项目目录'}</span><code>{workspace?.workdir || '工作空间默认目录'}{directory === '.' ? '' : `/${directory}`}</code></p>
          {choice === 'create' && <Field label="项目名称"><input className={INPUT} value={name} onChange={e => setName(e.target.value)} placeholder="例如 my-next-app" maxLength={100} disabled={busy} /></Field>}
          <details className="coding-open-options"><summary>验证设置（可选）</summary><Field label="验证命令" hint="在选定的工作空间中运行，例如 npm run build 或 ./mvnw test。"><input className={INPUT} value={validation} onChange={e => setValidation(e.target.value)} placeholder="使用项目现有的构建或测试命令" disabled={busy} /></Field></details>
          {error && <p role="alert" className="coding-inline-error">{error}</p>}
          <div className="coding-picker-footer"><span>{choice === 'create' ? '将在当前父目录创建新文件夹' : '以所选目录作为编程项目工作区'}</span><Button variant="primary" disabled={busy || loading || !view || !workspace?.enabled || (choice === 'create' && !name.trim())} onClick={() => void submit()}>{busy ? '正在打开…' : choice === 'create' ? '创建并打开项目' : '打开此目录 →'}</Button></div>
        </>}
      </section>}
      <section className="coding-recent"><div className="coding-section-heading"><h2>最近项目</h2><span>{projects.length ? `${projects.length} 个项目` : ''}</span></div>
        {!projects.length ? <div className="coding-recent-empty">你的编程项目会出现在这里。工作空间连接和凭证沿用现有配置。</div> : projects.map(project => {
          const latest = runs.find(run => run.repositoryId === project.id)
          return <button className="coding-project-row" key={project.id} onClick={() => onOpen(project)}><span className="coding-project-icon"><AppIcon name="folder-open" size={18} /></span><span className="coding-project-info"><strong>{project.name}</strong><small>{project.workspaceName} / {project.directory === '.' ? '根目录' : project.directory}</small></span><span className="coding-project-meta">{project.enabled ? latest ? new Date(latest.updatedAt).toLocaleDateString('zh-CN') : project.kind.toUpperCase() : '工作空间不可用'}</span><span>→</span></button>
        })}
      </section>
      <footer className="coding-start-foot">一个工作空间，多个项目。每次编程任务的修改都可以审阅后再应用。</footer>
    </main>
  </div>
}
