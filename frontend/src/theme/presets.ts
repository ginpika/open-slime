/** 内置预设主题（当前 Mono 即默认）
 *  全部为三色形态；深色系：夜幕 / 荧光绿，浅色系：Mono / 靛墨。 */
import type { ThemeDef } from './types'

export const BUILTIN_PRESETS: ThemeDef[] = [
  {
    id: 'mono',
    name: 'Mono',
    mode: 'tritone',
    colors: {
      primary: '#0f0f0f',
      secondary: '#8b8f96',
      accent: '#0f0f0f',
      dark: false,
      base: '#ffffff',
    },
    builtin: true,
  },
  {
    id: 'trio-ink',
    name: '靛墨（三色）',
    mode: 'tritone',
    colors: {
      primary: '#1f3a5f',
      secondary: '#8b8f96',
      accent: '#b45309',
      dark: false,
    },
    builtin: true,
  },
  {
    id: 'night',
    name: '夜幕',
    mode: 'tritone',
    colors: {
      primary: '#ededed',
      secondary: '#8b8f96',
      accent: '#ededed',
      dark: true,
      base: '#101010',
    },
    builtin: true,
  },
  {
    id: 'neon',
    name: '荧光绿',
    mode: 'tritone',
    colors: {
      primary: '#ffffff',
      secondary: '#8b8f96',
      accent: '#32f08c',
      dark: true,
      base: '#1a1b1d',
    },
    builtin: true,
  },
]

export const DEFAULT_PRESET_ID = 'mono'
/** 默认深色预设（外观深浅槽位兜底用） */
export const DEFAULT_DARK_ID = 'night'

export function findBuiltin(id: string): ThemeDef | undefined {
  return BUILTIN_PRESETS.find((p) => p.id === id)
}
