/** The everyday Slime surface. Existing specialist tools live in Dashboard. */
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ArrowDown, ArrowUp, AudioLines, BookOpen, Check, Copy, History, MessageSquare, Mic, PanelLeftOpen, PenLine, Plus, Search, ShieldCheck, Sparkles, Square, X } from 'lucide-react'
import { createSession, getSettings, listMaPeers, listSessions, sessionMessages, sessionPreviews, updateSessionConfig, type MaPeer, type ReasoningEffort, type SessionItem, type SessionMessage, type SettingsView } from '../../api/client'
import { CORE_AGENT_ID, isCodingAgent, sortAgentPeers } from '../../api/agentRuntime'
import ChatMarkdown from '../../components/ChatMarkdown'
import UserQuestionCard from '../../components/UserQuestionCard'
import { SlimeAppearanceButton } from '../../components/SlimeAppearance'
import { registerChatOpener } from '../../components/ChatNotifier'
import { subscribeSessionEvents } from '../../components/sessionEvents'
import { backfillSettledReply } from '../../components/settledChat'
import { questionContent, userQuestion } from '../../components/userQuestion'
import { attachLive, clearLive, getLive, setActiveChatSession, startChat, stopChat, useLiveChat, type ChatMsg, type LiveChat } from '../../components/chatLive'
import { BRAND } from '../../branding'
import AgentSidebar, { type AgentPreview } from './AgentSidebar'
import ComposerModelPicker from './ComposerModelPicker'
import { useDictation } from './useDictation'
import { useWelcomeSlogan } from './useWelcomeSlogan'
import './agent.css'

const LAST_SESSION_KEY = 'minis.agent.lastSession'
type ViewMsg = ChatMsg & { at?: string | null }
type ComposerPreference = { modelId: string; effort?: ReasoningEffort }
const SUGGESTIONS = [
  { icon: Sparkles, label: '理清思路', text: '我有一个想法，想和你一起理一理。' },
  { icon: BookOpen, label: '整理资料', text: '帮我总结知识库里最近的文档，提炼重点。' },
  { icon: PenLine, label: '写点东西', text: '我想写点东西，帮我一起打磨。' },
]
function toViewMsg(row: SessionMessage): ViewMsg {
  return { id: row.id, role: row.role === 'assistant' ? 'assistant' : 'user', content: row.content,
    reasoning: row.reasoning ?? undefined, trace: row.trace ?? undefined, kind: row.kind ?? undefined,
    subagent: row.subagent ?? undefined, at: row.createdAt }
}
function dayBucket(iso: string | null): string {
  if (!iso) return '更早'
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1)
  const time = new Date(iso).getTime()
  return time >= today.getTime() ? '今天' : time >= yesterday.getTime() ? '昨天' : '更早'
}
function sessionTitle(session: SessionItem): string { return session.title?.trim() || '新对话' }
function agentOf(session: SessionItem): string | null { return !session.agentId || session.agentId === CORE_AGENT_ID ? null : session.agentId }
function previewSnippet(value?: string | null): string | undefined {
  return value?.replace(/\s+/g, ' ').trim().slice(0, 220) || undefined
}
function withLive(history: ViewMsg[], live: LiveChat): ViewMsg[] {
  // A history response can already contain this completed turn (for example after
  // visiting Dashboard during generation). Keep the persisted reply in that case.
  const userIndex = live.userMessageId === null ? -1 : history.findIndex(message => message.role === 'user' && message.id === live.userMessageId)
  if (userIndex >= 0) {
    const after = history.slice(userIndex + 1)
    if (after.some(message => message.role === 'user') || (!live.running && after.some(message => message.role === 'assistant' && message.kind !== 'subagent'))) return history
  }
  const last = history.at(-1)
  const duplicate = last?.role === 'user' && (live.userMessageId !== null ? last.id === live.userMessageId : last.content === live.userText)
  return [...(duplicate ? history.slice(0, -1) : history),
    { role: 'user', content: live.userText, id: live.userMessageId ?? undefined },
    { role: 'assistant', content: live.content, reasoning: live.reasoning || undefined,
      trace: live.trace.length ? live.trace : undefined, streaming: live.running }]
}

