import { useSyncExternalStore } from 'react'
import { maChatStream, stopMaChat, type MaTopic, type TraceSegment } from '../api/client'

/**
 * 圆桌发言的「运行时」：把一次「人说一句 → 成员轮流应答 → 收束」的全过程从圆桌页组件搬进
 * 应用级模块仓（与首页对话的 {@code components/chatLive} 同一套写法）。
 *
 * <p><b>为什么</b>：发言期间用户切到别的会话、或离开圆桌页（切 tab、切路由）时，页面组件会被卸载，
 * 而后端那一轮还在继续跑。运行时状态若只活在组件里，就会出现两件事：别的会话的界面被按在
 * 「发言中」（发送键变成停止、上一位的气泡糊在别人的记录上），以及回来之后既看不到谁在说、
 * 也点不到「停止」。本仓解决的就是这个——离开只是「不再渲染」，发言照跑；重新进入这张桌子时
 * 把仓里的快照贴回消息流，进度与停止都接得上。
 *
 * <p><b>已落库的以库为准</b>：人的话、状态机动作、说完的发言在后端一落库就推事件过来
 * （见 RoundtableTopicService#advance：{@code speak} 先落库、再推 {@code speakerEnd}），
 * 本仓不另存一份，只把 {@link RtRun#revision} 自增，页面据此重取消息。于是「实时看到的」与
 * 「刷新后读到的」永远同一个形状，中途回来也不会重复渲染。仓里只有三样东西是库里还没有的：
 * 正在说话的那一位（{@link RtLive}）、最新话题快照、以及本次提问的原文。
 */

/** 一轮流式发言的进行态：正在说话的那一位，整轮攒在这里 */
export type RtLive = {
  peerId: string
  peerName: string
  /** 整轮的思考过程（思考 / 调用工具），与落库后的 trace 同形 */
  trace: TraceSegment[]
  /** 说出口的段落（一段一个气泡）；最后一段是还在长的那个，一轮没开口时为空 */
  segments: string[]
  /** 这条发言的开始时刻：流式途中没有落库时间，气泡上的时间用它 */
  at: string
}

/** 一张桌子当前（或刚结束）的发言快照 */
export type RtRun = {
  /** 后端确认的会话 id；草稿态（后端还没建会话）为空串 */
  sessionId: string
  /** 是不是还在跑（发一句到整轮收束为一次） */
  running: boolean
  /** 正在说话的那一位（没有说话人时为 null） */
  live: RtLive | null
  /** 后端推来的最新话题快照：状态条据此边聊边刷新，不必等整轮跑完再拉一次 */
  topic: MaTopic | null
  /** 本次提问的原文与时刻：落库回读到位之前，先由一个气泡顶上 */
  userText: string
  userAt: string
  /** 已有新内容落库的次数：挂载中的页面据此重取消息（值本身无意义） */
  revision: number
  /** 失败原因（null = 没失败）；页面提醒过之后调 {@link clearRunError} 清掉 */
  error: string | null
}

/* ---------------- 仓 + 订阅 ---------------- */

/** 草稿态（还没有会话）那一轮挂在它上面：后端建好会话后整条搬到真实会话 id 上 */
export const DRAFT_KEY = ''

/** 会话 key：没有会话时归到草稿位 */
export function runKey(sessionId: string | null | undefined): string {
  return sessionId ? sessionId : DRAFT_KEY
}

const runs = new Map<string, RtRun>()
const listeners = new Set<() => void>()
/** key → 该轮流的 AbortController（只用于「中途放弃读取」，真正打断要走后端 stop） */
const aborters = new Map<string, AbortController>()
/** 一次发言收尾（跑完 / 失败）的订阅者：页面据此刷新会话列表（标题、排序） */
const settledListeners = new Set<(sessionId: string) => void>()

function emit(): void {
  listeners.forEach((l) => l())
}

function subscribe(l: () => void): () => void {
  listeners.add(l)
  return () => {
    listeners.delete(l)
  }
}

