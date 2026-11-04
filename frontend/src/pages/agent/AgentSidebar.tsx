import { useMemo, useState, type ReactNode } from 'react'
import { LayoutGrid, PanelLeftClose, Plus, Search, Star, X } from 'lucide-react'
import type { MaPeer } from '../../api/client'
import { isCodingAgent } from '../../api/agentRuntime'
import { BRAND } from '../../branding'
import PeerAvatar from '../../components/PeerAvatar'
import './agentSidebar.css'

export type AgentPreview = { text?: string; unread?: boolean; running?: boolean }

type Props = {
  mainAgent: MaPeer | null
  agents: MaPeer[]
  selectedAgentId: string | null
  previews: Record<string, AgentPreview>
  loading: boolean
  error: boolean
  disabled: boolean
  narrow: boolean
  userSlot?: ReactNode
  onSelectAgent: (id: string | null) => void
  onReload: () => void
  onClose: () => void
}

function previewText(preview: AgentPreview | undefined, peer?: MaPeer | null): string {
  if (preview?.running) return '正在回复…'
  return preview?.text?.trim() || (peer && isCodingAgent(peer) ? '打开编程工作台' : peer?.note?.trim() || peer?.role?.trim() || '开始聊聊')
}

/** The sidebar represents people to talk to; individual conversations stay in their history. */
export default function AgentSidebar({ mainAgent, agents, selectedAgentId, previews, loading, error, disabled, narrow, userSlot, onSelectAgent, onReload, onClose }: Props) {
  const [searchOpen, setSearchOpen] = useState(false)
  const [query, setQuery] = useState('')
  const mainName = mainAgent?.name || BRAND.name
  const mainPreview = previews.main
  const visibleAgents = useMemo(() => {
    const value = query.trim().toLocaleLowerCase()
    return value ? agents.filter(agent => [agent.name, agent.role, agent.note, previews[agent.id]?.text]
      .some(text => text?.toLocaleLowerCase().includes(value))) : agents
  }, [agents, previews, query])

  return <>
    <div className="sl-agents__head" data-tauri-drag-region>
      <button type="button" className="sl-agents__close" aria-label="收起 Agent 列表" title="收起 Agent 列表" onClick={onClose}>
        {narrow ? <X size={18} /> : <PanelLeftClose size={17} />}
      </button>
      <div className="sl-agents__head-actions">
        <button type="button" className="sl-agents__round" aria-label="搜索 Agent" title="搜索 Agent" aria-expanded={searchOpen} aria-controls="slime-agent-search"
          onClick={() => { setSearchOpen(!searchOpen); setQuery('') }}><Search size={18} /></button>
        <a className="sl-agents__round" href="#/multi-agent/new" aria-label="新增 Agent" title="新增 Agent"><Plus size={21} /></a>
      </div>
    </div>

    {searchOpen && <div className="sl-agents__search" id="slime-agent-search">
      <Search size={15} aria-hidden="true" />
      <input autoFocus value={query} onChange={event => setQuery(event.target.value)} aria-label="搜索 Agent" placeholder="搜索 Agent…" />
      <button type="button" aria-label="关闭搜索" onClick={() => { setQuery(''); setSearchOpen(false) }}><X size={15} /></button>
    </div>}

    <div className="sl-agents__primary-wrap">
      <button type="button" className={`sl-agents__primary${selectedAgentId === null ? ' is-active' : ''}`} disabled={disabled}
        aria-pressed={selectedAgentId === null} aria-label={`与主 Agent ${mainName} 对话`} onClick={() => onSelectAgent(null)}>
        <span className="sl-agents__primary-picture">
          <img src={mainAgent?.avatar || '/icon-128x128.png'} width="56" height="56" alt="" className={mainAgent?.avatar ? 'is-custom' : ''} />
          <span className="sl-agents__primary-badge" title="主 Agent"><Star size={10} fill="currentColor" aria-hidden="true" /></span>
        </span>
        <span className="sl-agents__primary-name">{mainName}</span>
        {mainPreview?.running && <span className="sl-agents__primary-status">正在回复…</span>}
        {mainPreview?.unread && !mainPreview.running && <span className="sl-agents__primary-status">有新消息</span>}
      </button>
    </div>

    <nav className="sl-agents__list scrollbar-slim" aria-label="其他 Agent">
      {error && <div className="sl-agents__notice" role="status">暂时无法更新 Agent 列表<button type="button" onClick={onReload}>重试</button></div>}
      {loading && !agents.length ? <p className="sl-agents__empty" role="status">正在加载 Agent…</p>
        : visibleAgents.map(agent => {
          const preview = previews[agent.id]
          const text = previewText(preview, agent)
          return <button key={agent.id} type="button" disabled={disabled} className={`sl-agents__peer${selectedAgentId === agent.id ? ' is-active' : ''}`}
            aria-current={selectedAgentId === agent.id ? 'page' : undefined} onClick={() => onSelectAgent(agent.id)}>
            <span className="sl-agents__peer-picture">
              <PeerAvatar name={agent.name} avatar={agent.avatar} size="sl-agents__peer-avatar" textClass="sl-agents__peer-initial" bordered={false} />
              {preview?.unread && <span className="sl-agents__unread" aria-label="有未读消息" />}
            </span>
            <span className="sl-agents__peer-copy">
              <span className="sl-agents__peer-name">{agent.name}{preview?.running && <i className="sl-agents__working" aria-label="正在回复" />}</span>
              <span className="sl-agents__peer-preview" title={text}>{text}</span>
            </span>
          </button>
        })}
      {!loading && !visibleAgents.length && <p className="sl-agents__empty">{query.trim() ? '没有找到相关 Agent' : <>其他 Agent 会出现在这里。<a href="#/multi-agent/new">添加一位</a></>}</p>}
    </nav>

    <div className="sl-agents__footer">
      <div className="sl-agents__account">{userSlot}</div>
      <a className="sl-agents__dashboard" href="#/dashboard"><LayoutGrid size={15} /><span>Dashboard</span></a>
    </div>
  </>
}
