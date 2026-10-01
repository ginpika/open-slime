/**
 * 终末地专属主题菜单 —— 「外观终端」
 *
 * 与默认 ThemePanel 功能同构（主题库 / 明暗档 / 全局覆盖），但整个面板用
 * 终末地设计语言重写，作为该主题的展示橱窗。取材全部是现有词汇，不新增
 * 任何终末地专属 CSS 补丁：
 *  · 容器 = ui-card 大切角玻璃面板 + 发光 L 角标（sh-brk）+ 黄黑警戒带封底（sh-hazard）
 *  · 页签 = 顶栏编号导航式（01/02 + 竖线分隔 + 黄底激活 + 青黑指示条）
 *  · 选择器 = 描边分段刻度，激活段黄底黑字、等宽全大写（kit Range 章法）
 *  · 标签 = 黄刻度 + 等宽全大写（kit Field 章法）
 *  · 主题卡 = 色卡仓：种子底 + 三色横排 + 等宽编号
 *  · 提示条 = 黄黑斜纹警戒缘 + 等宽正文（kit Note 章法）
 *  · 按钮 = ui-btn 原语（endfieldBtn 登记簿令牌自动生效：黄主 / 黑次 / 红险）
 *  · 进场 = mech-in（steps 硬切分段展开，消费动效层 mech 令牌）
 */
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import FloatWindow from '../components/FloatWindow'
import { Control, IconPalette, btnDanger, btnQuiet } from '../components/ui'
import { normalizeHex } from './color'
import { FONT_STACKS, THEME_META } from './layers'
import { THEME_IDS } from './palette'
import { rawSeed, themeActions, useThemeState } from './store'
import type { SchemeMode, ThemeId } from './types'
import { FONT_PRESETS, SCHEMES, TABS, useDismiss, type Tab } from './ThemePanel'

