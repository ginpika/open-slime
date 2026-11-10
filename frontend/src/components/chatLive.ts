import { useSyncExternalStore } from 'react'
import {
  chatStream,
  loginUrlOf,
  sessionLive,
  sessionLiveStream,
  steerChat,
  stopChat as apiStopChat,
  type ChatStreamReset,
  type ChatToolActivity,
  type ChatToolActivityEvent,
  type InterimReply,
  type LiveRun,
  type SteerMessage,
  type SubagentBrief,
  type TraceSegment,
  type ReasoningEffort,
} from '../api/client'

/**
 * 会话生成的「运行时」：把一次「发送 → 流式生成 → 落定」的全过程从页面组件搬进应用级模块仓。
 *
 * <p><b>为什么</b>：生成期间用户切到别的页面（或别的路由）时，会话页组件会被卸载，而请求还在后端
 * 继续跑。生成状态若只活在组件里，重新进入该会话就既看不到进度、也点不到「停止」——本仓解决的就是这个：
 * 离开只是「不再渲染」，生成照跑；重新进入会话时把仓里的快照贴回消息列表，进度与停止都接得上。
 *
 * <p>状态载体是 {@link LiveChat}（按会话 id 归档），用 useSyncExternalStore 订阅，与 theme/store 同一套写法。
 */

/** 一条会话消息（与页面渲染态一致）：reasoning 为思维链、trace 为结构化分段 */
export type ChatMsg = {
  id?: number
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  trace?: TraceSegment[]
  kind?: string
  subagent?: SubagentBrief | null
  /** 是否仍在流式生成中：为 true 时正文按纯文本渲染，避免逐 token 重解析 markdown；
   *  生成结束后置回 false，再切到 markdown 正式渲染 */
  streaming?: boolean
}

/** 一个会话当前（或刚结束）的生成快照 */
export type LiveChat = {
  sessionId: string
  /** 本次提问的原文与落库行 id（id 供「回退到这条提问之前」定位删除区间） */
  userText: string
  userMessageId: number | null
  steers: SteerMessage[]
  interims: InterimReply[]
  /** 正在生成的回答正文 / 思维链 / 结构化分段 */
  content: string
  reasoning: string
  trace: TraceSegment[]
  /** 按开始顺序排列的在途工具调用。 */
  activeTools: ChatToolActivity[]
  /** 是否仍在生成 */
  running: boolean
  stopping?: boolean
  /** 生成失败的原因（null=没失败）：失败时半截输出要贴回会话，别让用户白等一场 */
  error: string | null
}

/* ---------------- 仓 + 订阅 ---------------- */

const live = new Map<string, LiveChat>()
const listeners = new Set<() => void>()

function emit(): void {
  listeners.forEach((l) => l())
}

function subscribe(l: () => void): () => void {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}

/** 会话 id → 已打开的原始流请求 */
const starts = new Set<string>()

/** 就地把某会话的快照换成 updater 的返回值（快照已被清掉则忽略） */
function patch(sessionId: string, updater: (cur: LiveChat) => LiveChat): void {
  const cur = live.get(sessionId)
  if (!cur) return
  const next = updater(cur)
  if (next === cur) return
  live.set(sessionId, next)
  emit()
}

function appendSteer(cur: LiveChat, message: SteerMessage): LiveChat {
  if (cur.steers.some(steer => steer.messageId === message.messageId)) return cur
  return { ...cur, steers: [...cur.steers, message], content: '' }
}

/** POST 和流事件都会确认已落库的消息，用行 id 合并，避免重复显示。 */
export async function steerActiveChat(sessionId: string, messageId: number, input: string): Promise<void> {
  const accepted = await steerChat(sessionId, messageId, input)
  patch(sessionId, cur => cur.userMessageId === messageId
    ? appendSteer(cur, { messageId: accepted.messageId, input })
    : cur)
}

/** 读某会话当前的生成快照（没有则 null） */
export function getLive(sessionId: string | null): LiveChat | null {
  return sessionId === null ? null : (live.get(sessionId) ?? null)
}

