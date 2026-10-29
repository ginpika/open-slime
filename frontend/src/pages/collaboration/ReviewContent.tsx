import { useId, useState, type ReactNode } from 'react'
import ChatMarkdown from '../../components/ChatMarkdown'
import { AppIcon } from '../../components/AppIcon'
import { prettyText } from './workReviewModel'

export function ReviewTabs<T extends string>({ label, tabs, value, onChange, panelId }: {
  label: string; tabs: { id: T; label: string; count?: number }[]; value: T; onChange: (value: T) => void; panelId: string
}) {
  const id = useId()
  return <div className="work-review-tabs" role="tablist" aria-label={label}>
    {tabs.map((tab, index) => <button key={tab.id} type="button" role="tab" id={`${id}-${tab.id}`} aria-selected={value === tab.id} aria-controls={panelId}
      tabIndex={value === tab.id ? 0 : -1} onClick={() => onChange(tab.id)}
      onKeyDown={event => {
        let next = index
        if (event.key === 'ArrowRight') next = (index + 1) % tabs.length
        else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length
        else if (event.key === 'Home') next = 0
        else if (event.key === 'End') next = tabs.length - 1
        else return
        event.preventDefault(); onChange(tabs[next].id)
        const controls = event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role="tab"]')
        controls?.[next]?.focus()
      }}>
      {tab.label}{tab.count != null && <span className="work-review-count">{tab.count}</span>}
    </button>)}
  </div>
}

export function CopyText({ text, label = '复制原文' }: { text: string; label?: string }) {
  const [message, setMessage] = useState('')
  return <span className="work-review-copy">
    <button type="button" className="work-review-button" aria-label={label} onClick={() => {
      void (async () => {
        try { await navigator.clipboard.writeText(text); setMessage('已复制') }
        catch { setMessage('复制失败，请选择正文复制') }
      })()
    }}><AppIcon name="restore" />{label}</button>
    {message && <span role="status">{message}</span>}
  </span>
}

export function ReviewSection({ title, children, text, tone = '' }: { title: string; children?: ReactNode; text?: string; tone?: string }) {
  return <section className={`work-review-section ${tone}`}>
    {text != null ? <ReviewText title={title} text={text} /> : <div className="work-review-section-head"><h3>{title}</h3></div>}
    {children}
  </section>
}

export function ReviewText({ text, title }: { text: string; title?: string }) {
  const [raw, setRaw] = useState(false)
  return <div className="work-review-text">
    <div className="work-review-text-head">{title && <h3>{title}</h3>}<div className="work-review-text-actions"><button type="button" className="work-review-button" aria-pressed={raw} onClick={() => setRaw(v => !v)}>{raw ? '阅读排版' : '查看原文'}</button><CopyText text={text} /></div></div>
    {raw ? <pre className="work-review-raw">{text}</pre> : <div className="work-review-prose"><ChatMarkdown content={text} /></div>}
  </div>
}

export function ReviewPayload({ text, label }: { text: string; label: string }) {
  return <div className="work-review-payload"><CopyText text={text} label={`复制${label}`} /><pre tabIndex={0} aria-label={label}>{prettyText(text) || '（空内容）'}</pre></div>
}
