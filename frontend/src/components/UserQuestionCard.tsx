import { useId, useRef, useState, type FormEvent } from 'react'
import type { UserQuestion } from './userQuestion'

export default function UserQuestionCard({ question, active, onAnswer }: {
  question: UserQuestion
  active: boolean
  onAnswer: (text: string) => Promise<void>
}) {
  const id = useId()
  const [selected, setSelected] = useState<number | 'custom' | null>(null)
  const [custom, setCustom] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const lock = useRef(false)
  const answer = selected === 'custom' ? custom.trim() : selected == null ? '' : question.options[selected].label
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!active || !answer || lock.current) return
    lock.current = true
    setSubmitting(true)
    try { await onAnswer(answer) }
    finally { lock.current = false; setSubmitting(false) }
  }
  return (
    <form onSubmit={event => void submit(event)} className="my-2 min-w-0 rounded-xl border border-line bg-bg p-4 text-[1rem] leading-relaxed">
      <fieldset disabled={!active || submitting} className="min-w-0 space-y-3">
        <legend className="mb-3 whitespace-pre-wrap font-medium">{question.question}</legend>
        {question.options.map((option, index) => (
          <label key={option.label} className={`flex min-w-0 items-start gap-3 rounded-lg border p-3 ${active ? 'cursor-pointer' : ''} ${selected === index ? 'border-accent bg-accent/5' : 'border-line'}`}>
            {active && <input type="radio" name={id} value={index} checked={selected === index} onChange={() => setSelected(index)} className="mt-1.5 shrink-0 accent-accent" />}
            <span className="min-w-0 break-words">
              <span className="block whitespace-pre-wrap font-medium">{option.label}</span>
              {option.description && <span className="mt-1 block whitespace-pre-wrap text-[0.875rem] leading-relaxed text-muted max-sm:text-[1rem]">{option.description}</span>}
            </span>
          </label>
        ))}
        {active && <>
          <label className="flex cursor-pointer items-center gap-3">
            <input type="radio" name={id} value="custom" checked={selected === 'custom'} onChange={() => setSelected('custom')} className="accent-accent" />
            自定义回复
          </label>
          <textarea aria-label="自定义回复内容" value={custom} maxLength={10000} rows={3}
            onFocus={() => setSelected('custom')}
            onChange={event => { setSelected('custom'); setCustom(event.target.value) }}
            placeholder="输入你的想法，也可以补充具体要求"
            className="block w-full resize-y rounded-lg border border-line bg-bg px-3 py-2 text-[1rem] text-fg placeholder:text-muted focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/30" />
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span className="text-[0.875rem] text-muted max-sm:text-[1rem]" role="status">等待你的回答</span>
            <button type="submit" disabled={!answer || submitting}
              className="rounded-lg bg-fg px-4 py-2 text-[1rem] font-medium text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:cursor-not-allowed disabled:opacity-50">
              {submitting ? '正在提交…' : '确认并继续'}
            </button>
          </div>
        </>}
      </fieldset>
      {!active && <p className="mt-3 text-[0.875rem] text-muted max-sm:text-[1rem]">历史询问</p>}
    </form>
  )
}
