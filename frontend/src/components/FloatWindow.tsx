import { AppIcon } from './AppIcon'
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'
import { useThemeState } from '../theme/store'

let windowStack: string[] = []
const stackListeners = new Set<() => void>()
const subscribeStack = (listener: () => void) => { stackListeners.add(listener); return () => { stackListeners.delete(listener) } }
const readStack = () => windowStack
function updateStack(next: string[]) { windowStack = next; stackListeners.forEach(listener => listener()) }
function activateWindow(id: string) {
  if (windowStack.at(-1) !== id) updateStack([...windowStack.filter(item => item !== id), id])
}

/**
 * 可拖动悬浮窗口：标题栏按住拖动（Pointer Events + setPointerCapture），
 * 打开即按实测尺寸在视口正中间落位（内容多高都居中，四边都留在可视区内）；
 * Esc 或关闭钮退出。不带遮罩，可同时操作页面其余部分。
 *
 * 渲染上 portal 到 body：
 * 宿主子树里只要嵌着一个 backdrop-filter / clip-path 的祖先（.ui-card__in 的玻璃面、
 * .ui-card 的切角、外壳顶栏的 .ui-surface 等），子树内的 position:fixed 就会被那个祖先
 * 劫持成 containing block 并被它裁剪——窗口于是错位、一大半落到可视区外。portal 之后
 * fixed 恒以视口为参照。共享浮窗栈管理激活顺序，仅顶层响应 Escape。
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
  /** 特定阅读器可接管内部滚动与留白，默认窗口保持原样。 */
  contentClassName?: string
  /** 长篇审阅窗口可切换为占满可用视口。 */
  expandable?: boolean
}

