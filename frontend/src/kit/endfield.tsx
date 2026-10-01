/**
 * Endfield Kit —— 终末地 · 双登记簿页面组件
 *
 * 基准 = docs/endfield-style-demo.html v3。装饰纪律（与 demo 一致）：
 *  · 面板默认环是白 alpha（--c-line-strong），黄色只出现在：
 *    面板头的黄条 / 编号牌 / 激活态 / 悬停边缘 / 分段刻度 —— 绝不做大段底色；
 *  · 数值一律等宽 + 分段刻度条（demo .segbar），不用连续进度条；
 *  · 提示条与空态带黄黑斜纹警戒缘（demo .hazard），黑是固定黑不随主题反转；
 *  · 角标（.sh-brk）打在未被切角的两个角上，光晕只在深档存在（shell.css）；
 *  · 关键指标是描边网格大读数（demo .bigread），不是实底色块。
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

/** 发光 L 角标（避开被切掉的两个角；光晕由 scheme 决定） */
function Brackets({ tl = false }: { tl?: boolean }) {
  return (
    <span className={`sh-brk${tl ? ' tl-br' : ''}`} aria-hidden="true">
      <i />
      <i />
    </span>
  )
}

/** 分段刻度条：方块矩阵表达比例，on 格带黄光晕（demo .segbar） */
function Segs({ ratio, blocks = 14 }: { ratio: number; blocks?: number }) {
  const on = Math.round(Math.max(0, Math.min(1, ratio)) * blocks)
  return (
    <span className="flex gap-px" aria-hidden="true">
      {Array.from({ length: blocks }).map((_, i) => (
        <span
          key={i}
          className="h-2.5 flex-1"
          style={
            i < on
              ? { background: 'var(--c-accent)', boxShadow: 'var(--ui-e2)' }
              : { background: 'var(--c-line)' }
          }
        />
      ))}
    </span>
  )
}

/** 黄黑斜纹竖条：提示 / 空态的警戒缘 */
function HazardEdge({ danger = false }: { danger?: boolean }) {
  return <span className={danger ? 'sh-hazard-r-v' : 'sh-hazard-v'} aria-hidden="true" />
}

function Page({ title, en, meta, children }: PageProps) {
  return (
    <section className="flex flex-col gap-3.5">
      {/* 扇区头：黄条 + 编号 kicker + 900 标题，底部 2px 黄色实边（色彩真源：palette 种子） */}
      <header
        className="flex flex-wrap items-end justify-between gap-3 border-b-2 pb-3"
        style={{ borderColor: 'var(--c-accent)' }}
      >
        <div className="flex min-w-0 items-end gap-3">
          <span
            className="h-7 w-1.5 shrink-0"
            style={{
              background: 'var(--c-accent)',
              boxShadow: 'var(--ui-e2)',
            }}
          />
          <div className="min-w-0">
            <p className="ui-label">{en}</p>
            <h1 className="ui-heading mt-1 text-2xl">{title}</h1>
          </div>
        </div>
        <div className="flex items-center gap-3">{meta}</div>
      </header>
      {children}
    </section>
  )
}

function Panel({ title, meta, className = '', children }: PanelProps) {
  return (
    <div className={`ui-card relative${className ? ` ${className}` : ''}`}>
      <Brackets />
      <div className="ui-card__in">
        {/* 面板头：黄条 + 标题 + 右侧等宽备注（demo .in-head） */}
        <div className="flex items-center gap-2.5 border-b border-line px-3.5 py-2">
          <span
            className="h-3.5 w-1 shrink-0"
            style={{ background: 'var(--c-accent)', boxShadow: 'var(--ui-e2)' }}
          />
          <h2 className="ui-heading text-sm">{title}</h2>
          <span className="flex-1" />
          {meta ? <span className="mono text-2xs text-dim">{meta}</span> : null}
        </div>
        <div className="min-w-0 flex-1 p-3.5">{children}</div>
      </div>
    </div>
  )
}

/** 关键指标：描边网格大读数（demo .bigread），非实底色块 */
function Stat({ label, value, unit, hint, progress, tone = 'default', hero }: StatProps) {
  const color =
    tone === 'accent' ? 'var(--c-accent)' : tone === 'muted' ? 'var(--c-muted)' : 'var(--c-fg)'
  return (
    <div
      className="flex min-w-[124px] flex-col gap-2 px-3.5 py-3"
      style={{ border: '1px solid var(--c-line-strong)' }}
    >
      <p className="ui-label">{label}</p>
      <p
        className={`mono leading-none font-extrabold tracking-tight ${hero ? 'text-3xl' : 'text-2xl'}`}
        style={{ color }}
      >
        {value}
        {unit ? <span className="ml-1 text-xs font-normal text-muted">{unit}</span> : null}
      </p>
      {progress != null && <Segs ratio={progress / 100} />}
      {hint ? <p className="mono text-2xs text-dim">{hint}</p> : null}
    </div>
  )
}

function Range({ options, value, onChange }: RangeProps) {
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

function Bar({ name, value, sub, ratio, index }: BarProps) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline gap-2">
        <span className="mono shrink-0 text-2xs text-dim">
          {String(index + 1).padStart(2, '0')}
        </span>
        <span className="min-w-0 flex-1 truncate text-sm text-fg" title={name}>
          {name}
        </span>
        <span className="mono shrink-0 text-xs font-bold text-fg">{value}</span>
        {sub ? <span className="mono shrink-0 text-2xs text-dim">{sub}</span> : null}
      </div>
      <Segs ratio={ratio} />
    </div>
  )
}

