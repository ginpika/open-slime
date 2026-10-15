import { Control } from '../components/ui'
import { flatKit } from './flat'
import type { BadgeProps, BarProps, EmptyProps, FieldProps, NoteProps, PageKit, PageProps, PanelProps, RangeProps, RowProps, StatProps, StepsProps, TileProps, ToolbarProps } from './types'

function Page({ title, en, meta, children }: PageProps) {
  return <section className="astro-page"><header className="astro-page__head"><div><p className="astro-eyebrow">THE RESEARCH COLLECTION <span> / {en}</span></p><h1 className="ui-heading">{title}<span aria-hidden="true">.</span></h1></div><div className="astro-page__meta">{meta}</div></header>{children}</section>
}
function Panel({ title, meta, className = '', children }: PanelProps) {
  return <section className={`ui-card astro-panel ${className}`}><div className="ui-card__in"><header><span className="astro-diamond" aria-hidden="true" /><h2 className="ui-heading">{title}</h2><span className="astro-panel__meta">{meta}</span></header><div className="astro-panel__body">{children}</div></div></section>
}
function Stat({ label, value, unit, hint, progress, tone = 'default', hero }: StatProps) {
  return <div className={`astro-stat astro-stat--${tone}`}><p className="astro-eyebrow">{label}</p><p className={`astro-stat__value${hero ? ' astro-stat__value--hero' : ''}`}>{value}{unit && <span>{unit}</span>}</p>{progress != null && <div className="astro-meter"><i style={{ width: `${Math.max(0, Math.min(100, progress))}%` }} /></div>}{hint && <p className="astro-stat__hint">{hint}</p>}</div>
}
function Range({ options, value, onChange }: RangeProps) {
  return <div className="astro-range" role="group" aria-label="视图切换">{options.map(o => <button key={o.value} type="button" aria-pressed={value === o.value} onClick={() => onChange(o.value)}>{o.label}</button>)}</div>
}
function Bar({ name, value, sub, ratio, index }: BarProps) {
  return <div className="astro-bar"><div><span className="astro-bar__index">{String(index + 1).padStart(2, '0')}</span><span className="truncate">{name}</span><strong>{value}</strong>{sub && <small>{sub}</small>}</div><div className="astro-meter"><i style={{ width: `${Math.max(0, Math.min(100, ratio * 100))}%` }} /></div></div>
}
function Badge({ children, tone = 'default' }: BadgeProps) {
  return <span className={`astro-badge astro-badge--${tone}`}>{children}</span>
}
function Note({ children, tone = 'info' }: NoteProps) {
  return <div className={`astro-note astro-note--${tone}`}><span aria-hidden="true">{tone === 'warn' ? '!' : '✧'}</span><div>{children}</div></div>
}
function Empty({ children }: EmptyProps) {
  return <div className="astro-empty"><span aria-hidden="true">✧</span><p>{children}</p></div>
}
function Field({ label, hint, children, wrap = true, className = '' }: FieldProps) {
  return <label className={`astro-field ${className}`}><span>{label}</span>{wrap ? <Control>{children}</Control> : children}{hint && <small>{hint}</small>}</label>
}
function Steps({ items }: StepsProps) {
  return <ol className="astro-steps">{items.map((it, i) => <li key={it.label} data-state={it.state}><span>{it.state === 'done' ? '✓' : String(i + 1).padStart(2, '0')}</span>{it.label}</li>)}</ol>
}
function Row({ children, href, onClick, className = '', disabled }: RowProps) {
  const cls = `astro-row ${className}`
  if (href) return <a className={cls} href={href}>{children}</a>
  if (onClick) return <button className={cls} type="button" onClick={onClick} disabled={disabled}>{children}</button>
  return <div className={cls}>{children}</div>
}
function Tile({ children, href, onClick, dashed, className = '' }: TileProps) {
  const cls = `ui-card astro-tile${href || onClick ? ' ui-card--interactive' : ''}${dashed ? ' astro-tile--dashed' : ''} ${className}`
  const content = <div className="ui-card__in p-5">{children}</div>
  if (href) return <a className={cls} href={href}>{content}</a>
  if (onClick) return <button type="button" className={cls} onClick={onClick}>{content}</button>
  return <div className={cls}>{content}</div>
}
function Toolbar({ left, right, className = '' }: ToolbarProps) {
  return <div className={`astro-toolbar ${className}`}><div>{left}</div><div>{right}</div></div>
}
export const astrolabeKit: PageKit = {
  ...flatKit, id: 'astrolabe',
  statRow: 'astro-stats', panelGrid: 'grid gap-6 lg:grid-cols-2', panelWide: 'lg:col-span-2',
  Page, Panel, Stat, Range, Bar, Badge, Note, Empty, Field, Steps, Row, Tile, Toolbar,
}
