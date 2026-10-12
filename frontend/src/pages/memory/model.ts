import { ApiError, type MemoryItem, type MemoryShare } from '../../api/client'
export const layers = [
  { id: 'L1', name: '已确认共享', desc: '用户规则对所有 Agent 可读；项目知识限对应项目。', route: '人工确认 → 持续复用' },
  { id: 'L2', name: 'Agent 私有', desc: '默认归属 Agent；可绑定项目，也可经显式授权分享。', route: '提炼经验 → 私有积累' },
  { id: 'L3', name: '任务与工作状态', desc: '会话 / 任务绑定；工作集与长期条目分别存储。', route: '记录进度 → 收尾蒸馏' },
  { id: 'L4', name: '显式发布', desc: '按发布目标授权；并非自动对全体 Agent 公开。', route: '发布 → 接收 / 提升 / 撤回' },
]
export const identityNames: Record<string, string> = { REAL: '现实事实', FICTION: '虚构内容', PROJECT_PREF: '项目偏好', INFERRED: '模型推断' }
export const ownerNames: Record<string, string> = { USER: '用户', PROJECT: '项目', AGENT: 'Agent', TASK: '任务' }
export const domainNames: Record<string, string> = { USER_PROFILE: '用户画像 / 规则', PROJECT: '项目知识', AGENT: 'Agent 经验', TASK_EPISODIC: '任务情景' }
export const time = (value?: number | null) => value == null ? '未设置' : new Date(value).toLocaleString('zh-CN', { hour12: false })
export function errorText(e: unknown) {
  if (e instanceof ApiError) return `接口 ${e.status}：${e.body.slice(0, 220) || '请求失败'}`
  return e instanceof Error ? e.message : String(e)
}
export function status(item: MemoryItem, now = Date.now()) {
  if (item.supersededBy != null) return '已取代'
  if (item.status && item.status !== 'active') return ({ revoked: '已撤销', archived: '已归档', conflicted: '冲突', expired: '已过期', superseded: '已取代' } as Record<string, string>)[item.status] ?? item.status
  if ((item.validUntil != null && item.validUntil <= now) || (item.expiresSoft != null && item.expiresSoft <= now)) return '已过期'
  return item.active ? '有效' : '不可注入'
}
export function scopeKind(item: MemoryItem, shares: MemoryShare[]) {
  if (item.owner === 'USER' && item.domain === 'USER_PROFILE') return 'global'
  if (shares.some(s => s.itemId === item.id && s.status === 'published')) return 'published'
  if (item.layer === 'L4') return 'unpublished'
  if (item.layer === 'L3' || item.owner === 'TASK') return 'bound'
  if (item.owner === 'PROJECT' && item.layer === 'L1') return 'project'
  return 'private'
}
export const scopeNames: Record<string, string> = { global: '全局用户规则', project: '项目共享', private: 'Agent 独享（默认）', bound: '会话 / 任务绑定', published: '显式授权共享', unpublished: 'L4 暂无发布授权' }
export function evidence(value?: string | null): { ref: string; text: string | null }[] {
  if (!value) return []
  try {
    const data: unknown = JSON.parse(value)
    if (!Array.isArray(data)) return [{ ref: value, text: null }]
    return data.map(v => typeof v === 'object' && v != null ? { ref: String(v.ref ?? v.id ?? '来源记录'), text: typeof v.text === 'string' ? v.text : null } : { ref: String(v), text: null })
  } catch { return [{ ref: value, text: null }] }
}
