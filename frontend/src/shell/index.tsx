/**
 * 外壳注册表：主题 → kit。
 *
 * 映射原则（与对话中声明一致）：
 *   old / clean / md3 → FlatShell 的三个 variant（同家族，只换排布）
 *   endfield          → EndfieldShell（独立 kit，理由见该文件头注释）
 *   bluearchive       → BlueArchiveShell（独立 kit，理由见该文件头注释）
 *
 * 新增主题时只需在这里加一行映射，页面与路由零改动。
 */
import './shell.css'
import { BlueArchiveShell } from './BlueArchiveShell'
import { EndfieldShell } from './EndfieldShell'
import { FlatShell, type FlatVariant } from './FlatShell'
import type { ShellProps } from './types'
import { useThemeState } from '../theme/store'
import type { ThemeId } from '../theme/types'

/** 同家族主题 → 排布变体 */
const FLAT_VARIANT: Record<Exclude<ThemeId, 'endfield' | 'bluearchive'>, FlatVariant> = {
  old: 'minimal',
  clean: 'sidebar',
  md3: 'rail',
}

export function AppShell(props: ShellProps) {
  const { themeId } = useThemeState()

  if (themeId === 'endfield') return <EndfieldShell {...props} />
  if (themeId === 'bluearchive') return <BlueArchiveShell {...props} />
  return <FlatShell {...props} variant={FLAT_VARIANT[themeId] ?? 'minimal'} />
}

export type { ShellProps }
