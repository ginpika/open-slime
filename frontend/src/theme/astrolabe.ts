import type { LayerBundle } from './layers'
import type { AstrolabeConfig, Scheme, ThemeSeed, Vars } from './types'

export const ASTROLABE_DEFAULT_CONFIG: AstrolabeConfig = {
  palette: 'brass', material: 'paper', atmosphere: 'orbits', motion: true,
  density: 'comfortable', reading: 'serif', hideChrome: true,
}

export const ASTROLABE_PALETTES: Record<AstrolabeConfig['palette'], Record<Scheme, ThemeSeed>> = {
  brass: {
    light: { primary: '#203e36', secondary: '#b1b4a5', accent: '#81612f', base: '#f3f0e7' },
    dark: { primary: '#e7e8dc', secondary: '#526c61', accent: '#d6b779', base: '#101f1b' },
  },
  copper: {
    light: { primary: '#493930', secondary: '#bfb0a1', accent: '#9a4d32', base: '#f5eee7' },
    dark: { primary: '#eee2d6', secondary: '#75594b', accent: '#e5a488', base: '#281c18' },
  },
  glacier: {
    light: { primary: '#293d47', secondary: '#adbcc1', accent: '#366b80', base: '#eff3f3' },
    dark: { primary: '#deebed', secondary: '#526e7a', accent: '#9fcbda', base: '#14252b' },
  },
}

export function normalizeAstrolabe(raw: unknown): AstrolabeConfig {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Partial<AstrolabeConfig>
  return {
    palette: r.palette === 'copper' || r.palette === 'glacier' ? r.palette : 'brass',
    material: r.material === 'glass' ? 'glass' : 'paper',
    atmosphere: r.atmosphere === 'none' || r.atmosphere === 'aurora' ? r.atmosphere : 'orbits',
    motion: r.motion !== false,
    density: r.density === 'compact' ? 'compact' : 'comfortable',
    reading: r.reading === 'sans' ? 'sans' : 'serif',
    hideChrome: r.hideChrome !== false,
  }
}

/** Config projects through the existing token system; color tokens remain concrete for Canvas. */
export function astrolabeConfigVars(config: AstrolabeConfig, scheme: Scheme): Vars {
  const compact = config.density === 'compact'
  const rail = ASTROLABE_PALETTES[config.palette].dark
  return {
    '--astro-ink': rail.base, '--astro-rail-fg': rail.primary, '--astro-brass': rail.accent,
    '--astro-rail-muted': `color-mix(in srgb, ${rail.primary} 75%, ${rail.base})`,
    '--astro-nav-fill': ASTROLABE_PALETTES[config.palette].light.base,
    '--astro-nav-fg': ASTROLABE_PALETTES[config.palette].light.primary,
    '--astro-page-gap': compact ? '18px' : '26px',
    '--astro-page-top': compact ? '22px' : '32px',
    '--astro-panel-pad': compact ? '16px' : '22px',
    '--astro-row-py': compact ? '10px' : '14px',
    '--astro-reading-font': config.reading === 'serif' ? 'var(--astro-display)' : 'var(--ui-font-sans)',
    '--astro-motion': config.motion ? 'running' : 'paused',
    ...(config.material === 'glass' ? {
      '--ui-panel-alpha': scheme === 'dark' ? '58%' : '62%',
      '--ui-blur': '26px',
      '--ui-backdrop-filter': 'blur(var(--ui-blur)) saturate(1.08)',
      '--ui-control-backdrop-filter': 'blur(var(--ui-blur))',
      '--ui-card-ring': 'color-mix(in srgb, var(--c-fg) 22%, transparent)',
      '--ui-card-ring-hover': 'color-mix(in srgb, var(--c-accent) 80%, transparent)',
    } : {}),
  }
}

/** A quiet observatory: paper, patinated brass, ink. All surfaces follow user seeds. */
export const astrolabeLayers: LayerBundle = {
  shape: {
    '--ui-r-xs': '4px', '--ui-r-sm': '6px', '--ui-r-md': '10px',
    '--ui-r-lg': '14px', '--ui-r-xl': '18px', '--ui-r-2xl': '24px',
    '--ui-r-card': '14px', '--ui-r-pill': '999px', '--ui-r-btn': '8px',
    '--ui-r-btn-sm': '6px', '--ui-r-control': '8px', '--ui-clip': 'none', '--ui-clip-sm': 'none',
  },
  depth: {
    '--ui-e0': 'none',
    '--ui-e1': '0 2px 3px rgb(14 35 28 / .025), 0 8px 24px rgb(14 35 28 / .035)',
    '--ui-e2': '0 8px 24px rgb(14 35 28 / .09)',
    '--ui-e3': '0 14px 36px rgb(14 35 28 / .13)',
    '--ui-e4': '0 24px 64px rgb(8 24 18 / .22)',
    '--ui-e5': '0 32px 80px rgb(8 24 18 / .3)',
  },
  surface: {
    '--ui-panel-alpha': '100%', '--ui-blur': '0px', '--ui-lift': '-2px',
    '--ui-backdrop-filter': 'none', '--ui-control-backdrop-filter': 'none',
    '--ui-card-fill': 'color-mix(in srgb, var(--c-bg) 55%, white)',
    '--ui-card-ring-hover': 'var(--c-accent)',
    '--astro-ink': '#183a30', '--astro-rail-fg': '#f0eddf', '--astro-brass': '#d6b779',
    '--astro-rail-muted': '#b8c6ba',
    '--astro-display': '"Palatino Linotype","Book Antiqua",Georgia,"STZhongsong","Songti SC","SimSun",serif',
  },
  typo: {
    '--ui-font-heading': 'var(--astro-display)', '--ui-heading-weight': '600',
    '--ui-heading-style': 'normal', '--ui-heading-tracking': '0em', '--ui-body-tracking': '0em',
    '--ui-label-tracking': '.14em', '--ui-label-transform': 'none', '--ui-label-weight': '500',
    '--ui-btn-fs': 'var(--ui-fs-base)', '--ui-btn-weight': '600', '--ui-btn-style': 'normal',
    '--ui-btn-tracking': '.015em', '--ui-btn-transform': 'none', '--ui-btn-py': '8px', '--ui-btn-px': '16px',
  },
  motion: {
    '--ui-dur-fast': '140ms', '--ui-dur-base': '200ms', '--ui-dur-slow': '360ms',
    '--ui-ease': 'cubic-bezier(.22,.68,0,1)', '--ui-ease-mech': 'ease',
  },
  btn: {
    '--ui-btn-primary-fill': 'var(--c-fg)', '--ui-btn-primary-ring': 'var(--c-fg)',
    '--ui-btn-primary-fill-hover': 'color-mix(in srgb, var(--c-fg) 88%, var(--c-bg))',
    '--ui-btn-primary-fg': 'var(--c-bg)', '--ui-btn-primary-fill-active': 'var(--c-fg)',
    '--ui-btn-outline-fg': 'var(--c-fg)',
  },
  overrides: {
    dark: {
      '--ui-card-fill': 'color-mix(in srgb, var(--c-bg) 94%, var(--c-fg))',
      '--ui-e1': '0 2px 8px rgb(0 0 0 / .08)',
      '--ui-e4': '0 24px 64px rgb(0 0 0 / .45)',
      '--ui-btn-primary-fill': 'var(--c-accent)', '--ui-btn-primary-ring': 'var(--c-accent)',
      '--ui-btn-primary-fill-hover': 'var(--c-accent-hover)',
      '--ui-btn-primary-fill-active': 'var(--c-accent)', '--ui-btn-primary-fg': 'var(--c-accent-fg)',
    },
  },
}
