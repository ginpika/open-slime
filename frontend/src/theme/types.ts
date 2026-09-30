/** 主题系统类型：双色 / 三色（统一结构：双色 = 辅色 ≡ 主色的三色）→ 语义令牌 */

export type ThemeMode = 'duotone' | 'tritone'

/**
 * 统一颜色结构（双色/三色共用，区别只在 mode）：
 * - primary   主色：文字、段落（正文墨色），灰阶 faint/muted 由底色+主色派生
 * - secondary 辅色：线条（header/footer 分割线、输入框描边、卡片边框）；双色模式下 ≡ primary
 * - accent    点缀色：按钮、链接、placeholder、状态提示、命中高亮
 * - base      底色：背景（缺省 = 从主色自动派生，可覆写）
 */
export type ThemeColors = {
  primary: string
  secondary: string
  accent: string
  /** base 缺省（自动派生）时取浅底还是深底 */
  dark: boolean
  /** 底色覆写（缺省 = 自动派生） */
  base?: string
}

export type ThemeDef = {
  id: string
  name: string
  mode: ThemeMode
  colors: ThemeColors
  builtin?: boolean
}

/** 运行时语义令牌（写入 :root 的 CSS 变量，键即变量名） */
export type Vars = Record<string, string>

/** 外观模式：浅色 / 深色 / 跟随系统 */
export type Appearance = 'light' | 'dark' | 'system'

/** 持久化状态（localStorage: minis.theme.v3） */
export type ThemeState = {
  v: 3
  /** 当前主题 id：builtin id / custom id / 'draft'（自定义编辑中） */
  currentId: string
  custom: ThemeDef[]
  /** 自定义编辑草稿（进入“自定义”页签时由当前主题派生） */
  draft: ThemeDef | null
  /** 最近一次解析出的令牌快照（供 index.html 防闪烁脚本读取） */
  vars: Vars
  /** 外观模式（浅色/深色/跟随系统）；手动选主题时自动切到对应深浅档 */
  appearance: Appearance
  /** 上次使用的浅色主题 id（外观开关「浅色」档的目标） */
  lastLightId: string
  /** 上次使用的深色主题 id（外观开关「深色」档的目标） */
  lastDarkId: string
}

/** 页面渲染版本注册表支持的路由（overrides.tsx 使用） */
export const OVERRIDE_ROUTES = [
  'home',
  'chat',
  'search',
  'graph',
  'articles',
  'article',
  'import',
  'analyze',
  'subconscious',
  'multi-agent',
  'agent',
  'roundtable',
  'workspace',
  'settings',
  'stats',
  'about',
] as const
export type OverrideRoute = (typeof OVERRIDE_ROUTES)[number]

export const ROUTE_LABELS: Record<OverrideRoute, string> = {
  home: '首页',
  chat: '聊天',
  search: '搜索',
  graph: '图谱',
  articles: '文档',
  article: '文档详情',
  import: '文档导入',
  analyze: '文档分析',
  subconscious: '潜意识',
  'multi-agent': '多智能体',
  agent: '智能体内部页',
  roundtable: '圆桌',
  workspace: '工作空间',
  settings: '设置',
  stats: '统计',
  about: '关于',
}
