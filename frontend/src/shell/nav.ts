/**
 * 外壳导航契约（所有 kit 共用同一份数据源，绝不允许各 kit 自己写一份）。
 * 变更导航只需改这里一个文件。
 */
export type NavItem = {
  path: string
  label: string
  en: string
  /** 图标名，见 shell/icons.tsx */
  icon: IconName
  /** 侧栏分组（仅 Tailwind Mono 侧边栏档使用） */
  group: string
}

export type IconName =
  | 'home'
  | 'doc'
  | 'graph'
  | 'import'
  | 'analyze'
  | 'agent'
  | 'memory'
  | 'subcons'
  | 'workspace'
  | 'settings'
  | 'stats'
  | 'about'
  | 'flow'
  | 'table'

export const NAV: NavItem[] = [
  { path: '/', label: '首页', en: 'HOME', icon: 'home', group: '概览' },
  { path: '/articles', label: '文档', en: 'DOCS', icon: 'doc', group: '内容' },
  { path: '/graph', label: '知识图谱', en: 'GRAPH', icon: 'graph', group: '内容' },
  { path: '/import', label: '文档导入', en: 'IMPORT', icon: 'import', group: '加工' },
  { path: '/analyze', label: '文档分析', en: 'ANALYZE', icon: 'analyze', group: '加工' },
  { path: '/multi-agent', label: '智能体', en: 'AGENTS', icon: 'agent', group: '智能' },
  { path: '/memory', label: '记忆', en: 'MEMORY', icon: 'memory', group: '智能' },
  { path: '/roundtable', label: '圆桌', en: 'ROUNDTABLE', icon: 'table', group: '智能' },
  { path: '/workflow', label: '执行态', en: 'WORKFLOW', icon: 'flow', group: '智能' },
  { path: '/workspace', label: '工作空间', en: 'WORKSPACE', icon: 'workspace', group: '管理' },
  { path: '/settings', label: '设置', en: 'SETTINGS', icon: 'settings', group: '管理' },
  { path: '/stats', label: '统计', en: 'STATS', icon: 'stats', group: '管理' },
  { path: '/about', label: '关于', en: 'ABOUT', icon: 'about', group: '管理' },
]

/** 侧栏分组顺序（Tailwind Mono 侧边栏档） */
export const NAV_GROUPS = ['概览', '内容', '加工', '智能', '管理'] as const

/** 页面归属哪条导航项（含子路由） */
export function activePathOf(route: string): string {
  if (/^\/article\//.test(route)) return '/articles'
  if (/^\/multi-agent\//.test(route)) return '/multi-agent'
  return route
}
