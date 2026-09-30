/**
 * 主题配置面板：header 右侧入口。
 * 页签：预设（内置+自定义）｜自定义（双色/三色取色，实时生效）。
 */
import { useEffect, useRef, useState } from 'react'
import { normalizeHex } from './color'
import { resolveTokens } from './apply'
import { BUILTIN_PRESETS } from './presets'
import { themeActions, useThemeState } from './store'
import type { Appearance, ThemeDef, ThemeMode } from './types'
import { IconPalette, btnIcon, btnOutline, btnPrimary, btnQuiet } from '../components/ui'

type Tab = 'presets' | 'customize'

const TABS: { id: Tab; label: string }[] = [
  { id: 'presets', label: '预设' },
  { id: 'customize', label: '自定义' },
]

const APPEARANCES: { id: Appearance; label: string }[] = [
  { id: 'light', label: '浅色' },
  { id: 'system', label: '跟随系统' },
  { id: 'dark', label: '深色' },
]

/** 外观开关：浅色 / 跟随系统 / 深色（切换到上次使用的对应深浅主题） */
function AppearanceSwitch() {
  const state = useThemeState()
  return (
    <div className="flex items-center justify-between border-b border-line py-3">
      <span className="text-[11px] font-semibold uppercase tracking-[0.25em] text-faint">
        Appearance
      </span>
      <div className="flex gap-1">
        {APPEARANCES.map((a) => (
          <button
            key={a.id}
            type="button"
            onClick={() => themeActions.setAppearance(a.id)}
            className={
              state.appearance === a.id
                ? 'rounded-sm border border-fg px-2 py-1 text-[11px] font-semibold text-fg'
                : 'rounded-sm border border-line px-2 py-1 text-[11px] text-faint transition hover:border-line-strong'
            }
          >
            {a.label}
          </button>
        ))}
      </div>
    </div>
  )
}

export function ThemePanel() {
  const [open, setOpen] = useState(false)
  const [tab, setTab] = useState<Tab>('presets')
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        aria-label="主题"
        aria-expanded={open}
        title="主题"
        onClick={() => setOpen((v) => !v)}
        className={`${btnIcon('rounded-sm')} ${open ? 'bg-fg/10 text-fg' : ''}`}
      >
        <IconPalette className="h-4 w-4" />
      </button>
      {open && (
        <div className="absolute right-0 top-[calc(100%+10px)] z-50 max-h-[85vh] w-[360px] overflow-y-auto rounded-sm border border-line bg-bg p-5 text-left">
          <div className="flex gap-5 border-b border-line pb-3">
            {TABS.map((t) => (
              <button
                key={t.id}
                type="button"
                onClick={() => setTab(t.id)}
                className={
                  t.id === tab
                    ? 'text-[12px] font-semibold text-fg underline decoration-fg underline-offset-4'
                    : 'text-[12px] font-medium text-faint transition hover:text-fg'
                }
              >
                {t.label}
              </button>
            ))}
          </div>
          <AppearanceSwitch />
          <div className="pt-4">
            {tab === 'presets' && <PresetsTab />}
            {tab === 'customize' && <CustomizeTab />}
          </div>
        </div>
      )}
    </div>
  )
}

/* ---------------- 预设 ---------------- */

function PresetsTab() {
  const state = useThemeState()
  const all = [...BUILTIN_PRESETS, ...state.custom]
  return (
    <div>
      <p className="text-[11px] font-semibold uppercase tracking-[0.25em] text-faint">Presets</p>
      <div className="mt-3 grid grid-cols-2 gap-3">
        {all.map((t) => {
          const v = resolveTokens(t)
          const active = state.currentId === t.id
          return (
            <div key={t.id} className="relative">
              <button
                type="button"
                onClick={() => themeActions.select(t.id)}
                className={
                  active
                    ? 'block w-full rounded-sm border border-fg p-2 text-left'
                    : 'block w-full rounded-sm border border-line p-2 text-left transition hover:border-line-strong'
                }
              >
                <span className="block h-10 w-full rounded-xs" style={{ background: v['--c-bg'] }}>
                  <span className="flex h-full items-end gap-1 p-1">
                    {(['--c-primary', '--c-secondary', '--c-accent'] as const).map((k) => (
                      <span key={k} className="h-3 w-3 rounded-xs" style={{ background: v[k] }} />
                    ))}
                  </span>
                </span>
                <span className="mt-2 flex items-baseline justify-between gap-2">
                  <span className={active ? 'text-[12px] font-semibold text-fg underline underline-offset-4' : 'text-[12px] text-muted'}>
                    {t.name}
                  </span>
                </span>
              </button>
              {!t.builtin && (
                <button
                  type="button"
                  title="删除"
                  onClick={(e) => {
                    e.stopPropagation()
                    themeActions.deleteCustom(t.id)
                  }}
                  className="absolute right-1 top-1 rounded-sm px-1 text-[11px] text-faint transition hover:text-fg"
                >
                  ✕
                </button>
              )}
            </div>
          )
        })}
      </div>
      <p className="mt-4 text-[11px] leading-relaxed text-faint">
        取色即预览即生效；保存后的自定义主题会出现在上方网格。
      </p>
      <div className="mt-4 border-t border-line pt-4">
        <button
          type="button"
          onClick={() => {
            if (window.confirm('恢复默认将清空自定义主题。继续？')) {
              themeActions.resetAll()
            }
          }}
          className={btnQuiet('rounded-sm')}
        >
          恢复默认（清空自定义主题）
        </button>
      </div>
    </div>
  )
}