/** 丢弃某会话的快照：生成已落定、且要改以会话历史（数据库）为准时调用 */
export function clearLive(sessionId: string): void {
  if (live.delete(sessionId)) emit()
}

/* ---------------- 会话页正在看哪条会话（供通知抑制） ---------------- */

/** 会话页此刻正在看的会话 id（没在会话语境里时为 null）：
 *  页级通知据此跳过「你正看着它」的提醒——输出就在眼前，不必再弹 */
let activeSession: string | null = null

export function setActiveChatSession(id: string | null): void {
  activeSession = id
}

export function getActiveChatSession(): string | null {
  return activeSession
}

/* ---------------- 生成落定订阅（供页级通知） ---------------- */

/** 一次生成收尾（成功 / 停止 / 失败）的订阅者集合 */
const settledListeners = new Set<(sessionId: string) => void>()

/** 订阅「某会话的生成刚收尾」：页级通知用它做到即时提醒（比轮询会话列表更及时，短回答也不会漏） */
export function onChatSettled(l: (sessionId: string) => void): () => void {
  settledListeners.add(l)
  return () => {
    settledListeners.delete(l)
  }
}

function emitSettled(sessionId: string): void {
  settledListeners.forEach((l) => l(sessionId))
}

/** 订阅某会话的生成快照：跨组件挂载存活，重新进入会话即可拿回进度 */
export function useLiveChat(sessionId: string | null): LiveChat | null {
  return useSyncExternalStore(subscribe, () => getLive(sessionId))
}

/* ---------------- 生成引擎 ---------------- */

/** 把一段 reasoning 增量并入分段数组：接续上一段普通思考；上一段是工具调用时另起一段。
 *  后端在工具轮后的首片思考前会补分隔线（供纯文本 CoT 列），分段展示时去掉前导分隔线。
 *  round=该段所属轮次（0 基） */
function appendReasoning(trace: TraceSegment[], delta: string, round: number): TraceSegment[] {
  const last = trace[trace.length - 1]
  if (last && last.type === 'reasoning' && last.round === round) {
    const next = trace.slice()
    next[next.length - 1] = { ...last, text: (last.text ?? '') + delta }
    return next
  }
  return [
    ...trace,
    { type: 'reasoning', text: delta.replace(/^\n*---\n+/, ''), name: null, args: null, round },
  ]
}

/** 原始流和续传流共用同一套增量、轮次和撤回规则。 */
function createStreamBuffer(sessionId: string) {
  let content = ''
  let reasoning = ''
  let round = 0
  let frame = 0

  const discard = () => {
    if (frame) cancelAnimationFrame(frame)
    frame = 0
    content = ''
    reasoning = ''
  }
  const flush = (resetContent = false) => {
    frame = 0
    if (!content && !reasoning && !resetContent) return
    patch(sessionId, cur => ({
      ...cur,
      content: resetContent ? '' : cur.content + content,
      reasoning: cur.reasoning + reasoning,
      trace: reasoning ? appendReasoning(cur.trace, reasoning, round) : cur.trace,
    }))
    discard()
  }
  const flushNow = (resetContent = false) => {
    if (frame) cancelAnimationFrame(frame)
    flush(resetContent)
  }
  const schedule = () => {
    if (!frame) frame = requestAnimationFrame(() => flush())
  }

  return {
    discard,
    flushNow,
    restoreRound: (current: number) => { round = current },
    handlers: {
      onRound(next: number) {
        flushNow(next > 0)
        round = next
      },
      onDelta(delta: { reasoning?: string; content?: string }) {
        reasoning += delta.reasoning ?? ''
        content += delta.content ?? ''
        schedule()
      },
      onTool(tool: { name: string; args: string; round?: number }) {
        flushNow()
        patch(sessionId, cur => ({
          ...cur,
          trace: [...cur.trace, { type: 'tool', text: null, name: tool.name, args: tool.args, round: tool.round ?? round }],
        }))
      },
      onActivity({ callId, name, target, running }: ChatToolActivityEvent) {
        patch(sessionId, cur => {
          if (!cur.running) return cur
          const exists = cur.activeTools.some(tool => tool.callId === callId)
          if (exists === running) return cur
          const activeTools = running
            ? [...cur.activeTools, { callId, name, target }]
            : cur.activeTools.filter(tool => tool.callId !== callId)
          return { ...cur, activeTools }
        })
      },
      onSteer(message: SteerMessage) {
        flushNow()
        patch(sessionId, cur => ({ ...appendSteer(cur, message), content: '' }))
      },
      onInterim(message: InterimReply) {
        patch(sessionId, cur => cur.interims.some(reply => reply.messageId === message.messageId)
          ? cur : { ...cur, interims: [...cur.interims, message] })
      },
      onReset(reset: ChatStreamReset) {
        discard()
        patch(sessionId, cur => ({ ...cur, reasoning: reset.reasoning, trace: reset.trace, content: '' }))
      },
    },
  }
}

