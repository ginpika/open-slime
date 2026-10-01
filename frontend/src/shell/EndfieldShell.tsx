/**
 * Endfield 外壳（终末地 · 双登记簿）
 *
 * 登记簿 A · DARK（默认）＝ 碳黑战术玻璃 HUD：
 *   全息背景层（双层网格 / 辉光 / 线框球 / 等高线 / 扫描带 / 噪点 / CRT 纹）+
 *   顶栏读数条 + 条码 + 黄黑刻度带 + 暗底跑马灯状态条。
 * 登记簿 B · LIGHT ＝ 工业印刷体：同一结构，CSS 按 [data-scheme='light']
 *   隐藏全息显影（shell.css），材质与阴影由令牌切换。
 *
 * 契约完全遵守 ShellProps：路由 / 用户 / 主题面板逻辑一行都没有重写。
 */
import type { CSSProperties } from 'react'
import { BRAND } from '../branding'
import { NAV, activePathOf } from './nav'
import { useClock } from './useClock'
import { useThemeState } from '../theme/store'
import type { ShellProps } from './types'

/** 条码宽度序列（固定值，避免每次渲染抖动） */
const BARCODE = [1, 2, 1, 3, 1, 1, 2, 1, 4, 1, 2, 1, 1, 3, 2, 1, 1, 2, 3, 1, 2, 1, 1, 4, 1]
/** 条码竖条高度（同样固定） */
const BAR_HEIGHT = [22, 24, 20, 26, 22, 23, 21, 25, 22, 24, 20, 22, 26, 23, 21, 24, 22, 25, 20, 23, 22, 24, 21, 26, 22]
/** 顶栏高（顶栏内容行引用）：Toast 的 fixed z-[70] 会被 main 的 relative z-20
    叠层困住压不过 z-30 顶栏，故经 --ui-toast-top 把 Toast 基准挪到 main 上缘之下 */
const HEADER_H = '3rem'

