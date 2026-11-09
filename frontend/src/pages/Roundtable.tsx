/**
 * 协作工作台：项目 / 协作会话为边界，当前局面、讨论、协作图与执行记录共享上下文。
 * 发言和停止沿用 roundtableLive 的应用级运行时，切换视图不会中断执行。
 * 文件名保留旧入口兼容，页面布局与项目视图位于 collaboration/。
 */

import { AppIcon } from '../components/AppIcon'
import AgentMessageReceipt from '../components/AgentMessageReceipt'
import { AGENT_MESSAGE_SENT } from '../components/settledChat'
import { subscribeSessionEvents } from '../components/sessionEvents'
import {
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import ReactMarkdown from 'react-markdown'
import ComfyImage from '../components/ComfyImage'
import remarkGfm from 'remark-gfm'
import { useVirtualizer } from '@tanstack/react-virtual'
import {
  ApiError,
  listProjects,
  organizeSessionProject,
  getSettings,
  listMaPeers,
  listMaSessions,
  maMessageContext,
  maMessages,
  maTopics,
  MA_STATE_LABELS,
  MA_EXEC_LABELS,
  MA_SPEAKER_STRATEGIES,
  type MaMessage,
  type MaPeer,
  type MaSession,
  type MaTopic,
  type TraceSegment,
  type MaWorkflowEvent,
  type Project,
} from '../api/client'
import { Card, CardHead } from '../components/Card'
import { btnOutline, miniBtn } from '../components/ui'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import {
  clearRunError,
  clearRoundtableRun,
  onRoundtableSettled,
  startRoundtable,
  stopRoundtable,
  useRoundtableRun,
  useRunningSessionIds,
} from '../components/roundtableLive'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import FloatWindow from '../components/FloatWindow'
import ProjectDialog from '../components/ProjectDialog'
import ProjectGraph from './collaboration/ProjectGraph'
import ProjectPages from './collaboration/ProjectPages'
import CollaborationDialog from './collaboration/CollaborationDialog'
import { ConclusionDialog, GraphActivity, IssueCard, TodoPanel } from './collaboration/SessionPanels'
import { collaborationHref, useCollaborationNavigation } from './collaboration/navigation'
import { collaborationWorkspace, createCollaborationTask, createCollaborationIssue, concludeCollaborationIssue, rewindCollaborationSession, type CollaborationWorkspace, type CollaborationIssue } from '../api/collaboration'
import ProjectSettings from './collaboration/ProjectSettings'
import TaskActions from './collaboration/TaskActions'
import PlanStatus from './collaboration/PlanStatus'
import RallyNotice from './collaboration/RallyNotice'
import ProjectWorkspaceDialog from './collaboration/ProjectWorkspaceDialog'
import { Detail, TodoMenu } from './WorkflowDetails'
import { useProjectWorkflow } from './collaboration/useProjectWorkflow'
import { projectMemberIds, projectStatus, readingMessages, type CollaborationView, type WorkSelection } from './collaboration/model'
import './collaboration/workspace.css'
import './collaboration/layout.css'
import { resetWindowStates, useDraftState, useRememberedState } from '../components/windowState'

/** 「不显示思维链」偏好的本地存储 key：离开圆桌再回来不用重设 */
const HIDE_COT_KEY = 'minis.roundtable.hide-cot'

/** 「显示入场 / 退场」偏好的本地存储 key（默认收起） */
const SHOW_PRESENCE_KEY = 'minis.roundtable.show-presence'

/** 「点击气泡查看原始上下文」偏好的本地存储 key（默认关） */
const BUBBLE_CONTEXT_KEY = 'minis.roundtable.bubble-context'

/** 「发言策略」偏好的本地存储 key（默认轮询）：决定这一轮该谁先开口 */
const SPEAKER_STRATEGY_KEY = 'minis.roundtable.speaker-strategy'

/**
 * 默认收起的状态行：话题的生命周期动作，而不是讨论内容——每开一个话题全桌各来一条。
 *
 * <p>「话题开始」不在这一列：它现在是一条居中的时间标记（见 {@link EventLine}），
 * 每开一轮都要看得见，所以由「⋯」菜单控制不了它。
 */
const NOISY_ACTIONS = ['入场', '退场']

/**
 * 事件种类 → 状态行文案：与后端 {@code roundtable_topic_event} 的 kind 一一对应。
 *
 * <p>有了它，界面就不必靠中文去认「这一行是什么」——{@code kind} 才是协议，文案只是它的显示形态。
 * 后端改了说法，这里跟着改一处即可，分类与布局不受影响（老数据没有 kind，退回文案匹配兜底）。
 */
const EVENT_KIND_ACTION: Record<string, string> = {
  topic_open: '话题开始',
  joined: '入场',
  close: '退场',
  addressed: '被点名',
  prompt: '被要求立刻表态',
  revived: '重新参与讨论',
  reopen: '撤回结束提议，重新决策',
  /* 这两个是最常见的表态（渲染成转化卡片，见 TransitionCard）：把动作名钉在 kind 上，
     不靠从文本里切片——后端改措辞、或理由里带括号，都不会再影响状态的显示 */
  propose_end: '提议结束',
  silent: '转入沉默',
}

/** 默认收起的那几种（「⋯」菜单里可开）；kind 缺失时退回 {@link NOISY_ACTIONS} 的文案匹配 */
const NOISY_KINDS = new Set(['joined', 'close'])

/** 读一个布尔偏好：没存过就用 fallback */
function readFlag(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key)
    return raw === null ? fallback : raw === '1'
  } catch {
    /* 隐私模式等取不到：按默认来 */
    return fallback
  }
}

function writeFlag(key: string, value: boolean) {
  try {
    localStorage.setItem(key, value ? '1' : '0')
  } catch {
    /* 存不了就算了，只是这次不记住 */
  }
}

/** 读一个字符串偏好：没存过就用 fallback */
function readText(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback
  } catch {
    /* 隐私模式等取不到：按默认来 */
    return fallback
  }
}

function writeText(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* 存不了就算了，只是这次不记住 */
  }
}

/**
 * 一条消息的时间文案：气泡上是「时:分」，话题开始那条标记带上「月-日」。
 * 拿不到时间或解析不出来就返回空串，由调用方决定退化成什么。
 */
function fmtTime(iso: string | null | undefined, withDate = false): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  return withDate ? `${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}` : hm
}

type Option = { value: string; label: string }

/**
 * 消息流里的一项（虚拟化用）：落库消息 / 直播分段。
 *
 * <p>只存「轻量描述」而非节点：可见时才渲染出 React 节点，否则等于把整棵树照旧建一遍，虚拟化就白做了。
 */
type FeedItem =
  | { key: string; kind: 'msg'; msg: MaMessage }
  | { key: string; kind: 'issue'; issue: CollaborationIssue }
  | { key: string; kind: 'live'; content: string; trace: TraceSegment[] | null; streaming: boolean }

function errMsg(e: unknown): string {
  if (e instanceof ApiError) {
    const raw = e.body?.trim()
    if (raw) {
      try {
        const j = JSON.parse(raw) as { message?: string }
        if (j.message) return j.message
      } catch {
        /* 非 JSON：原样展示 */
      }
      return raw
    }
    return `请求失败（${e.status}）`
  }
  return e instanceof Error ? e.message : String(e)
}

/** 原始上下文存的是紧凑 JSON（换行全被转义），缩进一下才读得动；解析失败就原样给 */
function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/**
 * 光标前是不是一个还没写完的「@提及」：从光标往回找最近的 @，
 * 只要它与光标之间没有空白，就当作正在补全这个名字。返回 @ 的位置与它后面已敲的片段。
 */
function detectMention(value: string, caret: number): { start: number; query: string } | null {
  const before = value.slice(0, caret)
  const at = before.lastIndexOf('@')
  if (at < 0) return null
  const query = before.slice(at + 1)
  if (/\s/.test(query)) return null
  return { start: at, query }
}

