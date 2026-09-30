import { useSyncExternalStore } from 'react'
import {
  chatStream,
  loginUrlOf,
  sessionLive,
  sessionLiveStream,
  stopChat as apiStopChat,
  type LiveRun,
  type SubagentBrief,
  type TraceSegment,
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
  /** 正在生成的回答正文 / 思维链 / 结构化分段 */
  content: string
  reasoning: string
  trace: TraceSegment[]
  /** 是否仍在生成 */
  running: boolean
  /** 生成失败的原因（null=没失败）：失败时半截输出要贴回会话，别让用户白等一场 */
  error: string | null
}

/* ---------------- 仓 + 订阅 ---------------- */

const live = new Map<string, LiveChat>()
/** 派过子代理、还在等回报的会话：生成结束后仍保留，会话页据此继续轮询把回报刷出来 */
const awaiting = new Set<string>()
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

/** 会话 id → 该会话进行中生成的 AbortController（「停止」用） */
const aborters = new Map<string, AbortController>()

/** 就地把某会话的快照换成 updater 的返回值（快照已被清掉则忽略） */
function patch(sessionId: string, updater: (cur: LiveChat) => LiveChat): void {
  const cur = live.get(sessionId)
  if (!cur) return
  live.set(sessionId, updater(cur))
  emit()
}

/** 读某会话当前的生成快照（没有则 null） */
export function getLive(sessionId: string | null): LiveChat | null {
  return sessionId === null ? null : (live.get(sessionId) ?? null)
}

/** 丢弃某会话的快照：生成已落定、且要改以会话历史（数据库）为准时调用 */
export function clearLive(sessionId: string): void {
  if (live.delete(sessionId)) emit()
}

