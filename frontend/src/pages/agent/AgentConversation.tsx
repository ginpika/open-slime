import { useEffect, useRef, useState } from 'react'
import { ApiError, agentConversation, type AgentConversationMessage, type AgentConversationTranscript } from '../../api/client'
import { AppIcon } from '../../components/AppIcon'
import ChatMarkdown from '../../components/ChatMarkdown'
import { requestOpenSession } from '../../components/ChatNotifier'
import PeerAvatar from '../../components/PeerAvatar'
import SlimePage from '../../components/SlimePage'
import { routeFromHash } from '../../shell/routes'
import './agentConversation.css'

const DATE_TIME = new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
})

const PENDING_STATUS: Record<string, string> = {
  pending: '等待处理', queued: '等待处理', accepted: '等待处理', running: '正在处理',
}

function readLocation() {
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const sessionId = params.get('session') ?? ''
  const messageId = Number(params.get('message'))
  const origin = params.get('from') ?? ''
  const roundtable = origin.startsWith('#/') && ['/collaboration', '/roundtable', '/workflow'].includes(routeFromHash(origin))
  return { sessionId, messageId, backHref: roundtable ? origin : '#/' }
}

function ConversationMessages({ sessionId, messageId }: { sessionId: string; messageId: number }) {
  const [transcript, setTranscript] = useState<AgentConversationTranscript | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const anchor = useRef<HTMLLIElement>(null)
  const jumped = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    let timer: number | undefined
    let loaded = false

    const load = async () => {
      if (document.hidden) {
        timer = window.setTimeout(load, 8000)
        return
      }
      try {
        const result = await agentConversation(sessionId, messageId, controller.signal)
        if (controller.signal.aborted) return
        loaded = true
        setTranscript(result)
        setError(null)
        const pending = result.messages.some(message => message.kind === 'request' && PENDING_STATUS[message.status])
        timer = window.setTimeout(load, pending ? 2500 : 8000)
      } catch (cause) {
        if (controller.signal.aborted) return
        if (cause instanceof ApiError && cause.status === 404) {
          setTranscript(null)
          setError('找不到这条 Agent 通信记录。')
          return
        }
        setError(loaded ? '暂时无法刷新通信记录。' : '暂时无法加载通信记录。')
        if (loaded) timer = window.setTimeout(load, 8000)
      }
    }

    void load()
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [sessionId, messageId, revision])

  useEffect(() => {
    if (!jumped.current && anchor.current) {
      anchor.current.scrollIntoView({ block: 'center' })
      jumped.current = true
    }
  }, [transcript])

  const refresh = () => {
    setError(null)
    setRevision(value => value + 1)
  }

  if (!transcript && !error) return <p className="agent-conversation__notice" role="status">正在加载通信记录…</p>

  return (
    <section className="agent-conversation" aria-label="Agent 通信记录">
      {transcript && (
        <header className="agent-conversation__header">
          <div className="agent-conversation__participants">
            <span className="agent-conversation__participant">
              <span aria-hidden="true"><PeerAvatar name={transcript.agentA.name} avatar={transcript.agentA.avatar} size="h-8 w-8" /></span>
              {transcript.agentA.name}
            </span>
            <span className="agent-conversation__divider" role="img" aria-label="与">↔</span>
            <span className="agent-conversation__participant">
              <span aria-hidden="true"><PeerAvatar name={transcript.agentB.name} avatar={transcript.agentB.avatar} size="h-8 w-8" /></span>
              {transcript.agentB.name}
            </span>
          </div>
          <button type="button" className="agent-conversation__refresh" onClick={refresh} title="刷新通信记录" aria-label="刷新通信记录">
            <AppIcon name="refresh" />
          </button>
        </header>
      )}
      {error && (
        <div className="agent-conversation__notice" role="status">
          <span>{error}</span>
          <button type="button" onClick={refresh}>重试</button>
        </div>
      )}
      {transcript && (transcript.messages.length ? (
        <ol className="agent-conversation__messages" aria-label="双向消息">
          {transcript.messages.map(message => {
            const fromA = message.fromAgentId === transcript.agentA.id
            const from = fromA ? transcript.agentA : transcript.agentB
            const to = message.toAgentId === transcript.agentA.id ? transcript.agentA : transcript.agentB
            const selected = message.kind === 'request' && message.executionId === messageId
            return (
              <li key={message.id} ref={selected ? anchor : null}
                className={`agent-conversation__message${fromA ? '' : ' is-peer'}${selected ? ' is-anchor' : ''}`}>
                <div className="agent-conversation__meta">
                  <strong>{from.name}</strong><AppIcon name="arrow-right" size={12} /><span>{to.name}</span>
                  <time dateTime={new Date(message.createdAt).toISOString()}>{DATE_TIME.format(message.createdAt)}</time>
                </div>
                <div className="agent-conversation__bubble"><ChatMarkdown content={message.content} /></div>
                <MessageStatus message={message} />
              </li>
            )
          })}
        </ol>
      ) : <p className="agent-conversation__notice">这两位 Agent 还没有通信记录。</p>)}
    </section>
  )
}

function MessageStatus({ message }: { message: AgentConversationMessage }) {
  if (message.kind === 'failure') {
    const label = message.status === 'interrupted' ? '执行中断' : message.status === 'cancelled' ? '执行已取消' : '执行失败'
    return <span className="agent-conversation__status is-failed"><AppIcon name="alert" size={13} />{label}</span>
  }
  const pending = message.kind === 'request' ? PENDING_STATUS[message.status] : null
  return pending ? <span className="agent-conversation__status"><AppIcon name="clock" size={13} />{pending}</span> : null
}

export default function AgentConversation() {
  const [location, setLocation] = useState(readLocation)
  useEffect(() => {
    const update = () => setLocation(readLocation())
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])

  const { sessionId, messageId, backHref } = location
  const valid = sessionId && Number.isSafeInteger(messageId) && messageId > 0
  return (
    <SlimePage title="Agent 通信" backHref={backHref} backLabel="返回原会话" onBack={event => {
      if (sessionId && backHref === '#/' && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
        requestOpenSession(sessionId)
      }
    }}>
      {valid ? <ConversationMessages key={`${sessionId}:${messageId}`} sessionId={sessionId} messageId={messageId} />
        : <p className="agent-conversation__notice" role="status">这条通信链接缺少会话或消息编号。</p>}
    </SlimePage>
  )
}
