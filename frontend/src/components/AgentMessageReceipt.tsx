import { agentConversationHref } from '../shell/routes'
import { AppIcon } from './AppIcon'
import './agentMessageReceipt.css'

type Receipt = { id: number; recipientId: string; recipientName: string }

function readReceipt(content: string): Receipt | null {
  try {
    const value = JSON.parse(content) as Partial<Receipt> | null
    return value && typeof value.id === 'number' && Number.isSafeInteger(value.id) && value.id > 0
      && typeof value.recipientId === 'string' && typeof value.recipientName === 'string' && value.recipientName.trim()
      ? { id: value.id, recipientId: value.recipientId, recipientName: value.recipientName }
      : null
  } catch {
    return null
  }
}

export default function AgentMessageReceipt({ sessionId, content, peers }: {
  sessionId: string
  content: string
  peers: { get(id: string): { avatar?: string | null } | undefined }
}) {
  const receipt = readReceipt(content)
  if (!receipt) return null
  const { id, recipientId, recipientName } = receipt
  const avatar = peers.get(recipientId)?.avatar
  return (
    <a className="agent-message-receipt" href={agentConversationHref(sessionId, id)}
      aria-label={`已发送消息给 ${recipientName}，查看 Agent 会话`}>
      <span>已发送消息给</span>
      <span className="agent-message-receipt__agent">
        <span className="agent-message-receipt__avatar" aria-hidden="true">
          {avatar ? <img src={avatar} alt="" /> : <AppIcon name="agent" size={12} />}
        </span>
        <span className="agent-message-receipt__name">{recipientName}</span>
      </span>
      <span className="agent-message-receipt__action" aria-hidden="true">
        <AppIcon name="arrow-right" size={14} />
        <span>查看会话</span>
      </span>
    </a>
  )
}
