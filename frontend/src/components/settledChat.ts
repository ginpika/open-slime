import type { SessionMessage } from '../api/client'
import type { ChatMsg, LiveChat } from './chatLive'

type MessageRole = { role: string; kind?: string | null }
export const AGENT_MESSAGE_SENT = 'agent_message_sent'

function startsTurn(message: MessageRole): boolean {
  return message.role === 'user' && message.kind !== 'steer'
}

function finishesTurn(message: MessageRole): boolean {
  return message.role === 'assistant' && !isBackgroundReply(message) && message.kind !== 'steer_reply'
}

export function isBackgroundReply(message: MessageRole): boolean {
  return message.kind === 'subagent' || message.kind === 'subagent_batch'
    || message.kind === 'agent_message' || message.kind === AGENT_MESSAGE_SENT
}

/** The POST acknowledgement, stream and history can all contain the same persisted message. */
function mergeById(history: ChatMsg[], messages: ChatMsg[]): ChatMsg[] {
  if (!messages.length) return history
  const merged = [...history]
  const ids = new Set(history.map(message => message.id))
  for (const message of messages) {
    const id = message.id
    if (id != null && ids.has(id)) continue
    const after = id == null ? -1 : merged.findIndex(row => row.id != null && row.id > id)
    merged.splice(after < 0 ? merged.length : after, 0, message)
    ids.add(id)
  }
  return merged
}

function backgroundRow(row: SessionMessage): ChatMsg {
  return {
    id: row.id, role: 'assistant', content: row.content, kind: row.kind ?? undefined,
    reasoning: row.reasoning ?? undefined, trace: row.trace ?? undefined, subagent: row.subagent ?? undefined,
  }
}

/** Receipts may arrive before the current assistant reply, so merge only the new activity. */
export function mergeAgentMessageReceipts(history: ChatMsg[], rows: SessionMessage[]): ChatMsg[] {
  return mergeById(history, rows.filter(row => row.kind === AGENT_MESSAGE_SENT).map(backgroundRow))
}

export function mergeLiveChat(history: ChatMsg[], live: LiveChat): ChatMsg[] {
  const first = live.userMessageId == null ? -1 : history.findIndex(message => message.id === live.userMessageId)
  if (first >= 0 && history.slice(first + 1).some(message => startsTurn(message) || (!live.running && finishesTurn(message)))) {
    return history
  }

  const messages: ChatMsg[] = [{ id: live.userMessageId ?? undefined, role: 'user', content: live.userText }]
  messages.push(...live.steers.map(({ messageId, input }): ChatMsg => ({ id: messageId, role: 'user', content: input, kind: 'steer' })))
  messages.push(...live.interims.map(({ messageId, content }): ChatMsg => ({ id: messageId, role: 'assistant', content, kind: 'steer_reply' })))
  return [...mergeById(history, messages), {
    role: 'assistant', content: live.content, reasoning: live.reasoning || undefined,
    trace: live.trace.length ? live.trace : undefined, streaming: live.running,
  }]
}

/** Backfill the persisted reply for this turn without replacing newer messages or an edited history. */
export function backfillSettledReply(history: ChatMsg[], live: LiveChat, rows: SessionMessage[]): ChatMsg[] {
  if (live.running || live.userMessageId == null) return history
  const userIndex = rows.findIndex(row => row.id === live.userMessageId && row.role === 'user')
  if (userIndex < 0) return history
  const localUser = history.findIndex(msg => msg.role === 'user' && msg.id === live.userMessageId)
  if (localUser < 0) return history
  const nextUser = rows.findIndex((row, index) => index > userIndex && startsTurn(row))
  const turnRows = rows.slice(userIndex + 1, nextUser < 0 ? rows.length : nextUser)
  const deliveries = turnRows.filter(isBackgroundReply).map(backgroundRow)
  const replyIndex = turnRows.findIndex(finishesTurn)
  const reply = turnRows[replyIndex]
  if (!reply) return mergeById(history, deliveries)
  const localReplyIndex = history.findIndex((msg, index) => index > localUser && (startsTurn(msg) || finishesTurn(msg)))
  const localReply = history[localReplyIndex]
  if (!localReply || !finishesTurn(localReply) || localReply.streaming
    || localReply.content !== live.content || (localReply.id != null && localReply.id !== reply.id)) {
    return mergeById(history, deliveries)
  }
  const patched = history.map((msg, index) => index !== localReplyIndex ? msg : {
    ...msg, id: reply.id, content: reply.content, reasoning: reply.reasoning ?? undefined,
    trace: reply.trace ?? undefined, kind: reply.kind ?? undefined, subagent: reply.subagent ?? undefined,
    streaming: false,
  })
  const duringTurn = turnRows.slice(0, replyIndex)
    .filter(row => row.kind === 'steer' || row.kind === 'steer_reply')
    .map((row): ChatMsg => ({ id: row.id, role: row.role === 'user' ? 'user' : 'assistant', content: row.content, kind: row.kind ?? undefined }))
  return mergeById(patched, [...duringTurn, ...deliveries])
}
