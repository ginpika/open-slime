import { AppIcon } from './AppIcon'
import { themeActions, useThemeState } from '../theme/store'
import './slimeAppearance.css'

/** A small, persistent appearance control shared by the conversation and tools. */
export function SlimeAppearanceButton() {
  const { schemeMode } = useThemeState()
  const next = schemeMode === 'system' ? 'light' : schemeMode === 'light' ? 'dark' : 'system'
  const label = { system: '跟随系统', light: '浅色', dark: '深色' }
  return (
    <button type="button" className="sl-appearance"
      title={`当前：${label[schemeMode]} · 切换为${label[next]}`}
      aria-label={`外观：${label[schemeMode]}，切换为${label[next]}`}
      onClick={() => themeActions.setSchemeMode(next)}>
      <AppIcon name={`appearance-${schemeMode}`} />
      <span>{label[schemeMode]}</span>
    </button>
  )
}
