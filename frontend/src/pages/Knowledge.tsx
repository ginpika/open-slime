import { KNOWLEDGE_NAV } from '../shell/nav'
import { ShellIcon } from '../shell/icons'
import './knowledge.css'

export default function Knowledge() {
  return (
    <section className="knowledge-start" aria-labelledby="knowledge-title">
      <header className="knowledge-start__intro">
        <span className="knowledge-start__eyebrow"><ShellIcon name="graph" />知识 / KNOWLEDGE</span>
        <h1 className="ui-heading" id="knowledge-title">从一份资料开始</h1>
        <p>收集、阅读与整理，让零散的资料成为相互连接的知识。</p>
      </header>

      <section className="knowledge-start__section" aria-labelledby="knowledge-browse">
        <div className="knowledge-start__heading">
          <h2 className="ui-heading" id="knowledge-browse">浏览与探索</h2>
          <p>回到已有的知识</p>
        </div>
        <div className="knowledge-start__grid">
          {KNOWLEDGE_NAV.filter(item => item.group === '内容').map(item => (
            <a className="knowledge-entry" key={item.path} href={`#${item.path}`}>
              <span className="knowledge-entry__icon"><ShellIcon name={item.icon} /></span>
              <h3 className="ui-heading">{item.label}</h3>
              <p>{item.description}</p>
              <span className="knowledge-entry__open">进入<span aria-hidden="true"> →</span></span>
            </a>
          ))}
        </div>
      </section>

      <section className="knowledge-start__section" aria-labelledby="knowledge-process">
        <div className="knowledge-start__heading">
          <h2 className="ui-heading" id="knowledge-process">收集与加工</h2>
          <p>为知识库添入新的内容</p>
        </div>
        <div className="knowledge-start__actions">
          {KNOWLEDGE_NAV.filter(item => item.group === '加工').map(item => (
            <a className="knowledge-action" key={item.path} href={`#${item.path}`}>
              <span className="knowledge-entry__icon"><ShellIcon name={item.icon} /></span>
              <div><h3 className="ui-heading">{item.label}</h3><p>{item.description}</p></div>
              <span className="knowledge-action__arrow" aria-hidden="true">→</span>
            </a>
          ))}
        </div>
      </section>
    </section>
  )
}
