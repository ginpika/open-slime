import { ApiError, requestJson } from './client'

export type TerminalView = { id: string; title: string; running: boolean; error: string | null }
export function terminalError(error: unknown): string {
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body) as { message?: string }; if (body.message) return body.message } catch { /* plain error body */ }
  }
  return error instanceof Error ? error.message : String(error)
}
export async function connectTerminal(path: string, socketPath: string, after: number): Promise<WebSocket> {
  const ticket = await requestJson<{ url: string }>(`${path}/connect`, { method: 'POST', body: JSON.stringify({ after }) }, 20000)
  const url = new URL(ticket.url, window.location.href)
  if (url.origin !== window.location.origin || !url.pathname.startsWith(socketPath)) throw new Error('终端连接地址无效')
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return new WebSocket(url)
}
const path = (workspace: string, id?: string) => `/api/workspaces/${encodeURIComponent(workspace)}/terminals${id ? `/${encodeURIComponent(id)}` : ''}`
export const listWorkspaceTerminals = (workspace: string) => requestJson<TerminalView[]>(path(workspace))
export const createWorkspaceTerminal = (workspace: string) => requestJson<TerminalView>(path(workspace), { method: 'POST', body: JSON.stringify({ cols: 80, rows: 24 }) }, 20000)
export const connectWorkspaceTerminal = (workspace: string, id: string, after: number) => connectTerminal(path(workspace, id), '/ws/workspaces/terminals/', after)
export const deleteWorkspaceTerminal = (workspace: string, id: string) => requestJson(path(workspace, id), { method: 'DELETE' }, 20000)
