/**
 * 5 套主题 × 浅/深 两档的色种子。
 * 只声明 3 色 + 底色；其余 30+ 令牌全部由 derive.ts 的 mix() 现混派生。
 */
import type { ThemeId, Scheme, ThemeSeed } from './types'

export const THEME_IDS: ThemeId[] = ['old', 'clean', 'md3', 'endfield', 'bluearchive']

export const DEFAULT_THEME_ID: ThemeId = 'old'

/** 主题 → 两档种子 */
export const SEEDS: Record<ThemeId, Record<Scheme, ThemeSeed>> = {
  /* ---------------------------------------------------------------- Old
   * 现有默认（Mono）：近纯黑墨 + 中性灰线 + 无彩点缀。极简、无阴影。 */
  old: {
    light: { primary: '#0f0f0f', secondary: '#8b8f96', accent: '#0f0f0f', base: '#ffffff' },
    dark: { primary: '#ededed', secondary: '#8b8f96', accent: '#ededed', base: '#101010' },
  },

  /* -------------------------------------------------------------- Clean
   * 现代 SaaS：石板灰墨 + 单一品牌蓝（blue-600/500）。干净、克制、可信。 */
  clean: {
    light: { primary: '#0f172a', secondary: '#94a3b8', accent: '#2563eb', base: '#ffffff' },
    dark: { primary: '#e2e8f0', secondary: '#475569', accent: '#3b82f6', base: '#0b1120' },
  },

  /* ---------------------------------------------------------------- MD3
   * Material Design 3 官方基线（紫罗兰 tonal palette）。
   * base 用 MD3 的 surface，primary 用 on-surface，secondary 用 outline。 */
  md3: {
    light: { primary: '#1d1b20', secondary: '#79747e', accent: '#6750a4', base: '#fef7ff' },
    dark: { primary: '#e6e0e9', secondary: '#938f99', accent: '#d0bcff', base: '#141218' },
  },

  /* ----------------------------------------------------------- Endfield
   * 浅档 = 工业印刷体（暖纸 + 黑墨 + 工业黄）
   * 深档 = 碳黑战术玻璃 HUD（近黑底 + 冷白墨 + 工业黄） */
  endfield: {
    light: { primary: '#0b0b0b', secondary: '#b8b8b0', accent: '#ffd400', base: '#f1f1ee' },
    dark: { primary: '#eaf0f2', secondary: '#4a565e', accent: '#ffd400', base: '#07080a' },
  },

  /* --------------------------------------------------------- BlueArchive
   * 纯净白底 + 档案青主色（青档用深墨，深档用冷白）。
   * 深档把 base 压到近夜的冷蓝，保持"青"的识别。 */
  bluearchive: {
    light: { primary: '#1a2333', secondary: '#c3cfdd', accent: '#128afa', base: '#ffffff' },
    dark: { primary: '#eaf2fb', secondary: '#3a4757', accent: '#4fb0ff', base: '#0d1420' },
  },
}

export function seedOf(id: ThemeId, scheme: Scheme): ThemeSeed {
  return SEEDS[id][scheme]
}
