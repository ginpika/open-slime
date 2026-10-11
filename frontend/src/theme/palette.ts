/**
 * 3 套主题 × 浅/深 两档的色种子 + 本环境可用的主题清单。
 * 只声明 3 色 + 底色；其余 30+ 令牌全部由 derive.ts 的 mix() 现混派生。
 */
import { isDesktopShell } from '../api/desktop'
import type { ThemeId, Scheme, ThemeSeed } from './types'

/** 已实现主题的全量清单（顺序即主题面板的展示顺序） */
const ALL_THEME_IDS: ThemeId[] = ['old', 'endfield', 'bluearchive']

/** 桌面壳里暂不适配的主题：这两套外壳还没在无边框窗口里调过，先整体下架 */
const DESKTOP_UNSUPPORTED: readonly ThemeId[] = ['endfield', 'bluearchive']

/**
 * 本环境可选的主题 —— 主题面板只列这里，store 的 isThemeId 校验也只认这里。
 * 两处共用同一份清单，于是桌面端既列不出这两套主题，已持久化的也会在 load() 时落回默认。
 */
export const THEME_IDS: ThemeId[] = isDesktopShell()
  ? ALL_THEME_IDS.filter((id) => !DESKTOP_UNSUPPORTED.includes(id))
  : ALL_THEME_IDS

export const DEFAULT_THEME_ID: ThemeId = 'old'

/** 主题 → 两档种子 */
export const SEEDS: Record<ThemeId, Record<Scheme, ThemeSeed>> = {
  /* -------------------------------------------------------- Tailwind Mono
   * 原 Old（现默认）：近纯黑墨 + 中性灰线 + 无彩点缀。极简、无阴影。 */
  old: {
    light: { primary: '#0f0f0f', secondary: '#8b8f96', accent: '#0f0f0f', base: '#ffffff' },
    dark: { primary: '#ededed', secondary: '#8b8f96', accent: '#ededed', base: '#101010' },
  },

  /* ----------------------------------------------------------- Endfield
   * 浅档 = 工业印刷体（暖纸 + 黑墨 + 工业黄）
   * 深档 = 碳黑战术玻璃 HUD（近黑底 + 冷白墨 + 工业黄） */
  endfield: {
    light: { primary: '#0b0b0b', secondary: '#b8b8b0', accent: '#ffd400', base: '#f1f1ee' },
    dark: { primary: '#eaf0f2', secondary: '#4a565e', accent: '#ffd400', base: '#07080a' },
  },

  /* --------------------------------------------------------- BlueArchive
   * 浅档 = 冷灰蓝纸底 + 纯白卡（卡色走 layers 的 --ui-card-fill）+ 档案青。
   * secondary 是浅青蓝：派生出有青味的细线，也是渐变的伙伴色；
   * 深档压到近夜冷蓝，secondary 同步加深，保持"青"的识别。 */
  bluearchive: {
    light: { primary: '#1a2333', secondary: '#a9c3e8', accent: '#128afa', base: '#f2f6fb' },
    dark: { primary: '#eaf2fb', secondary: '#2c527d', accent: '#4fb0ff', base: '#0d1420' },
  },
}

export function seedOf(id: ThemeId, scheme: Scheme): ThemeSeed {
  return SEEDS[id][scheme]
}
