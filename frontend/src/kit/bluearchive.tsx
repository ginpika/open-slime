/**
 * BlueArchive Kit —— 蔚蓝档案 · 数字街头
 *
 * 与 flat / endfield 的本质差异：
 *  · 章法从「网格铺满」改成「窄栏卡片流 + 大留白」，信息密度明显更低；
 *  · 每块内容是一张"档案卡"：冷灰蓝纸底上的纯白胶囊卡（--ui-card-fill），
 *    层次 = 细描边（border-line）+ 小而软的蓝调投影（--ui-e1），不是大蓝晕；
 *  · 标题与数值走粗斜体（由 --ui-heading-style 令牌驱动）；次级文本保持
 *    常规体——斜体只属于"被强调"的位置（demo 排版规范）；
 *  · 比例用圆角进度条 + 青→浅青渐变（--c-cyan-light），而非发灰的单色；
 *  · 页面标题上方有"光环"母题（BA 最核心的识别符号，-9° 倾斜 + 辉光）。
 */
import type { CSSProperties, ReactNode } from 'react'
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

/** 卡片底：浅档纯白 / 深档比底色亮一档（layers 注册，非 BA 主题回退 --c-bg） */
const CARD_FILL = 'var(--ui-card-fill, var(--c-bg))'

/** 渐变伙伴色：青 → 浅青（深档自动提亮），替代会发灰的 secondary */
const GRAD = 'linear-gradient(90deg, var(--c-accent), var(--c-cyan-light, var(--c-accent)))'

/** 光环：BA 的母题，出现在标题与品牌之上（-9° 倾斜 + 辉光 + 真椭圆） */
function Halo({ className = '' }: { className?: string }) {
  return (
    <span
      className={`pointer-events-none absolute rounded-[50%] border-2 border-accent ${className}`}
      style={{
        transform: 'rotate(-9deg)',
        boxShadow: '0 0 10px color-mix(in srgb, var(--c-accent) 45%, transparent)',
      }}
      aria-hidden="true"
    />
  )
}

/** 小节标：青色渐变短棒（BA 的 section marker，demo .sec-h .bar） */
function SecMark() {
  return (
    <span
      className="h-4 w-[5px] shrink-0 rounded-full"
      style={{ background: 'linear-gradient(180deg, var(--c-accent), var(--c-cyan-light, var(--c-accent)))' }}
      aria-hidden="true"
    />
  )
}

function Page({ title, en, meta, children }: PageProps) {
  return (
    // 章法差异：BA 收窄到 860px 单列卡片流（Endfield/flat 则铺满）
    <section className="mx-auto flex w-full max-w-[860px] flex-col gap-6 pt-4">
      <header className="relative flex flex-wrap items-end justify-between gap-4 px-1">
        <div className="relative min-w-0">
          <Halo className="-top-3.5 left-1 h-2.5 w-8" />
          <p className="ui-label">{en}</p>
          <h1 className="ui-heading mt-1 text-2xl">{title}</h1>
        </div>
        {meta}
      </header>
      {children}
    </section>
  )
}

function Panel({ title, meta, className = '', children }: PanelProps) {
  return (
    <div
      className={`flex min-w-0 flex-col rounded-[var(--ui-r-card)] border-[1.5px] border-line${className ? ` ${className}` : ''}`}
      style={{ background: CARD_FILL, boxShadow: 'var(--ui-e1)' }}
    >
      <div className="flex items-center gap-2.5 px-4 pb-2.5 pt-3.5">
        <SecMark />
        <h2 className="ui-heading text-md">{title}</h2>
        <span className="flex-1" />
        {meta}
      </div>
      <div className="min-w-0 flex-1 px-4 pb-4">{children}</div>
    </div>
  )
}

