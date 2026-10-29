import { useEffect, useState } from 'react'
import { createProject, updateProject, type MaPeer, type MaSession, type Project } from '../../api/client'
import { saveCollaborationMembers } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'
import PeerAvatar from '../../components/PeerAvatar'
import CollaborationDialog from './CollaborationDialog'
import ProjectWorkspaceDialog from './ProjectWorkspaceDialog'
import ProjectWorkspaceStatus from './ProjectWorkspaceStatus'
import TaskActions from './TaskActions'
import ProjectKnowledge from './ProjectKnowledge'
import type { CollaborationPlan, ProjectWorkspaceBinding } from '../../api/collaboration'
import { projectMemberIds } from './model'

function date(value: number | null) { const d = value ? new Date(value) : null; return d && Number.isFinite(+d) ? d.toLocaleDateString('zh-CN') : '' }
const projectTabs = [
  { id: 'sessions', label: '当前推进', icon: 'chat' },
  { id: 'knowledge', label: '成果与进度', icon: 'doc' },
  { id: 'settings', label: '项目设置', icon: 'sliders' },
] as const
type ProjectTab = typeof projectTabs[number]['id']
function currentProjectTab(): ProjectTab {
  const section = new URLSearchParams(window.location.hash.split('?')[1] || '').get('section')
  return projectTabs.find(tab => tab.id === section)?.id || 'sessions'
}