export function EndfieldThemePanel({
  open,
  onOpenChange,
}: {
  /** 开合状态由 App 持有（与默认面板同契约：切主题重挂子树不丢状态） */
  open: boolean
  onOpenChange: (next: boolean) => void
}) {
  const [tab, setTab] = useState<Tab>('theme')
  const [configOpen, setConfigOpen] = useState(false)
  const st = useThemeState()
  const boxRef = useRef<HTMLDivElement>(null)
  useDismiss(open, onOpenChange, boxRef)

  const overridden =
    !!st.override.seed.primary ||
    !!st.override.seed.accent ||
    !!st.override.seed.base ||
    !!st.override.fontSans ||
    !!st.override.fontMono ||
    !!st.override.headingStyle

  return (
    <div ref={boxRef} className="relative">
      {/* 触发钮：与默认面板同款（圆形图标钮） */}
      <button
        type="button"
        aria-label="主题"
        aria-expanded={open}
        title="外观终端"
        onClick={() => onOpenChange(!open)}
        className={`ui-btn ui-btn--icon rounded-full border border-transparent${
          open ? ' bg-clip-padding bg-fg/10 text-fg' : ''
        }`}
      >
        <IconPalette className="h-4 w-4" />
      </button>

      {open && (
        /* mech-in 挂在无 clip 的定位层上：闸门式展开动画结束后恢复 ui-card 自身的大切角 */
        <div className="mech-in absolute right-0 top-[calc(100%+10px)] z-[60] w-[380px]">
          <div className="ui-card">
            {/* 发光 L 角标：打在未被切角的两个角上（光晕仅深档存在）；
                lift-bl 让左下角标抬到警戒带上方，不与封底警戒线重叠 */}
            <span className="sh-brk lift-bl" aria-hidden="true">
              <i />
              <i />
            </span>
            <div className="ui-card__in max-h-[80vh] text-left">
              {/* 面板头：黄条 + 标题 + 右侧等宽读数 + 主题配置入口（kit Panel 头章法） */}
              <header className="flex shrink-0 items-center gap-2.5 border-b border-line px-3.5 py-2.5">
                <span
                  className="h-3.5 w-1 shrink-0"
                  style={{ background: 'var(--c-accent)', boxShadow: 'var(--ui-e2)' }}
                />
                <h2 className="ui-heading text-sm">外观终端</h2>
                <span className="flex-1" />
                <span className="mono text-2xs text-dim">MOD://{THEME_META[st.themeId].labelEn}</span>
                <span className="h-3 w-px shrink-0" style={{ background: 'var(--c-line)' }} aria-hidden="true" />
                <button
                  type="button"
                  onClick={() => {
                    setConfigOpen(true)
                    onOpenChange(false)
                  }}
                  title="主题配置"
                  aria-label="打开主题配置"
                  className="flex h-6 w-6 shrink-0 items-center justify-center text-muted transition hover:bg-accent-soft hover:text-fg"
                  style={{
                    border: '1px solid var(--c-line)',
                    borderRadius: 'var(--ui-r-control)',
                  }}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3.5 w-3.5">
                    <path
                      d="M3 7h8.5M17.5 7H21M3 17h3.5M12.5 17H21"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                    />
                    <circle cx="14.5" cy="7" r="2.5" stroke="currentColor" strokeWidth="2" />
                    <circle cx="8" cy="17" r="2.5" stroke="currentColor" strokeWidth="2" />
                  </svg>
                </button>
              </header>

              {/* 编号页签：镜像顶栏导航（序号 + 标签 + 竖线 + 黄底激活 + 指示条） */}
              <nav aria-label="外观设置分区" className="flex shrink-0 border-b border-line">
                {TABS.map((t, i) => {
                  const on = t.id === tab
                  return (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => setTab(t.id)}
                      aria-current={on ? 'true' : undefined}
                      className={`relative flex flex-1 items-center justify-center gap-1.5 border-r border-line px-3 py-2 transition last:border-r-0 ${
                        on
                          ? 'bg-accent font-bold text-accent-fg'
                          : 'text-muted hover:bg-accent-soft hover:text-fg'
                      }`}
                    >
                      <span className="mono text-2xs opacity-60">
                        {String(i + 1).padStart(2, '0')}
                      </span>
                      <span className="whitespace-nowrap text-xs font-bold tracking-wide">
                        {t.label}
                      </span>
                      {on && (
                        <span
                          className="absolute bottom-0 left-0 -right-px h-[3px]"
                          style={{ background: 'var(--ui-nav-edge)' }}
                          aria-hidden="true"
                        />
                      )}
                    </button>
                  )
                })}
              </nav>

              {/* 内容区单独滚动，警戒带钉在面板底缘不跟着滚 */}
              <div className="scrollbar-slim min-h-0 flex-1 overflow-y-auto">
                {tab === 'theme' ? (
                  <EfThemeTab schemeMode={st.schemeMode} current={st.themeId} />
                ) : (
                  <EfGlobalTab overridden={overridden} />
                )}
              </div>

              {/* 黄黑警戒带封底：工业铭牌的包边 */}
              <div className="sh-hazard shrink-0" aria-hidden="true" />
            </div>
          </div>
        </div>
      )}

      {/* 主题配置窗口：沿用全局 FloatWindow（可拖动 / Esc / 关闭钮，无遮罩）。
          portal 到 body 渲染——宿主 header 带 backdrop-filter（ui-surface），
          留在子树内的 fixed 会被劫持为 header 的 containing block */}
      {configOpen &&
        createPortal(
          <FloatWindow
            title={
              <span className="flex min-w-0 items-center gap-2">
                <span
                  className="h-3 w-0.5 shrink-0"
                  style={{ background: 'var(--c-accent)' }}
                  aria-hidden="true"
                />
                <span className="truncate">主题配置</span>
                <span className="mono shrink-0 text-2xs tracking-[0.1em] text-faint">
                  CFG://ENDFIELD
                </span>
              </span>
            }
            onClose={() => setConfigOpen(false)}
            width={560}
          >
            {/* 配置项：主题色风格 / 窗口样式（滑块开关，切换即生效） */}
            <div className="flex flex-col gap-3">
              <EfSwitchRow
                label="主题色风格"
                en="THEME STYLE"
                hint="罗德岛档把点缀色切换为罗德岛蓝 #38C2FF；手动覆盖点缀色时优先生效。"
                readout={st.endfield.palette === 'rhodes' ? 'RHODES' : 'DEFAULT'}
                on={st.endfield.palette === 'rhodes'}
                onLabel="主题色风格：罗德岛"
                onColor="#38c2ff"
                onSoft="rgb(56 194 255 / .18)"
                onGlow="0 0 10px rgb(56 194 255 / .45)"
                onToggle={() =>
                  themeActions.setEndfieldConfig({
                    palette: st.endfield.palette === 'rhodes' ? 'default' : 'rhodes',
                  })
                }
              />

              {/* 窗口样式：默认令牌玻璃 / 工作台双色窗口（全局 FloatWindow 换装） */}
              <EfSwitchRow
                label="窗口样式"
                en="WINDOW STYLE"
                hint="工作台档将全部浮窗换装：深炭标题栏 #1C1B1D + 纯白内容区，深浅档统一。"
                readout={st.endfield.window === 'workbench' ? 'WORKBENCH' : 'DEFAULT'}
                on={st.endfield.window === 'workbench'}
                onLabel="窗口样式：工作台"
                onColor="#1C1B1D"
                onSoft="rgb(28 27 29 / .16)"
                onGlow="none"
                onToggle={() =>
                  themeActions.setEndfieldConfig({
                    window: st.endfield.window === 'workbench' ? 'default' : 'workbench',
                  })
                }
              />
            </div>

            {/* 更多配置项规划中：Empty 章法占位（黄黑警戒缘 + 等宽待机说明） */}
            <div
              className="mt-3 flex items-stretch overflow-hidden"
              style={{ border: '1px dashed var(--c-line-strong)' }}
            >
              <span className="sh-hazard-v" aria-hidden="true" />
              <p className="mono flex-1 px-4 py-4 text-center text-2xs leading-relaxed text-dim">
                更多配置项建设中 // CONFIG MODULE STANDBY
              </p>
            </div>
          </FloatWindow>,
          document.body,
        )}
    </div>
  )
}

