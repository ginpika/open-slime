/**
 * BlueArchive 外壳（蔚蓝档案 · 数字街头 Y2K）
 *
 * 独立 kit 的理由（见对话中已声明的四条）：
 *  1. 导航形态改为「胶囊 Tab 条 + 圆形图标坞」——圆形容器/图标/标签的 DOM 结构本身不同；
 *  2. 新增粉彩光斑 + 网点装饰背景层，是新增 DOM；
 *  3. 排版层次不同：粗斜体大标题 + 贴纸式品牌区的节点结构；
 *  4. 容器节奏不同：糖果卡（大圆角 + 蓝调柔影 + 头图）与 flat 的描边卡不是同一套内边距体系。
 *
 * 契约完全遵守 ShellProps。
 */
import { BRAND } from '../branding'
import { ShellIconSolid } from './icons'
import { NAV, activePathOf } from './nav'
import type { ShellProps } from './types'

export function BlueArchiveShell({
  route,
  activePath,
  fullBleed,
  tools,
  userSlot,
  mainRef,
  children,
}: ShellProps) {
  const active = activePath || activePathOf(route)

  return (
    <div className="relative flex h-screen flex-col overflow-hidden text-fg">
      {/* ---------------- 粉彩背景层：方格纸 + 青/粉/黄光斑 + 角落网点 ---------------- */}
      <div className="sh-bg" style={{ background: 'var(--c-bg)' }} aria-hidden="true">
        <span className="sh-papergrid" />
        <span
          className="sh-blob"
          style={{
            width: 460,
            height: 460,
            left: -140,
            top: -140,
            background: 'color-mix(in srgb, var(--c-cyan-light, var(--c-accent)) 36%, transparent)',
          }}
        />
        <span
          className="sh-blob"
          style={{
            width: 360,
            height: 360,
            right: -100,
            top: 40,
            background: 'color-mix(in srgb, var(--c-pink, var(--c-accent)) 30%, transparent)',
          }}
        />
        <span
          className="sh-blob"
          style={{
            width: 420,
            height: 420,
            left: '36%',
            bottom: -190,
            background: 'color-mix(in srgb, var(--c-yellow, var(--c-accent)) 24%, transparent)',
          }}
        />
        <span className="sh-dots" style={{ left: -70, bottom: 90, width: 280, height: 280 }} />
        <span
          className="sh-dots sh-dots--pink"
          style={{ right: -50, bottom: 30, width: 240, height: 240 }}
        />
      </div>

      {/* ---------------- 顶栏：胶囊导航 + 圆形图标坞 ---------------- */}
      <header className="ui-surface relative z-30 shrink-0 border-b border-line">
        <div className="mx-auto flex max-w-[1280px] items-center gap-4 px-5 py-3">
          {/* 品牌：只留 brand icon + 小光环（BA 母题，-9° 倾斜 + 辉光），名称进 title/alt */}
          <a href="#/" className="group relative flex shrink-0 items-center" title={BRAND.name}>
            <span className="relative flex h-9 w-9 items-center justify-center">
              {/* 小光环右倾 + 右偏，正好浮在 logo 小脑袋上方 */}
              <span
                className="absolute -top-1.5 left-[60%] h-2.5 w-7 rounded-[50%] border-2 border-accent"
                style={{
                  transform: 'translateX(-50%) rotate(9deg)',
                  boxShadow: '0 0 8px color-mix(in srgb, var(--c-accent) 45%, transparent)',
                }}
              />
              <img
                src="/icon-64x64.png"
                alt={BRAND.name}
                className="h-8 w-8 rounded-[10px]"
              />
            </span>
          </a>

          {/* 胶囊导航条 */}
          <nav
            aria-label="主导航"
            className="scrollbar-hide flex min-w-0 flex-1 items-center gap-1.5 overflow-x-auto"
          >
            {NAV.map((it) => {
              const on = it.path === active
              return (
                <a
                  key={it.path}
                  href={`#${it.path}`}
                  aria-current={on ? 'page' : undefined}
                  className={`flex shrink-0 flex-col items-center gap-1 rounded-full px-3 py-1.5 transition ${
                    on ? 'ba-nav-item--on' : 'text-muted hover:text-fg'
                  }`}
                >
                  <ShellIconSolid name={it.icon} className="h-4 w-4" />
                  <span className="ba-nav-label">{it.label}</span>
                  {/* 指示线常驻占位（透明），激活项与未激活项高度一致 */}
                  <span className="ba-nav-dot" aria-hidden="true" />
                </a>
              )
            })}
          </nav>

          {/* 图标坞：主题面板 / 用户菜单等功能插槽 */}
          <div className="flex shrink-0 items-center gap-2">
            {tools}
            {userSlot({ placement: 'down', withName: false })}
          </div>
        </div>
      </header>

      {/* ---------------- 主体 ---------------- */}
      <main
        ref={mainRef}
        className={
          fullBleed
            ? 'relative z-10 min-h-0 flex-1'
            : 'scrollbar-slim relative z-10 min-h-0 flex-1 overflow-y-auto'
        }
      >
        {fullBleed ? (
          children
        ) : (
          <div className="mx-auto w-full max-w-[1160px] px-6 pb-16 pt-5">{children}</div>
        )}
      </main>

      {/* ---------------- 底部状态条：白底 + 虚线分隔（不是蓝色横幅） ---------------- */}
      <footer className="relative z-10 shrink-0">
        <div
          className="mx-auto flex max-w-[1280px] items-center gap-3 px-5 py-2"
          style={{
            background: 'var(--ui-card-fill, var(--c-bg))',
            borderTop: '2px dashed var(--c-line-strong)',
          }}
        >
          <span className="flex items-center gap-1.5">
            <span
              className="h-1.5 w-1.5 rounded-full"
              style={{
                background: 'var(--c-ok)',
                boxShadow: '0 0 6px color-mix(in srgb, var(--c-ok) 60%, transparent)',
              }}
            />
            <span className="text-2xs font-bold text-muted">系统正常</span>
          </span>
          <span className="text-2xs font-bold text-faint">
            {BRAND.footer}
          </span>
          <span className="mono ml-auto text-2xs text-faint">v4</span>
        </div>
      </footer>
    </div>
  )
}
