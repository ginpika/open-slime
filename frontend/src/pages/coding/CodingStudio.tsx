import { AppIcon } from '../../components/AppIcon'
import { memo, useCallback, useEffect, useId, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { ModelView, WorkspaceDirView, WorkspaceFileView } from '../../api/client'
import { acceptCodingChanges, cancelCodingRun, codingDiff, codingError, codingEvents, codingProjectFile, codingProjectFiles, codingRequestKey, codingRun, resumeCodingRun, startCodingRun, steerCodingRun, streamCodingEvents, type CodingCapabilities, type CodingDiff, type CodingEvent, type CodingMode, type CodingProject, type CodingRun } from '../../api/coding'
import { Button } from '../../components/ui'
import { Select } from '../../components/Select'
import { ModelBrandIcon } from '../../components/ModelBrandIcon'
import CodingIcon from './CodingIcon'
import { currentAttemptText } from './state'
import CodingAgentProfileLink from './CodingAgentProfileLink'
import CodingTimeline from './CodingTimeline'
import { type TranscriptView } from './CodingTimeline'
import CodingChildren from './CodingChildren'
import { codingChildren, codingSession, codingSessionRuns, type CodingChild, type CodingSession } from '../../api/coding'
import { eventData } from './timeline'
import { codingPermissions } from '../../api/coding'
import CodingTerminalPanel from './CodingTerminalPanel'
import CodingRunSettings from './CodingRunSettings'
import CodingReview from './CodingReview'
import { readTerminalLayout, saveTerminalLayout } from './terminalLayout'

const ACTIVE = new Set(['QUEUED', 'PREPARING', 'RUNNING', 'WAITING_AGENTS', 'VERIFYING', 'CANCELLING', 'ACCEPTING'])
const RESUME = new Set(['REVIEWABLE', 'PAUSED', 'INTERRUPTED', 'FAILED', 'CANCELLED'])
const STATUS: Record<string, string> = { QUEUED: '等待开始', PREPARING: '准备项目', RUNNING: 'Agent 工作中', VERIFYING: '验证中', REVIEWABLE: '等待审阅', CANCELLING: '正在停止', CANCELLED: '已停止', PAUSED: '已暂停', FAILED: '执行失败', INTERRUPTED: '已中断', ACCEPTING: '应用变更中', COMPLETED: '变更已应用' }
const VALIDATION: Record<string, string> = { NOT_RUN: '尚未验证', PASSED: '验证通过', FAILED: '验证失败', BLOCKED: '验证受阻', STALE: '验证版本已变化' }
STATUS.WAITING_AGENTS = '等待子 Agent'
function data(event: CodingEvent): Record<string, unknown> { try { return JSON.parse(event.payload) as Record<string, unknown> } catch { return {} } }
type Draft = { input: string; mode: CodingMode }
type Props = { project: CodingProject; models: ModelView[]; caps: CodingCapabilities; runs: CodingRun[]; sessions: CodingSession[]; runId: string; onRunChanged: (run: CodingRun) => void; onSelectRun: (id: string, draft?: Draft) => void; initialDraft?: Draft; loadError?: string; onReload?: () => Promise<void> }

const FileTree = memo(function ProjectFileTree({ projectId, path = '.', runId, revision, active, onOpen, depth = 0 }: { projectId: string; path?: string; runId?: string; revision: number | string; active: string; onOpen: (path: string) => void; depth?: number }) {
  const [view, setView] = useState<WorkspaceDirView | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [error, setError] = useState('')
  useEffect(() => {
    let stopped = false; setError('')
    void codingProjectFiles(projectId, path, runId).then(result => { if (!stopped) setView(result) }).catch(e => { if (!stopped) setError(codingError(e)) })
    return () => { stopped = true }
  }, [projectId, path, runId, revision])
  if (error) return <p className="coding-tree-note" role="alert">{error}</p>
  if (!view) return <p className="coding-tree-note">读取文件…</p>
  if (!view.entries.length) return depth === 0 ? <p className="coding-tree-note">项目目录为空。从右侧描述你想创建的内容。</p> : <p className="coding-tree-note" style={{ paddingLeft: depth * 14 + 24 }}>空目录</p>
  return <>{view.entries.map(entry => {
    const full = path === '.' ? entry.name : `${path}/${entry.name}`
    const open = expanded.has(entry.name)
    return <div key={full}><button className={`coding-tree-row ${active === full ? 'is-active' : ''}`} style={{ paddingLeft: depth * 14 + 12 }} onClick={() => { if (entry.dir) setExpanded(previous => { const next = new Set(previous); if (open) next.delete(entry.name); else next.add(entry.name); return next }); else onOpen(full) }} title={full} aria-expanded={entry.dir ? open : undefined}><span className="coding-tree-caret">{entry.dir && <CodingIcon name={open ? 'down' : 'chevron'} />}</span><span className={entry.dir ? 'coding-folder' : 'coding-file-glyph'}><CodingIcon name={entry.dir ? 'folder' : 'file'} /></span><span className="coding-tree-name">{entry.name}</span></button>{entry.dir && open && depth < 12 && <FileTree projectId={projectId} path={full} runId={runId} revision={revision} active={active} onOpen={onOpen} depth={depth + 1} />}</div>
  })}</>
})

export default function CodingStudio({ project, models, caps, runs, sessions, runId, onRunChanged, onSelectRun, initialDraft, loadError, onReload }: Props) {
  const [loadedRun, setRun] = useState<CodingRun | null>(null)
  const run = loadedRun?.id === runId ? loadedRun : null
  const [events, setEvents] = useState<CodingEvent[]>([])
  const [children, setChildren] = useState<CodingChild[]>([]), [childError, setChildError] = useState(''), [session, setSession] = useState<CodingSession | null>(null), [history, setHistory] = useState<CodingRun[]>([]), [sessionError, setSessionError] = useState('')
  const [transcript, setTranscript] = useState<TranscriptView>(() => { const saved = localStorage.getItem('minis.coding.transcript'); return saved === 'normal' || saved === 'verbose' ? saved : 'thinking' })
  const modeLoaded = useRef(false)
  const [diff, setDiff] = useState<CodingDiff | null>(null)
  const [diffError, setDiffError] = useState('')
  const [diffLoading, setDiffLoading] = useState(false)
  const [view, setView] = useState<'code' | 'diff'>('code')
  const [fullscreen, setFullscreen] = useState(false)
  const studio = useRef<HTMLDivElement>(null), fullscreenButton = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!fullscreen || !studio.current) return
    const outside: { element: HTMLElement; inert: boolean }[] = []
    // 全屏保留原 DOM 与终端连接，并暂停被覆盖的导航区域的键盘交互。
    for (let current: HTMLElement = studio.current; current.parentElement; current = current.parentElement) {
      for (const sibling of current.parentElement.children) {
        if (sibling !== current && sibling instanceof HTMLElement) { outside.push({ element: sibling, inert: sibling.inert }); sibling.inert = true }
      }
    }
    const exit = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || document.querySelector('dialog[open]') || studio.current?.querySelector('[aria-modal="true"], [role="combobox"][aria-expanded="true"]') || (event.target instanceof Element && event.target.closest('.xterm'))) return
      event.preventDefault(); setFullscreen(false); fullscreenButton.current?.focus()
    }
    window.addEventListener('keydown', exit)
    return () => { window.removeEventListener('keydown', exit); outside.forEach(({ element, inert }) => { element.inert = inert }) }
  }, [fullscreen])
  const [terminalLayout, setTerminalLayout] = useState(() => readTerminalLayout(project.id))
  const { inEditor: terminalInEditor, view: terminalView, open: terminalOpen } = terminalLayout
  const setTerminalView = useCallback((view: boolean) => setTerminalLayout(previous => ({ ...previous, view })), [])
  const setTerminalOpen = useCallback((open: boolean) => setTerminalLayout(previous => ({ ...previous, open })), [])
  const toggleTerminalLayout = () => setTerminalLayout(previous => ({ ...previous, inEditor: !previous.inEditor, view: !previous.inEditor }))
  const [terminalTabsHost, setTerminalTabsHost] = useState<HTMLDivElement | null>(null)
  const activateTerminal = useCallback(() => setTerminalView(true), [setTerminalView])
  const leaveTerminal = useCallback(() => setTerminalView(false), [setTerminalView])
  const showTerminal = terminalInEditor && terminalView
  useEffect(() => { saveTerminalLayout(project.id, terminalLayout) }, [project.id, terminalLayout])
  const [filePath, setFilePath] = useState('')
  const [tabs, setTabs] = useState<string[]>([])
  const [file, setFile] = useState<WorkspaceFileView | null>(null)
  const [fileError, setFileError] = useState('')
  const [fileLoading, setFileLoading] = useState(false)
  const [source, setSource] = useState<'origin' | 'copy'>('origin')
  const [revision, setRevision] = useState(0)
  const [input, setInput] = useState(initialDraft?.input || '')
  const [mode, setMode] = useState<CodingMode>(initialDraft?.mode || 'EDIT')
  const [model, setModel] = useState('')
  const [settings, setSettings] = useState(false)
  const [autoReview, setAutoReview] = useState(() => localStorage.getItem('minis.coding.autoReview') !== 'false')
  const [permissionLoading, setPermissionLoading] = useState(!!runId), [permissionError, setPermissionError] = useState(''), [permissionRevision, setPermissionRevision] = useState(0)
  const [acknowledge, setAcknowledge] = useState(false)
  const [reviewedDiff, setReviewedDiff] = useState<CodingDiff | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [pollError, setPollError] = useState('')
  const [side, setSide] = useState<'files' | 'agent'>('agent')
  const [explorerTab, setExplorerTab] = useState<'files' | 'sessions'>('files')
  const explorerId = useId()
  const [following, setFollowing] = useState(true)
  const cursor = useRef(0)
  const selected = useRef(runId)
  useEffect(() => { selected.current = runId }, [runId])
  const composer = useRef<HTMLTextAreaElement>(null)
  const scroll = useRef<HTMLDivElement>(null)
  const confirmElement = useRef<HTMLElement>(null)
  const request = useRef<{ fingerprint: string; key: string } | null>(null)
  const working = !!run && ACTIVE.has(run.status)
  const currentMode = working ? run!.mode : mode
  const copyId = source === 'copy' && runId ? runId : undefined
  const selectRun = (id: string) => { if (id !== runId || !id) { if (!id) setInput(''); onSelectRun(id) } }
  const update = useCallback((next: CodingRun) => { onRunChanged(next); if (selected.current === next.id) setRun(next) }, [onRunChanged])
  const action = async (work: () => Promise<void>) => { if (busy) return; const task = runId; setBusy(true); setError(''); try { await work() } catch (e) { if (selected.current === task) setError(codingError(e)) } finally { setBusy(false) } }
  useEffect(() => {
    setInput(initialDraft?.input || '')
    if (initialDraft || !runId) setMode(initialDraft?.mode || 'EDIT')
    request.current = null
  }, [runId, initialDraft])
  useEffect(() => {
    setPermissionError(''); setPermissionLoading(!!runId)
    if (!runId) { setAutoReview(localStorage.getItem('minis.coding.autoReview') !== 'false'); return }
    let stopped = false
    void codingPermissions(runId).then(policy => { if (!stopped) setAutoReview(policy.autoReview) }).catch(e => { if (!stopped) setPermissionError(codingError(e)) }).finally(() => { if (!stopped) setPermissionLoading(false) })
    return () => { stopped = true }
  }, [runId, permissionRevision])
  useEffect(() => {
    // 任务切换只重置会话数据，文件树、编辑器和终端保留项目级状态。
    setRun(null); setEvents([]); setDiff(null); setDiffError(''); setDiffLoading(false); setReviewedDiff(null); setError(''); setPollError(''); setAcknowledge(false); setFollowing(true)
    setChildren([]); setChildError(''); setSession(null); setHistory([]); setSessionError(''); modeLoaded.current = false
    cursor.current = 0
    if (!runId) { setSource('origin'); return }
    let stopped = false; let polling = false
    const controller = new AbortController()
    const receive = (event: CodingEvent) => {
      if (stopped) return
      cursor.current = Math.max(cursor.current, event.id)
      setEvents(previous => previous.some(row => row.id === event.id) ? previous : [...previous, event].sort((a, b) => a.id - b.id))
    }
    const refresh = async () => {
      if (polling) return; polling = true
      try {
        const [next, rows] = await Promise.all([codingRun(runId), codingEvents(runId, cursor.current)])
        if (stopped) return
        if (next.repositoryId !== project.id) { setError('任务不属于当前项目'); return }
        setPollError(''); update(next); rows.forEach(receive)
        if (!modeLoaded.current) { modeLoaded.current = true; setMode(next.mode); setModel(next.modelId || '') }
      } catch (e) { if (!stopped) setPollError(codingError(e)) } finally { polling = false }
    }
    void refresh()
    const auxiliary = async () => {
      const [items, conversation] = await Promise.allSettled([codingChildren(runId), codingSession(runId)])
      if (stopped) return
      if (items.status === 'fulfilled') { setChildren(items.value); setChildError('') } else setChildError(codingError(items.reason))
      if (conversation.status === 'fulfilled' && conversation.value.id) {
        setSession(conversation.value); setSessionError('')
        try { const previous = await codingSessionRuns(conversation.value.id); if (!stopped) setHistory(previous) } catch (e) { if (!stopped) setSessionError(codingError(e)) }
      } else if (conversation.status === 'rejected') setSessionError(codingError(conversation.reason))
    }
    void auxiliary()
    const timer = window.setInterval(() => void refresh(), 2200)
    const childTimer = window.setInterval(() => void auxiliary(), 3000)
    void streamCodingEvents(runId, 0, controller.signal, receive).catch(() => undefined)
    return () => { stopped = true; controller.abort(); window.clearInterval(timer); window.clearInterval(childTimer) }
  }, [runId, project.id, update])
  const artifact = run?.artifactHash
  const fileRevision = copyId ? `${revision}:${artifact || ''}` : revision
  useEffect(() => {
    if (view !== 'diff' || !runId) return
    let stopped = false; setDiffError(''); setDiffLoading(true); setDiff(null)
    void codingDiff(runId).then(result => { if (!stopped) setDiff(result) }).catch(e => { if (!stopped) setDiffError(codingError(e)) }).finally(() => { if (!stopped) setDiffLoading(false) })
    return () => { stopped = true }
  }, [view, runId, artifact, revision])
  useEffect(() => {
    setFile(null); setFileError(''); setFileLoading(false); if (!filePath) return
    let stopped = false; setFileLoading(true)
    void codingProjectFile(project.id, filePath, copyId).then(result => { if (!stopped) setFile(result) }).catch(e => { if (!stopped) setFileError(codingError(e)) }).finally(() => { if (!stopped) setFileLoading(false) })
    return () => { stopped = true }
  }, [project.id, filePath, copyId, fileRevision])
  useEffect(() => { if (following && scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight }, [events.length, run?.answer, following])
  useEffect(() => {
    if (!reviewedDiff) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    confirmElement.current?.focus()
    const handle = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) { event.preventDefault(); setReviewedDiff(null) }
      if (event.key !== 'Tab') return
      const buttons = [...(confirmElement.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
      const first = buttons[0], last = buttons[buttons.length - 1]
      if (!first) { event.preventDefault(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === confirmElement.current)) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === confirmElement.current)) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', handle)
    return () => { document.removeEventListener('keydown', handle); if (previous?.isConnected) previous.focus() }
  }, [reviewedDiff, busy])
  const openFile = useCallback((path: string) => { setFilePath(path); setTabs(previous => previous.includes(path) ? previous : [...previous, path].slice(-8)); setView('code'); setTerminalView(false) }, [setTerminalView])
  const closeFile = (path: string) => { const next = tabs.filter(tab => tab !== path); setTabs(next); if (filePath === path) setFilePath(next[next.length - 1] || '') }
  const send = () => void action(async () => {
    if (run && run.status !== 'COMPLETED') {
      if (working) update(await steerCodingRun(run.id, input))
      else update(await resumeCodingRun(run.id, input, acknowledge, mode, autoReview))
      if (selected.current !== runId) return
      setInput(''); setAcknowledge(false)
    } else {
      const body = { repositoryId: project.id, prompt: input, modelId: model || null, mode, runner: 'WORKSPACE' as const, autoReview, ...(run?.status === 'COMPLETED' && session ? { sessionId: session.id } : {}) }
      const fingerprint = JSON.stringify(body)
      if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, key: codingRequestKey() }
      const created = await startCodingRun(body, request.current.key)
      onRunChanged(created)
      if (selected.current !== runId) return
      request.current = null; onSelectRun(created.id)
    }
    setFollowing(true)
  })
  const openAccept = () => void action(async () => {
    if (!run) return
    const fresh = await codingDiff(run.id)
    if (selected.current !== run.id) return
    setDiff(fresh); setDiffError(''); setView('diff'); setTerminalView(false); setSide('files')
    if (view === 'diff') setReviewedDiff(fresh)
  })
  const text = currentAttemptText(events, run?.attempt || 1)
  const budget = [...events].reverse().find(event => event.type === 'budget.updated' && Number(data(event).attempt) === run?.attempt)
  const budgetData = budget ? data(budget) : null
  const historical = !!session?.lastRunId && session.lastRunId !== runId
  const disabledSend = busy || permissionLoading || !!permissionError || historical || !project.enabled || !caps.enabled || (!!runId && !run) || (!!run && ['CANCELLING', 'ACCEPTING'].includes(run.status)) || (!!run && !working && !RESUME.has(run.status) && run.status !== 'COMPLETED') || ((working || !run || run.status === 'COMPLETED') && !input.trim()) || (run?.status === 'COMPLETED' && !session)
  const lines = (file?.content || '').split('\n')
  const previousTurns = history.slice(0, Math.max(0, history.findIndex(item => item.id === runId)))
  return <div ref={studio} className={`coding-studio coding-mobile-${side}${fullscreen ? ' is-fullscreen' : ''}`}>
    <header className="coding-studio-toolbar"><a className="coding-back" href="#/coding?start=1" title="返回项目起始页"><CodingIcon name="back" /><span>项目</span></a><span className="coding-toolbar-divider" /><strong>{project.name}</strong><span className="coding-workspace-tag"><i className={project.enabled ? 'is-online' : ''} />{project.workspaceName}<span>{project.kind.toUpperCase()}</span></span><code className="coding-toolbar-path">{project.directory}</code><a className="coding-workspace-link" href={`#/workspace?target=${encodeURIComponent(project.workspaceId)}`}>工作空间<CodingIcon name="external" /></a><button ref={fullscreenButton} type="button" className="coding-fullscreen-toggle" aria-label={fullscreen ? '退出全屏' : '全屏 AI IDE'} title={fullscreen ? '退出全屏' : '全屏 AI IDE'} aria-pressed={fullscreen} onClick={() => setFullscreen(value => !value)}><AppIcon name={fullscreen ? 'exit-fullscreen' : 'fullscreen'} /></button></header>
    <div className="coding-mobile-tabs"><button onClick={() => setSide('files')} aria-pressed={side === 'files'}>文件与代码</button><button onClick={() => setSide('agent')} aria-pressed={side === 'agent'}>Agent 对话</button></div>
    <div className="coding-studio-body">
      <aside className="coding-explorer"><div className="coding-explorer-tabs" role="tablist" aria-label="项目导航" onKeyDown={event => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const next = event.key === 'Home' ? 'files' : event.key === 'End' ? 'sessions' : explorerTab === 'files' ? 'sessions' : 'files'; setExplorerTab(next); event.currentTarget.querySelector<HTMLButtonElement>(`[data-section="${next}"]`)?.focus() }}>
        {(['files', 'sessions'] as const).map(tab => <button key={tab} id={`${explorerId}-${tab}-tab`} data-section={tab} role="tab" aria-selected={explorerTab === tab} aria-controls={`${explorerId}-${tab}-panel`} tabIndex={explorerTab === tab ? 0 : -1} onClick={() => setExplorerTab(tab)}><CodingIcon name={tab === 'files' ? 'folder' : 'chat'} /><span>{tab === 'files' ? '项目文件' : '编程会话'}</span></button>)}
      </div><div className="coding-sidebar-panel" role="tabpanel" id={`${explorerId}-files-panel`} aria-labelledby={`${explorerId}-files-tab`} hidden={explorerTab !== 'files'}><div className="coding-pane-heading"><span>项目文件</span><button onClick={() => setRevision(value => value + 1)} title="刷新文件" aria-label="刷新文件"><CodingIcon name="refresh" /></button></div><Select menuClassName="coding-select-menu" className="coding-source-select" size="compact" label="文件查看来源" value={source} onChange={value => setSource(value as 'origin' | 'copy')} options={[{ value: 'origin', label: '项目目录' }, { value: 'copy', label: '当前任务副本', disabled: !run || ['QUEUED', 'PREPARING'].includes(run.status) }]} /><div className="coding-tree scrollbar-slim"><FileTree key={project.id} projectId={project.id} runId={copyId} revision={fileRevision} active={filePath} onOpen={openFile} /></div></div><div className="coding-sidebar-panel" role="tabpanel" id={`${explorerId}-sessions-panel`} aria-labelledby={`${explorerId}-sessions-tab`} hidden={explorerTab !== 'sessions'}>
        <div className="coding-task-heading"><span>编程会话</span><button onClick={() => selectRun('')} title="新会话" aria-label="新建编程任务"><CodingIcon name="plus" /></button></div><div className="coding-task-list scrollbar-slim"><button className={`coding-task ${!runId ? 'is-active' : ''}`} onClick={() => selectRun('')}><span><CodingIcon name="plus" />新会话</span></button>{sessions.map(item => { const latest = runs.find(r => r.id === item.lastRunId); return <button className={`coding-task ${session?.id === item.id || run?.sessionId === item.id ? 'is-active' : ''}`} onClick={() => selectRun(item.lastRunId || '')} key={item.id}><span>{item.title}</span><small>{latest ? STATUS[latest.status] || latest.status : '继续会话'}</small></button> })}{runs.filter(item => !item.sessionId && !sessions.some(s => s.lastRunId === item.id || s.id === item.id)).map(item => <button className={`coding-task ${runId === item.id ? 'is-active' : ''}`} onClick={() => selectRun(item.id)} key={item.id}><span>{item.prompt}</span><small>{STATUS[item.status] || item.status}</small></button>)}</div></div>
      </aside>
      <main className="coding-editor"><div className="coding-editor-tabs"><button className={!showTerminal && view === 'code' ? 'is-active' : ''} onClick={() => { setView('code'); setTerminalView(false) }}>文件</button><button className={!showTerminal && view === 'diff' ? 'is-active' : ''} disabled={!run} onClick={() => { setView('diff'); setTerminalView(false) }}>变更{diff?.changes.length ? ` ${diff.changes.length}` : ''}</button><div className="coding-file-tabs scrollbar-slim">{tabs.map(path => <div key={path} className={!showTerminal && view === 'code' && filePath === path ? 'coding-file-tab is-active' : 'coding-file-tab'}><button onClick={() => openFile(path)} title={path}>{path.split('/').pop()}</button><button className="coding-close-tab" onClick={() => closeFile(path)} aria-label={`关闭 ${path}`}><CodingIcon name="close" /></button></div>)}</div><div className="coding-editor-terminal-host" ref={setTerminalTabsHost} hidden={!terminalInEditor} /><button className="coding-terminal-layout" aria-label="切换终端布局" aria-pressed={terminalInEditor} title={terminalInEditor ? '恢复底部终端' : '在主视图中显示终端标签'} onClick={toggleTerminalLayout}><AppIcon name={terminalInEditor ? 'terminal-bottom' : 'terminal-layout'} /></button></div>
        <div className="coding-editor-document" hidden={showTerminal}>
        {view === 'code' ? <>
          {filePath && <div className="coding-editor-breadcrumb"><span>{project.name} / {filePath}</span><span>{source === 'copy' ? '任务副本' : '项目目录'} · 只读预览</span></div>}
          <div className="coding-editor-content scrollbar-slim">
            {fileLoading ? <div className="coding-center-note">正在读取代码…</div> : fileError ? <div className="coding-center-note" role="alert">{fileError}</div> : file?.binary ? <div className="coding-center-note">该文件为二进制文件，不提供文本预览。</div> : file ? <><div className="coding-code-view"><pre className="coding-line-numbers" aria-hidden="true">{lines.slice(0, 5000).map((_, i) => i + 1).join('\n')}</pre><pre className="coding-code-text"><code>{lines.slice(0, 5000).join('\n')}</code></pre></div>{(file.truncated || lines.length > 5000) && <div className="coding-preview-note">文件预览已截断。</div>}</> : <div className="coding-editor-welcome"><div className="coding-welcome-symbol"><AppIcon name="code" size={32} /></div><h2>{project.name}</h2><p>项目已经打开。选择左侧文件查看代码，<br />或向 Agent 描述你要完成的目标。</p><div className="coding-quick-actions">{['梳理项目结构与启动方式', '检查潜在问题并给出修复建议', '为项目实现一个新功能'].map((prompt, i) => <button key={prompt} onClick={() => { const nextMode = i < 2 ? 'PLAN' : 'EDIT'; if (run) onSelectRun('', { input: prompt, mode: nextMode }); else { setInput(prompt); setMode(nextMode) } setSide('agent'); composer.current?.focus() }}><span><CodingIcon name={i === 0 ? 'search' : i === 1 ? 'review' : 'plus'} /></span>{prompt}<span><CodingIcon name="arrow" /></span></button>)}</div></div>}
          </div>
        </> : <div className="coding-diff-view scrollbar-slim">{diffError ? <div className="coding-diff-error" role="alert"><p>{diffError}</p><Button size="sm" onClick={() => setRevision(value => value + 1)}>重新读取差异</Button></div> : !diff ? <div className="coding-center-note">{diffLoading ? '正在读取变更…' : '选择任务后查看代码差异'}</div> : <><div className="coding-diff-heading"><strong>{diff.changes.length} 个文件有变更</strong><span>当前任务相对项目快照</span>{run?.status === 'REVIEWABLE' && run.mode !== 'PLAN' && <Button size="sm" variant="primary" onClick={openAccept} disabled={busy || diffLoading || !diff.changes.length}>审阅并应用</Button>}</div><div className="coding-change-list">{diff.changes.map(change => <button key={change.path} onClick={() => { setSource('copy'); if (change.kind !== 'deleted') openFile(change.path) }}><span className={`coding-change-kind is-${change.kind}`}>{change.kind === 'added' ? 'A' : change.kind === 'deleted' ? 'D' : 'M'}</span><code>{change.path}</code><small>{change.binary ? '二进制' : ''}</small></button>)}</div>{diff.truncated && <p className="coding-preview-note">差异预览已截断，请逐个查看文件后再接受。</p>}<pre className="coding-patch">{diff.patch.split('\n').map((line, i) => <span key={i} className={line.startsWith('+') ? 'is-added' : line.startsWith('-') ? 'is-deleted' : line.startsWith('@@') ? 'is-hunk' : ''}>{line || ' '}</span>)}</pre>{!diff.changes.length && <div className="coding-center-note">当前任务没有代码变更。</div>}</>}</div>}
        </div>
        <CodingTerminalPanel key={project.id} project={project} events={events} hasRun={!!runId} embedded={terminalInEditor} visible={showTerminal} open={terminalOpen} onOpenChange={setTerminalOpen} tabsHost={terminalTabsHost} onActivate={activateTerminal} onEmpty={leaveTerminal} />
      </main>
      <section className="coding-agent"><div className="coding-pane-heading"><CodingAgentProfileLink agent={caps.agent} working={working} /><Button size="sm" variant="quiet" onClick={() => selectRun('')}>新任务<CodingIcon name="plus" /></Button></div>
        <div key={runId} className="coding-agent-scroll scrollbar-slim" ref={scroll} onScroll={() => { const el = scroll.current; if (el) setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight < 80) }}>
          {!run ? <div className="coding-agent-empty"><span><CodingIcon name="review" /></span><h3>一起完成这个项目</h3><p>告诉我你要实现、修复或理解的内容。<br />我会从当前项目的代码开始。</p></div> : <>
            <div className="coding-transcript-controls"><span>{session ? '持续编程会话' : '编程任务'}</span><Select menuClassName="coding-select-menu" size="compact" label="过程展示" value={transcript} onChange={value => { const next = value as TranscriptView; setTranscript(next); localStorage.setItem('minis.coding.transcript', next) }} options={[{ value: 'normal', label: 'Normal · 摘要' }, { value: 'thinking', label: 'Thinking · 思考' }, { value: 'verbose', label: 'Verbose · 详细' }]} /></div>
            {previousTurns.map(item => <details className="coding-history" key={item.id}><summary>{item.prompt}</summary><div className="coding-markdown"><Markdown remarkPlugins={[remarkGfm]}>{item.answer || item.error || STATUS[item.status]}</Markdown></div><button onClick={() => selectRun(item.id)}>查看本轮完整过程 ↗</button></details>)}
            {historical && <div className="coding-notice">这是历史轮次。<button onClick={() => selectRun(session!.lastRunId!)}>回到会话最新轮次 ↗</button></div>}
            <div className="coding-user-message"><span>你</span><p>{run.prompt}</p></div>
            <div className="coding-agent-state"><i className={working ? 'coding-agent-pulse' : ''} /><span>{STATUS[run.status] || run.status}</span><small>第 {run.attempt} 次 · {run.round + 1} 轮</small></div>
            <CodingTimeline events={events.filter(event => !eventData(event).executionId)} working={working} attempt={run.attempt} directory={project.directory} view={transcript} />
            <CodingChildren runId={run.id} executions={children} events={events} directory={project.directory} parentWorking={working} view={transcript} error={childError} refresh={() => { void codingChildren(run.id).then(items => { if (selected.current === run.id) setChildren(items) }).catch(e => { if (selected.current === run.id) setChildError(codingError(e)) }) }} />
            {budgetData && <p className="coding-budget-note">本次累计{budgetData.estimated !== false ? '估算' : '使用'} {Number(budgetData.totalTokens ?? budgetData.estimatedTokens).toLocaleString()} tokens · {Number(budgetData.limit) > 0 ? `额度 ${Number(budgetData.limit).toLocaleString()}` : '未限制累计额度'}</p>}
            {run.answer ? <div className="coding-markdown coding-agent-answer"><Markdown remarkPlugins={[remarkGfm]}>{run.answer}</Markdown></div> : text ? <div className="coding-agent-stream">{text}</div> : working ? <div className="coding-thinking"><span /><span /><span /><small>正在处理项目上下文</small></div> : null}
            {run.error && <div className="coding-notice">{run.error}</div>}
            <CodingReview events={events} attempt={run.attempt} hash={run.artifactHash} working={working} />
            {run.status === 'REVIEWABLE' && run.mode === 'PLAN' && <div className="coding-delivery"><strong>分析与规划已完成</strong><p>可继续讨论，或在下方选择“修改 / 执行”并批准本轮实施权限。</p></div>}
            {run.error?.includes('截断') && <a className="coding-preview-note" href="#/settings">调整模型的单次输出上限 ↗</a>}
            {run.status === 'REVIEWABLE' && run.mode !== 'PLAN' && <div className="coding-delivery"><strong>修改已准备好</strong><p>{VALIDATION[run.validationStatus]}。审阅差异后，可以应用到项目目录。</p><Button size="sm" variant="primary" disabled={busy} onClick={openAccept}>查看变更并应用 →</Button></div>}
          </>}
        </div>
        <div className="coding-composer-area">
          {(error || pollError) && <div role="alert" className="coding-inline-error">{error || pollError}</div>}
          {permissionError && <div role="alert" className="coding-inline-error">权限配置读取失败：{permissionError}<Button size="sm" variant="quiet" onClick={() => setPermissionRevision(value => value + 1)}>重试</Button></div>}
          {sessionError && <div role="alert" className="coding-inline-error">会话历史加载失败：{sessionError}</div>}
          {loadError && <div role="alert" className="coding-inline-error">{loadError}{onReload && <Button size="sm" variant="quiet" disabled={busy} onClick={() => void action(onReload)}>重新加载项目配置</Button>}</div>}
          {!project.enabled && <div className="coding-inline-error">项目工作空间不可用，请先检查工作空间连接与启用状态。</div>}
          {run && ['INTERRUPTED', 'FAILED', 'CANCELLED'].includes(run.status) && <label className="coding-acknowledge"><input type="checkbox" checked={acknowledge} onChange={e => setAcknowledge(e.target.checked)} />已核查中断后的文件与进程；允许在存在未知操作时继续。</label>}
          <div className="coding-composer"><textarea className="scrollbar-slim" ref={composer} aria-label="编程任务或补充指令" value={input} onChange={e => setInput(e.target.value)} onKeyDown={e => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && !disabledSend) { e.preventDefault(); send() } }} maxLength={20000} placeholder={working ? '补充约束或调整方向…' : run ? run.status === 'COMPLETED' ? '在当前会话继续提问或提出下一步目标…' : '说明下一步，或直接继续任务…' : '描述你想完成的任务…'} />
            <div className="coding-composer-controls"><Select menuClassName="coding-select-menu" className="coding-mode-select" size="compact" label="任务模式" value={currentMode} disabled={working || historical} onChange={value => setMode(value as CodingMode)} options={[{ value: 'PLAN', label: '分析 / 规划' }, { value: 'EDIT', label: '修改' }, { value: 'EXECUTE', label: '执行' }]} /><Select menuClassName="coding-select-menu" className="coding-model-select" size="compact" searchable label="编程模型" value={run && run.status !== 'COMPLETED' ? run.modelId || '' : model} disabled={!!run && run.status !== 'COMPLETED'} onChange={setModel} options={[{ value: '', label: caps.agent?.modelId ? `Agent · ${models.find(item => item.id === caps.agent?.modelId)?.name || caps.agent.modelId}` : '跟随编码 Agent' }, ...models.map(item => ({ value: item.id, label: item.name, icon: <ModelBrandIcon model={item.model} /> }))]} /><Select menuClassName="coding-select-menu coding-permission-menu" className="coding-permission-select" size="compact" label="权限" value={autoReview ? "auto" : "manual"} disabled={working || historical || busy || permissionLoading || !!permissionError} onChange={value => { const enabled = value === "auto"; setAutoReview(enabled); localStorage.setItem("minis.coding.autoReview", String(enabled)) }} options={[{ value: "auto", label: "权限 · 自动审查", icon: <AppIcon name="permissions" /> }, { value: "manual", label: "权限 · 手动审阅", icon: <AppIcon name="code" /> }]} /><button className="coding-composer-settings" title="运行设置" aria-label="运行设置" aria-haspopup="dialog" aria-expanded={settings} onClick={() => setSettings(true)}><CodingIcon name="settings" /></button>{working && <Button size="sm" variant="quiet" disabled={busy || run?.status === 'CANCELLING'} onClick={() => void action(async () => { if (run) update(await cancelCodingRun(run.id)) })}>停止</Button>}<Button size="sm" variant="primary" disabled={disabledSend} onClick={send}>{working ? '发送' : run?.mode === 'PLAN' && mode !== 'PLAN' && RESUME.has(run.status) ? '批准并实施' : run ? '继续' : '开始'}<CodingIcon name="send" /></Button></div>
          </div>
          {settings && <CodingRunSettings minutes={caps.maxRunMinutes} command={project.validationCommand} onClose={() => setSettings(false)} />}
          <p className="coding-permission-hint">{autoReview ? "Agent 完成变更后，Reviewer 自动检查代码并生成审查报告。" : "Agent 完成变更后，由你手动审阅代码差异。"} 应用变更始终需要确认。</p>
          <p className="coding-composer-hint">{currentMode === 'EXECUTE' ? `命令使用 ${project.workspaceName} 的权限与环境` : currentMode === 'EDIT' ? '修改任务副本，审阅后应用到项目' : '只读分析项目'}<span>Ctrl ↵ 发送</span></p>
        </div>
      </section>
    </div>
    <footer className="coding-statusbar"><span><i />{project.workspaceName} / {project.directory}</span><span>{run ? VALIDATION[run.validationStatus] : '项目已打开'}<span className="coding-status-separator">·</span>{source === 'copy' ? '任务副本' : '项目目录'}</span></footer>
    {reviewedDiff && run?.status === 'REVIEWABLE' && <div className="coding-confirm-backdrop"><section ref={confirmElement} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="coding-accept-title" className="coding-confirm scrollbar-slim"><h2 id="coding-accept-title">应用已审阅的修改</h2><p>将 {reviewedDiff.changes.length} 个文件的变更应用到 <strong>{project.name}</strong>。</p><code>{project.workspaceName} / {project.directory}</code><p>{VALIDATION[run.validationStatus]}。应用前会再次检查项目文件与产物版本；有冲突时拒绝覆盖。</p><div><Button disabled={busy} onClick={() => setReviewedDiff(null)}>继续审阅</Button><Button variant="primary" disabled={busy || !reviewedDiff.changes.length} onClick={() => void action(async () => { update(await acceptCodingChanges(run.id, reviewedDiff.artifactHash)); setReviewedDiff(null); setRevision(value => value + 1) })}>{busy ? '正在应用…' : '确认应用变更'}</Button></div></section></div>}
  </div>
}
