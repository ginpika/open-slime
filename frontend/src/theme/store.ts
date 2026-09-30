/** 主题状态：模块级 store + useSyncExternalStore + localStorage 持久化 */
import { useSyncExternalStore } from 'react'
import { applyVars, resolveTokens } from './apply'
import { BUILTIN_PRESETS, DEFAULT_DARK_ID, DEFAULT_PRESET_ID, findBuiltin } from './presets'
import { desaturate, isDark, mix } from './color'
import type { Appearance, ThemeDef, ThemeMode, ThemeState } from './types'

const KEY = 'minis.theme.v3'
/** v2 key（双色/三色两套结构），检测到时迁移后清理 */
const LEGACY_KEY_V2 = 'minis.theme.v2'
/** v1 key（含 userCss/overrides），迁移时清理 */
const LEGACY_KEY_V1 = 'minis.theme.v1'

function defaultState(): ThemeState {
  const mono = findBuiltin(DEFAULT_PRESET_ID) ?? BUILTIN_PRESETS[0]
  return {
    v: 3,
    currentId: mono.id,
    custom: [],
    draft: null,
    vars: resolveTokens(mono),
    appearance: 'light',
    lastLightId: mono.id,
    lastDarkId: DEFAULT_DARK_ID,
  }
}

/* ---------------- 主题深浅判断 ---------------- */

/** 主题深浅：按解析后的底色亮度判定 */
function slotOf(def: ThemeDef): 'light' | 'dark' {
  return isDark(resolveTokens(def)['--c-bg']) ? 'dark' : 'light'
}

function defById(s: ThemeState, id: string): ThemeDef | undefined {
  return findBuiltin(id) ?? s.custom.find((t) => t.id === id)
}

/** 外观槽位目标主题：id 无效或深浅不符时回退默认浅色/深色预设 */
function slotTheme(s: ThemeState, id: string, wantDark: boolean): ThemeDef {
  const def = defById(s, id)
  if (def && slotOf(def) === (wantDark ? 'dark' : 'light')) return def
  return (wantDark ? findBuiltin(DEFAULT_DARK_ID) : findBuiltin(DEFAULT_PRESET_ID)) ?? BUILTIN_PRESETS[0]
}

/** 系统深浅偏好 */
function sysDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    !!window.matchMedia &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

/** appearance 当前实际应取的深浅（system 档看系统） */
function effectiveDark(s: ThemeState): boolean {
  return s.appearance === 'dark' || (s.appearance === 'system' && sysDark())
}

/* ---------------- v2 → v3 迁移 ---------------- */

type LegacyColors =
  | { mode: 'duotone'; base: string; core: string }
  | {
      mode: 'tritone'
      primary: string
      secondary: string
      accent: string
      dark: boolean
      base?: string
    }
type LegacyDef = { id: string; name: string; mode: string; colors: LegacyColors }

/** v2 旧结构主题 → v3 统一结构（尽量保持视觉：旧核心色/旧主色照搬，旧三色的派生墨色算回主色） */
function migrateDef(d: LegacyDef | null | undefined): ThemeDef | null {
  if (!d || typeof d !== 'object' || !d.colors) return null
  const c = d.colors
  if (c.mode === 'duotone' && typeof c.base === 'string' && typeof c.core === 'string') {
    return {
      id: d.id,
      name: d.name || '自定义',
      mode: 'duotone',
      colors: {
        primary: c.core,
        secondary: c.core,
        accent: c.core,
        dark: isDark(c.base),
        base: c.base,
      },
    }
  }
  if (
    c.mode === 'tritone' &&
    typeof c.primary === 'string' &&
    typeof c.secondary === 'string' &&
    typeof c.accent === 'string'
  ) {
    /* 旧三色：primary 是按钮/链接主色 → 迁移为新点缀色；新主色取旧派生墨色（正文观感不变） */
    const ink = c.dark ? '#f2f2f2' : '#141414'
    const fg = mix(ink, desaturate(c.primary, 0.2), 0.18)
    return {
      id: d.id,
      name: d.name || '自定义',
      mode: 'tritone',
      colors: {
        primary: fg,
        secondary: c.secondary,
        accent: c.primary,
        dark: !!c.dark,
        base: c.base,
      },
    }
  }
  return null
}

