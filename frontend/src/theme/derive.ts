/**
 * 令牌派生器：色种子 + 六层投影 → 扁平 CSS 变量表。
 *
 * 分工：
 *   - 色彩层：由 3 色种子 + mix() 现混派生（含 WCAG 对比度选字、实体色相旋转）
 *   - 其余五层：直接取自 layers.ts 的主题清单
 *   - 用户覆盖层：优先级最高，最后写入（色种子 delta + 字体族）
 *
 * 输出是扁平的 `Vars`（键即 CSS 变量名），由 applyVars 一次性写入 :root。
 */
import { hexToHsl, hslToHex, isDark, mix, normalizeHex, pickText, readableText } from './color'
import {
  ENDFIELD_RHODES,
  ENDFIELD_RHODES_SCHEME,
  ENDFIELD_WINDOW_WORKBENCH,
  LAYERS,
  SKELETON,
} from './layers'
import { SEEDS } from './palette'
import { ASTROLABE_DEFAULT_CONFIG, ASTROLABE_PALETTES, astrolabeConfigVars } from './astrolabe'
import type { AstrolabeConfig, EndfieldConfig, OverrideState, Scheme, ThemeId, ThemeSeed, Vars } from './types'

/**
 * 分类色板（canvas 实体专用）：从点缀色色相出发旋转 90°，
 * 饱和度固定、亮度随底色深浅分档；点缀色接近无彩时从固定色相（215°）起。
 */
function entityColor(accent: string, bg: string, i: number): string {
  const { h, s } = hexToHsl(accent)
  const hue = s < 0.12 ? 215 : h
  return hslToHex({ h: (hue + i * 90) % 360, s: 0.55, l: isDark(bg) ? 0.62 : 0.45 })
}

/**
 * 语义色：固定色相 + 随底色深浅调整亮度。
 * 这样它们能跟随任意自定义主题，不再需要硬编码 red-500 / emerald-500。
 */
function semantic(hue: number, bg: string, s = 0.68): string {
  return hslToHex({ h: hue, s, l: isDark(bg) ? 0.66 : 0.5 })
}

/**
 * 解析一套完整令牌。
 * @param themeId 主题 id
 * @param scheme  明暗档
 * @param override 用户全局覆盖
 * @param endfield 终末地主题配置（色风格 / 窗口样式；仅 endfield 消费）
 */
