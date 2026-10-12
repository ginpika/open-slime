import { useEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * Tailwind 风格轻提示（message box）：视口顶部居中悬浮卡片，
 * 淡入上滑 / 淡出动画，超时自动关闭（点击可立即关闭）。
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
  /** 停留时长（毫秒），随后淡出并在动画结束后回调 onClose；缺省按类型取（错误更久） */
  duration?: number
  onClose: () => void
}) {
  /** 入场/退场动画开关（false = 透明 + 上移） */
  const [shown, setShown] = useState(false)
  /** onClose 存 ref：父级传内联箭头函数也不会重置计时 */
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    if (message === null) return
    const stay = duration ?? (kind === 'error' ? 6000 : 4500)
    // 先复位再下一帧切入，保证同一位置连续触发也有完整入场动画
    setShown(false)
    const raf = requestAnimationFrame(() => setShown(true))
    const hide = setTimeout(() => setShown(false), stay)
    const close = setTimeout(() => closeRef.current(), stay + 300)
    return () => {
      cancelAnimationFrame(raf)
      clearTimeout(hide)
      clearTimeout(close)
    }
  }, [message, duration, kind])

  if (message === null) return null

  return createPortal(
    <div className="pointer-events-none fixed inset-x-0 top-5 z-[70] flex justify-center px-4">
      <div
        role="status"
        onClick={() => closeRef.current()}
        title="点击关闭"
        className={`toast-card pointer-events-auto flex max-w-xl cursor-pointer items-start gap-2.5 rounded-lg border border-line border-l-4 bg-bg/95 px-4 py-3 shadow-lg backdrop-blur-md transition-all duration-300 ${
          kind === 'error' ? 'border-l-hint' : 'border-l-ok'
        } ${shown ? 'translate-y-0 opacity-100' : '-translate-y-3 opacity-0'}`}
      >
        {kind === 'error' ? (
          <svg
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
            className="mt-0.5 h-4 w-4 shrink-0 text-hint"
          >
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
            <path d="M12 7.5v5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            <circle cx="12" cy="16.4" r="0.6" fill="currentColor" />
          </svg>
        ) : (
          <svg
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
            className="mt-0.5 h-4 w-4 shrink-0 text-ok"
          >
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2" />
            <path
              d="M8 12.2l2.6 2.6L16 9.4"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
        <div className="min-w-0 text-sm leading-relaxed text-fg">{message}</div>
      </div>
    </div>,
    document.body,
  )
}