export default function FloatWindow({ title, onClose, children, width = 520, contentClassName = '', expandable = false }: Props) {
  const { vars } = useThemeState()
  const workbench = !!vars['--ui-win-title-bg']
  const id = useId()
  const stack = useSyncExternalStore(subscribeStack, readStack)
  const closeRef = useRef(onClose)
  useLayoutEffect(() => { closeRef.current = onClose }, [onClose])
  const previousFocus = useRef(document.activeElement as HTMLElement | null)
  const focusPlaced = useRef(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const [expanded, setExpanded] = useState(false)
  const expandedRef = useRef(false)
  useLayoutEffect(() => { expandedRef.current = expanded }, [expanded])
  /** 窗口位置（视口坐标）：null = 还没量到尺寸，此帧先不落位，避免闪一下 */
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  /** 拖动会话：起点指针坐标 + 窗口基准位置 */
  const drag = useRef<{ px: number; py: number; baseX: number; baseY: number } | null>(null)

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented || windowStack.at(-1) !== id) return
      if (e.target instanceof Element && e.target.closest('.xterm')) return
      const root = document.getElementById('root')
      if (root?.inert) return
      e.preventDefault()
      closeRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [id])

  useLayoutEffect(() => {
    activateWindow(id)
    const box = boxRef.current
    const focusTarget = previousFocus.current
    return () => {
      updateStack(windowStack.filter(item => item !== id))
      if (box?.contains(document.activeElement) || document.activeElement === document.body) {
        if (focusTarget?.isConnected) focusTarget.focus({ preventScroll: true })
      }
    }
  }, [id])

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
    const constrain = () => {
      if (expandedRef.current) return
      const bounds = el.getBoundingClientRect()
      setPos(current => {
        if (!current) return current
        const x = Math.max(8, Math.min(current.x, window.innerWidth - bounds.width - 8))
        const y = Math.max(8, Math.min(current.y, window.innerHeight - bounds.height - 8))
        return x === current.x && y === current.y ? current : { x, y }
      })
    }
    const observer = new ResizeObserver(constrain)
    observer.observe(el)
    window.addEventListener('resize', constrain)
    return () => { observer.disconnect(); window.removeEventListener('resize', constrain) }
  }, [])

  useLayoutEffect(() => {
    const el = boxRef.current
    if (!pos || !el || focusPlaced.current) return
    focusPlaced.current = true
    if (!el.contains(document.activeElement)) {
      const control = el.querySelector<HTMLElement>('input:not(:disabled),textarea:not(:disabled),select:not(:disabled)')
      ;(control ?? el).focus({ preventScroll: true })
    }
  }, [pos])

  const onTitlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // 点到标题栏内的按钮（如关闭钮）时不进入拖动
    if ((e.target as HTMLElement).closest('button')) return
    if (expanded || window.innerWidth <= 600) return
    const base = pos ?? { x: 0, y: 0 }
    drag.current = { px: e.clientX, py: e.clientY, baseX: base.x, baseY: base.y }
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d) return
    const x = d.baseX + e.clientX - d.px
    const y = d.baseY + e.clientY - d.py
    const bounds = boxRef.current?.getBoundingClientRect()
    // Keep all controls reachable, using the actual constrained dimensions.
    setPos({
      x: Math.max(8, Math.min(x, window.innerWidth - (bounds?.width ?? width) - 8)),
      y: Math.max(8, Math.min(y, window.innerHeight - (bounds?.height ?? 48) - 8)),
    })
  }

  const endDrag = () => {
    drag.current = null
  }

  return (
    <>
      {createPortal(
          <div
            ref={boxRef}
            role="dialog"
            aria-modal="false"
            aria-labelledby={`${id}-title`}
            tabIndex={-1}
            onPointerDownCapture={() => activateWindow(id)}
            onFocusCapture={() => activateWindow(id)}
            style={{
              left: expanded ? 8 : pos?.x ?? 0,
              top: expanded ? 8 : pos?.y ?? 0,
              width: expanded ? 'calc(100vw - 16px)' : width,
              ...(expanded ? { height: 'calc(100dvh - 16px)', maxHeight: 'calc(100dvh - 16px)' } : {}),
              zIndex: 60 + Math.max(0, stack.indexOf(id)),
              /* 量到尺寸前先隐身，避免在 (0,0) 处闪一下 */
              visibility: pos ? 'visible' : 'hidden',
            }}
            className={`ui-float-window fixed flex max-h-[82dvh] max-w-[calc(100vw-16px)] flex-col overflow-hidden rounded-md border border-line bg-bg shadow-xl${
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
              className="flex min-h-11 shrink-0 cursor-grab touch-none select-none items-center justify-between gap-2 border-b border-line bg-fg/[0.03] pl-3.5 pr-1 active:cursor-grabbing"
              style={
                workbench
                  ? ({
                      background: 'var(--ui-win-title-bg)',
                      '--c-fg': 'var(--ui-win-title-fg)',
                      '--c-muted': 'var(--ui-win-title-fg)',
                    } as CSSProperties)
                  : undefined
              }
            >
              <div id={`${id}-title`} className="min-w-0 truncate text-sm font-medium text-fg">{title}</div>
              <div className="flex shrink-0 items-center">
              {expandable && <button type="button" onClick={() => { drag.current = null; setExpanded(v => !v) }}
                aria-label={expanded ? '退出全屏阅读' : '全屏阅读'} title={expanded ? '退出全屏阅读' : '全屏阅读'}
                className="flex h-10 w-10 items-center justify-center rounded-sm text-muted hover:bg-fg/[0.06] hover:text-fg">
                <AppIcon name={expanded ? 'exit-fullscreen' : 'fullscreen'} className="h-4 w-4" />
              </button>}
              <button
                type="button"
                onClick={onClose}
                aria-label="关闭"
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-sm text-muted transition hover:bg-fg/[0.06] hover:text-fg"
              >
                <AppIcon name="close" className="h-3.5 w-3.5" />
              </button>
              </div>
            </div>
            {/* Only the drag handle is unselectable; prose, errors and output remain copyable. */}
            <div className={`scrollbar-slim min-h-0 flex-1 select-text overflow-y-auto p-3.5 ${contentClassName}`}>
              {children}
            </div>
          </div>,
          document.body,
        )}
    </>
  )
}
