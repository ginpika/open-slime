import { AppIcon } from '../components/AppIcon'
import { ModelBrandIcon } from '../components/ModelBrandIcon'
import SpeechButton from '../components/SpeechButton'
import ChatMarkdown from '../components/ChatMarkdown'
import UserQuestionCard from '../components/UserQuestionCard'
import { questionContent, userQuestion } from '../components/userQuestion'
import ConversationBrowser from '../components/ConversationBrowser'
import { OrbitalSeal } from '../shell/AstrolabeShell'
import { backfillSettledReply } from '../components/settledChat'
import { getTtsSettings } from '../api/client'
import {
  forwardRef,
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react'
import {
  bindSessionProject,
  branchSession,
  createSession,
  createProject,
  organizeSessionProject,
  continueSessionProject,
  revokeSessionContinuation,
  deleteSession,
  getPrompts,
  getSettings,
  listMaPeers,
  listProjects,
  listSessions,
  loginUrlOf,
  PROMPT_BUILTIN,
  rewindSession,
  saveRerank,
  sessionLive,
  sessionMessages,
  sessionRawContext,
  sessionTodos,
  updateSessionConfig,
  type MaPeer,
  type Project,
  type PromptsView,
  type RawContext,
  type SessionItem,
  type SessionMessage,
  type SessionTodo,
  type SettingsView,
  type TraceSegment,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import ProjectDialog, { type ProjectChoice } from '../components/ProjectDialog'
import { Select } from '../components/formKit'
import PeerAvatar from '../components/PeerAvatar'
import { RawContextView } from '../components/JsonViewer'
import Toast from '../components/Toast'
import TocRail, { type TocItem } from '../components/TocRail'
import { miniBtn } from '../components/ui'
import { registerChatOpener } from '../components/ChatNotifier'
import {
  attachLive,
  clearLive,
  getLive,
  setActiveChatSession,
  startChat,
  stopChat,
  useLiveChat,
  type ChatMsg,
  type LiveChat,
} from '../components/chatLive'
import { subscribeSessionEvents } from '../components/sessionEvents'
import { useThemeState } from '../theme/store'

type Props = { connErr: string | null }

const projectActionBtn = 'flex w-full items-center gap-2.5 rounded-md border px-3 py-2 text-left text-xs font-medium shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 focus-visible:ring-offset-2 focus-visible:ring-offset-bg disabled:cursor-not-allowed disabled:opacity-50'

/** 记住最近打开的会话：刷新后凭它判断「刚才那条会话是不是还在生成」，是就自动回去续看（断点续传） */
const LAST_SESSION_KEY = 'minis.chat.lastSession'

/** 本次页面加载是否「一进来就落在首页」：断点续传只在这种情形触发——
 *  否则从别的页点「首页」进来时，也会被强行拽进那条正在生成的会话 */
const BOOTED_ON_HOME =
  typeof window !== 'undefined' &&
  (() => {
    const h = window.location.hash.replace(/^#/, '')
    const p = h.startsWith('/') ? h : `/${h}`
    return p.split('?')[0] === '/'
  })()

/** 断点续传每次页面加载只尝试一次：离开首页再回来不该又跳一次 */
let resumeAttempted = false

/** 会话消息 → 渲染态：历史回放与「子代理结论异步投回」的自动刷新共用同一套映射 */
function toChatMsgs(rows: SessionMessage[]): ChatMsg[] {
  return rows.map((r) => ({
    id: r.id,
    role: r.role === 'assistant' ? ('assistant' as const) : ('user' as const),
    content: r.content,
    reasoning: r.reasoning ?? undefined,
    trace: r.trace ?? undefined,
    kind: r.kind ?? undefined,
    subagent: r.subagent ?? undefined,
  }))
}

/** 运行时快照 → 尾部两条消息（本次提问 + 回答）；running 决定正文按纯文本还是 markdown 渲染 */
function livePair(live: LiveChat): ChatMsg[] {
  return [
    { role: 'user', content: live.userText, id: live.userMessageId ?? undefined },
    {
      role: 'assistant',
      content: live.content,
      reasoning: live.reasoning || undefined,
      trace: live.trace.length > 0 ? live.trace : undefined,
      streaming: live.running,
    },
  ]
}

/** 生成中重进会话时历史里已带上本次提问：尾部若有它，替运行时快照去掉这条重复（只影响展示副本） */
function dropTrailingDupUser(history: ChatMsg[], live: LiveChat): ChatMsg[] {
  const last = history[history.length - 1]
  if (!last || last.role !== 'user') return history
  const same =
    live.userMessageId !== null ? last.id === live.userMessageId : last.content === live.userText
  return same ? history.slice(0, -1) : history
}

/** 生成落定后把运行时快照并回消息列表：此后以列表（含最终答复）为准。
 *  失败且一个字都没产出时丢掉空回答，只留提问（便于回退重发），与逐 token 时代的收尾一致 */
function commitLive(history: ChatMsg[], live: LiveChat): ChatMsg[] {
  const pair = livePair(live).filter(
    (m) => m.role === 'user' || !!m.content || !!m.reasoning || !!m.trace?.length,
  )
  return [...dropTrailingDupUser(history, live), ...pair]
}

/** 上下文上限默认值（token 数）：所选模型未配置上下文窗口时兜底（与后端 LlmProps 默认一致） */
const DEFAULT_CONTEXT_WINDOW = 262144

/** 粗略估算文本 token 数：CJK 约 1 字 1 token、其余约 4 字符 1 token（仅用于上下文占比展示） */
function estimateTokens(text: string): number {
  let cjk = 0
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0
    if (cp >= 0x3400 && cp <= 0x9fff) cjk++
  }
  return cjk + Math.ceil((text.length - cjk) / 4)
}

/** 会话时间显示：当天仅时分，跨天补日期，跨年补年份 */
function fmtTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (d.toDateString() === now.toDateString()) return hm
  const y = d.getFullYear() === now.getFullYear() ? '' : `${d.getFullYear()}/`
  return `${y}${d.getMonth() + 1}/${d.getDate()} ${hm}`
}

/** 会话目录 / 消息锚点共用的稳定 id：有落库 id 用它；尚在流式的那一次提问用固定锚点 */
function userAnchorId(msg: ChatMsg): string {
  return msg.id != null ? `chat-turn-${msg.id}` : 'chat-turn-live'
}

/** 会话目录项文案：取提问首个非空行，压掉多余空白并截断 */
function tocText(content: string): string {
  const line = content.split('\n').find((l) => l.trim()) ?? ''
  const s = line.replace(/\s+/g, ' ').trim()
  if (!s) return '（空提问）'
  return s.length > 60 ? `${s.slice(0, 60)}…` : s
}

/** 工具调用参数的可读展示：能解析为 JSON 时缩进输出，否则原样返回 */
function prettyArgs(args: string | null): string {
  if (!args) return ''
  try {
    return JSON.stringify(JSON.parse(args), null, 2)
  } catch {
    return args
  }
}

/** 每个分段的展示用轮次（1 基）：优先用后端 trace 写入的 round（0 基）；
 *  早于该字段落库的旧数据无 round 时，按「每段思考开启新一轮」（首段不算新一轮）回推 */
function displayRounds(segs: TraceSegment[]): number[] {
  if (segs.some((s) => typeof s.round === 'number')) {
    return segs.map((s) => (s.round ?? 0) + 1)
  }
  let n = 0
  return segs.map((s, i) => {
    if (i > 0 && s.type === 'reasoning') n += 1
    return n + 1
  })
}

/** 单段思维链折叠块：普通思考标题「思考」，工具调用标题「调用工具 - 工具名」。
 *  流式期间随增量实时增长，可手动收起；实时生成中的块默认展开（跟随输出），历史回放的默认收起。
 *  badge 传值时在最右侧显示轮次计数；流式期间下一个窗口一开，本块自动收起。
 *  memo 化：历史消息的块不随打字或其它消息的流式更新而重渲染 */
const CotBlock = memo(function CotBlock({
  label,
  text,
  code,
  plain = false,
  defaultOpen = true,
  badge,
  live = false,
  isLast = true,
}: {
  label: string
  text?: string
  /** 以代码块呈现的内容（工具调用参数）；与 text 二选一 */
  code?: string
  /** 纯文本渲染（流式生成中）：跳过 markdown 解析，避免逐 token 重解析 */
  plain?: boolean
  defaultOpen?: boolean
  /** 最右侧的轮次数字（未传 = 不显示） */
  badge?: string
  /** 是否处于流式生成中（此时新窗口一出现就自动收起本块） */
  live?: boolean
  /** 是否为本条消息的最后一段：不是则说明已有更新的窗口开启 */
  isLast?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  /* 流式生成中：下一个思维链窗口开启（本块不再是最后一段）时自动收起本块。
     历史回放的块不受影响；用户手动开合的选择在本次生成结束后照旧保留 */
  const wasLast = useRef(isLast)
  useLayoutEffect(() => {
    if (live && wasLast.current && !isLast) setOpen(false)
    wasLast.current = isLast
  }, [live, isLast])
  /* 内容框自身也是滚动容器（max-h-64）：流式生成中随增量滚到底部，
     让用户始终看到最新的思考内容，而不是停在块的顶部。
     用 layout effect：在浏览器绘制前就贴底，增量再快也不会出现「先露一帧旧内容」的抖动 */
  const bodyRef = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const el = bodyRef.current
    if (!el || !plain) return
    el.scrollTop = el.scrollHeight
  }, [text, code, plain, open])
  return (
    <div className="mb-2 rounded-lg border border-line bg-fg/[0.02]">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full cursor-pointer items-center gap-1.5 px-3 py-1.5 text-xs font-medium uppercase tracking-[0.12em] text-faint transition hover:text-muted"
      >
        <AppIcon name="chevron-right" className={`h-3 w-3 shrink-0 transition-transform ${open ? 'rotate-90' : ''}`} />
        <span className="min-w-0 flex-1 truncate text-left">{label}</span>
        {badge && <span className="shrink-0 tabular-nums tracking-normal text-faint">{badge}</span>}
      </button>
      {open && (
        <div
          ref={bodyRef}
          className="scrollbar-slim max-h-64 overflow-y-auto border-t border-line px-3 py-2 text-sm leading-relaxed text-muted"
        >
          {code !== undefined ? (
            <pre className="whitespace-pre-wrap break-all font-mono text-sm">{code}</pre>
          ) : plain ? (
            <div className="whitespace-pre-wrap break-words">{text ?? ''}</div>
          ) : (
            <ChatMarkdown content={text ?? ''} />
          )}
        </div>
      )}
    </div>
  )
})

/** 一条 assistant 消息的思维链分段：按 trace 逐段渲染（思考 / 调用工具 - 工具名）；
 *  无 trace（非推理模型或分段功能之前落库的旧消息）时退回单一「思考」块。
 *  showRounds 开启时每段最右侧标出所属轮次；流式生成中只有一个窗口展开（新窗口一开，上一个自动收起）。
 *  memo 化：仅自身消息变化时才重渲染 */
const TraceBlocks = memo(function TraceBlocks({
  msg,
  live,
  showRounds,
}: {
  msg: ChatMsg
  live: boolean
  showRounds: boolean
}) {
  const segs = msg.trace
  if (segs && segs.length > 0) {
    const rounds = displayRounds(segs)
    return (
      <>
        {segs.map((seg, i) => {
          const isTool = seg.type === 'tool'
          return (
            <CotBlock
              key={i}
              label={isTool ? `调用工具 - ${seg.name || '未知工具'}` : '思考'}
              text={isTool ? undefined : (seg.text ?? '')}
              code={isTool ? prettyArgs(seg.args) : undefined}
              plain={!isTool && live}
              defaultOpen={live}
              badge={showRounds ? String(rounds[i]) : undefined}
              live={live}
              isLast={i === segs.length - 1}
            />
          )
        })}
      </>
    )
  }
  return msg.reasoning ? (
    <CotBlock
      label="思考"
      text={msg.reasoning}
      plain={live}
      defaultOpen={live}
      badge={showRounds ? '1' : undefined}
    />
  ) : null
})