export default function ProjectPages({ projectId, projects, sessions, peers, configuredMembers, workspaceBindings = [], archivedTasks = [], plans = [], runningSessions, loading, error, onProject, onTask, onNewTask, onReload }: {
  projectId: string; projects: Project[]; sessions: MaSession[]; peers: MaPeer[]; configuredMembers: Record<string, string[]>
  runningSessions: string[]; loading: boolean; error: string | null
  workspaceBindings?: ProjectWorkspaceBinding[]; archivedTasks?: string[]; plans?: CollaborationPlan[]
  onProject: (id: string) => void; onTask: (session: MaSession) => void; onNewTask: () => void; onReload: () => Promise<void>
}) {
  const [editor, setEditor] = useState<'create' | 'direction' | 'members' | 'workspace' | null>(null)
  const [search, setSearch] = useState('')
  const [showArchived, setShowArchived] = useState(false)
  const [activeTab, setActiveTab] = useState(currentProjectTab)
  const project = projects.find(p => p.id === projectId)
  const allTasks = sessions.filter(s => (s.projectId || '') === projectId)
  const tasks = allTasks.filter(s => archivedTasks.includes(s.id) === showArchived)
  const taskState = (session: MaSession) => {
    const plan = plans.find(p => p.sessionId === session.id)
    return showArchived ? '已归档 · 查看记录' : runningSessions.includes(session.id) ? '正在推进' : session.topicStatus === 'waiting' ? '等待你回应' : plan?.phase === 'completed' ? '已确认交付' : plan?.continuationStatus === 'paused' ? '已暂停' : '进入会话'
  }
  const archiveToggle = <button type="button" className="ui-btn ui-btn--outline" aria-pressed={showArchived} onClick={() => setShowArchived(v => !v)}>{showArchived ? '返回当前会话' : '已归档会话'} <span>{allTasks.filter(s => archivedTasks.includes(s.id)).length}</span></button>
  const taskRows = tasks.map(s => <div className="collab-task-entry" key={s.id}><button className="collab-task-row" onClick={() => onTask(s)}><AppIcon name={showArchived ? 'archive' : 'chat'} /><span className="collab-task-text"><strong>{s.title || '未命名会话'}</strong><small>{s.memberIds.length} 位参与成员 · {date(s.updatedAt || s.createdAt)}</small></span><span className="collab-task-state">{taskState(s)}</span><AppIcon name="arrow-right" /></button><TaskActions compact id={s.id} title={s.title || '未命名会话'} archived={showArchived} busy={runningSessions.includes(s.id)} onChanged={async () => { await onReload() }} /></div>)
  const ids = projectMemberIds(projectId, configuredMembers, sessions, peers)
  const members = peers.filter(p => ids.includes(p.id))
  const activeCount = allTasks.filter(s => runningSessions.includes(s.id)).length
  const selectTab = (tab: ProjectTab) => {
    setActiveTab(tab)
    const [path, query = ''] = window.location.hash.split('?')
    const params = new URLSearchParams(query)
    params.set('section', tab)
    window.history.replaceState(null, '', `${path}?${params}`)
  }
  useEffect(() => {
    const syncTab = () => setActiveTab(currentProjectTab())
    window.addEventListener('hashchange', syncTab)
    return () => window.removeEventListener('hashchange', syncTab)
  }, [])
  return <div className="collab-project-page scrollbar-slim">
    <div className="collab-topline"><button type="button" className="collab-brand" onClick={() => onProject('')}><AppIcon name={projectId ? 'arrow-left' : 'table'} size={20} />{projectId ? '所有项目' : '协作'}</button><a href="#/multi-agent">管理智能体<AppIcon name="external" size={14} /></a></div>
    <main className="collab-page-body">
      <header className="collab-page-heading"><div><p className="collab-eyebrow">{projectId ? '持续推进的项目' : '项目协作'}</p><h1>{projectId ? project?.name || '项目不存在' : '从一个方向开始'}</h1><p>{projectId ? '每次会话推进一个目标，成果、决策和进度在项目中持续积累。' : '组织成员共同推进，让下一次协作接着已有成果开始。'}</p>{projectId && <div className="collab-project-facts"><span><AppIcon name="chat" />{allTasks.length} 次协作</span><span><AppIcon name="table" />{members.length} 位项目成员</span>{activeCount > 0 && <span className="collab-live-label"><AppIcon name="play" />{activeCount} 项正在推进</span>}</div>}</div><button className="ui-btn ui-btn--primary" disabled={loading || (!!projectId && !project)} onClick={() => projectId ? onNewTask() : setEditor('create')}><AppIcon name="plus" />{projectId ? '新建会话' : '新建项目'}</button></header>
      {error && <div className="collab-error" role="alert"><span>{error}</span><button className="ui-btn ui-btn--outline" onClick={() => void onReload()}>重试</button></div>}
      {loading ? <div className="collab-loading" role="status"><AppIcon name="loading" className="motion-safe:animate-spin" />正在载入项目…<div className="collab-skeleton" /><div className="collab-skeleton collab-skeleton--short" /></div> : !projectId ? <>
        <ol className="collab-start-guide" aria-label="协作如何持续推进"><li><AppIcon name="folder-open" size={20} /><div><strong>项目承接长期方向</strong><p>保留目标、成员和已有成果。</p></div></li><li><AppIcon name="chat" size={20} /><div><strong>会话推进本次目标</strong><p>主控规划，成员执行与独立检查。</p></div></li><li><AppIcon name="doc" size={20} /><div><strong>成果支持下一次协作</strong><p>沉淀决策、验证证据和项目现状。</p></div></li></ol>
        <div className="collab-list-heading"><h2>所有项目 <span>{projects.length}</span></h2><label className="collab-search"><AppIcon name="search" /><input aria-label="搜索项目" placeholder="搜索项目…" value={search} onChange={e => setSearch(e.target.value)} /></label></div>
        <div className="collab-project-list">{projects.filter(p => `${p.name} ${p.note || ''}`.toLowerCase().includes(search.toLowerCase())).map(p => {
          const list = sessions.filter(s => s.projectId === p.id && !archivedTasks.includes(s.id)), active = list.filter(s => runningSessions.includes(s.id)).length
          return <button className="collab-project-row" key={p.id} onClick={() => onProject(p.id)}><span className="collab-project-mark"><AppIcon name="folder-open" size={22} /></span><span className="collab-project-text"><strong>{p.name}</strong><span>{p.note || '尚未补充工作方向'}</span></span><span className="collab-project-meta">{active ? `${active} 项正在推进` : `${list.length} 次协作`}<small>{p.status === 'active' ? '进行中的项目' : p.status === 'paused' ? '已暂停' : '已归档'}</small></span><AppIcon name="arrow-right" /></button>
        })}</div>
        {!projects.length && <div className="collab-page-empty"><AppIcon name="folder" size={30} /><h2>你的第一个项目</h2><p>先给它一个名字和方向，再组织成员开始推进。</p><button className="ui-btn ui-btn--outline" onClick={() => setEditor('create')}>新建项目</button></div>}
        {!!projects.length && !projects.some(p => `${p.name} ${p.note || ''}`.toLowerCase().includes(search.toLowerCase())) && <p className="collab-page-empty">没有匹配的项目。</p>}
        {allTasks.length > 0 && !projectId && <section className="collab-legacy"><div className="collab-list-heading"><h2>未归属项目的协作记录</h2>{archiveToggle}</div><p>打开后可将历史记录整理到项目。</p>{taskRows}{!tasks.length && <p className="collab-page-empty">暂无{showArchived ? "已归档" : "推进中"}的记录。</p>}</section>}
      </> : project && <>
        <ProjectWorkspaceStatus binding={workspaceBindings.find(b => b.projectId === projectId)} busy={allTasks.some(t => runningSessions.includes(t.id))} onConfigure={() => setEditor('workspace')} />
        <div className="collab-project-tabs" role="tablist" aria-label="项目内容分页">{projectTabs.map((tab, index) => <button type="button" role="tab" key={tab.id} id={`collab-project-tab-${tab.id}`} aria-selected={activeTab === tab.id} aria-controls={`collab-project-${tab.id}`} tabIndex={activeTab === tab.id ? 0 : -1} onClick={() => selectTab(tab.id)} onKeyDown={event => {
          const next = event.key === 'ArrowRight' ? (index + 1) % projectTabs.length : event.key === 'ArrowLeft' ? (index + projectTabs.length - 1) % projectTabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? projectTabs.length - 1 : null
          if (next === null) return
          event.preventDefault()
          selectTab(projectTabs[next].id)
          document.getElementById(`collab-project-tab-${projectTabs[next].id}`)?.focus()
        }}><AppIcon name={tab.icon} />{tab.label}</button>)}</div>
        <section id="collab-project-sessions" role="tabpanel" aria-labelledby="collab-project-tab-sessions" tabIndex={0} hidden={activeTab !== 'sessions'} className="collab-project-tasks"><div className="collab-list-heading"><div><h2>{showArchived ? '已归档会话' : '协作会话'} <span>{tasks.length}</span></h2><p className="collab-description">{showArchived ? '已结束的推进记录仍可查阅，恢复后可继续交流。' : '围绕一个明确目标开始推进，进入会话查看讨论与具体工作。'}</p></div>{archiveToggle}</div>{taskRows}{!tasks.length && <div className="collab-page-empty"><AppIcon name={showArchived ? 'archive' : 'chat'} size={28} /><h3>{showArchived ? '暂无已归档会话' : '开始第一次推进'}</h3><p>{showArchived ? '归档后可在这里查看记录和恢复会话。' : '先明确本次目标，再由主控组织执行与检查。'}</p>{!showArchived && <button className="ui-btn ui-btn--outline" onClick={onNewTask}><AppIcon name="plus" />新建会话</button>}</div>}</section>
        <div id="collab-project-knowledge" role="tabpanel" aria-labelledby="collab-project-tab-knowledge" tabIndex={0} hidden={activeTab !== 'knowledge'}><ProjectKnowledge key={projectId} projectId={projectId} peers={peers} sessions={allTasks} onSession={id => { const session = sessions.find(s => s.id === id); if (session) onTask(session) }} /></div>
        <section id="collab-project-settings" role="tabpanel" aria-labelledby="collab-project-tab-settings" tabIndex={0} hidden={activeTab !== 'settings'} className="collab-project-settings"><div className="collab-section-intro"><h2>项目设置</h2><p className="collab-description">长期方向与项目成员为后续会话提供基础。</p></div><div className="collab-project-setup-grid"><section className="collab-project-direction"><div className="collab-list-heading"><h3>工作方向</h3><button className="ui-btn ui-btn--quiet" onClick={() => setEditor('direction')}><AppIcon name="edit" />编辑</button></div><p>{project.note || '补充项目希望解决的问题、长期目标和工作边界。'}</p></section><section className="collab-project-members"><div className="collab-list-heading"><h3>项目成员 <span>{members.length}</span></h3><button className="ui-btn ui-btn--quiet" onClick={() => setEditor('members')}><AppIcon name="table" />调整成员</button></div><div className="collab-member-strip">{members.map(p => <a key={p.id} href={`#/multi-agent/${encodeURIComponent(p.id)}`}><PeerAvatar name={p.name} avatar={p.avatar} size="h-9 w-9" /><span>{p.name}<small>{p.role || '成员'}</small></span></a>)}{!members.length && <p>先到智能体页添加成员。</p>}</div></section></div></section>
      </>}
    </main>
    {editor === 'workspace' && project && <ProjectWorkspaceDialog projectId={projectId} projectName={project.name} binding={workspaceBindings.find(b => b.projectId === projectId)} onSaved={onReload} onClose={() => setEditor(null)} />}
    {editor === 'create' && <CollaborationDialog title="新建项目" label="项目名称" initialNote="" submitLabel="创建项目" onClose={() => setEditor(null)} onSubmit={async (name, note) => { const p = await createProject({ name, note, kind: 'other' }); await onReload(); onProject(p.id) }} />}
    {editor === 'direction' && project && <CollaborationDialog title="编辑项目方向" label="项目名称" initialName={project.name} initialNote={project.note || ''} onClose={() => setEditor(null)} onSubmit={async (name, note) => { await updateProject(project.id, { name, note }); await onReload() }} />}
    {editor === 'members' && project && <CollaborationDialog title="调整项目成员" label="项目名称" nameReadOnly initialName={project.name} peers={peers} initialMembers={ids} onClose={() => setEditor(null)} onSubmit={async (_name, _note, picked) => { await saveCollaborationMembers(project.id, picked); await onReload() }} />}
  </div>
}
