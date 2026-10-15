/**
 * 主题系统 v4 类型契约 —— 「一个组件骨架，十种视觉投影」
 *
 * 核心模型：主题 = 6 层投影
 *   color   色彩层：由 3 色种子派生（primary/secondary/accent，每档明暗各一份）
 *   shape   形状层：圆角刻度、切角 clip-path、描边环宽度
 *   depth   深度层：e0–e5 五级投影（可为柔影 / 光晕 / 硬偏移 / 无）
 *   surface 材质层：面板透明度、背景模糊（玻璃）
 *   typo    排印层：字体族、标题字重与斜体策略、字距、全大写策略
 *   motion  动效层：时长、缓动、机械缓动（steps）
 *
 * 所有层最终都被解析成扁平 CSS 变量表（Vars），由 derive.ts 在运行时写入 :root。
 * 两档明暗只换 color 种子；需要分档的层用 `overrides[scheme]` 局部覆盖。
 */

export type Vars = Record<string, string>

/** 3 套主题 */
export type ThemeId = 'old' | 'endfield' | 'bluearchive' | 'astrolabe'

/** 明暗档位（每套主题都自带完整的两档） */
export type Scheme = 'light' | 'dark'

/** 用户可选的外观模式（system 跟随系统） */
export type SchemeMode = Scheme | 'system'

/** 一套主题在某一明暗档下的色种子 */
export type ThemeSeed = {
  /** 主色：正文墨色 */
  primary: string
  /** 辅色：线条 */
  secondary: string
  /** 点缀色：主行动 / 链接 / 强调 */
  accent: string
  /** 底色 */
  base: string
}

/** 全局用户覆盖（需求：保留全局色彩 / 字体调整面板） */
export type OverrideState = {
  /** 覆盖色种子（delta，切换主题时保留） */
  seed: Partial<Pick<ThemeSeed, 'primary' | 'accent' | 'base'>>
  /** 覆盖正文字体族 */
  fontSans?: string
  /** 覆盖标题斜体策略 */
  headingStyle?: 'normal' | 'italic'
}

export type BackdropKind = 'none' | 'grid' | 'holo' | 'pop'

/** 终末地主题色风格（主题配置窗口档位）：默认工业黄 / 罗德岛蓝点缀 */
export type EndfieldPalette = 'default' | 'rhodes'

/** 终末地窗口样式（主题配置窗口档位）：默认令牌驱动 / 工作台双色窗口 */
export type EndfieldWindowStyle = 'default' | 'workbench'

/** Tailwind Mono 主题配置（主题配置窗口，随设计逐步扩充） */
export type MonoConfig = {
  /** 侧边栏布局：启用原 Clean 主题最具特征的左侧分组侧栏 + 顶部工具条 */
  sidebar: boolean
  /**
   * 桌面端沉浸排布：隐藏外壳的 header / footer，主题按钮移到窗口标题栏
   * （用户按钮左侧）。仅桌面壳消费，浏览器端忽略。
   */
  hideChrome: boolean
}

/** 终末地主题配置（主题配置窗口，随设计逐步扩充） */
export type EndfieldConfig = {
  /** 主题色风格 */
  palette: EndfieldPalette
  /** 全局浮窗（FloatWindow）样式 */
  window: EndfieldWindowStyle
}

/** 星图观测台：独立配置，切换主题时保留。 */
export type AstrolabeConfig = {
  palette: 'brass' | 'copper' | 'glacier'
  material: 'paper' | 'glass'
  atmosphere: 'none' | 'orbits' | 'aurora'
  motion: boolean
  density: 'comfortable' | 'compact'
  reading: 'serif' | 'sans'
  hideChrome: boolean
}

/** 一套主题的完整清单 */
export type ThemeManifest = {
  id: ThemeId
  label: string
  labelEn: string
  desc: string
  /** 两档明暗的色种子 */
  seeds: Record<Scheme, ThemeSeed>
  /** 形状层（变量名 → 值） */
  shape: Vars
  /** 深度层 */
  depth: Vars
  /** 材质层 */
  surface: Vars
  /** 排印层 */
  typo: Vars
  /** 动效层 */
  motion: Vars
  /** 分档局部覆盖（例如 Endfield 深色才开玻璃） */
  overrides?: Partial<Record<Scheme, Vars>>
  /** 全局背景层类型（由 <ThemeBackdrop/> 消费） */
  backdrop: BackdropKind
  /** 面板预览用色（浅档的三色 + 底色） */
  swatch: string[]
}

/** 运行时主题状态（localStorage: minis.theme.v4） */
export type ThemeState = {
  v: 4
  themeId: ThemeId
  schemeMode: SchemeMode
  /** 解析后的实际明暗（system 档会跟随系统） */
  scheme: Scheme
  override: OverrideState
  /** 终末地主题配置（仅 endfield 主题消费；旧持久化数据缺失时逐字段兜底） */
  endfield: EndfieldConfig
  /** Tailwind Mono 主题配置（仅 old 主题消费；旧持久化数据缺失时逐字段兜底） */
  mono: MonoConfig
  astrolabe: AstrolabeConfig
  /** 最近一次解析出的完整令牌快照（供 index.html 防闪烁脚本与 canvas 读取） */
  vars: Vars
}

/* ---------------------------------------------------------------------------
 * 页面渲染版本注册表（管理员级扩展点，与主题系统解耦，保留不动）
 * ------------------------------------------------------------------------- */

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
  'memory',
  'agent',
  'roundtable',
  'workflow',
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
  memory: '记忆',
  agent: '智能体内部页',
  roundtable: '圆桌',
  workflow: '执行态',
  workspace: '工作空间',
  settings: '设置',
  stats: '用量 & 统计',
  about: '关于',
}
