/** 令牌推导：ThemeDef → 语义 CSS 变量；并负责写入 :root
 *
 * 角色分工（双色 = 辅色 ≡ 主色的特例，推导规则完全一致）：
 * - 底色 bg     → 背景
 * - 主色 primary → 文字正文（--c-fg），灰阶 faint/muted 按底色+主色插值
 * - 辅色 secondary → 线条（--c-line / --c-line-strong / focus 原色）
 * - 点缀色 accent → 按钮实底 + 自动黑/白字、链接、hint（placeholder/状态提示）、mark 高亮
 * - 分类色板 entity-1..4 → 图谱 canvas 实体专用（多色表达实体类型差异）
 * - MD3 角色 surface/on-surface/outline/*-container → 组件层次与状态底色（见 resolveTokens 内注释）
 */
import { hexToHsl, hslToHex, isDark, mix, pickText } from './color'
import type { ThemeDef, Vars } from './types'

/** 语义变量名（Tailwind v4 里经 @theme inline 映射为 bg-bg / text-fg 等工具类）。
 *  后 10 个是 MD3 角色（见 resolveTokens 的「MD3 角色」段） */
export type TokenKey =
  | '--c-bg'
  | '--c-fg'
  | '--c-muted'
  | '--c-faint'
  | '--c-line'
  | '--c-line-strong'
  | '--c-primary'
  | '--c-primary-fg'
  | '--c-secondary'
  | '--c-accent'
  | '--c-accent-fg'
  | '--c-accent-hover'
  | '--c-hint'
  | '--c-mark'
  | '--c-entity-1'
  | '--c-entity-2'
  | '--c-entity-3'
  | '--c-entity-4'
  | '--c-surface-container-lowest'
  | '--c-surface-container-low'
  | '--c-surface-container'
  | '--c-surface-container-high'
  | '--c-surface-container-highest'
  | '--c-surface-variant'
  | '--c-on-surface'
  | '--c-on-surface-variant'
  | '--c-outline'
  | '--c-outline-variant'

/** 主色去饱和 → 近白/近黑的中性底（保留主色相的极微倾向） */
function neutralTint(primary: string, dark: boolean): string {
  const { h } = hexToHsl(primary)
  return hslToHex({ h, s: 0.06, l: dark ? 0.12 : 0.98 })
}

/**
 * 分类色板（canvas 实体专用）：从点缀色色相出发旋转 90° 生成第 i 档，
 * 饱和度固定、亮度随底色深浅分档；点缀色接近无彩时从固定色相（215°）起，
 * 保证黑白主题下实体类型依然可区分。
 */
function entityColor(accent: string, bg: string, i: number): string {
  const { h, s } = hexToHsl(accent)
  const hue = s < 0.12 ? 215 : h
  return hslToHex({ h: (hue + i * 90) % 360, s: 0.55, l: isDark(bg) ? 0.62 : 0.45 })
}

/** ThemeDef → 语义令牌表（双色/三色共用同一推导） */
export function resolveTokens(def: ThemeDef): Vars {
  const { primary, secondary, accent, dark, base } = def.colors
  const bg = base ?? neutralTint(primary, dark)
  return {
    '--c-bg': bg,
    '--c-fg': primary,
    '--c-faint': mix(bg, primary, 0.42),
    '--c-muted': mix(bg, primary, 0.6),
    '--c-line': mix(bg, secondary, 0.4),
    '--c-line-strong': mix(bg, secondary, 0.7),
    '--c-primary': primary,
    '--c-primary-fg': pickText(primary),
    '--c-secondary': secondary,
    '--c-accent': accent,
    '--c-accent-fg': pickText(accent),
    '--c-accent-hover': mix(accent, bg, 0.15),
    '--c-hint': mix(bg, accent, 0.6),
    '--c-mark': mix(bg, accent, 0.15),
    '--c-entity-1': entityColor(accent, bg, 0),
    '--c-entity-2': entityColor(accent, bg, 1),
    '--c-entity-3': entityColor(accent, bg, 2),
    '--c-entity-4': entityColor(accent, bg, 3),
    /* ---- MD3 角色（与上面的令牌同源，全部由三色现混派生）----
     * 升阶 = 把墨色往底色里掺：浅色主题自然变深、深色主题自然变亮，
     * 因此内置预设与用户自定义主题都无需单独配一张色板。
     * 分工：surface-container-* 是卡片/悬浮态的高低层次，surface-variant 是 chip 这类小色块底，
     * on-* 是各自底上的文字色，outline / outline-variant 是描边与分割线。 */
    '--c-surface-container-lowest': mix(bg, primary, 0.015),
    '--c-surface-container-low': mix(bg, primary, 0.04),
    '--c-surface-container': mix(bg, primary, 0.075),
    '--c-surface-container-high': mix(bg, primary, 0.11),
    '--c-surface-container-highest': mix(bg, primary, 0.15),
    '--c-surface-variant': mix(bg, secondary, 0.16),
    '--c-on-surface': primary,
    '--c-on-surface-variant': mix(bg, primary, 0.6),
    '--c-outline': mix(bg, secondary, 0.6),
    '--c-outline-variant': mix(bg, secondary, 0.28),
  }
}

/** 令牌写入 :root；返回写入的键值（供持久化快照） */
export function applyVars(vars: Vars): Vars {
  const root = document.documentElement.style
  for (const [k, v] of Object.entries(vars)) root.setProperty(k, v)
  return vars
}
