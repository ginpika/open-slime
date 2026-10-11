/**
 * 圆桌页：一群具名成员在同一张圆桌上轮流说话。
 *
 * <p>与成员页（/multi-agent）分开成两个页面：这一页只负责「对话」，成员是谁、长什么样
 * 都在成员页维护。入口是首页的「群聊」（{@code #/roundtable}）——打开最近一次会话。
 *
 * <p>说一句话会依次引出一串成员发言：谁先说、说到哪、谁沉默了、谁提议结束、话题何时收束，
 * 全部实时可见（成员状态条与消息流都随 SSE 增量更新）。流式口径：一次发言 = 一段 SSE
 * （speaker → 增量 → speakerEnd），下一位紧接着再来一段。<b>已落库的以服务端为准</b>——
 * 人的话、状态机动作、说完的发言一落库就重取，正在说话的那一位单独渲染成一条「直播中」的气泡；
 * 「实时看到的」与「刷新之后看到的」因此是同一个形状。
 *
 * <p><b>发言期间可以随便走开</b>：这一轮的运行时状态在应用级模块仓
 * （{@link useRoundtableRun}，见 components/roundtableLive），不在本组件里——切会话、切 tab、
 * 切路由都只是「不再渲染」，成员照说；回到这张桌子即从仓里把进度接回来（谁是发言人、说到哪、
 * 话题什么状态），发送键也按「这张桌子在不在跑」各自判定，别的会话因此照常可用。
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
import { useVirtualizer } from '@tanstack/react-virtual'
import {
  ApiError,
  deleteMaSession,
  createMaSession,
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
} from '../api/client'
import { Card, CardHead } from '../components/Card'
import { btnOutline, btnPrimary, miniBtn } from '../components/ui'
import { Select } from '../components/formKit'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import {
  clearRunError,
  getRunningSessionIds,
  onRoundtableSettled,
  startRoundtable,
  stopRoundtable,
  useRoundtableRun,
  useRunningSessionIds,
} from '../components/roundtableLive'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import { useThemeState } from '../theme/store'

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
export default function Roundtable() {
  const kit = usePageKit()
  const sidebar = useThemeState().mono.sidebar
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
    /* kit.Page 出扇区头；圆桌本体占满剩余高度，滚动都发生在容器内部——底部的输入框因此始终可见。
       [&>*]:h-full 把高度传给 kit 渲染的 <section>，于是各主题的 Page 都能撑满整页。
       侧边栏档：页眉已被隐藏（见 kit 的 Page），这里再去掉自己的上边距——
       否则「外层 pt」叠上「Page 的 pt-6」，圆桌页顶部会比别的内容页高出一截。 */
    <div
      className={`h-full [&>*]:h-full ${
        sidebar ? 'px-3 pb-3 lg:px-5 lg:pb-5' : 'p-3 lg:p-5'
      }`}
    >
      <kit.Page
        title="圆桌"
        en="ROUNDTABLE"
        meta={
          <span className="flex items-center gap-3">
            <span className="text-sm text-muted">{peers.length} 位成员</span>
            <a href="#/multi-agent" className={btnOutline('rounded-lg')}>
              管理成员
            </a>
          </span>
        }
      >
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
      </kit.Page>
    </div>
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
  const kit = usePageKit()
  const [sessions, setSessions] = useState<MaSession[]>([])
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [messages, setMessages] = useState<MaMessage[]>([])
  const [topics, setTopics] = useState<MaTopic[]>([])
  const [modelId, setModelId] = useState('')
  const [input, setInput] = useState('')
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
  const [pickerOpen, setPickerOpen] = useState(false)
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
      setSessionId((current) => {
        if (current) return current
        /* 还没选中任何一条：优先落在「正在发言」的那张桌子上——离开再回来要接上的正是它；
           没有在跑的，就打开最近一次（列表本来就是按最近排序的） */
        const live = getRunningSessionIds().find((id) => list.some((s) => s.id === id))
        return live ?? list[0]?.id ?? null
      })
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

  /**
   * 重取这张桌子的消息与话题：会话换了要取，这一轮有新内容落库（run.revision 自增）也要取
   * ——已落库的一律以服务端为准，正在说话的那一位则由仓里的直播气泡补在末尾。
   */
  useEffect(() => {
    if (sessionId) void reloadSession(sessionId)
  }, [sessionId, reloadSession, run?.revision])

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

  /** 程序性平滑滚动进行中：这期间的 onScroll 不算「用户自己滚」（否则按钮会闪一下） */
  const programmaticRef = useRef(false)

  /** 消息区滚动：记下用户是否还贴着底（48px 容差） */
  const onMessagesScroll = () => {
    if (programmaticRef.current) return
    const el = scrollRef.current
    if (!el) return
    const bottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48
    atBottomRef.current = bottom
    setAtBottom((prev) => (prev === bottom ? prev : bottom))
  }

  /** 回到最新：滚到底并恢复自动跟随 */
  const scrollToBottom = () => {
    const el = scrollRef.current
    if (!el) return
    programmaticRef.current = true
    atBottomRef.current = true
    setAtBottom(true)
    el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    window.setTimeout(() => {
      programmaticRef.current = false
    }, 400)
  }

  /* 上下文浮层：Esc 关闭（点背景关闭在浮层自己身上） */
  useEffect(() => {
    if (!contextView) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setContextView(null)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [contextView])

  /* 话题状态条看的是「此刻这一轮」的快照：仓里有一份就用它（边聊边刷新），
     没有（没在跑 / 刚进来）就用回读来的最后一条 */
  const current = run?.topic ?? (topics.length > 0 ? topics[topics.length - 1] : null)

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
   * 本张桌子已在发言时再发一句 = 插话：会打断正在说话的那位、把新一轮排进议程（随时可加入讨论）。
   */
  const send = () => {
    const text = input.trim()
    /* 讨论进行中也能发：这一句会打断正在说话的那位、并把新一轮排进议程——
       随时加入讨论、补充信息是核心设计，故不因 busy 拒绝 */
    if (!text) return
    if (peers.length === 0) {
      notify('圆桌上还没有人，先去「多智能体」页加几位成员', 'err')
      return
    }
    setInput('')
    setMention(null)
    startRoundtable({
      sessionId,
      input: text,
      modelId: modelId || undefined,
      speakerStrategy,
      /* 草稿态（还没有会话）时后端会建一个：把它切过去，左侧列表随本轮收尾一起刷新 */
      onSession: (id) => setSessionId(id),
    })
  }

  const stop = async () => {
    /* 停的是「正在跑的那张桌子」：用户可能已经切到别的会话在看，别停错了人 */
    const id = run?.sessionId || sessionId
    if (!id) return
    try {
      await stopRoundtable(id)
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
    for (const m of messages) {
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
  }, [messages, showPresence, peerById, optimisticUser, live])

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
  }, [hideCot, virtualizer])

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

  return (
    /* 一张主容器：左边是贴着它的目录（会话 / 配置 / 在座），右边整块留给这张圆桌。
       容器交给 Card：描边环 / 面板底随主题（内部还是左目录 + 右圆桌的横排章法）。
       两个弹层是 fixed 定位，放在 Card 外——切角主题会裁剪内部超出容器的绘制 */
    <>
    <Card pad="none" className="ui-card--row flex min-h-0 flex-1">
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
          <kit.Field label="兜底模型" hint="成员没指定模型时用它" wrap={false}>
            <Select value={modelId} onChange={setModelId} options={modelOptions} />
          </kit.Field>
        </div>
        <div className="scrollbar-slim flex min-h-0 flex-1 flex-col overflow-y-auto">
          {sessions.map((s) => (
            /* 会话行：分隔线 + 悬停黄晕染（kit.Row）；选中态用左侧黄条 + 柔黄底标出 */
            <kit.Row
              key={s.id}
              className={`group ${
                s.id === sessionId
                  ? 'bg-accent-soft shadow-[inset_3px_0_0_var(--c-accent)]'
                  : ''
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
                    <span className="text-2xs text-hint">
                      +{s.memberIds.length - SESSION_AVATAR_LIMIT}
                    </span>
                  )}
                </span>
                <span className="mt-1 flex items-center gap-1.5 text-xs text-muted">
                  {runningSessions.includes(s.id) && (
                    /* 这张桌子正在发言：离开页面也照跑，点回来就能接着看 */
                    <span
                      className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-accent"
                      title="正在发言"
                      aria-label="正在发言"
                    />
                  )}
                  {s.memberIds.length > 0 ? `${s.memberIds.length} 人会话` : '暂无成员'}
                </span>
              </button>
              <button
                type="button"
                className="shrink-0 text-xs text-hint opacity-0 transition group-hover:opacity-100 hover:text-danger"
                onClick={() => void removeSession(s.id)}
              >
                删除
              </button>
            </kit.Row>
          ))}
          {sessions.length === 0 && (
            <p className="px-3 py-2 text-xs text-hint">还没有会话，点上面新建一个</p>
          )}
        </div>
        {seated.length > 0 && (
          <div className="scrollbar-slim mt-1 flex max-h-40 shrink-0 flex-col gap-1.5 overflow-y-auto border-t border-line pt-3">
            <p className="ui-label">在座</p>
            {seated.map((p) => (
              <span key={p.id} className="flex items-center gap-2 text-xs text-muted">
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
                /* 正在说话的那位高亮一下：状态条也要看得出「此刻是谁的回合」（accent 填充 = 激活态） */
                const speaking = live?.peerName === name
                return (
                  <kit.Badge key={name} tone={speaking ? 'accent' : 'default'}>
                    <PeerAvatar
                      name={name}
                      avatar={avatarOfName(name)}
                      size="h-4 w-4"
                      textClass="text-[8px]"
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
          ref={scrollRef}
          onScroll={onMessagesScroll}
          className="scrollbar-slim min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-4"
        >
          {messages.length === 0 && !live && !optimisticUser && (
            <kit.Empty>说一句话，圆桌上的人会依次应答</kit.Empty>
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
                  {item.kind === 'msg' ? (
                    <MessageItem
                      msg={item.msg}
                      avatar={avatarOf(item.msg.peerId)}
                      showCot={!hideCot}
                      peerById={peerById}
                      /* 只有落库的成员发言才有原始上下文可看（人的话不是一次模型调用）；
                         这个入口也由「⋯」菜单里的开关控制，默认关 */
                      allowContext={allowContext}
                      onOpenContext={openContext}
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
                className="ui-btn ui-btn--outline px-3 py-1 text-xs shadow-lg"
              >
                ↓ 回到最新
              </button>
            </div>
          )}
        </div>

        {/* 等待用户：某位成员请了用户表态（话题置 waiting），全桌暂停——这条提示贴在输入框正上方，
            免得「现在轮到你」只藏在状态条那个「等待用户」里。正在跑一轮（busy）时不出现，
            因为此刻还轮不到人说话 */}
        {awaiting && !busy && (
          <div className="flex shrink-0 flex-wrap items-center gap-x-2 gap-y-0.5 border-t border-line bg-accent-soft px-4 py-2">
            <span
              className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-accent"
              aria-hidden="true"
            />
            <p className="text-sm font-medium text-fg">
              {awaitingName ? `${awaitingName} 在等你表态` : '圆桌在等你表态'}
            </p>
            <p className="text-xs text-muted">全桌已暂停，说一句就继续</p>
          </div>
        )}

        {/* 输入：钉在容器底部，永远看得见。
            输入框与发送按钮包在同一个边框里，并用 items-center 对齐——两者天然在同一条水平线上 */}
        <div className="shrink-0 border-t border-line px-4 py-3">
          <div className="relative flex items-center gap-2 rounded-2xl border border-line bg-bg py-1.5 pl-4 pr-1.5 transition hover:border-line-strong focus-within:border-accent">
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
              placeholder="说点什么…（@名字 只让指定的人应答）"
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
            {/* 强制暂停：只在有人说话时出现，且**不占发送键**——用户随时可以发言（核心设计） */}
            {busy && (
              <button
                type="button"
                onClick={() => void stop()}
                title="中断全部发言"
                aria-label="中断全部发言"
                className="ui-btn ui-btn--outline h-11 w-11 shrink-0 [--ui-btn-px:0px] [--ui-btn-py:0px]"
              >
                <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              </button>
            )}
            {/* 发送：永远可用——讨论进行中也能随时插话补充（那会打断当前发言并排进新一轮） */}
            <button
              type="button"
              onClick={() => void send()}
              title="发送"
              aria-label="发送"
              className="ui-btn ui-btn--primary h-11 w-11 shrink-0 [--ui-btn-px:0px] [--ui-btn-py:0px]"
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
          </div>
        </div>
      </div>
    </Card>

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
        <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="1.7" />
          <circle cx="12" cy="12" r="1.7" />
          <circle cx="19" cy="12" r="1.7" />
        </svg>
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
            <PeerAvatar name={p.name} avatar={p.avatar} size="h-7 w-7" textClass="text-[11px]" />
            <span className="flex-1 truncate text-sm">{p.name}</span>
          </button>
        ))}
      </div>
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
      className={`rounded-2xl border px-3.5 py-2.5 text-md leading-relaxed ${
        mine
          ? 'border-line bg-fg/5 text-fg'
          : `${BUBBLE_MAX_W} border-line bg-fg/[0.04] text-fg`
      }${onClick ? ' cursor-pointer transition hover:border-accent' : ''}`}
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
  if (kind === 'rally') {
    return (
      <div className="flex justify-center px-1">
        <span
          className="inline-flex max-w-[min(100%,36rem)] min-w-0 items-center gap-1.5 rounded-full px-3 py-1 text-center text-xs leading-relaxed"
          style={{
            background: 'color-mix(in srgb, var(--c-accent) 15%, transparent)',
            color: 'var(--c-accent)',
          }}
        >
          {/* 信号灯钉在最左、相对整块标签垂直居中（多行文案时不再偏到首行） */}
          <span
            className="inline-block h-1.5 w-1.5 shrink-0 self-center animate-pulse rounded-full"
            style={{ background: 'var(--c-accent)' }}
          />
          <span className="min-w-0 whitespace-normal break-words">{text}</span>
        </span>
      </div>
    )
  }
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
    <span className="min-w-0 max-w-full whitespace-normal break-words rounded-full bg-fg/[0.07] px-1.5 py-0.5 font-mono text-2xs leading-relaxed text-hint">
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
                textClass="text-[8px]"
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
                textClass="text-[8px]"
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
          <PeerAvatar name={name ?? '成员'} avatar={avatar} size="h-5 w-5" textClass="text-[9px]" />
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
          <p className="min-w-0 whitespace-normal break-words text-xs leading-relaxed text-fg-2">
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
}) {
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
    />
  )
})

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
  const kit = usePageKit()
  const [picked, setPicked] = useState<string[]>([])
  const toggle = (id: string) =>
    setPicked((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  const allPicked = peers.length > 0 && picked.length === peers.length
  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 p-6"
      onClick={onCancel}
    >
      <Card
        pad="none"
        className="flex max-h-[82vh] w-full max-w-2xl flex-col shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <CardHead
          title="新建会话 · 选人上桌"
          meta={
            <button type="button" className={miniBtn} onClick={onCancel}>
              取消
            </button>
          }
        />
        <p className="border-b border-line px-4 py-2 text-xs leading-relaxed text-hint">
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
            <kit.Empty>名册还是空的：先去「多智能体 · 成员」页添几位</kit.Empty>
          )}
        </div>
        <div className="flex items-center gap-2 border-t border-line px-4 py-3">
          <span className="mr-auto text-xs text-muted">
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
      </Card>
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
  const kit = usePageKit()
  return (
    /* kit.Tile 出切角仪表块（角标 + 悬停切环）；选中态黄环 + 柔黄底都由 ui-card--accent 出 */
    <kit.Tile
      onClick={onToggle}
      className={`group h-28 w-28 shrink-0 ${picked ? 'ui-card--accent' : ''}`}
    >
      <span
        className={`absolute inset-0 z-10 flex items-center justify-center transition-opacity duration-150 ${
          picked ? 'opacity-0' : 'opacity-100 group-hover:opacity-0'
        }`}
      >
        <PeerAvatar name={peer.name} avatar={peer.avatar} size="h-16 w-16" textClass="text-[22px]" />
      </span>
      <span
        className={`absolute inset-0 z-10 flex flex-col items-center justify-center gap-1 px-2 text-center transition-opacity duration-150 ${
          picked ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
        }`}
      >
        <span className="line-clamp-1 w-full text-sm font-medium text-fg">{peer.name}</span>
        {peer.note && (
          <span className="line-clamp-3 w-full text-2xs leading-snug text-muted">
            {peer.note}
          </span>
        )}
      </span>
    </kit.Tile>
  )
}