/** 圆桌页（路由 /roundtable）：左侧选会话，右侧是这张圆桌。 */
export default function Roundtable({ initialView = 'discussion' }: { initialView?: CollaborationView }) {
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [modelOptions, setModelOptions] = useState<Option[]>([])
  const [toast, setToast] = useState<{ msg: string; kind?: 'ok' | 'err' } | null>(null)

  const notify = useCallback((msg: string, kind?: 'ok' | 'err') => setToast({ msg, kind }), [])

  const reloadPeers = useCallback(async () => {
    try {
      setPeers(await listMaPeers())
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  const loadModelOptions = useCallback(async () => {
    try {
      const settings = await getSettings()
      setModelOptions([
        { value: '', label: '跟随设置页默认' },
        ...settings.models.map((m) => ({ value: m.id, label: m.name })),
      ])
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  useEffect(() => {
    void reloadPeers()
    void loadModelOptions()
  }, [reloadPeers, loadModelOptions])

  /** 成员 id → 成员：气泡旁的头像与展示名都从这里取（按会话无关，名册是全局的） */
  const peerById = useMemo(() => {
    const map = new Map<string, MaPeer>()
    for (const p of peers) map.set(p.id, p)
    return map
  }, [peers])

  return (
    <div className="h-full flex min-h-0 flex-col">
        <PageBoundary>
          <RoundtableBoard
            initialView={initialView}
            peers={peers}
            peerById={peerById}
            modelOptions={modelOptions}
            notify={notify}
          />
        </PageBoundary>

        <Toast
          message={toast?.msg ?? null}
          kind={toast?.kind === 'err' ? 'error' : 'success'}
          onClose={() => setToast(null)}
        />
    </div>
  )
}

/* ---------------- 圆桌本体 ---------------- */

function RoundtableBoard({
  initialView,
  peers,
  peerById,
  modelOptions,
  notify,
}: {
  initialView: CollaborationView
  peers: MaPeer[]
  peerById: Map<string, MaPeer>
  modelOptions: Option[]
  notify: (msg: string, kind?: 'ok' | 'err') => void
}) {
  const kit = usePageKit()
  const [sessions, setSessions] = useState<MaSession[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const { location, navigate } = useCollaborationNavigation()
  const sessionId = location.issueId || location.taskId
  const [redirectWork, setRedirectWork] = useState(false)
  useEffect(() => setRedirectWork(false), [sessionId])
  const activeProjectId = location.projectId || sessions.find(s => s.id === location.taskId)?.projectId || ''
  const [workspace, setWorkspace] = useState<CollaborationWorkspace>({ issues: [], projectMembers: {} })
  const rootSessions = useMemo(() => sessions.filter(s => !workspace.issues.some(i => i.sessionId === s.id)), [sessions, workspace.issues])
  const currentIssue = workspace.issues.find(i => i.sessionId === location.issueId && i.parentSessionId === location.taskId)
  const taskIssues = useMemo(() => workspace.issues.filter(i => i.parentSessionId === location.taskId), [workspace.issues, location.taskId])
  const archived = workspace.archivedTasks?.includes(location.taskId || '') ?? false
  const mainSession = sessions.find(s => s.id === location.taskId)
  const project = projects.find(p => p.id === activeProjectId)
  const sessionsRequest = useRef(0)
  const openProject = (id: string) => navigate({ projectId: id, taskId: null, issueId: null })
  const openTask = (id: string, projectId = activeProjectId) => navigate({ projectId, taskId: id, issueId: null })
  const openIssue = (issue: CollaborationIssue) => navigate({ projectId: activeProjectId, taskId: issue.parentSessionId, issueId: issue.sessionId })
  const [loadedMessages, setMessages] = useState<MaMessage[]>([])
  const [loadedTopics, setTopics] = useState<MaTopic[]>([])
  const [loadedSessionId, setLoadedSessionId] = useState<string | null>(null)
  const messages = useMemo(() => loadedSessionId === sessionId ? loadedMessages : [], [loadedSessionId, sessionId, loadedMessages])
  const topics = useMemo(() => loadedSessionId === sessionId ? loadedTopics : [], [loadedSessionId, sessionId, loadedTopics])
  const activeSession = useRef(sessionId)
  activeSession.current = sessionId
  const messageRequest = useRef(0)
  const [messageError, setMessageError] = useState<string | null>(null)
  const [sessionsLoading, setSessionsLoading] = useState(true)
  const [sessionsError, setSessionsError] = useState<string | null>(null)
  const [view, setView] = useRememberedState<CollaborationView>(`collaboration:v2:${location.taskId ?? 'new'}:view`, initialView === 'graph' ? 'graph' : 'discussion')
  const [taskId, setTaskId] = useRememberedState<string | null>(`collaboration:${sessionId ?? 'new'}:task`, null)
  const [detail, setDetail] = useState<WorkSelection | null>(null)
  const [selectedEvent, setSelectedEvent] = useState<MaWorkflowEvent | null>(null)
  const [workflowRevision, setWorkflowRevision] = useState(0)
  const workflow = useProjectWorkflow(location.taskId, workflowRevision)
  const selectedTask = workflow.data?.todos.find(t => t.id === taskId) ?? null
  const selection: WorkSelection | null = selectedTask ? { kind: 'todo', id: selectedTask.id } : null
  const [reading, setReading] = useRememberedState('collaboration:reading', true)
  useEffect(() => { setDetail(null); setSelectedEvent(null); setMessageError(null) }, [sessionId])
  useEffect(() => { if (initialView === 'graph' || new URLSearchParams(window.location.hash.split('?')[1]).get('view') === 'graph') setView('graph') }, [initialView, location.taskId, setView])
  const [modelId, setModelId] = useState('')
  const [input, setInput, inputState] = useDraftState(`roundtable:${sessionId ?? `new:${activeProjectId || 'unfiled'}`}:input`, '')
  /**
   * 这张桌子当前那一轮发言的运行时（跑没跑、谁在说、说到哪）：状态在应用级模块仓里，
   * 组件只是订阅者——本页卸载（切会话 / 切 tab / 切路由）不会中断它，回来接着看（见 roundtableLive）。
   */
  const run = useRoundtableRun(sessionId)
  const busy = run?.running ?? false
  const live = run?.live ?? null
  /** 正在发言的会话清单：左侧列表据此打点，一眼看出哪张桌子在跑 */
  const runningSessions = useRunningSessionIds()
  /** 不显示思维链（默认开，「⋯」菜单里可关）：开了之后成员气泡只渲染发言正文 */
  const [hideCot, setHideCot] = useState(() => readFlag(HIDE_COT_KEY, true))
  /** 显示入场 / 退场（默认关）：每开一个话题全桌都会各来一条，噪声太大 */
  const [showPresence, setShowPresence] = useState(() => readFlag(SHOW_PRESENCE_KEY, false))
  /** 点气泡看原始上下文（默认关）：关着时点气泡不做任何事 */
  const [allowContext, setAllowContext] = useState(() => readFlag(BUBBLE_CONTEXT_KEY, false))
  /** 发言策略（默认轮询）：这一轮该谁先开口，随每次发言发给后端（「⋯」菜单里可改） */
  const [speakerStrategy, setSpeakerStrategy] = useState(() =>
    readText(SPEAKER_STRATEGY_KEY, MA_SPEAKER_STRATEGIES[0].value),
  )
  /** 点开一条发言，回看它当时真正发出去的原始上下文 */
  const [contextView, setContextView] = useState<{ title: string; text: string } | null>(null)
  /** 新建会话的选人弹窗开着没 */
  const [createDialog, setCreateDialog] = useState<'task' | 'issue' | null>(null)
  const [conclusionOpen, setConclusionOpen] = useState(false)
  const [mobileTodos, setMobileTodos] = useState(false)
  const scrollPositions = useRef(new Map<string, { top: number; bottom: boolean }>())
  const restoreScrollRef = useRef<number | null>(null)
  const [projectSettings, setProjectSettings] = useState(false)
  const [workspaceDialog, setWorkspaceDialog] = useState(false)
  const [organizeOpen, setOrganizeOpen] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [rewinding, setRewinding] = useState(false)
  const [taskMenuOpen, setTaskMenuOpen] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  /** 会话的消息列表 ajax 回读到位时置位：等下一次提交（layout effect）把视图落到底 */
  const pendingBottomRef = useRef(false)
  /** 上一位「落底」对应的会话 id：区分「换了会话」与「同一会话因新内容重取」 */
  const pinnedSessionRef = useRef<string | null>(null)
  /** 正在补全的 @提及：null 表示没在补；query 是 @ 后面已敲的片段，index 是当前高亮项 */
  const [mention, setMention] = useState<{ start: number; query: string; index: number } | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  /** 候选只来自当前会话；进入议题时使用议题成员，尚未加载时不回退到全局名册。 */
  const mentionMatches = useMemo(() => {
    if (!mention) return []
    const q = mention.query.toLowerCase()
    const memberIds = sessions.find(s => s.id === sessionId)?.memberIds ?? []
    return peers.filter(p => memberIds.includes(p.id) && (!q || p.name.toLowerCase().includes(q)))
  }, [mention, peers, sessions, sessionId])

  /** 高亮项下标：候选变少时收回最后一项，避免越界 */
  const mentionActive = mention ? Math.min(mention.index, mentionMatches.length - 1) : 0

  /** 每次改动输入后同步「@提及」：片段变了就把高亮拉回第一项 */
  const syncMention = (value: string, caret: number) => {
    const hit = detectMention(value, caret)
    if (!hit) {
      setMention(null)
      return
    }
    setMention((prev) => ({
      start: hit.start,
      query: hit.query,
      index: prev && prev.query === hit.query ? prev.index : 0,
    }))
  }

  /** 选中一位成员：把光标前那段 @片段 换成完整的 @名字 + 空格，光标落在空格之后 */
  const applyMention = (peer: MaPeer) => {
    if (!mention) return
    const caret = mention.start + 1 + mention.query.length
    const before = input.slice(0, mention.start)
    setInput(`${before}@${peer.name} ${input.slice(caret)}`)
    setMention(null)
    const pos = before.length + peer.name.length + 2
    requestAnimationFrame(() => {
      const el = inputRef.current
      if (!el) return
      el.focus()
      el.setSelectionRange(pos, pos)
    })
  }

  const reloadSessions = useCallback(async () => {
    const request = ++sessionsRequest.current
    try {
      const [list, projectList, metadata] = await Promise.all([listMaSessions(), listProjects(), collaborationWorkspace()])
      if (request !== sessionsRequest.current) return
      setSessions(list); setProjects(projectList); setWorkspace(metadata); setSessionsError(null)
    } catch (e) {
      if (request === sessionsRequest.current) { setSessionsError(errMsg(e)); notify(errMsg(e), 'err') }
    } finally { if (request === sessionsRequest.current) setSessionsLoading(false) }
  }, [notify])

  const reloadSession = useCallback(
    async (id: string, pinBottom = false, background = false) => {
      const request = ++messageRequest.current
      try {
        const [msgs, tps] = await Promise.all([maMessages(id), maTopics(id)])
        if (activeSession.current !== id || request !== messageRequest.current) return
        setMessages(msgs)
        setTopics(tps)
        setLoadedSessionId(id)
        setMessageError(null)
        /* ajax 回来、消息列表即将替换：请求下一次提交后把视图落到底（进入会话的默认姿态） */
        if (pinBottom) pendingBottomRef.current = true
      } catch (e) {
        if (activeSession.current !== id || request !== messageRequest.current) return
        setMessageError(errMsg(e))
        if (!background) notify(errMsg(e), 'err')
      }
    },
    [notify],
  )

  useEffect(() => {
    void reloadSessions()
  }, [reloadSessions])

  useEffect(() => {
    if (sessionsLoading || !/^#\/(workflow|roundtable)(\?|$)/.test(window.location.hash)) return
    const latest = rootSessions[0]
    if (latest && !location.taskId) window.location.hash = collaborationHref({ projectId: latest.projectId || '', taskId: latest.id, issueId: null }) + (window.location.hash.split('?')[0] === '#/workflow' ? '&view=graph' : '')
  }, [sessionsLoading, rootSessions, location, initialView])

  useEffect(() => {
    if (!location.taskId) return
    const timer = window.setInterval(() => void reloadSessions(), 10000)
    return () => window.clearInterval(timer)
  }, [location.taskId, reloadSessions])

  useEffect(() => {
    if (!mobileTodos) return
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !document.querySelector('[role="dialog"]')) { setMobileTodos(false); event.preventDefault() }
    }
    const media = window.matchMedia('(max-width: 760px)')
    const resized = () => { if (!media.matches) setMobileTodos(false) }
    document.addEventListener('keydown', escape); media.addEventListener('change', resized)
    return () => { document.removeEventListener('keydown', escape); media.removeEventListener('change', resized) }
  }, [mobileTodos])

  /**
   * 重取这张桌子的消息与话题：会话换了要取，这一轮有新内容落库（run.revision 自增）也要取
   * ——已落库的一律以服务端为准，正在说话的那一位则由仓里的直播气泡补在末尾。
   */
  useEffect(() => {
    if (!sessionId) return
    /* 只有「换了会话」才把视图落到底；同一会话因新内容（run.revision）重取时要保持用户当前姿态 */
    const saved = scrollPositions.current.get(sessionId)
    const pinBottom = pinnedSessionRef.current !== sessionId && !saved
    if (saved) restoreScrollRef.current = saved.top
    pinnedSessionRef.current = sessionId
    void reloadSession(sessionId, pinBottom)
  }, [sessionId, reloadSession, run?.revision])

  /* SSE 断开也不重发用户消息：独立回读正本，返回页面与恢复网络时立即补载。议题用自己的会话。 */
  useEffect(() => {
    if (!sessionId) return
    let alive = true, loading = false, queued = false
    let timer: ReturnType<typeof setTimeout>
    const refresh = async () => {
      if (!alive) return
      if (loading) { queued = true; return }
      loading = true
      clearTimeout(timer)
      try { await reloadSession(sessionId, false, true) }
      finally {
        loading = false
        if (alive) timer = setTimeout(refresh, queued ? 0 : document.hidden ? 15000 : 3000)
        queued = false
      }
    }
    const visible = () => { if (!document.hidden) void refresh() }
    const reconnect = () => { void refresh() }
    const unsubscribe = subscribeSessionEvents(event => {
      if (event.kind === AGENT_MESSAGE_SENT && event.sessionId === sessionId) void refresh()
    })
    timer = setTimeout(refresh, 3000)
    window.addEventListener('online', reconnect)
    window.addEventListener('focus', visible)
    document.addEventListener('visibilitychange', visible)
    return () => {
      alive = false; clearTimeout(timer)
      unsubscribe()
      window.removeEventListener('online', reconnect)
      window.removeEventListener('focus', visible)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [sessionId, reloadSession])

  const latestActivity = workflow.data?.events[0]?.id
  useEffect(() => {
    if (sessionId && latestActivity) void reloadSession(sessionId)
  }, [sessionId, latestActivity, reloadSession])

  /* 一轮说完（跑完 / 失败）：会话列表的标题与排序都在此时定下来，刷新一次 */
  useEffect(() => onRoundtableSettled(() => void reloadSessions()), [reloadSessions])

  /* 这一轮失败过：提醒一次就把它从仓里清掉，免得每次回到这张桌子再弹一遍 */
  useEffect(() => {
    if (!run?.error) return
    notify(run.error, 'err')
    clearRunError(sessionId)
  }, [run?.error, sessionId, notify])

  /** 用户是否「看得到最新」：只有贴着底时才自动跟随，否则不把他从讨论里拽走 */
  const [atBottom, setAtBottom] = useState(true)
  const atBottomRef = useRef(true)

  /** 程序性滚动进行中：这期间的 onScroll 不算「用户自己滚」（否则按钮会闪一下） */
  const programmaticRef = useRef(false)

  /** 「回到最新」补帧的帧号：卸载 / 再次点击时取消上一串，避免操作已废弃的滚动 */
  const jumpFrameRef = useRef<number | null>(null)

  /** 消息区滚动：记下用户是否还贴着底（48px 容差） */
  const onMessagesScroll = () => {
    if (programmaticRef.current || loadedSessionId !== sessionId || restoreScrollRef.current !== null) return
    const el = scrollRef.current
    if (!el) return
    const bottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48
    if (sessionId) scrollPositions.current.set(sessionId, { top: el.scrollTop, bottom })
    atBottomRef.current = bottom
    setAtBottom((prev) => (prev === bottom ? prev : bottom))
  }

  /** 回到最新：瞬时跳到最后一条并恢复自动跟随。
      不能走平滑滚动——虚拟列表只渲染视口内的项，滚动途中下方那些「按预估高度铺开」的项才被逐个实测、
      位置不断修正，于是文字重叠成一堆、动画也卡。这里改为瞬时定位，再连补几帧：
      每帧按最新 scrollHeight 重钉一次，把实测后增长的总高吃掉，直到真正落到底。
      补帧期间用 programmaticRef 屏蔽 onScroll，免得中途被判成「用户自己滚」而误弹按钮。 */
  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    if (jumpFrameRef.current !== null) cancelAnimationFrame(jumpFrameRef.current)
    programmaticRef.current = true
    atBottomRef.current = true
    setAtBottom(true)
    /* 虚拟列表的总高由「未渲染项的预估高度」撑着，落底后这些项才被逐个实测、总高随之增长，
       单钉一次往往差一截：连续补帧、每帧按最新 scrollHeight 重钉，直到连续两帧都真的贴底（20 帧封顶兜底） */
    let frames = 0
    let settled = 0
    const settle = () => {
      el.scrollTop = el.scrollHeight
      settled = el.scrollHeight - el.scrollTop - el.clientHeight < 2 ? settled + 1 : 0
      frames += 1
      if (settled < 2 && frames < 20) {
        jumpFrameRef.current = requestAnimationFrame(settle)
      } else {
        jumpFrameRef.current = null
        window.setTimeout(() => {
          programmaticRef.current = false
        }, 120)
      }
    }
    settle()
  }, [])

  /* 卸载时取消「回到最新」的补帧 */
  useEffect(
    () => () => {
      if (jumpFrameRef.current !== null) cancelAnimationFrame(jumpFrameRef.current)
    },
    [],
  )

  /* 上下文浮层：Esc 关闭（点背景关闭在浮层自己身上） */
  useEffect(() => {
    if (!contextView) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setContextView(null)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [contextView])

  useLayoutEffect(() => {
    const saved = sessionId ? scrollPositions.current.get(sessionId) : null
    atBottomRef.current = saved?.bottom ?? true
    setAtBottom(saved?.bottom ?? true)
    setMention(null)
  }, [sessionId])

  /* 话题状态条看的是「此刻这一轮」的快照：仓里有一份就用它（边聊边刷新），
     没有（没在跑 / 刚进来）就用回读来的最后一条 */
  const persistedTopic = topics.length > 0 ? topics[topics.length - 1] : null
  const current = busy ? run?.topic ?? persistedTopic : persistedTopic ?? run?.topic

  /**
   * 有人调了 ask_user：话题置 waiting，全桌就此暂停、等用户这一句话。
   * 状态条上那位成员会显示「等待用户」，这里再把名字解出来给提示条用。
   */
  const awaiting = current?.status === 'waiting'
  const awaitingName =
    current?.status === 'waiting'
      ? (Object.entries(current.states).find(([, s]) => s === 'AWAIT_USER')?.[0] ?? null)
      : null

  const rewindDisabled = archived || busy || preparing || rewinding || currentIssue?.status === 'resolved'
    || !!workflow.data?.advancing || !!workflow.data?.agents.some(a => a.working)
  const rewindTo = useCallback(async (target: MaMessage) => {
    if (!sessionId || rewindDisabled || target.role !== 'user' || target.id < 0) return
    const id = sessionId
    setRewinding(true)
    try {
      await rewindCollaborationSession(id, target.id)
      clearRoundtableRun(id)
      if (activeSession.current !== id) return
      ++messageRequest.current
      setMessages(rows => rows.filter(m => m.id < target.id || ['task_context', 'issue_context', 'issue_open', 'issue_resolved'].includes(m.kind || '')))
      setTopics([])
      setInput(target.content)
      setMention(null)
      setTaskId(null)
      setDetail(null)
      pendingBottomRef.current = true
      await Promise.all([reloadSession(id, true), reloadSessions()])
      if (activeSession.current === id) requestAnimationFrame(() => inputRef.current?.focus())
    } catch (e) { notify(`回退失败：${errMsg(e)}`, 'err') }
    finally { setRewinding(false) }
  }, [sessionId, rewindDisabled, setInput, setTaskId, reloadSession, reloadSessions, notify])

  const lifecycleChanged = async (deleted: boolean) => {
    setProjectSettings(false)
    if (deleted && location.taskId) {
      for (const id of [location.taskId, ...taskIssues.map(i => i.sessionId)]) {
        clearRoundtableRun(id)
        resetWindowStates('roundtable:' + id + ':')
        resetWindowStates('collaboration:' + id + ':')
        resetWindowStates('collaboration:v2:' + id + ':')
      }
      openProject(activeProjectId)
    }
    await reloadSessions()
    setWorkflowRevision(v => v + 1)
  }

  /** 点开一条成员发言：取它当时真正发出去的请求体（system + 历史 + 末尾交代 + 工具面） */
  const openContext = useCallback(
    async (m: MaMessage) => {
      try {
        const r = await maMessageContext(m.id)
        if (!r.context) {
          notify('这条没有留存上下文（人的发言不是模型调用，不产生上下文）', 'err')
          return
        }
        setContextView({ title: m.peerName ?? '成员', text: prettyJson(r.context) })
      } catch (e) {
        notify(errMsg(e), 'err')
      }
    },
    [notify],
  )

  /**
   * 人往桌上说一句：把这一轮交给运行时仓（见 roundtableLive）去跑。
   *
   * <p>本组件只是发起者：这一轮跑起来之后，本页卸载（切会话 / 切 tab / 切路由）都不影响它，
   * 进度、直播气泡与「停止」都从仓里读——所以这里不需要 await，也不需要自己维护在途状态。
   * 已建立计划后默认只交谈；勾选「调整任务」才撤销旧派工并重新规划。
   */
  const send = async () => {
    const body = input.trim()
    const text = selectedTask ? `围绕工作「${selectedTask.title}」（任务 ${selectedTask.id}）：\n${body}` : body
    /* 普通对话可与工作并行，不因 busy 拒绝。 */
    if (archived || !body || preparing || rewinding || sessionsLoading || !mainSession || !sessionId || (location.issueId && !currentIssue) || currentIssue?.status === 'resolved') return
    if (peers.length === 0) {
      notify('还没有协作成员，请先去「智能体」页添加成员', 'err')
      return
    }
    const targetSession = sessionId
    setPreparing(true)
    try {
      inputState.accept('', input)
      setMention(null)
      startRoundtable({
      sessionId: targetSession,
      input: text,
      redirectWork,
      modelId: modelId || undefined,
      speakerStrategy,
      /* 草稿态（还没有会话）时后端会建一个：把它切过去，左侧列表随本轮收尾一起刷新 */
      onSession: (id) => {
        if (id !== targetSession) void reloadSessions()
      },
      })
      setRedirectWork(false)
    } catch (e) {
      notify(errMsg(e), 'err')
    } finally {
      setPreparing(false)
    }
  }

  const stop = async () => {
    /* 停的是「正在跑的那张桌子」：用户可能已经切到别的会话在看，别停错了人 */
    const id = run?.sessionId || sessionId
    if (!id) return
    try {
      await stopRoundtable(id)
      notify('已停止当前协作与执行', 'ok')
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }

  /** 两个显示偏好都只记在本地：不落库、不影响后端生成 */
  const toggleHideCot = (v: boolean) => {
    setHideCot(v)
    writeFlag(HIDE_COT_KEY, v)
  }

  const toggleShowPresence = (v: boolean) => {
    setShowPresence(v)
    writeFlag(SHOW_PRESENCE_KEY, v)
  }

  const toggleAllowContext = (v: boolean) => {
    setAllowContext(v)
    writeFlag(BUBBLE_CONTEXT_KEY, v)
    /* 关掉就把已经摊开的那个窗口收起来，免得留一个「点不开、又还在」的浮层 */
    if (!v) setContextView(null)
  }

  /** 发言策略：也记在本地，但会随每次发言发给后端——它决定的是「谁先开口」，不只是显示 */
  const changeSpeakerStrategy = (v: string) => {
    setSpeakerStrategy(v)
    writeText(SPEAKER_STRATEGY_KEY, v)
  }

  /** 按成员 id 取头像（成员被删则退回首字母兜底） */
  const avatarOf = (peerId: string | null) =>
    peerId ? (peerById.get(peerId)?.avatar ?? null) : null

  /** 按展示名取头像：话题状态按名字记状态，没有 id 可用 */
  const avatarOfName = (name: string) => peers.find((p) => p.name === name)?.avatar ?? null

  /** 某个会话在座的人：memberIds 由后端解好（建会话时没选人 = 全体名册） */
  const membersOf = (memberIds: string[]) => peers.filter((p) => memberIds.includes(p.id))

  /** 当前这张桌子上坐着谁（草稿态还没有会话 = 全体名册）：左侧「在座」按它渲染 */
  const seated = membersOf(
    sessions.find((s) => s.id === sessionId)?.memberIds ?? peers.map((p) => p.id),
  )

  /**
   * 本次提问的气泡：说过的话要当场看得见，而后端把它读回来要等一次往返——
   * 中间这段由它顶着；库里那份一到位（消息回读里出现同内容的 user 行）就撤下，不会重复。
   */
  const optimisticUser = useMemo<MaMessage | null>(() => {
    if (!run?.userText) return null
    if (messages.some((m) => m.role === 'user' && m.content === run.userText)) return null
    return {
      id: -1,
      sessionId: sessionId ?? '',
      topicId: null,
      role: 'user',
      peerId: null,
      peerName: null,
      content: run.userText,
      reasoning: null,
      trace: null,
      createdAt: run.userAt,
    }
  }, [run?.userText, run?.userAt, messages, sessionId])

  /**
   * 消息流的一项（虚拟化用）：落库消息 / 直播分段。key 稳定，跨重排（收起入场行等）也能复用已测高度。
   * 这里只造「轻量描述」，真正的节点在可见时才渲染——否则等于把整棵树照旧建一遍，虚拟化就白做了。
   */
  const feedItems = useMemo<FeedItem[]>(() => {
    const items: FeedItem[] = []
    const issueByMessage = new Map(taskIssues.map(i => [i.messageId, i]))
    const source = reading && hideCot && !allowContext ? readingMessages(messages, new Set(['issue_open', 'issue_resolved', 'task_context', 'issue_context', 'runtime_paused', 'ask_user', AGENT_MESSAGE_SENT])) : messages
    for (const m of source) {
      const issue = !location.issueId ? issueByMessage.get(m.id) : undefined
      if (issue) { items.push({ key: 'issue:' + issue.id, kind: 'issue', issue }); continue }
      if (!showEventLine(m, showPresence, peerById)) continue
      items.push({ key: `m:${m.id}`, kind: 'msg', msg: m })
    }
    if (optimisticUser) items.push({ key: 'optimistic', kind: 'msg', msg: optimisticUser })
    if (live) {
      if (live.segments.length > 0) {
        live.segments.forEach((content, i) =>
          items.push({
            key: `live:${i}`,
            kind: 'live',
            content,
            trace: i === 0 ? live.trace : null,
            streaming: i === live.segments.length - 1,
          }),
        )
      } else {
        items.push({ key: 'live:0', kind: 'live', content: '', trace: live.trace, streaming: true })
      }
    }
    return items
  }, [messages, showPresence, peerById, optimisticUser, live, reading, hideCot, allowContext, taskIssues, location.issueId])

  /* 虚拟化：只渲染视口内（含一段缓冲）的那几条。消息一多，打字卡顿的根因就是整列表每敲一个字都重渲一遍
     （每条都跑 ReactMarkdown），这里把渲染量钉在视口大小上，再把每条 memo 住，输入便与列表长度无关 */
  const virtualizer = useVirtualizer({
    count: feedItems.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 88,
    overscan: 8,
    gap: 16,
    getItemKey: (i) => feedItems[i]?.key ?? i,
  })

  /* 「显示思维链」开关会整体改变每条气泡的高度：清掉已测高度，让它们按新形态重新测量 */
  useEffect(() => {
    virtualizer.measure()
    /* 清缓存后尺寸未变的节点不会再次触发 ResizeObserver，须补测已挂载项。 */
    scrollRef.current?.querySelectorAll<HTMLElement>('[data-index]').forEach(el => {
      virtualizer.resizeItem(Number(el.dataset.index), el.getBoundingClientRect().height)
    })
    if (view === 'discussion' && atBottomRef.current) scrollToBottom()
  }, [hideCot, reading, view, virtualizer, scrollToBottom])

  /* 新内容到达：只有用户本来就在底部时才跟着滚——否则让他自己看。
     讨论滚得太快，无条件到底会把正在围观的人不断拽走（他还想视奸 AGENT 怎么吵）。
     下一帧再钉一次：虚拟项的高度是测量出来的，这一帧写完、下一帧总高才准 */
  useEffect(() => {
    if (!atBottomRef.current) return
    const el = scrollRef.current
    if (!el) return
    const pin = () => {
      el.scrollTop = el.scrollHeight
    }
    pin()
    const id = requestAnimationFrame(pin)
    return () => cancelAnimationFrame(id)
  }, [feedItems, live])

  /* 进入会话：视图默认聚焦到最新一条。
     触发时机落在「消息列表 ajax 回读到位」之后（见 reloadSession 置的 pendingBottomRef），
     这里在浏览器绘制前消费它——用 layout effect 而非 effect，落底先于首帧，免得先闪一下顶部再跳 */
  useLayoutEffect(() => {
    if (restoreScrollRef.current !== null && loadedSessionId === sessionId) {
      const top = restoreScrollRef.current
      restoreScrollRef.current = null
      if (scrollRef.current) scrollRef.current.scrollTop = top
      return
    }
    if (!pendingBottomRef.current) return
    pendingBottomRef.current = false
    /* 会话一换就把「贴底」姿态复位，顺着落底一起摆正 */
    atBottomRef.current = true
    setAtBottom(true)
    /* 空会话没有可聚焦的最新消息；等第一条发言（live/optimisticUser）出现时再落底 */
    if (messages.length === 0 && !live && !optimisticUser) return
    scrollToBottom()
  }, [messages, live, optimisticUser, scrollToBottom, loadedSessionId, sessionId])

  const selectWork = (selected: WorkSelection) => {
    setDetail(selected)
    if (selected.kind === 'todo') setTaskId(selected.id)
    setTaskMenuOpen(false)
  }
  const discuss = (task?: NonNullable<typeof selectedTask>) => {
    if (task) setTaskId(task.id)
    setMobileTodos(false)
    setView('discussion')
    requestAnimationFrame(() => inputRef.current?.focus())
  }
  const status = archived ? '已归档 · 只读' : projectStatus(workflow.data, busy, awaiting)
  const invalidLocation = !sessionsLoading && !sessionsError && !!location.taskId && (!mainSession
    || workspace.issues.some(i => i.sessionId === location.taskId)
    || (location.projectId && mainSession.projectId !== location.projectId)
    || (!!location.issueId && !currentIssue))
  return (
    <>
    <div className="collab-workspace collab-v2">
      {invalidLocation ? <div className="collab-page-empty"><h1>这个会话或议题不属于当前位置</h1><button className="ui-btn ui-btn--outline" onClick={() => openProject(activeProjectId)}>返回项目</button></div> : !location.taskId ? <ProjectPages key={activeProjectId} projectId={activeProjectId} projects={projects} sessions={rootSessions} peers={peers} configuredMembers={workspace.projectMembers} workspaceBindings={workspace.workspaceBindings} archivedTasks={workspace.archivedTasks} plans={workspace.plans} runningSessions={runningSessions} loading={sessionsLoading} error={sessionsError} onProject={openProject} onTask={s => openTask(s.id, s.projectId || '')} onNewTask={() => setCreateDialog('task')} onReload={reloadSessions} /> : <>
        <header className="collab-session-header"><nav aria-label="协作位置"><button onClick={() => openProject('')}>协作</button><AppIcon name="chevron-right" /><button onClick={() => openProject(activeProjectId)}>{project?.name || '未归档记录'}</button><AppIcon name="chevron-right" /><span>{mainSession?.title || '协作会话'}</span></nav><div className="collab-session-title"><div className="collab-session-heading"><h1>{mainSession?.title || '协作会话'}</h1><span className="collab-session-status" role="status">{status}</span></div><div className="collab-session-actions">{mainSession?.projectId && <button className="ui-btn ui-btn--quiet collab-project-docs-link" aria-label="项目资料" title="查看项目资料与进度" onClick={() => { window.location.hash = collaborationHref({ projectId: activeProjectId, taskId: null, issueId: null }) + '&section=knowledge' }}><AppIcon name="doc" /><span>项目资料</span></button>}{mainSession?.projectId && <button className="ui-btn ui-btn--outline collab-workspace-link" aria-label={workspace.workspaceBindings?.some(b => b.projectId === mainSession.projectId && b.enabled) ? '工作目录' : '设置工作目录'} title="查看或设置项目工作目录" disabled={busy || !!workflow.data?.agents.some(a => a.working)} onClick={() => setWorkspaceDialog(true)}><AppIcon name="folder" /><span>{workspace.workspaceBindings?.some(b => b.projectId === mainSession.projectId && b.enabled) ? '工作目录' : '设置工作目录'}</span></button>}<button className="ui-btn ui-btn--outline" onClick={() => setCreateDialog('issue')} disabled={archived || sessionsLoading || !mainSession || !!currentIssue}><AppIcon name="branch" />发起议题</button><button className="ui-btn ui-btn--quiet collab-mobile-todo" aria-expanded={mobileTodos} onClick={() => setMobileTodos(v => !v)}><AppIcon name="todo" />工作 {workflow.data?.todos.filter(t => ['pending', 'doing', 'blocked', 'reviewing', 'checking'].includes(t.status)).length || 0}</button><button className="ui-btn ui-btn--quiet collab-session-settings" aria-label="会话设置与成员" title="会话设置与成员" onClick={() => setProjectSettings(true)}><AppIcon name="sliders" /></button></div></div></header>
        {sessionsError && <div className="collab-error" role="alert"><span>{sessionsError}</span><button className="ui-btn ui-btn--outline" onClick={() => void reloadSessions()}>重试</button></div>}
        {archived && mainSession && <div className="collab-archived-notice"><p>会话已归档，消息、工作条目和议题仍可查阅。</p><TaskActions id={mainSession.id} title={mainSession.title || '未命名会话'} archived busy={false} onChanged={lifecycleChanged} /></div>}
        {workspace.plans?.filter(p => p.sessionId === location.taskId).map(plan => <PlanStatus key={plan.sessionId} plan={plan} peers={peers} />)}
        <div className={'collab-session-grid' + (mobileTodos ? ' show-mobile-todos' : '')}>
          <div className="collab-main" inert={mobileTodos}>
            <div className="collab-viewbar"><nav role="tablist" aria-label="任务视图">{([['discussion', '消息'], ['graph', '协作图']] as const).map(([value, label]) => <button type="button" role="tab" id={'collab-tab-' + value} aria-controls={'collab-panel-' + value} aria-selected={view === value} key={value} onClick={() => setView(value)}>{label}</button>)}</nav>{view === 'discussion' && <div className="collab-reading-tools"><button className="ui-btn ui-btn--quiet" aria-pressed={reading} onClick={() => setReading(v => !v)}>{reading ? '完整记录' : '连续阅读'}</button></div>}</div>
            {location.issueId && <div className="collab-issue-header"><button className="ui-btn ui-btn--quiet" onClick={() => location.taskId && openTask(location.taskId)}><AppIcon name="arrow-left" />返回主会话</button><div><span className="collab-secondary">议题 · {currentIssue?.status === 'resolved' ? '已形成结论' : '独立讨论'}</span><h2>{currentIssue?.title || '正在载入议题…'}</h2></div>{!archived && currentIssue?.status === 'open' && <button className="ui-btn ui-btn--outline" disabled={busy} onClick={() => setConclusionOpen(true)}>提交结论</button>}</div>}
            {view === 'graph' && <GraphActivity data={workflow.data} issues={taskIssues} onIssue={issue => { setView('discussion'); openIssue(issue) }} onEvent={event => { setSelectedEvent(event); if (event.taskId) setTaskId(event.taskId) }} />}
            {view === 'graph' && !workflow.data && <div className="collab-loading">{workflow.error || '正在载入协作关系…'}</div>}
            {workflow.data && <div id="collab-panel-graph" role="tabpanel" aria-labelledby="collab-tab-graph" className="collab-content collab-content--graph" style={{ display: view === 'graph' ? 'flex' : 'none' }}><ProjectGraph key={location.taskId} data={workflow.data} peers={peers} selected={selection} event={selectedEvent} active={view === 'graph'} stale={!!workflow.error} onSelect={selectWork} onDiscuss={() => discuss()} /></div>}
        {/* 话题状态条：内容全部挂在当前话题上——还没开谈时整条不出现，别留一条空横栏 */}
        {current && view === 'discussion' && (
          <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-4 py-2.5">
            <div className="flex flex-wrap items-center gap-1">
              {Object.entries(current.states).map(([name, state]) => {
                /* 正在说话的那位高亮一下：状态条也要看得出「此刻是谁的回合」（accent 填充 = 激活态） */
                const speaking = live?.peerName === name
                return (
                  <kit.Badge key={name} tone={speaking ? 'accent' : 'default'}>
                    <PeerAvatar
                      name={name}
                      avatar={avatarOfName(name)}
                      size="h-4 w-4"
                      textClass="text-[12px]"
                    />
                    <span className="ml-1">
                      {name} · {MA_STATE_LABELS[state] ?? state}
                      {speaking ? ' · 正在说' : ''}
                      {/* 执行状态（二维状态的第二维）：非「就绪」时才补一句，免得状态条被噪声塞满 */}
                      {(() => {
                        const exec = current.executions?.[name]
                        return exec && exec !== 'READY'
                          ? ` · ${MA_EXEC_LABELS[exec] ?? exec}`
                          : ''
                      })()}
                    </span>
                  </kit.Badge>
                )
              })}
            </div>
          </div>
        )}

        {/* 消息：这一块自己滚（min-h-0 才真的滚得起来），把输入框留给容器底部 */}
        <div
          id="collab-panel-discussion"
          role="tabpanel"
          aria-labelledby="collab-tab-discussion"
          ref={scrollRef}
          onScroll={onMessagesScroll}
          className="scrollbar-slim min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-4"
          style={{ display: view === 'discussion' ? undefined : 'none' }}
        >
          {sessionId && loadedSessionId !== sessionId && !messageError && <div className="collab-loading" role="status"><AppIcon name="loading" className="motion-safe:animate-spin" />正在载入讨论记录…</div>}
          {messageError && <div className="collab-error" role="alert"><span>讨论记录加载失败：{messageError}</span><button type="button" className={btnOutline()} onClick={() => sessionId && void reloadSession(sessionId)}>重试讨论</button></div>}
          {(!sessionId || loadedSessionId === sessionId) && messages.length === 0 && !live && !optimisticUser && (
            <kit.Empty>描述一个目标，让成员一起讨论并推进</kit.Empty>
          )}
          {/* 虚拟列表：外层固定为「总高度」的定位容器，只有视口内（含缓冲）的项被真正渲染 */}
          <div style={{ position: 'relative', height: virtualizer.getTotalSize(), width: '100%' }}>
            {virtualizer.getVirtualItems().map((vi) => {
              const item = feedItems[vi.index]
              if (!item) return null
              return (
                <div
                  key={item.key}
                  data-index={vi.index}
                  ref={virtualizer.measureElement}
                  style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    width: '100%',
                    transform: `translateY(${vi.start}px)`,
                  }}
                >
                  {item.kind === 'issue' ? <IssueCard issue={item.issue} members={(sessions.find(s => s.id === item.issue.sessionId)?.memberIds || []).map(id => peerById.get(id)?.name || id)} running={runningSessions.includes(item.issue.sessionId)} onOpen={() => openIssue(item.issue)} /> : item.kind === 'msg' ? (
                    <MessageItem
                      msg={item.msg}
                      avatar={avatarOf(item.msg.peerId)}
                      showCot={!hideCot}
                      peerById={peerById}
                      /* 只有落库的成员发言才有原始上下文可看（人的话不是一次模型调用）；
                         这个入口也由「⋯」菜单里的开关控制，默认关 */
                      allowContext={allowContext}
                      onOpenContext={openContext}
                      onRewind={rewindTo}
                      rewindDisabled={rewindDisabled}
                    />
                  ) : (
                    /* 正在说话的那一位：一次发言可能分好几段（边说边想），一段一个气泡；
                       思考链挂在第一段上——与落库后回读到的形状一致，刷新前后不跳 */
                    <Bubble
                      role="peer"
                      name={live?.peerName ?? ''}
                      avatar={avatarOf(live?.peerId ?? null)}
                      content={item.content}
                      trace={item.trace}
                      createdAt={live?.at}
                      streaming={item.streaming}
                      showCot={!hideCot}
                    />
                  )}
                </div>
              )
            })}
          </div>

          {/* 「回到最新」：新内容到达时不再无条件把用户拽到底——他在围观讨论时给一个按钮即可 */}
          {!atBottom && (
            <div className="sticky bottom-0 flex justify-end pr-1 pt-2">
              <button
                type="button"
                onClick={scrollToBottom}
                title="回到最新"
                aria-label="回到最新"
                className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-black text-white shadow-lg transition hover:bg-black/80"
              >
                <AppIcon name="arrow-down" className="h-4 w-4" />
              </button>
            </div>
          )}
        </div>

        {/* 等待用户：某位成员请了用户表态（话题置 waiting），全桌暂停——这条提示贴在输入框正上方，
            免得「现在轮到你」只藏在状态条那个「等待用户」里。正在跑一轮（busy）时不出现，
            因为此刻还轮不到人说话 */}
        {!archived && view === 'discussion' && awaiting && !busy && currentIssue?.status !== 'resolved' && (
          <div className="flex shrink-0 flex-wrap items-center gap-x-2 gap-y-0.5 border-t border-line bg-accent-soft px-4 py-2">
            <span
              className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-accent"
              aria-hidden="true"
            />
            <p className="text-sm font-medium text-fg">
              {awaitingName ? `${awaitingName} 在等你表态` : '圆桌在等你表态'}
            </p>
            <p className="text-xs text-muted">请回复你的决定；要继续工作，请明确提出恢复或调整要求。</p>
          </div>
        )}

        {/* 输入：钉在容器底部，永远看得见。
            输入框与发送按钮包在同一个边框里，并用 items-center 对齐——两者天然在同一条水平线上 */}
        {!archived && view === 'discussion' && currentIssue?.status !== 'resolved' && <div className="collab-composer shrink-0 border-t border-line px-4 py-3">
          <div className="collab-compose-scope"><span><AppIcon name="reply" />{selectedTask ? `围绕工作：${selectedTask.title}` : currentIssue ? '发往议题：' + currentIssue.title : '发往主会话'}</span>{selectedTask && <button type="button" className="ui-btn ui-btn--quiet ui-btn--sm" onClick={() => setTaskId(null)}>清除待办引用</button>}{busy && <span className="collab-secondary">{live?.peerName ? `${live.peerName} 正在发言` : '成员正在协作'}</span>}</div>
          {!currentIssue && <label className="collab-compose-intent" title="勾选后，本条消息会停止当前执行并交由主控重新规划">
            <input type="checkbox" checked={redirectWork} onChange={e => setRedirectWork(e.target.checked)} />
            调整任务
            <span>{redirectWork ? '停止当前执行，按本条要求重新规划' : '普通交流不中断工作；明确的恢复、调整或停止指令由成员识别后执行'}</span>
          </label>}
          <div className="collab-compose-box relative flex items-center gap-2 rounded-2xl border border-line bg-bg py-1.5 pl-4 pr-1.5 transition hover:border-line-strong focus-within:border-accent">
            {mention && mentionMatches.length > 0 && (
              <MentionMenu
                peers={mentionMatches}
                activeIndex={mentionActive}
                onPick={applyMention}
              />
            )}
            <textarea
              ref={inputRef}
              className="scrollbar-slim max-h-40 min-h-[40px] w-full resize-none bg-transparent py-2 text-base leading-relaxed text-fg outline-none placeholder:text-hint"
              rows={2}
              placeholder={redirectWork ? '描述新的工作要求或调整…' : selectedTask ? '询问这项工作的进度、问题或验收结果…' : currentIssue ? '围绕议题补充意见…（@名字 定向沟通）' : '输入消息或本次目标，@成员定向沟通…'}
              value={input}
              onChange={(e) => {
                setInput(e.target.value)
                syncMention(e.target.value, e.target.selectionStart ?? e.target.value.length)
              }}
              /* 焦点离开输入框就收起补全菜单（点菜单项已在 mousedown 里阻止了失焦） */
              onBlur={() => setMention(null)}
              onKeyDown={(e) => {
                /* 输入法组词中（中文候选）不接管任何按键：回车是选字，不是补全 / 发送 */
                if (e.nativeEvent.isComposing) return
                if (mentionMatches.length > 0) {
                  if (e.key === 'ArrowDown') {
                    e.preventDefault()
                    setMention((m) =>
                      m ? { ...m, index: (m.index + 1) % mentionMatches.length } : m,
                    )
                    return
                  }
                  if (e.key === 'ArrowUp') {
                    e.preventDefault()
                    setMention((m) =>
                      m
                        ? { ...m, index: (m.index - 1 + mentionMatches.length) % mentionMatches.length }
                        : m,
                    )
                    return
                  }
                  if (e.key === 'Enter' || e.key === 'Tab') {
                    e.preventDefault()
                    applyMention(mentionMatches[mentionActive])
                    return
                  }
                  if (e.key === 'Escape') {
                    e.preventDefault()
                    setMention(null)
                    return
                  }
                }
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  void send()
                }
              }}
            />
            <DisplayMenu
              hideCot={hideCot}
              onToggleCot={toggleHideCot}
              showPresence={showPresence}
              onTogglePresence={toggleShowPresence}
              allowContext={allowContext}
              onToggleContext={toggleAllowContext}
              speakerStrategy={speakerStrategy}
              onChangeSpeakerStrategy={changeSpeakerStrategy}
            />
            {/* 明确停止同时作用于执行与对话；普通发送保持独立。 */}
            {(busy || workflow.data?.agents.some(agent => agent.working)) && (
              <button
                type="button"
                onClick={() => void stop()}
                title="停止协作（包括执行与发言）"
                aria-label="停止协作"
                className="ui-btn ui-btn--outline h-11 w-11 shrink-0 [--ui-btn-px:0px] [--ui-btn-py:0px]"
              >
                <AppIcon name="stop" className="h-3.5 w-3.5" />
              </button>
            )}
            {/* 发送：永远可用——讨论进行中也能随时插话补充（那会打断当前发言并排进新一轮） */}
            <button
              type="button"
              onClick={() => void send()}
              title="发送"
              aria-label="发送"
              disabled={preparing || rewinding || sessionsLoading}
              className="ui-btn ui-btn--primary h-11 w-11 shrink-0 [--ui-btn-px:0px] [--ui-btn-py:0px]"
            >
              <AppIcon name="send" className="h-4 w-4" />
            </button>
          </div>
        </div>}
        {view === 'discussion' && currentIssue?.status === 'resolved' && <div className="collab-resolved"><AppIcon name="check" /><div><strong>议题结论已带回主会话</strong><p>{currentIssue.conclusion}</p></div><button className="ui-btn ui-btn--outline" onClick={() => location.taskId && openTask(location.taskId)}>回到主会话继续</button></div>}
          </div>
          <TodoPanel readOnly={archived} data={workflow.data} loading={workflow.loading} error={workflow.error} onRetry={() => setWorkflowRevision(v => v + 1)} onSelect={id => selectWork({ kind: 'todo', id })} onDiscuss={discuss} />
        </div>
      </>}
    </div>

      {projectSettings && <ProjectSettings session={sessions.find(s => s.id === sessionId)} issue={!!currentIssue} hasIssues={taskIssues.length > 0} archived={archived} members={seated} modelId={modelId} models={modelOptions} busy={busy || !!workflow.data?.agents.some(a => a.working)} onModel={setModelId} onOrganize={() => { setProjectSettings(false); setOrganizeOpen(true) }} onLifecycle={lifecycleChanged} onClose={() => setProjectSettings(false)} />}
      {organizeOpen && sessionId && <ProjectDialog organize initialName={sessions.find(s => s.id === sessionId)?.title || '协作项目'} projects={projects} onClose={() => setOrganizeOpen(false)} onConfirm={async choice => { await organizeSessionProject(sessionId, { name: choice.name, kind: choice.kind, note: choice.note, projectId: choice.projectId }); await reloadSessions() }} />}
      {detail && workflow.data && <FloatWindow title={detail.kind === 'todo' ? '工作详情与执行记录' : '成员在当前项目中的活动'} onClose={() => { setDetail(null); setTaskMenuOpen(false) }} width={detail.kind === 'todo' ? 1120 : 600} expandable={detail.kind === 'todo'} contentClassName={detail.kind === 'todo' ? 'work-review-window' : ''}><div className="collab-detail"><Detail key={`${sessionId}:${detail.kind}:${detail.id}`} sel={detail} data={workflow.data} onSelect={selectWork} agentOf={id => workflow.data?.agents.find(a => a.id === id)} todoOf={id => workflow.data?.todos.find(t => t.id === id)} />{!archived && detail.kind === 'todo' && workflow.data.todos.find(t => t.id === detail.id) && <div className="collab-detail-actions"><button type="button" className={btnOutline()} onClick={() => { const t = workflow.data?.todos.find(t => t.id === detail.id); if (t) discuss(t); setDetail(null) }}><AppIcon name="chat" />讨论这项工作</button><div className="relative"><button type="button" className={btnOutline()} onClick={() => setTaskMenuOpen(v => !v)} aria-expanded={taskMenuOpen}><AppIcon name="more" />工作操作</button>{taskMenuOpen && <TodoMenu t={workflow.data.todos.find(t => t.id === detail.id)!} onClose={() => { setTaskMenuOpen(false); setWorkflowRevision(v => v + 1) }} />}</div></div>}</div></FloatWindow>}

      {/* 新建会话的选人弹窗 */}
      {workspaceDialog && mainSession?.projectId && <ProjectWorkspaceDialog projectId={mainSession.projectId} projectName={project?.name || '当前项目'} binding={workspace.workspaceBindings?.find(b => b.projectId === mainSession.projectId)} onSaved={reloadSessions} onClose={() => setWorkspaceDialog(false)} />}
      {createDialog === 'task' && <CollaborationDialog chooseCoordinator title="新建协作会话" label="这次希望推进什么？" peers={peers} initialMembers={projectMemberIds(activeProjectId, workspace.projectMembers, rootSessions, peers)} submitLabel="创建会话" onClose={() => setCreateDialog(null)} onSubmit={async (name, _note, ids, coordinator) => { const result = await createCollaborationTask(activeProjectId, name, ids, coordinator); await reloadSessions(); openTask(result.sessionId) }} />}
      {createDialog === 'issue' && mainSession && <CollaborationDialog title="发起议题" label="需要一起解决什么问题？" peers={peers.filter(p => mainSession.memberIds.includes(p.id))} initialMembers={mainSession.memberIds} submitLabel="创建并讨论" onClose={() => setCreateDialog(null)} onSubmit={async (name, _note, ids) => { const issue = await createCollaborationIssue(mainSession.id, name, ids); await reloadSessions(); openIssue(issue); startRoundtable({ sessionId: issue.sessionId, input: name, modelId: modelId || undefined, speakerStrategy }) }} />}
      {conclusionOpen && currentIssue && <ConclusionDialog onClose={() => setConclusionOpen(false)} onSubmit={async text => { await concludeCollaborationIssue(currentIssue.id, text); await reloadSessions(); openTask(currentIssue.parentSessionId) }} />}

      {/* 原始上下文浮层：点开一条发言时打开；Esc 或点背景关闭 */}
      {contextView && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 p-6"
          onClick={() => setContextView(null)}
        >
          <Card
            pad="none"
            className="flex max-h-[82vh] w-full max-w-3xl flex-col shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <CardHead
              title={`原始上下文 · ${contextView.title}`}
              meta={
                <button type="button" className={miniBtn} onClick={() => setContextView(null)}>
                  关闭
                </button>
              }
            />
            <p className="border-b border-line px-4 py-2 text-xs text-hint">
              这是它这次发言当时真正发出去的那一份：system（身份 / 协作规程 / 局面）、
              共享话题历史、末尾的发言权交代，以及挂了哪些工具。
            </p>
            <pre className="scrollbar-slim flex-1 overflow-auto px-4 py-3 font-mono text-xs leading-relaxed whitespace-pre-wrap break-all text-muted">
              {contextView.text}
            </pre>
          </Card>
        </div>
      )}
    </>
  )
}

/**
 * 一段思考的折叠块：标题一行 + 可滚动的正文。
 * 流式生成中默认展开、随增量贴底（始终看到最新思考）；历史回放默认收起，点标题展开。
 */
const CotBlock = memo(function CotBlock({
  label,
  text,
  code,
  defaultOpen = false,
  live = false,
}: {
  label: string
  /** 思考正文；与 code 二选一 */
  text?: string
  /** 以代码块呈现的内容（工具调用参数） */
  code?: string
  defaultOpen?: boolean
  /** 是否处于流式生成中（贴底跟随） */
  live?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  const bodyRef = useRef<HTMLDivElement>(null)
  /* 贴底用 layout effect：浏览器绘制前就到底，增量再快也不会先露一帧旧内容 */
  useLayoutEffect(() => {
    const el = bodyRef.current
    if (!el || !live || !open) return
    el.scrollTop = el.scrollHeight
  }, [text, code, live, open])
  return (
    <div
      className="mb-2 rounded-lg border border-line bg-fg/[0.02]"
      /* 气泡整个是「点开看当时的上下文」的热区：块内点击不能再冒泡出去，否则一开合就弹窗 */
      onClick={(e) => e.stopPropagation()}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full cursor-pointer items-center gap-1.5 px-3 py-1.5 text-xs text-hint transition hover:text-muted"
      >
        <AppIcon name="chevron-right" className={`h-3 w-3 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} />
        <span className="min-w-0 flex-1 truncate text-left">{label}</span>
      </button>
      {open && (
        <div
          ref={bodyRef}
          className="scrollbar-slim max-h-64 overflow-y-auto border-t border-line px-3 py-2 text-sm leading-relaxed text-muted"
        >
          {code !== undefined ? (
            <pre className="whitespace-pre-wrap break-all font-mono text-sm">{code}</pre>
          ) : (
            <div className="whitespace-pre-wrap break-words">{text ?? ''}</div>
          )}
        </div>
      )}
    </div>
  )
})

/**
 * 一位成员这次发言的思考过程：有 trace 就逐段渲染（思考 / 调用工具 - 工具名），
 * 没有 trace（流式途中，或非推理模型）时退回单块「思考」；两者都没有则什么都不渲染。
 */
const CotBlocks = memo(function CotBlocks({
  reasoning,
  trace,
  live,
}: {
  reasoning?: string | null
  trace?: TraceSegment[] | null
  live: boolean
}) {
  const segs = trace ?? []
  const text = reasoning && reasoning.trim() ? reasoning : ''
  if (segs.length === 0 && !text) return null
  if (segs.length === 0) {
    return <CotBlock label="思考" text={text} defaultOpen={live} live={live} />
  }
  return (
    <>
      {segs.map((seg, i) => {
        const isTool = seg.type === 'tool'
        return (
          <CotBlock
            key={i}
            label={isTool ? `调用工具 - ${seg.name || '未知工具'}` : '思考'}
            text={isTool ? undefined : (seg.text ?? '')}
            code={isTool ? prettyJson(seg.args ?? '') : undefined}
            defaultOpen={live}
            live={live}
          />
        )
      })}
    </>
  )
})

/** 菜单里的一行开关：整行即开关（与首页对话页的「思维链（CoT）」同一个手感） */
function MenuSwitch({
  label,
  hint,
  on,
  onToggle,
}: {
  label: string
  hint: string
  on: boolean
  onToggle: (v: boolean) => void
}) {
  return (
    <>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        onClick={() => onToggle(!on)}
        className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-fg/5 ${
          on ? 'text-fg' : 'text-muted hover:text-fg'
        }`}
      >
        <span className="flex-1 truncate">{label}</span>
        <span
          className={`relative h-[18px] w-8 shrink-0 rounded-full transition-colors ${
            on ? 'bg-accent' : 'bg-fg/20'
          }`}
        >
          <span
            className={`absolute top-0.5 h-3.5 w-3.5 rounded-full shadow-sm transition-all ${
              on ? 'left-4 bg-accent-fg' : 'left-0.5 bg-bg'
            }`}
          />
        </span>
      </button>
      <p className="px-2 pb-1.5 pt-1 text-xs leading-relaxed text-hint">{hint}</p>
    </>
  )
}

/**
 * 菜单里的一组单选：一行一项、左侧圆点标记选中（开关表达不了「多选一」，
 * 竖排则不会因为选项名长而被截断）。
 */
function MenuChoice({
  options,
  value,
  onChange,
}: {
  options: { value: string; label: string }[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <div role="radiogroup" className="px-1">
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(o.value)}
            className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-fg/5 ${
              active ? 'font-medium text-fg' : 'text-muted hover:text-fg'
            }`}
          >
            <span
              className={`h-3 w-3 shrink-0 rounded-full border transition ${
                active ? 'border-accent bg-accent' : 'border-line-strong'
              }`}
            />
            <span className="flex-1 truncate">{o.label}</span>
          </button>
        )
      })}
    </div>
  )
}

/**
 * 「⋯」菜单（在输入框那一行的发送按钮左侧）：点外部或 Esc 收起。
 * 面板向上展开——它在页面底部，往下没有空间。
 */
function DisplayMenu({
  hideCot,
  onToggleCot,
  showPresence,
  onTogglePresence,
  allowContext,
  onToggleContext,
  speakerStrategy,
  onChangeSpeakerStrategy,
}: {
  hideCot: boolean
  onToggleCot: (v: boolean) => void
  showPresence: boolean
  onTogglePresence: (v: boolean) => void
  allowContext: boolean
  onToggleContext: (v: boolean) => void
  speakerStrategy: string
  onChangeSpeakerStrategy: (v: string) => void
}) {
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={boxRef} className="relative shrink-0">
      <button
        type="button"
        aria-label="显示设置"
        aria-expanded={open}
        title="显示设置"
        onClick={() => setOpen((v) => !v)}
        className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full text-muted transition hover:bg-fg/10 hover:text-fg"
      >
        <AppIcon name="more" className="h-4 w-4" />
      </button>

      {open && (
        <div className="ui-card ui-surface absolute bottom-[calc(100%+8px)] right-0 z-30 w-[252px]">
          <div className="ui-card__in p-1.5">
          <p className="ui-label px-2 pb-1 pt-1.5">显示</p>
          <MenuSwitch
            label="不显示思维链"
            hint="成员气泡只显示发言正文，思考过程与工具调用都收起来。"
            on={hideCot}
            onToggle={onToggleCot}
          />
          <MenuSwitch
            label="显示入场 / 退场"
            hint="默认收起：每开一个话题，全桌都会各来一条。话题开始那条时间标记不受这个开关影响。"
            on={showPresence}
            onToggle={onTogglePresence}
          />
          <MenuSwitch
            label="点击气泡查看原始上下文"
            hint="开着时点一条成员发言，能看到它当时真正发出去的那一份请求体。"
            on={allowContext}
            onToggle={onToggleContext}
          />
          <div className="my-1.5 border-t border-line" />
          <p className="ui-label px-2 pb-1 pt-0.5">发言策略</p>
          <MenuChoice
            options={MA_SPEAKER_STRATEGIES}
            value={speakerStrategy}
            onChange={onChangeSpeakerStrategy}
          />
          <p className="px-2 pb-1.5 pt-1.5 text-xs leading-relaxed text-hint">
            这一轮该谁先开口：轮询＝按名册顺序依次交接；随机＝每次掷骰子；发言欲望优先＝每位成员说完
            报一个 0~10 的欲望，高的先开口。下一次发言生效。
          </p>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * 输入框里敲「@」时弹出的成员补全菜单：向上展开——输入框钉在容器底部，往下没有空间。
 * 列出名册里的成员（已按 @ 后面敲的片段过滤好），上下键移动、回车 / Tab 选中、Esc 收起，
 * 也可以直接点。选中后补成完整的 @名字——后端就是靠这个名字把发言权先交给它。
 */
function MentionMenu({
  peers,
  activeIndex,
  onPick,
}: {
  peers: MaPeer[]
  activeIndex: number
  onPick: (peer: MaPeer) => void
}) {
  return (
    <div className="ui-card ui-surface absolute bottom-[calc(100%+8px)] left-0 z-30 w-[280px] overflow-hidden">
      <div className="ui-card__in overflow-hidden">
      <p className="ui-label px-3 pb-1 pt-2">提及成员</p>
      <div
        role="listbox"
        aria-label="提及成员"
        className="scrollbar-slim max-h-60 overflow-y-auto p-1.5 pt-0"
      >
        {peers.map((p, i) => (
          <button
            key={p.id}
            type="button"
            role="option"
            aria-selected={i === activeIndex}
            /* 按下鼠标不夺走输入框焦点：否则 blur 会先把菜单收掉，点击就落空了 */
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => onPick(p)}
            className={`flex w-full cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-left transition ${
              i === activeIndex ? 'bg-fg/5 text-fg' : 'text-muted hover:bg-fg/5 hover:text-fg'
            }`}
          >
            <PeerAvatar name={p.name} avatar={p.avatar} size="h-7 w-7" textClass="text-[12px]" />
            <span className="flex-1 truncate text-sm">{p.name}</span>
          </button>
        ))}
      </div>
      </div>
    </div>
  )
}

/** 会话行里最多画几个头像，多出来的用 +N 表示（左栏窄，画不下太多） */

/** 成员气泡里头像那一格的尺寸：状态行要用同样的宽度空出这一格，才能和气泡正文左对齐 */
const BUBBLE_AVATAR_SIZE = 'h-14 w-14'

/**
 * 成员气泡的最大宽度：它里面是模型写的长段落，铺满整条消息区时每行会拖得很长、很累眼。
 * 560px 在 13px 字号下约 43 个汉字一行——长句有地方断行，又不至于窄到频繁折行。
 *
 * <p>只约束成员的气泡：人的话通常很短，86% 那个上限已经够用。
 * 它和外面那层 86% 是两级上限，各自管「窗口很宽」与「窗口很窄」两段。
 */
const BUBBLE_MAX_W = 'max-w-[560px]'

/** 流式生成中、正文还没到时的占位：三点依次跳动（比写「（没有正文）」自然得多） */
const TypingDots = memo(function TypingDots() {
  return (
    <span className="flex items-center gap-1 py-1" role="status" aria-label="正在生成">
      {[0, 150, 300].map((delay) => (
        <span
          key={delay}
          className="h-1.5 w-1.5 animate-bounce rounded-full bg-hint"
          style={{ animationDelay: `${delay}ms` }}
        />
      ))}
    </span>
  )
})

/** 一条气泡：人的话靠右、成员的发言靠左（左边一列是它的头像）。
 *  传了 onClick 就表示这条发言留了原始上下文，点开可回看。 */
function Bubble({
  role,
  name,
  avatar,
  content,
  reasoning,
  trace,
  streaming,
  createdAt,
  showCot,
  onClick,
  onRewind,
  rewindDisabled = false,
}: {
  role: string
  name: string | null
  /** 发言人头像（data URL）；null 时用名字首字兜底 */
  avatar?: string | null
  content: string
  /** 这一轮模型的思维链纯文本；没有 trace 时用它兜底渲染 */
  reasoning?: string | null
  /** 结构化思考分段（思考 / 调用工具）；有它时按段渲染 */
  trace?: TraceSegment[] | null
  streaming?: boolean
  /** 这条消息的时间（ISO）；流式途中用发言开始的时刻顶上 */
  createdAt?: string | null
  /** 是否渲染思维链（「⋯」菜单里那个开关；关掉后气泡只留发言正文） */
  showCot: boolean
  onClick?: () => void
  onRewind?: () => void
  rewindDisabled?: boolean
}) {
  const mine = role === 'user'
  const label = mine ? '你' : name ?? '成员'
  const time = fmtTime(createdAt)

  const meta = (
    <span className="flex items-center gap-1.5 text-xs text-hint">
      {label}
      {streaming && <span className="text-muted">正在说…</span>}
      {onClick && !streaming && <span>· 点开看当时的上下文</span>}
    </span>
  )

  const body = (
    <div
      onClick={onClick}
      title={onClick ? '点开看它这次发言的原始上下文' : undefined}
      className={`collab-message-body ${mine ? 'is-user' : 'is-agent'} rounded-2xl border px-3.5 py-2.5 text-md leading-relaxed ${
        mine
          ? 'border-line bg-fg/5 text-fg'
          : `${BUBBLE_MAX_W} border-line bg-fg/[0.04] text-fg`
      }${onClick ? ' cursor-pointer transition hover:border-accent' : ''}`}
    >
      {/* 思考过程：没有思考时它什么都不渲染；「不显示思维链」开着时整块不渲染 */}
      {showCot && <CotBlocks reasoning={reasoning} trace={trace} live={!!streaming} />}
      {content ? (
        <div className="prose-chat [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ img: ComfyImage }}>{content}</ReactMarkdown>
        </div>
      ) : streaming ? (
        <TypingDots />
      ) : (
        <span className="text-hint">…</span>
      )}
    </div>
  )

  /* 气泡与它下面的时间包在同一个宽度盒里：时间于是右对齐到气泡的右边缘，而不是消息区的右边缘 */
  const block = (
    <div className="flex max-w-[86%] flex-col gap-0.5">
      {body}
      {time && (
        <span className="self-end pr-1 font-mono text-2xs leading-none text-faint">
          {time}
        </span>
      )}
    </div>
  )

  if (mine) {
    return (
      <div className="flex flex-col items-end gap-1">
        {meta}
        {block}
        {onRewind && <button type="button" aria-label="回到这条提问之前"
          title="回退本会话的这条提问及后续对话，提问放回输入框；项目工作、议题和已执行的操作保留"
          disabled={rewindDisabled} onClick={onRewind}
          className="mt-1 inline-flex items-center gap-1 self-end rounded px-2 py-1 text-sm text-muted hover:text-fg disabled:cursor-not-allowed disabled:opacity-40">
          <AppIcon name="reply" size={14} />回退
        </button>}
      </div>
    )
  }

  /* 成员的发言：头像独占左边一列并对齐气泡顶部，名字与气泡在右——
     一轮里好几个人接连说话时，一眼看得出这句是谁说的 */
  return (
    <div className="flex items-start gap-2.5">
      <PeerAvatar name={label} avatar={avatar} size={BUBBLE_AVATAR_SIZE} textClass="text-[20px]" />
      <div className="flex min-w-0 flex-1 flex-col items-start gap-1">
        {meta}
        {block}
      </div>
    </div>
  )
}

/** 一条状态行拆出来的三要素 */
type EventParts = {
  /** 主角（用它取头像）；「话题开始」这类没有主角时为 null */
  peer: MaPeer | null
  /** 主角展示名（找不到对应成员时退化成文本里那段）；没有主角时为 null */
  name: string | null
  /** 动作本身：转入沉默 / 提议结束 / … */
  action: string
  /** 括号里的理由（后端就只有这一种结构化位置） */
  reason: string | null
}

/**
 * 行尾的（理由）：后端 {@code line()} 只会把理由拼在末尾，所以从「第一个（ 到最后一个 ）」切最稳。
 *
 * <p>用 {@code [\s\S]*}（而非 {@code [^）]*}）是必须的：理由里常再套括号
 * （如「…跑距离场（rev 2→3、24 格变）…」），一旦限制「不含右括号」就匹配不到结尾，
 * 整段理由会被当成动作灌进状态标签——表意错、还会撑出横向滚动条（踩过这个坑）。
 */
const REASON_RE = /（([\s\S]*)）\s*$/

/** 理由句首 / 句尾要去掉的符号：模型常写成「。理由。」或「，理由…」这种 */
const EDGE_SYMBOLS = String.raw`\s。，、；：！？…·,.;:!?~～—\-"'“”‘’()（）\[\]【】《》<>「」『』`

/** 一次匹配并去掉首尾两段的符号（正则只在模块加载时编译一次，运行时逐行扫描很短） */
const EDGE_SYMBOLS_RE = new RegExp(`^[${EDGE_SYMBOLS}]+|[${EDGE_SYMBOLS}]+$`, 'g')

/** 理由洗干净：剥掉首尾符号；剥完啥也不剩就当这条没有理由 */
function cleanReason(text: string): string | null {
  const clean = text.replace(EDGE_SYMBOLS_RE, '').trim()
  return clean || null
}

/**
 * 把一行状态文案拆成「谁 / 做了什么 / 为什么」。
 *
 * <p>后端给的是自述式文本（如「张三 转入沉默（讨论已经够了）」），没有结构化字段。
 * 历史消息自带 peerId（且落库了当时的展示名），最可靠；流式推来的状态行只有文本，
 * 就拿名册里的名字去比前缀（取最长匹配，免得「张」抢了「张三」的）。
 */
function parseEventLine(
  text: string,
  peerId: string | null,
  peerName: string | null,
  peerById: Map<string, MaPeer>,
  kind: string | null = null,
): EventParts {
  const m = REASON_RE.exec(text)
  const reason = m ? cleanReason(m[1]) : null
  const body = (m ? text.slice(0, m.index) : text).trim()

  /* 圆桌层面的事（话题开始 / 本轮结束）：整句就是文案，没有主角。
     不去猜「谁」——否则「本轮结束：甲、乙 发言」会被中间那个空格切成「名字 + 动作」 */
  if (kind === 'topic_open' || kind === 'turn_end') {
    return { peer: null, name: null, action: EVENT_KIND_ACTION[kind] ?? body, reason }
  }

  const byId = peerId ? (peerById.get(peerId) ?? null) : null
  const byPrefix = [...peerById.values()]
    .filter((p) => body.startsWith(p.name + ' '))
    .sort((a, b) => b.name.length - a.name.length)[0]
  const peer = byId ?? byPrefix ?? null

  /* 名字段：文本确实以它开头才按它切；成员改过名时退回「第一个空格前就是名字」 */
  let name: string | null = null
  for (const candidate of [peer?.name, peerName]) {
    if (candidate && body.startsWith(candidate + ' ')) {
      name = candidate
      break
    }
  }
  if (!name) {
    const sp = body.indexOf(' ')
    if (sp > 0) name = body.slice(0, sp)
  }

  /* 动作以 kind 为准（协议），没人给 kind 的老数据才从文本里切 */
  const action = (kind ? EVENT_KIND_ACTION[kind] : undefined) ?? (name ? body.slice(name.length).trim() : body)
  return { peer, name, action, reason }
}

/**
 * 这几条状态行居中显示；其余「谁主动做了什么」（转沉默、提议结束、举手抢答……）左对齐。
 *
 * <p>居中的几条要么没有主角（话题开始），要么是圆桌层面的进出与系统动作——都不是某个成员对
 * 话题的表态，跟上下成员气泡摆在同一列反而混淆。「撤回结束提议，重新决策」虽逐人各来一条
 * （重开时每个此前投过结束票的人各撤一条），讲的却是整桌的事：话题被重新打开了，所以也居中。
 *
 * <p>居中与否现在按事件种类判（{@link CENTERED_KINDS}），不再依赖文案：文案取自后端 line() 的
 * 输出，改一个字就会静默错位。老数据没有 kind，退回 {@link CENTERED_ACTIONS} 的文案匹配。
 * （「话题开始」在 {@link EventLine} 里单独走时间标记那条分支。）
 */
const CENTERED_ACTIONS = new Set([
  '入场',
  '退场',
  '被点名',
  '被要求立刻表态',
  '重新参与讨论',
  '撤回结束提议，重新决策',
  /* 被打断是圆桌层的事（人的新话把某人截了），居中才读得顺 */
  '的发言被打断',
])

/**
 * 居中的那几种事件（按 kind）：圆桌层面的进出、系统动作、被打断、以及「本轮结束」的清单——
 * 它们都不是某个成员对话题的表态，跟上下成员气泡摆在同一列反而混淆。
 */
const CENTERED_KINDS = new Set([
  'joined',
  'close',
  'addressed',
  'prompt',
  'revived',
  'reopen',
  'interrupt',
  'turn_end',
])

/**
 * 状态机动作行（role=event）：谁转沉默、谁提议结束……
 *
 * <p>它不是某个人的发言，而是圆桌上发生的事，所以单独一行浅色说明、与气泡分得开。
 * 默认左边空出头像那一格，好和上下成员气泡的正文左对齐；{@link CENTERED_ACTIONS} 里那几条居中。
 * 前缀不再写名字，改用头像（悬停仍看得到名字）；括号里的理由单独用一个 badge 包起来。
 *
 * <p><b>话题开始</b>例外：它是圆桌上的时间标记——这一轮从什么时候开始，居中看一眼就知道，
 * 所以不摆「话题开始」那几个字，也不受「⋯」菜单里那个开关控制。
 */
function EventLine({
  text,
  peerId,
  peerName,
  createdAt,
  peerById,
  kind = null,
}: {
  text: string
  peerId: string | null
  peerName: string | null
  /** 这条动作发生的时间（话题开始那条标记用它） */
  createdAt: string | null
  peerById: Map<string, MaPeer>
  /** 事件种类（joined / silent / turn_end …）：分类与渲染按它走，不再依赖中文文案 */
  kind?: string | null
}) {
  const kit = usePageKit()
  /* 系统提示（全桌已收到受阻 / 报告、正在重新处理）：一条醒目的高亮提示，居中。
     它不是某个成员对话题的表态，用户最需要一眼看到的正是「其他成员已经在接手」 */
  if (kind === 'task_context' || kind === 'issue_context') return <details className="collab-context-note"><summary>{kind === 'task_context' ? '本次目标与项目方向' : '议题发起时的上下文'}</summary><p>{text}</p></details>
  if (kind === 'issue_resolved') return <div className="collab-conclusion-notice"><span className="collab-secondary">议题结论 · 已带回主会话</span><details><summary>查看本次结论</summary><p>{text}</p></details></div>
  if (kind === 'ask_user') return <div className="border-l-2 border-accent pl-4"><p className="text-xs text-muted">需要你表态</p><p className="whitespace-pre-wrap break-words text-base leading-relaxed text-fg">{text}</p></div>
  if (kind === 'rally') return <RallyNotice text={text} />
  const { peer, name, action, reason } = parseEventLine(text, peerId, peerName, peerById, kind)

  if (kind === 'topic_open' || action === '话题开始') {
    return (
      <div className="flex justify-center">
        <kit.Badge>{fmtTime(createdAt, true) || '话题开始'}</kit.Badge>
      </div>
    )
  }

  /* 流程转化（提议结束 / 转入沉默）：走卡片式（见 {@link TransitionCard}），
     kind 优先、老数据（无 kind）退回文案匹配 */
  const transition =
    kind === 'propose_end' || action === '提议结束'
      ? 'propose_end'
      : kind === 'silent' || action === '转入沉默'
        ? 'silent'
        : null
  if (transition) {
    return (
      <TransitionCard
        kind={transition}
        name={name}
        avatar={peer?.avatar ?? null}
        action={action}
        reason={reason}
      />
    )
  }

  /* 理由可能是一整句：单独成 chip，限宽并允许换行——绝不撑出消息容器的横向滚动条 */
  const reasonChip = reason ? (
    <span className="collab-event-reason min-w-0 max-w-full whitespace-normal break-words rounded-full bg-fg/[0.07] px-1.5 py-0.5 font-mono text-2xs leading-relaxed text-hint">
      {reason}
    </span>
  ) : null

  /* 不是成员的主动表态（进出 / 系统动作 / 本轮结束）：居中，不占左边那一格头像。
     有 kind 就按它判（协议），老数据退回文案匹配 */
  const centered = kind ? CENTERED_KINDS.has(kind) : CENTERED_ACTIONS.has(action)
  if (centered) {
    /* 自造可换行的 chip：kit.Badge 是 nowrap 的，长文案（如「本轮结束」清单）会撑出横向滚动条 */
    return (
      <div className="flex justify-center px-1">
        <span
          className="mono inline-flex max-w-[min(100%,40rem)] min-w-0 flex-wrap items-center justify-center gap-x-1.5 gap-y-0.5 rounded-full border px-2.5 py-1 text-2xs font-bold tracking-wider uppercase"
          style={{ borderColor: 'var(--c-line-strong)', color: 'var(--c-muted)' }}
        >
          {name && (
            <span className="shrink-0" title={name}>
              <PeerAvatar
                name={name}
                avatar={peer?.avatar ?? null}
                size="h-4 w-4"
                textClass="text-[12px]"
              />
            </span>
          )}
          <span className="min-w-0 whitespace-normal break-words text-center">{action}</span>
          {reasonChip}
        </span>
      </div>
    )
  }
  return (
    /* 左边空出头像那一格：状态行于是和上下成员气泡的正文左对齐 */
    <div className="flex items-start gap-2.5">
      <span className={`${BUBBLE_AVATAR_SIZE} shrink-0`} aria-hidden="true" />
      <span className="flex min-w-0 max-w-full flex-wrap items-center gap-1.5">
        <kit.Badge>
          {name && (
            <span className="shrink-0" title={name}>
              <PeerAvatar
                name={name}
                avatar={peer?.avatar ?? null}
                size="h-4 w-4"
                textClass="text-[12px]"
              />
            </span>
          )}
          <span className="ml-1">{action}</span>
        </kit.Badge>
        {reasonChip}
      </span>
    </div>
  )
}

/**
 * 流程转化卡片：把「提议结束 / 转入沉默」这两种最常出现的表态做成一张卡片——
 * 头像 + 名字 + 动作 + 理由各归其位，比挤在一条 nowrap 徽章里更好读，也天然限宽（不会撑出横向滚动）。
 *
 * <p>目前只适配这两类；其余动作仍走原来的状态行（见 {@link EventLine}）。
 */
function TransitionCard({
  kind,
  name,
  avatar,
  action,
  reason,
}: {
  kind: 'propose_end' | 'silent'
  name: string | null
  avatar: string | null
  action: string
  reason: string | null
}) {
  const end = kind === 'propose_end'
  return (
    <div className="flex items-start gap-2.5">
      <span className={`${BUBBLE_AVATAR_SIZE} shrink-0`} aria-hidden="true" />
      <div
        className="flex min-w-0 max-w-[26rem] flex-col gap-1.5 rounded-lg border px-3 py-2"
        style={{
          borderColor: end
            ? 'color-mix(in srgb, var(--c-accent) 45%, var(--c-line))'
            : 'var(--c-line)',
          background: end
            ? 'color-mix(in srgb, var(--c-accent) 8%, transparent)'
            : 'color-mix(in srgb, var(--c-fg) 4%, transparent)',
        }}
      >
        <div className="flex min-w-0 items-center gap-2">
          <PeerAvatar name={name ?? '成员'} avatar={avatar} size="h-5 w-5" textClass="text-[12px]" />
          <span className="min-w-0 flex-1 truncate text-sm font-medium text-fg">
            {name ?? '成员'}
          </span>
          <span
            className="shrink-0 rounded px-1.5 py-0.5 text-2xs font-bold tracking-wide"
            style={{
              background: end
                ? 'var(--c-accent)'
                : 'color-mix(in srgb, var(--c-fg) 10%, transparent)',
              color: end ? 'var(--c-accent-fg)' : 'var(--c-muted)',
            }}
          >
            {action}
          </span>
        </div>
        {reason && (
          <p className="collab-event-reason min-w-0 whitespace-normal break-words text-xs leading-relaxed text-fg-2">
            {reason}
          </p>
        )}
      </div>
    </div>
  )
}

/** 一条状态行要不要显示：入场 / 退场默认收起（「⋯」菜单里可开）；其余行不受影响 */
function showEventLine(
  msg: MaMessage,
  showPresence: boolean,
  peerById: Map<string, MaPeer>,
): boolean {
  if (msg.role !== 'event') return true
  if (showPresence) return true
  /* 有 kind 按它判（协议），老数据退回文案匹配 */
  if (msg.kind) return !NOISY_KINDS.has(msg.kind)
  return !NOISY_ACTIONS.includes(
    parseEventLine(msg.content, msg.peerId, msg.peerName, peerById).action,
  )
}

/**
 * 一条消息：状态机动作行走状态行 / 转化卡片，其余（人的话 / 成员发言）走气泡。
 *
 * <p>用 {@link memo} 包住：虚拟化把渲染量钉在视口内之后，输入框每敲一个字仍会重渲本组件，
 * 而每条气泡里跑着 ReactMarkdown——memo 让「打字」与列表长度、气泡内容彻底脱钩。
 * props 必须都是稳定的（msg 取自数组、peerById 是 useMemo、onOpenContext 是 useCallback）。
 */
const MessageItem = memo(function MessageItem({
  msg,
  avatar,
  showCot,
  peerById,
  allowContext,
  onOpenContext,
  onRewind,
  rewindDisabled,
}: {
  msg: MaMessage
  avatar?: string | null
  showCot: boolean
  /** 状态行要按名字认出主角才能换头像，所以名册得传进来 */
  peerById: Map<string, MaPeer>
  /** 点气泡看原始上下文的开关（默认关） */
  allowContext: boolean
  /** 点开一条成员发言时的回调（稳定引用，memo 依赖它保持稳定） */
  onOpenContext: (m: MaMessage) => void
  onRewind: (m: MaMessage) => void
  rewindDisabled: boolean
}) {
  if (msg.kind === AGENT_MESSAGE_SENT) {
    return <AgentMessageReceipt sessionId={msg.sessionId} content={msg.content} peers={peerById} />
  }
  if (msg.role === 'event') {
    return (
      <EventLine
        text={msg.content}
        peerId={msg.peerId}
        peerName={msg.peerName}
        createdAt={msg.createdAt}
        peerById={peerById}
        kind={msg.kind ?? null}
      />
    )
  }
  return (
    <Bubble
      role={msg.role}
      name={msg.peerName}
      avatar={avatar}
      content={msg.content}
      reasoning={msg.reasoning}
      trace={msg.trace}
      createdAt={msg.createdAt}
      showCot={showCot}
      /* 只有成员发言才有原始上下文可看（人的话不是一次模型调用）；入口由「⋯」菜单里的开关控制 */
      onClick={allowContext && msg.role === 'peer' ? () => void onOpenContext(msg) : undefined}
      onRewind={msg.role === 'user' && msg.id > 0 ? () => onRewind(msg) : undefined}
      rewindDisabled={rewindDisabled}
    />
  )
})