export default function AgentHome({ connErr, userSlot }: { connErr?: string | null; userSlot?: ReactNode }) {
  const { slogan, randomize: randomizeSlogan } = useWelcomeSlogan()
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [peersLoading, setPeersLoading] = useState(true)
  const [peersError, setPeersError] = useState(false)
  const [previews, setPreviews] = useState<Record<string, string>>({})
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null)
  const [settings, setSettings] = useState<SettingsView | null>(null)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState(false)
  const [modelId, setModelId] = useState('')
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort | undefined>(undefined)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [msgs, setMsgs] = useState<ViewMsg[]>([])
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [sessionsError, setSessionsError] = useState(false)
  const [messageError, setMessageError] = useState(false)
  const [toast, setToast] = useState<string | null>(null)
  const [copied, setCopied] = useState<number | null>(null)
  const [narrow, setNarrow] = useState(() => window.matchMedia('(max-width: 760px)').matches)
  const [sidebarOpen, setSidebarOpen] = useState(() => !window.matchMedia('(max-width: 760px)').matches)
  const [awayFromBottom, setAwayFromBottom] = useState(false)
  const live = useLiveChat(sessionId)
  const busy = live?.running ?? false
  const currentRef = useRef<string | null>(null)
  const selectedAgentRef = useRef<string | null>(null)
  const sessionsRef = useRef<SessionItem[]>([])
  const sessionsVersion = useRef(0)
  const settledRef = useRef<LiveChat | null>(null)
  const creatingRef = useRef(false)
  const historyVersion = useRef(0)
  const drafts = useRef(new Map<string, string>())
  const textRef = useRef(text)
  textRef.current = text
  const followBottom = useRef(true)
  const scrollRef = useRef<HTMLDivElement>(null)
  const sideRef = useRef<HTMLElement>(null)
  const menuRef = useRef<HTMLButtonElement>(null)
  const taRef = useRef<HTMLTextAreaElement>(null)
  const copyTimer = useRef<number | undefined>(undefined)
  const historyRef = useRef<HTMLDivElement>(null)
  const historyButtonRef = useRef<HTMLButtonElement>(null)
  const composerPreferences = useRef(new Map<string, ComposerPreference>())
  const preferenceRef = useRef<ComposerPreference>({ modelId: '' })
  preferenceRef.current = { modelId, effort: reasoningEffort }
  const moreRef = useRef<HTMLDetailsElement>(null)
  const dictationOwner = useRef<string | null>(null)
  const dictation = useDictation({
    onText: transcript => {
      const owner = currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`
      if (dictationOwner.current !== owner) return
      setText(previous => `${previous}${previous && !/\s$/.test(previous) ? ' ' : ''}${transcript}`)
    },
    onError: setToast,
  })
  const toggleDictation = () => {
    if (!dictation.listening) dictationOwner.current = currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`
    dictation.toggle()
  }
  useEffect(() => {
    dictation.stop()
    if (moreRef.current) moreRef.current.open = false
  }, [sessionId, selectedAgentId, dictation.stop])
  useEffect(() => { if (busy) dictation.stop() }, [busy, dictation.stop])
  useEffect(() => {
    const closeMore = (event: PointerEvent) => {
      if (moreRef.current && !moreRef.current.contains(event.target as Node)) moreRef.current.open = false
    }
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape' && moreRef.current) moreRef.current.open = false }
    document.addEventListener('pointerdown', closeMore)
    document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', closeMore); document.removeEventListener('keydown', escape) }
  }, [])

  const rememberPreferences = useCallback(() => {
    composerPreferences.current.set(currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`, preferenceRef.current)
  }, [])
  const restorePreferences = useCallback((key: string, fallbackModelId = '') => {
    const preference = composerPreferences.current.get(key) ?? { modelId: fallbackModelId }
    preferenceRef.current = preference
    setModelId(preference.modelId); setReasoningEffort(preference.effort)
  }, [])
  const reloadSettings = useCallback(() => {
    setSettingsLoading(true)
    void getSettings().then(value => { setSettings(value); setSettingsError(false) })
      .catch(() => setSettingsError(true)).finally(() => setSettingsLoading(false))
  }, [])
  useEffect(reloadSettings, [reloadSettings])

  const selectIdentity = useCallback((id: string | null) => {
    selectedAgentRef.current = id
    setSelectedAgentId(id)
  }, [])
  const reloadPeers = useCallback(() => {
    setPeersLoading(true)
    void listMaPeers().then(list => { setPeers(sortAgentPeers(list)); setPeersError(false) })
      .catch(() => setPeersError(true)).finally(() => setPeersLoading(false))
  }, [])
  useEffect(reloadPeers, [reloadPeers])

  const reloadSessions = useCallback(() => {
    const version = ++sessionsVersion.current
    void listSessions().then(list => {
      if (sessionsVersion.current !== version) return
      sessionsRef.current = list; setSessions(list); setSessionsError(false)
    }).catch(() => { if (sessionsVersion.current === version) setSessionsError(true) })
    void sessionPreviews().then(result => { if (sessionsVersion.current === version) setPreviews(result) })
      .catch(() => { /* Older backends can still show a conversation title. */ })
  }, [])
  useEffect(reloadSessions, [reloadSessions])
  useEffect(() => {
    const media = window.matchMedia('(max-width: 760px)')
    const change = () => { setNarrow(media.matches); setSidebarOpen(!media.matches) }
    media.addEventListener('change', change)
    return () => media.removeEventListener('change', change)
  }, [])

  const openSession = useCallback((id: string) => {
    if (creatingRef.current) return
    rememberPreferences()
    const snapshot = getLive(id)
    const settled = snapshot && !snapshot.running ? snapshot : null
    if (settled) clearLive(id)
    drafts.current.set(currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`, textRef.current)
    const known = sessionsRef.current.find(session => session.id === id)
    if (known) selectIdentity(agentOf(known))
    restorePreferences(id, known?.modelId ?? '')
    setHistoryOpen(false)
    currentRef.current = id
    const version = ++historyVersion.current
    setSessionId(id); setMsgs([]); setLoading(true); setMessageError(false)
    setText(drafts.current.get(id) ?? '')
    followBottom.current = true; setAwayFromBottom(false)
    window.localStorage.setItem(LAST_SESSION_KEY, id)
    if (window.matchMedia('(max-width: 760px)').matches) setSidebarOpen(false)
    // Notifications and restoration can arrive before the session list. Resolve
    // the Agent before exposing the conversation as ready for another message.
    const identity = known ? Promise.resolve(known) : listSessions().then(list => {
      const found = list.find(session => session.id === id)
      if (!found) throw new Error('会话不存在')
      if (currentRef.current === id && historyVersion.current === version) {
        sessionsRef.current = list; setSessions(list)
      }
      return found
    })
    void Promise.all([sessionMessages(id), identity]).then(([rows, session]) => {
      if (currentRef.current !== id || historyVersion.current !== version) return
      selectIdentity(agentOf(session))
      restorePreferences(id, session.modelId ?? '')
      const history = rows.map(toViewMsg)
      setMsgs(settled?.error ? withLive(history, settled) : history)
      if (settled?.error) setToast(`上次回复未完成：${settled.error}`)
      setSessions(list => list.map(s => s.id === id ? { ...s, unread: false } : s))
    }).catch(() => {
      if (currentRef.current !== id || historyVersion.current !== version) return
      if (settled) { setMsgs(withLive([], settled)); setToast('暂时无法加载完整历史，已保留最近的回复') }
      else setMessageError(true)
    }).finally(() => {
      if (currentRef.current === id && historyVersion.current === version) setLoading(false)
    })
    if (!snapshot) void attachLive(id)
  }, [selectIdentity, rememberPreferences, restorePreferences])

  useEffect(() => {
    const session = sessions.find(item => item.id === sessionId)
    if (session) selectIdentity(agentOf(session))
  }, [sessions, sessionId, selectIdentity])

  useEffect(() => {
    const last = window.localStorage.getItem(LAST_SESSION_KEY)
    if (last) openSession(last)
  }, [openSession])
  useEffect(() => {
    registerChatOpener(openSession)
    return () => registerChatOpener(null)
  }, [openSession])

  const newChat = useCallback(() => {
    if (creatingRef.current) return
    const active = currentRef.current ? sessionsRef.current.find(session => session.id === currentRef.current) : null
    if (currentRef.current && !active) { setToast('正在读取 Agent 信息，请稍后再试'); return }
    rememberPreferences()
    drafts.current.set(currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`, textRef.current)
    if (active) selectIdentity(agentOf(active))
    currentRef.current = null; historyVersion.current++
    composerPreferences.current.set(`new:${selectedAgentRef.current ?? CORE_AGENT_ID}`, preferenceRef.current)
    randomizeSlogan()
    setSessionId(null); setMsgs([]); setText(''); setLoading(false); setMessageError(false)
    setHistoryOpen(false); setQuery('')
    followBottom.current = true; setAwayFromBottom(false)
    window.localStorage.removeItem(LAST_SESSION_KEY)
    if (window.matchMedia('(max-width: 760px)').matches) setSidebarOpen(false)
    requestAnimationFrame(() => taRef.current?.focus())
  }, [selectIdentity, rememberPreferences, randomizeSlogan])

  const closeSidebar = useCallback(() => {
    setSidebarOpen(false)
    requestAnimationFrame(() => menuRef.current?.focus())
  }, [])

  const selectAgent = useCallback((id: string | null) => {
    if (creatingRef.current) return
    const peer = peers.find(item => item.id === id)
    if (peer && isCodingAgent(peer)) { window.location.hash = '#/coding'; return }
    const recent = [...sessionsRef.current].filter(session => agentOf(session) === id)
      .sort((a, b) => (Date.parse(b.updatedAt ?? '') || 0) - (Date.parse(a.updatedAt ?? '') || 0))[0]
    if (recent) { openSession(recent.id); return }
    rememberPreferences()
    drafts.current.set(currentRef.current ?? `new:${selectedAgentRef.current ?? CORE_AGENT_ID}`, textRef.current)
    currentRef.current = null; historyVersion.current++
    selectIdentity(id); setSessionId(null); setMsgs([]); setLoading(false); setMessageError(false)
    restorePreferences(`new:${id ?? CORE_AGENT_ID}`)
    randomizeSlogan()
    setText(drafts.current.get(`new:${id ?? CORE_AGENT_ID}`) ?? '')
    setHistoryOpen(false); setQuery(''); followBottom.current = true; setAwayFromBottom(false)
    window.localStorage.removeItem(LAST_SESSION_KEY)
    if (window.matchMedia('(max-width: 760px)').matches) setSidebarOpen(false)
  }, [peers, openSession, selectIdentity, rememberPreferences, restorePreferences, randomizeSlogan])

  useEffect(() => {
    if (!historyOpen) return
    const onPointer = (event: PointerEvent) => {
      if (!historyRef.current?.contains(event.target as Node) && !historyButtonRef.current?.contains(event.target as Node)) setHistoryOpen(false)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setHistoryOpen(false); historyButtonRef.current?.focus() }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('pointerdown', onPointer); document.removeEventListener('keydown', onKey) }
  }, [historyOpen])

  // Keep background delivery visible without replacing a newer turn with a stale history request.
  useEffect(() => subscribeSessionEvents(event => {
    if (event.kind === 'todo') return
    reloadSessions()
    const id = currentRef.current
    if (!id || event.sessionId !== id || event.kind !== 'delivered' || getLive(id)?.running) return
    const version = historyVersion.current
    void sessionMessages(id).then(rows => {
      if (currentRef.current === id && historyVersion.current === version && !getLive(id)?.running) setMsgs(rows.map(toViewMsg))
    }).catch(() => { /* A later delivery or reopening can retry the request. */ })
  }), [reloadSessions])

  useEffect(() => {
    if (!live || live.running || settledRef.current === live || loading) return
    settledRef.current = live
    const id = live.sessionId
    setMsgs(previous => withLive(previous, live).filter(m => m.role === 'user' || m.content || m.trace?.length))
    clearLive(id)
    void sessionMessages(id).then(rows => {
      if (currentRef.current !== id || settledRef.current !== live || getLive(id)?.running) return
      setMsgs(previous => backfillSettledReply(previous, live, rows))
    }).catch(() => { /* Retain the completed stream when history is temporarily unavailable. */ })
    reloadSessions()
  }, [live, loading, reloadSessions])
  useEffect(() => { if (live?.error) setToast(`发送失败：${live.error}`) }, [live?.error])
  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(null), 4500)
    return () => window.clearTimeout(timer)
  }, [toast])
  useEffect(() => () => window.clearTimeout(copyTimer.current), [])
  useEffect(() => {
    setActiveChatSession(sessionId)
    return () => setActiveChatSession(null)
  }, [sessionId])

  // On phones the history is an actual drawer, including keyboard focus and Escape handling.
  useEffect(() => {
    if (!sidebarOpen || !narrow) return
    const aside = sideRef.current
    aside?.querySelector<HTMLElement>('button, a')?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeSidebar()
      if (event.key !== 'Tab' || !aside) return
      const items = Array.from(aside.querySelectorAll<HTMLElement>('button:not(:disabled), a, input'))
      const first = items[0], last = items.at(-1)
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [narrow, sidebarOpen, closeSidebar])

  const inheritedAgent = peers.find(peer => peer.id === (selectedAgentId ?? CORE_AGENT_ID))
  const inheritedModelId = inheritedAgent ? inheritedAgent.modelId || settings?.defaultModelId : undefined
  const effectiveModel = settings?.models.find(model => model.id === (modelId || inheritedModelId))
  const supportedEffort = reasoningEffort && effectiveModel?.reasoningEfforts?.includes(reasoningEffort) ? reasoningEffort : undefined
  useEffect(() => {
    if (settings && (modelId || inheritedModelId) && reasoningEffort && !effectiveModel?.reasoningEfforts?.includes(reasoningEffort)) setReasoningEffort(undefined)
  }, [settings, effectiveModel, reasoningEffort, modelId, inheritedModelId])

  const sendText = useCallback(async (raw: string) => {
    const value = raw.trim()
    if (!value || creatingRef.current || loading || messageError || getLive(currentRef.current)?.running) return
    creatingRef.current = true; setCreating(true)
    followBottom.current = true; setAwayFromBottom(false)
    let id = currentRef.current
    const agentId = selectedAgentRef.current
    try {
      if (!id) {
        const created = await createSession()
        if (agentId || modelId) {
          await updateSessionConfig(created.id, { agentId, modelId: modelId || null, promptId: null, showCot: null, showRounds: null })
        }
        id = created.id
        currentRef.current = id; setSessionId(id)
        window.localStorage.setItem(LAST_SESSION_KEY, id)
        const session: SessionItem = { id, title: null, agentId, modelId: modelId || null, promptId: null, projectId: null, showCot: null, showRounds: null, unread: false, generating: false, updatedAt: new Date().toISOString() }
        sessionsRef.current = [session, ...sessionsRef.current]
        setSessions(sessionsRef.current)
      } else if ((sessionsRef.current.find(session => session.id === id)?.modelId ?? '') !== modelId) {
        await updateSessionConfig(id, { agentId: null, modelId: modelId || '', promptId: null, showCot: null, showRounds: null })
        sessionsRef.current = sessionsRef.current.map(session => session.id === id ? { ...session, modelId: modelId || null } : session)
        setSessions(sessionsRef.current)
      }
      historyVersion.current++
      setText(''); drafts.current.delete(id); drafts.current.delete(`new:${agentId ?? CORE_AGENT_ID}`)
      composerPreferences.current.set(id, { modelId, effort: supportedEffort })
      startChat({ sessionId: id, text: value, modelId: modelId || undefined, reasoningEffort: supportedEffort, onSettled: reloadSessions })
      reloadSessions()
    } catch {
      setText(value); setToast('暂时无法发送，请稍后重试')
    } finally { creatingRef.current = false; setCreating(false) }
  }, [loading, messageError, reloadSessions, modelId, supportedEffort])

  const view = useMemo(() => live ? withLive(msgs, live) : msgs, [live, msgs])
  const empty = !loading && !messageError && view.length === 0
  const latestTurn = view.findLastIndex(m => m.kind !== 'subagent')
  const scrollToBottom = () => {
    const element = scrollRef.current
    if (element) element.scrollTop = element.scrollHeight
    followBottom.current = true; setAwayFromBottom(false)
  }
  useEffect(() => {
    if (followBottom.current && scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [view, loading])
  useEffect(() => {
    const element = taRef.current
    if (element) { element.style.height = '0px'; element.style.height = `${Math.min(element.scrollHeight, 180)}px` }
  }, [text, empty])

  const groups = useMemo(() => {
    const search = query.trim().toLowerCase()
    const list = [...sessions].filter(s => agentOf(s) === selectedAgentId && (!search || sessionTitle(s).toLowerCase().includes(search)))
      .sort((a, b) => (Date.parse(b.updatedAt ?? '') || 0) - (Date.parse(a.updatedAt ?? '') || 0))
    return ['今天', '昨天', '更早'].map(label => ({ label, items: list.filter(s => dayBucket(s.updatedAt) === label) })).filter(group => group.items.length)
  }, [query, sessions, selectedAgentId])
  const title = sessions.find(s => s.id === sessionId)
  const mainAgent = peers.find(peer => peer.id === CORE_AGENT_ID) ?? null
  const currentAgent = selectedAgentId ? peers.find(peer => peer.id === selectedAgentId) : mainAgent
  const agentName = currentAgent?.name || (selectedAgentId ? 'Agent' : BRAND.name)
  const agentAvatar = currentAgent?.avatar || (!selectedAgentId ? '/icon-64x64.png' : null)
  const agentPreviews = useMemo(() => {
    const result: Record<string, AgentPreview> = {}
    const sorted = [...sessions].sort((a, b) => (Date.parse(b.updatedAt ?? '') || 0) - (Date.parse(a.updatedAt ?? '') || 0))
    for (const session of sorted) {
      const key = agentOf(session) ?? 'main'
      const current = result[key]
      const snapshot = getLive(session.id)
      const local = session.id === sessionId ? view.at(-1)?.content : snapshot?.content
      const running = session.generating || snapshot?.running === true
      if (!current) result[key] = { text: previewSnippet(local) || previewSnippet(previews[session.id]) || previewSnippet(session.title), unread: Boolean(session.unread), running }
      else { current.unread ||= Boolean(session.unread); current.running ||= running }
    }
    return result
  }, [sessions, previews, sessionId, view])
  const copy = async (content: string, index: number) => {
    try {
      await navigator.clipboard.writeText(content)
      setCopied(index); window.clearTimeout(copyTimer.current)
      copyTimer.current = window.setTimeout(() => setCopied(null), 1800)
    } catch { setToast('复制失败，请手动选择文字复制') }
  }

  return (
    <div className={`slime-surface sl-chat${sidebarOpen ? ' is-sidebar-open' : ''}${empty ? ' is-welcome' : ''}`}>
      {narrow && sidebarOpen && <button type="button" className="sl-chat__scrim" aria-label="关闭会话列表" onClick={closeSidebar} />}
      <aside className="sl-chat__side" id="slime-history" ref={sideRef} inert={!sidebarOpen} aria-label="Agent 导航" role={narrow && sidebarOpen ? 'dialog' : undefined} aria-modal={narrow && sidebarOpen ? true : undefined}>
        <AgentSidebar mainAgent={mainAgent} agents={peers.filter(peer => peer.id !== CORE_AGENT_ID)}
          selectedAgentId={selectedAgentId} previews={agentPreviews} loading={peersLoading} error={peersError}
          disabled={creating} narrow={narrow} userSlot={userSlot} onSelectAgent={selectAgent}
          onReload={reloadPeers} onClose={closeSidebar} />
      </aside>

      <main className="sl-chat__main" inert={narrow && sidebarOpen}>
        <header className="sl-chat__header" data-tauri-drag-region>
          <button ref={menuRef} type="button" className={`sl-chat__icon sl-chat__menubtn${sidebarOpen ? ' is-hidden' : ''}`} title="展开 Agent 列表" aria-label="展开 Agent 列表" aria-expanded={sidebarOpen} aria-controls="slime-history" onClick={() => { setHistoryOpen(false); setSidebarOpen(true) }}><PanelLeftOpen size={19} /></button>
          <div className="sl-chat__heading sl-chat__agent-heading">
            <span className="sl-chat__agent-avatar">{agentAvatar ? <img src={agentAvatar} alt="" /> : agentName.trim()[0]?.toUpperCase()}</span>
            <span className="sl-chat__agent-title"><strong>{agentName}</strong><small>{busy ? '正在回复…' : title ? sessionTitle(title) : '新对话'}</small></span>
            {busy && <span className="sl-chat__running" role="status" aria-label="正在回复" />}
          </div>
          <div className="sl-chat__headtools">
            <SlimeAppearanceButton />
            <button ref={historyButtonRef} type="button" className="sl-chat__icon" title="历史对话" aria-label="历史对话" aria-expanded={historyOpen} aria-controls="slime-agent-history" onClick={() => { setHistoryOpen(!historyOpen); setQuery('') }}><History size={18} /></button>
            <button type="button" className="sl-chat__icon" title="新对话" aria-label="新对话" disabled={creating} onClick={newChat}><PenLine size={18} /></button>
          </div>
        </header>
        {historyOpen && <div ref={historyRef} className="sl-chat__historypanel" id="slime-agent-history" role="dialog" aria-label={`${agentName} 的历史对话`}>
          <div className="sl-chat__historypanel-head"><span>与 {agentName} 的对话</span><button type="button" className="sl-chat__icon" aria-label="关闭历史对话" onClick={() => { setHistoryOpen(false); historyButtonRef.current?.focus() }}><X size={16} /></button></div>
          <label className="sl-chat__historypanel-search"><Search size={15} /><input autoFocus aria-label="搜索历史对话" placeholder="搜索对话…" value={query} onChange={event => setQuery(event.target.value)} /></label>
          <button type="button" className="sl-chat__historypanel-new" onClick={newChat} disabled={creating}><Plus size={16} />新对话</button>
          <div className="sl-chat__historypanel-list scrollbar-slim">
            {sessionsError ? <div className="sl-chat__nohistory">暂时无法加载历史<button type="button" onClick={reloadSessions}>重新加载</button></div>
              : !groups.length && <p className="sl-chat__nohistory">{query ? '没有找到相关对话' : '还没有历史对话，开始聊聊吧。'}</p>}
            {groups.map(group => <div className="sl-chat__group" key={group.label}><p>{group.label}</p>{group.items.map(session => <button type="button" key={session.id} disabled={creating} aria-current={session.id === sessionId ? 'page' : undefined} className={`sl-chat__session${session.id === sessionId ? ' is-active' : ''}`} onClick={() => openSession(session.id)}><MessageSquare size={15} /><span>{sessionTitle(session)}</span>{(session.generating || session.unread) && <i aria-label={session.generating ? '正在回复' : '未读消息'} />}</button>)}</div>)}
          </div>
        </div>}
        {connErr && <div className="sl-chat__banner" role="status">暂时连接不上服务，请检查后端是否已启动。</div>}
        <div className="sl-chat__scroll scrollbar-slim" ref={scrollRef} onScroll={() => {
          const element = scrollRef.current
          if (!element) return
          const away = element.scrollHeight - element.scrollTop - element.clientHeight > 100
          followBottom.current = !away; setAwayFromBottom(away)
        }} aria-busy={loading}>
          {loading ? <div className="sl-chat__loading" role="status"><span className="sl-chat__thinking"><i /><i /><i /></span>正在打开对话</div>
            : messageError ? <div className="sl-chat__loading" role="status">暂时无法打开这段对话。<button type="button" onClick={() => sessionId && openSession(sessionId)}>重新加载</button></div>
            : empty ? <div className="sl-chat__welcome">
              <h1>{slogan.replaceAll('{agent}', agentName)}</h1>
            </div>
            : <div className="sl-chat__thread" role="log" aria-label="对话消息">
              {view.map((message, index) => {
                const question = userQuestion(message)
                return <article key={message.id ?? `live-${index}`} className={`sl-chat__turn sl-chat__turn--${message.role}`} aria-label={message.role === 'user' ? '你的消息' : `${agentName} 的回复`}>
                  {message.role === 'user' ? <div className="sl-chat__bubble">{message.content}</div> : <>
                    <div className="sl-chat__answer">
                      {question ? <><UserQuestionCard key={JSON.stringify(question)} question={question} active={!busy && !creating && !loading && !live?.error && index === latestTurn} onAnswer={sendText} /><ChatMarkdown content={message.content.slice(questionContent(question).length)} /></>
                        : message.streaming ? message.content ? <div className="sl-chat__stream">{message.content}</div> : <span className="sl-chat__thinking" role="status" aria-label="正在思考"><i /><i /><i /></span>
                        : <ChatMarkdown content={message.content} />}
                    </div>
                    {message.content && !message.streaming && <div className="sl-chat__message-actions"><button type="button" className="sl-chat__icon" aria-label={copied === index ? '已复制回复' : '复制回复'} title={copied === index ? '已复制' : '复制回复'} onClick={() => void copy(message.content, index)}>{copied === index ? <Check size={14} /> : <Copy size={14} />}</button></div>}
                  </>}
                </article>
              })}
            </div>}
        </div>
        {awayFromBottom && !empty && <button type="button" className="sl-chat__jump" aria-label="回到最新消息" onClick={scrollToBottom}><ArrowDown size={16} /><span>最新消息</span></button>}
        <div className="sl-chat__composearea">
          <form className={`sl-chat__composer${busy ? ' is-busy' : ''}`} onSubmit={event => { event.preventDefault(); void sendText(text) }}>
            <textarea ref={taRef} rows={1} value={text} onChange={event => setText(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229 && !narrow) {
                  event.preventDefault(); void sendText(text)
                }
              }} placeholder={dictation.listening ? '正在听你说…' : `和 ${agentName} 一起工作`} aria-label={`给 ${agentName} 发消息`} disabled={loading || messageError} />
            <div className="sl-chat__composerbar">
              <div className="sl-chat__compose-left">
                <details ref={moreRef} className="sl-chat__compose-more">
                  <summary aria-label="更多操作" title="更多操作"><Plus size={18} /></summary>
                  <div className="sl-chat__compose-menu">
                    <button type="button" disabled={creating} onClick={() => { if (moreRef.current) moreRef.current.open = false; newChat() }}><PenLine size={15} />新对话</button>
                    <a href="#/import"><BookOpen size={15} />导入资料</a>
                    <a href="#/snapshots"><Plus size={15} />保存网页</a>
                  </div>
                </details>
                <a className="sl-chat__permissions" href={`#/multi-agent/${encodeURIComponent(selectedAgentId ?? CORE_AGENT_ID)}`} title="配置当前 Agent 的工具权限"><ShieldCheck size={15} /><span>工具权限</span></a>
              </div>
              <div className={`sl-chat__compose-right${dictation.supported ? '' : ' without-dictation'}`}>
              <ComposerModelPicker models={settings?.models.map(model => ({ id: model.id, name: model.name, model: model.model, efforts: model.reasoningEfforts })) ?? []}
                value={modelId} inheritedModelId={inheritedModelId} inheritedLabel={settings?.models.find(model => model.id === inheritedModelId)?.model || '跟随 Agent'}
                effort={supportedEffort} onModelChange={value => { setModelId(value); setReasoningEffort(undefined) }} onEffortChange={setReasoningEffort}
                disabled={busy || creating || loading} loading={settingsLoading} error={settingsError} onRetry={reloadSettings} />
              {dictation.supported && <button type="button" className={`sl-chat__dictate${dictation.listening ? ' is-listening' : ''}`} title={dictation.listening ? '结束听写' : '语音输入'} aria-label={dictation.listening ? '结束听写' : '语音输入'} aria-pressed={dictation.listening} disabled={busy || creating || loading || messageError} onClick={toggleDictation}><Mic size={18} /></button>}
              {busy ? <button type="button" className="sl-chat__send is-stop" title="停止回复" aria-label="停止回复" onClick={() => sessionId && stopChat(sessionId)}><Square size={14} fill="currentColor" /></button>
                : !text.trim() && dictation.supported ? <button type="button" className={`sl-chat__send${dictation.listening ? ' is-stop' : ''}`} title={dictation.listening ? '结束听写' : '语音输入'} aria-label={dictation.listening ? '结束听写' : '语音输入'} disabled={creating || loading || messageError} onClick={toggleDictation}>{dictation.listening ? <Square size={12} fill="currentColor" /> : <AudioLines size={18} />}</button>
                : <button type="submit" className="sl-chat__send" title="发送消息" aria-label="发送消息" disabled={!text.trim() || creating || loading || messageError}><ArrowUp size={18} strokeWidth={2.2} /></button>}
              </div>
            </div>
          </form>
          {empty && <div className="sl-chat__suggestions">{SUGGESTIONS.map(({ label, text: suggestion, icon: Icon }) => <button type="button" key={label} onClick={() => { setText(suggestion); taRef.current?.focus() }}><Icon size={15} />{label}</button>)}</div>}
        </div>
        {toast && <div className="sl-chat__toast" role="status">{toast}<button type="button" aria-label="关闭提示" onClick={() => setToast(null)}><X size={14} /></button></div>}
      </main>
    </div>
  )
}
