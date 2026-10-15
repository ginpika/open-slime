/**
 * 桌面框架 —— 无边框窗口的自绘外壳
 *
 * 只在桌面壳里渲染（见 App.tsx 的 isDesktopShell() 判断）：窗口已去掉系统装饰，
 * 标题栏、拖拽区、窗口按钮、窗缘缩放都要页面自己给。
 *
 *   ┌ 外层一圈亚克力（12px）────────────────────────────────┐
 *   │ ┌ 菜单栏 88px ┐ ┌ 主视图容器 ───────────────────────┐ │
 *   │ │ 白底 logo   │ │ 标题栏（拖拽区+身份胶囊+窗口按钮） │ │
 *   │ │ 竖排 NAV    │ │ 页面内容（AppShell 渲染在这里）    │ │
 *   │ └─────────────┘ └───────────────────────────────────┘ │
 *   └───────────────────────────────────────────────────────┘
 *
 * 这一层是「窗口外壳」，和引导页（desktop/ui/index.html）是同一块东西：外观严格同源，
 * 深色轨配色（#1c1b1d 菜单栏 / 圆形窗口按钮 / 身份胶囊）刻意写死、不跟主题走；
 * 只有主视图里装的 AppShell 跟随主题（--c-*）。
 *
 * 两者分属不同构建产物（tauri 自带静态资源 vs Vite 产物），无法共用代码，
 * 所以改引导页那套配色/尺寸时，必须回 desktopFrame.css 成对同步。
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
  children,
}: {
  activePath: string
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

  /* 页面整体让出底色（见 desktopFrame.css 的 body[data-df]）：窗口是透明的，桌面要从
     外壳的圆角外与那一圈亚克里透出来。用 layout effect 赶在首帧前挂上，
     免得先闪一帧不透明的 --c-bg */
  useLayoutEffect(() => {
    document.body.dataset.df = 'on'
    /* 首帧骨架（index.html 预置）到位即撤：形状与外壳一致，撤下时看不出变化。
       先挂 data-df 把底让给桌面、再撤骨架，中间不会闪出一帧不透明底 */
    document.getElementById('minis-df-skeleton')?.remove()
    document.documentElement.classList.remove('minis-df-skeleton')
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
    <div className="df">
      <div className="df__acrylic" aria-hidden="true" />

      {/* -------------------------------------------- 左：菜单栏 */}
      <aside className="df__rail">
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
      </aside>

      {/* -------------------------------------------- 右：主视图 */}
      <div className="df__stage">
        {/* 标题栏：一行 = 左段拖拽区 + 身份胶囊 + 窗口按钮。
            与引导页同构——拖拽区是按钮的同层前兄弟，不覆盖按钮 */}
        <div className="df__titlebar">
          <div className="df__drag" data-tauri-drag-region />
          {toolsSlot}
          {homeSlot}
          {userSlot}
          <div className="df__win">
            <button
              type="button"
              className="df__wbtn"
              title="最小化"
              aria-label="最小化"
              onClick={minimizeWindow}
            >
              <AppIcon name="minimize" />
            </button>
            <button
              type="button"
              className="df__wbtn"
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