/** 解耦输入框：文本状态收在组件内部，打字只重渲染自身，不会波及消息列表（markdown 重解析）。
 *  通过 ref 暴露取值 / 回填 / 聚焦；仅在文本由空↔非空翻转时回调外层（用于发送按钮禁用态），
 *  避免逐字符向上冒泡引起 Home 整体重渲染 */
type DecoupledInputHandle = {
  /** 读取当前文本 */
  getValue: () => string
  /** 覆盖文本（发送后清空 / 回退回填） */
  setValue: (v: string) => void
  /** 聚焦输入框 */
  focus: (options?: FocusOptions) => void
}

const DecoupledInput = forwardRef<
  DecoupledInputHandle,
  {
    placeholder: string
    className: string
    autoFocus?: boolean
    /** 文本非空状态翻转时回调（只在空↔非空切换时触发一次） */
    onHasTextChange?: (hasText: boolean) => void
  }
>(function DecoupledInput({ placeholder, className, autoFocus, onHasTextChange }, ref) {
  const [text, setText] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  /** 上一次上报的非空态：只在翻转时回调，避免逐字符触发外层 setState */
  const hasTextRef = useRef(false)

  const update = useCallback(
    (v: string) => {
      setText(v)
      const has = v.trim().length > 0
      if (has !== hasTextRef.current) {
        hasTextRef.current = has
        onHasTextChange?.(has)
      }
    },
    [onHasTextChange],
  )

  useImperativeHandle(
    ref,
    () => ({
      getValue: () => inputRef.current?.value ?? '',
      setValue: (v: string) => update(v),
      focus: (options?: FocusOptions) => inputRef.current?.focus(options),
    }),
    [update],
  )

  return (
    <input
      ref={inputRef}
      value={text}
      onChange={(ev) => update(ev.target.value)}
      placeholder={placeholder}
      className={className}
      autoFocus={autoFocus}
    />
  )
})

/** 一条用户提问气泡（memo）：仅自身内容 / 位置 / 可回退态变化时重渲染 */
const UserRow = memo(function UserRow({
  msg,
  index,
  canRewind,
  onRewind,
}: {
  msg: ChatMsg
  index: number
  /** AI 回复中不可回退（直接不渲染按钮，避免禁用态常驻半透明） */
  canRewind: boolean
  onRewind: (index: number) => void
}) {
  return (
    /* id=会话目录的锚点（点目录项 scrollIntoView 落到这条提问）；scroll-mt-6 给锚点留顶距 */
    <div id={userAnchorId(msg)} className="group relative flex scroll-mt-6 justify-end">
      <div className="max-w-[85%] break-words rounded-2xl rounded-br-sm bg-fg px-4 py-2.5 text-md leading-relaxed text-primary-fg">
        <ChatMarkdown content={msg.content} onBubble />
      </div>
      {/* hover 气泡时浮现在其下方消息间隙里的回退按钮：删掉这条提问及其后的对话，
          内容放回底部输入框的未发送状态（不占排版，绝对定位在 space-y-5 的间隙内） */}
      {canRewind && (
        <button
          type="button"
          onClick={() => onRewind(index)}
          title="回到这条提问之前（内容放回输入框）"
          aria-label="回到这条提问之前"
          className="absolute -bottom-5 right-1 flex cursor-pointer items-center gap-1 rounded-full border border-line bg-bg px-2 py-0.5 text-xs text-faint opacity-0 shadow-sm transition group-hover:opacity-100 hover:text-fg"
        >
          <AppIcon name="reply" className="h-3 w-3" />
          回退
        </button>
      )}
    </div>
  )
})

/** 一条 AI 回复（memo）：仅自身消息 / CoT 开关 / 可分支态变化时重渲染。
 *  content-visibility 让浏览器跳过屏外消息的布局与绘制（含 trace 折叠块、代码块、表格） */
const AssistantRow = memo(function AssistantRow({
  msg,
  showCot,
  showRounds,
  canBranch,
  onBranch,
  speechAgentId,
  speechEnabled,
  canAnswer,
  onAnswer,
}: {
  msg: ChatMsg
  showCot: boolean
  showRounds: boolean
  /** 可分支（这条回复已落库、且当前不在生成中）；不满足时整排按钮不渲染 */
  canBranch: boolean
  onBranch: (msg: ChatMsg) => void
  speechAgentId: string
  speechEnabled: boolean
  canAnswer: boolean
  onAnswer: (text: string) => Promise<void>
}) {
  const question = userQuestion(msg)
  return (
    <div className="group break-words border-l border-line pl-4 text-md leading-relaxed text-fg [content-visibility:auto] [contain-intrinsic-size:auto_300px]">
      {/* 正在流式生成的这条默认展开跟随输出；历史回放的默认收起 */}
      {showCot && <TraceBlocks msg={msg} live={!!msg.streaming} showRounds={showRounds} />}
      {question ? <>
        <UserQuestionCard key={JSON.stringify(question)} question={question} active={canAnswer} onAnswer={onAnswer} />
        <ChatMarkdown content={msg.content.slice(questionContent(question).length)} />
      </> : msg.streaming ? (
        /* 流式生成中按纯文本渲染：逐 token 期间不解析 markdown，结束后再切正式渲染 */
        <div className="whitespace-pre-wrap break-words">{msg.content}</div>
      ) : (
        <ChatMarkdown content={msg.content} />
      )}
      {/* 回复下方的一排操作按钮（悬停浮现，与提问气泡的「回退」同一套克制手感）：
          目前只有「分支」——以到这里为止的内容新建一个副本会话 */}
      {canBranch && (
        <div className="mt-1.5 flex flex-wrap items-center gap-2 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
          <button
            type="button"
            className={miniBtn}
            onClick={() => onBranch(msg)}
            title="以到这里为止的内容创建一个副本会话"
          >
            <AppIcon name="branch" className="h-3 w-3" />
            分支
          </button>
          {speechEnabled && <SpeechButton text={msg.content} agentId={speechAgentId} />}
        </div>
      )}
    </div>
  )
})

/** 待办项状态 → 文本样式（plan-of-record 的四态；未知态按 pending 兜底） */
const TODO_TONE: Record<string, string> = {
  pending: 'text-fg',
  in_progress: 'text-accent',
  done: 'text-hint line-through',
  failed: 'text-red-500',
}

/** 待办项左侧标记：完成=勾、失败=叉、进行中=实心脉冲点、待做=空心圈 */
function TodoMark({ status }: { status: string }) {
  if (status === 'done') {
    return (
      <AppIcon name="check" className="mt-[3px] h-3.5 w-3.5 shrink-0 text-accent" />
    )
  }
  if (status === 'failed') {
    return (
      <AppIcon name="close" className="mt-[3px] h-3.5 w-3.5 shrink-0 text-red-500" />
    )
  }
  return (
    <span className="mt-[3px] flex h-3.5 w-3.5 shrink-0 items-center justify-center" aria-hidden="true">
      <span
        className={
          status === 'in_progress'
            ? 'h-2 w-2 animate-pulse rounded-full bg-accent'
            : 'h-2 w-2 rounded-full border border-line-strong'
        }
      />
    </span>
  )
}

/** 会话 TODO 卡片：把 agent 的 plan-of-record（write_todos 覆盖式写入）显示成一张进度卡。
 *  可折叠——窄屏默认收起、全部完成自动收起，避免悬浮卡长期压住对话区；内容为空时不渲染（由调用处判空）。 */
