/**
 * 圆桌页：一群具名成员在同一张圆桌上轮流说话。
 *
 * <p>与成员页（/multi-agent）分开成两个页面：这一页只负责「对话」，成员是谁、长什么样
 * 都在成员页维护。入口是首页的「群聊」（{@code #/roundtable}）——打开最近一次会话。
 *
 * <p>说一句话会依次引出一串成员发言：谁先说、说到哪、谁沉默了、谁提议结束、话题何时收束，
 * 全部实时可见（成员状态条与消息流都随 SSE 增量更新）。流式口径：一次发言 = 一段 SSE
 * （speaker → 增量 → speakerEnd），下一位紧接着再来一段。已说完的发言在本地即时落成气泡，
 * 正在说话的单独渲染成一条「直播中」的气泡；整轮结束后以服务端为准重新拉一遍消息。
 *
 * <p>与首页对话页不复用：多智能体有自己的会话与消息（后端 ma_* 一套表），
 * 页面也因此自成一套——左侧选会话，右侧是这张圆桌。
 */

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
import remarkGfm from 'remark-gfm'
import {
  ApiError,
  deleteMaSession,
  createMaSession,
  getSettings,
  listMaPeers,
  listMaSessions,
  maChatStream,
  maMessageContext,
  maMessages,
  maTopics,
  stopMaChat,
  MA_STATE_LABELS,
  MA_SPEAKER_STRATEGIES,
  type MaMessage,
  type MaPeer,
  type MaSession,
  type MaTopic,
  type TraceSegment,
} from '../api/client'
import { btnOutline, btnPrimary, miniBtn } from '../components/ui'
import { Field, Select } from '../components/formKit'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import Toast from '../components/Toast'

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

/** 本地临时消息还没有落库时间（要等整轮回读）：先用此刻顶上，hover 与话题标记都要它 */
function nowIso(): string {
  return new Date().toISOString()
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
 * 一轮流式发言的进行态：正在说话的那一位，整轮攒在这里。
 *
 * <p>一次发言可以由好几段组成（边说边想）：每来一次 speak 就多一段，一段一个气泡。
 * 思考链是整轮的、挂在**第一段**上——落到库里也是第一段带 trace、后面几段不带，
 * 于是刷新前后是同一个形状（否则实时几条、刷新后并成一条）。
 */
type Live = {
  peerId: string
  peerName: string
  /** 整轮的思考过程：随增量实时分段（思考 / 调用工具），与落库后的 trace 同形 */
  trace: TraceSegment[]
  /** 说出口的段落（一段一个气泡）；最后一段是还在长的那个，一轮没开口时为空 */
  segments: string[]
  /** 这条发言的开始时刻：流式途中没有落库时间，hover 时的气泡时间用它 */
  at: string
}

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

/**
 * 把一段 reasoning 增量并进分段数组：接续上一段普通思考；上一段是工具调用时另起一段
 * （与对话页 chatLive 同一规则，这里拿不到轮次，round 统一留空）。
 * 实时分段与落库后的 trace 同形，于是思考与工具调用能边跑边渲染，不必等整轮跑完回读。
 */
function appendReasoning(trace: TraceSegment[], delta: string): TraceSegment[] {
  const last = trace[trace.length - 1]
  if (last && last.type === 'reasoning') {
    const next = trace.slice()
    next[next.length - 1] = { ...last, text: (last.text ?? '') + delta }
    return next
  }
  /* 跨轮时后端会在首个思考分片前补一条分隔线，那不是思考内容 */
  return [
    ...trace,
    { type: 'reasoning', text: delta.replace(/^\n*---\n+/, ''), name: null, args: null, round: null },
  ]
}

/** 圆桌页（路由 /roundtable）：左侧选会话，右侧是这张圆桌 */
export default function Roundtable() {
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
    /* 整页只有这一块：占满可用高度，滚动都发生在容器内部——底部的输入框因此始终可见。
       顶部不再放标题与说明，页面全留给对话本身。 */
    <section className="flex h-full flex-col p-3 lg:p-5">
      <PageBoundary>
        <RoundtableBoard
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
    </section>
  )
}

/* ---------------- 圆桌本体 ---------------- */

