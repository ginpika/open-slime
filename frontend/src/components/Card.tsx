/**
 * 主题化卡片（主题 v4）。
 *
 * 结构：外壳画 1px 描边环（切角由 --ui-clip 承担），内层画"面板底"——
 * 面板底的透明度与模糊来自材质层，于是同一棵 DOM 在 Endfield 深档是
 * 碳黑玻璃、在 BlueArchive 是纯白实底、在 Tailwind Mono 是 surface 容器。
 *
 * 组件内部不含任何主题判断，全部视觉由令牌决定。
 */
import type { CSSProperties, HTMLAttributes, ReactNode } from 'react'

const PAD = {
  none: '',
  sm: 'p-3',
  md: 'p-4',
  lg: 'p-5',
} as const

/** 面板底色调（映射到同一批 surface 角色词汇，各主题通用） */
const TONE: Record<string, string | undefined> = {
  default: undefined,
  low: 'var(--c-surface-container-low)',
  high: 'var(--c-surface-container-high)',
  accent: 'var(--c-accent-soft)',
}

export type CardProps = HTMLAttributes<HTMLDivElement> & {
  tone?: keyof typeof TONE
  pad?: keyof typeof PAD
  /** 可交互：抬升 + 描边加强（位移量由主题的 --ui-lift 决定） */
  interactive?: boolean
  /** 用点缀色作描边环（重要面板 / 选中态） */
  accentRing?: boolean
  as?: 'div' | 'section' | 'article' | 'li'
  children?: ReactNode
}

export function Card({
  tone = 'default',
  pad = 'md',
  interactive = false,
  accentRing = false,
  as: As = 'div',
  className = '',
  children,
  ...rest
}: CardProps) {
  const ringStyle = TONE[tone] ? ({ '--ui-card-fill': TONE[tone] } as CSSProperties) : undefined
  /**
   * 多态标签：这里刻意收敛成 'div' 的字面量类型。
   * 原因是 JSX 对 union 标签会按各 intrinsic 元素的 Attributes 做交叉校验，
   * 而 div/section/article/li 的 Attributes 互不可赋值（onCopy 等事件处理器泛型不同）。
   * 我们实际只透传 DOM 通用属性，运行时渲染的仍是 as 指定的标签。
   */
  const Comp = As as unknown as 'div'
  return (
    <Comp
      className={`ui-card${accentRing ? ' ui-card--accent' : ''}${
        interactive ? ' ui-card--interactive' : ''
      }${className ? ` ${className}` : ''}`}
      {...rest}
    >
      <div className={`ui-card__in ${PAD[pad]}`} style={ringStyle}>
        {children}
      </div>
    </Comp>
  )
}

/**
 * 卡片头：左侧竖条 + 标题 + 右侧 meta。
 * 竖条与字距均由令牌驱动（Endfield 会变成全大写大字距，BA 会变成粗斜体）。
 */
export function CardHead({
  title,
  cn,
  meta,
  className = '',
}: {
  title: string
  cn?: string
  meta?: ReactNode
  className?: string
}) {
  return (
    <div
      className={`flex items-center gap-2.5 border-b border-line px-3 py-2${className ? ` ${className}` : ''}`}
    >
      <span className="h-3.5 w-1 shrink-0 bg-accent" />
      <span className="ui-heading text-sm">{title}</span>
      {cn ? <span className="text-sm font-semibold text-muted">{cn}</span> : null}
      <span className="flex-1" />
      {meta}
    </div>
  )
}
