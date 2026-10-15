import { ApiError, requestJson } from './client'
import { apiFetch } from './desktop'

export type WebElement = { ref: string; tag: string; label: string; href: string }
export type BrowserView = {
  browserId: string; chatSessionId: string | null; url: string; title: string; text: string
  totalChars: number; offset: number; elements: WebElement[]; blocked: boolean; screenshot: string | null
}
export type BrowserCommand = {
  browserId?: string; action: 'open' | 'read' | 'click' | 'fill' | 'press' | 'scroll' | 'back' | 'close'
  url?: string; ref?: string; text?: string; offset?: number
  x?: number; y?: number; deltaX?: number; deltaY?: number
}
export type Paragraph = { id: string; text: string }
export type MediaOptions = { images: boolean; audio: boolean; video: boolean }
export const DEFAULT_MEDIA: MediaOptions = { images: true, audio: false, video: false }
export type Capture = {
  captureId: string; browserId: string; url: string; title: string; author: string; publishedAt: string
  capturedAt: number; contentHash: string; paragraphs: Paragraph[]; warnings: string[]; sourceHtml: string
}
export type Claim = { text: string; kind: 'fact' | 'opinion' | 'inference'; evidence: string[] }
export type SnapshotDraft = {
  captureId: string; title: string; summary: string; rationale: string; claims: Claim[]; model: string
}
export type WebSnapshot = {
  id: string; docId: number | null; url: string; title: string; author: string; publishedAt: string
  capturedAt: number; savedAt: number; contentHash: string; summary: string; rationale: string
  claims: Claim[]; model: string; warnings: string[]; indexStatus: string; enabled: boolean; sourceHtml: string; html: string
}
export type SnapshotImage = {
  id: string; filename: string; mimeType: string; bytes: number; sourceIndex: number
  occurrences: { sourceIndex: number; label: string; section: string; version: string }[]
}
export type SnapshotImageCatalog = {
  totalOccurrences: number; imageCount: number; duplicateCount: number; skippedCount: number; totalBytes: number; images: SnapshotImage[]
}

export const browserCommand = (command: BrowserCommand) => requestJson<BrowserView | null>('/api/web/browser', { method: 'POST', body: JSON.stringify(command) })
export const browserViews = (chatSessionId?: string) => requestJson<BrowserView[]>(`/api/web/browser${chatSessionId ? `?chatSessionId=${encodeURIComponent(chatSessionId)}` : ''}`)
export const captureBrowser = (id: string, media: MediaOptions = DEFAULT_MEDIA) => requestJson<Capture>(`/api/web/browser/${encodeURIComponent(id)}/capture`, { method: 'POST', body: JSON.stringify(media) })
export const distillCapture = (captureId: string, instruction: string) => requestJson<SnapshotDraft>('/api/web/distill', { method: 'POST', body: JSON.stringify({ captureId, instruction }) })
export const saveSnapshot = (draft: SnapshotDraft) => requestJson<WebSnapshot>('/api/web/snapshots', { method: 'POST', body: JSON.stringify(draft) })
export const listSnapshots = (page = 1) => requestJson<WebSnapshot[]>(`/api/web/snapshots?page=${page}&size=20`)
export const getSnapshot = (id: string) => requestJson<WebSnapshot>(`/api/web/snapshots/${encodeURIComponent(id)}`)
export const renameSnapshot = (id: string, title: string) => requestJson<{ id: string; title: string }>(`/api/web/snapshots/${encodeURIComponent(id)}/title`, { method: 'PATCH', body: JSON.stringify({ title }) })
export const toggleSnapshot = (id: string, enabled: boolean) => requestJson<WebSnapshot>(`/api/web/snapshots/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ enabled }) })
export const deleteSnapshot = (id: string) => requestJson<{ deleted: boolean }>(`/api/web/snapshots/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const indexSnapshot = (id: string) => requestJson<WebSnapshot>(`/api/web/snapshots/${encodeURIComponent(id)}/index`, { method: 'POST' })
export const getSnapshotImages = (id: string) => requestJson<SnapshotImageCatalog>(`/api/web/snapshots/${encodeURIComponent(id)}/images`)

export async function downloadSnapshot(id: string) {
  const response = await apiFetch(`/api/web/snapshots/${encodeURIComponent(id)}/html`, { credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status, await response.text())
  saveBlob(await response.blob(), `snapshot-${id}.html`)
}

export async function downloadSnapshotImages(id: string) {
  const response = await apiFetch(`/api/web/snapshots/${encodeURIComponent(id)}/images.zip`, { credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status, await response.text())
  saveBlob(await response.blob(), `snapshot-${id}-images.zip`)
}

export async function downloadSnapshotImage(image: SnapshotImage, src: string) {
  if (!/^data:image\/(?:png|jpeg|webp|gif);base64,/.test(src)) throw new Error('这张图片没有保存为可导出的离线文件')
  const response = await fetch(src)
  saveBlob(await response.blob(), image.filename)
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = filename; link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export function webError(error: unknown): string {
  if (error instanceof ApiError) {
    try { const body = JSON.parse(error.body) as { error?: string; message?: string }; return body.error || body.message || error.message } catch { return error.body || error.message }
  }
  return error instanceof Error ? error.message : String(error)
}

export const INDEX_LABEL: Record<string, string> = {
  pending: '待索引', indexing: '正在生成向量', 'keyword-only': '已进入关键词检索', ready: '向量已就绪', failed: '向量生成失败，可重试', disabled: '已停用',
}
