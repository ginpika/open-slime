/**
 * 默认主题面板（DefaultThemePanel）+ 主题菜单的共享文案与折叠逻辑。
 *
 * 两个页签：
 *   主题 —— 3 套语言 × 浅/深/跟随系统
 *   全局 —— 在任意主题之上覆盖主色 / 点缀色 / 底色 / 字体（delta，切主题保留）
 *
 * 面板自身也是主题驱动的：它的卡片、按钮、输入框与页面共用同一套令牌，
 * 所以在 Endfield 下这里是切角的、在 BlueArchive 下这里是胶囊的。
 * 三套菜单实现（本文件的 DefaultThemePanel / EndfieldThemePanel /
 * MonoThemePanel）与「主题 → 菜单」的映射分居两处：映射唯一登记于
 * theme/registry.tsx 的 THEMES.panel，本文件不再做按主题分流。
 */
import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { IconPalette, btnPrimary, btnQuiet } from '../components/ui'
import { normalizeHex } from './color'
import { FONT_STACKS, THEME_META } from './layers'
import { THEME_IDS } from './palette'
import { rawSeed, themeActions, useThemeState } from './store'
import type { SchemeMode, ThemeId } from './types'

export type Tab = 'theme' | 'global'

export const TABS: { id: Tab; label: string }[] = [
  { id: 'theme', label: '主题' },
  { id: 'global', label: '全局' },
]

export const SCHEMES: { id: SchemeMode; label: string }[] = [
  { id: 'light', label: '浅色' },
  { id: 'system', label: '跟随系统' },
  { id: 'dark', label: '深色' },
]

/** 字体预设栈：点一下即整体替换正文字体 */
export const FONT_PRESETS: { label: string; value: string }[] = [
  { label: '系统默认', value: FONT_STACKS.sans },
  { label: '衬线', value: 'Georgia,"Songti SC","SimSun",serif' },
  { label: '等宽', value: FONT_STACKS.mono },
]

/** 外点 / ESC 关闭（默认面板与终末地面板共用） */
export function useDismiss(
  open: boolean,
  onOpenChange: (v: boolean) => void,
  boxRef: RefObject<HTMLDivElement | null>,
) {
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) onOpenChange(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onOpenChange(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onOpenChange, boxRef])
}

export function DefaultThemePanel({
  open,
  onOpenChange,
  themeTabExtra,
  variant = 'plain',
}: {
  open: boolean
  onOpenChange: (next: boolean) => void
  /** 「主题」页尾部追加设定块（如 Tailwind Mono 的侧边栏布局开关） */
  themeTabExtra?: ReactNode
  /** 触发按钮形态：plain 跟随主题；chrome 用桌面框架标题栏的深色轨按钮样式 */
  variant?: 'plain' | 'chrome'
}) {
  const [tab, setTab] = useState<Tab>('theme')
  const st = useThemeState()
  const boxRef = useRef<HTMLDivElement>(null)
  useDismiss(open, onOpenChange, boxRef)

  const overridden =
    !!st.override.seed.primary ||
    !!st.override.seed.accent ||
    !!st.override.seed.base ||
    !!st.override.fontSans ||
    !!st.override.headingStyle

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        aria-label="主题"
        aria-expanded={open}
        title="主题与外观"
        onClick={() => onOpenChange(!open)}
        className={
          variant === 'chrome'
            ? 'df__toolbtn'
            : `ui-btn ui-btn--icon rounded-full border border-transparent${
                open ? ' bg-clip-padding bg-fg/10 text-fg' : ''
              }`
        }
      >
        <IconPalette className="h-4 w-4" />
      </button>

      {open && (
        <div className="ui-card ui-surface absolute right-0 top-[calc(100%+10px)] z-[60] w-[364px]">
          <div className="ui-card__in max-h-[80vh] overflow-y-auto scrollbar-slim p-4 text-left">
            {/* 页签 */}
            <div className="flex gap-5 border-b border-line pb-2.5">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTab(t.id)}
                  className={
                    t.id === tab
                      ? 'ui-heading text-sm underline decoration-fg underline-offset-4'
                      : 'text-sm font-medium text-faint transition hover:text-fg'
                  }
                >
                  {t.label}
                </button>
              ))}
              <span className="flex-1" />
              <span className="self-center text-2xs text-faint">
                {THEME_META[st.themeId].labelEn}
              </span>
            </div>

            {tab === 'theme' ? (
              <ThemeTab schemeMode={st.schemeMode} current={st.themeId} extra={themeTabExtra} />
            ) : (
              <GlobalTab overridden={overridden} />
            )}
          </div>
        </div>
      )}
    </div>
  )
}