export function EndfieldShell({
  route,
  activePath,
  fullBleed,
  tools,
  userSlot,
  mainRef,
  children,
}: ShellProps) {
  const clock = useClock()
  const { scheme } = useThemeState()
  const active = activePath || activePathOf(route)

  return (
    <div
      className="relative flex h-screen flex-col overflow-hidden text-fg"
      style={{ '--ui-toast-top': `calc(${HEADER_H} + 1.25rem)` } as CSSProperties}
    >
      {/* ---------------- 全息背景层（浅档由 CSS 隐藏大部分显影） ---------------- */}
      <div className="sh-bg" style={{ background: 'var(--c-bg)' }} aria-hidden="true">
        {/* 网格显影区：只占视口下方 30%，上缘渐隐；内部网格沿对角线钟摆漂移 */}
        <div className="sh-gridzone">
          <div className="sh-bggrid" />
        </div>
        <div className="sh-glow" />
        <svg className="sh-orb" viewBox="0 0 400 400" fill="none">
          <g stroke="var(--c-accent)" strokeWidth=".7" opacity=".55">
            <circle cx="200" cy="200" r="150" />
            <ellipse cx="200" cy="200" rx="150" ry="48" />
            <ellipse cx="200" cy="200" rx="150" ry="98" />
            <ellipse cx="200" cy="200" rx="150" ry="135" />
            <ellipse cx="200" cy="200" rx="48" ry="150" />
            <ellipse cx="200" cy="200" rx="98" ry="150" />
            <ellipse cx="200" cy="200" rx="135" ry="150" />
          </g>
          <g stroke="var(--c-info)" strokeWidth=".6" opacity=".35">
            <circle cx="200" cy="200" r="178" />
            <circle cx="200" cy="200" r="196" />
          </g>
        </svg>
        {/* 地形等高线 */}
        <svg className="sh-topo" viewBox="0 0 600 420" fill="none" stroke="var(--c-accent)" strokeWidth=".8">
          <path d="M40 300 C 120 210 200 250 260 190 S 400 120 470 170 560 150 590 120" opacity=".5" />
          <path d="M60 340 C 150 250 230 290 290 230 S 430 160 500 210 570 190 592 165" opacity=".42" />
          <path d="M80 380 C 180 290 260 330 320 270 S 460 200 530 250 580 232 596 210" opacity=".34" />
          <path d="M20 260 C 100 170 180 210 240 150 S 380 80 450 130 540 110 570 80" opacity=".28" />
          <path d="M0 220 C 80 130 160 170 220 110 S 360 40 430 90 520 70 550 40" opacity=".2" />
        </svg>
        <div className="sh-grain" />
        <div className="sh-raster" />
      </div>

      {/* ---------------- 顶栏：品牌 + 横排编号导航 + 读数 + 工具 + 条码 ---------------- */}
      <header className="ui-surface relative z-30 shrink-0 border-b border-line">
        <div className="flex items-stretch" style={{ height: HEADER_H }}>
          {/* 品牌：直接展示 logo，简洁大气；深档加黄色圆底（工业铭牌） */}
          <div className="flex shrink-0 items-center border-r border-line px-4" title={BRAND.name}>
            <img
              src="/icon-64x64.png"
              alt={BRAND.name}
              draggable={false}
              className={`h-7 w-7 rounded-full${scheme === 'dark' ? ' p-1' : ''}`}
              style={scheme === 'dark' ? { background: 'var(--c-accent)' } : undefined}
            />
          </div>

          {/* 横排编号导航（demo .nav 章法）：序号 + 标签，竖线分隔；
              激活项 = 黄底黑字 + 光晕 + 3px 底条，悬停 = 黄晕染 */}
          <nav
            aria-label="主导航"
            className="scrollbar-hide flex min-w-0 flex-1 items-stretch overflow-x-auto"
          >
            {NAV.map((it, i) => {
              const on = it.path === active
              return (
                <a
                  key={it.path}
                  href={`#${it.path}`}
                  aria-current={on ? 'page' : undefined}
                  className={`relative flex shrink-0 items-center gap-1.5 border-r border-line px-3 transition ${
                    on
                      ? 'bg-accent font-bold text-accent-fg'
                      : 'text-muted hover:bg-accent-soft hover:text-fg'
                  }`}
                  style={on ? { boxShadow: 'var(--ui-e2)' } : undefined}
                >
                  <span className="mono text-2xs opacity-60">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                  <span className="whitespace-nowrap text-xs font-bold tracking-wide">
                    {it.label}
                  </span>
                  {on && (
                    <span
                      className="absolute bottom-0 left-0 -right-px h-[3px]"
                      style={{ background: 'var(--ui-nav-edge)' }}
                      aria-hidden="true"
                    />
                  )}
                </a>
              )
            })}
          </nav>

          {/* 读数条 —— 仅宽屏显示，与导航之间靠导航末项的竖线分隔 */}
          <div className="hidden items-stretch divide-x divide-line border-r border-line xl:flex">
            <Readout k="STATUS" v="NOMINAL" ok />
            <Readout k="LOCAL" v={clock} mono />
          </div>

          {/* 主题 + 用户 */}
          <div className="flex shrink-0 items-center gap-2 px-3">
            {tools}
            {userSlot}
          </div>

          {/* 条码 */}
          <div
            className="sh-bar hidden self-center border-l border-line px-3.5 2xl:flex"
            aria-hidden="true"
          >
            {BARCODE.map((w, i) => (
              <i key={i} style={{ width: `${w}px`, height: `${BAR_HEIGHT[i]}px` }} />
            ))}
          </div>
        </div>
      </header>

      {/* ---------------- 主体：满幅内容（导航已上移顶栏） ---------------- */}
      <main
        ref={mainRef}
        className={
          fullBleed
            ? 'relative z-20 min-h-0 min-w-0 flex-1'
            : 'scrollbar-slim relative z-20 min-h-0 min-w-0 flex-1 overflow-y-auto px-6 pb-10 pt-5'
        }
      >
        {children}
      </main>

      {/* ---------------- 底部跑马灯状态条：暗底细字 + 右侧黄色状态块 ---------------- */}
      <footer
        className="relative z-10 flex shrink-0 items-stretch overflow-hidden border-t border-line"
        style={{ background: 'var(--ui-card-fill, var(--c-bg))' }}
      >
        <div className="flex h-8 min-w-0 flex-1 items-center overflow-hidden">
          <div className="sh-marquee">
            {[0, 1].map((d) => (
              <span
                key={d}
                className="mono px-6 text-2xs font-bold tracking-[0.26em] text-dim uppercase"
              >
                {'// SYSTEM NOMINAL // ALL CHANNELS OPERATIONAL // '}
                <b className="font-extrabold text-accent">SECTOR G-07 CLEAR</b>
                {' // NEXT SWEEP 00:00:42 // '}
                {BRAND.nameUpper}
                {' INDUSTRIAL TERMINAL //'}
              </span>
            ))}
          </div>
        </div>
        <span className="flex h-8 shrink-0 items-center gap-2 bg-accent px-4">
          <i className="sh-blink h-1.5 w-1.5 bg-accent-fg" aria-hidden="true" />
          <span className="mono text-2xs font-extrabold tracking-[0.14em] text-accent-fg">
            UPLINK 99.2%
          </span>
        </span>
      </footer>
    </div>
  )
}

/** 顶栏读数单元：8.5px 上标签 + 等宽数值（分隔线由容器的 divide-x 负责） */
function Readout({ k, v, mono = false, ok = false }: { k: string; v: string; mono?: boolean; ok?: boolean }) {
  return (
    <div className="flex flex-col justify-center gap-0.5 px-4">
      <span className="ui-label" style={{ fontSize: '9px' }}>
        {k}
      </span>
      <span
        className={`text-xs font-extrabold tracking-[0.06em] ${mono ? 'mono' : ''}`}
        style={ok ? { color: 'var(--c-ok)' } : undefined}
      >
        {v}
      </span>
    </div>
  )
}
