import { requestJson, type MemoryItem } from './client'

export type CatalogView = 'remembered' | 'recent' | 'attention' | 'history'
export type CatalogEntry = { item: MemoryItem; stopsAt: number | null; archiveNext: boolean; reason: string }
export type MemoryCatalog = {
  checkedAt: number; dueBefore: number; counts: Record<CatalogView, number>; total: number
  page: number; pages: number; entries: CatalogEntry[]; attention: CatalogEntry[]
  pendingProfiles: number; extractingMaterials: number
}
export function memoryCatalog(filters: { view: CatalogView; q: string; agentId: string; projectId: string; page: number }, signal?: AbortSignal) {
  return requestJson<MemoryCatalog>(`/api/memory/catalog?${new URLSearchParams(Object.entries(filters).map(([k,v]) => [k, String(v)]))}`, { signal })
}
export const memoryItem = (id: number) => requestJson<MemoryItem>(`/api/memory/items/${id}`)
export type MemoryEvidencePage = { claimId: number; body: string; offset: number; totalEvidence: number; evidence: { id: string; sourceRef: string; status: string; payload: string; readable: boolean; observedAt: number }[] }
export const memoryEvidencePage = (id: number, offset = 0) => requestJson<MemoryEvidencePage>(`/api/memory/graph/claims/${id}/evidence?offset=${offset}&limit=4`)