/** v3 结构校验（custom 数组元素兜底） */
function isValidDef(d: unknown): d is ThemeDef {
  const c = (d as ThemeDef | undefined)?.colors
  return (
    !!c &&
    typeof c.primary === 'string' &&
    typeof c.secondary === 'string' &&
    typeof c.accent === 'string'
  )
}

function load(): ThemeState {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ThemeState>
      const base = defaultState()
      const merged: ThemeState = {
        ...base,
        ...parsed,
        v: 3,
        custom: Array.isArray(parsed.custom) ? parsed.custom.filter(isValidDef) : [],
        vars: typeof parsed.vars === 'object' && parsed.vars ? parsed.vars : base.vars,
        appearance: parsed.appearance ?? base.appearance,
        lastLightId: parsed.lastLightId ?? base.lastLightId,
        lastDarkId: parsed.lastDarkId ?? base.lastDarkId,
      }
      /* 旧 v3 数据（无外观字段）：按当前主题深浅推断初值 */
      if (!parsed.appearance || !parsed.lastLightId || !parsed.lastDarkId) {
        const def = activeDef(merged)
        const slot = slotOf(def)
        const id = merged.currentId === 'draft' ? null : def.id
        merged.appearance = parsed.appearance ?? slot
        if (!parsed.lastLightId) merged.lastLightId = slot === 'light' && id ? id : base.lastLightId
        if (!parsed.lastDarkId) merged.lastDarkId = slot === 'dark' && id ? id : base.lastDarkId
      }
      return merged
    }
    const rawV2 = localStorage.getItem(LEGACY_KEY_V2)
    if (rawV2) {
      const old = JSON.parse(rawV2) as {
        currentId?: string
        custom?: LegacyDef[]
        draft?: LegacyDef | null
      }
      const custom = (Array.isArray(old.custom) ? old.custom : [])
        .map(migrateDef)
        .filter((d): d is ThemeDef => d !== null)
      const draft = migrateDef(old.draft)
      const migrated: ThemeState = {
        ...defaultState(),
        currentId: draft ? 'draft' : (old.currentId ?? DEFAULT_PRESET_ID),
        custom,
        draft,
      }
      migrated.vars = resolveTokens(activeDef(migrated))
      /* 立即落盘 v3：否则下次刷新 v2/v3 都不存在，迁移结果会丢失 */
      try {
        localStorage.setItem(KEY, JSON.stringify(migrated))
      } catch {
        // 忽略持久化失败（隐私模式等）
      }
      localStorage.removeItem(LEGACY_KEY_V2)
      localStorage.removeItem(LEGACY_KEY_V1)
      return migrated
    }
    localStorage.removeItem(LEGACY_KEY_V1)
    return defaultState()
  } catch {
    return defaultState()
  }
}

let state: ThemeState = defaultState()
const listeners = new Set<() => void>()

/** 当前生效主题（draft 优先，其次 builtin/custom，兜底 Mono） */
export function activeDef(s: ThemeState = state): ThemeDef {
  if (s.currentId === 'draft' && s.draft) return s.draft
  return (
    findBuiltin(s.currentId) ??
    s.custom.find((t) => t.id === s.currentId) ??
    findBuiltin(DEFAULT_PRESET_ID) ??
    BUILTIN_PRESETS[0]
  )
}

function persist(s: ThemeState): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(s))
  } catch {
    // 隐私模式等场景忽略持久化失败
  }
}

/** 应用当前主题到 DOM（变量写入 :root）并持久化 */
function commit(s: ThemeState): void {
  const def = activeDef(s)
  const vars = resolveTokens(def)
  applyVars(vars)
  state = { ...s, vars }
  persist(state)
  listeners.forEach((l) => l())
}

function init(): void {
  const loaded = load()
  const def = activeDef(loaded)
  /* 快照只服务于 index.html 的首帧防闪烁；运行期一律以当前主题现算为准——
     持久化的快照可能来自旧版本（缺后来新增的令牌） */
  const vars = applyVars(resolveTokens(def))
  state = { ...loaded, vars }
}