export type StartArgs = {
  sessionId: string
  text: string
  modelId?: string
  promptId?: string
  reasoningEffort?: ReasoningEffort
  /** 生成落定（成功 / 用户停止 / 失败）后回调：页面据此刷新会话列表（标题、排序） */
  onSettled?: (sessionId: string) => void
}

/**
 * 开始一次生成：状态全程写进本仓，故调用方（会话页）卸载也不影响生成与后续续看。
 * 同一会话已有生成在跑时直接忽略（不并发）。
 */
export function startChat(o: StartArgs): void {
  if (starts.has(o.sessionId)) return
  starts.add(o.sessionId)
  live.set(o.sessionId, {
    sessionId: o.sessionId,
    userText: o.text,
    userMessageId: null,
    steers: [],
    interims: [],
    content: '',
    reasoning: '',
    trace: [],
    activeTools: [],
    running: true,
    error: null,
  })
  emit()

  const buffer = createStreamBuffer(o.sessionId)
  void (async () => {
    try {
      const reply = await chatStream(
        o.text,
        o.sessionId,
        o.modelId,
        o.promptId,
        {
          ...buffer.handlers,
          /* user 消息已落库：回填其行 id，供「回退到这条提问之前」定位删除区间 */
          onMeta: (meta) => patch(o.sessionId, (cur) => ({ ...cur, userMessageId: meta.messageId })),
          onReasoning: reasoning => buffer.handlers.onDelta({ reasoning }),
          onContent: content => buffer.handlers.onDelta({ content }),
        },
        undefined,
        o.reasoningEffort,
      )
      /* 用落库正本覆盖流式累积（与历史回放一致），并结束流式态 */
      buffer.flushNow()
      patch(o.sessionId, (cur) => ({ ...cur, running: false, activeTools: [], content: reply.message }))
      emitSettled(o.sessionId)
      o.onSettled?.(o.sessionId)
    } catch (e) {
      /* 丢弃在途增量，避免收尾后仍有残留帧触发渲染 */
      buffer.discard()
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      const msg = e instanceof Error ? e.message : String(e)
      /* 失败：保留提问与已产出的半截输出（供回退重发），只落下 running 并记下原因。 */
      patch(o.sessionId, (cur) => ({ ...cur, running: false, activeTools: [], error: msg }))
      o.onSettled?.(o.sessionId)
    } finally {
      starts.delete(o.sessionId)
    }
  })()
}

/** Stop the turn and keep listening until its final transcript is available. */
export function stopChat(sessionId: string): void {
  const current = live.get(sessionId)
  if (!current?.running || current.stopping || current.userMessageId == null) return
  const messageId = current.userMessageId
  patch(sessionId, cur => ({ ...cur, stopping: true }))
  const restore = () => patch(sessionId, cur => cur.userMessageId === messageId
    ? { ...cur, stopping: false } : cur)
  void apiStopChat(sessionId, messageId).then(({ stopped }) => {
    if (!stopped) restore()
  }, restore)
}

/* ---------------- 断点续传：重挂到后端仍在跑的生成 ---------------- */

/** 已挂上续传流的会话（键为会话 id，值为该流的 AbortController） */
const attaches = new Map<string, AbortController>()

