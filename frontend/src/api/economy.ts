import { requestJson } from './client'
import type { BrowserCommand, BrowserView } from './webSnapshots'

export type ResearchKind = 'market' | 'stock'
export type ResearchSource = { browserId?: string; image?: string; title: string; capturedAt: number }
export type Timeframe = 'weekly' | 'daily' | 'intraday'
export type Study = { id:string;instrumentId:string;kind:ResearchKind;symbol:string;tradeDate:string;createdAt:number }
export type StepAnalysis = { sufficientEvidence:boolean;summary:string;technical:string;risks:string }
export type Observation = {
  id:string;studyId:string;timeframe:Timeframe;capturedAt:number;createdAt:number;sourceTitle:string;sourceUrl:string;imageHash:string
  agentId:string;agentName:string;model:string;notes:string;status:'ANALYZING'|'COMPLETE'|'FAILED';analysis:StepAnalysis|null;error:string|null
}
export type EconomyReport = {
  id:string;studyId:string;instrumentId:string;kind:ResearchKind;symbol:string;tradeDate:string;strategy:string;observationIds:string[]
  agentId:string;agentName:string;model:string;analyzedAt:number;sufficientEvidence:boolean
  entryDecision:'ENTER'|'WAIT'|'AVOID';overnightDecision:'BUY_CLOSE'|'WAIT'|'AVOID';score:number|null;stage:number|null
  summary: string; factors: { name: string; points: number; maximum: number; reason: string }[]
  technical:string;fundamentals:string;conditions:string;exitPlan:string;risks:string;retrievalStatus:string
}
export type StudyDetail = { study:Study;observations:Observation[];reports:EconomyReport[] }
export type StudySummary = { study:Study;completedSteps:number;observationCount:number;reportCount:number;latestSummary:string|null;latestScore:number|null }
export type StudyPage = { items:StudySummary[];total:number;page:number;pageSize:number }
export type HistoryFilter = { kind:string;symbol:string;from:string;to:string;page:number }
export const createStudy = (input:{kind:ResearchKind;symbol:string;tradeDate:string}) => requestJson<Study>('/api/economy/studies',{method:'POST',body:JSON.stringify(input)})
export const getStudy = (id:string) => requestJson<StudyDetail>(`/api/economy/studies/${encodeURIComponent(id)}`)
export const listStudies = (filter:HistoryFilter) => requestJson<StudyPage>(`/api/economy/studies?${new URLSearchParams(Object.fromEntries(Object.entries(filter).map(([k,v])=>[k,String(v)])))}`)
export const analyzeStep = (id:string,input:{timeframe:Timeframe;agentId:string;browserId?:string;screenshot?:string;capturedAt:number;sourceTitle:string;notes:string}) => requestJson<Observation>(`/api/economy/studies/${encodeURIComponent(id)}/observations`,{method:'POST',body:JSON.stringify(input)})
export const retryStep = (id:string) => requestJson<Observation>(`/api/economy/observations/${encodeURIComponent(id)}/retry`,{method:'POST'})
export const observationImage = (id:string) => requestJson<{image:string}>(`/api/economy/observations/${encodeURIComponent(id)}/image`)
export const submitStudy = (id:string,input:{agentId:string;observationIds:string[];notes:string;fundamentals:boolean}) => requestJson<EconomyReport>(`/api/economy/studies/${encodeURIComponent(id)}/submit`,{method:'POST',body:JSON.stringify(input)})
export type LegacyReport = { id:string;symbol:string;kind:string;analyzedAt:number;summary:string;technical:string;score:number|null }
export const legacyReports = () => requestJson<LegacyReport[]>('/api/economy/reports')
export const economyBrowserCommand = (command: BrowserCommand) => requestJson<BrowserView | null>('/api/economy/browser', { method:'POST',body:JSON.stringify(command) })

export type ObservedWindow = { id: number; pid: number; title: string; app: string }
export type WindowFrame = { image: string; title: string; capturedAt: number }
async function native<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  const invoke = window.__TAURI__?.core?.invoke
  if (!invoke) throw new Error('请使用桌面版选择已打开窗口，或使用浏览器的共享窗口功能')
  return await invoke(command, args) as T
}
export const economyWindows = () => native<ObservedWindow[]>('economy_windows')
export const economyCapture = (id: number, pid: number) => native<WindowFrame>('economy_capture_window', { id, pid })
