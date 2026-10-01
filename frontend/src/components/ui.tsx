/**
 * 共享 UI 原语（主题 v4）：统一按钮样式与内联图标。
 *
 * 两条并行出口，互为补充：
 *  1. 类名字符串 helper（btnPrimary / btnOutline / …）——兼容既有页面的
 *     `className={btnPrimary('rounded-md')}` 写法，零改动即获得主题驱动；
 *  2. <Button> 组件——新代码与需要装饰槽的场景使用，状态机与 a11y 一次写好。
 *
 * 视觉全部来自 .ui-btn（见 index.css）：形状层负责圆角/切角，深度层负责
 * 阴影/光晕，排印层负责字重与斜体，动效层负责时长与缓动。组件本身不含任何
 * 主题判断分支。
 */
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'

/** 常用圆角档位：pill 胶囊 / panel 面板内控件 / card 卡片级。
 *  注意：普通按钮的圆角主权在主题形状层（--ui-r-btn），工厂默认不再注入
 *  形状类——默认 fallback 999px（胶囊）与原 pill 形态一致；图标钮例外，
 *  圆形是跨主题交互惯例，仍默认 pill。 */
export const RADIUS = {
  pill: 'rounded-full',
  panel: 'rounded-md',
  card: 'rounded-lg',
} as const

export type BtnVariant = 'primary' | 'outline' | 'quiet' | 'danger' | 'icon'

/** 变体 → 类名 */
function variantClass(variant: BtnVariant): string {
  return `ui-btn--${variant}`
}

/* ------------------------------------------------ 类名 helper（兼容旧写法） */

/** 描边按钮（登出 / 面板次级主行动）；圆角跟随主题，显式传参可覆盖 */
export const btnOutline = (radius: string = '') => `ui-btn ui-btn--outline${radius ? ` ${radius}` : ''}`

/** 主行动按钮（保存 / 搜索）；圆角跟随主题，显式传参可覆盖 */
export const btnPrimary = (radius: string = '') => `ui-btn ui-btn--primary${radius ? ` ${radius}` : ''}`

/** 安静按钮（低优先级动作）；圆角跟随主题，显式传参可覆盖 */
export const btnQuiet = (radius: string = '') => `ui-btn ui-btn--quiet${radius ? ` ${radius}` : ''}`

/** 图标按钮（无描边圆形切换钮）；圆形为跨主题惯例，默认 pill */
export const btnIcon = (radius: string = RADIUS.pill) => `ui-btn ui-btn--icon ${radius}`

/** 破坏性动作；圆角跟随主题，显式传参可覆盖 */
export const btnDanger = (radius: string = '') => `ui-btn ui-btn--danger${radius ? ` ${radius}` : ''}`

/** 表格操作列小动作钮（Analyze 系列共用）；圆角走 --ui-r-btn-sm 控件刻度 */
export const miniBtn = 'ui-btn ui-btn--quiet ui-btn--sm'

/* ---------------------------------------------------------- Button 组件 */

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: BtnVariant
  /** sm 用于密集场景（表格操作列、工具栏） */
  size?: 'sm' | 'md'
  radius?: string
  /** 尾部装饰：Endfield 的箭头、BA 的流光等由主题 CSS 决定呈现 */
  deco?: ReactNode
  children?: ReactNode
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'outline', size = 'md', radius = '', deco, className = '', children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`${variantClass(variant)}${radius ? ` ${radius}` : ''}${
        size === 'sm' ? ' ui-btn--sm' : ''
      }${className ? ` ${className}` : ''}`}
      {...rest}
    >
      {children}
      {deco ? <span className="ui-btn__deco">{deco}</span> : null}
    </button>
  )
})

/* --------------------------------------------------------------- 图标 */

/**
 * 表单控件外壳：给 input / textarea 补上它们自己做不到的「描边环 + 切角 + 玻璃」。
 * 纯令牌实现，因此不需要按主题分叉。
 */
export function Control({
  children,
  className = '',
}: {
  children: ReactNode
  className?: string
}) {
  return <div className={`ui-control${className ? ` ${className}` : ''}`}>{children}</div>
}

export function IconPalette({ className = '' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinejoin="round"
      />
      <circle cx="13.5" cy="6.5" r="0.5" fill="currentColor" />
      <circle cx="17.5" cy="10.5" r="0.5" fill="currentColor" />
      <circle cx="6.5" cy="12.5" r="0.5" fill="currentColor" />
      <circle cx="8.5" cy="7.5" r="0.5" fill="currentColor" />
    </svg>
  )
}
