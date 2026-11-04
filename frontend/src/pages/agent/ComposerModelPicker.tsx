import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowLeft, Check, ChevronDown, ChevronRight, RotateCcw, Search, Zap } from 'lucide-react'
import type { ReasoningEffort } from '../../api/client'
import './composerModelPicker.css'

export type { ReasoningEffort } from '../../api/client'
export type ModelChoice = {
  id: string
  name: string
  model?: string
  description?: string
  efforts?: ReasoningEffort[]
}

type Props = {
  models: ModelChoice[]
  value: string
  inheritedLabel: string
  inheritedModelId?: string
  effort: ReasoningEffort | undefined
  onModelChange: (id: string) => void
  onEffortChange: (effort: ReasoningEffort | undefined) => void
  disabled?: boolean
  loading?: boolean
  error?: boolean
  onRetry?: () => void
}

const EFFORT_LABELS: Record<ReasoningEffort, string> = {
  low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高',
}

function modelIdentifier(model: ModelChoice): string { return model.model?.trim() || model.id }

/** The first view is the compact strength control; the model name opens its own list. */
export default function ComposerModelPicker({ models, value, inheritedLabel, inheritedModelId, effort, onModelChange, onEffortChange, disabled = false, loading = false, error = false, onRetry }: Props) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState<'strength' | 'models'>('strength')
  const [query, setQuery] = useState('')
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const modelButton = useRef<HTMLButtonElement>(null)
  const searchInput = useRef<HTMLInputElement>(null)
  const modelList = useRef<HTMLDivElement>(null)
  const id = useId()
  const selected = models.find(model => model.id === value)
  const effective = selected ?? (!value ? models.find(model => model.id === inheritedModelId) : undefined)
  const capabilitiesMissing = Boolean(effective && effective.efforts === undefined)
  const efforts = useMemo(() => Array.from(new Set(effective?.efforts ?? [])), [effective])
  const chosenEffort = effort && efforts.includes(effort) ? effort : undefined
  // Zero is explicitly the inherited/default setting, never an invented middle level.
  const effortIndex = chosenEffort ? efforts.indexOf(chosenEffort) + 1 : 0
  const canAdjust = efforts.length > 1
  const level = chosenEffort ? EFFORT_LABELS[chosenEffort] : '默认'
  const inheritedModel = models.find(model => model.id === inheritedModelId)
  const inheritedName = inheritedModel ? modelIdentifier(inheritedModel) : inheritedLabel
  const name = loading && !models.length ? '加载模型…' : effective ? modelIdentifier(effective) : value ? '选择模型' : inheritedName || '选择模型'
  const filtered = useMemo(() => {
    const text = query.trim().toLocaleLowerCase()
    return text ? models.filter(model => [model.name, model.model, model.description].some(part => part?.toLocaleLowerCase().includes(text))) : models
  }, [models, query])
  const visible = open && !disabled
  const strengthHelp = capabilitiesMissing ? '后端尚未提供模型能力，请重新编译并重启后端。' : !effective ? '请先选择模型，等待模型信息加载。' : canAdjust
    ? `默认，${efforts.map(item => EFFORT_LABELS[item]).join('，')}`
    : efforts.length === 1 ? `此模型仅支持 ${EFFORT_LABELS[efforts[0]]}，沿用模型设置。` : '此模型不支持调整推理强度。'

  const close = (restoreFocus = false) => {
    setOpen(false)
    if (restoreFocus) trigger.current?.focus({ preventScroll: true })
  }
  const show = () => {
    if (disabled) return
    setView('strength')
    setQuery('')
    setOpen(true)
  }
  useEffect(() => {
    if (!visible) return
    const onPointer = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    const onFocus = (event: FocusEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('focusin', onFocus)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('focusin', onFocus)
    }
  }, [visible])
  useEffect(() => {
    if (!visible) return
    if (view === 'models') searchInput.current?.focus({ preventScroll: true })
    else modelButton.current?.focus({ preventScroll: true })
  }, [visible, view])
  useEffect(() => { if (disabled) setOpen(false) }, [disabled])

  const chooseModel = (next: string) => {
    const nextModel = models.find(model => model.id === (next || inheritedModelId))
    onModelChange(next)
    if (effort && !nextModel?.efforts?.includes(effort)) onEffortChange(undefined)
    setView('strength')
  }
  const escape = (event: KeyboardEvent<HTMLDivElement>) => {
    if (visible && event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close(true)
    }
  }
  const listKeys = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
    const rows = Array.from(modelList.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]') ?? [])
    if (!rows.length) return
    event.preventDefault()
    const index = rows.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1
      : event.key === 'ArrowDown' ? (index + 1) % rows.length : (index - 1 + rows.length) % rows.length
    rows[next]?.focus({ preventScroll: true })
    const list = modelList.current
    const row = rows[next]
    if (list && row) {
      const viewport = list.getBoundingClientRect(), bounds = row.getBoundingClientRect()
      if (bounds.top < viewport.top) list.scrollTop -= viewport.top - bounds.top
      else if (bounds.bottom > viewport.bottom) list.scrollTop += bounds.bottom - viewport.bottom
    }
  }

  return <div className="sl-model" ref={root} onKeyDownCapture={escape}>
    <button ref={trigger} type="button" className="sl-model__trigger" disabled={disabled}
      aria-haspopup="dialog" aria-expanded={visible} aria-controls={`${id}-panel`}
      aria-label={`选择推理强度，当前 ${level}，模型 ${name}`}
      title={`${name} · ${level}`}
      onClick={() => visible ? close() : show()}
      onKeyDown={event => {
        if (!visible && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) { event.preventDefault(); show() }
      }}>
      {visible ? <span>选择推理强度</span> : <><span className="sl-model__trigger-name">{name}</span><span className="sl-model__trigger-level">· {level}</span></>}
      <ChevronDown size={12} className={visible ? 'is-open' : ''} aria-hidden="true" />
    </button>

    {visible && <div className={`sl-model__panel${view === 'models' ? ' sl-model__panel--models' : ''}`} id={`${id}-panel`} role="dialog" aria-label={view === 'models' ? '选择模型' : '推理强度'}>
      {view === 'strength' ? <>
        <div className="sl-model__strength-head">
          <Zap className="sl-model__strength-icon" size={16} aria-hidden="true" />
          <button ref={modelButton} type="button" className="sl-model__strength-copy" title={`选择模型：${name}`} onClick={() => { setQuery(''); setView('models') }}>
            <span className="sl-model__strength-name" id={`${id}-level`}>{level}</span>
            <span className="sl-model__current-model">
              <span>{name}</span><ChevronRight size={12} aria-hidden="true" />
            </span>
          </button>
          <button type="button" className="sl-model__reset" aria-label="恢复默认推理强度" title="恢复默认推理强度" disabled={effort === undefined} onClick={() => onEffortChange(undefined)}><RotateCcw size={14} aria-hidden="true" /></button>
        </div>
        <div className="sl-model__track-wrap" title={strengthHelp}>
          <input type="range" className="sl-model__range" min={0} max={Math.max(1, efforts.length)} step={1} value={effortIndex}
            aria-label="推理强度" aria-valuetext={level} aria-describedby={`${id}-help`} disabled={!canAdjust}
            onChange={event => onEffortChange(efforts[Number(event.target.value) - 1])} />
          {canAdjust && <span className="sl-model__range-stops" aria-hidden="true">
            {[undefined, ...efforts].map((item, index) => <i key={item ?? 'default'} className={index === effortIndex ? 'is-current' : undefined} />)}
          </span>}
          {capabilitiesMissing && <span className="sl-model__capability-note" role="status">请更新并重启后端</span>}
        </div>
        <span className="sl-model__sr-only" id={`${id}-help`}>{strengthHelp}</span>
      </> : <>
        <div className="sl-model__models-head"><button type="button" className="sl-model__back" aria-label="返回推理强度" onClick={() => setView('strength')}><ArrowLeft size={15} aria-hidden="true" /></button><span>选择模型</span></div>
        <label className="sl-model__search"><Search size={14} aria-hidden="true" />
          <input ref={searchInput} value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索模型…" aria-label="搜索模型"
            onKeyDown={event => {
              if (event.key === 'ArrowDown') { event.preventDefault(); modelList.current?.querySelector<HTMLButtonElement>('[role="radio"]')?.focus({ preventScroll: true }) }
            }} />
        </label>
        <div ref={modelList} className="sl-model__list scrollbar-slim" role="radiogroup" aria-label="选择模型" onKeyDown={listKeys}>
          <button type="button" className={`sl-model__option${!value ? ' is-selected' : ''}`} role="radio" aria-checked={!value} onClick={() => chooseModel('')}>
            <span className="sl-model__option-copy"><span>跟随 Agent</span><small>{inheritedName || '使用 Agent 的默认模型'}</small></span>
            {!value && <Check size={14} aria-hidden="true" />}
          </button>
          {filtered.map(model => <button key={model.id} type="button" className={`sl-model__option${value === model.id ? ' is-selected' : ''}`} role="radio" aria-checked={value === model.id} onClick={() => chooseModel(model.id)}>
            <span className="sl-model__option-copy"><span>{modelIdentifier(model)}</span>{(model.description || model.name !== modelIdentifier(model)) && <small>{model.description || `端点：${model.name}`}</small>}</span>
            {value === model.id && <Check size={14} aria-hidden="true" />}
          </button>)}
          {loading && !models.length && <p className="sl-model__notice" role="status">正在读取模型…</p>}
          {!loading && !error && !filtered.length && <p className="sl-model__notice" role="status">{query.trim() ? '没有找到相关模型' : '还没有其他可选模型'}</p>}
        </div>
        {error && <div className="sl-model__error" role="status"><span>暂时无法更新模型</span>{onRetry && <button type="button" onClick={onRetry}>重试</button>}</div>}
      </>}
    </div>}
  </div>
}
