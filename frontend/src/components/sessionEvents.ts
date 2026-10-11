import { ApiError, sessionEventsStream, type SessionEvent } from '../api/client'

/**
 * 会话事件订阅仓（STEP 4）：全站共用一条 SSE 连接，把「某会话有新消息 / 答完了」即时送进来，
 * 取代原先各部件的定时轮询（通知器 5s、会话页等待子代理回报 4s）。
 *
 * <p>为什么做成单例：一条连接就够所有组件用，且只要还有人订阅就保持连接。断线（切标签页、服务端
 * 5 分钟掐断、网络抖动）后自动重连，并带上最后收到的事件 id——后端据此把断线期间漏掉的事件补推一遍，
 * 于是「事件驱动」既不慢也不丢。
 */

type Handler = (e: SessionEvent) => void

/** 重连间隔：连接结束（服务端超时掐断 / 网络抖动）后等这么久再挂 */
const RETRY_MS = 3000

const handlers = new Set<Handler>()
/** 当前连接（null=未连接）；用 generation 防止旧循环在重建连接后误清状态 */
let controller: AbortController | null = null
let generation = 0
/** 已收到的最后一条事件 id：重连时作为 Last-Event-ID 补推 */
let lastEventId: number | null = null

/**
 * 订阅会话事件：返回退订函数。第一个订阅者触发连接，最后一个退订时关闭连接。
 *
 * <p>各部件按需订阅，收到事件后自行决定怎么反应（弹通知 / 刷新消息列表）。
 */
export function subscribeSessionEvents(handler: Handler): () => void {
  handlers.add(handler)
  ensureConnected()
  return () => {
    handlers.delete(handler)
    if (handlers.size === 0) shutdown()
  }
}

function ensureConnected(): void {
  if (controller) return
  const my = ++generation
  const ac = new AbortController()
  controller = ac
  void loop(ac.signal, my)
}

function shutdown(): void {
  generation++ // 让在跑的循环失效，不再重连
  controller?.abort()
  controller = null
}

async function loop(signal: AbortSignal, my: number): Promise<void> {
  while (!signal.aborted && my === generation && handlers.size > 0) {
    try {
      await sessionEventsStream({ onEvent: emit }, signal, lastEventId)
    } catch (e) {
      /* 401 = 未登录 / 登录过期：交给应用既有的登录跳转处理，这里不再空转重试 */
      if (e instanceof ApiError && e.status === 401) {
        break
      }
      /* 其余（网络抖动 / 后端未起 / 流被掐断）：稍后重连 */
    }
    if (signal.aborted || my !== generation) return
    await sleep(RETRY_MS, signal)
  }
  if (my === generation) {
    controller = null
  }
}

function emit(e: SessionEvent): void {
  if (e.id > 0) {
    lastEventId = e.id
  }
  handlers.forEach((h) => h(e))
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(resolve, ms)
    signal.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer)
        resolve()
      },
      { once: true },
    )
  })
}