/* 跟随系统：系统深浅变化时切换到对应槽位主题（保持 system 档不动） */
if (typeof window !== 'undefined' && window.matchMedia) {
  try {
    window
      .matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => {
        if (state.appearance !== 'system') return
        const wantDark = sysDark()
        const def = slotTheme(state, wantDark ? state.lastDarkId : state.lastLightId, wantDark)
        const next = applyTheme(state, def.id)
        commit(next)
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

/* ---------- 内部：主题切换 + 槽位记录 ---------- */

/** 切换到正式主题并按深浅记录槽位（不改变 appearance） */
function applyTheme(s: ThemeState, id: string): ThemeState {
  const def = defById(s, id) ?? (findBuiltin(DEFAULT_PRESET_ID) ?? BUILTIN_PRESETS[0])
  const slot = slotOf(def)
  return {
    ...s,
    currentId: def.id,
    draft: null,
    lastLightId: slot === 'light' ? def.id : s.lastLightId,
    lastDarkId: slot === 'dark' ? def.id : s.lastDarkId,
  }
}

/* ---------- actions ---------- */

export const themeActions = {
  /** 选择预设/自定义主题：记录槽位，并把外观档切到该主题深浅 */
  select(id: string): void {
    const def = defById(state, id)
    if (!def) return
    const slot = slotOf(def)
    commit({
      ...applyTheme(state, id),
      appearance: slot,
    })
  },

  /** 外观开关：切换到上次使用的浅色/深色主题；跟随系统时按系统偏好并实时响应变化 */
  setAppearance(ap: Appearance): void {
    const wantDark = ap === 'dark' || (ap === 'system' && sysDark())
    const def = slotTheme(state, wantDark ? state.lastDarkId : state.lastLightId, wantDark)
    commit({
      ...applyTheme(state, def.id),
      appearance: ap,
    })
  },

  /** 编辑自定义草稿（基于指定主题派生） */
  editDraft(from?: ThemeDef): void {
    const base = from ?? activeDef(state)
    const draft: ThemeDef = { ...base, id: 'draft', builtin: undefined }
    commit({ ...state, currentId: 'draft', draft })
  },

  /** 更新草稿颜色/名称/模式 */
  patchDraft(
    patch: Partial<ThemeDef['colors']>,
    opts?: { name?: string; mode?: ThemeMode },
  ): void {
    if (!state.draft) return
    const colors = { ...state.draft.colors, ...patch } as ThemeDef['colors']
    const draft: ThemeDef = {
      ...state.draft,
      colors,
      ...(opts?.mode ? { mode: opts.mode } : {}),
      ...(opts?.name !== undefined ? { name: opts.name } : {}),
    }
    commit({ ...state, currentId: 'draft', draft })
  },

  /** 放弃草稿：回到当前外观档的上次使用主题 */
  cancelDraft(): void {
    const wantDark = effectiveDark(state)
    const def = slotTheme(state, wantDark ? state.lastDarkId : state.lastLightId, wantDark)
    commit(applyTheme(state, def.id))
  },

  /** 保存草稿为自定义主题；editingId 存在时原地更新（保存后即正式启用，记录槽位） */
  saveDraft(name: string, editingId?: string): string | null {
    if (!state.draft) return null
    const id = editingId ?? `custom-${Date.now().toString(36)}`
    const def: ThemeDef = { ...state.draft, id, name: name.trim() || '自定义', builtin: undefined }
    const custom = editingId
      ? state.custom.map((t) => (t.id === editingId ? def : t))
      : [...state.custom, def]
    const slot = slotOf(def)
    commit({
      ...state,
      currentId: id,
      custom,
      draft: null,
      appearance: slot,
      lastLightId: slot === 'light' ? id : state.lastLightId,
      lastDarkId: slot === 'dark' ? id : state.lastDarkId,
    })
    return id
  },

  deleteCustom(id: string): void {
    const custom = state.custom.filter((t) => t.id !== id)
    if (state.currentId === id) {
      /* 删除的是当前主题：回到当前外观档的槽位主题 */
      const wantDark = effectiveDark(state)
      const def = slotTheme({ ...state, custom }, wantDark ? state.lastDarkId : state.lastLightId, wantDark)
      commit(applyTheme({ ...state, custom }, def.id))
    } else {
      commit({ ...state, custom })
    }
  },

  resetAll(): void {
    localStorage.removeItem(KEY)
    commit(defaultState())
  },
}

/** 供 index.html 之外的入口（main.tsx）在首帧前调用 */
export function initTheme(): void {
  init()
}

/** 当前令牌快照（供渲染版本注册表 ctx 读取） */
export function currentVars(): Record<string, string> {
  return state.vars
}
