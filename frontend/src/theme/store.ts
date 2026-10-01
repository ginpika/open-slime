/**
 * 主题状态 v4：模块级 store + useSyncExternalStore + localStorage 持久化。
 *
 * 状态形状（见 types.ts）：
 *   themeId     5 套主题之一
 *   schemeMode  light | dark | system
 *   scheme      解析后的实际明暗
 *   override    全局用户覆盖（色种子 delta + 字体）
 *   vars        完整令牌快照（供 index.html 防闪烁与 canvas 读取）
 */
import { useSyncExternalStore } from 'react'
import { applyVars, resolveTheme } from './derive'
import { DEFAULT_THEME_ID, SEEDS, THEME_IDS } from './palette'
import type {
  EndfieldConfig,
  EndfieldPalette,
  EndfieldWindowStyle,
  OverrideState,
  Scheme,
  SchemeMode,
  ThemeId,
  ThemeState,
  Vars,
} from './types'

const KEY = 'minis.theme.v4'
/** v3（双色/三色 + 自定义主题编辑器）→ 迁移后清理 */
const LEGACY_V3 = 'minis.theme.v3'

const EMPTY_OVERRIDE: OverrideState = { seed: {} }

/** 系统深浅偏好 */
function sysDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    !!window.matchMedia &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/** schemeMode → 实际明暗 */
function effectiveScheme(mode: SchemeMode): Scheme {
  if (mode === 'system') return sysDark() ? 'dark' : 'light'
  return mode
}

function resolveVars(
  themeId: ThemeId,
  scheme: Scheme,
  override: OverrideState,
  endfield: EndfieldConfig,
): Vars {
  return resolveTheme(themeId, scheme, override, endfield)
}

const DEFAULT_ENDFIELD: EndfieldConfig = { palette: 'default', window: 'default' }

function buildState(
  themeId: ThemeId,
  schemeMode: SchemeMode,
  override: OverrideState,
  endfield: EndfieldConfig = DEFAULT_ENDFIELD,
): ThemeState {
  const scheme = effectiveScheme(schemeMode)
  return {
    v: 4,
    themeId,
    schemeMode,
    scheme,
    override,
    endfield,
    vars: resolveVars(themeId, scheme, override, endfield),
  }
}

function defaultState(): ThemeState {
  // 默认浅色，跟随系统偏好做初次判定
  return buildState(DEFAULT_THEME_ID, 'system', EMPTY_OVERRIDE)
}

function isThemeId(v: unknown): v is ThemeId {
  return typeof v === 'string' && (THEME_IDS as string[]).includes(v)
}

function isSchemeMode(v: unknown): v is SchemeMode {
  return v === 'light' || v === 'dark' || v === 'system'
}

function isEndfieldPalette(v: unknown): v is EndfieldPalette {
  return v === 'default' || v === 'rhodes'
}

function isEndfieldWindowStyle(v: unknown): v is EndfieldWindowStyle {
  return v === 'default' || v === 'workbench'
}

/** 规范化终末地配置（逐字段兜底；兼容旧字段 endfieldPalette → endfield.palette） */
function normalizeEndfield(raw: unknown, legacyPalette: unknown): EndfieldConfig {
  const r = (raw ?? {}) as Partial<EndfieldConfig>
  return {
    palette: isEndfieldPalette(r.palette)
      ? r.palette
      : legacyPalette === 'rhodes'
        ? 'rhodes'
        : 'default',
    window: isEndfieldWindowStyle(r.window) ? r.window : 'default',
  }
}

/** 规范化持久化的覆盖对象（防脏数据） */
function normalizeOverride(raw: unknown): OverrideState {
  const r = (raw ?? {}) as Partial<OverrideState>
  const seed = (r.seed ?? {}) as Record<string, unknown>
  const out: OverrideState = { seed: {} }
  for (const k of ['primary', 'accent', 'base'] as const) {
    const v = seed[k]
    if (typeof v === 'string' && /^#[0-9a-f]{6}$/i.test(v)) out.seed[k] = v.toLowerCase()
  }
  if (typeof r.fontSans === 'string' && r.fontSans) out.fontSans = r.fontSans
  if (typeof r.fontMono === 'string' && r.fontMono) out.fontMono = r.fontMono
  if (r.headingStyle === 'normal' || r.headingStyle === 'italic') out.headingStyle = r.headingStyle
  return out
}

function load(): ThemeState {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const p = JSON.parse(raw) as Partial<ThemeState> & { endfieldPalette?: unknown }
      return buildState(
        isThemeId(p.themeId) ? p.themeId : DEFAULT_THEME_ID,
        isSchemeMode(p.schemeMode) ? p.schemeMode : 'system',
        normalizeOverride(p.override),
        normalizeEndfield(p.endfield, p.endfieldPalette),
      )
    }
    /* v3 → v4：旧主题全部归入 old，明暗档沿用 appearance */
    const rawV3 = localStorage.getItem(LEGACY_V3)
    if (rawV3) {
      const old = JSON.parse(rawV3) as { appearance?: string }
      const mode: SchemeMode =
        old.appearance === 'light' || old.appearance === 'dark' || old.appearance === 'system'
          ? old.appearance
          : 'system'
      const migrated = buildState(DEFAULT_THEME_ID, mode, EMPTY_OVERRIDE)
      localStorage.setItem(KEY, JSON.stringify(migrated))
      localStorage.removeItem(LEGACY_V3)
      return migrated
    }
    return defaultState()
  } catch {
    return defaultState()
  }
}

