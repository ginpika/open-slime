import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { isDesktopShell } from '../api/desktop'
import { IconPalette } from '../components/ui'
import { AppIcon } from '../components/AppIcon'
import { OrbitalSeal } from '../shell/AstrolabeShell'
import { ASTROLABE_PALETTES } from './astrolabe'
import { THEME_META } from './layers'
import { THEME_IDS } from './palette'
import { GlobalTab, SCHEMES } from './ThemePanel'
import type { ThemePanelProps } from './registry'
import { rawSeed, themeActions, useThemeState } from './store'
import type { AstrolabeConfig } from './types'

type ObservatoryTab = 'studio' | 'library' | 'global'
const TABS: { id: ObservatoryTab; title: string; en: string }[] = [
  { id: 'studio', title: '观测台', en: 'STUDIO' },
  { id: 'library', title: '主题库', en: 'COLLECTION' },
  { id: 'global', title: '全局', en: 'FOUNDATION' },
]
const PALETTES: { id: AstrolabeConfig['palette']; name: string; en: string }[] = [
  { id: 'brass', name: '旧黄铜', en: 'BRASS' },
  { id: 'copper', name: '赤铜', en: 'COPPER' },
  { id: 'glacier', name: '冰川', en: 'GLACIER' },
]

function Choice<T extends string>({ label, hint, value, options, onChange }: {
  label: string; hint?: string; value: T; options: { value: T; label: string }[]; onChange: (value: T) => void
}) {
  return <div className="astro-setting"><div className="astro-setting__label"><strong>{label}</strong>{hint && <small>{hint}</small>}</div><div className="astro-choice" role="group" aria-label={label}>{options.map(option => <button key={option.value} type="button" aria-pressed={option.value === value} onClick={() => onChange(option.value)}>{option.label}</button>)}</div></div>
}

function Toggle({ label, hint, on, onChange }: { label: string; hint: string; on: boolean; onChange: () => void }) {
  return <div className="astro-setting astro-setting--toggle"><div className="astro-setting__label"><strong>{label}</strong><small>{hint}</small></div><button type="button" className="astro-switch" role="switch" aria-label={label} aria-checked={on} onClick={onChange}><span /></button></div>
}

