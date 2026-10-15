import { AppIcon } from './AppIcon'
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

let toastDock: HTMLDivElement | null = null
let dockUsers = 0
function acquireDock() {
  if (!toastDock) {
    toastDock = document.createElement('div')
    toastDock.className = 'toast-stack'
    document.body.append(toastDock)
  }
  dockUsers++
  return toastDock
}

/**
 * Tailwind 风格轻提示（message box）：视口顶部居中悬浮卡片，
 * 所有实例进入共享通知栈；成功提示自动关闭，错误保留到明确关闭。
 * 用于替代提交成功横幅与页内错误提示，避免挤占、破坏原有布局。
 * z 序高于 FloatWindow（z-[60]）与 ChatNotifier（z-[65]），弹窗内提交后同样可见。
 *
 * <p>渲染上 portal 到 body（与 FloatWindow / ChatNotifier 同一套思路）：调用处所在子树里
 * 常嵌着 backdrop-filter / clip-path 的祖先（.ui-card__in 的玻璃面、.ui-card 的切角等），
 * 子树内的 position:fixed 会被那个祖先劫持成 containing block——通知条于是跟着 DOM 卡在
 * 内容区里、既不在视口正中也可能被裁。portal 之后 fixed 恒以浏览器视口为参照，
 * 且脱离 main 的 z-20 叠层，直接压在 z-30 顶栏之上，无需再按外壳挪基准。
 * 终末地换装（.toast-card 白底黑缘 / 炭底点缀色光晕）在 shell.css 补丁区。
 */
export type ToastKind = 'success' | 'error'

export default function Toast({
  message,
  kind = 'success',
  duration,
  onClose,
}: {
  /** 提示内容（null = 不渲染；变化时重触发入场动画与计时） */
  message: ReactNode
  kind?: ToastKind
  /** 停留时长（毫秒）；0 表示常驻。默认成功 4500ms，错误常驻。 */
  duration?: number
  onClose: () => void
}) {
  /** 入场/退场动画开关（false = 透明 + 上移） */
  const [shown, setShown] = useState(false)
  const [host, setHost] = useState<HTMLElement | null>(null)
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  /** onClose 存 ref：父级传内联箭头函数也不会重置计时 */
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useLayoutEffect(() => {
    setHost(acquireDock())
    return () => {
      dockUsers--
      if (!dockUsers) { toastDock?.remove(); toastDock = null }
    }
  }, [])

  useEffect(() => {
    if (message === null) return
    setHovered(false)
    setFocused(false)
    // 先复位再下一帧切入，保证同一位置连续触发也有完整入场动画
    setShown(false)
    const raf = requestAnimationFrame(() => setShown(true))
    return () => cancelAnimationFrame(raf)
  }, [message])

  useEffect(() => {
    const stay = duration ?? (kind === 'error' ? 0 : 4500)
    if (message === null || stay <= 0 || hovered || focused) return
    const hide = setTimeout(() => setShown(false), stay)
    const close = setTimeout(() => closeRef.current(), stay + 300)
    return () => {
      clearTimeout(hide)
      clearTimeout(close)
    }
  }, [message, duration, kind, hovered, focused])

  if (message === null || !host) return null

  return createPortal(
      <div
        role={kind === 'error' ? 'alert' : 'status'}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        onFocusCapture={() => setFocused(true)}
        onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false) }}
        className={`toast-card toast-card--${kind} pointer-events-auto flex w-max max-w-full shrink-0 items-center gap-2.5 rounded-lg border border-line border-l-4 bg-bg/95 px-4 py-3 shadow-lg backdrop-blur-md transition-all duration-300 ${
          kind === 'error' ? 'border-l-danger' : 'border-l-ok'
        } ${shown ? 'translate-y-0 opacity-100' : '-translate-y-3 opacity-0'}`}
      >
        {kind === 'error' ? (
          <AppIcon name="alert" className="h-4 w-4 shrink-0 text-danger" />
        ) : (
          <AppIcon name="success" className="h-4 w-4 shrink-0 text-ok" />
        )}
        <div className="scrollbar-slim max-h-[45dvh] min-w-0 flex-1 select-text overflow-y-auto whitespace-pre-wrap break-words text-sm leading-relaxed text-fg">{message}</div>
        <button type="button" aria-label="关闭通知" onClick={() => closeRef.current()} className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted transition hover:bg-fg/10 hover:text-fg">×</button>
      </div>,
    host,
  )
}
