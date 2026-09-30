/**
 * 共享 UI 原语：统一按钮样式与内联图标。
 * 按钮默认 TailwindCSS 风格胶囊（rounded-full，header 级动作），
 * 面板内控件传 'rounded-none' 得到无圆角直角；
 * 图标一律 currentColor 线条（与首页放大镜同风格），自动跟随主题令牌。
 */

/** 按钮基底：12px/semibold 排版 */
const BTN_BASE =
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap text-[12px] font-semibold transition'

/** 描边按钮（登出 / 面板次级主行动）：细线描边，悬停边框加深 + 轻微填充 */
export const btnOutline = (radius = 'rounded-full') =>
  `${BTN_BASE} ${radius} border border-line bg-bg px-3.5 py-1.5 text-muted hover:border-line-strong hover:bg-fg/5 hover:text-fg`

/** 主行动按钮（保存 / 搜索）：点缀色实底 + 自动黑/白文字（对比度保证可读） */
export const btnPrimary = (radius = 'rounded-full') =>
  `${BTN_BASE} ${radius} bg-accent px-3.5 py-1.5 text-accent-fg hover:bg-accent-hover`

/** 安静按钮（低优先级动作）：细线描边、muted 文字 */
export const btnQuiet = (radius = 'rounded-full') =>
  `${BTN_BASE} ${radius} border border-line px-3.5 py-1.5 text-muted hover:border-line-strong`

/** 图标按钮：无描边圆形切换钮（同 Tailwind 明暗切换钮），悬停轻微填充 */
export const btnIcon = (radius = 'rounded-full') =>
  `${BTN_BASE} ${radius} h-[30px] w-[30px] text-muted hover:bg-fg/10 hover:text-fg`

/** 表格操作列小动作钮（Analyze 系列页面共用）：微小圆角细描边 */
export const miniBtn =
  'inline-flex items-center whitespace-nowrap rounded-sm border border-line px-2.5 py-1 text-xs font-medium text-muted transition hover:border-line-strong hover:bg-fg/5 hover:text-fg disabled:pointer-events-none disabled:opacity-40'

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
