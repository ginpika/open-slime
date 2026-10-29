import { ECONOMY_NAV } from '../shell/nav'
import { AppIcon } from '../components/AppIcon'
import './knowledge.css'
import './economy/economy.css'

export default function Economy() {
  return <section className="knowledge-start economy-start" aria-labelledby="economy-title">
    <header className="knowledge-start__intro">
      <span className="knowledge-start__eyebrow"><AppIcon name="economy" />经济 / ECONOMY</span>
      <h1 className="ui-heading" id="economy-title">三张图，一份隔夜策略报告</h1>
      <p>从行情截图到可回看的判断。只研究尾盘是否进场，以及下一交易日开盘后立即卖出的机会。</p>
    </header>
    <ol className="economy-start__flow" aria-label="隔夜策略流程">{[['周线','看趋势环境'],['日线','看量价结构'],['分时','看尾盘承接'],['总结','联合评估进场']].map(([title,description],i)=><li key={title}><span>{i+1}</span><div><strong>{title}</strong><p>{description}</p></div></li>)}</ol>
    <section className="knowledge-start__section" aria-label="经济研究入口">
      <div className="knowledge-start__heading"><h2 className="ui-heading">开始观测，持续积累</h2><p>大盘与个股共用一套流程，每次截图、分析与总结都留存。</p></div>
      <div className="knowledge-start__actions">{ECONOMY_NAV.map((item,index) => <a className="knowledge-entry" key={item.path} href={`#${item.path}`}>
        <span className="knowledge-entry__icon"><AppIcon name={item.icon} /></span>
        <div><h3 className="ui-heading">{item.label}</h3><p>{item.description}</p></div>
        <span className="knowledge-entry__open">{index===0?'进入工作台':'查找与回看'}<AppIcon name="arrow-right"/></span>
      </a>)}</div>
    </section>
    <p className="economy-start__note"><AppIcon name="about"/>三周期截图共同支撑判断；机会评分用于比较条件，不代表获利概率。</p>
  </section>
}
