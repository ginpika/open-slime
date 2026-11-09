import { useEffect, useState } from 'react'
import { ApiError, getSettings, listMaPeers, type MaPeer } from '../api/client'
import { presenceLabel, useAgentOverview } from '../api/agentPresence'
import { sortAgentPeers } from '../api/agentRuntime'
import { AgentAvatar, AgentRole } from '../components/AgentPresence'
import { AppIcon } from '../components/AppIcon'
import PageBoundary from '../components/PageBoundary'
import Toast from '../components/Toast'
import './multiAgent.css'

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body.trim()
    if (!body) return `请求失败（${error.status}）`
    try {
      const parsed = JSON.parse(body) as { message?: string } | null
      return parsed?.message || body
    } catch {
      return body
    }
  }
  return error instanceof Error ? error.message : String(error)
}

function MemberCard({ peer, model, working, stale }: {
  peer: MaPeer
  model: string
  working?: boolean
  stale: boolean
}) {
  return (
    <a className="agent-roster__card" href={`#/multi-agent/${encodeURIComponent(peer.id)}`}>
      <div className="agent-roster__identity">
        <span className="agent-roster__avatar" aria-hidden="true">
          <AgentAvatar name={peer.name} avatar={peer.avatar} size="h-10 w-10" textClass="text-base" working={working} stale={stale} />
        </span>
        <div className="agent-roster__profile">
          <div className="agent-roster__heading">
            <span className="agent-roster__name">{peer.name}</span>
            {peer.builtin && <span className="agent-roster__badge">内置</span>}
          </div>
          {peer.role && <div className="agent-roster__role"><AgentRole role={peer.role} color={peer.roleColor} /></div>}
        </div>
      </div>
      <div className="agent-roster__meta">
        <span>模型 · {model}</span>
        <span>{presenceLabel(working, stale)}</span>
      </div>
    </a>
  )
}

export default function MultiAgent() {
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [modelNames, setModelNames] = useState<Map<string, string>>(() => new Map())
  const [toast, setToast] = useState<string | null>(null)
  const { data: overview, stale } = useAgentOverview(true)
  const activity = new Map(overview?.agents.map(agent => [agent.id, agent.working]))

  useEffect(() => {
    let active = true
    void listMaPeers().then((rows) => {
      if (!active) return
      setPeers(sortAgentPeers(rows))
      setLoadState('ready')
    }).catch((error: unknown) => {
      if (!active) return
      setLoadState('error')
      setToast(errorMessage(error))
    })
    void getSettings().then((settings) => {
      if (active) setModelNames(new Map(settings.models.map(model => [model.id, model.name])))
    }).catch((error: unknown) => {
      if (active) setToast(errorMessage(error))
    })
    return () => { active = false }
  }, [])

  return (
    <div className="agent-roster">
      <p className="agent-roster__count" role="status">
        {loadState === 'loading' ? '正在加载成员…' : loadState === 'error' ? '暂时无法加载成员' : `${peers.length} 位成员`}
      </p>
      {loadState !== 'loading' && (
        <PageBoundary>
          <ul className="agent-roster__grid" role="list">
            {peers.map((peer) => (
              <li key={peer.id}>
                <MemberCard
                  peer={peer}
                  model={peer.modelId ? modelNames.get(peer.modelId) ?? peer.modelId : '跟随默认设置'}
                  working={activity.get(peer.id)}
                  stale={stale}
                />
              </li>
            ))}
            <li>
              <a className="agent-roster__card agent-roster__card--new" href="#/multi-agent/new">
                <AppIcon name="plus" size={20} />
                <span>新建成员</span>
              </a>
            </li>
          </ul>
        </PageBoundary>
      )}
      <Toast message={toast} kind="error" onClose={() => setToast(null)} />
    </div>
  )
}
