import type { SessionMessage } from '../api/client'
import type { ChatMsg, LiveChat } from './chatLive'

/** Backfill the persisted reply for this turn without replacing newer messages or an edited history. */
export function backfillSettledReply(history: ChatMsg[], live: LiveChat, rows: SessionMessage[]): ChatMsg[] {
  if (live.running || live.userMessageId == null) return history
  const userIndex = rows.findIndex(row => row.id === live.userMessageId && row.role === 'user')
  if (userIndex < 0) return history
  let reply: SessionMessage | undefined
  for (const row of rows.slice(userIndex + 1)) {
    if (row.role === 'user') break
    if (row.role === 'assistant' && row.kind !== 'subagent') { reply = row; break }
  }
  if (!reply) return history
  const localUser = history.findIndex(msg => msg.role === 'user' && msg.id === live.userMessageId)
  const localReply = history[localUser + 1]
  if (localUser < 0 || !localReply || localReply.role !== 'assistant' || localReply.streaming
    || localReply.content !== live.content || (localReply.id != null && localReply.id !== reply.id)) return history
  return history.map((msg, index) => index !== localUser + 1 ? msg : {
    ...msg, id: reply.id, content: reply.content, reasoning: reply.reasoning ?? undefined,
    trace: reply.trace ?? undefined, kind: reply.kind ?? undefined, subagent: reply.subagent ?? undefined,
    streaming: false,
  })
}