/* ---------------------------------------------------------------- 主题页 */

function ThemeTab({
  schemeMode,
  current,
  extra,
}: {
  schemeMode: SchemeMode
  current: ThemeId
  /** 尾部追加设定块（主题特有配置） */
  extra?: ReactNode
}) {
  return (
    <div className="pt-3">
      {/* 外观档位 */}
      <div className="mb-3 flex items-center justify-between">
        <span className="ui-label">Appearance</span>
        <div className="flex gap-1">
          {SCHEMES.map((s) => (
            <button
              key={s.id}
              type="button"
              onClick={() => themeActions.setSchemeMode(s.id)}
              className={
                schemeMode === s.id
                  ? 'rounded-sm border border-fg px-2 py-0.5 text-2xs font-semibold text-fg'
                  : 'rounded-sm border border-line px-2 py-0.5 text-2xs text-faint transition hover:border-line-strong'
              }
            >
              {s.label}
            </button>
          ))}
        </div>
      </div>

      {/* 主题网格 */}
      <span className="ui-label">Themes</span>
      <div className="mt-2 grid grid-cols-2 gap-2.5">
        {THEME_IDS.map((id) => {
          const meta = THEME_META[id]
          const seed = rawSeed(id, 'light')
          const active = current === id
          return (
            <button
              key={id}
              type="button"
              onClick={() => themeActions.setTheme(id)}
              className={`block w-full rounded-sm border p-2 text-left transition ${
                active ? 'border-fg' : 'border-line hover:border-line-strong'
              }`}
            >
              {/* 预览：底色 + 三色点 */}
              <span
                className="block h-9 w-full rounded-xs border border-line/60"
                style={{ background: seed.base }}
              >
                <span className="flex h-full items-end gap-1 p-1">
                  {[seed.primary, seed.secondary, seed.accent].map((c, i) => (
                    <span
                      key={i}
                      className="h-3 w-3 rounded-xs border border-black/10"
                      style={{ background: c }}
                    />
                  ))}
                </span>
              </span>
              <span className="mt-1.5 flex items-baseline gap-1.5">
                <span className={`text-xs ${active ? 'font-semibold text-fg' : 'text-muted'}`}>
                  {meta.label}
                </span>
                <span className="text-2xs tracking-[0.1em] text-faint">{meta.labelEn}</span>
              </span>
            </button>
          )
        })}
      </div>

      <p className="mt-3 text-xs leading-relaxed text-faint">{THEME_META[current].desc}</p>
      <p className="mt-2 text-xs leading-relaxed text-faint">
        每套主题都自带浅色与深色两档；切主题不会清掉你在「全局」里的覆盖。
      </p>

      {extra}
    </div>
  )
}

/* ---------------------------------------------------------------- 全局页 */

