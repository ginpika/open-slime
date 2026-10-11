/**
 * Tailwind Mono 专属主题菜单
 *
 * 与默认 ThemePanel 同构（主题库 / 明暗档 / 全局覆盖），只在「主题」页尾部
 * 追加一个 Tailwind Mono 专属设定块：
 *   浏览器端 —— 滑块开关「侧边栏布局」（原 Clean 排布：左侧分组侧栏 + 顶部工具条）；
 *   桌面端   —— 滑块开关「隐藏顶栏与底栏」（沉浸排布：收起 header/footer，
 *               主题按钮移到窗口标题栏、用户按钮左侧）。
 * 两者互斥：同一时刻只呈现当前运行环境相关的那个开关。
 *
 * 为什么开关直接落在菜单里，而不是像终末地那样另开「主题配置」浮窗：
 * 该开关会切换外壳排布变体（minimal ⇄ sidebar），shell 子树随之重挂——
 * 浮窗的开合状态若存在子树内会被重置、表现为"一拨开关窗口就消失"。
 * 菜单的开合状态由 App 持有（见 ThemePanel 注释），因此放在菜单内最稳。
 *
 * 面板本体沿用默认平面语言（Tailwind Mono 自身即该语言），故直接复用
 * DefaultThemePanel 并注入 themeTabExtra，不重写两个页签。
 */
import { isDesktopShell } from '../api/desktop'
import { DefaultThemePanel } from './ThemePanel'
import { themeActions, useThemeState } from './store'

export function MonoThemePanel({
  open,
  onOpenChange,
  variant,
}: {
  open: boolean
  onOpenChange: (next: boolean) => void
  variant?: 'plain' | 'chrome'
}) {
  const st = useThemeState()

  // 桌面端只给「隐藏顶栏与底栏」，浏览器端只给「侧边栏布局」——各自与本端外壳排布相关。
  const layoutExtra = isDesktopShell() ? (
    <div className="mt-4 border-t border-line pt-3">
      <span className="ui-label">Layout</span>
      <div className="mt-2.5">
        <SwitchRow
          label="隐藏顶栏与底栏"
          en="HIDE CHROME"
          hint="桌面端沉浸排布：收起外壳的 header 与 footer，主题按钮移到窗口标题栏、用户按钮左侧。关闭则恢复顶底栏。"
          readout={st.mono.hideChrome ? 'ON' : 'OFF'}
          on={st.mono.hideChrome}
          onLabel="隐藏顶栏与底栏"
          onToggle={() => themeActions.setMonoConfig({ hideChrome: !st.mono.hideChrome })}
        />
      </div>
    </div>
  ) : (
    <div className="mt-4 border-t border-line pt-3">
      <span className="ui-label">Layout</span>
      <div className="mt-2.5">
        <SwitchRow
          label="侧边栏布局"
          en="SIDEBAR LAYOUT"
          hint="启用原 Clean 主题最具特征的排布：左侧分组侧栏 + 顶部工具条（典型 SaaS）。关闭则回到顶部横排导航。"
          readout={st.mono.sidebar ? 'ON' : 'OFF'}
          on={st.mono.sidebar}
          onLabel="侧边栏布局"
          onToggle={() => themeActions.setMonoConfig({ sidebar: !st.mono.sidebar })}
        />
      </div>
    </div>
  )

  return (
    <DefaultThemePanel
      open={open}
      onOpenChange={onOpenChange}
      themeTabExtra={layoutExtra}
      variant={variant}
    />
  )
}

/* ---------------------------------------------------------------- 原语 */

/** 配置行：标签 + 说明（左）与读数 + 滑块开关（右）。轨道胶囊、滑块圆形。 */
function SwitchRow({
  label,
  en,
  hint,
  readout,
  on,
  onLabel,
  onToggle,
}: {
  label: string
  en: string
  hint: string
  readout: string
  on: boolean
  onLabel: string
  onToggle: () => void
}) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <span className="flex items-baseline gap-2">
          <span className="text-xs font-semibold text-fg">{label}</span>
          <span className="text-2xs tracking-[0.1em] text-faint">{en}</span>
        </span>
        <p className="mt-1 text-2xs leading-relaxed text-faint">{hint}</p>
      </div>
      <div className="flex shrink-0 items-center gap-2.5 pt-0.5">
        <span className="font-mono text-2xs font-semibold tracking-[0.1em] text-muted">
          {readout}
        </span>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={onLabel}
          onClick={onToggle}
          className="relative h-6 w-11 shrink-0 cursor-pointer"
          style={{
            border: '1px solid var(--c-line-strong)',
            borderRadius: 'var(--ui-r-pill)',
            background: on ? 'var(--c-accent)' : 'transparent',
            transition:
              'background var(--ui-dur-base) var(--ui-ease), border-color var(--ui-dur-base) var(--ui-ease)',
          }}
        >
          <span
            className="absolute top-1/2 h-4 w-4 -translate-y-1/2 rounded-full"
            style={{
              left: on ? 'calc(100% - 18px)' : '2px',
              background: on ? 'var(--c-accent-fg)' : 'var(--c-muted)',
              transition:
                'left var(--ui-dur-base) var(--ui-ease), background var(--ui-dur-base) var(--ui-ease)',
            }}
          />
        </button>
      </div>
    </div>
  )
}