/** 提示条：黄黑 / 红黑斜纹警戒缘 + 等宽正文（像设备警示标签） */
function Note({ children, tone = 'info' }: NoteProps) {
  return (
    <div className="flex items-stretch overflow-hidden" style={{ border: '1px solid var(--c-line)' }}>
      <HazardEdge danger={tone === 'warn'} />
      <p className="mono flex-1 px-3 py-2.5 text-xs leading-relaxed text-fg-2">
        {children as ReactNode}
      </p>
    </div>
  )
}

/** 工具条：上下细线夹住的一条"状态带" */
function Toolbar({ left, right, className = '' }: ToolbarProps) {
  return (
    <div
      className={`flex flex-wrap items-center justify-between gap-3 border-y border-line py-2${className ? ` ${className}` : ''}`}
    >
      <div className="mono min-w-0 text-2xs tracking-[0.14em] uppercase text-dim">{left}</div>
      <div className="flex shrink-0 items-center gap-2">{right}</div>
    </div>
  )
}

/** 步骤条：斜切编号牌（不是圆点），当前步填黄 */
function Steps({ items }: StepsProps) {
  return (
    <ol className="flex flex-wrap items-center gap-1.5">
      {items.map((it, i) => (
        <li key={it.label} className="flex items-center gap-1.5">
          <span
            className="mono flex items-center gap-2 px-2.5 py-1.5 text-2xs font-bold tracking-[0.12em] uppercase"
            style={
              it.state === 'current'
                ? { background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }
                : it.state === 'done'
                  ? { border: '1px solid var(--c-accent)', color: 'var(--c-accent)' }
                  : { border: '1px solid var(--c-line)', color: 'var(--c-dim)' }
            }
          >
            <span style={{ opacity: 0.6 }}>{String(i + 1).padStart(2, '0')}</span>
            {it.label}
          </span>
        </li>
      ))}
    </ol>
  )
}

/** 徽章：印章式（描边 + 等宽全大写，demo .stamp） */
function Badge({ children, tone = 'default' }: BadgeProps) {
  const style: CSSProperties =
    tone === 'accent'
      ? { background: 'var(--c-accent)', color: 'var(--c-accent-fg)' }
      : tone === 'ok'
        ? { border: '1px solid var(--c-ok)', color: 'var(--c-ok)' }
        : tone === 'warn'
          ? { border: '1px solid var(--c-warn)', color: 'var(--c-warn)' }
          : tone === 'danger'
            ? { border: '1px solid var(--c-danger)', color: 'var(--c-danger)' }
            : { border: '1px solid var(--c-line-strong)', color: 'var(--c-muted)' }
  return (
    <span
      className="mono inline-flex shrink-0 items-center whitespace-nowrap px-2 py-0.5 text-2xs font-bold tracking-wider uppercase"
      style={style}
    >
      {children}
    </span>
  )
}

/** 列表行：分隔线 + 悬停黄晕染 + 左侧黄色读数条（demo .tbl tbody tr:hover） */
function Row({ children, href, onClick, className = '', disabled }: RowProps) {
  const cls = `flex w-full items-center border-b border-line px-3 py-2.5 text-left transition last:border-b-0 hover:bg-accent-soft hover:shadow-[inset_3px_0_0_var(--c-accent)]${className ? ` ${className}` : ''}`
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

/** 空态：黄黑斜纹左缘 + 等宽说明（像设备上的标签） */
function Empty({ children }: EmptyProps) {
  return (
    <div
      className="flex items-stretch overflow-hidden"
      style={{ border: '1px dashed var(--c-line-strong)' }}
    >
      <HazardEdge />
      <p className="mono flex-1 px-3 py-4 text-xs leading-relaxed text-dim">{children}</p>
    </div>
  )
}

/** 网格单元：切角仪表块（角标 + 默认白 alpha 环，悬停切黄），虚位用斜纹占位 */
function Tile({ children, href, dashed, onClick, className = '' }: TileProps) {
  if (dashed) {
    const cls = `flex min-h-[104px] flex-col items-center justify-center gap-2 text-2xs font-bold tracking-[0.14em] uppercase text-dim transition hover:text-fg${className ? ` ${className}` : ''}`
    return (
      <a href={href} className={cls} style={{ border: '1px dashed var(--c-line-strong)' }}>
        {children}
      </a>
    )
  }
  const cls = `ui-card ui-card--interactive relative${className ? ` ${className}` : ''}`
  const inner = (
    <>
      <Brackets />
      <div className="ui-card__in p-3.5">{children}</div>
    </>
  )
  if (href)
    return (
      <a href={href} className={cls}>
        {inner}
      </a>
    )
  if (onClick)
    return (
      <button type="button" onClick={onClick} className={cls}>
        {inner}
      </button>
    )
  return <div className={cls}>{inner}</div>
}

/** 表单字段：黄刻度 + 等宽全大写标签（像设备上的旋钮标注） */
function Field({ label, hint, children, wrap = true, className = '' }: FieldProps) {
  return (
    <label className={`flex flex-col gap-1.5${className ? ` ${className}` : ''}`}>
      <span className="flex items-center gap-2">
        <span className="h-2.5 w-0.5" style={{ background: 'var(--c-accent)' }} />
        <span className="mono text-2xs font-bold tracking-[0.16em] uppercase text-dim">{label}</span>
      </span>
      {wrap ? <Control>{children}</Control> : children}
      {hint ? <span className="mono text-2xs text-dim">{hint}</span> : null}
    </label>
  )
}

export const endfieldKit: PageKit = {
  id: 'endfield',
  statRow: 'grid grid-cols-2 gap-2 sm:grid-cols-4',
  panelGrid: 'grid gap-3 lg:grid-cols-2',
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
