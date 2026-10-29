import { requestJson } from './client'
import { connectTerminal, type TerminalView } from './terminal'

export type CodingTerminal = TerminalView
const path = (project: string, id?: string) => `/api/coding/projects/${encodeURIComponent(project)}/terminals${id ? `/${encodeURIComponent(id)}` : ''}`
const post = <T,>(url: string, body: unknown) => requestJson<T>(url, { method: 'POST', body: JSON.stringify(body) }, 20000)
export const listCodingTerminals = (project: string) => requestJson<CodingTerminal[]>(path(project))
export const createCodingTerminal = (project: string) => post<CodingTerminal>(path(project), { cols: 80, rows: 16 })
export const connectCodingTerminal = (project: string, id: string, after: number) => connectTerminal(path(project, id), '/ws/coding/terminals/', after)
export const deleteCodingTerminal = (project: string, id: string) => requestJson(path(project, id), { method: 'DELETE' }, 20000)
