import { useId } from 'react'
import './astrolabeLogo.css'

/** 直接复用原 logo 的 alpha：轮廓、弧度和双眼均不重绘，只换主题色与外围装饰。 */
export function AstrolabeLogo({ className = '' }: { className?: string }) {
  const maskId = useId()
  return (
    <svg className={`astro-logo ${className}`} viewBox="0 0 96 96" fill="none" aria-hidden="true" focusable="false">
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="96" height="96" style={{ maskType: 'alpha' }}>
          <image href="/icon-256x256.png" x="16" y="14" width="64" height="64" preserveAspectRatio="xMidYMid meet" />
        </mask>
      </defs>
      <ellipse className="astro-logo__orbit-back" cx="48" cy="49" rx="43" ry="34" transform="rotate(-28 48 49)" />
      <rect className="astro-logo__body" x="16" y="14" width="64" height="64" mask={`url(#${maskId})`} />
      <path className="astro-logo__star" d="m77 9 2.6 7.4L87 19l-7.4 2.6L77 29l-2.6-7.4L67 19l7.4-2.6Z" />
      <circle className="astro-logo__satellite" cx="7" cy="43" r="2.3" />
      <circle className="astro-logo__satellite" cx="72" cy="80" r="1.8" />
    </svg>
  )
}