/** 该会话派出去的子代理是否还在等回报 */
export function isAwaitingReport(sessionId: string | null): boolean {
  return sessionId !== null && awaiting.has(sessionId)
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

/** 订阅「是否在等子代理回报」（页面据此轮询会话列表） */
export function useAwaitingReport(sessionId: string | null): boolean {
  return useSyncExternalStore(subscribe, () => isAwaitingReport(sessionId))
}

/* ---------------- 生成引擎 ---------------- */

/** 把一段 reasoning 增量并入分段数组：接续上一段普通思考；上一段是工具调用时另起一段。
 *  后端在工具轮后的首片思考前会补分隔线（供纯文本 CoT 列），分段展示时去掉前导分隔线。
 *  round=该段所属轮次（0 基） */
function appendReasoning(trace: TraceSegment[], delta: string, round: number): TraceSegment[] {
  const last = trace[trace.length - 1]
  if (last && last.type === 'reasoning') {
    const next = trace.slice()
    next[next.length - 1] = { ...last, text: (last.text ?? '') + delta }
    return next
  }
  return [
    ...trace,
    { type: 'reasoning', text: delta.replace(/^\n*---\n+/, ''), name: null, args: null, round },
  ]
}

export type StartArgs = {
  sessionId: string
  text: string
  modelId?: string
  promptId?: string
  /** 生成落定（成功 / 用户停止 / 失败）后回调：页面据此刷新会话列表（标题、排序） */
  onSettled?: (sessionId: string) => void
}

/**
 * 开始一次生成：状态全程写进本仓，故调用方（会话页）卸载也不影响生成与后续续看。
 * 同一会话已有生成在跑时直接忽略（不并发）。
 */
export function startChat(o: StartArgs): void {
  if (aborters.has(o.sessionId)) return
  const ac = new AbortController()
  aborters.set(o.sessionId, ac)
  live.set(o.sessionId, {
    sessionId: o.sessionId,
    userText: o.text,
    userMessageId: null,
    content: '',
    reasoning: '',
    trace: [],
    running: true,
    error: null,
  })
  emit()

  /* 流式增量攒批：token 回调频率远高于帧率，一帧（rAF）提交一次，避免逐 token 重渲染整份消息列表 */
  const buf = { content: '', reasoning: '', reset: false }
  /* 当前轮次（0 基，与后端 round 对齐）：onRound 到达时更新，之后产出的思考/工具分段都归这一轮 */
  let curRound = 0
  let raf = 0

  const flush = () => {
    raf = 0
    if (!buf.content && !buf.reasoning && !buf.reset) return
    const delta = { content: buf.content, reasoning: buf.reasoning, reset: buf.reset }
    buf.content = ''
    buf.reasoning = ''
    buf.reset = false
    patch(o.sessionId, (cur) => {
      /* 新一轮正文从头开始（最终答复以末轮为准）；思维链跨轮累加保留 */
      const base = delta.reset ? { ...cur, content: '' } : cur
      let reasoning = base.reasoning
      let trace = base.trace
      if (delta.reasoning) {
        reasoning += delta.reasoning
        trace = appendReasoning(trace, delta.reasoning, curRound)
      }
      return { ...base, reasoning, trace, content: base.content + delta.content }
    })
  }
  /** 立刻提交在途增量（低频道事件与收尾前调用，保证先后顺序） */
  const flushNow = () => {
    if (raf) {
      cancelAnimationFrame(raf)
      raf = 0
    }
    flush()
  }
  const schedule = () => {
    if (!raf) raf = requestAnimationFrame(flush)
  }

  void (async () => {
    try {
      const reply = await chatStream(
        o.text,
        o.sessionId,
        o.modelId,
        o.promptId,
        {
          /* user 消息已落库：回填其行 id，供「回退到这条提问之前」定位删除区间 */
          onMeta: (meta) => patch(o.sessionId, (cur) => ({ ...cur, userMessageId: meta.messageId })),
          /* 工具轮之后的新一轮：正文从头开始；思维链跨轮累加保留，分段展示按 trace 另起一段 */
          onRound: (round) => {
            if (round > 0) {
              flushNow()
              buf.reset = true
              schedule()
            }
            curRound = round
          },
          onReasoning: (delta) => {
            buf.reasoning += delta
            schedule()
          },
          onContent: (delta) => {
            buf.content += delta
            schedule()
          },
          /* 模型发起一次工具调用：先提交在途增量，保证该分段排在已产生的思考之后 */
          onTool: (tool) => {
            flushNow()
            patch(o.sessionId, (cur) => ({
              ...cur,
              trace: [
                ...cur.trace,
                { type: 'tool', text: null, name: tool.name, args: tool.args, round: curRound },
              ],
            }))
            /* 对话侧派活是非阻塞的：结论晚些作为一条新消息投回本会话，
               记下是哪个会话在等回报，好让会话页轮询把它刷出来 */
            if (tool.name === 'delegate_subagent') {
              awaiting.add(o.sessionId)
              emit()
            }
          },
        },
        ac.signal,
      )
      /* 用落库正本覆盖流式累积（与历史回放一致），并结束流式态 */
      flushNow()
      patch(o.sessionId, (cur) => ({ ...cur, running: false, content: reply.message }))
      emitSettled(o.sessionId)
      o.onSettled?.(o.sessionId)
    } catch (e) {
      /* 丢弃在途增量，避免收尾后仍有残留帧触发渲染 */
      if (raf) {
        cancelAnimationFrame(raf)
        raf = 0
      }
      if (e instanceof DOMException && e.name === 'AbortError') {
        /* 用户点「停止」：不算失败。后端已把部分回答落库，只落下 running 即可 */
        patch(o.sessionId, (cur) => ({ ...cur, running: false }))
      } else {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        const msg = e instanceof Error ? e.message : String(e)
        /* 失败：保留提问与已产出的半截输出（供回退重发），只落下 running 并记下原因；
           原因由会话页统一提示（见 Home 的 error 效应），本地发起与刷新后重挂两条路径共用 */
        patch(o.sessionId, (cur) => ({ ...cur, running: false, error: msg }))
      }
      o.onSettled?.(o.sessionId)
    } finally {
      aborters.delete(o.sessionId)
    }
  })()
}

/** 停止该会话进行中的生成：先通知后端该响应不再需要（已产出部分照常落库），再中断本地流读取。
 *  重挂过来的续传流不中断——要收后端推来的收尾快照才能把状态落定 */
export function stopChat(sessionId: string): void {
  void apiStopChat(sessionId).catch(() => {})
  aborters.get(sessionId)?.abort()
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
    content: snap.content ?? '',
    reasoning: snap.reasoning ?? '',
    trace: snap.trace ?? [],
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
  patch(sessionId, (cur) => (cur.running ? { ...cur, running: false } : cur))
}

/** 订阅一次续传流；返回 true=收到收尾事件（done/error，进度已落定），false=流被掐断，需要重挂 */
async function resumeStream(sessionId: string, signal: AbortSignal): Promise<boolean> {
  /* 与本地生成同一套攒批规则：token 回调频率远高于帧率，一帧（rAF）只提交一次 */
  const buf = { content: '', reasoning: '', reset: false }
  let curRound = 0
  let raf = 0
  const flush = () => {
    raf = 0
    if (!buf.content && !buf.reasoning && !buf.reset) return
    const delta = { content: buf.content, reasoning: buf.reasoning, reset: buf.reset }
    buf.content = ''
    buf.reasoning = ''
    buf.reset = false
    patch(sessionId, (cur) => {
      const base = delta.reset ? { ...cur, content: '' } : cur
      let reasoning = base.reasoning
      let trace = base.trace
      if (delta.reasoning) {
        reasoning += delta.reasoning
        trace = appendReasoning(trace, delta.reasoning, curRound)
      }
      return { ...base, reasoning, trace, content: base.content + delta.content }
    })
  }
  const flushNow = () => {
    if (raf) {
      cancelAnimationFrame(raf)
      raf = 0
    }
    flush()
  }
  const schedule = () => {
    if (!raf) raf = requestAnimationFrame(flush)
  }

  let terminated = false
  try {
    await sessionLiveStream(
      sessionId,
      {
        /* 快照是全量：直接覆盖本地进度（重新挂上时也靠它补齐断线期间的部分）。
           订阅时这次生成刚好收尾的话，后端只会推一份 running=false 的空快照——那不是进度，别覆盖 */
        onSnapshot: (run) => {
          if (!run.running) {
            finishLocally(sessionId)
            return
          }
          live.set(sessionId, fromSnapshot(run))
          emit()
        },
        onRound: (round) => {
          if (round > 0) {
            flushNow()
            buf.reset = true
            schedule()
          }
          curRound = round
        },
        onDelta: (d) => {
          if (d.reasoning) buf.reasoning += d.reasoning
          if (d.content) buf.content += d.content
          schedule()
        },
        onTool: (tool) => {
          flushNow()
          patch(sessionId, (cur) => ({
            ...cur,
            trace: [
              ...cur.trace,
              { type: 'tool', text: null, name: tool.name, args: tool.args, round: tool.round },
            ],
          }))
        },
        /* 收尾：后端的快照已是最终态（running=false，正文为落库正本），直接覆盖即可。
           正常答完才通知「已应答」；失败另有提示，不当成应答去弹气泡 */
        onTerminal: (run, failed) => {
          terminated = true
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
    if (raf) {
      cancelAnimationFrame(raf)
      raf = 0
    }
  }
  return terminated
}
