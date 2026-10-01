/**
 * 页面级 Kit 契约 —— 「同一份 props，不同的视觉实现」
 *
 * 与外壳 Kit（src/shell）同一套哲学，但作用在**页面内部**：
 *   行为/数据/事件由页面自己持有（Stats.tsx 只管取数与时序），
 *   呈现完全交给 kit。于是同一个页面在五套设计语言下可以是
 *   截然不同的密度、章法与装饰，而数据逻辑一份都不重复。
 *
 * 允许 kit 改变 DOM 结构、密度、装饰与排布 —— 这是与「只换令牌」的关键区别。
 * 但所有 kit 必须实现同一份 props，且不持有业务状态。
 */
import type { FC, ReactNode } from 'react'

export type KitId = 'flat' | 'endfield' | 'bluearchive'

/* ------------------------------------------------------------ 页面骨架 */

export type PageProps = {
  /** 中文标题 */
  title: string
  /** 拉丁标题（Endfield 会渲染成全大写编号，BA 渲染成斜体副标） */
  en: string
  /** 标题右侧的补充信息 */
  meta?: ReactNode
  children: ReactNode
}

/* ------------------------------------------------------------------ 面板 */

export type PanelProps = {
  title: string
  /** 面板右侧的等宽备注（Endfield 用 code，BA 用徽章） */
  meta?: ReactNode
  className?: string
  children: ReactNode
}

/* -------------------------------------------------------------- 关键指标 */

export type StatProps = {
  label: string
  value: string
  /** 单位（小字，紧随数值） */
  unit?: string
  /** 底部补充说明 */
  hint?: string
  /** 0–100：Endfield 渲染成分段刻度条，BA 渲染成圆角进度条 */
  progress?: number
  tone?: 'default' | 'accent' | 'muted'
  /** 是否作为主指标（更大字号） */
  hero?: boolean
}

/* -------------------------------------------------------------- 范围切换 */

export type RangeProps = {
  options: { value: number; label: string }[]
  value: number
  onChange: (v: number) => void
}

/* -------------------------------------------------------------- 分布行 */

export type BarProps = {
  name: string
  value: string
  sub?: string
  /** 0–1 的相对占比 */
  ratio: number
  /** 序号（Endfield 用等宽前导零） */
  index: number
}

/* -------------------------------------------------------------- 提示条 */

export type NoteProps = {
  children: ReactNode
  tone?: 'info' | 'warn'
}

/* -------------------------------------------------------------- 工具条 */

export type ToolbarProps = {
  left?: ReactNode
  right?: ReactNode
  className?: string
}

/* -------------------------------------------------------------- 步骤条 */

export type StepItem = { label: string; state: 'done' | 'current' | 'todo' }
export type StepsProps = { items: StepItem[] }

/* ---------------------------------------------------------------- 徽章 */

export type BadgeTone = 'default' | 'accent' | 'ok' | 'warn' | 'danger'
export type BadgeProps = { children: ReactNode; tone?: BadgeTone }

/* -------------------------------------------------------------- 列表行 */

/**
 * 列表/表格行的唯一容器。
 * 三套语言的差异在这里最直观：flat 与 Endfield 用分隔线，BlueArchive 用圆角块 + 间隙。
 */
export type RowProps = {
  children: ReactNode
  href?: string
  onClick?: () => void
  className?: string
  disabled?: boolean
}

/* ---------------------------------------------------------------- 空态 */

export type EmptyProps = { children: ReactNode }

/* -------------------------------------------------------------- 网格单元 */

/**
 * 卡片墙的单元。与 Panel 的差别：没有头部条，纯粹是"一块内容"，
 * 用来铺网格（成员卡 / 名册卡 / 功能入口）。
 */
export type TileProps = {
  children: ReactNode
  href?: string
  /** 虚线占位（"新建"这类空位） */
  dashed?: boolean
  onClick?: () => void
  className?: string
}

/* -------------------------------------------------------------- 表单字段 */

/**
 * 字段 = 标签 + 控件（可自动包一层 .ui-control 外壳）+ 提示。
 * 标签的字重/大小写/字距由主题排印层决定，所以三套语言的"表单观感"天然不同。
 */
export type FieldProps = {
  label: string
  hint?: string
  children: ReactNode
  /** 是否自动包一层 .ui-control 外壳（替换元素式控件传 false） */
  wrap?: boolean
  className?: string
}

/* -------------------------------------------------------------- Kit */

export type PageKit = {
  id: KitId
  /**
   * 以下三个是**布局**而非样式：章法差异（是否铺满、几栏、密度）本身就是设计语言的一部分，
   * 所以它属于 kit 的职责，由页面读取后套在容器上。
   */
  /** 关键指标区的排布 */
  statRow: string
  /** 面板区排布 */
  panelGrid: string
  /** 需要横跨整行的面板（趋势图等） */
  panelWide: string
  Page: FC<PageProps>
  Panel: FC<PanelProps>
  Stat: FC<StatProps>
  Range: FC<RangeProps>
  Bar: FC<BarProps>
  Note: FC<NoteProps>
  Toolbar: FC<ToolbarProps>
  Steps: FC<StepsProps>
  Badge: FC<BadgeProps>
  Row: FC<RowProps>
  Empty: FC<EmptyProps>
  Tile: FC<TileProps>
  Field: FC<FieldProps>
}
