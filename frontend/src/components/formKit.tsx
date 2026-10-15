/**
 * 共享表单原语：设置页（模型 / Embedding / Rerank）与工作空间页的目标表单共用同一套
 * 输入框、标签、自绘下拉与「模式选择」按钮，避免两处各写一份、样式慢慢跑偏。
 */
import type { ReactNode } from 'react'

export { INPUT } from './formStyles'
export { Select } from './Select'

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
      aria-pressed={active}
      className={`min-w-0 basis-[220px] flex-1 rounded-md border px-3.5 py-3 text-left transition ${
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
