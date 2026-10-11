/**
 * 外壳入口：按当前主题从注册表取外壳组件。
 *
 * 「主题 → 外壳」的映射已收口到 theme/registry.tsx（THEMES），此处只做读取；
 * 此前 kit / shell / ThemePanel 各自维护一份平行映射，是新增主题时最易漏改的地方。
 * 同时侧效导入 shell.css：外壳 kit 的专属样式只在此处加载。
 */
import './shell.css'
import { THEMES } from '../theme/registry'
import { useThemeState } from '../theme/store'
import type { ShellProps } from './types'

export function AppShell(props: ShellProps) {
  const Shell = THEMES[useThemeState().themeId].shell
  return <Shell {...props} />
}

export type { ShellProps }
