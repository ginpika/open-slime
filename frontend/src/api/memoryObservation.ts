import { requestJson, type WorkingItem } from './client'

export type MemoryContext = { agentId: string; projectId?: string; sessionId?: string; taskId?: number }
export type MemoryAccess = { itemId: number; reasons: string[]; editableByAgent: boolean }
export type MemoryVisibility = { context: MemoryContext; checkedAt: number; entries: MemoryAccess[]; work: WorkingItem[] }
export type SnapshotSummary = {
  turnId: string; sessionId: string | null; agentId: string | null; projectId: string | null
  watermark: number; itemCount: number; itemIds: number[]; workCount: number | null; budgetTokens: number; builtAt: number; complete: boolean
}
export type FrozenItem = {
  id: number; title: string | null; body: string; layer: string | null; owner: string | null
  agentId: string | null; projectId: string | null; identity: string | null; version: number
}
export type FrozenWork = { id: number; kind: string; title: string | null; body: string; status: string }
export type SnapshotDetail = {
  summary: SnapshotSummary; content: { items: FrozenItem[]; work: FrozenWork[] } | null; itemIds: number[]
  requests: { round: number; requestRecorded: boolean; memorySectionPresent: boolean; memoryText: string | null; responseRecorded: boolean }[]
  requestNote: string
}
function query(values: Record<string, string | number | undefined>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== '') params.set(key, String(value))
  return params.toString()
}
export const memoryVisibility = (context: MemoryContext) => requestJson<MemoryVisibility>(`/api/memory/observation/visibility?${query(context)}`)
export const memorySnapshots = (context: Partial<MemoryContext> = {}) => requestJson<SnapshotSummary[]>(`/api/memory/observation/snapshots?${query({ ...context, limit: 100 })}`, undefined, 15000)
export const memorySnapshot = (turnId: string) => requestJson<SnapshotDetail>(`/api/memory/observation/snapshots/${encodeURIComponent(turnId)}`, undefined, 15000)
export const memoryObservedWork = () => requestJson<WorkingItem[]>('/api/memory/observation/working?limit=200')
