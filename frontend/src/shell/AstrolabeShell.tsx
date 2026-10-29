import { useEffect, useRef, useState } from 'react'
import { isDesktopShell } from '../api/desktop'
import { BRAND } from '../branding'
import { AstrolabeLogo } from '../components/AstrolabeLogo'
import { useThemeState } from '../theme/store'
import { ShellIcon } from './icons'
import { breadcrumbsOf, NAV, NAV_GROUPS } from './nav'
import type { ShellProps } from './types'
import './astrolabe.css'
import './astrolabeObservatory.css'

/** Vector engraving; a shared motif rather than a background image dependency. */
export function OrbitalSeal({ className = '' }: { className?: string }) {
  return (
    <svg className={`astro-seal ${className}`} viewBox="0 0 400 400" fill="none" aria-hidden="true">
      <g stroke="currentColor" strokeWidth=".8">
        <circle cx="200" cy="200" r="174" /><circle cx="200" cy="200" r="160" />
        <circle cx="200" cy="200" r="122" /><circle cx="200" cy="200" r="60" />
        <ellipse cx="200" cy="200" rx="160" ry="60" transform="rotate(-35 200 200)" />
        <ellipse cx="200" cy="200" rx="160" ry="60" transform="rotate(55 200 200)" />
        <path d="M200 14v372M14 200h372M69 69l262 262M69 331 331 69" opacity=".45" />
        {Array.from({ length: 48 }, (_, i) => <path key={i} d={`M200 26v${i % 4 === 0 ? 10 : 4}`} transform={`rotate(${i * 7.5} 200 200)`} />)}
      </g>
      <g fill="currentColor">
        <circle cx="308" cy="96" r="4" /><circle cx="92" cy="272" r="3" /><circle cx="252" cy="244" r="3" />
        <path d="m200 180 5 15 15 5-15 5-5 15-5-15-15-5 15-5z" />
      </g>
    </svg>
  )
}

export function AstrolabeShell({ route, activePath, fullBleed, hideChrome = false, tools, userSlot, mainRef, children }: ShellProps) {
  const config = useThemeState().astrolabe
  const desktop = isDesktopShell()
  const localMainRef = useRef<HTMLElement>(null)
  const scrollRef = mainRef ?? localMainRef
  const [homeConversation, setHomeConversation] = useState(false)

  // 首页和会话共用路由：按实际可见的那一屏更新面包屑，返回首页不卸载会话。
  useEffect(() => {
    const main = scrollRef.current
    if (!main || route !== '/') return
    const update = () => {
      const chat = main.querySelector<HTMLElement>('[data-home-view="conversation"]')
      setHomeConversation(Boolean(chat &&
        chat.getBoundingClientRect().top <= main.getBoundingClientRect().top + main.clientHeight / 2))
    }
    update()
    main.addEventListener('scroll', update, { passive: true })
    const observer = new ResizeObserver(update)
    observer.observe(main)
    return () => {
      main.removeEventListener('scroll', update)
      observer.disconnect()
    }
  }, [route, scrollRef])

  const breadcrumbs = breadcrumbsOf(route)
  if (route === '/' && homeConversation) breadcrumbs.push({ path: '/#conversation', label: '会话' })
  return (
    <div className={`astro-shell${desktop ? ' astro-shell--desktop' : ''}`} data-astro-atmosphere={config.atmosphere} data-astro-material={config.material} data-astro-motion={config.motion ? 'flow' : 'still'}>
      {!desktop && (
        <aside className="astro-rail">
          <a className="astro-brand" href="#/" aria-label={`${BRAND.name} 首页`}>
            <span className="astro-brand__mark"><AstrolabeLogo /></span>
          </a>
          <div className="astro-rail__rule" aria-hidden="true" />
          <nav className="astro-nav scrollbar-slim" aria-label="主导航">
            {NAV_GROUPS.map((group, index) => (
              <div className="astro-nav__group" key={group}>
                <p><span>{group}</span><span>0{index + 1}</span></p>
                {NAV.filter(n => n.group === group).map(n => (
                  <a key={n.path} href={`#${n.path}`} aria-current={activePath === n.path ? 'page' : undefined}>
                    <ShellIcon name={n.icon} /><span>{n.label}</span><i aria-hidden="true">↗</i>
                  </a>
                ))}
              </div>
            ))}
          </nav>
        </aside>
      )}
      <div className="astro-workarea">
        {config.atmosphere !== 'none' && <div className={`astro-backdrop astro-backdrop--${config.atmosphere}`} aria-hidden="true">
          {config.atmosphere === 'orbits' ? <><OrbitalSeal className="astro-backdrop__orbit" /><svg className="astro-backdrop__stars" viewBox="0 0 1000 800" fill="currentColor"><circle cx="120" cy="240" r="2" /><circle cx="750" cy="150" r="1.5" /><circle cx="340" cy="650" r="2" /><circle cx="860" cy="490" r="2" /><path d="m800 280 2 6 6 2-6 2-2 6-2-6-6-2 6-2z" /></svg></> : <><i /><i /><i /></>}
        </div>}
        {!hideChrome && <header className="astro-topbar">
          <nav className="astro-breadcrumb" aria-label="面包屑导航">
            <ol>
              {breadcrumbs.map((item, index) => (
                <li key={item.path}>
                  {index > 0 && <span className="astro-breadcrumb__separator" aria-hidden="true">/</span>}
                  {index === breadcrumbs.length - 1
                    ? <span aria-current="page">{item.label}</span>
                    : <a href={`#${item.path}`} onClick={event => {
                      if (item.path === '/' && route === '/') {
                        event.preventDefault()
                        scrollRef.current?.scrollTo({ top: 0, behavior: 'smooth' })
                      }
                    }}>{item.label}</a>}
                </li>
              ))}
            </ol>
          </nav>
          <div className="astro-topbar__tools"><span className="astro-edition">ASTROLABE <i>·</i> 知识与灵感的坐标</span>{tools}{desktop ? null : userSlot({ placement: 'down', withName: false })}</div>
        </header>}
        <main ref={scrollRef} data-panel-anchor className={`astro-main${fullBleed ? ' astro-main--bleed' : ' scrollbar-slim'}`}>
          {fullBleed ? children : <div className={`astro-content${route === '/workspace' ? ' astro-content--workspace' : ''}${route === '/' ? ' astro-content--home' : ''}`}>{children}</div>}
        </main>
        {!hideChrome && <footer className="astro-footer"><span>{BRAND.name} <i> / </i> PRIVATE OBSERVATORY</span><span>探索 · 连接 · 沉淀</span></footer>}
      </div>
    </div>
  )
}