export function resolveTheme(
  themeId: ThemeId,
  scheme: Scheme,
  override: OverrideState = { seed: {} },
  endfield: EndfieldConfig = { palette: 'default', window: 'default' },
  astrolabe: AstrolabeConfig = ASTROLABE_DEFAULT_CONFIG,
): Vars {
  /* ---------------- 1. 色种子（主题 → 用户 delta 覆盖） ---------------- */
  /* 优先级：手动覆盖点缀色 > 罗德岛档预设变体 > 主题默认 */
  const rhodes = themeId === 'endfield' && endfield.palette === 'rhodes'
  const workbench = themeId === 'endfield' && endfield.window === 'workbench'
  const base0 = themeId === 'astrolabe' ? ASTROLABE_PALETTES[astrolabe.palette][scheme] : SEEDS[themeId][scheme]
  const seed: ThemeSeed = {
    primary: override.seed.primary ?? base0.primary,
    secondary: base0.secondary,
    accent: override.seed.accent ?? (rhodes ? '#38c2ff' : base0.accent),
    base: override.seed.base ?? base0.base,
  }
  const { primary, secondary, accent, base: bg } = seed

  /* ---------------- 2. 色彩层 ---------------- */
  const danger = semantic(2, bg)
  const warn = semantic(40, bg)
  const ok = semantic(152, bg)

  const colorVars: Vars = {
    /* 骨架四角色 */
    '--c-bg': bg,
    '--c-fg': primary,
    '--c-secondary': secondary,

    /* 灰阶（五级：fg > fg-2 > muted > faint > dim，方向向底色衰减） */
    '--c-fg-2': mix(bg, primary, 0.8),
    '--c-muted': mix(bg, primary, 0.6),
    '--c-faint': mix(bg, primary, themeId === 'astrolabe' ? 0.65 : 0.42),
    '--c-dim': mix(bg, primary, 0.3),

    /* 线条 */
    '--c-line': mix(bg, secondary, 0.4),
    '--c-line-strong': mix(bg, secondary, 0.7),

    /* 主色 */
    '--c-primary': primary,
    '--c-primary-fg': pickText(primary),

    /* 点缀色 */
    '--c-accent': accent,
    '--c-accent-fg': pickText(accent),
    '--c-accent-hover': mix(accent, bg, 0.15),
    '--c-accent-soft': mix(bg, accent, 0.12),
    '--c-hint': themeId === 'astrolabe' ? mix(bg, primary, 0.65) : mix(bg, accent, 0.6),
    '--c-mark': mix(bg, accent, 0.15),

    /* 分类色板（图谱实体） */
    '--c-entity-1': entityColor(accent, bg, 0),
    '--c-entity-2': entityColor(accent, bg, 1),
    '--c-entity-3': entityColor(accent, bg, 2),
    '--c-entity-4': entityColor(accent, bg, 3),

    /* Material 角色阶梯（与三色同源现混，各主题共用同一批词汇） */
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

    /* 语义色（由底色深浅派生，跟随任意主题） */
    '--c-danger': danger,
    '--c-danger-soft': mix(bg, danger, 0.14),
    '--c-warn': warn,
    '--c-warn-soft': mix(bg, warn, 0.16),
    '--c-ok': ok,
    '--c-ok-soft': mix(bg, ok, 0.14),
    '--c-info': accent,
    /* 与底色对比最高的纯色（贴纸 / 反白场景） */
    '--c-contrast': pickText(bg),
  }

  /* ---------------- 3. 层清单（六层 + 按钮层 → 分档覆盖 → 终末地配置变体） ---------------- */
  const L = LAYERS[themeId]
  const layerVars: Vars = {
    ...L.shape,
    ...L.depth,
    ...L.surface,
    ...L.typo,
    ...L.motion,
    ...(L.btn ?? {}),
    ...(L.overrides?.[scheme] ?? {}),
    /* 罗德岛档：覆写各层黄色硬编码位（蓝光晕 / 蓝晕染 / 蓝提亮压暗） */
    ...(rhodes
      ? { ...ENDFIELD_RHODES, ...(ENDFIELD_RHODES_SCHEME[scheme] ?? {}) }
      : {}),
    /* 工作台档：FloatWindow 窗口令牌族（哨兵式——存在即换装） */
    ...(workbench ? ENDFIELD_WINDOW_WORKBENCH : {}),
    ...(themeId === 'astrolabe' ? astrolabeConfigVars(astrolabe, scheme) : {}),
  }

  /* ---------------- 4. 用户覆盖层（最高优先级） ---------------- */
  if (override.fontSans) layerVars['--ui-font-sans'] = override.fontSans
  if (override.headingStyle) layerVars['--ui-heading-style'] = override.headingStyle

  /* 合并顺序契约（勿重排）：layerVars 在 colorVars 之后，因此层的
     overrides[scheme] 可覆写派生色（--c-*）；layerVars 内部则按
     shape→depth→surface→typo→motion→btn→分档覆盖 的顺序后者胜 */
  const vars = { ...SKELETON, ...colorVars, ...layerVars }
  // These are useful text roles, not decoration. Apply the floor after scheme overrides.
  for (const role of ['--c-muted', '--c-faint', '--c-dim', '--c-hint', '--c-on-surface-variant']) {
    const color = normalizeHex(vars[role])
    const minimum = role === '--c-muted' || role === '--c-on-surface-variant' ? 6 : role === '--c-faint' ? 4.8 : 4.5
    if (color) vars[role] = readableText(color, bg, minimum)
  }
  if (workbench) {
    for (const role of ['muted', 'faint', 'dim']) {
      const key = `--ui-win-c-${role}`
      const color = normalizeHex(vars[key])
      if (color) vars[key] = readableText(color, vars['--ui-win-c-bg'], role === 'muted' ? 6 : role === 'faint' ? 4.8 : 4.5)
    }
  }
  return vars
}

/**
 * 上一次写入 :root 的令牌键集。
 * 各主题登记的令牌集不同（如 BA 的 --ui-btn-shear / 终末地的 --ui-card-fill），
 * 而内联样式不会自动回退：若不清理，上一套主题的遗留令牌会继续生效，
 * 表现为「切主题后部分样式不刷新，刷新页面才恢复」。
 */
let appliedKeys: string[] = []

/** 令牌写入 :root，并把主题/明暗写进 data 属性（供无法变量化的 CSS 使用） */
export function applyVars(vars: Vars, themeId: ThemeId, scheme: Scheme): Vars {
  const root = document.documentElement
  const style = root.style
  /* 先清残留：上一套登记、本套未登记的令牌必须移除，否则会压过 CSS 兜底值 */
  for (const k of appliedKeys) if (!(k in vars)) style.removeProperty(k)
  for (const [k, v] of Object.entries(vars)) style.setProperty(k, v)
  appliedKeys = Object.keys(vars)
  root.dataset.theme = themeId
  root.dataset.scheme = scheme
  style.colorScheme = scheme
  return vars
}