/** 后端快照 → 本地运行时条目（字段一一对应，只是形状不同） */
function fromSnapshot(snap: LiveRun): LiveChat {
  return {
    sessionId: snap.sessionId,
    userText: snap.userText ?? '',
    userMessageId: snap.userMessageId,
    steers: snap.steers ?? [],
    interims: snap.interims ?? [],
    content: snap.content ?? '',
    reasoning: snap.reasoning ?? '',
    trace: snap.trace ?? [],
    activeTools: snap.running ? (snap.activeTools ?? []) : [],
    running: snap.running,
    error: snap.error,
  }
}

/**
 * 重挂到后端仍在进行的生成：页面刷新（或换标签页）后进入该会话时调用，先查一次在途快照，
 * 确实还在生成才订阅续传流把进度接回来。本标签页自己发起的生成不走这里（由 {@link startChat} 直接驱动）。
 *
 * <p>注意：没有在途生成时后端返回的是一份 running=false 的<b>空快照</b>（没有提问也没有正文），
 * 绝不能拿它覆盖本地仓——那会凭空渲染出一个空白气泡。此时本地仓里若还留着断线前的进度，
 * 就把它落定（见 {@link finishLocally}），交由会话页并回消息列表。
 */
export async function attachLive(sessionId: string): Promise<void> {
  if (attaches.has(sessionId) || live.has(sessionId)) return
  const ac = new AbortController()
  attaches.set(sessionId, ac)
  try {
    /* 循环：查快照 → 订阅续传流。流正常收尾（done/error）时快照已 running=false，循环随之退出；
       若只是被服务端掐断（SSE 有最长保持时长），下一圈重新取快照补齐再接着看
       ——快照即全量，重新取一次就不会漏掉断线期间产生的增量 */
    for (;;) {
      if (ac.signal.aborted) return
      const snap = await sessionLive(sessionId).catch(() => null)
      if (!snap) return
      if (!snap.running) {
        finishLocally(sessionId)
        return
      }
      live.set(sessionId, fromSnapshot(snap))
      emit()
      const terminated = await resumeStream(sessionId, ac.signal)
      if (terminated || ac.signal.aborted) return
      await new Promise((r) => setTimeout(r, 1000))
    }
  } finally {
    attaches.delete(sessionId)
  }
}

/** 后端这次生成已收尾（多半是断线期间跑完的）：把手上这份进度落定，交给会话页并回消息列表。
 *  本地仓里没有该会话的条目时什么都不做——空快照不是「进度」，不能凭空造出气泡。
 *  这里不发落定通知：收尾结果未知（可能成功也可能失败），提醒交给轮询那条路（按「不再生成」判定） */
function finishLocally(sessionId: string): void {
  patch(sessionId, (cur) => (cur.running ? { ...cur, running: false, activeTools: [] } : cur))
}

/** 订阅一次续传流；返回 true=收到收尾事件（done/error，进度已落定），false=流被掐断，需要重挂 */
async function resumeStream(sessionId: string, signal: AbortSignal): Promise<boolean> {
  const buffer = createStreamBuffer(sessionId)
  let terminated = false
  try {
    await sessionLiveStream(
      sessionId,
      {
        ...buffer.handlers,
        /* 快照是全量：直接覆盖本地进度（重新挂上时也靠它补齐断线期间的部分）。
           订阅时这次生成刚好收尾的话，后端只会推一份 running=false 的空快照——那不是进度，别覆盖 */
        onSnapshot: (run) => {
          if (!run.running) {
            finishLocally(sessionId)
            return
          }
          buffer.restoreRound(run.round ?? run.trace?.at(-1)?.round ?? 0)
          live.set(sessionId, fromSnapshot(run))
          emit()
        },
        /* 收尾：后端的快照已是最终态（running=false，正文为落库正本），直接覆盖即可。
           正常答完才通知「已应答」；失败另有提示，不当成应答去弹气泡 */
        onTerminal: (run, failed) => {
          terminated = true
          buffer.discard()
          live.set(sessionId, fromSnapshot(run))
          emit()
          if (!failed) emitSettled(sessionId)
        },
      },
      signal,
    )
  } catch {
    /* 断线（超时 / 网络）：把已拿到的进度留在仓里，交给外层重挂；真断了也还有会话历史兜底 */
  } finally {
    buffer.discard()
  }
  return terminated
}
