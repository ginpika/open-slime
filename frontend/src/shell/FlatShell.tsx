/**
 * Flat 外壳（Old / Clean / MD3 共用）
 *
 * 为什么这三个主题**不**各自独立：它们是同一个"现代平面"设计家族，
 * 差异集中在颜色、圆角、投影与字重——这些恰好是令牌层能表达的部分。
 * 三者的真正差别是**排布**，所以用一个外壳 + 三个 variant 表达，
 * 避免为同家族写三份几乎相同的 DOM。
 *
 *   minimal → Old：顶部横排文字链（保持原有观感）
 *   sidebar → Clean：左侧分组侧栏 + 顶部工具条（典型 SaaS）
 *   rail    → MD3：Top app bar + Navigation rail + FAB
 *
 * 契约完全遵守 ShellProps。
 */
import { BRAND } from '../branding'
import { ShellIcon } from './icons'
import { NAV, NAV_GROUPS, activePathOf } from './nav'
import type { ShellProps } from './types'

export type FlatVariant = 'minimal' | 'sidebar' | 'rail'

export function FlatShell({
  variant,
  route,
  activePath,
  fullBleed,
  tools,
  userSlot,
  mainRef,
  children,
}: ShellProps & { variant: FlatVariant }) {
  const active = activePath || activePathOf(route)

  /* ------------------------------------------------------------ Old */
  if (variant === 'minimal') {
    return (
      <div className="flex h-screen flex-col overflow-hidden bg-bg text-fg antialiased">
        <header className="relative z-30 shrink-0 border-b border-line">
          <div className="mx-auto flex max-w-6xl items-center justify-between gap-6 px-6 py-4">
            <nav className="scrollbar-hide flex items-center gap-5 overflow-x-auto">
              {NAV.map((n) => {
                const on = n.path === active
                return (
                  <a
                    key={n.path}
                    href={`#${n.path}`}
                    aria-current={on ? 'page' : undefined}
                    className={
                      on
                        ? 'text-sm font-semibold text-fg underline decoration-fg underline-offset-8'
                        : 'text-sm font-medium text-faint underline-offset-8 transition hover:text-fg'
                    }
                  >
                    {n.label}
                  </a>
                )
              })}
            </nav>
            <div className="flex shrink-0 items-center gap-4 text-base">
              {tools}
              {userSlot}
            </div>
          </div>
        </header>
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
        <footer className="shrink-0 border-t border-line">
          <p className="mx-auto max-w-6xl px-6 py-6 text-sm text-faint">{BRAND.footer}</p>
        </footer>
      </div>
    )
  }

  /* --------------------------------------------------------- Clean */
  if (variant === 'sidebar') {
    return (
      <div className="flex h-screen overflow-hidden bg-bg text-fg">
        {/* 侧栏：品牌 + 分组导航 */}
        <aside className="flex w-60 shrink-0 flex-col border-r border-line">
          <div className="flex items-center gap-2.5 px-4 py-4">
            <span
              className="h-7 w-7 shrink-0 rounded-md"
              style={{ background: 'var(--c-accent)' }}
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

          <div className="flex items-center gap-2 border-t border-line px-3 py-3">
            {userSlot}
            <span className="min-w-0 flex-1 truncate text-2xs text-faint">{BRAND.footer}</span>
          </div>
        </aside>

        {/* 右列：工具条 + 内容 */}
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="relative z-30 flex shrink-0 items-center gap-3 border-b border-line px-6 py-2.5">
            <span className="text-sm font-semibold">
              {NAV.find((n) => n.path === active)?.label ?? '首页'}
            </span>
            <span className="ui-label">{NAV.find((n) => n.path === active)?.en}</span>
            <span className="flex-1" />
            {tools}
          </header>
          <main
            ref={mainRef}
            className={
              fullBleed
                ? 'min-h-0 flex-1'
                : 'scrollbar-slim min-h-0 flex-1 overflow-y-auto px-8 py-7'
            }
          >
            {fullBleed ? children : <div className="mx-auto w-full max-w-[1180px]">{children}</div>}
          </main>
        </div>
      </div>
    )
  }

  /* ---------------------------------------------------------- MD3 */
  const activeItem = NAV.find((n) => n.path === active)
  return (
    <div className="flex h-screen flex-col overflow-hidden bg-bg text-fg">
      {/* Top app bar */}
      <header
        className="relative z-30 flex shrink-0 items-center gap-3 border-b border-line px-5 py-3"
        style={{ background: 'var(--c-surface-container-low)' }}
      >
        <span
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-semibold"
          style={{ background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }}
        >
          {BRAND.name.slice(0, 1)}
        </span>
        <span className="min-w-0">
          <span className="block truncate text-md font-medium">{activeItem?.label ?? '首页'}</span>
          <span className="ui-label block">{activeItem?.en}</span>
        </span>
        <span className="flex-1" />
        <span
          className="hidden items-center gap-2 rounded-full px-4 py-2 text-sm text-faint md:flex"
          style={{ background: 'var(--c-surface-container-highest)' }}
        >
          <span aria-hidden="true">⌕</span>
          <span>搜索</span>
        </span>
        {tools}
        {userSlot}
      </header>

      <div className="flex min-h-0 flex-1">
        {/* Navigation rail */}
        <nav
          aria-label="主导航"
          className="scrollbar-slim flex w-20 shrink-0 flex-col items-center gap-1 overflow-y-auto py-3"
        >
          {NAV.map((n) => {
            const on = n.path === active
            return (
              <a
                key={n.path}
                href={`#${n.path}`}
                aria-current={on ? 'page' : undefined}
                className="flex w-16 flex-col items-center gap-1 py-1"
                title={n.label}
              >
                <span
                  className="flex h-8 w-14 items-center justify-center rounded-full transition"
                  style={
                    on
                      ? { background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }
                      : { color: 'var(--c-muted)' }
                  }
                >
                  <ShellIcon name={n.icon} className="h-4.5 w-4.5" />
                </span>
                <span className={`text-2xs ${on ? 'font-semibold text-fg' : 'text-muted'}`}>
                  {n.label}
                </span>
              </a>
            )
          })}
        </nav>

        <main
          ref={mainRef}
          className={
            fullBleed ? 'min-h-0 min-w-0 flex-1' : 'scrollbar-slim min-h-0 min-w-0 flex-1 overflow-y-auto px-6 py-6'
          }
        >
          {fullBleed ? children : <div className="mx-auto w-full max-w-[1120px]">{children}</div>}
        </main>
      </div>

      {/* MD3 FAB */}
      <a
        href="#/"
        className="fixed bottom-6 right-6 z-30 flex h-14 items-center gap-2 px-5 text-sm font-medium"
        style={{
          background: 'var(--c-accent)',
          color: 'var(--c-accent-fg)',
          borderRadius: 'var(--ui-r-lg)',
          boxShadow: 'var(--ui-e3)',
        }}
      >
        <span aria-hidden="true">＋</span>新建
      </a>
    </div>
  )
}