let state: ThemeState = defaultState()
const listeners = new Set<() => void>()

function persist(s: ThemeState): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(s))
  } catch {
    /* 隐私模式等场景忽略 */
  }
}

/** 应用令牌到 DOM 并持久化、通知订阅者 */
function commit(next: ThemeState): void {
  applyVars(next.vars, next.themeId, next.scheme)
  state = next
  persist(next)
  listeners.forEach((l) => l())
}

function init(): void {
  const loaded = load()
  applyVars(loaded.vars, loaded.themeId, loaded.scheme)
  state = loaded
}

/* 跟随系统：系统深浅变化时（仅在 system 档）重新解析并应用 */
if (typeof window !== 'undefined' && window.matchMedia) {
  try {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
        if (state.schemeMode !== 'system') return
        commit(buildState(state.themeId, 'system', state.override, state.endfield))
      })
  } catch {
    /* 老浏览器忽略 */
  }
}

export function subscribe(l: () => void): () => void {
  listeners.add(l)
  return () => listeners.delete(l)
}

export function getState(): ThemeState {
  return state
}

export function useThemeState(): ThemeState {
  return useSyncExternalStore(subscribe, getState)
}

/** 当前令牌快照（供渲染版本注册表 ctx 读取） */
export function currentVars(): Vars {
  return state.vars
}

/* ------------------------------------------------------------- actions */

export const themeActions = {
  /** 切换主题（保留明暗档与全局覆盖） */
  setTheme(id: ThemeId): void {
    if (!isThemeId(id) || id === state.themeId) return
    commit(buildState(id, state.schemeMode, state.override, state.endfield))
  },

  /** 切换外观档位（浅 / 深 / 跟随系统） */
  setSchemeMode(mode: SchemeMode): void {
    if (!isSchemeMode(mode)) return
    commit(buildState(state.themeId, mode, state.override, state.endfield))
  },

  /** 浅/深一键对调（不改变 system 档语义：一旦手动切换即固定为对应档） */
  toggleScheme(): void {
    const next: SchemeMode = state.scheme === 'dark' ? 'light' : 'dark'
    commit(buildState(state.themeId, next, state.override, state.endfield))
  },

  /** 更新全局覆盖（色种子 delta / 字体） */
  setOverride(patch: Partial<OverrideState>): void {
    const merged: OverrideState = {
      ...state.override,
      ...patch,
      seed: { ...state.override.seed, ...(patch.seed ?? {}) },
    }
    commit(buildState(state.themeId, state.schemeMode, merged, state.endfield))
  },

  /** 清除全局覆盖，回到主题原始种子与字体 */
  resetOverride(): void {
    commit(buildState(state.themeId, state.schemeMode, { seed: {} }, state.endfield))
  },

  /** 更新终末地主题配置（色风格 / 窗口样式；仅 endfield 消费，增量合并） */
  setEndfieldConfig(patch: Partial<EndfieldConfig>): void {
    const next: EndfieldConfig = { ...state.endfield, ...patch }
    commit(buildState(state.themeId, state.schemeMode, state.override, next))
  },

  /** 恢复出厂：默认主题 + 跟随系统 + 无覆盖 */
  reset(): void {
    try {
      localStorage.removeItem(KEY)
      localStorage.removeItem(LEGACY_V3)
    } catch {
      /* 忽略 */
    }
    commit(defaultState())
  },
}

/** 供 main.tsx 在首帧前调用 */
export function initTheme(): void {
  init()
}

/** 主题选择面板用的种子读取（不含用户覆盖） */
export function rawSeed(id: ThemeId, scheme: Scheme) {
  return SEEDS[id][scheme]
}