function Stat({ label, value, unit, hint, progress, tone = 'default', hero }: StatProps) {
  const color =
    tone === 'accent' ? 'var(--c-accent)' : tone === 'muted' ? 'var(--c-muted)' : 'var(--c-fg)'
  return (
    <div
      className="relative min-w-[120px] flex-1 rounded-[var(--ui-r-md)] border-[1.5px] border-line px-3.5 py-3"
      style={{ background: CARD_FILL, boxShadow: 'var(--ui-e1)' }}
    >
      <p className="ui-label">{label}</p>
      <p
        className={`ui-heading mt-1 leading-none ${hero ? 'text-3xl' : 'text-2xl'}`}
        style={{ color, fontVariantNumeric: 'tabular-nums' }}
      >
        {value}
        {unit ? <span className="ml-1 text-xs font-bold text-muted">{unit}</span> : null}
      </p>
      {progress != null && (
        <div
          className="mt-2.5 h-1.5 w-full overflow-hidden rounded-full"
          style={{ background: 'color-mix(in srgb, var(--c-fg) 8%, transparent)' }}
        >
          <div
            className="h-full rounded-full"
            style={{
              width: `${progress}%`,
              background: GRAD,
            }}
          />
        </div>
      )}
      {hint ? <p className="mt-1.5 text-2xs text-faint">{hint}</p> : null}
    </div>
  )
}

