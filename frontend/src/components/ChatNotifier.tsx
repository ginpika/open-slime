import { useCallback, useEffect, useRef, useState } from 'react'
import { listSessions, type SessionItem } from '../api/client'
import { getActiveChatSession, onChatSettled } from './chatLive'
import { subscribeSessionEvents } from './sessionEvents'

/**
 * 页级消息通知：无论用户停在哪一页，只要大模型在某个会话里答完了、或某会话被投递了新消息，
 * 就在左上角弹一只气泡提醒；点气泡直接切到会话页并打开那个会话。
 *
 * <p>两个来源：
 * <ol>
 *   <li><b>本标签页的生成收尾</b>（本地发起、或刷新后重挂的续传流）——由运行时仓当场推来：
 *       既及时（短回答也不会被轮询间隔漏掉），又不会误报（「刚答完」是亲眼看到的，不是猜的）；</li>
 *   <li><b>会话事件推送</b>（STEP 4，取代原来的 5 秒轮询）——后端在「子代理结论投回 / 某会话答完」
 *       时经 SSE 即时推来，跨标签页也收得到，且断线重连会补推断线期间漏掉的。</li>
 * </ol>
 *
 * <p>正在看的那条会话答完了不提醒（输出就在眼前）；被投递的新消息照旧提醒。同一会话挂着气泡时不重复弹。
 */

/* ---------- 跨组件桥：气泡 ↔ 会话页（Home） ---------- */

type Opener = (sessionId: string) => void
/** 会话页注册的「打开某会话」入口（它没挂载时为 null） */
let opener: Opener | null = null
/** 会话页尚未挂载时先攒下要打开的会话，等它挂载后立刻兑现 */
let pending: string | null = null

/** 会话页挂载时注册入口、卸载时注销（注销时清空，避免指向已卸载组件的回调） */
export function registerChatOpener(fn: Opener | null): void {
  opener = fn
  if (fn && pending !== null) {
    const id = pending
    pending = null
    fn(id)
  }
}

/** 气泡被点击：会话页在就交给它打开；不在（用户停在别的页）就先攒着，
 *  路由切回首页后会话页挂载时自会来取 */
export function requestOpenSession(id: string): void {
  if (opener) opener(id)
  else pending = id
}

/* ---------- 通知气泡 ---------- */

/** 提醒缘由：answered=大模型答完了 | delivered=会话被投递了新消息（潜意识子代理） */
type NoticeKind = 'answered' | 'delivered'

/** 一条待展示的通知 */
type Notice = {
  /** 会话 id（去重键，也是点击跳转的目标） */
  id: string
  /** 会话标题（未命名时由调用方兜底） */
  title: string
  kind: NoticeKind
}

/** 同屏最多挂几条气泡，超出丢弃最旧的（避免连环投递时糊满左上角） */
const MAX_NOTICES = 3
/** 单条气泡停留时长（毫秒）：到点自动收起，点击 / 关闭可提前 */
const NOTICE_TTL_MS = 12000

const KIND_LABEL: Record<NoticeKind, string> = {
  answered: '大模型已应答',
  delivered: '会话有新消息',
}

/**
 * 单条气泡：淡入左滑入场，到点或点关闭按钮自行退场。
 * onClose 收进 ref（父级内联箭头函数不会重置计时），与 Toast 同一套写法。
 */