/** Independent, non-modal observatory. A portal avoids titlebar blur/clipping contexts. */
export function AstrolabeThemePanel({ open, onOpenChange, variant }: ThemePanelProps) {
  const st = useThemeState()
  const config = st.astrolabe
  const [tab, setTab] = useState<ObservatoryTab>('studio')
  const [position, setPosition] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const id = useId()
  const visible = open && !!position
  const overridden = !!st.override.seed.primary || !!st.override.seed.accent || !!st.override.seed.base || !!st.override.fontSans || !!st.override.headingStyle

  useLayoutEffect(() => {
    if (!open) return
    const update = () => {
      const rect = trigger.current?.getBoundingClientRect()
      if (!rect) return
      const width = Math.min(400, window.innerWidth - 24)
      const maxHeight = Math.min(600, window.innerHeight - 24)
      const top = Math.max(12, Math.min(rect.bottom + 8, window.innerHeight - maxHeight - 12))
      setPosition({ left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)), top, width, maxHeight })
    }
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update, true)
    return () => { window.removeEventListener('resize', update); window.removeEventListener('scroll', update, true) }
  }, [open])

  useEffect(() => {
    if (!open) return
    const outside = (e: Event) => {
      if (!trigger.current?.contains(e.target as Node) && !panel.current?.contains(e.target as Node)) onOpenChange(false)
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { onOpenChange(false); trigger.current?.focus() }
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('focusin', outside)
    document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('focusin', outside); document.removeEventListener('keydown', escape) }
  }, [open, onOpenChange])

  useEffect(() => {
    if (visible) panel.current?.querySelector<HTMLButtonElement>('.astro-menu__tabs [aria-pressed="true"]')?.focus()
  }, [visible])

  return <>
    <button ref={trigger} type="button" aria-label="主题" title="星图观测台" aria-expanded={open} aria-controls={open ? id : undefined} onClick={() => onOpenChange(!open)} className={variant === 'chrome' ? 'df__toolbtn' : 'ui-btn ui-btn--icon rounded-full'}><IconPalette className="h-4 w-4" /></button>
    {open && position && createPortal(
      <div ref={panel} id={id} role="dialog" aria-label="星图观测台" aria-modal="false" className="astro-menu" data-astro-material={config.material} style={position}>
        <header className="astro-menu__head"><div><span className="astro-eyebrow">THE PRIVATE OBSERVATORY</span><h2>星图观测台<span>✧</span></h2></div><button type="button" aria-label="关闭观测台" onClick={() => { onOpenChange(false); trigger.current?.focus() }}><AppIcon name="close" /></button></header>
        <nav className="astro-menu__tabs" aria-label="外观设置分区">{TABS.map(t => <button key={t.id} type="button" aria-pressed={tab === t.id} onClick={() => setTab(t.id)}><span>{t.title}</span><small>{t.en}</small></button>)}</nav>
        <div className="astro-menu__body scrollbar-slim">
          <div className="astro-menu__appearance"><span>光线</span><div className="astro-choice" role="group" aria-label="明暗外观">{SCHEMES.map(s => <button key={s.id} type="button" aria-pressed={st.schemeMode === s.id} onClick={() => themeActions.setSchemeMode(s.id)}>{s.label}</button>)}</div></div>
          {tab === 'studio' ? <>
            <div className={`astro-sample astro-sample--${config.material} astro-sample--${config.atmosphere}`}>
              <div className="astro-sample__scene" aria-hidden="true"><i /><i /><OrbitalSeal className="astro-sample__seal" /></div>
              <div className="astro-sample__pane">
                <span className="astro-eyebrow">YOUR LITTLE UNIVERSE</span>
                <h3>把好奇心，<br />安放在这里。</h3>
                <div className="astro-sample__rule"><span>OBSERVATION / 01</span><i aria-hidden="true" /></div>
              </div>
              <p className="astro-sample__caption" role="status">{config.material === 'glass' ? '磨砂玻璃 · 背景透光，轮廓柔化' : '实底书页 · 遮住背景，安静阅读'}</p>
            </div>
            <div className="astro-presets"><button type="button" onClick={() => { themeActions.setAstrolabeConfig({ palette: 'brass', material: 'paper', atmosphere: 'orbits', motion: false, density: 'comfortable', reading: 'serif' }); themeActions.setSchemeMode('light') }}>☀ 日间书房<span>温暖 · 专注</span></button><button type="button" onClick={() => { themeActions.setAstrolabeConfig({ palette: 'glacier', material: 'glass', atmosphere: 'aurora', motion: true, density: 'comfortable', reading: 'sans' }); themeActions.setSchemeMode('dark') }}>✧ 夜航观测<span>冷冽 · 流动</span></button></div>
            <div className="astro-setting"><div className="astro-setting__label"><strong>金属与墨色</strong><small>整套配色联动，保留你的全局覆盖</small></div><div className="astro-palettes">{PALETTES.map(p => { const seed = ASTROLABE_PALETTES[p.id][st.scheme]; return <button key={p.id} type="button" aria-pressed={config.palette === p.id} onClick={() => themeActions.setAstrolabeConfig({ palette: p.id })}><span className="astro-palettes__swatch" style={{ background: seed.base }}><i style={{ background: seed.primary }} /><i style={{ background: seed.accent }} /></span><strong>{p.name}</strong><small>{p.en}</small></button> })}</div></div>
            <Choice<AstrolabeConfig['material']> label="面板材质" hint="应用于当前菜单、首页输入框、卡片和统计面板；上方实时预览" value={config.material} options={[{ value: 'paper', label: '书页' }, { value: 'glass', label: '磨砂玻璃' }]} onChange={material => themeActions.setAstrolabeConfig({ material })} />
            <Choice<AstrolabeConfig['atmosphere']> label="研究室氛围" hint="装饰始终留在内容后方" value={config.atmosphere} options={[{ value: 'none', label: '纯净' }, { value: 'orbits', label: '星轨' }, { value: 'aurora', label: '极光' }]} onChange={atmosphere => themeActions.setAstrolabeConfig({ atmosphere })} />
            <Toggle label="让星辰缓缓流动" hint="低速轨道与极光；跟随系统减少动态效果偏好" on={config.motion} onChange={() => themeActions.setAstrolabeConfig({ motion: !config.motion })} />
            <Choice<AstrolabeConfig['density']> label="工作密度" value={config.density} options={[{ value: 'comfortable', label: '舒展' }, { value: 'compact', label: '紧凑' }]} onChange={density => themeActions.setAstrolabeConfig({ density })} />
            <Choice<AstrolabeConfig['reading']> label="文档阅读" hint="只改变文章正文，代码保持等宽" value={config.reading} options={[{ value: 'serif', label: '书籍排印' }, { value: 'sans', label: '现代排印' }]} onChange={reading => themeActions.setAstrolabeConfig({ reading })} />
            {isDesktopShell() && <Toggle label="沉浸工作台" hint="收起主题顶底栏，工具留在窗口标题栏" on={config.hideChrome} onChange={() => themeActions.setAstrolabeConfig({ hideChrome: !config.hideChrome })} />}
            <button type="button" className="astro-reset" onClick={() => themeActions.resetAstrolabeConfig()}>恢复星图默认设定</button>
            {!!Object.keys(st.override.seed).length && <p className="astro-menu__hint">已保留全局颜色覆盖；若要完整观看预设配色，可在「全局」清除覆盖。</p>}
          </> : tab === 'library' ? <><div className="astro-library">{THEME_IDS.map(theme => { const seed = rawSeed(theme, st.scheme); return <button type="button" key={theme} aria-pressed={st.themeId === theme} onClick={() => themeActions.setTheme(theme)}><span className="astro-library__mini" style={{ background: seed.base, color: seed.primary }}><i style={{ background: seed.primary }} /><span><b style={{ background: seed.accent }} /><i style={{ background: seed.secondary }} /><i style={{ background: seed.secondary }} /></span></span><strong>{THEME_META[theme].label}</strong><small>{THEME_META[theme].labelEn}</small></button> })}</div><p className="astro-menu__hint">{THEME_META[st.themeId].desc}</p></> : <GlobalTab overridden={overridden} />}
        </div>
        <footer className="astro-menu__footer"><span>ASTROLABE / PERSONAL EDITION</span><span>随手调整，自动保存</span></footer>
      </div>, document.body,
    )}
  </>
}