function RoundtableBoard({
  peers,
  peerById,
  modelOptions,
  notify,
}: {
  peers: MaPeer[]
  peerById: Map<string, MaPeer>
  modelOptions: Option[]
  notify: (msg: string, kind?: 'ok' | 'err') => void
}) {
  const [sessions, setSessions] = useState<MaSession[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<MaMessage[]>([])
  const [topics, setTopics] = useState<MaTopic[]>([])
  const [modelId, setModelId] = useState('')
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [live, setLive] = useState<Live | null>(null)
  const [done, setDone] = useState<MaMessage[]>([])
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
  const [pickerOpen, setPickerOpen] = useState(false)
  const abortRef = useRef<AbortController | null>(null)
  /** 本地临时消息的递减 id：一次流里会连着来好几条（状态行 / 发言），用时间戳会撞 key */
  const localSeq = useRef(0)
  const scrollRef = useRef<HTMLDivElement>(null)
  /** 正在补全的 @提及：null 表示没在补；query 是 @ 后面已敲的片段，index 是当前高亮项 */
  const [mention, setMention] = useState<{ start: number; query: string; index: number } | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  /** 候选成员：按 @ 后面的片段过滤（空片段=全桌），名字与后端 @ 解析用的是同一个 */
  const mentionMatches = useMemo(() => {
    if (!mention) return []
    const q = mention.query.toLowerCase()
    return q ? peers.filter((p) => p.name.toLowerCase().includes(q)) : peers
  }, [mention, peers])

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
    try {
      const list = await listMaSessions()
      setSessions(list)
      /* 还没选中任何一个会话时，默认打开最近一次（列表本来就是按最近排序的） */
      setSessionId((current) => current ?? list[0]?.id ?? null)
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  const reloadSession = useCallback(
    async (id: string) => {
      try {
        const [msgs, tps] = await Promise.all([maMessages(id), maTopics(id)])
        setMessages(msgs)
        setTopics(tps)
      } catch (e) {
        notify(errMsg(e), 'err')
      }
    },
    [notify],
  )

  useEffect(() => {
    void reloadSessions()
  }, [reloadSessions])

  useEffect(() => {
    if (sessionId) void reloadSession(sessionId)
  }, [sessionId, reloadSession])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight })
  }, [messages, done, live])

  /* 上下文浮层：Esc 关闭（点背景关闭在浮层自己身上） */
  useEffect(() => {
    if (!contextView) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setContextView(null)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [contextView])

  const current = topics.length > 0 ? topics[topics.length - 1] : null

  /**
   * 有人调了 ask_user：话题置 waiting，全桌就此暂停、等用户这一句话。
   * 状态条上那位成员会显示「等待用户」，这里再把名字解出来给提示条用。
   */
  const awaiting = current?.status === 'waiting'
  const awaitingName =
    current?.status === 'waiting'
      ? (Object.entries(current.states).find(([, s]) => s === 'AWAIT_USER')?.[0] ?? null)
      : null

  /** 新建会话：勾选的成员上桌（空选 = 全体名册），建完直接切过去 */
  const newSession = async (memberIds: string[]) => {
    try {
      const created = await createMaSession(memberIds)
      await reloadSessions()
      setSessionId(created.sessionId)
      setMessages([])
      setTopics([])
      setPickerOpen(false)
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }

  const removeSession = async (id: string) => {
    try {
      await deleteMaSession(id)
      setSessionId(null)
      await reloadSessions()
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }

  /** 点开一条成员发言：取它当时真正发出去的请求体（system + 历史 + 末尾交代 + 工具面） */
  const openContext = async (m: MaMessage) => {
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
  }

  const send = async () => {
    const text = input.trim()
    if (!text || busy) return
    if (peers.length === 0) {
      notify('圆桌上还没有人，先去「多智能体」页加几位成员', 'err')
      return
    }
    setInput('')
    setMention(null)
    setBusy(true)
    /* 人的话立刻上屏：后端要整轮跑完才回读落库数据，不能让这句话等（它其实一发出就落库了） */
    setDone([
      {
        id: --localSeq.current,
        sessionId: sessionId ?? '',
        topicId: null,
        role: 'user',
        peerId: null,
        peerName: null,
        content: text,
        reasoning: null,
        trace: null,
        createdAt: nowIso(),
      },
    ])
    const controller = new AbortController()
    abortRef.current = controller
    let topicId: string | null = null
    let pending: Live | null = null
    /**
     * 把这一轮就地落成气泡：说完了、或轮到下一位时调用。
     *
     * <p>一段一行（第一段带上整轮的思考链），与后端落库的形状一致——刷新后回读到的正是这些行。
     *
     * <p>先把这一刻的值固化下来：setDone 的 updater 是延迟执行的（React 到渲染期才跑它），
     * 到那时 pending 早被置空了——直接在 updater 里读 pending! 会在渲染期抛
     * 「Cannot read properties of null」把整页打成白屏（踩过一次）。
     */
    const commitLive = () => {
      if (!pending) return
      const finished = pending
      const finishedTopic = topicId
      pending = null
      setLive(null)
      /* 只落真说了话的段落：一句话没说（如只调 go_silent 转了沉默）就不留气泡——
         后端那边同样不落行，于是实时与刷新后是同一个形状 */
      const segments = finished.segments.filter((s) => s.trim())
      setDone((prev) => [
        ...prev,
        ...segments.map(
          (content, i): MaMessage => ({
            id: --localSeq.current,
            sessionId: sessionId ?? '',
            topicId: finishedTopic,
            role: 'peer',
            peerId: finished.peerId,
            peerName: finished.peerName,
            content,
            reasoning: null,
            trace: i === 0 && finished.trace.length > 0 ? finished.trace : null,
            createdAt: nowIso(),
          }),
        ),
      ])
    }
    /** 把增量并进当前这一段（还没有段落时就开一段） */
    const appendSegment = (delta: string) => {
      if (!pending) return
      const segments = pending.segments.slice()
      if (segments.length === 0) {
        segments.push(delta)
      } else {
        segments[segments.length - 1] += delta
      }
      pending = { ...pending, segments }
    }
    try {
      const result = await maChatStream(
        text,
        sessionId ?? undefined,
        modelId || undefined,
        speakerStrategy,
        {
          onTopic: (t) => {
            topicId = t.topicId
            setSessionId(t.sessionId)
          },
          onSpeaker: (s) => {
            pending = {
              peerId: s.peerId,
              peerName: s.peerName,
              trace: [],
              segments: [],
              at: nowIso(),
            }
            setLive(pending)
          },
          onState: (t) => {
            /* 状态快照随时到：就地替换这个话题，状态条于是边聊边刷新（不必等整轮跑完） */
            setTopics((prev) => [...prev.filter((x) => x.id !== t.id), t])
          },
          onContent: (d) => {
            appendSegment(d)
            if (pending) setLive(pending)
          },
          onReasoning: (d) => {
            if (!pending) return
            pending = { ...pending, trace: appendReasoning(pending.trace, d) }
            setLive(pending)
          },
          onTool: (t) => {
            if (!pending) return
            /* 工具调用即时成一段：与思考同一条流，调用一发生就渲染出来，不等整轮跑完 */
            pending = {
              ...pending,
              trace: [
                ...pending.trace,
                { type: 'tool', text: null, name: t.name, args: t.args, round: null },
              ],
            }
            setLive(pending)
          },
          onSpeech: (text) => {
            if (!pending) return
            /* 又开口说了一段（一次 speak）：最后那段已经有内容就另起一格；
               最后那段还空着（本轮先出思考、话才到）就直接装进它——思考与它引出的话同在一格 */
            const segments = pending.segments.slice()
            if (segments.length > 0 && !segments[segments.length - 1].trim()) {
              segments[segments.length - 1] = text
            } else {
              segments.push(text)
            }
            pending = { ...pending, segments }
            setLive(pending)
          },
          onSpeakerEnd: () => {
            /* 这一位说完了：整轮就地落成气泡（一段一格），下一位接着说 */
            commitLive()
          },
          onAction: (text) => {
            /* 状态机动作行（开话题、入场、转沉默……）：后端一落库就推过来，即刻居中显示 */
            setDone((prev) => [
              ...prev,
              {
                id: --localSeq.current,
                sessionId: sessionId ?? '',
                topicId: topicId,
                role: 'event',
                peerId: null,
                peerName: null,
                content: text,
                reasoning: null,
                trace: null,
                createdAt: nowIso(),
              },
            ])
          },
        },
        controller.signal,
      )
      /* 用后端回执里的会话/话题 id 回读：闭包里的 sessionId 在「无会话直接开说」时还是 null */
      await reloadSession(result.sessionId)
      /* 草稿态下第一句才让后端建了会话：刷新左侧列表，让它归到历史里 */
      await reloadSessions()
      /* 落库那份已到位：本地这批临时气泡（人的话、状态行、发言）就地交出位置，免得一闪重复 */
      setDone([])
    } catch (e) {
      const aborted = e instanceof DOMException && e.name === 'AbortError'
      if (!aborted) notify(errMsg(e), 'err')
      setLive(null)
    } finally {
      setDone([])
      setBusy(false)
      abortRef.current = null
    }
  }

  const stop = async () => {
    if (!sessionId) return
    try {
      await stopMaChat(sessionId)
      notify('已请当前发言人打住', 'ok')
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

  return (
    /* 一张主容器：左边是贴着它的目录（会话 / 配置 / 在座），右边整块留给这张圆桌 */
    <div className="flex min-h-0 flex-1 rounded-2xl border border-line">
      {/* 左：目录。会话列表自己滚，底下的配置与在座钉住不动 */}
      <aside className="flex min-h-0 w-[164px] shrink-0 flex-col gap-2 border-r border-line p-3 lg:w-[220px]">
        <button
          type="button"
          className={`${btnOutline('rounded-lg')} shrink-0`}
          onClick={() => setPickerOpen(true)}
        >
          + 新会话
        </button>
        {/* 兜底模型放在上面：它的下拉往会话列表的方向展开，不会被容器底边切掉 */}
        <div className="shrink-0">
          <Field label="兜底模型" hint="成员没指定模型时用它">
            <Select value={modelId} onChange={setModelId} options={modelOptions} />
          </Field>
        </div>
        <div className="scrollbar-slim flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto">
          {sessions.map((s) => (
            <div
              key={s.id}
              className={`group flex items-center gap-2 rounded-lg border px-3 py-2 transition ${
                s.id === sessionId ? 'border-line-strong bg-fg/5' : 'border-line hover:bg-fg/5'
              }`}
            >
              <button
                type="button"
                className="min-w-0 flex-1 text-left"
                onClick={() => setSessionId(s.id)}
              >
                {/* 这一行要说的不是「这个会话叫什么」，而是「和谁在说」：头像 + 人数 */}
                <span className="flex items-center gap-1">
                  {membersOf(s.memberIds)
                    .slice(0, SESSION_AVATAR_LIMIT)
                    .map((p) => (
                      <PeerAvatar
                        key={p.id}
                        name={p.name}
                        avatar={p.avatar}
                        size="h-5 w-5"
                        textClass="text-[9px]"
                      />
                    ))}
                  {s.memberIds.length > SESSION_AVATAR_LIMIT && (
                    <span className="text-[10px] text-hint">
                      +{s.memberIds.length - SESSION_AVATAR_LIMIT}
                    </span>
                  )}
                </span>
                <span className="mt-1 block text-[11px] text-muted">
                  {s.memberIds.length > 0 ? `${s.memberIds.length} 人会话` : '暂无成员'}
                </span>
              </button>
              <button
                type="button"
                className="shrink-0 text-[11px] text-hint opacity-0 transition group-hover:opacity-100"
                onClick={() => void removeSession(s.id)}
              >
                删除
              </button>
            </div>
          ))}
          {sessions.length === 0 && (
            <p className="px-1 text-[11px] text-hint">还没有会话，点上面新建一个</p>
          )}
        </div>
        {seated.length > 0 && (
          <div className="scrollbar-slim mt-1 flex max-h-40 shrink-0 flex-col gap-1.5 overflow-y-auto border-t border-line pt-3">
            <p className="text-[10px] font-medium uppercase tracking-[0.18em] text-faint">
              在座
            </p>
            {seated.map((p) => (
              <span key={p.id} className="flex items-center gap-2 text-[11.5px] text-muted">
                <PeerAvatar
                  name={p.name}
                  avatar={p.avatar}
                  size="h-6 w-6"
                  textClass="text-[10px]"
                />
                <span className="truncate">{p.name}</span>
              </span>
            ))}
          </div>
        )}
      </aside>

      {/* 右：这一张圆桌 */}
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 话题状态条：内容全部挂在当前话题上——还没开谈时整条不出现，别留一条空横栏 */}
        {current && (
          <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-4 py-2.5">
            <div className="flex flex-wrap items-center gap-1">
              {Object.entries(current.states).map(([name, state]) => {
                /* 正在说话的那位高亮一下：状态条也要看得出「此刻是谁的回合」 */
                const speaking = live?.peerName === name
                return (
                  <span
                    key={name}
                    className={`flex items-center gap-1 rounded-full border px-1.5 py-0.5 text-[11px] transition ${
                      speaking
                        ? 'border-line-strong bg-fg/10 font-medium text-fg'
                        : 'border-line text-muted'
                    }`}
                  >
                    <PeerAvatar
                      name={name}
                      avatar={avatarOfName(name)}
                      size="h-4 w-4"
                      textClass="text-[8px]"
                    />
                    {name} · {MA_STATE_LABELS[state] ?? state}
                    {speaking ? ' · 正在说' : ''}
                  </span>
                )
              })}
            </div>
          </div>
        )}

        {/* 消息：这一块自己滚（min-h-0 才真的滚得起来），把输入框留给容器底部 */}
        <div ref={scrollRef} className="scrollbar-slim min-h-0 flex-1 overflow-y-auto px-4 py-4">
          {messages.length === 0 && !live && done.length === 0 && (
            <p className="py-16 text-center text-[12px] text-hint">
              说一句话，圆桌上的人会依次应答
            </p>
          )}
          <div className="flex flex-col gap-4">
            {messages.filter((m) => showEventLine(m, showPresence, peerById)).map((m) => (
              <MessageItem
                key={m.id}
                msg={m}
                avatar={avatarOf(m.peerId)}
                showCot={!hideCot}
                peerById={peerById}
                /* 只有落库的成员发言才有原始上下文可看（人的话不是一次模型调用）；
                   这个入口也由「⋯」菜单里的开关控制，默认关 */
                onClick={
                  allowContext && m.role === 'peer' ? () => void openContext(m) : undefined
                }
              />
            ))}
            {done
              .filter((m) => showEventLine(m, showPresence, peerById))
              .map((m) => (
                <MessageItem
                  key={m.id}
                  msg={m}
                  avatar={avatarOf(m.peerId)}
                  showCot={!hideCot}
                  peerById={peerById}
                />
              ))}
            {/* 正在说话的那一位：一次发言可能分好几段（边说边想），一段一个气泡；
                思考链挂在第一段上——与落库后回读到的形状一致，刷新前后不跳 */}
            {live &&
              (live.segments.length > 0 ? (
                live.segments.map((content, i) => (
                  <Bubble
                    key={i}
                    role="peer"
                    name={live.peerName}
                    avatar={avatarOf(live.peerId)}
                    content={content}
                    trace={i === 0 ? live.trace : null}
                    createdAt={live.at}
                    streaming={i === live.segments.length - 1}
                    showCot={!hideCot}
                  />
                ))
              ) : (
                <Bubble
                  role="peer"
                  name={live.peerName}
                  avatar={avatarOf(live.peerId)}
                  content=""
                  trace={live.trace}
                  createdAt={live.at}
                  streaming
                  showCot={!hideCot}
                />
              ))}
          </div>
        </div>

        {/* 等待用户：某位成员请了用户表态（话题置 waiting），全桌暂停——这条提示贴在输入框正上方，
            免得「现在轮到你」只藏在状态条那个「等待用户」里。正在跑一轮（busy）时不出现，
            因为此刻还轮不到人说话 */}
        {awaiting && !busy && (
          <div className="flex shrink-0 flex-wrap items-center gap-x-2 gap-y-0.5 border-t border-line bg-accent/10 px-4 py-2">
            <span
              className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-accent"
              aria-hidden="true"
            />
            <p className="text-[12px] font-medium text-fg">
              {awaitingName ? `${awaitingName} 在等你表态` : '圆桌在等你表态'}
            </p>
            <p className="text-[11px] text-muted">全桌已暂停，说一句就继续</p>
          </div>
        )}

        {/* 输入：钉在容器底部，永远看得见。
            输入框与发送按钮包在同一个边框里，并用 items-center 对齐——两者天然在同一条水平线上 */}
        <div className="shrink-0 border-t border-line px-4 py-3">
          <div className="relative flex items-center gap-2 rounded-2xl border border-line bg-bg py-1.5 pl-4 pr-1.5 transition hover:border-line-strong focus-within:border-secondary">
            {mention && mentionMatches.length > 0 && (
              <MentionMenu
                peers={mentionMatches}
                activeIndex={mentionActive}
                onPick={applyMention}
              />
            )}
            <textarea
              ref={inputRef}
              className="scrollbar-slim max-h-40 min-h-[40px] w-full resize-none bg-transparent py-2 text-[13px] leading-relaxed text-fg outline-none placeholder:text-hint"
              rows={2}
              placeholder="说点什么…（@名字 可以让指定的人先应答）"
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
            {busy ? (
              <button
                type="button"
                onClick={() => void stop()}
                title="停止"
                aria-label="停止"
                className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-line text-muted transition hover:border-line-strong hover:bg-fg/5 hover:text-fg"
              >
                <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              </button>
            ) : (
              <button
                type="button"
                onClick={() => void send()}
                title="发送"
                aria-label="发送"
                className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full bg-accent text-accent-fg transition hover:bg-accent-hover"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M21 3 10.5 13.5M21 3l-6.8 18-3.7-7.5L3 9.8 21 3z"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 新建会话的选人弹窗 */}
      {pickerOpen && (
        <NewSessionDialog
          peers={peers}
          onCancel={() => setPickerOpen(false)}
          onCreate={(ids) => void newSession(ids)}
        />
      )}

      {/* 原始上下文浮层：点开一条发言时打开；Esc 或点背景关闭 */}
      {contextView && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 p-6"
          onClick={() => setContextView(null)}
        >
          <div
            className="flex max-h-[82vh] w-full max-w-3xl flex-col rounded-2xl border border-line bg-bg"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
              <p className="text-[13px] font-medium text-fg">原始上下文 · {contextView.title}</p>
              <button type="button" className={miniBtn} onClick={() => setContextView(null)}>
                关闭
              </button>
            </div>
            <p className="border-b border-line px-4 py-2 text-[11px] text-hint">
              这是它这次发言当时真正发出去的那一份：system（身份 / 协作规程 / 局面）、
              共享话题历史、末尾的发言权交代，以及挂了哪些工具。
            </p>
            <pre className="scrollbar-slim flex-1 overflow-auto px-4 py-3 font-mono text-[11px] leading-relaxed whitespace-pre-wrap break-all text-muted">
              {contextView.text}
            </pre>
          </div>
        </div>
      )}
    </div>
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
        className="flex w-full cursor-pointer items-center gap-1.5 px-3 py-1.5 text-[11px] text-hint transition hover:text-muted"
      >
        <svg
          className={`h-3 w-3 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`}
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M9 5l7 7-7 7"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <span className="min-w-0 flex-1 truncate text-left">{label}</span>
      </button>
      {open && (
        <div
          ref={bodyRef}
          className="scrollbar-slim max-h-64 overflow-y-auto border-t border-line px-3 py-2 text-[12.5px] leading-relaxed text-muted"
        >
          {code !== undefined ? (
            <pre className="whitespace-pre-wrap break-all font-mono text-[12px]">{code}</pre>
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
        className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12px] transition hover:bg-fg/5 ${
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
      <p className="px-2 pb-1.5 pt-1 text-[11px] leading-relaxed text-hint">{hint}</p>
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
            className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12px] transition hover:bg-fg/5 ${
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
        <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="1.7" />
          <circle cx="12" cy="12" r="1.7" />
          <circle cx="19" cy="12" r="1.7" />
        </svg>
      </button>

      {open && (
        <div className="absolute bottom-[calc(100%+8px)] right-0 z-30 w-[252px] rounded-xl border border-line bg-bg p-1.5 shadow-xl">
          <p className="px-2 pb-1 pt-1.5 text-[10px] font-medium uppercase tracking-[0.18em] text-faint">
            显示
          </p>
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
          <p className="px-2 pb-1 pt-0.5 text-[10px] font-medium uppercase tracking-[0.18em] text-faint">
            发言策略
          </p>
          <MenuChoice
            options={MA_SPEAKER_STRATEGIES}
            value={speakerStrategy}
            onChange={onChangeSpeakerStrategy}
          />
          <p className="px-2 pb-1.5 pt-1.5 text-[11px] leading-relaxed text-hint">
            这一轮该谁先开口：轮询＝按名册顺序依次交接；随机＝每次掷骰子；发言欲望优先＝每位成员说完
            报一个 0~10 的欲望，高的先开口。下一次发言生效。
          </p>
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
    <div className="absolute bottom-[calc(100%+8px)] left-0 z-30 w-[280px] overflow-hidden rounded-xl border border-line bg-bg shadow-xl">
      <p className="px-3 pt-2 pb-1 text-[10px] font-medium uppercase tracking-[0.18em] text-faint">
        提及成员
      </p>
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
            <PeerAvatar name={p.name} avatar={p.avatar} size="h-7 w-7" textClass="text-[11px]" />
            <span className="flex-1 truncate text-[12px]">{p.name}</span>
          </button>
        ))}
      </div>
    </div>
  )
}

/** 会话行里最多画几个头像，多出来的用 +N 表示（左栏窄，画不下太多） */
const SESSION_AVATAR_LIMIT = 4

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
}) {
  const mine = role === 'user'
  const label = mine ? '你' : name ?? '成员'
  const time = fmtTime(createdAt)

  const meta = (
    <span className="flex items-center gap-1.5 text-[11px] text-hint">
      {label}
      {streaming && <span className="text-muted">正在说…</span>}
      {onClick && !streaming && <span>· 点开看当时的上下文</span>}
    </span>
  )

  const body = (
    <div
      onClick={onClick}
      title={onClick ? '点开看它这次发言的原始上下文' : undefined}
      className={`rounded-2xl border px-3.5 py-2.5 text-[13px] leading-relaxed ${
        mine
          ? 'border-line bg-fg/5 text-fg'
          : `${BUBBLE_MAX_W} border-line bg-surface-container-low/50 text-fg`
      }${onClick ? ' cursor-pointer transition hover:border-line-strong' : ''}`}
    >
      {/* 思考过程：没有思考时它什么都不渲染；「不显示思维链」开着时整块不渲染 */}
      {showCot && <CotBlocks reasoning={reasoning} trace={trace} live={!!streaming} />}
      {content ? (
        <div className="prose-chat [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
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
        <span className="self-end pr-1 font-mono text-[10.5px] leading-none text-faint">
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

/** 行尾的（理由）：后端 reason() 只会拼在末尾，所以从尾部切最稳 */
const REASON_RE = /（([^）]*)）\s*$/

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
): EventParts {
  const m = REASON_RE.exec(text)
  const reason = m ? cleanReason(m[1]) : null
  const body = (m ? text.slice(0, m.index) : text).trim()

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

  return { peer, name, action: name ? body.slice(name.length).trim() : body, reason }
}

/**
 * 这几条状态行居中显示；其余「谁主动做了什么」（转沉默、提议结束、举手抢答……）左对齐。
 *
 * <p>居中的几条要么没有主角（话题开始），要么是圆桌层面的进出与系统动作——都不是某个成员对
 * 话题的表态，跟上下成员气泡摆在同一列反而混淆。「撤回结束提议，重新决策」虽逐人各来一条
 * （重开时每个此前投过结束票的人各撤一条），讲的却是整桌的事：话题被重新打开了，所以也居中。
 *
 * <p>文案取自后端 MaTopicService#line 的固定输出，后端改文案时这里要跟着改。
 * （「话题开始」在 {@link EventLine} 里单独走时间标记那条分支。）
 */
const CENTERED_ACTIONS = new Set([
  '入场',
  '退场',
  '被点名',
  '被要求立刻表态',
  '重新参与讨论',
  '撤回结束提议，重新决策',
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
}: {
  text: string
  peerId: string | null
  peerName: string | null
  /** 这条动作发生的时间（话题开始那条标记用它） */
  createdAt: string | null
  peerById: Map<string, MaPeer>
}) {
  const { peer, name, action, reason } = parseEventLine(text, peerId, peerName, peerById)

  if (action === '话题开始') {
    return (
      <div className="flex justify-center">
        <span className="rounded-full border border-line px-2.5 py-1 font-mono text-[10.5px] tracking-wide text-faint">
          {fmtTime(createdAt, true) || '话题开始'}
        </span>
      </div>
    )
  }

  const pill = (
    <p className="flex flex-wrap items-center gap-1.5 rounded-full border border-line px-2.5 py-1 text-[11px] text-muted">
      {name && (
        <span className="shrink-0" title={name}>
          <PeerAvatar
            name={name}
            avatar={peer?.avatar ?? null}
            size="h-4 w-4"
            textClass="text-[8px]"
          />
        </span>
      )}
      <span>{action}</span>
      {reason && (
        <span className="rounded-full bg-fg/[0.07] px-1.5 py-0.5 text-[10.5px] text-hint">
          {reason}
        </span>
      )}
    </p>
  )
  /* 不是成员的主动表态（进出 / 系统动作 / 话题本身）：居中，不占左边那一格头像 */
  if (CENTERED_ACTIONS.has(action)) {
    return <div className="flex justify-center">{pill}</div>
  }
  return (
    /* 左边空出头像那一格：状态行于是和上下成员气泡的正文左对齐 */
    <div className="flex items-start gap-2.5">
      <span className={`${BUBBLE_AVATAR_SIZE} shrink-0`} aria-hidden="true" />
      {pill}
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
  return !NOISY_ACTIONS.includes(
    parseEventLine(msg.content, msg.peerId, msg.peerName, peerById).action,
  )
}

/** 一条消息：状态机动作行走居中的说明行，其余（人的话 / 成员发言）走气泡 */
function MessageItem({
  msg,
  avatar,
  showCot,
  peerById,
  onClick,
}: {
  msg: MaMessage
  avatar?: string | null
  showCot: boolean
  /** 状态行要按名字认出主角才能换头像，所以名册得传进来 */
  peerById: Map<string, MaPeer>
  onClick?: () => void
}) {
  if (msg.role === 'event') {
    return (
      <EventLine
        text={msg.content}
        peerId={msg.peerId}
        peerName={msg.peerName}
        createdAt={msg.createdAt}
        peerById={peerById}
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
      onClick={onClick}
    />
  )
}

/**
 * 新建会话的选人弹窗：一面头像墙，鼠标移到谁身上、谁就渐入一张卡片（名称 + 备注），
 * 点一下选中。选中的几位组成这张桌子。
 *
 * <p>一位都不选也建得出来——那是「全体名册」（后端在没有会话时自动开的那一桌也是这种）。
 */
function NewSessionDialog({
  peers,
  onCancel,
  onCreate,
}: {
  peers: MaPeer[]
  onCancel: () => void
  onCreate: (memberIds: string[]) => void
}) {
  const [picked, setPicked] = useState<string[]>([])
  const toggle = (id: string) =>
    setPicked((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  const allPicked = peers.length > 0 && picked.length === peers.length
  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 p-6"
      onClick={onCancel}
    >
      <div
        className="flex max-h-[82vh] w-full max-w-2xl flex-col rounded-2xl border border-line bg-bg"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <p className="text-[13px] font-medium text-fg">新建会话 · 选人上桌</p>
          <button type="button" className={miniBtn} onClick={onCancel}>
            取消
          </button>
        </div>
        <p className="border-b border-line px-4 py-2 text-[11px] leading-relaxed text-hint">
          勾选的成员组成这张桌子，没勾的不会出现在这次会话里；一位都不勾就是全体名册。
          选了就定下来——以后往名册里加人，也不会自动加进这张桌子。
        </p>
        <div className="scrollbar-slim flex min-h-[7rem] flex-1 flex-wrap gap-3 overflow-y-auto px-4 py-4">
          {peers.map((p) => (
            <MemberTile
              key={p.id}
              peer={p}
              picked={picked.includes(p.id)}
              onToggle={() => toggle(p.id)}
            />
          ))}
          {peers.length === 0 && (
            <p className="w-full py-6 text-center text-[12px] text-hint">
              名册还是空的：先去「多智能体 · 成员」页添几位
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 border-t border-line px-4 py-3">
          <span className="mr-auto text-[11px] text-muted">
            {picked.length > 0 ? `已选 ${picked.length} 位` : '未选 = 全体名册上桌'}
          </span>
          {peers.length > 0 && (
            <button
              type="button"
              className={miniBtn}
              onClick={() => setPicked(allPicked ? [] : peers.map((p) => p.id))}
            >
              {allPicked ? '全不选' : '全选'}
            </button>
          )}
          <button type="button" className={btnPrimary()} onClick={() => onCreate(picked)}>
            创建
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 选人弹窗里的一格：默认只看得到头像；hover 或已选中时，那张卡片（名称 + 备注）渐入。
 *
 * <p>卡片与头像叠在同一格里做交叉渐入（而不是浮在格子外）——浮层会撑大滚动区域，
 * 靠边的格子还会带出一条横向滚动条。
 */
function MemberTile({
  peer,
  picked,
  onToggle,
}: {
  peer: MaPeer
  picked: boolean
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      title={peer.name}
      className={`group relative h-28 w-28 shrink-0 rounded-2xl border p-2 transition ${
        picked ? 'border-secondary bg-secondary/10' : 'border-line hover:border-line-strong'
      }`}
    >
      <span
        className={`absolute inset-0 flex items-center justify-center transition-opacity duration-150 ${
          picked ? 'opacity-0' : 'opacity-100 group-hover:opacity-0'
        }`}
      >
        <PeerAvatar name={peer.name} avatar={peer.avatar} size="h-16 w-16" textClass="text-[22px]" />
      </span>
      <span
        className={`absolute inset-0 flex flex-col items-center justify-center gap-1 px-2 text-center transition-opacity duration-150 ${
          picked ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
        }`}
      >
        <span className="line-clamp-1 w-full text-[12px] font-medium text-fg">{peer.name}</span>
        {peer.note && (
          <span className="line-clamp-3 w-full text-[10.5px] leading-snug text-muted">
            {peer.note}
          </span>
        )}
      </span>
    </button>
  )
}
