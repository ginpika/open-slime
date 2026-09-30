import { useEffect, useRef, useState, type ReactNode } from 'react'

/**
 * 可拖动悬浮窗口：标题栏按住拖动（Pointer Events + setPointerCapture），
 * 初始屏幕居中偏上，位置限制在视口内（标题栏始终可见可抓）。Esc 或关闭钮退出。
 * 不带遮罩，可同时操作页面其余部分。
 */

type Props = {
  /** 标题栏内容 */
  title: ReactNode
  onClose: () => void
  children: ReactNode
  /** 窗口宽度（px，默认 520） */
  width?: number
}

export default function FloatWindow({ title, onClose, children, width = 520 }: Props) {
  // 初始位置：水平居中、垂直 15% 处（挂载时一次性计算）
  const [pos, setPos] = useState(() => ({
    x: Math.max(16, (window.innerWidth - Math.min(width, window.innerWidth * 0.9)) / 2),
    y: Math.max(16, window.innerHeight * 0.15),
  }))
  /** 拖动会话：起点指针坐标 + 窗口基准位置 */
  const drag = useRef<{ px: number; py: number; baseX: number; baseY: number } | null>(null)

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const onTitlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // 点到标题栏内的按钮（如关闭钮）时不进入拖动
    if ((e.target as HTMLElement).closest('button')) return
    drag.current = { px: e.clientX, py: e.clientY, baseX: pos.x, baseY: pos.y }
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    const x = d.baseX + e.clientX - d.px
    const y = d.baseY + e.clientY - d.py
    // clamp：窗口不完全移出视口（每边至少留 80px 宽 / 标题栏整高）
    setPos({
      x: Math.min(Math.max(x, -width + 80), window.innerWidth - 80),
      y: Math.min(Math.max(y, 8), window.innerHeight - 48),
    })
  }

  const endDrag = () => {
    drag.current = null
  }

  return (
    <div
      role="dialog"
      aria-modal="false"
      style={{ left: pos.x, top: pos.y, width }}
      className="fixed z-[60] flex max-h-[70vh] max-w-[90vw] flex-col overflow-hidden rounded-md border border-line bg-bg shadow-xl"
    >
      {/* 标题栏：拖动手柄 */}
      <div
        onPointerDown={onTitlePointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        className="flex h-9 shrink-0 cursor-grab touch-none select-none items-center justify-between gap-2 border-b border-line bg-fg/[0.03] pl-3.5 pr-2 active:cursor-grabbing"
      >
        <div className="min-w-0 truncate text-xs font-medium text-fg">{title}</div>
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭"
          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-sm text-muted transition hover:bg-fg/[0.06] hover:text-fg"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3.5 w-3.5">
            <path
              d="M6 6l12 12M18 6L6 18"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            />
          </svg>
        </button>
      </div>
      {/* 内容区（select-none：拖动窗口/图时不误选文字；输入框内仍可正常选择；scrollbar-slim：细滚动条） */}
      <div className="scrollbar-slim min-h-0 flex-1 select-none overflow-y-auto p-3.5">{children}</div>
    </div>
  )
}
