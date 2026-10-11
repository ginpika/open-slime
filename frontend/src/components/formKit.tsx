/**
 * 共享表单原语：设置页（模型 / Embedding / Rerank）与工作空间页的目标表单共用同一套
 * 输入框、标签、自绘下拉与「模式选择」按钮，避免两处各写一份、样式慢慢跑偏。
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'

/** 表单控件基底：与 Import 页保持一致的输入框风格 */
export const INPUT =
  'w-full rounded-md border border-line bg-bg px-3 py-2 text-base text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-secondary focus:ring-2 focus:ring-secondary/30'

export function Field({
  label,
  hint,
  children,
}: {
  label: string
  hint?: string
  children: ReactNode
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium uppercase tracking-[0.12em] text-faint">
        {label}
      </span>
      {children}
      {hint && <span className="mt-1 block text-xs text-hint">{hint}</span>}
    </label>
  )
}

/**
 * 完全自定义下拉（Tailwind 风格）：原生 select 的弹出菜单无法用 CSS 定制，
 * 故用按钮 + 绝对定位面板自绘整条交互（点击外部 / Esc 关闭，选中项高亮）。
 */
export function Select({
  value,
  onChange,
  options,
}: {
  value: string
  onChange: (v: string) => void
  options: { value: string; label: string }[]
}) {
  const [open, setOpen] = useState(false)
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

  const current = options.find((o) => o.value === value)

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className={`${INPUT} flex cursor-pointer items-center justify-between gap-2 text-left`}
      >
        <span className="truncate">{current?.label ?? '—'}</span>
        <svg
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
          className={`h-4 w-4 shrink-0 text-hint transition-transform duration-200 ${
            open ? 'rotate-180' : ''
          }`}
        >
          <path
            d="M6 9.5l6 6 6-6"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>
      {open && (
        <div className="scrollbar-slim absolute z-20 mt-1.5 max-h-60 w-full overflow-y-auto rounded-md border border-line bg-bg p-1 shadow-lg">
          {options.map((o) => (
            <button
              key={o.value}
              type="button"
              onClick={() => {
                onChange(o.value)
                setOpen(false)
              }}
              className={`block w-full cursor-pointer truncate rounded px-2.5 py-2 text-left text-base transition ${
                o.value === value
                  ? 'bg-accent/10 font-medium text-fg'
                  : 'text-muted hover:bg-fg/5 hover:text-fg'
              }`}
            >
              {o.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** 单选框式的模式选择按钮（Embedding / Rerank / 目标类型共用） */
export function ModeBtn({
  active,
  title,
  desc,
  onClick,
}: {
  active: boolean
  title: string
  desc: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`min-w-[220px] flex-1 rounded-md border px-3.5 py-3 text-left transition ${
        active ? 'border-secondary bg-secondary/10' : 'border-line hover:border-line-strong'
      }`}
    >
      <span className="flex items-center gap-2 text-base font-semibold text-fg">
        <span
          className={`h-3 w-3 shrink-0 rounded-full border ${
            active ? 'border-secondary bg-secondary' : 'border-line-strong'
          }`}
        />
        {title}
      </span>
      <span className="mt-1 block text-xs leading-relaxed text-faint">{desc}</span>
    </button>
  )
}

/** 规则清单 ↔ 多行文本（一行一条正则；空行忽略） */
export function linesToPatterns(text: string): string[] {
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}
