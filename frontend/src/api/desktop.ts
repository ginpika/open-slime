/**
 * 桌面壳（Tauri）桥：本机模式的一次性令牌与桌面环境探测。
 *
 * <p>浏览器打开时两者都不存在，一切按现状走 —— 所以这里的能力都是「有则用之」，
 * 桌面壳与 Web 部署共用同一份前端产物。
 */

declare global {
  interface Window {
    /** 桌面壳在页面脚本执行前注入的一次性本机令牌（壳的本机模式下才有） */
    __MINIS_LOCAL_TOKEN__?: string
  }
}

/** 桌面壳的标志字段：Tauri v2 会挂在 window 上 */
const TAURI_FLAG = '__TAURI_INTERNALS__'

/** 本机模式令牌；浏览器 / 远程模式下为 null */
export function localToken(): string | null {
  const token = window.__MINIS_LOCAL_TOKEN__
  return typeof token === 'string' && token.length > 0 ? token : null
}

/** 是否运行在桌面壳里（后续原生通知、托盘交互据此决定是否降级） */
export function isDesktopShell(): boolean {
  return typeof window !== 'undefined' && TAURI_FLAG in window
}

/**
 * 统一请求出口：桌面本机模式带上 X-Minis-Token，其余与原生 fetch 完全一致。
 *
 * <p>后端的 DesktopTokenFilter 只校验 /api/**，静态资源不受影响；令牌为空则不加头，
 * 因此 Web 部署与本地开发（浏览器直连）的行为不变。
 */
export function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const token = localToken()
  if (!token) return fetch(input, init)
  const headers = new Headers(init?.headers)
  headers.set('X-Minis-Token', token)
  return fetch(input, { ...init, headers })
}