function Range({ options, value, onChange }: RangeProps) {
  return (
    <div
      className="flex w-fit gap-1 rounded-full p-1"
      style={{ background: 'var(--c-accent-soft)' }}
    >
      {options.map((o) => {
        const on = o.value === value
        return (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            className={`rounded-full px-4 py-1.5 text-sm font-bold transition ${
              on ? '' : 'text-muted hover:text-fg'
            }`}
            style={
              on
                ? { background: CARD_FILL, color: 'var(--c-accent)', boxShadow: 'var(--ui-e1)' }
                : undefined
            }
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

function Bar({ name, value, sub, ratio }: BarProps) {
  const pct = Math.max(2, ratio * 100)
  return (
    <div>
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-sm font-bold text-fg" title={name}>
          {name}
        </span>
        <span
          className="ui-heading text-sm"
          style={{ fontVariantNumeric: 'tabular-nums' }}
        >
          {value}
        </span>
        {sub ? (
          <span className="text-2xs font-bold text-faint">{sub}</span>
        ) : null}
      </div>
      <div
        className="mt-1.5 h-2 w-full overflow-hidden rounded-full"
        style={{ background: 'color-mix(in srgb, var(--c-fg) 8%, transparent)' }}
      >
        <div
          className="h-full rounded-full"
          style={{
            width: `${pct}%`,
            background: GRAD,
            boxShadow: '0 0 8px color-mix(in srgb, var(--c-accent) 30%, transparent)',
          }}
        />
      </div>
    </div>
  )
}

function Note({ children, tone = 'info' }: NoteProps) {
  const warn = tone === 'warn'
  return (
    <div
      className="rounded-[var(--ui-r-md)] border-l-4 py-3 pl-3.5 pr-4"
      style={{
        background: warn ? 'var(--c-danger-soft)' : 'var(--c-accent-soft)',
        borderLeftColor: warn ? 'var(--c-danger)' : 'var(--c-accent)',
      }}
    >
      <p className="text-sm leading-relaxed font-medium text-muted">{children as ReactNode}</p>
    </div>
  )
}

function Toolbar({ left, right, className = '' }: ToolbarProps) {
  return (
    <div
      className={`flex flex-wrap items-center justify-between gap-3${className ? ` ${className}` : ''}`}
    >
      <div className="min-w-0 text-sm font-bold text-muted">{left}</div>
      <div className="flex shrink-0 items-center gap-2">{right}</div>
    </div>
  )
}

/** 步骤条：圆形编号 + 标签，当前步实底青（进度感来自实底对比，不发光） */
function Steps({ items }: StepsProps) {
  return (
    <ol className="flex flex-wrap items-center gap-2">
      {items.map((it, i) => (
        <li key={it.label} className="flex items-center gap-2">
          <span
            className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-2xs font-black ${
              it.state === 'todo' ? 'text-muted' : ''
            }`}
            style={
              it.state === 'current'
                ? {
                    background: 'var(--c-accent)',
                    color: 'var(--c-accent-fg)',
                    boxShadow: 'var(--ui-e1)',
                  }
                : it.state === 'done'
                  ? { background: 'var(--c-accent-soft)', color: 'var(--c-accent)' }
                  : { background: 'var(--c-surface-container-high)' }
            }
          >
            {it.state === 'done' ? '✓' : i + 1}
          </span>
          <span
            className={`text-sm font-bold ${
              it.state === 'todo' ? 'text-faint' : it.state === 'current' ? 'text-fg' : 'text-muted'
            }`}
          >
            {it.label}
          </span>
        </li>
      ))}
    </ol>
  )
}

/** 徽章：胶囊 + 实底/浅底（斜体留给标题与按钮，徽章保持正体） */
function Badge({ children, tone = 'default' }: BadgeProps) {
  const style: CSSProperties =
    tone === 'default'
      ? { background: 'var(--c-surface-container-high)', color: 'var(--c-muted)' }
      : tone === 'accent'
        ? { background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }
        : tone === 'ok'
          ? { background: 'var(--c-ok-soft)', color: 'var(--c-ok)' }
          : tone === 'warn'
            ? { background: 'var(--c-warn-soft)', color: 'var(--c-warn)' }
            : { background: 'var(--c-danger-soft)', color: 'var(--c-danger)' }
  return (
    <span
      className="inline-flex shrink-0 items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-2xs font-bold"
      style={style}
    >
      {children}
    </span>
  )
}

/** 列表行：无分隔线 —— 用圆角块 + 间隙分组（与 flat/Endfield 的表格感形成对照） */
function Row({ children, href, onClick, className = '', disabled }: RowProps) {
  const cls = `flex w-full items-center rounded-[var(--ui-r-md)] px-3.5 py-3 text-left transition hover:bg-accent-soft${className ? ` ${className}` : ''}`
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

/** 空态：粉彩卡 + 圆点（不是虚线框） */
function Empty({ children }: EmptyProps) {
  return (
    <div
      className="flex items-start gap-2.5 rounded-[var(--ui-r-md)] px-4 py-4"
      style={{ background: 'var(--c-accent-soft)' }}
    >
      <span
        className="mt-1 h-3 w-3 shrink-0 rounded-full"
        style={{ background: 'var(--c-accent)' }}
      />
      <p className="text-sm leading-relaxed font-medium text-muted">{children}</p>
    </div>
  )
}

/** 网格单元：白卡 + 细描边 + 悬浮上移描边转青；虚位用粉彩虚线 */
function Tile({ children, href, dashed, onClick, className = '' }: TileProps) {
  if (dashed) {
    const cls = `flex min-h-[104px] flex-col items-center justify-center gap-2 rounded-[var(--ui-r-card)] border-2 border-dashed text-sm font-bold text-muted transition hover:text-fg${className ? ` ${className}` : ''}`
    return (
      <a
        href={href}
        className={cls}
        style={{ borderColor: 'color-mix(in srgb, var(--c-accent) 35%, transparent)' }}
      >
        {children}
      </a>
    )
  }
  const cls = `flex flex-col rounded-[var(--ui-r-card)] border-[1.5px] border-line px-4 py-3.5 text-left transition hover:-translate-y-[var(--ui-lift,0px)] hover:border-accent${className ? ` ${className}` : ''}`
  const style = { background: CARD_FILL, boxShadow: 'var(--ui-e1)' }
  if (href)
    return (
      <a href={href} className={cls} style={style}>
        {children}
      </a>
    )
  if (onClick)
    return (
      <button type="button" onClick={onClick} className={cls} style={style}>
        {children}
      </button>
    )
  return (
    <div className={cls} style={style}>
      {children}
    </div>
  )
}

/** 表单字段：粗体标签（斜体只留给标题体系，中文仿斜体在小字号会发糊） */
function Field({ label, hint, children, wrap = true, className = '' }: FieldProps) {
  return (
    <label className={`flex flex-col gap-1.5${className ? ` ${className}` : ''}`}>
      <span className="text-sm font-bold text-muted">{label}</span>
      {wrap ? <Control>{children}</Control> : children}
      {hint ? <span className="text-2xs font-bold text-faint">{hint}</span> : null}
    </label>
  )
}

export const blueArchiveKit: PageKit = {
  id: 'bluearchive',
  // BA 的章法是「窄栏卡片流」：单列、大留白、指标横排换行
  statRow: 'flex flex-wrap gap-3',
  panelGrid: 'grid gap-5',
  panelWide: '',
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
