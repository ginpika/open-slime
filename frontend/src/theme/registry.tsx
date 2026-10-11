/**
 * 主题接线注册表 —— 「主题 id → 组件」的唯一定义处（组合层 / composition root）。
 *
 * 一个主题由三件东西组成，全部在这里登记：
 *   kit    页面内构件的渲染包（PageKit 契约，见 kit/types.ts）
 *   shell  外壳（ShellProps 契约，见 shell/types.ts）
 *   panel  主题菜单（三份实现共用 ThemePanelProps）
 *
 * 消费入口（kit/index.tsx 的 usePageKit、shell/index.tsx 的 AppShell、本文件的
 * ThemePanel）都从这里读取，于是新增一个主题只需：
 *   ① types.ts 的 ThemeId 联合加一项
 *   ② palette.ts 加色种子、layers.ts 加六层登记簿（两处均为 Record<ThemeId>）
 *   ③ 本文件 THEMES 加一条记录
 * 前两步漏写会直接编译报错（Record<ThemeId> 穷尽检查），不再依赖人工记得
 * 去改 kit / shell / ThemePanel 三份平行映射。
 *
 * 依赖方向（勿反转，否则成环）：
 *   registry → kit/* + shell/* + theme/*ThemePanel（均为叶子实现）
 *   反向：kit/index.tsx、shell/index.tsx、App.tsx → registry
 * 因此 theme/ 下的纯数据模块（palette / layers / derive / store / color）不得
 * 反向 import 本文件——它们必须保持零 React 依赖。
 */
import type { ComponentType } from 'react'
import { blueArchiveKit } from '../kit/bluearchive'
import { endfieldKit } from '../kit/endfield'
import { flatKit } from '../kit/flat'
import type { PageKit } from '../kit/types'
import { BlueArchiveShell } from '../shell/BlueArchiveShell'
import { EndfieldShell } from '../shell/EndfieldShell'
import { FlatThemeShell } from '../shell/FlatShell'
import type { ShellProps } from '../shell/types'
import { EndfieldThemePanel } from './EndfieldThemePanel'
import { MonoThemePanel } from './MonoThemePanel'
import { DefaultThemePanel } from './ThemePanel'
import { useThemeState } from './store'
import type { ThemeId } from './types'

/** 主题菜单统一 props（EndfieldThemePanel / MonoThemePanel / DefaultThemePanel 共用） */
export type ThemePanelProps = {
  /** 开合状态由 App 持有：切主题会换 shell 组件（子树重挂），面板内部状态会被重置 */
  open: boolean
  onOpenChange: (next: boolean) => void
  /**
   * 触发按钮形态：
   *   plain  —— 跟随主题（外壳内，见 ThemePanel 默认样式）
   *   chrome —— 桌面框架标题栏的深色轨按钮（与窗口按钮同风格）
   */
  variant?: 'plain' | 'chrome'
}

export type ThemeEntry = {
  /** 页面内构件渲染包 */
  kit: PageKit
  /** 外壳：三套实现接收同一份 ShellProps */
  shell: ComponentType<ShellProps>
  /** 主题菜单 */
  panel: ComponentType<ThemePanelProps>
}

/** 主题 id → 组件。Record<ThemeId> 保证穷尽：漏登记一个主题即编译报错 */
export const THEMES: Record<ThemeId, ThemeEntry> = {
  old: { kit: flatKit, shell: FlatThemeShell, panel: MonoThemePanel },
  endfield: { kit: endfieldKit, shell: EndfieldShell, panel: EndfieldThemePanel },
  bluearchive: { kit: blueArchiveKit, shell: BlueArchiveShell, panel: DefaultThemePanel },
}

/** 当前主题的菜单（App 的 tools 插槽入口） */
export function ThemePanel({ open, onOpenChange, variant }: ThemePanelProps) {
  const Panel = THEMES[useThemeState().themeId].panel
  return <Panel open={open} onOpenChange={onOpenChange} variant={variant} />
}
