/**
 * 页面 Kit 入口：按当前主题从注册表取渲染包。
 *
 * 「主题 → kit」的映射已收口到 theme/registry.tsx（THEMES），页面统一经
 * usePageKit() 读取，因此 kit 的选用对页面是透明的。
 */
import { THEMES } from '../theme/registry'
import { useThemeState } from '../theme/store'
import type { PageKit } from './types'

/** 当前主题对应的页面 Kit */
export function usePageKit(): PageKit {
  return THEMES[useThemeState().themeId].kit
}

export type { PageKit } from './types'
