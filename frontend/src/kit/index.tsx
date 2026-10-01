/**
 * 页面 Kit 注册表：主题 → kit。
 *
 * 分组与外壳 kit 保持一致：
 *   old / clean / md3 → flat（同家族，靠令牌区分）
 *   endfield          → endfield（独立实现，理由是"章法不同"而非"颜色不同"）
 *   bluearchive       → bluearchive（独立实现，理由是"密度与容器形态不同"）
 */
import { useThemeState } from '../theme/store'
import type { ThemeId } from '../theme/types'
import { blueArchiveKit } from './bluearchive'
import { endfieldKit } from './endfield'
import { flatKit } from './flat'
import type { KitId, PageKit } from './types'

export const KITS: Record<KitId, PageKit> = {
  flat: flatKit,
  endfield: endfieldKit,
  bluearchive: blueArchiveKit,
}

/** 主题 → kit */
const KIT_OF_THEME: Record<ThemeId, KitId> = {
  old: 'flat',
  clean: 'flat',
  md3: 'flat',
  endfield: 'endfield',
  bluearchive: 'bluearchive',
}

/** 当前主题对应的页面 Kit */
export function usePageKit(): PageKit {
  const { themeId } = useThemeState()
  return KITS[KIT_OF_THEME[themeId]]
}

export type { PageKit } from './types'
