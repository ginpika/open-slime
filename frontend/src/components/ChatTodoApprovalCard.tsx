import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ApiError,
  decideSessionTodoApproval,
  loginUrlOf,
  sessionTodoApprovals,
  type ChatTodoApproval,
  type ChatTodoDecision,
} from '../api/client'
import { getLive, onChatSettled } from './chatLive'
import { subscribeSessionEvents } from './sessionEvents'

export function useChatTodoApprovals(sessionId: string | null) {
  const [snapshot, setSnapshot] = useState<{ sessionId: string; rows: ChatTodoApproval[] } | null>(null)
  const loader = useRef<{ sessionId: string; refresh: (receipt?: ChatTodoApproval) => void } | null>(null)

  useEffect(() => {
    if (sessionId === null) return
    let active = true
    let version = 0
    const refresh = (receipt?: ChatTodoApproval) => {
      if (receipt) {
        setSnapshot(current => current?.sessionId === sessionId ? {
          sessionId,
          rows: current.rows.map(row => row.operationId === receipt.operationId ? receipt : row),
        } : current)
      }
      const request = ++version
      void sessionTodoApprovals(sessionId).then(rows => {
        if (active && request === version) setSnapshot({ sessionId, rows })
      }).catch(() => {
        // Keep any known receipts until a subsequent refresh succeeds.
      })
    }
    loader.current = { sessionId, refresh }
    refresh()
    const unsubscribe = subscribeSessionEvents(event => {
      if (event.sessionId === sessionId && (event.kind === 'answered' || event.kind === 'todo')) refresh()
    })
    const stopListening = onChatSettled(settledId => {
      if (settledId === sessionId) refresh()
    })
    return () => {
      active = false
      loader.current = null
      unsubscribe()
      stopListening()
    }
  }, [sessionId])

  const refresh = useCallback((id: string) => {
    if (loader.current?.sessionId === id) loader.current.refresh()
  }, [])

  const decide = useCallback(async (operationId: number, decision: ChatTodoDecision) => {
    if (sessionId === null || loader.current?.sessionId !== sessionId) {
      throw new Error('会话已切换，请重新打开审批。')
    }
    if (getLive(sessionId)?.running) throw new Error('请等当前回复结束后再处理审批。')
    let receipt: ChatTodoApproval | undefined
    try {
      receipt = await decideSessionTodoApproval(sessionId, operationId, decision)
    } finally {
      if (loader.current?.sessionId === sessionId) loader.current.refresh(receipt)
    }
  }, [sessionId])

  const byReply = useMemo(() => {
    const grouped = new Map<number, ChatTodoApproval[]>()
    if (!snapshot || snapshot.sessionId !== sessionId) return grouped
    for (const approval of snapshot.rows) {
      const group = grouped.get(approval.replyMessageId)
      if (group) group.push(approval)
      else grouped.set(approval.replyMessageId, [approval])
    }
    return grouped
  }, [snapshot, sessionId])

  return { byReply, decide, refresh }
}

const STATUS: Record<ChatTodoApproval['status'], string> = {
  pending: '待确认',
  approved: '已批准 · 待办已完成',
  rejected: '已拒绝',
  stale: '审批已失效',
}

const button = 'min-h-11 rounded-lg px-4 text-base font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent disabled:cursor-not-allowed disabled:opacity-50'

export default function ChatTodoApprovalCard({ approval, busy, onDecision }: {
  approval: ChatTodoApproval
  busy: boolean
  onDecision: (operationId: number, decision: ChatTodoDecision) => Promise<void>
}) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  const pending = approval.status === 'pending'
  const decidedAt = approval.decidedAt == null ? null : new Date(approval.decidedAt)

  const submit = async (decision: ChatTodoDecision) => {
    if (lock.current || busy || !pending) return
    lock.current = true
    setSubmitting(true)
    setError('')
    try {
      await onDecision(approval.operationId, decision)
    } catch (err) {
      const loginUrl = loginUrlOf(err)
      if (loginUrl) window.location.assign(loginUrl)
      else setError(decisionError(err))
    } finally {
      lock.current = false
      setSubmitting(false)
    }
  }

  return (
    <section aria-label="待办完成审批" aria-busy={submitting}
      className="my-2 min-w-0 space-y-2 rounded-xl border border-line bg-bg p-4 text-base leading-relaxed">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <p className="font-medium">待办完成审批</p>
        <p role="status" className="text-xs text-muted">{submitting ? '正在提交…' : STATUS[approval.status]}</p>
      </div>
      <p className="whitespace-pre-wrap break-words">{approval.content}</p>
      {approval.note && (
        <p className="whitespace-pre-wrap break-words text-sm text-muted max-sm:text-base">{approval.note}</p>
      )}
      {pending && (
        <div className="flex flex-wrap gap-2 pt-1">
          <button type="button" disabled={busy || submitting} onClick={() => void submit('approve')}
            className={`${button} bg-fg text-primary-fg`}>批准完成</button>
          <button type="button" disabled={busy || submitting} onClick={() => void submit('reject')}
            className={`${button} border border-line text-fg`}>拒绝</button>
        </div>
      )}
      {decidedAt && (
        <time dateTime={decidedAt.toISOString()} className="block text-xs text-muted">{decidedAt.toLocaleString('zh-CN')}</time>
      )}
      {pending && error && <p role="alert" className="text-sm text-fg max-sm:text-base">{error}</p>}
    </section>
  )
}

function decisionError(error: unknown): string {
  if (error instanceof ApiError) {
    try {
      const body = JSON.parse(error.body) as { message?: unknown }
      if (typeof body.message === 'string' && body.message) return body.message
    } catch { /* The response may be from a proxy. */ }
    return '待办审批未完成，请重试。'
  }
  return error instanceof Error ? error.message : '待办审批未完成，请重试。'
}
