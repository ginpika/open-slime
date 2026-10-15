import {
  ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Bot, Brain, ChartNoAxesCombined,
  ChartNoAxesColumn, Check, ChevronDown, ChevronLeft, ChevronRight, CircleAlert,
  CircleCheck, Cloud, CodeXml, Copy, Crosshair, Download, Ellipsis, ExternalLink, File, FileText,
  Folder, FolderOpen, GitBranch, GripVertical, House, Image, Info, Layers,
  Lightbulb, Link, LoaderCircle, MessageCircle, Minus, Network, Palette, Pencil, Plus,
  RefreshCw, Search, Send, Settings, SlidersHorizontal, Square,
  Undo2, Upload, UsersRound, Workflow, X,
  type LucideProps,
} from 'lucide-react'

// 仅静态导入使用中的图标；业务代码通过语义名调用，不依赖整库动态查找。
const ICONS = {
  'arrow-down': ArrowDown,
  'arrow-left': ArrowLeft,
  'arrow-right': ArrowRight,
  'arrow-up': ArrowUp,
  agent: Bot,
  memory: Brain,
  analyze: ChartNoAxesCombined,
  stats: ChartNoAxesColumn,
  check: Check,
  'chevron-down': ChevronDown,
  'chevron-left': ChevronLeft,
  'chevron-right': ChevronRight,
  alert: CircleAlert,
  success: CircleCheck,
  cloud: Cloud,
  chat: MessageCircle,
  code: CodeXml,
  restore: Copy,
  center: Crosshair,
  download: Download,
  more: Ellipsis,
  external: ExternalLink,
  file: File,
  doc: FileText,
  folder: Folder,
  'folder-open': FolderOpen,
  branch: GitBranch,
  grip: GripVertical,
  home: House,
  image: Image,
  about: Info,
  workspace: Layers,
  subcons: Lightbulb,
  link: Link,
  loading: LoaderCircle,
  minimize: Minus,
  graph: Network,
  palette: Palette,
  edit: Pencil,
  plus: Plus,
  refresh: RefreshCw,
  search: Search,
  send: Send,
  settings: Settings,
  sliders: SlidersHorizontal,
  square: Square,
  stop: Square,
  reply: Undo2,
  import: Upload,
  table: UsersRound,
  flow: Workflow,
  close: X,
} as const

export type AppIconName = keyof typeof ICONS
export type AppIconProps = Omit<LucideProps, 'children' | 'strokeWidth' | 'fill'> & {
  name: AppIconName
}

/** 装饰图标由相邻文字或所属按钮提供可访问名称。 */
export function AppIcon({ name, size = 16, ...props }: AppIconProps) {
  const Icon = ICONS[name]
  return <Icon size={size} {...props} fill={name === 'stop' ? 'currentColor' : 'none'} strokeWidth={1.8} aria-hidden="true" focusable="false" />
}
