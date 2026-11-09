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
  | 'snapshot'
  | 'import'
  | 'analyze'
  | 'agent'
  | 'memory'
  | 'subcons'
  | 'workspace'
  | 'settings'
  | 'system'
  | 'stats'
  | 'flow'
  | 'table'
  | 'code'
  | 'todo'
  | 'economy'

/** 知识起始页的功能入口；保留原有路由与页面。 */
export const KNOWLEDGE_NAV: (NavItem & { description: string })[] = [
  { path: '/articles', label: '文档', en: 'DOCS', icon: 'doc', group: '内容', description: '翻阅已保存的文档，回到原文继续阅读。' },
  { path: '/snapshots', label: '网页快照', en: 'SNAPSHOTS', icon: 'snapshot', group: '内容', description: '浏览网页、保存快照，让有价值的内容随时可查。' },
  { path: '/graph', label: '知识图谱', en: 'GRAPH', icon: 'graph', group: '内容', description: '从一个实体出发，探索知识之间的联系。' },
  { path: '/import', label: '文档导入', en: 'IMPORT', icon: 'import', group: '加工', description: '添加一份文档，为知识库积累新的资料。' },
  { path: '/analyze', label: '文档分析', en: 'ANALYZE', icon: 'analyze', group: '加工', description: '处理文档分块，提取并审核实体与关系。' },
]

export const NAV: NavItem[] = [
  { path: '/', label: '首页', en: 'HOME', icon: 'home', group: '概览' },
  { path: '/todos', label: '项目 & 待办', en: 'PROJECTS & TODOS', icon: 'todo', group: '工作' },
  { path: '/collaboration', label: '协作', en: 'COLLABORATION', icon: 'flow', group: '工作' },
  { path: '/coding', label: '编程', en: 'CODING', icon: 'code', group: '工作' },
  { path: '/economy', label: '经济', en: 'ECONOMY', icon: 'economy', group: '工作' },
  { path: '/knowledge', label: '知识', en: 'KNOWLEDGE', icon: 'graph', group: '内容' },
  { path: '/multi-agent', label: '智能体', en: 'AGENTS', icon: 'agent', group: '智能' },
  { path: '/system', label: '系统', en: 'SYSTEM', icon: 'system', group: '智能' },
  { path: '/memory', label: '记忆', en: 'MEMORY', icon: 'memory', group: '智能' },
  { path: '/workspace', label: '工作空间', en: 'WORKSPACE', icon: 'workspace', group: '管理' },
  { path: '/stats', label: '用量 & 统计', en: 'USAGE & STATS', icon: 'stats', group: '管理' },
  { path: '/settings', label: '设置', en: 'SETTINGS', icon: 'settings', group: '管理' },
]

export const ECONOMY_NAV: (NavItem & { description: string })[] = [
  { path: '/economy/research', label: '隔夜策略工作台', en: 'OVERNIGHT', icon: 'economy', group: '工作', description: '依次分析周线、日线和分时，提交尾盘买入与次日早盘卖出的评估。' },
  { path: '/economy/database', label: '观测数据库', en: 'OBSERVATIONS', icon: 'doc', group: '工作', description: '按标的与日期查找观测，回看截图、分步分析与总结，接着完成未结束的观测。' },
]

/** 侧栏分组顺序（Tailwind Mono 侧边栏档） */
export const NAV_GROUPS = ['概览', '工作', '内容', '智能', '管理'] as const

function pagePathOf(route: string): string {
  if(route==='/economy/market'||route==='/economy/stock')return '/economy/research'
  if (route === '/roundtable' || route === '/workflow') return '/collaboration'
  if (/^\/snapshots\/[^/]+$/.test(route)) return '/snapshots'
  if (/^\/article\/.+$/.test(route)) return '/articles'
  if (/^\/multi-agent\/.+$/.test(route)) return '/multi-agent'
  return route
}

/** 页面自身的标题信息与全局导航归属分别解析。 */
export function pageNavOf(route: string): NavItem | undefined {
  const path = pagePathOf(route)
  return ECONOMY_NAV.find(item => item.path === path) ?? KNOWLEDGE_NAV.find(item => item.path === path) ?? NAV.find(item => item.path === path)
}

/** 页面归属哪条全局导航项（含子路由）。 */
export function activePathOf(route: string): string {
  const path = pagePathOf(route)
  if (ECONOMY_NAV.some(item => item.path === path)) return '/economy'
  return KNOWLEDGE_NAV.some(item => item.path === path) ? '/knowledge' : path
}

export type BreadcrumbItem = { path: string; label: string }

/** 以各侧栏入口为根组织层级，首页仅属于自身及其会话。所有父级都是真实页面。 */
export function breadcrumbsOf(route: string): BreadcrumbItem[] {
  if (route === '/') {
    const home = NAV.find(item => item.path === '/')!
    return [{ path: home.path, label: home.label }]
  }
  const crumbs: BreadcrumbItem[] = []

  const page = pageNavOf(route)
  if (page) {
    if (ECONOMY_NAV.some(item => item.path === page.path)) crumbs.push({ path: '/economy', label: '经济' })
    if (KNOWLEDGE_NAV.some(item => item.path === page.path)) {
      const knowledge = NAV.find(item => item.path === '/knowledge')!
      crumbs.push({ path: knowledge.path, label: knowledge.label })
    }
    crumbs.push({ path: page.path, label: page.label })
    if (route !== page.path && route !== '/roundtable' && route !== '/workflow' && route !== '/economy/market' && route !== '/economy/stock') {
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