/* ---------------------------------------------------------------- 主题页 */

function EfThemeTab({ schemeMode, current }: { schemeMode: SchemeMode; current: ThemeId }) {
  return (
    <div className="p-3.5">
      {/* 明暗档 */}
      <EfField label="MODE // 明暗档">
        <EfSeg
          options={SCHEMES.map((s) => ({ label: s.label, value: s.id }))}
          value={schemeMode}
          onChange={(v) => themeActions.setSchemeMode(v as SchemeMode)}
        />
      </EfField>

      {/* 主题库 */}
      <div className="mt-3.5">
        <EfField label="THEMES // 主题库" />
        <div className="mt-2 grid grid-cols-2 gap-2">
          {THEME_IDS.map((id, i) => {
            const meta = THEME_META[id]
            const seed = rawSeed(id, 'light')
            const active = current === id
            return (
              <button
                key={id}
                type="button"
                onClick={() => themeActions.setTheme(id)}
                className="block w-full p-1.5 text-left transition hover:bg-accent-soft"
                style={{
                  border: `1px solid ${active ? 'var(--c-accent)' : 'var(--c-line)'}`,
                  borderRadius: 'var(--ui-r-control)',
                  boxShadow: active ? 'var(--ui-e2)' : undefined,
                }}
              >
                {/* 色卡仓：种子底 + 三色横排（底部对齐像颜料仓） */}
                <span
                  className="block h-10 w-full"
                  style={{ background: seed.base, border: '1px solid var(--c-line)' }}
                >
                  <span className="flex h-full items-end gap-1 p-1">
                    {[seed.primary, seed.secondary, seed.accent].map((c, j) => (
                      <span
                        key={j}
                        className="h-3.5 flex-1"
                        style={{ background: c, boxShadow: 'inset 0 0 0 1px rgb(0 0 0 / .14)' }}
                      />
                    ))}
                  </span>
                </span>
                <span className="mt-1.5 flex items-baseline gap-1.5">
                  <span className="mono text-2xs font-bold text-dim">
                    {String(i + 1).padStart(2, '0')}
                  </span>
                  <span className={`text-xs ${active ? 'font-semibold text-fg' : 'text-muted'}`}>
                    {meta.label}
                  </span>
                  <span className="mono min-w-0 flex-1 truncate text-right text-2xs tracking-[0.08em] text-faint">
                    {meta.labelEn}
                  </span>
                </span>
              </button>
            )
          })}
        </div>
      </div>

      {/* 当前主题读数 */}
      <div className="mt-3 border-t border-line pt-2.5">
        <p className="mono text-2xs leading-relaxed text-dim">
          <b className="font-bold text-accent">&gt;&gt;</b> {THEME_META[current].desc}
        </p>
        <p className="mono mt-1.5 text-2xs leading-relaxed text-dim">
          每套主题自带浅 / 深两档；切换主题不会清除「全局」里的覆盖。
        </p>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------- 全局页 */

function EfGlobalTab({ overridden }: { overridden: boolean }) {
  const st = useThemeState()
  const { seed } = st.override
  const base = rawSeed(st.themeId, st.scheme)

  return (
    <div className="p-3.5">
      {/* 警示标签：黄黑警戒缘 + 等宽正文（设备警示标牌） */}
      <div
        className="mb-3 flex items-stretch overflow-hidden"
        style={{ border: '1px solid var(--c-line)' }}
      >
        <span className="sh-hazard-v" aria-hidden="true" />
        <p className="mono flex-1 px-3 py-2 text-2xs leading-relaxed text-fg-2">
          覆盖是<b className="text-fg">增量</b>：只改动过的项，其余仍由当前主题决定；切换主题后覆盖保留。
        </p>
      </div>

      {/* 色板 */}
      <EfField label="COLORS // 色板" />
      <div className="mt-1.5">
        <EfColorRow
          code="P-01"
          label="主色"
          hint="正文墨色"
          value={seed.primary ?? base.primary}
          modified={!!seed.primary}
          onChange={(hex) => themeActions.setOverride({ seed: { primary: hex } })}
          onClear={() => themeActions.setOverride({ seed: { primary: undefined } })}
        />
        <EfColorRow
          code="A-02"
          label="点缀色"
          hint="按钮 / 链接"
          value={seed.accent ?? base.accent}
          modified={!!seed.accent}
          onChange={(hex) => themeActions.setOverride({ seed: { accent: hex } })}
          onClear={() => themeActions.setOverride({ seed: { accent: undefined } })}
        />
        <EfColorRow
          code="B-03"
          label="底色"
          hint="页面背景"
          value={seed.base ?? base.base}
          modified={!!seed.base}
          onChange={(hex) => themeActions.setOverride({ seed: { base: hex } })}
          onClear={() => themeActions.setOverride({ seed: { base: undefined } })}
        />
      </div>

      {/* 字体 */}
      <div className="mt-4">
        <EfField label="FONT // 正文字体" />
        <div className="mt-1.5">
          <EfSeg
            options={FONT_PRESETS.map((f) => ({ label: f.label, value: f.value }))}
            value={st.override.fontSans ?? FONT_STACKS.sans}
            onChange={(v) => themeActions.setOverride({ fontSans: v })}
          />
          <Control className="mt-2">
            <input
              value={st.override.fontSans ?? ''}
              placeholder="自定义 font-family…"
              spellCheck={false}
              onChange={(e) =>
                themeActions.setOverride({ fontSans: e.target.value || undefined })
              }
              className="w-full bg-transparent px-2 py-1 font-mono text-xs text-fg outline-none"
            />
          </Control>
        </div>
      </div>

      {/* 标题字形 */}
      <div className="mt-4">
        <EfField label="HEAD // 标题字形" />
        <div className="mt-1.5">
          <EfSeg
            options={[
              { label: '跟随主题', value: 'default' },
              { label: '常规', value: 'normal' },
              { label: '斜体', value: 'italic' },
            ]}
            value={st.override.headingStyle ?? 'default'}
            onChange={(v) =>
              themeActions.setOverride({
                headingStyle: v === 'default' ? undefined : (v as 'normal' | 'italic'),
              })
            }
          />
        </div>
      </div>

      {/* 底部操作：工业按钮体系（ui-btn 原语自动吃 endfieldBtn 令牌） */}
      <div className="mt-4 flex items-center gap-2 border-t border-line pt-3">
        <button
          type="button"
          disabled={!overridden}
          onClick={() => themeActions.resetOverride()}
          className={`shrink-0 ${btnQuiet()}`}
        >
          清除覆盖
        </button>
        <span className="flex-1" />
        <button
          type="button"
          onClick={() => {
            if (window.confirm('恢复出厂设置将重置主题、外观与全部覆盖。继续？')) {
              themeActions.reset()
            }
          }}
          className={`shrink-0 ${btnDanger()}`}
        >
          恢复出厂
        </button>
      </div>

      {overridden && (
        <p className="mono mt-2 text-2xs text-hint">
          已应用覆盖。当前主题为「{THEME_META[st.themeId].label}」。
        </p>
      )}
      <p className="mono mt-2 text-2xs leading-relaxed text-dim">
        主色=文字 / 点缀色=按钮链接 / 底色=背景；线条与灰阶由三色自动派生。
      </p>
    </div>
  )
}

/* ---------------------------------------------------------------- 原语 */

/**
 * 配置行：黄刻度 + 标签 + 说明（左侧）与等宽读数 + 工业滑块开关（右侧）。
 * 激活色（onColor/onSoft/onGlow）由调用处给定（罗德岛蓝 / 工作台炭…），
 * 滑块位移走 --ui-ease-mech（steps 硬切到位）。
 */
function EfSwitchRow({
  label,
  en,
  hint,
  readout,
  on,
  onLabel,
  onColor,
  onSoft,
  onGlow,
  onToggle,
}: {
  label: string
  en: string
  hint: string
  readout: string
  on: boolean
  onLabel: string
  /** 激活态描边与滑块色 */
  onColor: string
  /** 激活态轨道染底 */
  onSoft: string
  /** 激活态轨道光晕（无需则 'none'） */
  onGlow: string
  onToggle: () => void
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="min-w-0">
        <span className="flex items-center gap-2">
          <span
            className="h-2.5 w-0.5 shrink-0"
            style={{ background: 'var(--c-accent)' }}
            aria-hidden="true"
          />
          <span className="text-xs font-bold text-fg">{label}</span>
          <span className="mono text-2xs tracking-[0.08em] text-faint">{en}</span>
        </span>
        <p className="mono mt-1 text-2xs leading-relaxed text-dim">{hint}</p>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <span className="mono text-2xs font-bold tracking-[0.12em] text-dim">{readout}</span>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={onLabel}
          onClick={onToggle}
          className="relative h-6 w-11 shrink-0 cursor-pointer"
          style={{
            border: `1px solid ${on ? onColor : 'var(--c-line-strong)'}`,
            borderRadius: 'var(--ui-r-control)',
            background: on ? onSoft : 'transparent',
            boxShadow: on ? onGlow : 'none',
            transition:
              'background var(--ui-dur-base) var(--ui-ease), border-color var(--ui-dur-base) var(--ui-ease), box-shadow var(--ui-dur-base) var(--ui-ease)',
          }}
        >
          <span
            className="absolute top-1/2 h-4 w-4 -translate-y-1/2"
            style={{
              left: on ? 'calc(100% - 18px)' : '2px',
              background: on ? onColor : 'var(--c-muted)',
              boxShadow: on
                ? 'inset 0 0 0 1px rgb(0 0 0 / .28)'
                : 'inset 0 0 0 1px rgb(0 0 0 / .1)',
              transition:
                'left var(--ui-dur-base) var(--ui-ease-mech), background var(--ui-dur-base) var(--ui-ease)',
            }}
          />
        </button>
      </div>
    </div>
  )
}

/** 黄刻度 + 等宽全大写标签（kit Field 章法；无控件包裹时只渲染标签行） */
function EfField({ label, children }: { label: string; children?: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="flex items-center gap-2">
        <span className="h-2.5 w-0.5" style={{ background: 'var(--c-accent)' }} />
        <span className="mono text-2xs font-bold tracking-[0.16em] uppercase text-dim">{label}</span>
      </span>
      {children}
    </div>
  )
}

/** 分段刻度选择器：描边 + 黄底激活段（kit Range 同款，theme 层独立轻量实现） */
function EfSeg({
  options,
  value,
  onChange,
}: {
  options: { label: string; value: string }[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <div className="flex w-fit" style={{ border: '1px solid var(--c-line-strong)' }}>
      {options.map((o) => {
        const on = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            className="mono px-3 py-1.5 text-xs font-bold tracking-wider uppercase transition hover:bg-accent-soft first:pl-3.5 last:pr-3.5"
            style={
              on
                ? { background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }
                : { color: 'var(--c-muted)' }
            }
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

/** 工业读数式取色行：料号 + 名称 + 取色器 + hex 等宽输入；已覆盖的料号亮黄 */
function EfColorRow({
  code,
  label,
  hint,
  value,
  modified,
  onChange,
  onClear,
}: {
  code: string
  label: string
  hint: string
  value: string
  modified: boolean
  onChange: (hex: string) => void
  onClear: () => void
}) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  const norm = normalizeHex(text)

  return (
    <div className="flex items-center gap-2.5 border-b border-line py-2 last:border-b-0">
      <span
        className={`mono w-8 shrink-0 text-2xs font-bold ${modified ? 'text-accent' : 'text-dim'}`}
        title={modified ? '已覆盖' : undefined}
      >
        {code}
      </span>
      <span className="flex w-16 shrink-0 flex-col">
        <span className="text-xs text-fg">{label}</span>
        <span className="mono text-2xs text-faint">{hint}</span>
      </span>
      <input
        type="color"
        value={norm ?? '#000000'}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        className="h-6 w-8 shrink-0 cursor-pointer border-0 bg-transparent p-0"
        style={{ borderRadius: 'var(--ui-r-control)' }}
      />
      <Control className="w-[86px] shrink-0">
        <input
          value={text}
          spellCheck={false}
          onChange={(e) => {
            setText(e.target.value)
            const n = normalizeHex(e.target.value)
            if (n) onChange(n)
          }}
          onBlur={() => setText(value)}
          aria-label={`${label} hex`}
          className="w-full bg-transparent px-2 py-1 font-mono text-xs text-fg outline-none"
        />
      </Control>
      {modified && (
        <button
          type="button"
          onClick={onClear}
          title="恢复主题默认"
          className="mono shrink-0 text-2xs font-bold tracking-wider text-faint uppercase underline underline-offset-2 transition hover:text-fg"
        >
          重置
        </button>
      )}
    </div>
  )
}
