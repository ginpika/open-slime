/** Dashboard 收纳全部功能入口：常用工作直接打开，专业能力按需展开。 */
import { ArrowLeft, ArrowUpRight, ChevronRight } from 'lucide-react'
import { ShellIcon } from '../shell/icons'
import { ECONOMY_NAV, KNOWLEDGE_NAV, NAV, type NavItem } from '../shell/nav'
import { SlimeAppearanceButton } from '../components/SlimeAppearance'
import { BRAND } from '../branding'
import './dashboard.css'

type Card = NavItem & { description: string }

const DESCRIPTIONS: Record<string, string> = {
  '/todos': '整理项目与待办，让下一步更清楚。',
  '/collaboration': '组织圆桌讨论，编排协作流程与计划。',
  '/coding': '从一个想法开始，编写、运行与验证代码。',
  '/economy': '研究市场，管理策略与观测。',
  '/economy/research': '按周期分析行情，完成策略评估。',
  '/economy/database': '回看每次观测、分析与截图。',
  '/knowledge': '保存与整理资料，需要时随手找到。',
  '/articles': '阅读已保存的文档，随时回到原文。',
  '/snapshots': '保存网页，把有价值的内容留下来。',
  '/graph': '探索实体与知识之间的联系。',
  '/import': '添加文档，补充知识库资料。',
  '/analyze': '处理文档，提取与审核实体关系。',
  '/multi-agent': '管理智能体、职责与运行状态。',
  '/memory': '查看与整理 AI 记住的内容。',
  '/workspace': '管理文件与终端运行环境。',
  '/stats': '查看模型调用、用量与成本趋势。',
  '/settings': '配置模型、提示词与检索偏好。',
}

const LABELS: Record<string, string> = {
  '/todos': '项目与待办',
  '/knowledge': '知识库',
  '/stats': '用量与统计',
  '/settings': '系统设置',
}

function itemsOf(paths: string[]): Card[] {
  const all = [...NAV, ...KNOWLEDGE_NAV, ...ECONOMY_NAV]
  return paths
    .map((path) => all.find((item) => item.path === path))
    .filter((item): item is NavItem => Boolean(item))
    .map((item) => ({
      ...item,
      label: LABELS[item.path] ?? item.label,
      description: DESCRIPTIONS[item.path] ?? '',
    }))
}

const EVERYDAY = itemsOf(['/knowledge', '/todos', '/coding'])

const SECTIONS = [
  {
    label: '资料与知识',
    hint: '文档、网页与知识加工',
    icon: 'doc' as const,
    items: itemsOf(['/articles', '/snapshots', '/import', '/analyze', '/graph']),
  },
  {
    label: '智能体与协作',
    hint: '成员、记忆与协作计划',
    icon: 'agent' as const,
    items: itemsOf(['/multi-agent', '/memory', '/collaboration']),
  },
  {
    label: '经济研究',
    hint: '策略分析与历史观测',
    icon: 'economy' as const,
    items: itemsOf(['/economy', '/economy/research', '/economy/database']),
  },
  {
    label: '环境与设置',
    hint: '工作空间、模型与用量',
    icon: 'settings' as const,
    items: itemsOf(['/workspace', '/stats', '/settings']),
  },
]

function ToolLink({ item }: { item: Card }) {
  return (
    <a className="dash-card" href={`#${item.path}`}>
      <span className="dash-card__icon"><ShellIcon name={item.icon} /></span>
      <span className="dash-card__text">
        <span className="dash-card__label">{item.label}</span>
        <span className="dash-card__desc">{item.description}</span>
      </span>
      <ArrowUpRight className="dash-card__arrow" size={15} aria-hidden="true" />
    </a>
  )
}

export default function Dashboard() {
  return (
    <div className="dash-app slime-surface">
      <header className="dash-head">
        <a className="dash-back" href="#/">
          <ArrowLeft size={16} />
          <span>回到对话</span>
        </a>
        <span className="dash-head__brand">{BRAND.name}<span aria-hidden="true">/</span>Dashboard</span>
        <SlimeAppearanceButton />
      </header>

      <main className="dash-scroll scrollbar-slim">
        <div className="dash-body">
          <div className="dash-intro">
            <span className="dash-eyebrow">工作与管理</span>
            <h1>所有工具，都在这里。</h1>
            <p>日常从对话开始，其他功能在这里按需打开。</p>
          </div>

          <section className="dash-everyday" aria-labelledby="dash-everyday-title">
            <div className="dash-section__head">
              <h2 id="dash-everyday-title">常用工具</h2>
              <span>从这里继续手头的工作</span>
            </div>
            <div className="dash-grid dash-grid--everyday">
              {EVERYDAY.map((item) => <ToolLink key={item.path} item={item} />)}
            </div>
          </section>

          <section className="dash-more" aria-labelledby="dash-more-title">
            <div className="dash-section__head">
              <h2 id="dash-more-title">更多能力</h2>
              <span>按需展开</span>
            </div>
            <div className="dash-sections">
              {SECTIONS.map((section) => (
                <details key={section.label} className="dash-section">
                  <summary className="dash-section__summary">
                    <span className="dash-section__icon"><ShellIcon name={section.icon} /></span>
                    <span className="dash-section__name">{section.label}</span>
                    <span className="dash-section__hint">{section.hint}</span>
                    <ChevronRight className="dash-section__chevron" size={16} aria-hidden="true" />
                  </summary>
                  <div className="dash-grid dash-grid--more">
                    {section.items.map((item) => <ToolLink key={item.path} item={item} />)}
                  </div>
                </details>
              ))}
            </div>
          </section>

          <p className="dash-footer">工具随时在这里。<a href="#/">回去聊聊<ArrowUpRight size={12} aria-hidden="true" /></a></p>
        </div>
      </main>
    </div>
  )
}
