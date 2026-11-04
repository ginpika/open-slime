/**
 * 桌面框架 —— 无边框窗口的自绘外壳
 *
 * 只在桌面壳里渲染（见 App.tsx 的 isDesktopShell() 判断）：窗口已去掉系统装饰，
 * 标题栏、拖拽区、窗口按钮、窗缘缩放都要页面自己给。
 *
 *   ┌ 外层纯色窗缘（12px）────────────────────────────────┐
 *   │ ┌ 菜单栏 88px ┐ ┌ 主视图容器 ───────────────────────┐ │
 *   │ │ 白底 logo   │ │ 标题栏（拖拽区+身份胶囊+窗口按钮） │ │
 *   │ │ 竖排 NAV    │ │ 页面内容（AppShell 渲染在这里）    │ │
 *   │ └─────────────┘ └───────────────────────────────────┘ │
 *   └───────────────────────────────────────────────────────┘
 *
 * 菜单栏、标题栏、窗口按钮与身份胶囊跟随 data-scheme 切换纯浅色 / 深色，
 * 主视图里的 AppShell 继续使用应用主题令牌（--c-*）。
 *
 * Vite 入口 index.html 提供同尺寸、同配色的首帧占位，调整外壳视觉时需同步其骨架样式。
 */
import { AppIcon } from '../components/AppIcon'
import { AstrolabeLogo } from '../components/AstrolabeLogo'
import { useThemeState } from '../theme/store'
import { useEffect, useLayoutEffect, useState, type ReactNode } from 'react'
import {
  closeWindow,
  isWindowMaximized,
  minimizeWindow,
  resizeWindow,
  toggleMaximizeWindow,
} from '../api/desktop'
import { BRAND } from '../branding'
import { ShellIcon } from './icons'
import { NAV } from './nav'
import './desktopFrame.css'

/** 窗缘热区方位，取值与 plugin:window|start_resize_dragging 的 ResizeDirection 一致 */
const EDGES = [
  'North',
  'South',
  'West',
  'East',
  'NorthWest',
  'NorthEast',
  'SouthWest',
  'SouthEast',
] as const

export function DesktopFrame({
  activePath,
  userSlot,
  toolsSlot,
  homeSlot,
  hideRail = false,
  children,
}: {
  activePath: string
  /** 隐藏左侧菜单轨；对话首页另外将窗口控件叠在内容上，不占标题栏高度。 */
  hideRail?: boolean
  /** 身份胶囊：状态与用户逻辑由 App 持有（见 App.tsx 的 UserMenu），框架只给位置 */
  userSlot?: ReactNode
  /** 标题栏工具位（主题按钮）：隐藏顶栏时由 App 挪到这里，位于身份胶囊左侧 */
  toolsSlot?: ReactNode
  /** 标题栏首页入口（回引导页，见 App.tsx）：紧邻身份胶囊左侧 */
  homeSlot?: ReactNode
  children: ReactNode
}) {
  const [maximized, setMaximized] = useState(false)
  const { themeId } = useThemeState()
  const conversation = hideRail && activePath === '/'
  const mac = typeof navigator !== 'undefined' && /Mac/i.test(navigator.platform)

  /* 页面整体让出底色（见 desktopFrame.css 的 body[data-df]）：窗口是透明的，桌面要从
     外壳的圆角外透出来。用 layout effect 赶在首帧前挂上，
     免得先闪一帧不透明的 --c-bg */
  useLayoutEffect(() => {
    document.body.dataset.df = 'on'
    /* 首帧骨架（index.html 预置）到位即撤：形状与外壳一致，撤下时看不出变化。
       先挂 data-df 把底让给桌面、再撤骨架，中间不会闪出一帧不透明底 */
    document.getElementById('minis-df-skeleton')?.remove()
    document.documentElement.classList.remove('minis-df-skeleton', 'minis-df-conversation')
    return () => {
      delete document.body.dataset.df
    }
  }, [])

  /* 最大化状态可能被系统拖拽 / 双击 / 贴边改变，靠 resize 事件回读 */
  useEffect(() => {
    let alive = true
    const sync = () => {
      isWindowMaximized().then((next) => {
        if (alive) setMaximized(next)
      })
    }
    sync()
    window.addEventListener('resize', sync)
    return () => {
      alive = false
      window.removeEventListener('resize', sync)
    }
  }, [])

  return (
    <div className={`df${conversation ? ' df--conversation' : ''}${mac ? ' df--mac' : ''}`}>
      <div className="df__acrylic" aria-hidden="true" />

      {/* -------------------------------------------- 左：菜单栏 */}
      {!hideRail && <aside className="df__rail">
        {/* 品牌位也当拖拽区（整块含内边距都能拖，故用 deep） */}
        <div className="df__brand" data-tauri-drag-region="deep" title={BRAND.name} role="img" aria-label={BRAND.name}>
          {themeId === 'astrolabe' ? <AstrolabeLogo /> : <img src="/icon-64x64.png" alt="" draggable={false} />}
        </div>

        <nav className="df__menu scrollbar-hide" aria-label="主导航">
          {NAV.map((item) => {
            const on = item.path === activePath
            return (
              <a
                key={item.path}
                href={`#${item.path}`}
                title={item.label}
                aria-label={item.label}
                aria-current={on ? 'page' : undefined}
                className="df__item"
              >
                <ShellIcon name={item.icon} className="df__icon" />
              </a>
            )
          })}
        </nav>
      </aside>}

      {/* -------------------------------------------- 右：主视图 */}
      <div className="df__stage">
        {/* 标题栏：一行 = 左段拖拽区 + 身份胶囊 + 窗口按钮。
            拖拽区是按钮的同层前兄弟，不覆盖按钮 */}
        <div className="df__titlebar">
          <div className="df__drag" data-tauri-drag-region />
          {!conversation && toolsSlot}
          {!conversation && homeSlot}
          {!conversation && userSlot}
          <div className="df__win">
            <button
              type="button"
              className="df__wbtn df__wbtn--minimize"
              title="最小化"
              aria-label="最小化"
              onClick={minimizeWindow}
            >
              <AppIcon name="minimize" />
            </button>
            <button
              type="button"
              className="df__wbtn df__wbtn--maximize"
              title={maximized ? '还原' : '最大化'}
              aria-label={maximized ? '还原' : '最大化'}
              onClick={() => toggleMaximizeWindow().then(setMaximized)}
            >
              {maximized ? (
                <AppIcon name="restore" />
              ) : (
                <AppIcon name="square" />
              )}
            </button>
            <button
              type="button"
              className="df__wbtn df__wbtn--close"
              title="关闭"
              aria-label="关闭"
              onClick={closeWindow}
            >
              <AppIcon name="close" />
            </button>
          </div>
        </div>

        <div className="df__view">{children}</div>
      </div>

      {/* 窗缘热区：无边框后系统不再给缩放热区，这里补一圈透明热区 */}
      <div className="df__resize" aria-hidden="true">
        {EDGES.map((dir) => (
          <i
            key={dir}
            data-dir={dir}
            onPointerDown={(e) => {
              if (e.button !== 0) return
              e.preventDefault()
              resizeWindow(dir)
            }}
          />
        ))}
      </div>
    </div>
  )
}