/** 读某张桌子当前的发言快照（没有则为 null） */
export function getRun(sessionId: string | null | undefined): RtRun | null {
  return runs.get(runKey(sessionId)) ?? null
}

/** 订阅某张桌子的发言快照：跨组件挂载存活，离开再回来即拿回进度（与 chatLive 同一套） */
export function useRoundtableRun(sessionId: string | null | undefined): RtRun | null {
  return useSyncExternalStore(subscribe, () => getRun(sessionId))
}

/* ---------------- 正在跑的有哪几张桌子（左侧列表打点、回来时优先落座用） ---------------- */

/** 上一次的清单与其签名：清单没变就交回同一个数组引用，订阅方不因别的会话的增量而重渲染 */
let runningCache: string[] = []
let runningSig = ''

/** 正在发言的会话 id 清单（快照引用只在清单变化时更换） */
export function getRunningSessionIds(): string[] {
  const sig = [...runs.values()]
    .filter((r) => r.running && r.sessionId)
    .map((r) => r.sessionId)
    .join('\u0000')
  if (sig !== runningSig) {
    runningSig = sig
    runningCache = sig ? sig.split('\u0000') : []
  }
  return runningCache
}

/** 订阅「正在发言的会话清单」 */
export function useRunningSessionIds(): string[] {
  return useSyncExternalStore(subscribe, getRunningSessionIds)
}

/* ---------------- 生成落定订阅 ---------------- */

/** 订阅「某张桌子刚说完一轮」：页面据此刷新会话列表（标题与排序都在这时定下来） */
export function onRoundtableSettled(l: (sessionId: string) => void): () => void {
  settledListeners.add(l)
  return () => {
    settledListeners.delete(l)
  }
}

function emitSettled(sessionId: string): void {
  settledListeners.forEach((l) => l(sessionId))
}

/** 读走并清掉失败标记：页面提醒过一次就不必每次挂载再提醒一遍 */
export function clearRunError(sessionId: string | null | undefined): void {
  const key = runKey(sessionId)
  const cur = runs.get(key)
  if (!cur || cur.error === null) return
  runs.set(key, { ...cur, error: null })
  emit()
}

/* ---------------- 攒段：与落库后的形状对齐 ---------------- */

/** 把一段正文增量并进分段数组：没有段就开一段，否则续在最后一段上 */
function appendDelta(segments: string[], delta: string): string[] {
  const next = segments.slice()
  if (next.length === 0) next.push(delta)
  else next[next.length - 1] += delta
  return next
}

/**
 * 说出口一段话（一次 speak）：最后一段还空着（本轮先出思考、话才到）就装进它，
 * 否则另起一格——思考与它引出的话因此同在一格，与落库后的分段一致。
 */
function appendSpeech(segments: string[], text: string): string[] {
  const next = segments.slice()
  if (next.length > 0 && !next[next.length - 1].trim()) next[next.length - 1] = text
  else next.push(text)
  return next
}

/** 把一段 reasoning 增量并进分段数组：接续上一段普通思考；上一段是工具调用时另起一段 */
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

/* ---------------- 发言引擎 ---------------- */

export type RtStartArgs = {
  /** 往哪张桌子说；null = 还没有会话（后端会用最近一个，没有就新建） */
  sessionId: string | null
  /** 人的这一句话 */
  input: string
  /** 兜底模型（成员没指定时用） */
  modelId?: string
  /** 选人策略标识 */
  speakerStrategy?: string
  /** 草稿态下后端建好会话后回传 id：页面据此把选中的会话切过去 */
  onSession?: (sessionId: string) => void
}

/**
 * 让圆桌说一句：状态全程写进本仓，故调用方（圆桌页）卸载也不影响这一轮跑完与后续续看。
 *
 * <p>同一张桌子已有发言在跑时<b>不再拒绝</b>——那是「用户插话」：放弃旧流的读取（后端收到新消息
 * 会打断正在说话的那位），再投递新的一轮。随时加入讨论、补充信息是这套设计的核心目标。
 */
