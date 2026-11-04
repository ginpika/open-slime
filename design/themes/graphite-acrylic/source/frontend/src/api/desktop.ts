/**
 * 桌面壳（Tauri）桥：本机模式的一次性令牌与桌面环境探测。
 *
 * <p>浏览器打开时两者都不存在，一切按现状走 —— 所以这里的能力都是「有则用之」，
 * 桌面壳与 Web 部署共用同一份前端产物。
 */

/** 桌面壳注入的 IPC 出口：命令名 + 参数 → 结果 */
type TauriInvoke = (cmd: string, args?: Record<string, unknown>) => Promise<unknown>

declare global {
  interface Window {
    /** 桌面壳在页面脚本执行前注入的一次性本机令牌（壳的本机模式下才有） */
    __MINIS_LOCAL_TOKEN__?: string
    /** 桌面壳注入的全局 Tauri API（withGlobalTauri）；只用到 core.invoke */
    __TAURI__?: { core?: { invoke?: TauriInvoke } }
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

/* ----------------------------------------------------------------------------
 * 自绘标题栏的窗口命令（见 shell/DesktopFrame.tsx）
 *
 * 主窗口是无边框的（desktop/src/main.rs 里的 .decorations(false)），标题栏由页面
 * 自绘，窗口操作统一走 Tauri 核心 window 插件。命令名与参数对齐框架自身的拖拽
 * 脚本（plugin:window|…），且不传 label——操作的就是发起命令的那个窗口，因此这里
 * 不需要知道窗口标签。拖拽本身由 data-tauri-drag-region 属性交给框架，无需代码。
 *
 * 浏览器里没有 __TAURI__：全部静默降级为 no-op，同一份前端产物照旧跑在 Web 部署上。
 * -------------------------------------------------------------------------- */

/** 调一条核心 window 插件命令；不在桌面壳里时返回 undefined */
function windowCmd(cmd: string, args?: Record<string, unknown>): Promise<unknown> | undefined {
  return window.__TAURI__?.core?.invoke?.(`plugin:window|${cmd}`, args)
}

/** 最小化窗口 */
export function minimizeWindow(): void {
  windowCmd('minimize')?.catch(() => {})
}

/** 关闭窗口（走正常关闭流程，壳会一并停掉本机后端） */
export function closeWindow(): void {
  windowCmd('close')?.catch(() => {})
}

/** 最大化 / 还原切换，回传切换后的最大化状态（标题栏据此换图标） */
export async function toggleMaximizeWindow(): Promise<boolean> {
  const toggled = windowCmd('toggle_maximize')
  if (!toggled) return false
  await toggled.catch(() => {})
  return isWindowMaximized()
}

/** 当前是否最大化；不在桌面壳里恒为 false */
export async function isWindowMaximized(): Promise<boolean> {
  const query = windowCmd('is_maximized')
  if (!query) return false
  try {
    return Boolean(await query)
  } catch {
    return false
  }
}

/** 在指定窗缘开始系统缩放；dir 取 "North" / "SouthEast" 等八个方位 */
export function resizeWindow(dir: string): void {
  windowCmd('start_resize_dragging', { value: dir })?.catch(() => {})
}

/** 登出哨兵路径：与 desktop/src/main.rs 的 LOGOUT_PATH 一致（主窗口 on_navigation 会拦截它） */
const LOGOUT_PATH = '/__minis_desktop_logout'

/**
 * 桌面端登出：请求壳把主窗口带回引导页（壳页面），后续重新登录 / 换身份由壳接管。
 *
 * <p>用一次「哨兵导航」而不是 IPC：远程源调用壳的自有命令受 Tauri ACL 限制，而主窗口的
 * 导航拦截不依赖 ACL，连着远程实例时也能可靠触发。壳拦下这条导航后会把窗口带回引导页，
 * 并立即提示会话已失效。浏览器里没有壳，别调用本函数——那边走 window.location.reload。
 */
export function backToLauncher(): void {
  window.location.href = LOGOUT_PATH
}

/** 首页哨兵路径：与 desktop/src/main.rs 的 HOME_PATH 一致（主窗口 on_navigation 会拦截它） */
const HOME_PATH = '/__minis_desktop_home'

/**
 * 桌面端首页入口：请求壳把主窗口带回引导页的「启动」视图（视图一：选本机 / 远程那一屏）。
 *
 * <p>与 {@link backToLauncher} 同走「哨兵导航」：不登出、不撤销会话；壳顺手把实例当前地址
 * 记进状态，引导页据此把对应卡片显示成「回到实例」。浏览器里没有壳，别调用本函数。
 */
export function openDesktopHome(): void {
  window.location.href = HOME_PATH
}