function GlobalTab({ overridden }: { overridden: boolean }) {
  const st = useThemeState()
  const { seed } = st.override
  const base = rawSeed(st.themeId, st.scheme)

  return (
    <div className="pt-3">
      <p className="mb-3 text-xs leading-relaxed text-faint">
        覆盖是<b className="text-muted">增量</b>：只改你动过的项，其余仍由当前主题决定；切换主题后覆盖会保留。
      </p>

      <span className="ui-label">Colors</span>
      <div className="mt-1.5 divide-y divide-line">
        <ColorRow
          label="主色"
          hint="正文墨色"
          value={seed.primary ?? base.primary}
          modified={!!seed.primary}
          onChange={(hex) => themeActions.setOverride({ seed: { primary: hex } })}
          onClear={() => themeActions.setOverride({ seed: { primary: undefined } })}
        />
        <ColorRow
          label="点缀色"
          hint="按钮 / 链接"
          value={seed.accent ?? base.accent}
          modified={!!seed.accent}
          onChange={(hex) => themeActions.setOverride({ seed: { accent: hex } })}
          onClear={() => themeActions.setOverride({ seed: { accent: undefined } })}
        />
        <ColorRow
          label="底色"
          hint="页面背景"
          value={seed.base ?? base.base}
          modified={!!seed.base}
          onChange={(hex) => themeActions.setOverride({ seed: { base: hex } })}
          onClear={() => themeActions.setOverride({ seed: { base: undefined } })}
        />
      </div>

      <div className="mt-4">
        <span className="ui-label">Font</span>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {FONT_PRESETS.map((f) => {
            const active = (st.override.fontSans ?? FONT_STACKS.sans) === f.value
            return (
              <button
                key={f.label}
                type="button"
                onClick={() => themeActions.setOverride({ fontSans: f.value })}
                className={
                  active
                    ? 'rounded-sm border border-fg px-2 py-0.5 text-2xs font-semibold text-fg'
                    : 'rounded-sm border border-line px-2 py-0.5 text-2xs text-faint transition hover:border-line-strong'
                }
              >
                {f.label}
              </button>
            )
          })}
        </div>
        <input
          value={st.override.fontSans ?? ''}
          placeholder="自定义 font-family…"
          spellCheck={false}
          onChange={(e) => themeActions.setOverride({ fontSans: e.target.value || undefined })}
          className="mt-2 w-full rounded-sm border border-line bg-transparent px-2 py-1 font-mono text-xs text-fg outline-none transition placeholder:text-hint focus:border-secondary"
        />
      </div>

      <div className="mt-4">
        <span className="ui-label">Heading</span>
        <div className="mt-1.5 flex items-center gap-1.5">
          {(['default', 'normal', 'italic'] as const).map((v) => {
            const active = (st.override.headingStyle ?? 'default') === v
            const label = v === 'default' ? '跟随主题' : v === 'normal' ? '常规' : '斜体'
            return (
              <button
                key={v}
                type="button"
                onClick={() =>
                  themeActions.setOverride({
                    headingStyle: v === 'default' ? undefined : v,
                  })
                }
                className={
                  active
                    ? 'rounded-sm border border-fg px-2 py-0.5 text-2xs font-semibold text-fg'
                    : 'rounded-sm border border-line px-2 py-0.5 text-2xs text-faint transition hover:border-line-strong'
                }
              >
                {label}
              </button>
            )
          })}
        </div>
      </div>

      <div className="mt-4 flex items-center gap-2 border-t border-line pt-3">
        <button
          type="button"
          disabled={!overridden}
          onClick={() => themeActions.resetOverride()}
          className={`shrink-0 ${btnQuiet('rounded-sm')}`}
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
          className="shrink-0 text-xs text-faint underline underline-offset-4 transition hover:text-fg"
        >
          恢复出厂
        </button>
      </div>

      {overridden && (
        <p className="mt-2 text-xs text-hint">
          已应用覆盖。当前主题为「{THEME_META[st.themeId].label}」。
        </p>
      )}
      <p className="mt-2 text-xs leading-relaxed text-faint">
        主色=文字，点缀色=按钮与链接，底色=背景；线条与灰阶由这三色自动派生。
      </p>
    </div>
  )
}

/** 单行取色：取色器 + hex 输入 + 已覆盖标记 */
function ColorRow({
  label,
  hint,
  value,
  modified,
  onChange,
  onClear,
}: {
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
    <div className="flex items-center gap-2.5 py-2">
      <span className="w-16 shrink-0 text-xs text-muted">{label}</span>
      <input
        type="color"
        value={norm ?? '#000000'}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        className="h-6 w-8 shrink-0 cursor-pointer rounded-sm border border-line bg-transparent p-0.5"
      />
      <input
        value={text}
        spellCheck={false}
        onChange={(e) => {
          setText(e.target.value)
          const n = normalizeHex(e.target.value)
          if (n) onChange(n)
        }}
        onBlur={() => setText(value)}
        className="w-20 shrink-0 rounded-sm border border-line bg-transparent px-2 py-1 font-mono text-xs text-fg outline-none transition focus:border-secondary"
      />
      <span className="min-w-0 flex-1 truncate text-2xs text-faint">{hint}</span>
      {modified && (
        <button
          type="button"
          onClick={onClear}
          title="恢复主题默认"
          className="shrink-0 text-2xs text-faint underline underline-offset-2 transition hover:text-fg"
        >
          重置
        </button>
      )}
    </div>
  )
}

// 保留导出以便后续扩展（如表单里的单按钮触发）
export { btnPrimary }