const TodoCard = memo(function TodoCard({ todos }: { todos: SessionTodo[] }) {
  const done = todos.filter((t) => t.status === 'done').length
  const failed = todos.filter((t) => t.status === 'failed').length
  const allDone = failed === 0 && done === todos.length
  /* 窄屏默认收起：悬浮卡在窄屏会盖住消息列，先收纳成小胶囊，用户需要时再展开 */
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 1024)
  /* 全部完成自动收起一次：只在「刚变成全完成」时触发，用户随后手动展开不再强收 */
  const prevAllDone = useRef(false)
  useEffect(() => {
    if (allDone && !prevAllDone.current) setCollapsed(true)
    prevAllDone.current = allDone
  }, [allDone])

  if (collapsed) {
    return (
      <button
        type="button"
        onClick={() => setCollapsed(false)}
        title="展开待办清单"
        className="ui-surface flex items-center gap-2 rounded-full border border-line px-3 py-1.5 text-xs text-muted shadow-xl transition hover:border-line-strong hover:text-fg"
      >
        <span
          className={`h-2 w-2 shrink-0 rounded-full bg-accent ${allDone ? '' : 'animate-pulse'}`}
          aria-hidden="true"
        />
        <span>待办</span>
        <span className="tabular-nums">{done}/{todos.length}</span>
        {failed > 0 && <span className="text-red-500">{failed} 失败</span>}
      </button>
    )
  }

  return (
    <div className="ui-surface flex max-h-[70vh] w-[min(20rem,calc(100vw-2rem))] flex-col rounded-xl border border-line px-4 py-3 shadow-xl">
      <div className="mb-2 flex shrink-0 items-center gap-2 text-xs font-medium uppercase tracking-[0.12em] text-faint">
        <span>待办清单</span>
        <span className="tabular-nums">{done}/{todos.length}</span>
        {failed > 0 && <span className="normal-case tracking-normal text-red-500">{failed} 步失败</span>}
        <button
          type="button"
          onClick={() => setCollapsed(true)}
          title="收起待办清单"
          className="ui-btn ui-btn--icon -mr-1 ml-auto h-6 w-6 shrink-0 rounded-full"
        >
          <AppIcon name="chevron-down" className="h-3.5 w-3.5" />
        </button>
      </div>
      <ul className="scrollbar-slim min-h-0 space-y-1.5 overflow-y-auto">
        {todos.map((t) => (
          <li key={t.seq} className="flex items-start gap-2 text-base leading-relaxed">
            <TodoMark status={t.status} />
            <span className={`min-w-0 flex-1 ${TODO_TONE[t.status] ?? TODO_TONE.pending}`}>
              {t.content}
              {t.note && <span className="ml-1 text-hint">— {t.note}</span>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
})

/** 子代理结论气泡（memo）：只显示标题与摘要——长篇报告不再直接铺满会话页；
 *  点气泡弹窗看 content 里的完整结论。status=failed 按「未完成」样式渲染 */
const SubagentBubble = memo(function SubagentBubble({
  msg,
  onOpen,
}: {
  msg: ChatMsg
  onOpen: (msg: ChatMsg) => void
}) {
  const brief = msg.subagent
  const failed = brief?.status === 'failed'
  const title = brief?.title || '子代理回报'
  return (
    <div className="flex">
      <button
        type="button"
        onClick={() => onOpen(msg)}
        title="查看完整结论"
        className={`w-full max-w-[85%] cursor-pointer rounded-2xl rounded-bl-sm border px-4 py-3 text-left transition ${
          failed
            ? 'border-red-500/40 bg-red-500/[0.04] hover:bg-red-500/[0.07]'
            : 'border-line bg-fg/[0.02] hover:border-line-strong hover:bg-fg/[0.04]'
        }`}
      >
        <div className="flex items-center gap-2">
          <span
            className={`h-1.5 w-1.5 shrink-0 rounded-full ${failed ? 'bg-red-500' : 'bg-accent'}`}
            aria-hidden="true"
          />
          <span className="min-w-0 flex-1 truncate text-base font-semibold text-fg">{title}</span>
          <span className="shrink-0 rounded-full border border-line px-2 py-0.5 text-2xs font-medium tracking-wide text-faint">
            {failed ? '子代理未完成' : '子代理回报'}
          </span>
        </div>
        {brief?.summary && (
          <p className="mt-1.5 line-clamp-3 text-sm leading-relaxed text-muted">
            {brief.summary}
          </p>
        )}
        <span className="mt-2 flex items-center gap-1 text-xs text-accent">
          查看完整结论
          <AppIcon name="chevron-right" className="h-3 w-3" />
        </span>
      </button>
    </div>
  )
})

/** 首页：Google 式问候 + 居中搜索。检索提交跳转搜索结果页；点搜索框左侧图标切换会话模式。
 *  两页滚动模式：搜索页在上、对话页在下，进入会话时平滑滚到第 2 页，模拟搜索栏上移消失的
 *  过渡效果；滚轮拦截让两页各自锁定（搜索页不向下、对话页不外溢），拖拽选中文本期间再把
 *  main 临时锁为不可滚，防止选中自动滚动把视口带回搜索页。左侧抽屉可切换/新建会话。 */
export default function Home({ connErr }: Props) {
  /** 当前主题（蔚蓝档案下首页标题换主题专属 title.png）；mono.sidebar 决定 Tailwind Mono 是否走侧边栏档 */
  const { themeId, mono } = useThemeState()
  /** BA 动画背景开关（层叠天空 + 会话白幕），记忆到 localStorage */
  const [skyOn, setSkyOn] = useState(() => {
    try {
      return localStorage.getItem('minis.ba.sky') !== '0'
    } catch {
      return true
    }
  })
  const toggleSky = () => {
    setSkyOn((v) => {
      const next = !v
      try {
        localStorage.setItem('minis.ba.sky', next ? '1' : '0')
      } catch {
        /* 隐私模式等场景忽略 */
      }
      return next
    })
  }
  const showSky = themeId === 'bluearchive' && skyOn
  /** 底部输入框是否有内容：仅在空↔非空翻转时更新，用于发送按钮禁用态。
   *  打字本身只重渲染输入框组件，不触发这里（否则长会话会随击键全量重渲染） */
  const [hasText, setHasText] = useState(false)
  const [chatMode, setChatMode] = useState(false)
  /** 当前可见屏幕独立于会话是否存在；返回首页保留会话，但后台更新不能再切屏。 */
  const [homeView, setHomeView] = useState<'search' | 'conversation'>('search')
  const [msgs, setMsgs] = useState<ChatMsg[]>([])
  /** 当前会话的个人待办清单（plan-of-record）：TODO 卡片的数据源；
   *  由会话事件（kind=todo）与生成结束（busy→false）驱动刷新（见下方两个效应） */
  const [todos, setTodos] = useState<SessionTodo[]>([])
  /** 错误轻提示（message box，替代页内错误文案：发送失败 / 会话加载失败 / 后端未连通） */
  const [toast, setToast] = useState<string | null>(null)

  /* 会话历史：当前会话 id + 最近会话列表 + 左侧悬浮面板开合 */
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [sessionsLoading, setSessionsLoading] = useState(false)
  const [sessionsError, setSessionsError] = useState(false)
  const sessionsRequest = useRef(0)
  const [panelOpen, setPanelOpen] = useState(false)
  /* 滑块切入会话模式时抽屉以 fadeIn 亮相（挂载即开没有 transition 起点，会硬闪）；
     仅首挂载播一次，之后拉手开合仍走原滑动 transition */
  const [panelMountFade, setPanelMountFade] = useState(false)

  /* 会话视图激活态：新建会话后即使尚无消息也直接展示对话页（而非搜索页） */
  const [sessionActive, setSessionActive] = useState(false)

  /* 生成运行时（应用级模块仓，见 components/chatLive）：本次会话是否正在生成、进度快照为何。
     它跨页面挂载存活，所以离开会话/切到别的页再回来，仍能续看推理进度、续点「停止」 */
  const live = useLiveChat(sessionId)
  const busy = live?.running === true

  /** 消息列表的展示副本：历史 + 本次生成中的两条（提问 + 流式回答）。
   *  生成落定后由下面的效应并回 msgs 并清掉快照，此后以 msgs 为准 */
  const view = useMemo<ChatMsg[]>(
    () => (live ? [...dropTrailingDupUser(msgs, live), ...livePair(live)] : msgs),
    [msgs, live],
  )
  const lastConversationIndex = view.findLastIndex(message => message.kind !== 'subagent')

  /** 正在弹窗查看完整结论的子代理气泡（null=未打开）：气泡只显示标题+摘要，全文在这里看 */
  const [reportView, setReportView] = useState<ChatMsg | null>(null)

  /** 正在弹窗查看的原始上下文（null=未打开）：ctx=后端按真实发送规则组装的请求体 + 逐 Turn 切片，
   *  sessionId 供回看某个 Turn 的调用留痕（用 Turn 感知的 JSON 预览器 RawContextView 展示） */
  const [rawView, setRawView] = useState<{ sessionId: string; ctx: RawContext } | null>(null)

  /* 本次会话使用的模型 / 系统提示词 / 是否显示 CoT / 思维链轮次计数：
     属会话级配置（「…」菜单），随会话存取——进入历史会话时从后端恢复，菜单变更时回写后端 */
  const [settings, setSettings] = useState<SettingsView | null>(null)
  const [speechEnabled, setSpeechEnabled] = useState(false)
  useEffect(() => {
    let alive = true
    void getTtsSettings().then(v => { if (alive) setSpeechEnabled(v.profiles.length > 0) }).catch(() => {})
    return () => { alive = false }
  }, [])
  /* 提示词页的「对话身份」位：会话可在「…」菜单里单独切换其版本 */
  const [prompts, setPrompts] = useState<PromptsView | null>(null)
  const [modelId, setModelId] = useState('')
  const [promptId, setPromptId] = useState('')
  /** 本会话跟哪位 Agent 对话（「…」菜单切换）：'' = 内置 Agent（用全局「对话身份」提示词） */
  const [agentId, setAgentId] = useState('')
  /** 可选智能体名册（「…」菜单的「智能体」分区）：展开菜单时拉取 */
  const [peers, setPeers] = useState<MaPeer[]>([])
  /* 项目（memory-v6）：会话所属项目决定记忆归属与跨项目隔离；空 = 无项目（个人工作域） */
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState('')
  const [projectDialog, setProjectDialog] = useState<'create' | 'organize' | null>(null)
  const [projectSaving, setProjectSaving] = useState(false)
  const activeSessionRef = useRef(sessionId)
  useEffect(() => { activeSessionRef.current = sessionId }, [sessionId])
  /** 输入框左侧「…」菜单：向上弹出，切换模型 / 提示词 / CoT 显示 */
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  /** 是否显示思维链（默认显示）；开关在「…」菜单里，按会话存储 */
  const [showCot, setShowCot] = useState(true)

  /** 思维链分段是否在右侧标轮次计数（默认不显示）；开关在「…」菜单里，按会话存储 */
  const [showRounds, setShowRounds] = useState(false)

  /* 「…」菜单：点击外部或 Esc 关闭 */
  useEffect(() => {
    if (!menuOpen) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuOpen])

  /* 「…」菜单展开时静默刷新提示词视图与智能体名册：身份版本/绑定可在提示词页改过、
     成员名册可在智能体页改过（含其它标签页），菜单里的列表要跟那边保持一致；失败不打扰 */
  useEffect(() => {
    if (!menuOpen) return
    let alive = true
    getPrompts()
      .then((p) => {
        if (alive) setPrompts(p)
      })
      .catch(() => {})
    listMaPeers()
      .then((list) => {
        if (alive) setPeers(list)
      })
      .catch(() => {})
    listProjects()
      .then((list) => {
        if (alive) setProjects(list.filter((p) => p.status !== 'archived'))
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [menuOpen])

  const searchPageRef = useRef<HTMLElement>(null)
  const chatPageRef = useRef<HTMLElement>(null)
  const chatScrollRef = useRef<HTMLDivElement>(null)
  /** 会话目录：只取用户提问（点它跳到该次输入）。圆点数量=轮次数，重建很轻 */
  const tocItems = useMemo<TocItem[]>(
    () =>
      view
        .filter((m) => m.role === 'user')
        .map((m) => ({ id: userAnchorId(m), text: tocText(m.content) })),
    [view],
  )
  /** 目录要跟着内层消息列表滚（不是 main）：稳定回调，避免每帧重挂滚动侦听 */
  const getChatScroller = useCallback(() => chatScrollRef.current, [])
  /** 检索输入框 / 会话底部输入框：文本状态各自收在 DecoupledInput 内部，
   *  这里只借 ref 读值（提交时）、回填（回退）与聚焦，避免击键重渲染消息列表 */
  const searchInputRef = useRef<DecoupledInputHandle>(null)
  const chatInputRef = useRef<DecoupledInputHandle>(null)
  /** 新建会话期间的并发闸门：此时尚无 sessionId，来不及由 live 快照拦住连点 */
  const sendingRef = useRef(false)
  const hasChat = chatMode && (sessionActive || msgs.length > 0 || live !== null)
  const showingChat = hasChat && homeView === 'conversation'

  /* 屏幕显隐完成后复位唯一的外层滚动容器并移交焦点，避免 WebView 恢复屏外控件的焦点
     时又把页面带回会话。两屏保持挂载，输入草稿和历史消息无需重新加载。 */
  useLayoutEffect(() => {
    const main = searchPageRef.current?.closest('main')
    main?.scrollTo({ top: 0, behavior: 'instant' })
    const input = showingChat ? chatInputRef.current : searchInputRef.current
    input?.focus({ preventScroll: true })
  }, [showingChat])
  /** main 的实际可视高度（随 header 高度/登录态/窗口变化），用于撑满当前屏幕。 */
  const [pageH, setPageH] = useState(0)

  /* 量出 main 的可视高度，让当前屏幕随顶栏、登录态与窗口大小撑满内容区。 */
  useLayoutEffect(() => {
    const main = searchPageRef.current?.closest('main')
    if (!main) return
    const update = () => setPageH(main.clientHeight)
    update()
    const ro = new ResizeObserver(update)
    ro.observe(main)
    return () => ro.disconnect()
  }, [])

  /* 最近会话抽屉避开导航：桌面框架优先取 .df__stage；新外壳用
     data-panel-anchor 明确内容左缘；兼容 Mono 的侧边栏档。
     窗口缩放、侧栏档与主题切换时重新量实际位置。 */
  const [panelLeft, setPanelLeft] = useState(0)
  useLayoutEffect(() => {
    const main = searchPageRef.current?.closest('main')
    if (!main) return
    const update = () => {
      const anchor = main.closest('.df__stage') ?? main.closest('[data-panel-anchor]') ??
        (themeId === 'old' && mono.sidebar ? main : null)
      setPanelLeft(anchor ? anchor.getBoundingClientRect().left : 0)
    }
    update()
    const ro = new ResizeObserver(update)
    ro.observe(main)
    window.addEventListener('resize', update)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', update)
    }
  }, [themeId, mono.sidebar])

  /* 「对话身份」提示词位：内置（.env）+ 用户版本，会话可在「…」菜单里单独切换 */
  const identity = useMemo(
    () =>
      prompts?.categories
        .flatMap((c) => c.contexts)
        .flatMap((c) => c.parts)
        .find((s) => s.key === 'identity') ?? null,
    [prompts],
  )
  const identityOptions = useMemo(
    () => [
      { id: PROMPT_BUILTIN, name: '.env（内置）', content: identity?.builtin ?? '' },
      ...(identity?.versions ?? []).map((v) => ({ id: v.id, name: v.name, content: v.content })),
    ],
    [identity],
  )
  /* 会话钉住的版本可能已在提示词页被删：不存在时视同未钉住（后端解析同样回退全局绑定） */
  const pinnedPromptId =
    promptId && identityOptions.some((o) => o.id === promptId) ? promptId : ''
  /* 本会话实际使用的身份版本：会话显式钉住 → 提示词页的绑定 → 内置 */
  const effectivePromptId = pinnedPromptId || identity?.activeVersionId || PROMPT_BUILTIN

  /* 本会话实际对话的 Agent：'' / 名册里找不到（已删除）→ 内置 Agent（与后端 agentOf 同口径） */
  const effectiveAgentId = agentId && peers.some((p) => p.id === agentId) ? agentId : 'builtin'
  /** 当前所选 Agent 的展示名（内置 Agent 不在名册时回退文案） */
  const currentAgentName = peers.find((p) => p.id === effectiveAgentId)?.name ?? '内置 Agent'

  /* 上下文占比：系统提示词 + 会话消息按 token 估算，除以所选模型的上下文窗口（未配置按默认值） */
  const contextPct = useMemo(() => {
    const win =
      settings?.models.find((m) => m.id === (modelId || settings?.defaultModelId))?.contextWindow ??
      DEFAULT_CONTEXT_WINDOW
    const sys = identityOptions.find((p) => p.id === effectivePromptId)?.content ?? ''
    let tokens = estimateTokens(sys)
    for (const m of view) tokens += estimateTokens(m.content)
    if (!win || win <= 0) return 0
    return Math.min(100, (tokens / win) * 100)
  }, [settings, modelId, identityOptions, effectivePromptId, view])

  /* 生成落定（成功 / 停止 / 失败）：把运行时快照并回消息列表，并清掉它——此后以列表为准。
     页面不在该会话（或未挂载）时不做：快照留在仓里，等下次进入该会话时按需并回或丢弃 */
  const committedRef = useRef<LiveChat | null>(null)
  useEffect(() => {
    if (!live || live.running || committedRef.current === live) return
    committedRef.current = live
    setMsgs((m) => commitLive(m, live))
    clearLive(live.sessionId)
    // The terminal stream has no assistant row ID. Fetch the persisted turn so actions work immediately.
    void sessionMessages(live.sessionId).then(rows => {
      if (activeSessionRef.current !== live.sessionId || committedRef.current !== live
        || getLive(live.sessionId)?.running) return
      setMsgs(current => backfillSettledReply(current, live, rows))
    }).catch(() => {
      if (activeSessionRef.current === live.sessionId && committedRef.current === live)
        setToast('回复已结束，但消息操作加载失败，请重新打开会话重试')
    })
  }, [live])

  /* 生成失败：统一在这里提示（本地发起与刷新后重挂两条路径共用一个 error 字段） */
  const erroredRef = useRef<LiveChat | null>(null)
  useEffect(() => {
    if (!live?.error || erroredRef.current === live) return
    erroredRef.current = live
    setToast(`发送失败：${live.error}`)
  }, [live])

  /* 上报「正在看哪条会话」：页级通知据此跳过「你正看着它」的提醒（见 ChatNotifier） */
  useEffect(() => {
    setActiveChatSession(showingChat ? sessionId : null)
    return () => setActiveChatSession(null)
  }, [showingChat, sessionId])

  /* 首页和会话由显式视图切换；隐藏外层滚动条，消息列表使用自己的滚动容器。
     仅在首页挂载期间生效，切到其它页面照常恢复。 */
  useEffect(() => {
    const mainEl = searchPageRef.current?.closest('main')
    if (!mainEl) return
    mainEl.classList.add('scrollbar-stealth')
    return () => {
      mainEl.classList.remove('scrollbar-stealth')
    }
  }, [])

  /* 子代理的结论是异步投回的（后端跑完才把它作为一条新消息落库并打未读点）：STEP 4 起不再每 4 秒
     轮询会话列表，改由会话事件推送即时告知——本会话一有消息投递就重新拉一次把它刷出来（无轮询延迟）。
     正在生成时不刷：流式视图在渲染，等它收尾由落定逻辑并回，免得与在途快照打架 */
  useEffect(() => {
    if (sessionId === null || busy) return
    return subscribeSessionEvents((e) => {
      if (e.sessionId !== sessionId || e.kind !== 'delivered') return
      void sessionMessages(sessionId) // 顺带把该会话的未读清零
        .then((rows) => {
          setMsgs(toChatMsgs(rows))
          /* 未读点后端已清零：本地列表同步改掉，免得抽屉上还挂着点 */
          setSessions((cur) => cur.map((s) => (s.id === sessionId ? { ...s, unread: false } : s)))
        })
        .catch(() => {
          /* 取消息失败静默：历史仍在，下次事件或切页时会再拉 */
        })
    })
  }, [sessionId, busy])

  /* 个人待办清单（plan-of-record）：切会话时拉一次，本次生成收尾（busy→false）后再拉一次——
     把生成期间 write_todos 覆盖写入的最终清单刷进来（生成中不刷，免得与在途视图交叠） */
  useEffect(() => {
    if (sessionId === null) {
      setTodos([])
      return
    }
    let alive = true
    void sessionTodos(sessionId)
      .then((rows) => {
        if (alive) setTodos(rows)
      })
      .catch(() => {
        /* 取清单失败静默：下次生成结束 / 事件到达时会再拉 */
      })
    return () => {
      alive = false
    }
  }, [sessionId, busy])

  /* 生成中 write_todos 一落库，后端即推 kind=todo：这条订阅不受 busy 限制，够即时把卡片刷成最新 */
  useEffect(() => {
    if (sessionId === null) return
    return subscribeSessionEvents((e) => {
      if (e.sessionId !== sessionId || e.kind !== 'todo') return
      void sessionTodos(sessionId)
        .then(setTodos)
        .catch(() => {
          /* 静默：下一轮事件或生成结束时兜底 */
        })
    })
  }, [sessionId])

  /* 对话页滚轮只作用于消息列表：内容不足一屏或已到边界时不再外溢回搜索页 */
  useEffect(() => {
    const el = chatScrollRef.current
    if (!showingChat || !el) return
    const onWheel = (ev: WheelEvent) => {
      const max = el.scrollHeight - el.clientHeight
      if (
        max <= 0 ||
        (ev.deltaY < 0 && el.scrollTop <= 0) ||
        (ev.deltaY > 0 && el.scrollTop >= max)
      ) {
        ev.preventDefault()
      }
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [showingChat])

  /* 对话页里除消息列表以外的区域（底部输入框、页面留白等）没有可滚内容：落在其中的
     滚轮会冒泡到 App 的 main，移动外层视口。这里在 main 上兜底拦截——
     从事件目标向上找可滚容器（消息列表、抽屉、「…」菜单各自会滚），还能滚就放行，
     滚到边界或没有可滚容器才 preventDefault，避免外层页面被滚离当前视图。 */
  useEffect(() => {
    if (!showingChat) return
    const main = chatPageRef.current?.closest('main')
    if (!main) return
    const onWheel = (ev: WheelEvent) => {
      for (
        let node = ev.target instanceof Element ? ev.target : null;
        node && node !== main;
        node = node.parentElement
      ) {
        const max = node.scrollHeight - node.clientHeight
        const oy = getComputedStyle(node).overflowY
        if (max > 0 && (oy === 'auto' || oy === 'scroll' || oy === 'overlay')) {
          if (ev.deltaY < 0 ? node.scrollTop > 0 : node.scrollTop < max) return
        }
      }
      ev.preventDefault()
    }
    main.addEventListener('wheel', onWheel, { passive: false })
    return () => main.removeEventListener('wheel', onWheel)
  }, [showingChat])

  /* 搜索页滚轮向下不进入会话页：首页不再内联展示结果，向下滚一律留在第 1 页 */
  useEffect(() => {
    const el = searchPageRef.current
    if (!el) return
    const onWheel = (ev: WheelEvent) => {
      if (ev.deltaY > 0) ev.preventDefault()
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  /* 新消息 / 加载历史后，消息列表贴到底部（最后一条——通常就是大模型的回答——落在视野内）。
     屏外消息靠 content-visibility 的占位高度参与布局，跳到底后浏览器才把视野附近的真实高度补齐，
     scrollHeight 随之变化、位置跟着漂；连续几帧校正到稳定贴底后收工，用户一动滚轮就立刻让位。
     首页可见时不操作隐藏的消息列表，避免 WebView 将它重新滚入视口。 */
  useEffect(() => {
    const el = chatScrollRef.current
    if (!showingChat || !el) return
    let raf = 0
    let frames = 0
    let stable = 0
    let stopped = false
    const yieldToUser = () => {
      stopped = true
      if (raf) {
        cancelAnimationFrame(raf)
        raf = 0
      }
    }
    el.addEventListener('wheel', yieldToUser, { passive: true })
    el.addEventListener('touchstart', yieldToUser, { passive: true })
    const settle = () => {
      raf = 0
      if (stopped) return
      const max = el.scrollHeight - el.clientHeight
      if (el.scrollTop < max) {
        /* 高度又长出来了（刚补齐的这几屏比占位高）：再贴一次 */
        el.scrollTop = max
        stable = 0
      } else if (++stable >= 3) {
        return
      }
      if (++frames >= 90) return
      raf = requestAnimationFrame(settle)
    }
    const begin = () => {
      if (stopped) return
      el.scrollTop = el.scrollHeight
      raf = requestAnimationFrame(settle)
    }
    /* 显示会话或历史更新后先贴底，再校正屏外消息的占位高度。 */
    begin()
    return () => {
      el.removeEventListener('wheel', yieldToUser)
      el.removeEventListener('touchstart', yieldToUser)
      if (raf) cancelAnimationFrame(raf)
    }
  }, [view, busy, showingChat])

  /* backend 健康检查失败（App 注入）：以错误 message box 提示，不再占据首页排版 */
  useEffect(() => {
    if (connErr) {
      setToast(`backend 未连通（${connErr}）——请先启动 minis/backend`)
    }
  }, [connErr])

  /* 进入会话模式时加载设置页配置，用默认模型/系统提示词初始化切换器（仅一次；失败则静默降级为后端默认） */
  useEffect(() => {
    if (!chatMode || settings) return
    let alive = true
    Promise.all([getSettings(), getPrompts()])
      .then(([v, p]) => {
        if (!alive) return
        setSettings(v)
        setPrompts(p)
        setModelId(v.defaultModelId)
        /* 身份提示词留空 = 跟随提示词页的绑定（effectivePromptId 会兜底到绑定/内置） */
      })
      .catch((e: unknown) => {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) window.location.assign(loginUrl)
      })
    return () => {
      alive = false
    }
  }, [chatMode, settings])

  /** 统一异常 → 错误文案；401 时跳转登录页并返回 null（调用方无需再设置错误态） */
  const errorText = (e: unknown): string | null => {
    const loginUrl = loginUrlOf(e)
    if (loginUrl) {
      window.location.assign(loginUrl)
      return null
    }
    return e instanceof Error ? e.message : String(e)
  }

  /** 刷新最近会话列表；返回列表供自动恢复取最近会话 */
  const refreshSessions = async (): Promise<SessionItem[]> => {
    const request = ++sessionsRequest.current
    setSessionsLoading(true)
    setSessionsError(false)
    try {
      const list = await listSessions()
      if (request === sessionsRequest.current) {
        setSessions(list)
        const active = list.find(item => item.id === activeSessionRef.current)
        if (active) setProjectId(active.projectId ?? '')
      }
      return list
    } catch (e) {
      if (request === sessionsRequest.current) {
        setSessionsError(true)
        const msg = errorText(e)
        if (msg !== null) setToast(`会话列表加载失败：${msg}`)
      }
      return []
    } finally {
      if (request === sessionsRequest.current) setSessionsLoading(false)
    }
  }

  /** 把「…」菜单选择写入会话配置：仅传变更字段（null 项后端保持原值）；
   *  promptId 传空串 = 清除本会话钉住、回到跟随提示词页绑定；失败提示但不抛出 */
  const saveConfig = async (
    sid: string,
    cfg: {
      modelId?: string | null
      promptId?: string | null
      agentId?: string | null
      showCot?: boolean
      showRounds?: boolean
    },
  ): Promise<void> => {
    try {
      await updateSessionConfig(sid, {
        modelId: cfg.modelId || null,
        promptId: cfg.promptId ?? null,
        /* undefined=不改；空串=清除所选 Agent、回到内置 Agent（后端把它落成 NULL）；否则=所选成员 id */
        agentId: cfg.agentId === undefined ? null : cfg.agentId,
        showCot: cfg.showCot ?? null,
        showRounds: cfg.showRounds ?? null,
      })
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`会话设置保存失败：${msg}`)
    }
  }

  /** Rerank 精排开关（全局配置，与设置页共用同一接口）：关闭后检索保持 RRF 融合原序 */
  const toggleRerank = async () => {
    if (!settings) return
    const next = settings.rerank.mode === 'off' ? 'llm' : 'off'
    try {
      const v = await saveRerank({
        mode: next,
        llmModelId: settings.rerank.llmModelId,
        baseUrl: settings.rerank.baseUrl,
        apiKey: settings.rerank.apiKey,
        model: settings.rerank.model,
      })
      setSettings(v)
      setToast(next === 'off' ? 'Rerank 精排已关闭' : 'Rerank 精排已开启')
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`Rerank 设置保存失败：${msg}`)
    }
  }

  /** 「查看原始上下文」：拉取后端按真实发送规则组装的 /chat/completions 请求体，弹出 Turn 感知的
   *  JSON 预览器展示（不发送、不落库；此刻再发一条消息时系统发给大模型接口的就是这份内容） */
  const viewRawContext = async () => {
    if (!sessionId) {
      setToast('当前还没有会话')
      return
    }
    setMenuOpen(false)
    try {
      const ctx = await sessionRawContext(sessionId)
      setRawView({ sessionId, ctx })
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`原始上下文加载失败：${msg}`)
    }
  }

  /** 切换会话：拉取历史消息回显并进入会话视图，后续发送都带该 sessionId。
   *  若该会话有生成运行时快照：仍在生成就留着（贴回列表即可续看进度、续点停止）；
   *  已落定的先收尾再拉历史——成功/停止的答复已在历史里，失败时的半截输出只在快照里，要并回列表。
   *  **生成中也能切走**：生成托管在应用级运行时（见 chatLive），离开只是不再看它，
   *  那边照常产出并落库；切回去照样续看进度、续点停止。 */
  const openSession = async (id: string, knownItem?: SessionItem) => {
    try {
      const snapshot = getLive(id)
      const settled = snapshot && !snapshot.running ? snapshot : null
      if (settled) clearLive(id)
      const rows = await sessionMessages(id)
      const history = toChatMsgs(rows)
      setSessionId(id)
      setSessionActive(true)
      setMsgs(settled?.error ? commitLive(history, settled) : history)
      /* 本标签页没有它的生成状态：后端可能还在生成（刷新/换标签页进来的），挂上续传流接着看 */
      if (!snapshot) void attachLive(id)
      try {
        localStorage.setItem(LAST_SESSION_KEY, id)
      } catch {
        /* 隐私模式等场景忽略持久化失败 */
      }
      /* 恢复该会话的「…」菜单配置：未设置项回退提示词页绑定 / 设置页默认模型 / CoT 默认显示 */
      const item = knownItem ?? sessions.find((s) => s.id === id)
      if (item) {
        setModelId(item.modelId ?? settings?.defaultModelId ?? '')
        setPromptId(item.promptId ?? '')
        setAgentId(item.agentId ?? '')
        setProjectId(item.projectId ?? '')
        setShowCot(item.showCot ?? true)
        setShowRounds(item.showRounds ?? false)
      }
      setPanelOpen(false)
      void refreshSessions() // 后端已把该会话未读清零，刷新列表去掉未读点
      // 再次打开同一会话时，消息数和激活态可能未变，仍需显式切回会话屏。
      setHomeView('conversation')
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`会话加载失败：${msg}`)
    }
  }

  /** 页级通知气泡点进来时调用：切到会话模式并打开目标会话。
   *  会话列表可能还没拉过（直接落在别的页刷新进来的），先补一次以便回显该会话的「…」配置 */
  const openFromNotice = async (id: string) => {
    setChatMode(true)
    if (sessions.length === 0) await refreshSessions()
    await openSession(id)
  }

  /* 把「打开某会话」的入口注册给全站通知器（ChatNotifier）：Ref 里始终是最新实现，
     避免它每次重渲染都重新注册；挂载时若有攒下的跳转请求会被通知器立刻兑现 */
  const openFromNoticeRef = useRef(openFromNotice)
  useEffect(() => {
    openFromNoticeRef.current = openFromNotice
  })
  useEffect(() => {
    registerChatOpener((id) => void openFromNoticeRef.current(id))
    return () => registerChatOpener(null)
  }, [])

  /* 刷新后来的第一步：若刚才正在看的那条会话后端还在生成，自动回到它会话页把进度接上（断点续传）。
     只在确实还在生成时才自动进去——否则不该拿它改掉用户刷新后的落地页。
     两个闸门缺一不可：① 本次页面加载是一进来就落在首页（否则从别的页点「首页」会被拽进去）；
     ② 每次页面加载只试一次（离开首页再回来不该又跳）。 */
  const openSessionRef = useRef(openSession)
  useEffect(() => {
    openSessionRef.current = openSession
  })
  useEffect(() => {
    if (!BOOTED_ON_HOME || resumeAttempted) return
    let last: string | null
    try {
      last = localStorage.getItem(LAST_SESSION_KEY)
    } catch {
      resumeAttempted = true
      return
    }
    const target = last
    if (!target) {
      resumeAttempted = true
      return
    }
    let alive = true
    sessionLive(target)
      .then((snap) => {
        /* StrictMode 下本效应会跑两遍：第一遍被 cleanup 作废，只认还活着的那一遍 */
        if (!alive) return
        /* 判定过了就记下来：本次加载不再重试，离开首页再回来也不跳 */
        resumeAttempted = true
        if (!snap.running) return
        setChatMode(true)
        void openSessionRef.current(target)
      })
      .catch(() => {
        /* 查询失败：当作没有在途生成，照常停在落地页 */
        if (alive) resumeAttempted = true
      })
    return () => {
      alive = false
    }
  }, [])

  /** 删除会话：后端连同消息正本一并清除并刷新列表；删的是当前会话时清空上下文回到起始态 */
  const removeSession = async (id: string) => {
    if (busy) return
    try {
      await deleteSession(id)
      if (id === sessionId) {
        setSessionActive(false)
        setMsgs([])
        setSessionId(null)
      }
      void refreshSessions()
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`删除会话失败：${msg}`)
    }
  }

  /** 新建会话：预创建 id（不传 id 时后端会续接最近会话），清空回显并直接进入会话视图 */
  const startNewSession = async () => {
    if (busy) return
    try {
      /* 新会话带上当前选择的项目：记忆从第一条消息起就落对归属（PROJECT×L1 / 无项目则 AGENT×L2） */
      const s = await createSession(projectId || null)
      setSessionId(s.id)
      setSessionActive(true)
      setHomeView('conversation')
      setMsgs([])
      setPanelOpen(false)
      /* 新会话延续模型 / Agent / CoT / 轮次计数显示选择；身份提示词不延续——新会话默认跟随提示词页的「对话身份」绑定 */
      setPromptId('')
      await saveConfig(s.id, {
        modelId: modelId || null,
        agentId: agentId || null,
        showCot,
        showRounds,
      })
      void refreshSessions()
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`新建会话失败：${msg}`)
    }
  }

  const saveProjectChoice = async (choice: ProjectChoice) => {
    const p = sessionId
      ? await organizeSessionProject(sessionId, choice)
      : await createProject({ name: choice.name, kind: choice.kind, note: choice.note })
    setProjectId(p.id)
    setProjects(await listProjects().then(list => list.filter(item => item.status === 'active')))
    await refreshSessions()
    setToast(`已绑定项目「${p.name}」，可在新会话中继续`)
  }

  const selectProject = async (next: string) => {
    if (busy || projectSaving) return
    setProjectSaving(true)
    try {
      if (sessionId) await bindSessionProject(sessionId, next || null)
      setProjectId(next)
      await refreshSessions()
    } catch (e) {
      const message = errorText(e)
      if (message) setToast(`项目绑定失败：${message}`)
    } finally { setProjectSaving(false) }
  }

  const continueInNewSession = async () => {
    if (!sessionId || busy || projectSaving) return
    setProjectSaving(true)
    setMenuOpen(false)
    try {
      const next = await continueSessionProject(sessionId)
      const list = await refreshSessions()
      await openSession(next.id, list.find(item => item.id === next.id))
      setProjectId(next.projectId ?? '')
      setAgentId(next.agentId ?? '')
      setToast('已创建续接会话，最近进度会在发送消息时带入')
    } catch (e) {
      const message = errorText(e)
      if (message) setToast(`续接失败：${message}`)
    } finally { setProjectSaving(false) }
  }

  const revokeContinuation = async () => {
    if (!sessionId || projectSaving) return
    setProjectSaving(true)
    try {
      await revokeSessionContinuation(sessionId)
      setToast('本会话已停止读取其他会话的进度；重新绑定项目或明确续接可恢复')
      setMenuOpen(false)
    } catch (e) {
      const message = errorText(e)
      if (message) setToast(`取消续接失败：${message}`)
    } finally { setProjectSaving(false) }
  }

  /** 发一条消息：生成全程交给应用级运行时（components/chatLive）托管——页面卸载/切走都不影响它，
   *  重新进入该会话时把快照贴回列表即可续看推理进度、续点「停止」 */
  const send = async (text: string) => {
    if (!text || busy || sendingRef.current) return
    sendingRef.current = true
    try {
      /* 尚无会话 id（New Chat）时先显式新建，避免缺省续接到最近会话 */
      let sid = sessionId
      if (sid === null) {
        const s = await createSession(projectId || null)
        sid = s.id
        setSessionId(s.id)
        setSessionActive(true)
        /* 模型/提示词随本次请求落库，这里补记 Agent 选择、CoT 显示与轮次计数，便于再次进入该会话时回显 */
        await saveConfig(s.id, { agentId: agentId || null, showCot, showRounds })
      }
      setHomeView('conversation')
      startChat({
        sessionId: sid,
        text,
        modelId: modelId || undefined,
        promptId: promptId || undefined,
        onSettled: () => void refreshSessions(), // 标题/排序已变化
      })
    } catch (e) {
      const msg = errorText(e)
      if (msg !== null) setToast(`发送失败：${msg}`)
    } finally {
      sendingRef.current = false
    }
  }

  /** 回退到第 index 条提问之前：后端删除该条及其后的消息（正本与上下文一并回退），
   *  内容放回输入框的未发送状态。先等后端成功再改本地视图，失败时保持原样以免两边不一致 */
  const rewindTo = async (index: number) => {
    const target = msgs[index]
    /* 有生成运行时快照时展示列表比 msgs 多出尾部两条，下标对不上——此刻不允许回退 */
    if (live || !target || target.role !== 'user') return
    if (sessionId !== null) {
      try {
        /* 有落库 id 用 id 定位；发送失败没拿到 id 时后端按 keep（保留前 index 条）兜底 */
        await rewindSession(sessionId, { messageId: target.id ?? null, keep: index })
      } catch (e) {
        const msg = errorText(e)
        if (msg !== null) setToast(`回退失败：${msg}`)
        return
      }
    }
    setMsgs((m) => m.slice(0, index))
    /* 内容放回底部输入框的未发送状态并聚焦（走 ref，不经过 Home 的 state） */
    chatInputRef.current?.setValue(target.content)
    chatInputRef.current?.focus()
  }

  /** 分支：以某条回复为界，在后端把到这条（含）为止的转录复制进一个新会话（源会话不动），
   *  建好后直接打开这个分支会话继续对话 */
  const branchFrom = async (msg: ChatMsg) => {
    if (sessionId === null || msg.id == null || busy) return
    try {
      const created = await branchSession(sessionId, msg.id)
      await refreshSessions()
      await openSession(created.id)
    } catch (e) {
      const text = errorText(e)
      if (text !== null) setToast(`分支失败：${text}`)
    }
  }

  /* AssistantRow 已 memo：分支回调需保持引用稳定，否则每次 Home 重渲染（如流式逐帧）
     都会因回调换新而让全部历史回复跟着重渲染 */
  const branchRef = useRef(branchFrom)
  useEffect(() => {
    branchRef.current = branchFrom
  })
  const onBranch = useCallback((msg: ChatMsg) => {
    void branchRef.current(msg)
  }, [])

  /* UserRow 已 memo：回退回调需保持引用稳定，否则每次 Home 重渲染（如流式逐帧）
     都会因回调换新而让全部历史用户消息跟着重渲染 */
  const rewindRef = useRef(rewindTo)
  useEffect(() => {
    rewindRef.current = rewindTo
  })
  const onRewind = useCallback((index: number) => {
    void rewindRef.current(index)
  }, [])

  /** 停止当前会话的生成：运行时先通知后端该响应不再需要（已产出部分会落库保留），再中断本地流读取。
   *  生成托管在应用级运行时里，所以离开会话再回来照样停得掉 */
  const stop = () => {
    if (sessionId !== null) stopChat(sessionId)
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const text = searchInputRef.current?.getValue().trim() ?? ''
    if (!text) return
    if (chatMode) {
      searchInputRef.current?.setValue('')
      void send(text)
    } else {
      window.location.assign(`#/search?q=${encodeURIComponent(text)}`)
    }
  }

  const returnToSearch = useCallback(() => {
    setHomeView('search')
    setMenuOpen(false)
    setPanelOpen(false)
  }, [])

  useEffect(() => {
    window.addEventListener('minis:home-search', returnToSearch)
    return () => window.removeEventListener('minis:home-search', returnToSearch)
  }, [returnToSearch])

  /** 切换检索/会话模式：搜索框左侧图标按钮与下方滑块共用同一入口 */
  const switchMode = (toChat: boolean) => {
    if (toChat === chatMode) {
      if (toChat && hasChat) setHomeView('conversation')
      return
    }
    if (toChat) {
      /* 切入会话模式：自动拉开左侧抽屉并刷新会话列表，省去再点箭头的一步 */
      setChatMode(true)
      setPanelOpen(true)
      setPanelMountFade(true)
      void refreshSessions()
    } else {
      /* 切回检索模式：清空会话上下文，避免再次切入会话模式时自动恢复
         上次的对话——历史会话的唯一入口是左侧抽屉；身份钉住一并清除，
         新会话回到跟随提示词页绑定 */
      returnToSearch()
      setChatMode(false)
      setPanelMountFade(false)
      setSessionActive(false)
      setMsgs([])
      setSessionId(null)
      setPromptId('')
    }
  }

  const chatSubmit = (e: FormEvent) => {
    e.preventDefault()
    const text = chatInputRef.current?.getValue().trim() ?? ''
    if (!text) return
    chatInputRef.current?.setValue('')
    void send(text)
  }

  return (
    <>
      {/* BA 专属：官网抠像的层叠天空背景（bg → cloud-b → cloud-a → frame），
          云层钟摆漂移的编排见 shell.css 的 .ba-homesky；由右上角悬浮钮开关 */}
      {showSky && (
        <div className="ba-homesky" aria-hidden="true">
          <img src="/theme/blue-archive/students-bg.jpg" alt="" />
          <img
            src="/theme/blue-archive/students-bg-cloud-b.png"
            alt=""
            className="ba-homesky__cloud ba-homesky__cloud--b"
          />
          <img
            src="/theme/blue-archive/students-bg-cloud-a.png"
            alt=""
            className="ba-homesky__cloud ba-homesky__cloud--a"
          />
          <img src="/theme/blue-archive/students-bg-frame.png" alt="" />
        </div>
      )}
      {/* BA 天空的可读性白幕：进入会话界面（chatMode）时 fadeIn，
          罩在天空四层之上、页面内容之下（见 shell.css 的 .ba-homesky-veil） */}
      {showSky && (
        <div
          className={`ba-homesky-veil${chatMode ? ' ba-homesky-veil--on' : ''}`}
          aria-hidden="true"
        />
      )}
      {/* 内容区右上角悬浮钮：隐藏/显示动画背景（仅 BA 主题） */}
      {themeId === 'bluearchive' && (
        <button
          type="button"
          onClick={toggleSky}
          title={skyOn ? '隐藏动画背景' : '显示动画背景'}
          aria-label={skyOn ? '隐藏动画背景' : '显示动画背景'}
          aria-pressed={skyOn}
          className="ba-sky-toggle fixed right-5 top-[84px] z-20 flex h-9 w-9 items-center justify-center rounded-full border-[1.5px] border-line bg-[var(--ui-card-fill,var(--c-bg))] transition hover:border-accent"
          style={{
            color: skyOn ? 'var(--c-accent)' : 'var(--c-faint)',
          }}
        >
          <AppIcon name="cloud" className="h-4.5 w-4.5" />
          {!skyOn && (
            <span className="absolute inset-0 flex items-center justify-center" aria-hidden="true">
              <span className="h-[1.5px] w-6 rotate-45 bg-faint" />
            </span>
          )}
        </button>
      )}
      {/* 错误轻提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      {/* 欢迎语 + 居中搜索框：隐藏的屏幕不参与布局或焦点导航，避免屏外合成层残留。 */}
      <section
        ref={searchPageRef}
        data-home-view="search"
        hidden={showingChat}
        style={{ minHeight: pageH || undefined, display: showingChat ? 'none' : undefined }}
        className={`flex min-h-[calc(100dvh-8.5rem)] flex-col${themeId === 'astrolabe' ? ' astro-home' : ''}`}
      >
        {/* 标题到搜索框的整体区域，在 content 内垂直居中 */}
        <div className="relative my-auto">
          {/* 净空层：标题区压一记底色光晕，把背景层的网格/全息噪声让开，裸排文本才有层级。
              BA 除外——它假设「背景≈底色」才隐形，BA 的实景天空上会显影成中心糊斑 */}
          {themeId !== 'bluearchive' && themeId !== 'astrolabe' && (
            <div
              className="sh-clear pointer-events-none absolute -inset-y-14 inset-x-0"
              aria-hidden="true"
            />
          )}
          <div className="relative">
            {themeId !== 'bluearchive' && themeId !== 'astrolabe' && (
              /* 军事 HUD 语汇的标签只随其它主题；BA 不显示（其标题图自带氛围） */
              <p className="ui-label text-center">// SYSTEM ONLINE</p>
            )}
            {themeId === 'astrolabe' ? (
              <div className="astro-home__intro">
                <OrbitalSeal className="astro-home__engraving" />
                <p className="astro-home__overline">PRIVATE OBSERVATORY / 私人知识研究室</p>
                <h1 className="ui-heading astro-home__title">每一个问题，<br />都是一颗<em>新的星辰。</em></h1>
                <p className="astro-home__subtitle">在知识之间发现联系，让灵感沿着自己的轨道生长。</p>
                <p className="astro-home__caption">A little curiosity. An infinite constellation.</p>
              </div>
            ) : themeId === 'bluearchive' ? (
              /* 主题专属标题图（public/theme/blue-archive/）：width/height 登记原图
                 906×250 防加载抖动，CSS 收到 720px 宽、高按比例 */
              <img
                src="/theme/blue-archive/title.png"
                alt="欢迎回来, 我的朋友!"
                width={906}
                height={250}
                draggable={false}
                className="mx-auto mt-2 block h-auto w-180 max-w-[80vw] select-none pointer-events-none"
              />
            ) : (
              <h1 className="ui-heading mt-2 text-center text-4xl tracking-tight">
                欢迎回来, 我的朋友!
              </h1>
            )}
            <p className="mt-2.5 text-center text-sm text-faint">
              点击搜索框左侧图标或是下方按钮，可在检索与会话模式间切换
            </p>
          </div>

          <div className="mx-auto mt-7 w-full max-w-2xl">
            <form onSubmit={submit}>
              <div className="astro-composer ba-home-inputbar ui-surface flex items-center gap-2 rounded-full border border-line py-1.5 pl-5 pr-1.5 shadow-md transition hover:border-line-strong focus-within:border-accent">
                <button
                  type="button"
                  onClick={() => switchMode(!chatMode)}
                  title={chatMode ? '切换为检索模式' : '切换为会话模式'}
                  aria-label={chatMode ? '切换为检索模式' : '切换为会话模式'}
                  className="ba-home-modebtn shrink-0 cursor-pointer rounded-full p-1 text-faint transition hover:text-muted"
                >
                  {chatMode ? (
                    <AppIcon name="chat" className="h-4.5 w-4.5" />
                  ) : (
                    <AppIcon name="search" className="h-4.5 w-4.5" />
                  )}
                </button>
                <DecoupledInput
                  ref={searchInputRef}
                  placeholder={chatMode ? '输入问题，开始对话…' : '输入关键词或问题…'}
                  className="min-w-0 flex-1 bg-transparent py-2.5 text-md text-fg outline-none placeholder:text-hint"
                  autoFocus
                />
                {busy ? (
                  /* 等待回复期间发送按钮改为「停止」：点击通知后端放弃等待并中断本地读取 */
                  <button
                    type="button"
                    onClick={stop}
                    className="ba-home-inputbar-btn flex shrink-0 cursor-pointer items-center justify-center gap-1.5 rounded-full bg-accent px-6 py-2.5 text-sm font-medium text-accent-fg transition hover:bg-accent-hover"
                  >
                    <AppIcon name="stop" className="h-3 w-3" />
                    停止
                  </button>
                ) : (
                  <button
                    type="submit"
                    className="ba-home-inputbar-btn flex shrink-0 items-center justify-center gap-1.5 rounded-full bg-accent px-6 py-2.5 text-sm font-medium text-accent-fg transition enabled:hover:bg-accent-hover disabled:opacity-40"
                  >
                    {chatMode ? (
                      <>
                        <AppIcon name="send" className="h-3.5 w-3.5" />
                        发送
                      </>
                    ) : (
                      <>
                        <AppIcon name="search" className="h-3.5 w-3.5" />
                        搜索
                      </>
                    )}
                  </button>
                )}
              </div>
            </form>

            {/* 模式滑块：与搜索框左侧图标按钮同步切换检索/会话模式；
                「搜索」按钮双击为测试暗门：进入 RAG 模式搜索页（只返回 chunk 命中） */}
            <div className="mt-4 flex justify-center">
              <div className="ba-home-slider ui-surface flex items-center rounded-full border border-line p-1 shadow-md">
                <button
                  type="button"
                  onClick={() => switchMode(false)}
                  onDoubleClick={() => {
                    const text = searchInputRef.current?.getValue().trim() ?? ''
                    window.location.assign(
                      `#/search?rag=1${text ? `&q=${encodeURIComponent(text)}` : ''}`,
                    )
                  }}
                  aria-pressed={!chatMode}
                  className={`ba-home-slider-btn flex cursor-pointer items-center gap-1.5 rounded-full px-4 py-1.5 text-base font-medium transition ${
                    !chatMode
                      ? 'bg-accent text-accent-fg'
                      : 'text-muted hover:text-fg'
                  }`}
                >
                  <AppIcon name="search" className="h-3.5 w-3.5" />
                  搜索
                </button>
                <button
                  type="button"
                  onClick={() => switchMode(true)}
                  aria-pressed={chatMode}
                  className={`ba-home-slider-btn flex cursor-pointer items-center gap-1.5 rounded-full px-4 py-1.5 text-base font-medium transition ${
                    chatMode
                      ? 'bg-accent text-accent-fg'
                      : 'text-muted hover:text-fg'
                  }`}
                >
                  <AppIcon name="chat" className="h-3.5 w-3.5" />
                  会话
                </button>
                {/* 直达群聊：本页不开对话，跳到圆桌页并打开最近一次群聊（一次会话都没有时，
                    那边就是一张空桌——发出第一句时后端按全体名册开一桌） */}
                <button
                  type="button"
                  onClick={() => window.location.assign('#/collaboration')}
                  title="打开最近一次群聊"
                  className="ba-home-slider-btn flex cursor-pointer items-center gap-1.5 rounded-full px-4 py-1.5 text-base font-medium text-muted transition hover:text-fg"
                >
                  <AppIcon name="table" className="h-3.5 w-3.5" />
                  群聊
                </button>
              </div>
            </div>
          </div>
          {themeId === 'astrolabe' && <div className="astro-home__links">
            <a href="#/articles"><span aria-hidden="true">01</span>翻阅文档 <span aria-hidden="true">↗</span></a>
            <a href="#/graph"><span aria-hidden="true">02</span>探索图谱 <span aria-hidden="true">↗</span></a>
            <a href="#/multi-agent"><span aria-hidden="true">03</span>与智能体协作 <span aria-hidden="true">↗</span></a>
          </div>}
        </div>
      </section>

      {/* 会话屏：保持挂载以保留草稿；仅在显式进入会话时显示，不用外层滚动模拟路由切换。 */}
      {hasChat && (
        <section
          ref={chatPageRef}
          data-home-view="conversation"
          hidden={!showingChat}
          className="relative flex h-[calc(100dvh-8.5rem)] flex-col"
          style={{ height: pageH || undefined, display: showingChat ? undefined : 'none' }}
        >
          {/* 待办清单悬浮卡片（plan-of-record）：固定在视口右侧、垂直居中，不随消息列表滚动。
              外层 pointer-events-none 让卡片四周的空白不挡交互，只有卡片本身可点/可滚 */}
          {todos.length > 0 && (
            <div className="pointer-events-none fixed right-4 top-1/2 z-40 -translate-y-1/2">
              <div className="pointer-events-auto">
                <TodoCard todos={todos} />
              </div>
            </div>
          )}
          {/* 会话目录（形态同文档渲染页）：圆点轨与正文右缘留出空隙，宽屏向外移 64px，
              窄窗口至少保留 8px 的右侧边距，避免轨道超出可视区域。
              hover 浮出各次提问，点它跳到那一条提问；返回搜索屏时随会话屏一起隐藏。 */}
          {tocItems.length > 0 && (
            <aside className="absolute right-[max(0.5rem,calc(50%_-_26rem))] top-1/2 z-20 hidden -translate-y-1/2 lg:block">
              <TocRail items={tocItems} side="right" scroller={getChatScroller} compact />
            </aside>
          )}
          <div
            ref={chatScrollRef}
            className="scrollbar-hide flex-1 overflow-y-auto overscroll-contain"
          >
            <div className="mx-auto w-full max-w-2xl space-y-5 pb-6 pt-10">
              {view.map((m, i) =>
                m.role === 'user' ? (
                  /* memo 行：仅自身内容 / 回退可用态变化时重渲染 */
                  <UserRow
                    key={m.id ?? `i${i}`}
                    msg={m}
                    index={i}
                    canRewind={!live}
                    onRewind={onRewind}
                  />
                ) : m.kind === 'subagent' ? (
                  /* 子代理结论：专属气泡，只显示标题+摘要，点开弹窗看全文 */
                  <SubagentBubble key={m.id ?? `i${i}`} msg={m} onOpen={setReportView} />
                ) : (
                  <AssistantRow
                    key={m.id ?? `i${i}`}
                    msg={m}
                    showCot={showCot}
                    showRounds={showRounds}
                    canBranch={!busy && m.id != null && !m.streaming}
                    onBranch={onBranch}
                    speechAgentId={agentId || 'builtin'}
                    speechEnabled={speechEnabled}
                    canAnswer={!busy && !live?.error && i === lastConversationIndex}
                    onAnswer={send}
                  />
                ),
              )}
              {busy && !view[view.length - 1]?.content && (
                <p className="pt-1 text-base text-hint">正在思考 …</p>
              )}
            </div>
          </div>

          {/* 底部输入框：不随消息列表滚动；左侧按钮返回搜索屏并移交焦点。 */}
          <div className="mx-auto w-full max-w-2xl pb-4">
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={returnToSearch}
                title="返回搜索"
                aria-label="返回搜索界面"
                className="flex h-10 w-10 shrink-0 cursor-pointer items-center justify-center rounded-full bg-accent text-accent-fg shadow-lg transition hover:bg-accent-hover"
              >
                <AppIcon name="home" className="h-4.5 w-4.5" />
              </button>
              <form onSubmit={chatSubmit} className="min-w-0 flex-1">
                <div className="astro-composer ui-surface relative flex items-center gap-2 rounded-full border border-line py-1 pl-5 pr-1 shadow-md transition hover:border-line-strong focus-within:border-accent">
                  <DecoupledInput
                    ref={chatInputRef}
                    placeholder="继续输入…"
                    className="min-w-0 flex-1 bg-transparent py-2 text-md text-fg outline-none placeholder:text-hint"
                    onHasTextChange={setHasText}
                  />
                  {/* 上下文占比圆环：实心弧长=当前上下文占模型窗口的百分比，hover 显示数值 */}
                  <div className="group relative flex shrink-0 items-center" aria-label="上下文占用">
                    <svg className="h-5 w-5 -rotate-90" viewBox="0 0 36 36" aria-hidden="true">
                      <circle
                        cx="18"
                        cy="18"
                        r="15.915"
                        fill="none"
                        strokeWidth="4"
                        stroke="currentColor"
                        className="text-line"
                      />
                      <circle
                        cx="18"
                        cy="18"
                        r="15.915"
                        fill="none"
                        strokeWidth="4"
                        strokeLinecap="round"
                        pathLength={100}
                        strokeDasharray={`${contextPct} ${100 - contextPct}`}
                        stroke="currentColor"
                        className="text-accent transition-[stroke-dasharray] duration-300"
                      />
                    </svg>
                    <span className="pointer-events-none absolute bottom-full left-1/2 z-30 mb-2 hidden -translate-x-1/2 whitespace-nowrap rounded-md border border-line bg-bg px-2 py-1 text-xs text-fg shadow-lg group-hover:block">
                      上下文 {contextPct.toFixed(1)}%
                    </span>
                  </div>
                  {/* 「…」：向上弹出菜单，切换本次会话的模型 / 提示词（仅影响后续消息） */}
                  {settings && (
                    <div ref={menuRef} className="relative shrink-0">
                      <button
                        type="button"
                        onClick={() => setMenuOpen((v) => !v)}
                        title="模型与提示词"
                        aria-label="模型与提示词"
                        aria-expanded={menuOpen}
                        className="flex h-9 w-9 shrink-0 cursor-pointer items-center justify-center rounded-full bg-accent text-accent-fg transition hover:bg-accent-hover"
                      >
                        <AppIcon name="more" className="h-4 w-4" />
                      </button>
                      {menuOpen && (
                        <div className="absolute bottom-full right-0 z-30 mb-2 grid grid-cols-[16rem_18rem] overflow-hidden rounded-xl border border-line bg-bg shadow-xl">
                          {/* 左栏：Agent 网格与项目记忆入口 */}
                          <div className="scrollbar-slim flex max-h-[75vh] w-64 shrink-0 flex-col overflow-y-auto border-r border-line p-2">
                            <p className="px-2 pb-2 pt-1 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                              选择 Agent
                            </p>
                            <div className="scrollbar-slim grid max-h-60 shrink-0 grid-cols-3 gap-1.5 overflow-y-auto p-1">
                            {peers.length === 0 && (
                              <p className="col-span-3 px-2 py-1.5 text-2xs text-faint">名册加载中…</p>
                            )}
                            {peers.map((p) => (
                              <button
                                key={p.id}
                                type="button"
                                title={p.name}
                                aria-label={p.name}
                                aria-pressed={effectiveAgentId === p.id}
                                onClick={() => {
                                  /* 内置 Agent 存空串 = 清除所选、回到默认（后端落 NULL） */
                                  const next = p.builtin ? '' : p.id
                                  setAgentId(next)
                                  if (sessionId !== null) void saveConfig(sessionId, { agentId: next })
                                }}
                                className={`flex min-w-0 cursor-pointer flex-col items-center gap-2 rounded-lg border px-1.5 py-3 text-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40 ${
                                  effectiveAgentId === p.id
                                    ? 'border-accent/40 bg-accent/10 font-medium text-accent'
                                    : 'border-transparent text-muted hover:border-line hover:bg-fg/5 hover:text-fg'
                                }`}
                              >
                                <PeerAvatar
                                  name={p.name}
                                  avatar={p.avatar}
                                  size="h-11 w-11"
                                  textClass="text-base"
                                />
                                <span className="w-full truncate text-xs leading-4">{p.name}</span>
                              </button>
                            ))}
                          </div>
                          {effectiveAgentId !== 'builtin' && (
                            <p className="mt-1 px-2 pt-2 text-2xs text-faint">
                              由「{currentAgentName}」的人设决定 system；右栏「身份提示词」对其不生效
                            </p>
                          )}
                          <div className="mt-3 border-t border-line pt-3">
                          <p className="px-2 pb-1 pt-1.5 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                            项目与记忆
                          </p>
                          <div className="px-2 pb-1.5">
                            <Select
                              value={projectId}
                              onChange={(next) => { void selectProject(next) }}
                              options={[
                                { value: '', label: '无项目（个人工作域）' },
                                ...projects.map((p) => ({
                                  value: p.id,
                                  label: `${p.name}${p.narrative ? '（创作 · 不升画像）' : ''}`,
                                })),
                              ]}
                            />
                          <p className="mt-1 text-2xs text-faint">绑定后，同一 Agent 可续接项目内其他会话的最近进度。</p>
                            <div className="mt-3 flex flex-col gap-2">
                              <button type="button" disabled={busy || projectSaving} className={`${projectActionBtn} border-accent bg-accent text-accent-fg hover:border-accent-hover hover:bg-accent-hover`} onClick={() => { setMenuOpen(false); setProjectDialog('create') }}>
                                <AppIcon name="plus" className="h-4 w-4 shrink-0" />
                                <span>新建项目并绑定</span>
                              </button>
                              {sessionId && <>
                                <button type="button" disabled={busy || projectSaving} className={`${projectActionBtn} border-line bg-bg text-fg hover:border-accent/40 hover:bg-accent/5`} onClick={() => { setMenuOpen(false); setProjectDialog('organize') }}>
                                  <AppIcon name="folder-open" className="h-4 w-4 shrink-0 text-muted" />
                                  <span>将当前会话整理为项目</span>
                                </button>
                                <button type="button" disabled={busy || projectSaving} className={`${projectActionBtn} border-line bg-bg text-fg hover:border-accent/40 hover:bg-accent/5`} onClick={() => { void continueInNewSession() }}>
                                  <AppIcon name="external" className="h-4 w-4 shrink-0 text-muted" />
                                  <span>在新会话继续{projectId ? '项目' : '当前会话'}</span>
                                </button>
                                <button type="button" disabled={projectSaving} className={`${projectActionBtn} mt-1 border-red-500/20 bg-red-500/5 text-red-500 hover:border-red-500/40 hover:bg-red-500/10`} onClick={() => { void revokeContinuation() }}>
                                  <AppIcon name="stop" className="h-4 w-4 shrink-0" />
                                  <span>停止本会话的跨会话续接</span>
                                </button>
                              </>}
                            </div>
                          </div>
                          </div>
                          </div>
                          {/* 右栏：原有会话配置（模型 / 身份提示词 / 显示 / 生成 / 检索 / 调试） */}
                          <div className="scrollbar-slim max-h-[75vh] w-72 overflow-y-auto p-1.5">
                          <p className="px-2 pb-1 pt-1.5 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                            模型
                          </p>
                          <div className="scrollbar-slim max-h-48 overflow-y-auto">
                            {settings.models.map((m) => (
                              <button
                                key={m.id}
                                type="button"
                                onClick={() => {
                                  setModelId(m.id)
                                  if (sessionId !== null) void saveConfig(sessionId, { modelId: m.id })
                                }}
                                className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition ${
                                  modelId === m.id
                                    ? 'bg-accent/10 font-medium text-fg'
                                    : 'text-muted hover:bg-fg/5 hover:text-fg'
                                }`}
                              >
                                <ModelBrandIcon model={m.model} />
                                <span className="flex-1 truncate">
                                  {m.name || m.model || '.env'}
                                </span>
                                {modelId === m.id && (
                                  <AppIcon name="check" className="h-3.5 w-3.5 shrink-0" />
                                )}
                              </button>
                            ))}
                          </div>
                          <p className="mt-1 border-t border-line px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                            身份提示词
                          </p>
                          <div className="scrollbar-slim max-h-48 overflow-y-auto">
                            {/* 跟随默认：清除本会话的身份钉住，生效项回到「对话身份」位的全局绑定 */}
                            <button
                              type="button"
                              onClick={() => {
                                setPromptId('')
                                if (sessionId !== null) void saveConfig(sessionId, { promptId: '' })
                              }}
                              className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition ${
                                !pinnedPromptId
                                  ? 'bg-accent/10 font-medium text-fg'
                                  : 'text-muted hover:bg-fg/5 hover:text-fg'
                              }`}
                            >
                              <span className="flex-1 truncate">跟随默认</span>
                              {!pinnedPromptId && (
                                <AppIcon name="check" className="h-3.5 w-3.5 shrink-0" />
                              )}
                            </button>
                            {identityOptions.map((p) => (
                              <button
                                key={p.id}
                                type="button"
                                onClick={() => {
                                  setPromptId(p.id)
                                  if (sessionId !== null)
                                    void saveConfig(sessionId, { promptId: p.id })
                                }}
                                className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition ${
                                  pinnedPromptId === p.id
                                    ? 'bg-accent/10 font-medium text-fg'
                                    : 'text-muted hover:bg-fg/5 hover:text-fg'
                                }`}
                              >
                                <span className="flex-1 truncate">{p.name}</span>
                                {pinnedPromptId === p.id && (
                                  <AppIcon name="check" className="h-3.5 w-3.5 shrink-0" />
                                )}
                              </button>
                            ))}
                          </div>
                          <p className="mt-1 border-t border-line px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                            显示
                          </p>
                          <button
                            type="button"
                            role="switch"
                            aria-checked={showCot}
                            onClick={() => {
                              const next = !showCot
                              setShowCot(next)
                              if (sessionId !== null) void saveConfig(sessionId, { showCot: next })
                            }}
                            className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-fg/5 ${
                              showCot ? 'text-fg' : 'text-muted hover:text-fg'
                            }`}
                          >
                            <span className="flex-1 truncate">思维链（CoT）</span>
                            <span
                              className={`relative h-[18px] w-8 shrink-0 rounded-full transition-colors ${
                                showCot ? 'bg-accent' : 'bg-fg/20'
                              }`}
                            >
                              <span
                              className={`absolute top-0.5 h-3.5 w-3.5 rounded-full shadow-sm transition-all ${
                                showCot ? 'left-4 bg-accent-fg' : 'left-0.5 bg-bg'
                              }`}
                            />
                          </span>
                        </button>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={showRounds}
                          title="在每个思维链分段的右侧标出它属于第几轮工具往返"
                          onClick={() => {
                            const next = !showRounds
                            setShowRounds(next)
                            if (sessionId !== null) void saveConfig(sessionId, { showRounds: next })
                          }}
                          className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-fg/5 ${
                            showRounds ? 'text-fg' : 'text-muted hover:text-fg'
                          }`}
                        >
                          <span className="flex-1 truncate">思维链轮数</span>
                          <span
                            className={`relative h-[18px] w-8 shrink-0 rounded-full transition-colors ${
                              showRounds ? 'bg-accent' : 'bg-fg/20'
                            }`}
                          >
                            <span
                              className={`absolute top-0.5 h-3.5 w-3.5 rounded-full shadow-sm transition-all ${
                                showRounds ? 'left-4 bg-accent-fg' : 'left-0.5 bg-bg'
                              }`}
                            />
                          </span>
                        </button>
                        <p className="mt-1 border-t border-line px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                          检索
                        </p>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={settings.rerank.mode !== 'off'}
                          title="用 cross-encoder 对检索结果重新打分排序；关闭后保持 RRF 融合原序（端点在设置页配置）"
                          onClick={() => void toggleRerank()}
                          className={`flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition hover:bg-fg/5 ${
                            settings.rerank.mode !== 'off' ? 'text-fg' : 'text-muted hover:text-fg'
                          }`}
                        >
                          <span className="flex-1 truncate">Rerank 精排</span>
                          <span
                            className={`relative h-[18px] w-8 shrink-0 rounded-full transition-colors ${
                              settings.rerank.mode !== 'off' ? 'bg-accent' : 'bg-fg/20'
                            }`}
                          >
                            <span
                              className={`absolute top-0.5 h-3.5 w-3.5 rounded-full shadow-sm transition-all ${
                                settings.rerank.mode !== 'off'
                                  ? 'left-4 bg-accent-fg'
                                  : 'left-0.5 bg-bg'
                              }`}
                            />
                          </span>
                        </button>
                        <p className="mt-1 border-t border-line px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
                          调试
                        </p>
                        <button
                          type="button"
                          onClick={() => void viewRawContext()}
                          title="查看此刻发消息时系统发给大模型接口的完整请求体（model + messages + tools）"
                          className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm text-muted transition hover:bg-fg/5 hover:text-fg"
                        >
                          <span className="flex-1 truncate">查看原始上下文</span>
                          <AppIcon name="code" className="h-3.5 w-3.5 shrink-0" />
                        </button>
                          </div>
                      </div>
                      )}
                    </div>
                  )}
                  {sessionId && <ConversationBrowser key={sessionId} sessionId={sessionId} />}
                  {busy ? (
                    /* 等待回复期间发送按钮改为「停止」：点击通知后端放弃等待并中断本地读取 */
                    <button
                      type="button"
                      onClick={stop}
                      className="flex shrink-0 cursor-pointer items-center justify-center gap-1.5 rounded-full bg-accent px-5 py-2 text-base font-medium text-accent-fg shadow-md transition hover:bg-accent-hover"
                    >
                      <AppIcon name="stop" className="h-3 w-3" />
                      停止
                    </button>
                  ) : (
                    <button
                      type="submit"
                      disabled={!hasText}
                      className="flex shrink-0 items-center justify-center gap-1.5 rounded-full bg-accent px-5 py-2 text-base font-medium text-accent-fg shadow-md transition enabled:hover:bg-accent-hover disabled:opacity-40"
                    >
                      <AppIcon name="send" className="h-3.5 w-3.5" />
                      发送
                    </button>
                  )}
                </div>
              </form>
            </div>
          </div>
        </section>
      )}

      {/* 会话模式下的左侧悬浮抽屉：箭头拉手控制开合，抽屉自主视图左缘渐入渐出滑出（历史保存在后端）。
          左缘基准随排布走：侧边栏档/桌面壳避开左侧栏，贴主视图左缘；默认贴视口最左侧。
          横向裁剪（overflow-x: clip）：抽屉向左收纳/渐隐时，超出主视图左缘的虚影要被裁掉，
          不能盖到侧栏/菜单栏上；只裁横向，纵向阴影照旧浮出 */}
      {chatMode && (
        <div
          className="home-session-drawer fixed top-1/2 z-40 flex -translate-y-1/2 items-center overflow-x-clip"
          style={{ left: panelLeft }}
        >
          <aside
            aria-label="最近会话"
            className={`flex max-h-[calc(100dvh-32px)] w-64 shrink-0 flex-col rounded-r-xl border-y border-r border-line bg-bg p-2 shadow-xl transition-all duration-300 ease-out ${
              panelMountFade ? 'panel-fade-in' : ''
            } ${
              panelOpen
                ? 'opacity-100'
                : 'pointer-events-none invisible -ml-64 opacity-0'
            }`}
          >
            <div className="flex shrink-0 items-center justify-between gap-2 px-1.5 pb-1.5 pt-0.5">
              <span className="text-base font-medium text-fg">最近会话</span>
              <span role="status" aria-live="polite" className="flex h-5 w-5 items-center justify-center text-muted">
                {sessionsLoading && <><AppIcon name="loading" className="home-session-loading h-4 w-4" /><span className="sr-only">会话列表加载中</span></>}
              </span>
            </div>
            <button
              type="button"
              onClick={() => void startNewSession()}
              disabled={busy}
              className="mb-1.5 flex w-full shrink-0 items-center justify-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-accent-fg transition enabled:hover:bg-accent-hover disabled:opacity-40"
            >
              <AppIcon name="plus" className="h-3.5 w-3.5" />
              新会话
            </button>
            <div className="home-session-list scrollbar-slim" aria-busy={sessionsLoading}>
              {sessionsLoading && sessions.length === 0 ? (
                <div className="home-session-skeleton" aria-hidden="true">
                  {[0, 1, 2, 3].map(row => <div key={row} className="home-session-skeleton__row"><span /><span /></div>)}
                </div>
              ) : sessions.length === 0 ? (
                <div className="flex h-full flex-col items-center justify-center gap-3 px-2 text-sm text-hint">
                  <p>{sessionsError ? '会话列表加载失败' : '暂无会话记录'}</p>
                  {sessionsError && <button type="button" className="ui-btn ui-btn--quiet text-sm" onClick={() => void refreshSessions()}>重新加载</button>}
                </div>
              ) : (
                sessions.map((s) => (
                  <div
                    key={s.id}
                    className={`group mb-0.5 flex items-center rounded-lg border transition ${
                      s.id === sessionId
                        ? 'border-secondary bg-secondary/10'
                        : 'border-transparent hover:border-line hover:bg-secondary/10'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() => void openSession(s.id)}
                      className="min-w-0 flex-1 cursor-pointer px-2.5 py-2 text-left"
                    >
                      <span className="flex items-center gap-1.5">
                        {s.unread && (
                          <span
                            className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent"
                            title="潜意识子体往这个会话投了新消息"
                            aria-label="有未读投递"
                          />
                        )}
                        <span
                          className={`block truncate text-base text-fg ${
                            s.unread ? 'font-semibold' : ''
                          }`}
                        >
                          {s.title || '未命名会话'}
                        </span>
                      </span>
                      <span className="mt-0.5 block text-xs text-hint">
                        {s.generating ? (
                          /* 后端为这次生成留了在途状态：哪怕你离开过，答完也会通知，这里先标个「生成中」 */
                          <span className="inline-flex items-center gap-1 text-accent">
                            <span className="h-1 w-1 animate-pulse rounded-full bg-accent" aria-hidden="true" />
                            生成中…
                          </span>
                        ) : (
                          fmtTime(s.updatedAt)
                        )}
                      </span>
                    </button>
                    <button
                      type="button"
                      onClick={() => void removeSession(s.id)}
                      disabled={busy}
                      title="删除会话"
                      aria-label="删除会话"
                      className="mr-1 flex h-6 w-6 shrink-0 cursor-pointer items-center justify-center rounded-md text-hint opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 enabled:hover:bg-secondary/20 enabled:hover:text-fg disabled:cursor-default disabled:group-hover:opacity-40"
                    >
                      <AppIcon name="close" className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))
              )}
            </div>
          </aside>
          <button
            type="button"
            onClick={() => {
              setPanelOpen((v) => !v)
              if (!panelOpen) void refreshSessions()
            }}
            title={panelOpen ? '收起会话列表' : '展开会话列表'}
            aria-label={panelOpen ? '收起会话列表' : '展开会话列表'}
            aria-expanded={panelOpen}
            className={`relative z-10 flex h-12 w-6 cursor-pointer items-center justify-center rounded-r-md bg-accent text-accent-fg shadow-lg transition-all duration-300 ease-out hover:bg-accent-hover ${
              panelMountFade ? 'panel-fade-in' : ''
            }`}
          >
            <AppIcon name="chevron-right" className={`h-4 w-4 transition-transform duration-300 ease-out ${
                panelOpen ? 'rotate-180' : ''
              }`} />
          </button>
        </div>
      )}

      {projectDialog && <ProjectDialog
        organize={projectDialog === 'organize'}
        initialName={sessions.find(item => item.id === sessionId)?.title ?? ''}
        projects={projects}
        onConfirm={saveProjectChoice}
        onClose={() => setProjectDialog(null)}
      />}

      {/* 子代理完整结论：点气泡打开，Esc / 关闭钮退出（可拖动、无遮罩，与其它详情窗一致） */}
      {reportView && (
        <FloatWindow
          title={`${reportView.subagent?.title || '子代理回报'} · 完整结论`}
          onClose={() => setReportView(null)}
          width={720}
        >
          <div className="text-base leading-relaxed text-fg">
            {reportView.subagent?.summary && (
              <p className="mb-3 border-l-2 border-accent/50 bg-fg/[0.03] px-2.5 py-1.5 text-sm text-muted">
                {reportView.subagent.summary}
              </p>
            )}
            <ChatMarkdown content={reportView.content} />
          </div>
        </FloatWindow>
      )}

      {/* 原始上下文：「…」菜单的「查看原始上下文」打开，完整请求体（model + messages + tools）
          以 Turn 感知的 JSON 预览器展示——此刻发消息时系统发给大模型接口的就是这份内容。
          messages 按 Turn 切片、每条消息默认折叠；每个 Turn 右侧可回看它每一轮的请求体 / 响应体（可拖动） */}
      {rawView && (
        <FloatWindow
          title="原始上下文"
          onClose={() => setRawView(null)}
          width={960}
          contentClassName="raw-context-window-body"
        >
          <RawContextView ctx={rawView.ctx} sessionId={rawView.sessionId} />
        </FloatWindow>
      )}
    </>
  )
}