/* ---------------- 自定义 ---------------- */

/** 三色默认辅色（双色 → 三色切换时，从主色解绑辅线的起点色） */
const DEFAULT_SECONDARY = '#8b8f96'

/** 模式切换颜色迁移：→双色 辅色并入主色（主辅相同）；→三色 辅色解绑为中性默认 */
function switchColors(mode: ThemeMode, cur: ThemeDef['colors']): ThemeDef['colors'] {
  if (mode === 'duotone') return { ...cur, secondary: cur.primary }
  return { ...cur, secondary: cur.secondary === cur.primary ? DEFAULT_SECONDARY : cur.secondary }
}

function CustomizeTab() {
  const state = useThemeState()
  const draft = state.draft
  const [name, setName] = useState('')

  if (!draft) {
    return (
      <div>
        <p className="text-[11px] font-semibold uppercase tracking-[0.25em] text-faint">Customize</p>
        <p className="mt-3 text-[12px] leading-relaxed text-muted">
          主色 = 文字；辅色 = 线条（双色并入主色）；点缀色 = 按钮、链接与提示。底色自动派生，可覆写。
        </p>
        <button
          type="button"
          onClick={() => {
            themeActions.editDraft()
            setName('')
          }}
          className={`mt-4 ${btnOutline('rounded-sm')}`}
        >
          基于当前主题开始自定义
        </button>
      </div>
    )
  }

  const c = draft.colors
  const duotone = draft.mode === 'duotone'
  const patch = (p: Partial<ThemeDef['colors']>) => themeActions.patchDraft(p)

  return (
    <div>
      <div className="flex items-center justify-between">
        <p className="text-[11px] font-semibold uppercase tracking-[0.25em] text-faint">Customize</p>
        <div className="flex gap-1">
          {(['duotone', 'tritone'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => {
                if (draft.mode !== m) themeActions.patchDraft(switchColors(m, c), { mode: m })
              }}
              className={
                draft.mode === m
                  ? 'rounded-sm border border-fg px-2 py-1 text-[11px] font-semibold text-fg'
                  : 'rounded-sm border border-line px-2 py-1 text-[11px] text-faint transition hover:border-line-strong'
              }
            >
              {m === 'duotone' ? '双色' : '三色'}
            </button>
          ))}
        </div>
      </div>

      <div className="mt-2 divide-y divide-line">
        <ColorRow
          label="主色"
          value={c.primary}
          onChange={(hex) =>
            patch(duotone ? { primary: hex, secondary: hex } : { primary: hex })
          }
        />
        {!duotone && (
          <ColorRow label="辅色" value={c.secondary} onChange={(hex) => patch({ secondary: hex })} />
        )}
        <ColorRow label="点缀色" value={c.accent} onChange={(hex) => patch({ accent: hex })} />
        <div className="flex items-center gap-3 py-2">
          <span className="w-20 shrink-0 text-[12px] text-muted">底色</span>
          {c.base ? (
            <>
              <ColorRow inline value={c.base} onChange={(hex) => patch({ base: hex })} />
              <button
                type="button"
                onClick={() => patch({ base: undefined })}
                className="ml-auto text-[11px] text-faint underline underline-offset-4 transition hover:text-fg"
              >
                默认
              </button>
            </>
          ) : (
            <div className="flex gap-1">
              {([false, true] as const).map((dark) => (
                <button
                  key={String(dark)}
                  type="button"
                  onClick={() => patch({ dark })}
                  className={
                    c.dark === dark
                      ? 'rounded-sm border border-fg px-2 py-1 text-[11px] font-semibold text-fg'
                      : 'rounded-sm border border-line px-2 py-1 text-[11px] text-faint transition hover:border-line-strong'
                  }
                >
                  {dark ? '深底' : '浅底'}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="mt-4 flex items-center gap-2 border-t border-line pt-4">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="主题名称"
          className="min-w-0 flex-1 rounded-sm border border-line px-2 py-1.5 text-[12px] text-fg outline-none focus:border-secondary"
        />
        <button
          type="button"
          onClick={() => themeActions.saveDraft(name)}
          className={`shrink-0 ${btnPrimary('rounded-sm')}`}
        >
          保存
        </button>
        <button
          type="button"
          onClick={() => themeActions.cancelDraft()}
          className="shrink-0 text-[11px] text-faint underline underline-offset-4 transition hover:text-fg"
        >
          放弃
        </button>
      </div>
    </div>
  )
}

function ColorRow({
  label,
  value,
  onChange,
  inline,
}: {
  label?: string
  value: string
  onChange: (hex: string) => void
  inline?: boolean
}) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  const norm = normalizeHex(text)
  return (
    <div className={inline ? 'flex items-center gap-3' : 'flex items-center gap-3 py-2'}>
      {label && <span className="w-20 shrink-0 text-[12px] text-muted">{label}</span>}
      <input
        type="color"
        value={norm ?? '#000000'}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label ?? '颜色'}
        className="h-7 w-9 shrink-0 cursor-pointer rounded-sm border border-line bg-bg p-0.5"
      />
      <input
        value={text}
        spellCheck={false}
        onChange={(e) => {
          setText(e.target.value)
          const n = normalizeHex(e.target.value)
          if (n) onChange(n)
        }}
        onBlur={() => setText(norm ?? value)}
        className="w-24 shrink-0 rounded-sm border border-line px-2 py-1 font-mono text-[12px] text-fg outline-none focus:border-secondary"
      />
      <span className="h-4 min-w-4 flex-1 rounded-xs" style={{ background: norm ?? value }} />
    </div>
  )
}
