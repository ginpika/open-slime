import { AppIcon } from '../components/AppIcon'
import { useCallback, useEffect, useState } from 'react'
import { getSettings, type ModelView } from '../api/client'
import { codingCapabilities, codingError, codingProjects, codingRuns, codingSessions, codingWorkspaces, type CodingCapabilities, type CodingMode, type CodingProject, type CodingRun, type CodingSession, type CodingWorkspace } from '../api/coding'
import CodingStart from './coding/CodingStart'
import CodingStudio from './coding/CodingStudio'
import { readCodingProject, rememberCodingProject, resolveCodingProject } from './coding/projectSession'
import './coding.css'
import './coding/studio.css'

function routeParams() { return new URLSearchParams(window.location.hash.split('?')[1] || '') }

export default function Coding() {
  const [params, setParams] = useState(routeParams)
  const [projects, setProjects] = useState<CodingProject[]>([])
  const [projectsLoaded, setProjectsLoaded] = useState(false)
  const [workspaces, setWorkspaces] = useState<CodingWorkspace[]>([])
  const [runs, setRuns] = useState<CodingRun[]>([])
  const [sessions, setSessions] = useState<CodingSession[]>([])
  const [models, setModels] = useState<ModelView[]>([])
  const [caps, setCaps] = useState<CodingCapabilities | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [draft, setDraft] = useState<{ projectId: string; input: string; mode: CodingMode } | null>(null)
  const load = useCallback(async () => {
    setError('')
    const result = await Promise.allSettled([codingProjects(), codingWorkspaces(), codingRuns(), getSettings(), codingCapabilities(), codingSessions()] as const)
    const [p, w, r, settings, capabilities, conversations] = result
    setProjectsLoaded(p.status === 'fulfilled')
    if (p.status === 'fulfilled') setProjects(p.value)
    if (w.status === 'fulfilled') setWorkspaces(w.value)
    if (r.status === 'fulfilled') setRuns(r.value)
    if (conversations.status === 'fulfilled') setSessions(conversations.value)
    if (settings.status === 'fulfilled') setModels(settings.value.models)
    if (capabilities.status === 'fulfilled') setCaps(capabilities.value)
    else setCaps(null)
    const names = ['项目', '工作空间', '任务', '模型设置', '模块状态', '会话']
    setError(result.flatMap((item, i) => item.status === 'rejected' ? [`${names[i]}加载失败：${codingError(item.reason)}`] : []).join('；'))
    setLoading(false)
  }, [])
  useEffect(() => { void load() }, [load])
  useEffect(() => { const change = () => setParams(routeParams()); window.addEventListener('hashchange', change); return () => window.removeEventListener('hashchange', change) }, [])
  const runId = params.get('run') || ''
  const explicitProject = params.has('project') || params.has('run')
  const projectId = resolveCodingProject(params, runs, readCodingProject())
  const project = projects.find(item => item.id === projectId && (explicitProject || item.enabled))
  useEffect(() => {
    if (!loading && project?.enabled && caps) rememberCodingProject(project.id)
  }, [loading, project, caps])
  useEffect(() => {
    if (loading || !projectsLoaded) return
    const remembered = readCodingProject()
    if (remembered && !projects.some(item => item.id === remembered && item.enabled)) rememberCodingProject('')
  }, [loading, projectsLoaded, projects])
  const open = (item: CodingProject) => {
    setDraft(null)
    setProjects(previous => [item, ...previous.filter(p => p.id !== item.id)])
    window.location.hash = `/coding?project=${encodeURIComponent(item.id)}`
  }
  const changed = useCallback((run: CodingRun) => {
    setRuns(previous => [run, ...previous.filter(r => r.id !== run.id)].sort((a, b) => b.updatedAt - a.updatedAt))
    if (run.sessionId) setSessions(previous => { const existing = previous.find(s => s.id === run.sessionId); if (existing && existing.updatedAt > run.updatedAt) return previous; const next = { id: run.sessionId!, repositoryId: run.repositoryId, title: existing?.title || run.prompt, lastRunId: run.id, createdAt: existing?.createdAt || run.createdAt, updatedAt: run.updatedAt }; return [next, ...previous.filter(s => s.id !== next.id)] })
  }, [])
  if (loading) return <div className="coding-loading" role="status"><span className="coding-wordmark"><AppIcon name="code" size={26} /></span><p>正在加载编程项目…</p></div>
  if (project && caps) return <CodingStudio key={`${project.id}:${runId}`} project={project} models={models} caps={caps} runs={runs.filter(r => r.repositoryId === project.id)} sessions={sessions.filter(s => s.repositoryId === project.id)} runId={runId} onRunChanged={changed} initialDraft={draft?.projectId === project.id ? draft : undefined} loadError={error} onReload={load} onSelectRun={(id, seed) => { setDraft(seed ? { ...seed, projectId: project.id } : null); window.location.hash = `/coding?project=${encodeURIComponent(project.id)}${id ? `&run=${encodeURIComponent(id)}` : ''}` }} />
  return <CodingStart key={`${params.get('workspace') || ''}:${params.get('directory') || '.'}`} projects={projects} workspaces={workspaces} runs={runs} onOpen={open} onReload={load} initialWorkspace={params.get('workspace') || ''} initialDirectory={params.get('directory') || '.'} error={error || (explicitProject && projectId ? '这个项目未绑定现有工作空间，请从下方选择工作空间目录打开项目。旧任务记录仍保存在后端。' : '')} enabled={caps?.enabled === true} agent={caps?.agent} />
}
