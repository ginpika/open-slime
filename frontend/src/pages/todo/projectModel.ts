import type { Project } from '../../api/client'
import type { TodoProjectSummary } from '../../api/todo'

export const PROJECT_KINDS = [
  { value: 'other', label: '通用项目' }, { value: 'software', label: '软件开发' },
  { value: 'research', label: '研究 / 学习' }, { value: 'novel', label: '小说' },
  { value: 'story', label: '剧本 / 故事' }, { value: 'rpg', label: '角色扮演' },
  { value: 'fiction', label: '虚构创作' },
]
export const PROJECT_STATUSES = [
  { value: 'active', label: '进行中' }, { value: 'paused', label: '已暂停' },
  { value: 'archived', label: '已归档' }, { value: 'deleted', label: '已删除' },
]
export const projectKind = (kind: string) => PROJECT_KINDS.find(option => option.value === kind)?.label ?? kind
export const projectStatus = (status: string) => PROJECT_STATUSES.find(option => option.value === status)?.label ?? status
export type ProjectOverview = TodoProjectSummary & { info: Project | null }
export type PersonalProjectInput = { name: string; note: string; kind: string; status?: string }
