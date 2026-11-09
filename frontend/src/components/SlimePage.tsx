import { useId, type MouseEventHandler, type ReactNode } from 'react'
import { AppIcon } from './AppIcon'
import { SlimeAppearanceButton } from './SlimeAppearance'
import './slimePage.css'

export default function SlimePage({ title, backHref, backLabel, onBack, children }: {
  title: string
  backHref: string
  backLabel: string
  onBack?: MouseEventHandler<HTMLAnchorElement>
  children: ReactNode
}) {
  const titleId = useId()
  return (
    <div className="sl-page slime-surface">
      <header className="sl-page__header">
        <a className="sl-page__back" href={backHref} aria-label={backLabel} title={backLabel} onClick={onBack}>
          <AppIcon name="arrow-left" size={18} />
        </a>
        <h1 id={titleId}>{title}</h1>
        <SlimeAppearanceButton />
      </header>
      <main className="sl-page__main scrollbar-slim" aria-labelledby={titleId}>{children}</main>
    </div>
  )
}
