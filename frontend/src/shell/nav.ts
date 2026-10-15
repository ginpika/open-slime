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
  | 'code'

/** 知识起始页的功能入口；保留原有路由与页面。 */
export const KNOWLEDGE_NAV: (NavItem & { description: string })[] = [
  { path: '/articles', label: '文档', en: 'DOCS', icon: 'doc', group: '内容', description: '翻阅已保存的文档，回到原文继续阅读。' },
  { path: '/snapshots', label: '网页快照', en: 'SNAPSHOTS', icon: 'doc', group: '内容', description: '浏览网页、保存快照，让有价值的内容随时可查。' },
  { path: '/graph', label: '知识图谱', en: 'GRAPH', icon: 'graph', group: '内容', description: '从一个实体出发，探索知识之间的联系。' },
  { path: '/import', label: '文档导入', en: 'IMPORT', icon: 'import', group: '加工', description: '添加一份文档，为知识库积累新的资料。' },
  { path: '/analyze', label: '文档分析', en: 'ANALYZE', icon: 'analyze', group: '加工', description: '处理文档分块，提取并审核实体与关系。' },
]

export const NAV: NavItem[] = [
  { path: '/', label: '首页', en: 'HOME', icon: 'home', group: '概览' },
  { path: '/knowledge', label: '知识', en: 'KNOWLEDGE', icon: 'graph', group: '内容' },
  { path: '/multi-agent', label: '智能体', en: 'AGENTS', icon: 'agent', group: '智能' },
  { path: '/memory', label: '记忆', en: 'MEMORY', icon: 'memory', group: '智能' },
  { path: '/roundtable', label: '圆桌', en: 'ROUNDTABLE', icon: 'table', group: '智能' },
  { path: '/workflow', label: '执行态', en: 'WORKFLOW', icon: 'flow', group: '智能' },
  { path: '/coding', label: '编程', en: 'CODING', icon: 'code', group: '智能' },
  { path: '/workspace', label: '工作空间', en: 'WORKSPACE', icon: 'workspace', group: '管理' },
  { path: '/settings', label: '设置', en: 'SETTINGS', icon: 'settings', group: '管理' },
  { path: '/stats', label: '用量 & 统计', en: 'USAGE & STATS', icon: 'stats', group: '管理' },
  { path: '/about', label: '关于', en: 'ABOUT', icon: 'about', group: '管理' },
]

/** 侧栏分组顺序（Tailwind Mono 侧边栏档） */
export const NAV_GROUPS = ['概览', '内容', '智能', '管理'] as const

function pagePathOf(route: string): string {
  if (/^\/snapshots\/[^/]+$/.test(route)) return '/snapshots'
  if (/^\/article\/.+$/.test(route)) return '/articles'
  if (/^\/multi-agent\/.+$/.test(route)) return '/multi-agent'
  return route
}

/** 页面自身的标题信息与全局导航归属分别解析。 */
export function pageNavOf(route: string): NavItem | undefined {
  const path = pagePathOf(route)
  return KNOWLEDGE_NAV.find(item => item.path === path) ?? NAV.find(item => item.path === path)
}

/** 页面归属哪条全局导航项（含子路由）。 */
export function activePathOf(route: string): string {
  const path = pagePathOf(route)
  return KNOWLEDGE_NAV.some(item => item.path === path) ? '/knowledge' : path
}

export type BreadcrumbItem = { path: string; label: string }

/** 按功能入口组织层级，而不是按 URL 分段或浏览历史拼接。所有父级都是真实页面。 */
export function breadcrumbsOf(route: string): BreadcrumbItem[] {
  const home = NAV.find(item => item.path === '/')!
  const crumbs: BreadcrumbItem[] = [{ path: home.path, label: home.label }]
  if (route === '/') return crumbs

  const page = pageNavOf(route)
  if (page) {
    if (KNOWLEDGE_NAV.some(item => item.path === page.path)) {
      const knowledge = NAV.find(item => item.path === '/knowledge')!
      crumbs.push({ path: knowledge.path, label: knowledge.label })
    }
    crumbs.push({ path: page.path, label: page.label })
    if (route !== page.path) {
      const label = page.path === '/articles' ? '文档详情'
        : page.path === '/snapshots' ? '快照详情'
        : route === '/multi-agent/new' ? '新建智能体' : '智能体详情'
      crumbs.push({ path: route, label })
    }
  } else if (route === '/search') {
    crumbs.push({ path: route, label: '检索' })
  } else if (route === '/subconscious') {
    const agents = NAV.find(item => item.path === '/multi-agent')!
    crumbs.push({ path: agents.path, label: agents.label }, { path: route, label: '系统' })
  } else {
    crumbs.push({ path: route, label: '页面不存在' })
  }
  return crumbs
}
