import type { ReactNode, RefObject } from 'react'

/**
 * 外壳契约：所有 kit（flat / endfield / bluearchive）必须实现同一份 props。
 *
 * 这是"一个契约 × N 个皮肤"的落点——kit 可以完全不同地组织 DOM、布局与装饰，
 * 但对外暴露的输入必须一致，且不允许自己实现路由/用户/主题逻辑。
 */
/** 用户菜单插槽参数：由外壳按自身排布决定（顶部图标钮向下弹、底部栏向上弹并展示昵称） */
export type UserSlotProps = {
  /** 弹出方向：底部栏向上，顶部栏向下 */
  placement: 'up' | 'down'
  /** 是否在按钮内展示昵称（底部栏展示，顶部图标钮不展示） */
  withName: boolean
}

/** 用户菜单插槽：外壳只决定位置与形态，不实现用户逻辑 */
export type UserSlotRender = (props: UserSlotProps) => ReactNode

export type ShellProps = {
  /** 当前路由（hash 路径，如 '/graph'、'/article/xxx'） */
  route: string
  /** 当前路由归属的导航项 path（子路由已归一） */
  activePath: string
  /** 页面自管滚动（图谱 / 圆桌 / 工作空间）：外壳不再套滚动容器与内边距 */
  fullBleed: boolean
  /** 主题面板节点（行为共享，位置由 kit 决定） */
  tools: ReactNode
  /** 用户菜单插槽（位置与弹出方向由 kit 决定） */
  userSlot: UserSlotRender
  /** 滚动容器 ref（App 用于切路由时回到顶部） */
  mainRef?: RefObject<HTMLElement | null>
  children: ReactNode
}
