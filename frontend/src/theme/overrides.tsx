/**
 * 页面渲染版本注册表（管理员级扩展点，不面向终端用户）。
 * 同一路由可在代码中保留多个渲染结构版本，激活哪个由 ACTIVE_VARIANTS 常量决定；
 * 未来由管理员后台切换（本次不实装切换 UI）。未激活任何版本时渲染原生页面（App.tsx 的 fallback）。
 */
import { Component, type ReactNode } from 'react'
import * as ReactModule from 'react'
import * as api from '../api/client'
import { currentVars } from './store'
import type { OverrideRoute } from './types'

/** 渲染版本的上下文：注入 React / api 封装 / 当前主题令牌 / 路由 props */
export type PageCtx = {
  React: typeof ReactModule
  route: OverrideRoute
  props: Record<string, unknown>
  api: typeof api
  tokens: Record<string, string>
}

/** 某个路由的一个渲染结构版本（替代原生页面） */
export type RenderVariant = {
  id: string
  label: string
  render: (ctx: PageCtx) => ReactNode
}

/**
 * 各路由的候选渲染版本。原生页面不进注册表——这里只描述「替代结构」。
 * 新增版本示例：
 *   home: [{ id: 'compact', label: '紧凑版', render: (ctx) => <HomeCompact /> }]
 */
const VARIANTS: Partial<Record<OverrideRoute, RenderVariant[]>> = {}

/**
 * 各路由当前激活的版本 id（缺省 = 渲染原生页面）。
 * 未来管理员后台切换时改写此处，或改为读取后台下发配置。
 */
const ACTIVE_VARIANTS: Partial<Record<OverrideRoute, string>> = {}

function ctxOf(route: OverrideRoute, props: Record<string, unknown>): PageCtx {
  return { React: ReactModule, route, props, api, tokens: currentVars() }
}

/** 渲染版本出错时兜底展示（key 绑定版本 id，切换版本即复位） */
class VariantBoundary extends Component<{ children: ReactNode }, { error: string | null }> {
  state = { error: null as string | null }

  static getDerivedStateFromError(e: unknown) {
    return { error: e instanceof Error ? e.message : String(e) }
  }

  render() {
    if (this.state.error !== null) {
      return (
        <p className="mt-10 border border-line p-6 text-base text-muted">
          渲染版本运行出错：{this.state.error} —— 请将 ACTIVE_VARIANTS 回退至默认。
        </p>
      )
    }
    return this.props.children
  }
}

/** 页面插槽：该路由激活了注册表版本则渲染之，否则渲染原生页面 */
export function PageSlot({
  route,
  props,
  fallback,
}: {
  route: OverrideRoute
  props: Record<string, unknown>
  fallback: ReactNode
}) {
  const variantId = ACTIVE_VARIANTS[route]
  const variant = variantId ? VARIANTS[route]?.find((v) => v.id === variantId) : undefined
  if (!variant) return <>{fallback}</>
  return (
    <VariantBoundary key={`${route}:${variant.id}`}>
      {variant.render(ctxOf(route, props))}
    </VariantBoundary>
  )
}
