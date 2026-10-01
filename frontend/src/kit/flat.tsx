/**
 * Flat Kit —— Old / Clean / MD3 共用（与外壳 kit 的分组保持一致）
 *
 * 章法：克制、留白规整、以描边与极浅填充建立层次；
 * 具体圆角/投影/字重/字号全部由令牌决定，所以这三个主题在这里只是"参数不同"。
 */
import type { ReactNode } from 'react'
import { Control } from '../components/ui'
import type {
  BadgeProps,
  BarProps,
  EmptyProps,
  FieldProps,
  NoteProps,
  PageKit,
  PageProps,
  PanelProps,
  RangeProps,
  RowProps,
  StatProps,
  StepsProps,
  TileProps,
  ToolbarProps,
} from './types'

function Page({ title, en, meta, children }: PageProps) {
  return (
    <section className="flex flex-col gap-5 pt-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-line pb-3">
        <div className="min-w-0">
          <h1 className="ui-heading text-xl">{title}</h1>
          <p className="ui-label mt-1">{en}</p>
        </div>
        {meta}
      </header>
      {children}
    </section>
  )
}

function Panel({ title, meta, className = '', children }: PanelProps) {
  return (
    <div className={`ui-card${className ? ` ${className}` : ''}`}>
      <div className="ui-card__in">
        <div className="flex items-center gap-2.5 border-b border-line px-4 py-3">
          <span className="h-3.5 w-1 shrink-0 rounded-full bg-accent" />
          <h2 className="ui-heading text-sm">{title}</h2>
          <span className="flex-1" />
          {meta}
        </div>
        <div className="min-w-0 flex-1 p-4">{children}</div>
      </div>
    </div>
  )
}

function Stat({ label, value, unit, hint, progress, tone = 'default', hero }: StatProps) {
  const color =
    tone === 'accent' ? 'text-accent' : tone === 'muted' ? 'text-muted' : 'text-fg'
  return (
    <div className="min-w-[104px]">
      <p className="ui-label">{label}</p>
      <p
        className={`mono mt-1.5 leading-none font-semibold ${hero ? 'text-3xl' : 'text-xl'} ${color}`}
      >
        {value}
        {unit ? <span className="ml-1 text-xs font-normal text-muted">{unit}</span> : null}
      </p>
      {progress != null && (
        <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-fg/10">
          <div className="h-full rounded-full bg-accent" style={{ width: `${progress}%` }} />
        </div>
      )}
      {hint ? <p className="mt-1.5 text-2xs text-faint">{hint}</p> : null}
    </div>
  )
}

function Range({ options, value, onChange }: RangeProps) {
  return (
    <div className="flex w-fit gap-1 rounded-full border border-line p-1">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => onChange(o.value)}
          className={`rounded-full px-3.5 py-1 text-sm font-semibold transition ${
            o.value === value ? 'bg-accent text-accent-fg' : 'text-muted hover:bg-fg/5 hover:text-fg'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function Bar({ name, value, sub, ratio }: BarProps) {
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="truncate text-muted" title={name}>
          {name}
        </span>
        <span className="mono shrink-0 text-fg">
          {value}
          {sub ? <span className="ml-2 text-xs font-normal text-faint">{sub}</span> : null}
        </span>
      </div>
      <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-fg/10">
        <div
          className="h-full rounded-full bg-accent"
          style={{ width: `${Math.max(2, ratio * 100)}%` }}
        />
      </div>
    </div>
  )
}

function Note({ children, tone = 'info' }: NoteProps) {
  const border = tone === 'warn' ? 'border-warn' : 'border-line'
  return (
    <p className={`rounded-md border border-dashed ${border} px-4 py-3 text-base leading-relaxed text-faint`}>
      {children as ReactNode}
    </p>
  )
}

function Toolbar({ left, right, className = '' }: ToolbarProps) {
  return (
    <div
      className={`flex flex-wrap items-center justify-between gap-3${className ? ` ${className}` : ''}`}
    >
      <div className="min-w-0 text-sm text-muted">{left}</div>
      <div className="flex shrink-0 items-center gap-2">{right}</div>
    </div>
  )
}

/** 步骤条：圆点 + 细连接线（最传统的表达） */
function Steps({ items }: StepsProps) {
  return (
    <ol className="flex flex-wrap items-center gap-3">
      {items.map((it, i) => (
        <li key={it.label} className="flex items-center gap-3">
          {i > 0 && <span className="h-px w-6 bg-line-strong" aria-hidden="true" />}
          <span className="flex items-center gap-2">
            <span
              className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-2xs font-semibold ${
                it.state === 'current'
                  ? 'bg-accent text-accent-fg'
                  : it.state === 'done'
                    ? 'border border-accent text-accent'
                    : 'border border-line text-faint'
              }`}
            >
              {it.state === 'done' ? '✓' : i + 1}
            </span>
            <span
              className={`text-sm ${
                it.state === 'current'
                  ? 'font-semibold text-fg'
                  : it.state === 'done'
                    ? 'text-muted'
                    : 'text-faint'
              }`}
            >
              {it.label}
            </span>
          </span>
        </li>
      ))}
    </ol>
  )
}

