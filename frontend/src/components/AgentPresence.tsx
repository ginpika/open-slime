import { presenceLabel } from '../api/agentPresence'
import { pickText } from '../theme/color'
import PeerAvatar from './PeerAvatar'

const AGENT_WORKING_COLOR = '#3b82f6'

export function AgentAvatar({ name, avatar, working, stale = false, size = 'h-12 w-12', textClass = 'text-md' }: {
  name: string; avatar?: string | null; working?: boolean; stale?: boolean; size?: string; textClass?: string
}) {
  const label = presenceLabel(working, stale)
  const color = stale || working === undefined ? 'var(--c-faint)' : working ? AGENT_WORKING_COLOR : '#22c55e'
  return <span className="relative inline-flex shrink-0" title={label}>
    <PeerAvatar name={name} avatar={avatar} size={size} textClass={textClass} bordered={false} />
    <span aria-label={label} role="img" className="absolute -bottom-0.5 -right-0.5 h-3.5 w-3.5 rounded-full"
      style={{ background: color, boxShadow: '0 0 0 2px var(--ui-card-fill, var(--c-bg))' }} />
  </span>
}

export function AgentRole({ role, color }: { role?: string | null; color?: string | null }) {
  if (!role) return null
  const background = color && /^#[0-9a-f]{6}$/i.test(color) ? color : '#64748b'
  return <span title={role} className="inline-block max-w-full truncate rounded-full px-2 py-0.5 text-xs font-medium leading-4"
    style={{ background, color: pickText(background) }}>{role}</span>
}