export function startRoundtable(o: RtStartArgs): void {
  const startKey = runKey(o.sessionId)
  aborters.get(startKey)?.abort()
  const ac = new AbortController()
  aborters.set(startKey, ac)
  /* 上一轮留下的话题快照先留着：状态条不必在开局瞬间闪一下空白 */
  const prev = runs.get(startKey)
  runs.set(startKey, {
    sessionId: startKey,
    running: true,
    live: null,
    topic: prev?.topic ?? null,
    userText: o.input,
    userAt: new Date().toISOString(),
    revision: prev?.revision ?? 0,
    error: null,
  })
  emit()

  /** 当前 key：草稿那一轮在后端建好会话后，整条搬到真实的会话 id 上 */
  let key = startKey
  const put = (fn: (r: RtRun) => RtRun) => {
    const cur = runs.get(key)
    if (!cur) return
    runs.set(key, fn(cur))
    emit()
  }
  const adopt = (id: string) => {
    if (key === id) return
    const cur = runs.get(key)
    aborters.delete(key)
    runs.delete(key)
    key = id
    aborters.set(key, ac)
    if (cur) runs.set(key, { ...cur, sessionId: id })
    emit()
    o.onSession?.(id)
  }

  void (async () => {
    try {
      await maChatStream(
        o.input,
        o.sessionId ?? undefined,
        o.modelId,
        o.speakerStrategy,
        {
          onTopic: (t) => adopt(t.sessionId),
          onSpeaker: (s) =>
            put((r) => ({
              ...r,
              live: {
                peerId: s.peerId,
                peerName: s.peerName,
                trace: [],
                segments: [],
                at: new Date().toISOString(),
              },
            })),
          onState: (t) => put((r) => ({ ...r, topic: t })),
          onReasoning: (d) =>
            put((r) => (r.live ? { ...r, live: { ...r.live, trace: appendReasoning(r.live.trace, d) } } : r)),
          onContent: (d) =>
            put((r) => (r.live ? { ...r, live: { ...r.live, segments: appendDelta(r.live.segments, d) } } : r)),
          onSpeech: (text) =>
            put((r) => (r.live ? { ...r, live: { ...r.live, segments: appendSpeech(r.live.segments, text) } } : r)),
          onTool: (t) =>
            put((r) =>
              r.live
                ? {
                    ...r,
                    live: {
                      ...r.live,
                      trace: [
                        ...r.live.trace,
                        { type: 'tool', text: null, name: t.name, args: t.args, round: null },
                      ],
                    },
                  }
                : r,
            ),
          /* 这一位说完了：它的发言此刻已经落库，清掉直播气泡并请页面重取消息 */
          onSpeakerEnd: () => put((r) => ({ ...r, live: null, revision: r.revision + 1 })),
          /* 状态机动作行（开话题、入场、转沉默……）已落库：同样重取一次，让它按真实次序出现 */
          onAction: () => put((r) => ({ ...r, revision: r.revision + 1 })),
        },
        ac.signal,
      )
      put((r) => ({ ...r, running: false, live: null, revision: r.revision + 1 }))
    } catch (e) {
      const aborted = e instanceof DOMException && e.name === 'AbortError'
      put((r) => ({
        ...r,
        running: false,
        live: null,
        revision: r.revision + 1,
        error: aborted ? null : e instanceof Error ? e.message : String(e),
      }))
    } finally {
      aborters.delete(key)
      emitSettled(key)
    }
  })()
}

/**
 * 人打断当前发言（页面的「停止」）：请后端让正在说话的那位打住，已说出的部分照常落库。
 *
 * <p>只发这一条请求、不掐断读取——那位被打住时这一轮会正常收尾（走 {@code done}），
 * 页面于是能拿到完整的收尾快照，不必自己去猜「停到哪了」。
 */
export async function stopRoundtable(sessionId: string): Promise<void> {
  await stopMaChat(sessionId)
}
