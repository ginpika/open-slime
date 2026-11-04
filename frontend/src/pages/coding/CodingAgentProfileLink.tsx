import type { CodingAgent } from '../../api/coding'
import { agentProfileHref } from '../../api/agentRuntime'
import { AppIcon } from '../../components/AppIcon'
import PeerAvatar from '../../components/PeerAvatar'

/** 工作台与成员页共用同一份资料；头像和名字都是角色设置的入口。 */
export default function CodingAgentProfileLink({ agent, working = false }: { agent?: CodingAgent | null; working?: boolean }) {
  const name = agent?.name || '默认 Agent'
  return (
    <a className="coding-agent-profile" href={agentProfileHref(agent?.id)}
      aria-label={`编辑${name}的头像、名字与人设`} title="修改头像、名字、模型与角色人设">
      <PeerAvatar name={name} avatar={agent?.avatar} size="h-7 w-7" textClass="text-sm" />
      <span className="coding-agent-name">{name}</span>
      {working && <i className="coding-agent-pulse" aria-hidden="true" />}
      <AppIcon name="edit" size={14} />
    </a>
  )
}
