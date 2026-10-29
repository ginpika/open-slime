import { AppIcon } from './AppIcon'
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
  size?: 'sm' | 'md' | 'compact'
  className?: string
  menuClassName?: string
  placeholder?: string
}

/** 共享 Tailwind 下拉：键盘导航、长列表搜索、视口内定位，弹层避开主题卡片的裁切。 */
export function Select({ value, onChange, options, label, disabled = false, searchable = false, size = 'md', className = '', menuClassName = '', placeholder = '请选择' }: SelectProps) {
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
  const highlightedIndex = filtered.findIndex(o => o.value === highlight && !o.disabled)
  const activeIndex = highlightedIndex >= 0 ? highlightedIndex : filtered.findIndex(o => !o.disabled)
  const activeId = activeIndex >= 0 ? `${id}-${activeIndex}` : undefined
  const close = (restoreFocus = false) => { setOpen(false); if (restoreFocus) trigger.current?.focus({ preventScroll: true }) }
  const show = (last = false) => {
    if (disabled) return
    const enabled = options.filter(option => !option.disabled)
    setSearch(''); setHighlight(current && !current.disabled ? current.value : (last ? enabled.at(-1)?.value : enabled[0]?.value) ?? ''); setOpen(true)
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
  useEffect(() => { if (visible && searchable) searchInput.current?.focus({ preventScroll: true }) }, [visible, searchable])
  useEffect(() => {
    if (!visible) return
    const list = menu.current?.querySelector<HTMLElement>('[role="listbox"]')
    const option = list?.querySelector<HTMLElement>('[data-highlighted="true"]')
    if (!list || !option) return
    const viewport = list.getBoundingClientRect(), item = option.getBoundingClientRect()
    // Scroll the options only; scrollIntoView can also move the surrounding page.
    if (item.top < viewport.top) list.scrollTop -= viewport.top - item.top
    else if (item.bottom > viewport.bottom) list.scrollTop += item.bottom - viewport.bottom
  }, [highlight, visible, search])
  const choose = (option: SelectOption) => { if (option.disabled) return; onChange(option.value); close(true) }
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
  return <div className={`relative min-w-0 ${className}`}>
    <button ref={trigger} type="button" role="combobox" aria-label={label} aria-haspopup="listbox" aria-controls={`${id}-list`} aria-expanded={open && !disabled} aria-activedescendant={!searchable && open ? activeId : undefined} disabled={disabled} onKeyDown={keyDown} onClick={() => open ? close() : show()} className={`${INPUT} flex min-h-9 items-center justify-between gap-2 text-left disabled:cursor-not-allowed disabled:opacity-50 ${size === 'sm' ? '!py-2 !text-[.875rem] max-sm:!text-base' : size === 'compact' ? '!text-[.875rem] max-sm:!text-base' : ''}`}>
      <span className="flex min-w-0 items-center gap-2">{current?.icon}<span className="truncate">{current?.label ?? placeholder}</span></span><AppIcon name="chevron-down" className={`h-4 w-4 shrink-0 text-faint transition-transform ${open ? 'rotate-180' : ''}`} />
    </button>
    {open && !disabled && position && host && createPortal(<div ref={menu} className={`fixed z-[150] flex flex-col overflow-hidden rounded-md border border-line-strong bg-bg p-1 shadow-xl ${menuClassName}`} style={{ left: position.left, top: position.top, width: position.width, maxHeight: position.maxHeight, transform: position.above ? 'translateY(-100%)' : undefined }} onKeyDown={keyDown}>
      {searchable && <div className="shrink-0 border-b border-line p-1 pb-2"><input ref={searchInput} role="combobox" aria-label={`搜索${label ?? '选项'}`} aria-controls={`${id}-list`} aria-expanded="true" aria-autocomplete="list" aria-activedescendant={activeId} value={search} onChange={e => { setSearch(e.target.value); setHighlight('') }} placeholder="搜索名称或 ID…" className="w-full rounded border border-line bg-bg px-2.5 py-2 text-[.875rem] text-fg outline-none placeholder:text-faint focus:border-accent max-sm:text-base" /></div>}
      <div id={`${id}-list`} role="listbox" aria-label={label ?? '选项'} className="scrollbar-slim min-h-0 overflow-y-auto">
        {filtered.map((option, index) => <div key={option.value} id={`${id}-${index}`} role="option" aria-selected={option.value === value} aria-disabled={option.disabled || undefined} data-highlighted={index === activeIndex} onPointerMove={() => { if (!option.disabled) setHighlight(option.value) }} onPointerDown={e => e.preventDefault()} onClick={() => choose(option)} className={`flex items-center justify-between gap-2 rounded px-2.5 py-2 text-left ${option.disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'} ${size === 'md' ? 'text-base' : 'text-[.875rem] max-sm:text-base'} ${index === activeIndex ? 'bg-accent-soft text-fg' : size === 'compact' ? 'text-fg' : 'text-muted'} ${option.value === value ? 'font-semibold' : ''}`}>
          <span className="flex min-w-0 items-center gap-2">{option.icon}<span className="min-w-0 break-words">{option.label}</span></span>{option.value === value && <AppIcon name="check" className="h-4 w-4 shrink-0 text-accent" />}
        </div>)}
        {!filtered.length && <p className="px-2.5 py-3 text-[.75rem] text-muted" role="status">没有匹配的选项</p>}
      </div>
    </div>, host)}
  </div>
}
