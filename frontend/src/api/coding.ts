import { requestJson, ApiError, type WorkspaceDirView, type WorkspaceFileView } from './client'
import { apiFetch } from './desktop'

export type CodingMode = 'PLAN' | 'EDIT' | 'EXECUTE'
export type CodingRunner = 'DOCKER' | 'LOCAL' | 'WORKSPACE'
export type CodingWorkspace = { id: string; name: string; kind: string; enabled: boolean; workdir: string | null }
export type CodingProject = { id: string; name: string; workspaceId: string; workspaceName: string; kind: string; directory: string; validationCommand: string | null; enabled: boolean; createdAt: number }
export type CodingRepository = { id: string; name: string; path: string; projectId: string | null; validationCommand: string | null }
export type CodingRun = {
  id: string; taskId: number; repositoryId: string; prompt: string; modelId: string | null
  mode: CodingMode; runner: CodingRunner; status: string; attempt: number; round: number
  answer: string | null; error: string | null; validationStatus: string; artifactHash: string | null
  createdAt: number; updatedAt: number; sessionId?: string | null
}
export type CodingSession = { id: string; repositoryId: string; title: string; lastRunId: string | null; createdAt: number; updatedAt: number }
export type CodingRole = { id: string; name: string; description: string; modelId: string | null; instructions: string; version: number; tools: string[] }
export type CodingChild = { id: string; runId: string; role: string; title: string; prompt: string; modelId: string | null; status: string; attempt: number; round: number; answer: string | null; error: string | null; baseHash: string | null; finalHash: string | null; configVersion: number; createdAt: number; updatedAt: number }
export type CodingEvent = { id: number; runId: string; type: string; payload: string; createdAt: number }
export type CodingDiff = {
  artifactHash: string; changes: { path: string; kind: string; size: number; binary: boolean }[]
  patch: string; truncated: boolean
}
export type CodingAgent = { id: string; name: string; modelId: string | null }
export type CodingCapabilities = { enabled: boolean; localExecutionAllowed: boolean; dockerImage: string; maxRounds: number; maxRunMinutes: number; isolation: string; agent?: CodingAgent | null }
const base = '/api/coding'
const path = (id: string) => `${base}/runs/${encodeURIComponent(id)}`
const post = <T,>(url: string, body?: unknown) => requestJson<T>(url, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
export const codingCapabilities = () => requestJson<CodingCapabilities>(`${base}/capabilities`)
export const codingRepositories = () => requestJson<CodingRepository[]>(`${base}/repositories`)
export const codingWorkspaces = () => requestJson<CodingWorkspace[]>(`${base}/workspaces`)
export const codingProjects = () => requestJson<CodingProject[]>(`${base}/projects`)
export const openCodingProject = (body: { workspaceId: string; directory: string; name?: string; create: boolean; validationCommand?: string }) => post<CodingProject>(`${base}/projects`, body)
export const codingProjectFiles = (id: string, directory = '.', runId?: string) => requestJson<WorkspaceDirView>(`${base}/projects/${encodeURIComponent(id)}/files?path=${encodeURIComponent(directory)}${runId ? `&runId=${encodeURIComponent(runId)}` : ''}`)
export const codingProjectFile = (id: string, file: string, runId?: string) => requestJson<WorkspaceFileView>(`${base}/projects/${encodeURIComponent(id)}/file?path=${encodeURIComponent(file)}${runId ? `&runId=${encodeURIComponent(runId)}` : ''}`)
export const codingRuns = () => requestJson<CodingRun[]>(`${base}/runs`)
export const codingSessions = () => requestJson<CodingSession[]>(`${base}/sessions`)
export const codingSession = (id: string) => requestJson<CodingSession>(`${path(id)}/session`)
export const codingSessionRuns = (id: string) => requestJson<CodingRun[]>(`${base}/sessions/${encodeURIComponent(id)}/runs`)
export const codingRoles = () => requestJson<CodingRole[]>(`${base}/roles`)
export const saveCodingRole = (id: string, body: { modelId: string | null; instructions: string }) => requestJson<CodingRole>(`${base}/roles/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) })
export const codingChildren = (id: string) => requestJson<CodingChild[]>(`${path(id)}/executions`)
export const stopCodingChild = (run: string, id: string) => post(`${path(run)}/executions/${encodeURIComponent(id)}/stop`)
export const resumeCodingChild = (run: string, id: string, input: string) => post<CodingChild>(`${path(run)}/executions/${encodeURIComponent(id)}/resume`, { input })
export const codingRun = (id: string) => requestJson<CodingRun>(path(id))
export const registerCodingRepository = (body: { name: string; path: string; validationCommand: string }) => post<CodingRepository>(`${base}/repositories`, body)
export const codingRequestKey = () => globalThis.crypto?.randomUUID?.() || `coding_${Date.now()}_${Math.random().toString(36).slice(2)}`
export const startCodingRun = (body: { repositoryId: string; prompt: string; modelId: string | null; mode: CodingMode; runner: CodingRunner; maxRounds: number; sessionId?: string }, requestKey: string = codingRequestKey()) => requestJson<CodingRun>(`${base}/runs`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': requestKey }, body: JSON.stringify(body) })
export const cancelCodingRun = (id: string) => post<CodingRun>(`${path(id)}/cancel`)
export const resumeCodingRun = (id: string, input: string, acknowledgeUnknownOperations: boolean, mode?: CodingMode) => post<CodingRun>(`${path(id)}/resume`, { input, acknowledgeUnknownOperations, mode })
export const steerCodingRun = (id: string, input: string) => post<CodingRun>(`${path(id)}/messages`, { input })
export const codingDiff = (id: string) => requestJson<CodingDiff>(`${path(id)}/diff`)
export const acceptCodingChanges = (id: string, artifactHash: string) => post<CodingRun>(`${path(id)}/accept`, { artifactHash })
export const codingEvents = (id: string, after = 0) => requestJson<CodingEvent[]>(`${path(id)}/events?after=${after}`)

/** Uses apiFetch so desktop tokens work; event IDs allow replay after an interrupted connection. */
export async function streamCodingEvents(id: string, after: number, signal: AbortSignal, receive: (event: CodingEvent) => void): Promise<void> {
  const response = await apiFetch(`${path(id)}/events/stream?after=${after}`, { credentials: 'same-origin', signal, headers: { Accept: 'text/event-stream' } })
  if (!response.ok) throw new ApiError(response.status, await response.text())
  if (!response.body) throw new Error('事件流不可用')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let match: RegExpExecArray | null
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        let eventId = 0, type = 'message'
        const data: string[] = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('id:')) eventId = Number(line.slice(3).trim())
          if (line.startsWith('event:')) type = line.slice(6).trim()
          if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
        }
        if (eventId > 0 && data.length) receive({ id: eventId, runId: id, type, payload: data.join('\n'), createdAt: Date.now() })
      }
    }
  } finally { await reader.cancel().catch(() => undefined) }
}

export function codingError(error: unknown): string {
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body) as { message?: string; detail?: string; error?: string }; return body.message || body.detail || body.error || `请求失败 (${error.status})` } catch { return error.body || error.message }
  }
  return error instanceof Error ? error.message : String(error)
}