const BADGE_TONE: Record<string, string> = {
  default: 'border-line text-muted',
  accent: 'border-accent/40 bg-accent/10 text-accent',
  ok: 'border-ok/40 bg-ok/10 text-ok',
  warn: 'border-warn/40 bg-warn/10 text-warn',
  danger: 'border-danger/40 bg-danger/10 text-danger',
}

function Badge({ children, tone = 'default' }: BadgeProps) {
  return (
    <span
      className={`inline-flex shrink-0 items-center whitespace-nowrap rounded-full border px-2 py-0.5 text-2xs font-semibold ${BADGE_TONE[tone]}`}
    >
      {children}
    </span>
  )
}

/** 列表行：无圆角 + 底部分隔线（表格感） */
function Row({ children, href, onClick, className = '', disabled }: RowProps) {
  const cls = `flex w-full items-center border-b border-line px-3 py-3 text-left transition last:border-b-0 hover:bg-fg/5${className ? ` ${className}` : ''}`
  if (href)
    return (
      <a href={href} className={cls}>
        {children}
      </a>
    )
  if (onClick)
    return (
      <button type="button" onClick={onClick} disabled={disabled} className={cls}>
        {children}
      </button>
    )
  return <div className={cls}>{children}</div>
}

function Empty({ children }: EmptyProps) {
  return (
    <p className="rounded-md border border-dashed border-line px-4 py-6 text-base leading-relaxed text-faint">
      {children}
    </p>
  )
}

/** 网格单元：描边卡（dashed 时为虚线占位） */
function Tile({ children, href, dashed, onClick, className = '' }: TileProps) {
  const tag = href ? 'a' : onClick ? 'button' : 'div'
  if (dashed) {
    const cls = `flex min-h-[104px] flex-col items-center justify-center gap-2 rounded-[var(--ui-r-card)] border border-dashed border-line text-sm text-muted transition hover:border-line-strong hover:text-fg${className ? ` ${className}` : ''}`
    return (
      <a href={href} className={cls}>
        {children}
      </a>
    )
  }
  const cls = `ui-card ui-card--interactive${className ? ` ${className}` : ''}`
  const inner = <div className="ui-card__in p-4">{children}</div>
  if (tag === 'a')
    return (
      <a href={href} className={cls}>
        {inner}
      </a>
    )
  if (tag === 'button')
    return (
      <button type="button" onClick={onClick} className={cls}>
        {inner}
      </button>
    )
  return <div className={cls}>{inner}</div>
}

/** 表单字段：标签在上、控件在下（最传统的表单排布） */
function Field({ label, hint, children, wrap = true, className = '' }: FieldProps) {
  return (
    <label className={`block${className ? ` ${className}` : ''}`}>
      <span className="ui-label mb-1 block">{label}</span>
      {wrap ? <Control>{children}</Control> : children}
      {hint ? <span className="mt-1 block text-2xs text-hint">{hint}</span> : null}
    </label>
  )
}

export const flatKit: PageKit = {
  id: 'flat',
  statRow: 'flex flex-wrap items-end gap-x-10 gap-y-4',
  panelGrid: 'grid gap-5 lg:grid-cols-2',
  panelWide: 'lg:col-span-2',
  Page,
  Panel,
  Stat,
  Range,
  Bar,
  Note,
  Toolbar,
  Steps,
  Badge,
  Row,
  Empty,
  Tile,
  Field,
}