function NoticeCard({
  notice,
  onOpen,
  onClose,
}: {
  notice: Notice
  onOpen: (n: Notice) => void
  onClose: (id: string) => void
}) {
  const [shown, setShown] = useState(false)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    const raf = requestAnimationFrame(() => setShown(true))
    const timer = setTimeout(() => closeRef.current(notice.id), NOTICE_TTL_MS)
    return () => {
      cancelAnimationFrame(raf)
      clearTimeout(timer)
    }
  }, [notice.id])

  return (
    <div
      className={`pointer-events-auto relative transition-all duration-300 ${
        shown ? 'translate-x-0 opacity-100' : '-translate-x-3 opacity-0'
      }`}
    >
      {/* 整块可点：前往该会话（关闭钮单独叠在右上角，故不用嵌套 button） */}
      <button
        type="button"
        onClick={() => onOpen(notice)}
        title="点击前往会话"
        className="flex w-72 cursor-pointer items-start gap-2.5 rounded-xl border border-line border-l-4 border-l-accent bg-bg/95 py-3 pl-3.5 pr-8 text-left shadow-lg backdrop-blur-md transition hover:border-line-strong"
      >
        <svg
          className="mt-0.5 h-4 w-4 shrink-0 text-accent"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M12 4.5c4.7 0 8.5 3.1 8.5 7s-3.8 7-8.5 7c-.9 0-1.8-.1-2.6-.3L5 20l1.1-2.9C4.2 15.9 3.5 14 3.5 11.5c0-3.9 3.8-7 8.5-7z"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinejoin="round"
          />
        </svg>
        <span className="min-w-0 flex-1">
          <span className="block text-2xs font-medium uppercase tracking-[0.18em] text-faint">
            {KIND_LABEL[notice.kind]}
          </span>
          <span className="mt-1 block truncate text-base font-semibold text-fg">
            {notice.title}
          </span>
          <span className="mt-0.5 block text-xs text-accent">点击前往会话</span>
        </span>
      </button>
      <button
        type="button"
        onClick={() => onClose(notice.id)}
        aria-label="关闭通知"
        className="absolute right-1.5 top-1.5 flex h-5 w-5 cursor-pointer items-center justify-center rounded-md text-hint transition hover:bg-fg/[0.06] hover:text-fg"
      >
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3 w-3">
          <path
            d="M6 6l12 12M18 6L6 18"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          />
        </svg>
      </button>
    </div>
  )
}

/**
 * 全站通知器：挂在 App 根节点下（每个路由都渲染），大模型答完或会话被投递新消息时弹气泡。
 */
export default function ChatNotifier() {
  const [notices, setNotices] = useState<Notice[]>([])

  /** 弹一条气泡；同一会话已有气泡挂在屏上就不重复弹 */
  const pushNotice = useCallback((id: string, kind: NoticeKind, title: string) => {
    setNotices((cur) =>
      cur.some((n) => n.id === id) ? cur : [{ id, title, kind }, ...cur].slice(0, MAX_NOTICES),
    )
  }, [])

  /* 来源一：本标签页的生成收尾（运行时仓当场推来，见 chatLive.onChatSettled）。
     正在看那条会话时不打扰——输出就在眼前 */
  useEffect(
    () =>
      onChatSettled((sessionId) => {
        if (sessionId === getActiveChatSession()) return
        /* 标题现取一次：会话标题是首条提问攒出来的，可能刚写进去，本地缓存还来不及更新 */
        void listSessions()
          .catch(() => [] as SessionItem[])
          .then((list) => {
            pushNotice(sessionId, 'answered', list.find((s) => s.id === sessionId)?.title || '未命名会话')
          })
      }),
    [pushNotice],
  )

  /* 来源二：会话事件推送（STEP 4，取代原来每 5 秒轮询会话列表的 unread）。
     子代理结论投回、或某会话在别处答完，经 SSE 即时到达——正在看的那条不打扰（输出就在眼前） */
  useEffect(
    () =>
      subscribeSessionEvents((e) => {
        /* todo（个人待办清单更新）由会话页的 TODO 卡片自行消费，不弹通知气泡 */
        if (e.kind === 'todo') return
        if (e.sessionId === getActiveChatSession()) return
        pushNotice(e.sessionId, e.kind, e.title || '未命名会话')
      }),
    [pushNotice],
  )

  const dismiss = useCallback((id: string) => {
    setNotices((cur) => cur.filter((n) => n.id !== id))
  }, [])

  /** 点气泡：请会话页打开该会话；已在首页时 hash 不变（不触发路由），在别的页则切回首页 */
  const open = useCallback(
    (n: Notice) => {
      requestOpenSession(n.id)
      window.location.hash = '#/'
      dismiss(n.id)
    },
    [dismiss],
  )

  if (notices.length === 0) return null

  return (
    /* 左上角、顶栏之下：避开导航，不挡操作；容器不接事件，只有气泡本身可点 */
    <div className="pointer-events-none fixed left-4 top-[72px] z-[65] flex flex-col gap-2">
      {notices.map((n) => (
        <NoticeCard key={n.id} notice={n} onOpen={open} onClose={dismiss} />
      ))}
    </div>
  )
}
