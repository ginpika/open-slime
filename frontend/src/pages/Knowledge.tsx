import { AppIcon, type AppIconName } from '../components/AppIcon'
import { KNOWLEDGE_NAV, type IconName } from '../shell/nav'
import './knowledge.css'

/** 起始页入口图标：导航数据只存语义名，渲染统一走 AppIcon */
const ENTRY_ICONS: Record<IconName, AppIconName> = {
  home: 'home',
  doc: 'doc',
  graph: 'graph',
  snapshot: 'snapshot',
  import: 'import',
  analyze: 'analyze',
  agent: 'agent',
  memory: 'memory',
  subcons: 'subcons',
  workspace: 'workspace',
  settings: 'settings',
  system: 'system',
  stats: 'stats',
  flow: 'flow',
  table: 'table',
  code: 'code',
  todo: 'todo',
  economy: 'economy',
}

const browseEntries = KNOWLEDGE_NAV.filter((item) => item.group === '内容')
const collectEntries = KNOWLEDGE_NAV.filter((item) => item.group === '加工')

export default function Knowledge() {
  return (
    <section className="knowledge-start" aria-labelledby="knowledge-title">
      <header className="knowledge-start__intro">
        <p className="knowledge-start__brand">
          <span className="knowledge-start__dot" aria-hidden="true" />
          知识库 · KNOWLEDGE
        </p>
        <h1 className="knowledge-start__title" id="knowledge-title">从一份资料开始</h1>
        <p className="knowledge-start__lead">收集、阅读与整理，让零散的资料成为相互连接的知识。</p>
      </header>

      <section className="knowledge-start__section" aria-labelledby="knowledge-browse">
        <div className="knowledge-start__heading">
          <h2 id="knowledge-browse">浏览与探索</h2>
          <span>回到已有的知识</span>
        </div>
        <div className="knowledge-start__grid">
          {browseEntries.map((item) => (
            <a className="knowledge-entry" key={item.path} href={`#${item.path}`}>
              <span className="knowledge-entry__icon"><AppIcon name={ENTRY_ICONS[item.icon]} size={20} /></span>
              <strong className="knowledge-entry__title">{item.label}</strong>
              <span className="knowledge-entry__desc">{item.description}</span>
              <span className="knowledge-entry__open">进入<AppIcon name="arrow-right" size={14} /></span>
            </a>
          ))}
        </div>
      </section>

      <section className="knowledge-start__section" aria-labelledby="knowledge-collect">
        <div className="knowledge-start__heading">
          <h2 id="knowledge-collect">收集与加工</h2>
          <span>为知识库添入新的内容</span>
        </div>
        <div className="knowledge-start__actions">
          {collectEntries.map((item) => (
            <a className="knowledge-action" key={item.path} href={`#${item.path}`}>
              <span className="knowledge-entry__icon"><AppIcon name={ENTRY_ICONS[item.icon]} size={20} /></span>
              <span className="knowledge-action__text">
                <strong>{item.label}</strong>
                <span>{item.description}</span>
              </span>
              <AppIcon className="knowledge-action__arrow" name="arrow-right" size={16} />
            </a>
          ))}
        </div>
      </section>
    </section>
  )
}
