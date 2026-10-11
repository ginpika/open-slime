import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { useThemeState } from '../theme/store'

/**
 * 可拖动悬浮窗口：标题栏按住拖动（Pointer Events + setPointerCapture），
 * 打开即按实测尺寸在视口正中间落位（内容多高都居中，四边都留在可视区内）；
 * Esc 或关闭钮退出。不带遮罩，可同时操作页面其余部分。
 *
 * 渲染上 portal 到最近的主内容区（<main>；取不到则退回 body）：
 * 宿主子树里只要嵌着一个 backdrop-filter / clip-path 的祖先（.ui-card__in 的玻璃面、
 * .ui-card 的切角、外壳顶栏的 .ui-surface 等），子树内的 position:fixed 就会被那个祖先
 * 劫持成 containing block 并被它裁剪——窗口于是错位、一大半落到可视区外。portal 之后
 * fixed 恒以视口为参照，且仍与 Toast 同处一个叠层上下文（Toast 的 z 序更高，压得住窗口）。
 *
 * 窗口样式可被主题换装（哨兵式）：当令牌快照里存在 --ui-win-title-bg
 * （终末地「工作台」窗口样式档写入，值登记于 layers.ts）时挂
 * .ui-win-workbench —— 窗口内部 --c-* 切到浅色印刷环境，标题栏换
 * 深炭底浅字。其他主题无此令牌族，维持默认外观。
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
  const { vars } = useThemeState()
  const workbench = !!vars['--ui-win-title-bg']
  /** 原地留一个不可见锚点：由它找最近的主内容区，作为 portal 宿主 */
  const anchorRef = useRef<HTMLSpanElement>(null)
  const [host, setHost] = useState<HTMLElement | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  /** 窗口位置（视口坐标）：null = 还没量到尺寸，此帧先不落位，避免闪一下 */
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
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

  /* 首帧（绘制前）确定 portal 宿主：最近的主内容区，找不到就退回 body */
  useLayoutEffect(() => {
    setHost(anchorRef.current?.closest('main') ?? document.body)
  }, [])

  /* 挂载后按实测宽高居中：宽高随内容变化，只有量过才能落在正中间；
     Math.max(8, …) 保证窄视口 / 超高内容下至少贴住左上、四边不被裁。
     layout effect 在绘制前同步跑完，setPos 触发的那次重渲染也在绘制前完成，
     所以用户看到的第一帧就是居中结果，不会先闪在别处 */
  useLayoutEffect(() => {
    const el = boxRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    setPos({
      x: Math.max(8, Math.round((window.innerWidth - r.width) / 2)),
      y: Math.max(8, Math.round((window.innerHeight - r.height) / 2)),
    })
  }, [host])

  const onTitlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // 点到标题栏内的按钮（如关闭钮）时不进入拖动
    if ((e.target as HTMLElement).closest('button')) return
    const base = pos ?? { x: 0, y: 0 }
    drag.current = { px: e.clientX, py: e.clientY, baseX: base.x, baseY: base.y }
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
    <>
      {/* 定位锚点（不渲染）：随调用处所在子树走，用来找最近的主内容区 */}
      <span ref={anchorRef} hidden />
      {host &&
        createPortal(
          <div
            ref={boxRef}
            role="dialog"
            aria-modal="false"
            style={{
              left: pos?.x ?? 0,
              top: pos?.y ?? 0,
              width,
              /* 量到尺寸前先隐身，避免在 (0,0) 处闪一下 */
              visibility: pos ? 'visible' : 'hidden',
            }}
            className={`fixed z-[60] flex max-h-[70vh] max-w-[90vw] flex-col overflow-hidden rounded-md border border-line bg-bg shadow-xl${
              workbench ? ' ui-win-workbench' : ''
            }`}
          >
            {/* 标题栏：拖动手柄。工作台档换深炭底，并在栏内把 --c-fg 切到
                标题栏前景——标题文字 / 关闭钮的 text-* 类与 hover 逻辑无需改动 */}
            <div
              onPointerDown={onTitlePointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
              className="flex h-9 shrink-0 cursor-grab touch-none select-none items-center justify-between gap-2 border-b border-line bg-fg/[0.03] pl-3.5 pr-2 active:cursor-grabbing"
              style={
                workbench
                  ? ({
                      background: 'var(--ui-win-title-bg)',
                      '--c-fg': 'var(--ui-win-title-fg)',
                    } as CSSProperties)
                  : undefined
              }
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
            <div className="scrollbar-slim min-h-0 flex-1 select-none overflow-y-auto p-3.5">
              {children}
            </div>
          </div>,
          host,
        )}
    </>
  )
}
