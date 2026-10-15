/**
 * Flat 外壳（Tailwind Mono）
 *
 * 默认平面语言：顶部横排文字链 / 侧边栏分组两档排布，
 * 差异集中在颜色、圆角、投影与字重——由令牌层表达。
 *
 *   minimal → Tailwind Mono 默认：顶部横排文字链（保持原有观感）
 *   sidebar → Tailwind Mono 侧边栏档（mono.sidebar 开启）：左侧分组侧栏 +
 *             顶部工具条（原 Clean 排布，典型 SaaS）
 *
 * 契约完全遵守 ShellProps。
 */
import { BRAND } from '../branding'
import { isDesktopShell } from '../api/desktop'
import { useThemeState } from '../theme/store'
import { ShellIcon } from './icons'
import { NAV, NAV_GROUPS, activePathOf, pageNavOf } from './nav'
import type { ShellProps } from './types'

export type FlatVariant = 'minimal' | 'sidebar'

/** Tailwind Mono 外壳入口（注册表消费）：排布变体由主题配置 mono.sidebar 决定。
 *  放在 FlatShell 旁边，让「变体在哪选」与「变体有哪些」同处一文件。 */
export function FlatThemeShell(props: ShellProps) {
  const { mono } = useThemeState()
  return (
    <FlatShell
      {...props}
      variant={!isDesktopShell() && mono.sidebar ? 'sidebar' : 'minimal'}
      /* 桌面端沉浸排布：收起顶底栏（主题按钮由 App 移到窗口标题栏） */
      hideChrome={isDesktopShell() && mono.hideChrome}
    />
  )
}

export function FlatShell({
  variant,
  hideChrome,
  route,
  activePath,
  fullBleed,
  tools,
  userSlot,
  mainRef,
  children,
}: ShellProps & { variant: FlatVariant; hideChrome: boolean }) {
  const active = activePath || activePathOf(route)
  const pageNav = pageNavOf(route)

  /* --------------------------------------- Tailwind Mono · 默认顶部横排 */
  if (variant === 'minimal') {
    return (
      <div className="flex h-screen flex-col overflow-hidden bg-bg text-fg antialiased">
        {!hideChrome && (
          <header className="relative z-30 shrink-0 border-b border-line">
            <div className="mx-auto flex max-w-6xl items-center justify-between gap-6 px-6 py-4">
              <nav className="scrollbar-hide flex min-w-0 flex-1 items-center gap-5 overflow-x-auto">
                {NAV.map((n) => {
                  const on = n.path === active
                  return (
                    <a
                      key={n.path}
                      href={`#${n.path}`}
                      aria-current={on ? 'page' : undefined}
                      className={
                        on
                          ? 'shrink-0 whitespace-nowrap text-sm font-semibold text-fg underline decoration-fg underline-offset-8'
                          : 'shrink-0 whitespace-nowrap text-sm font-medium text-faint underline-offset-8 transition hover:text-fg'
                      }
                    >
                      {n.label}
                    </a>
                  )
                })}
              </nav>
              <div className="flex shrink-0 items-center gap-4 text-base">
                {tools}
                {userSlot({ placement: 'down', withName: false })}
              </div>
            </div>
          </header>
        )}
        <main
          ref={mainRef}
          className={
            fullBleed
              ? 'min-h-0 flex-1'
              : `mx-auto w-full max-w-6xl min-h-0 flex-1 overflow-y-auto px-6 pb-16${
                  route === '/' ? ' scrollbar-hide' : ''
                }${
                  /^\/article\//.test(route)
                    ? ' scrollbar-accent'
                    : ' scrollbar-slim'
                }`
          }
        >
          {children}
        </main>
        {!hideChrome && (
          <footer className="shrink-0 border-t border-line">
            <p className="mx-auto max-w-6xl px-6 py-6 text-sm text-faint">{BRAND.footer}</p>
          </footer>
        )}
      </div>
    )
  }

  /* ------------------------------------------- Tailwind Mono · 侧边栏档 */
  if (variant === 'sidebar') {
    return (
      <div className={`flex h-screen overflow-hidden bg-bg text-fg${route === '/coding' ? ' flex-col md:flex-row' : ''}`}>
        {/* 侧栏：品牌 + 分组导航 */}
        <aside className={`${route === '/coding' ? 'hidden md:flex' : 'flex'} w-60 shrink-0 flex-col border-r border-line`}>
          <div className="flex items-center gap-2.5 px-4 py-4">
            <img
              src="/icon-64x64.png"
              alt={BRAND.name}
              draggable={false}
              className="h-7 w-7 shrink-0 rounded-md"
            />
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold">{BRAND.name}</span>
              <span className="block truncate text-2xs text-faint">{BRAND.tagline}</span>
            </span>
          </div>

          <nav
            aria-label="主导航"
            className="scrollbar-slim min-h-0 flex-1 overflow-y-auto px-2.5 pb-4"
          >
            {NAV_GROUPS.map((g) => {
              const items = NAV.filter((n) => n.group === g)
              if (!items.length) return null
              return (
                <div key={g} className="mb-1.5">
                  <p className="ui-label mb-1 px-2.5 pt-2">{g}</p>
                  {items.map((n) => {
                    const on = n.path === active
                    return (
                      <a
                        key={n.path}
                        href={`#${n.path}`}
                        aria-current={on ? 'page' : undefined}
                        className={`mb-0.5 flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm transition ${
                          on
                            ? 'bg-accent-soft font-semibold text-fg'
                            : 'text-muted hover:bg-fg/5 hover:text-fg'
                        }`}
                      >
                        <ShellIcon
                          name={n.icon}
                          className={`h-4 w-4 shrink-0 ${on ? 'text-accent' : ''}`}
                        />
                        <span className="min-w-0 flex-1 truncate">{n.label}</span>
                      </a>
                    )
                  })}
                </div>
              )
            })}
          </nav>

          <div className="border-t border-line px-2.5 py-2.5">
            {userSlot({ placement: 'up', withName: true })}
          </div>
        </aside>

        {/* 右列：工具条 + 内容 */}
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {route === '/coding' && !hideChrome && <nav aria-label="主导航" className="scrollbar-hide flex shrink-0 items-center gap-4 overflow-x-auto border-b border-line px-4 py-3 md:hidden">{NAV.map(item => <a key={item.path} href={`#${item.path}`} aria-current={active === item.path ? 'page' : undefined} className={`shrink-0 whitespace-nowrap text-xs ${active === item.path ? 'font-semibold text-fg' : 'text-muted'}`}>{item.label}</a>)}</nav>}
          {!hideChrome && (
            <header className="relative z-30 flex shrink-0 items-center gap-3 border-b border-line px-6 py-2.5">
              <span className="text-sm font-semibold">
                {pageNav?.label ?? '首页'}
              </span>
              <span className="ui-label">{pageNav?.en}</span>
              <span className="flex-1" />
              {tools}
              {route === '/coding' && <div className="md:hidden">{userSlot({ placement: 'down', withName: false })}</div>}
            </header>
          )}
          <main
            ref={mainRef}
            className={
              fullBleed
                ? 'min-h-0 flex-1'
                : 'scrollbar-slim min-h-0 flex-1 overflow-y-auto'
            }
          >
            {fullBleed ? children : <div className="mx-auto w-full max-w-[1180px]">{children}</div>}
          </main>
        </div>
      </div>
    )
  }
}
