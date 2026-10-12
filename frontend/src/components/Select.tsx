import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { INPUT } from './formStyles'
import { filterSelectOptions, nextSelectValue, selectPosition, type SelectOption } from './selectModel'

export type { SelectOption } from './selectModel'
export type SelectProps = {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  label?: string
  disabled?: boolean
  searchable?: boolean
  size?: 'sm' | 'md'
  placeholder?: string
}

/** 共享 Tailwind 下拉：键盘导航、长列表搜索、视口内定位，弹层避开主题卡片的裁切。 */
export function Select({ value, onChange, options, label, disabled = false, searchable = false, size = 'md', placeholder = '请选择' }: SelectProps) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [highlight, setHighlight] = useState(value)
  const [position, setPosition] = useState<{ left: number; top: number; width: number; maxHeight: number; above: boolean } | null>(null)
  const [host, setHost] = useState<HTMLElement | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const searchInput = useRef<HTMLInputElement>(null)
  const id = useId()
  const current = options.find(o => o.value === value)
  const filtered = filterSelectOptions(options, search)
  const activeIndex = Math.max(0, filtered.findIndex(o => o.value === highlight))
  const activeId = filtered.length ? `${id}-${activeIndex}` : undefined
  const close = (restoreFocus = false) => { setOpen(false); if (restoreFocus) trigger.current?.focus() }
  const show = (last = false) => {
    if (disabled) return
    setSearch(''); setHighlight(current?.value ?? (last ? options.at(-1)?.value : options[0]?.value) ?? ''); setOpen(true)
  }
  useLayoutEffect(() => {
    if (!open) return
    setHost(trigger.current?.closest<HTMLElement>('[role="dialog"]') ?? document.body)
    const update = () => {
      const rect = trigger.current?.getBoundingClientRect()
      if (!rect) return
      if (rect.bottom < 0 || rect.top > window.innerHeight) { setOpen(false); return }
      setPosition(selectPosition(rect, { width: window.innerWidth, height: window.innerHeight }))
    }
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    return () => { window.removeEventListener('resize', update); window.removeEventListener('scroll', update, true) }
  }, [open])
  useEffect(() => {
    if (!open) return
    const outside = (e: PointerEvent) => {
      if (!trigger.current?.contains(e.target as Node) && !menu.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open, searchable])
  const visible = open && !!position && !!host && !disabled
  useEffect(() => { if (visible && searchable) searchInput.current?.focus() }, [visible, searchable])
  useEffect(() => { if (open) menu.current?.querySelector('[data-highlighted="true"]')?.scrollIntoView({ block: 'nearest' }) }, [highlight, open, search])
  const choose = (option: SelectOption) => { onChange(option.value); close(true) }
  const keyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key === 'Escape' && open) { e.preventDefault(); e.stopPropagation(); close(true); return }
    if (e.key === 'Tab' && open) { close(e.target === searchInput.current); return }
    if (!open && ['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) { e.preventDefault(); show(e.key === 'ArrowUp'); return }
    if (!open) return
    if ((e.key === 'Home' || e.key === 'End') && e.target === searchInput.current) return
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) {
      e.preventDefault()
      setHighlight(nextSelectValue(filtered, highlight, e.key))
    } else if (e.key === 'Enter' || (e.key === ' ' && e.target === trigger.current)) {
      e.preventDefault(); if (filtered[activeIndex]) choose(filtered[activeIndex])
    }
  }
  // 在对话框中保留 DOM 归属，让现有焦点循环包含搜索输入；其他位置挂到 body 避免裁切。
  return <div className="relative min-w-0">
    <button ref={trigger} type="button" role="combobox" aria-label={label} aria-haspopup="listbox" aria-controls={`${id}-list`} aria-expanded={open && !disabled} aria-activedescendant={!searchable && open ? activeId : undefined} disabled={disabled} onKeyDown={keyDown} onClick={() => open ? close() : show()} className={`${INPUT} flex min-h-9 items-center justify-between gap-2 text-left disabled:cursor-not-allowed disabled:opacity-50 ${size === 'sm' ? '!py-2 !text-xs' : ''}`}>
      <span className="min-w-0 truncate">{current?.label ?? placeholder}</span><svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className={`h-4 w-4 shrink-0 text-faint transition-transform ${open ? 'rotate-180' : ''}`}><path d="m6 9 6 6 6-6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
    </button>
    {open && !disabled && position && host && createPortal(<div ref={menu} className="fixed z-[150] flex flex-col overflow-hidden rounded-md border border-line-strong bg-bg p-1 shadow-xl" style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.maxHeight, transform: position.above ? 'translateY(-100%)' : undefined }} onKeyDown={keyDown}>
      {searchable && <div className="shrink-0 border-b border-line p-1 pb-2"><input ref={searchInput} role="combobox" aria-label={`搜索${label ?? '选项'}`} aria-controls={`${id}-list`} aria-expanded="true" aria-autocomplete="list" aria-activedescendant={activeId} value={search} onChange={e => { setSearch(e.target.value); setHighlight('') }} placeholder="搜索名称或 ID…" className="w-full rounded border border-line bg-bg px-2.5 py-2 text-xs text-fg outline-none placeholder:text-faint focus:border-accent" /></div>}
      <div id={`${id}-list`} role="listbox" aria-label={label ?? '选项'} className="scrollbar-slim min-h-0 overflow-y-auto">
        {filtered.map((option, index) => <div key={option.value} id={`${id}-${index}`} role="option" aria-selected={option.value === value} data-highlighted={index === activeIndex} onPointerMove={() => setHighlight(option.value)} onPointerDown={e => e.preventDefault()} onClick={() => choose(option)} className={`flex cursor-pointer items-center justify-between gap-2 rounded px-2.5 py-2 text-left ${size === 'sm' ? 'text-xs' : 'text-base'} ${index === activeIndex ? 'bg-accent-soft text-fg' : 'text-muted'} ${option.value === value ? 'font-semibold' : ''}`}>
          <span className="min-w-0 break-words">{option.label}</span>{option.value === value && <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4 shrink-0 text-accent"><path d="m5 12 4 4L19 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>}
        </div>)}
        {!filtered.length && <p className="px-2.5 py-3 text-xs text-muted" role="status">没有匹配的选项</p>}
      </div>
    </div>, host)}
  </div>
}
