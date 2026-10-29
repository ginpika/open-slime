import { ApiError, requestJson } from './client'

export function collaborationError(error: unknown) {
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body); return body.message || body.detail || body.error || `请求失败（${error.status}）` } catch { return error.body || error.message }
  }
  return error instanceof Error ? error.message : String(error)
}

export type CollaborationIssue = {
  id: string; parentSessionId: string; sessionId: string; title: string
  status: 'open' | 'resolved'; conclusion: string | null; messageId: number; createdAt: number; resolvedAt: number | null
}
export type CollaborationPlan = {
  sessionId: string; coordinatorId: string; phase: 'planning' | 'executing' | 'checking' | 'completed'
  criteria: string | null; decision: string | null; evidence: string | null; todoId: string | null
  executorId: string | null; reviewerId: string | null
  assignments?: { todoId: string; title: string; executorId: string; reviewerId: string; phase: 'executing' | 'checking'; version: number }[]
  continuationStatus: string; pauseReason: string | null
  scope?: { deliverables: string; exclusions: string; baseline: string; roadmap: string } | null
}
export type ProjectWorkspaceBinding = { projectId: string; repositoryId: string; workspaceId: string | null; workspaceName: string; directory: string | null; root: string | null; enabled: boolean }
export type CollaborationWorkspace = { issues: CollaborationIssue[]; projectMembers: Record<string, string[]>; plans?: CollaborationPlan[]; workspaceBindings?: ProjectWorkspaceBinding[]; archivedTasks?: string[] }
export type TaskQuality = {
  contract: { outcome: string; acceptanceCriteria: { id: string; text: string }[]; stopCondition: string } | null
  findings: { id: string; severity: 'BLOCKER' | 'MINOR'; location: string; expected: string; basis: string; status: 'OPEN' | 'RESOLVED' | 'ADVISORY'; resolutionEvidence?: string }[] | null
  diagnosis: { code: string; detail: string; runId: string; artifactHash: string; createdAt: number; state?: 'OPEN' | 'REASSIGNED' | 'RESOLVED'; decision?: string } | null
}
export const collaborationTaskQuality = (session: string, todo: string) => requestJson<TaskQuality>(`/api/agents/collaboration/tasks/${encodeURIComponent(session)}/todos/${encodeURIComponent(todo)}/quality`)
export const archiveCollaborationTask = (id: string, archived: boolean) => requestJson<{ archived: boolean }>(`/api/agents/collaboration/tasks/${encodeURIComponent(id)}/archive`, { method: 'PUT', body: JSON.stringify({ archived }) })
export const deleteCollaborationTask = (id: string) => requestJson<{ deleted: boolean }>(`/api/agents/collaboration/tasks/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const collaborationWorkspaces = () => requestJson<{ id: string; name: string; kind: string; enabled: boolean; workdir: string | null }[]>('/api/agents/collaboration/workspaces')
export const bindCollaborationWorkspace = (id: string, body: { workspaceId: string; directory: string; create: boolean; name?: string }) => requestJson<ProjectWorkspaceBinding>(`/api/projects/${encodeURIComponent(id)}/collaboration-workspace`, { method: 'PUT', body: JSON.stringify(body) })
export const collaborationWorkspace = () => requestJson<CollaborationWorkspace>('/api/agents/collaboration')
export const createCollaborationTask = (projectId: string, title: string, memberIds: string[], coordinatorId?: string) => requestJson<{ sessionId: string }>('/api/agents/collaboration/tasks', { method: 'POST', body: JSON.stringify({ projectId, title, memberIds, coordinatorId }) })
export const createCollaborationIssue = (sessionId: string, title: string, memberIds: string[]) => requestJson<CollaborationIssue>(`/api/agents/sessions/${encodeURIComponent(sessionId)}/issues`, { method: 'POST', body: JSON.stringify({ title, memberIds }) })
export const concludeCollaborationIssue = (id: string, conclusion: string) => requestJson<CollaborationIssue>(`/api/agents/issues/${encodeURIComponent(id)}/conclusion`, { method: 'PUT', body: JSON.stringify({ conclusion }) })
export const rewindCollaborationSession = (id: string, messageId: number) => requestJson<{ sessionId: string; removed: number }>(`/api/agents/sessions/${encodeURIComponent(id)}/rewind`, { method: 'POST', body: JSON.stringify({ messageId }) })
export const saveCollaborationMembers = (id: string, memberIds: string[]) => requestJson<string[]>(`/api/projects/${encodeURIComponent(id)}/collaboration-members`, { method: 'PUT', body: JSON.stringify({ memberIds }) })

export type ProjectDocumentKind = 'overview' | 'decision' | 'reference' | 'delivery'
export type ProjectDocumentEntry = { key: string; kind: ProjectDocumentKind; title: string; excerpt: string; version: number; retired: boolean; sourceSessionId: string | null; sourceTitle: string | null; author: string; updatedAt: number }
export type ProjectDocument = Omit<ProjectDocumentEntry, 'excerpt'> & { content: string }
export type ProjectKnowledgePage<T> = { items: T[]; total: number; offset: number; limit: number }
export type ProjectOpenWork = { sessionId: string; sessionTitle: string; todoId: string; title: string; status: string; owner: string | null; result: string | null; blocker: string | null; paused: boolean }
export type ProjectKnowledgeProgress = { completedSessions: number; openTodos: number; blockedTodos: number; work: ProjectKnowledgePage<ProjectOpenWork> }
const knowledgeUrl = (project: string) => `/api/projects/${encodeURIComponent(project)}/knowledge`
export const projectKnowledge = (project: string, query = '', offset = 0, retired = false) => requestJson<ProjectKnowledgePage<ProjectDocumentEntry>>(`${knowledgeUrl(project)}?${new URLSearchParams({ query, offset: String(offset), retired: String(retired) })}`)
export const projectKnowledgeProgress = (project: string, offset = 0) => requestJson<ProjectKnowledgeProgress>(`${knowledgeUrl(project)}/progress?offset=${offset}`)
export const projectDocument = (project: string, key: string, version?: number) => requestJson<ProjectDocument>(`${knowledgeUrl(project)}/documents/${encodeURIComponent(key)}${version ? `?version=${version}` : ''}`)
export const projectDocumentHistory = (project: string, key: string) => requestJson<ProjectDocument[]>(`${knowledgeUrl(project)}/documents/${encodeURIComponent(key)}/history`)
export const saveProjectDocument = (project: string, key: string, document: { kind: ProjectDocumentKind; title: string; content: string; expectedVersion: number; retired: boolean }) => requestJson<ProjectDocument>(`${knowledgeUrl(project)}/documents/${encodeURIComponent(key)}`, { method: 'PUT', body: JSON.stringify(document) })
