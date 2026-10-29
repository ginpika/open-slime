/** minis 前端 API client（M0：fetch + 类型；SSO 401 处理见 me/App） */

import { apiFetch } from './desktop'

export function getTtsSettings(): Promise<{ profiles: { id: string; name: string }[]; defaultProfileId: string | null }> {
  return requestJson('/api/settings/tts')
}

export class ApiError extends Error {
  status: number
  body: string

  constructor(status: number, body: string) {
    super(`API ${status}`)
    this.status = status
    this.body = body
  }
}

/** 测试类请求的响应上限：与后端 TEST_TIMEOUT_MS 对齐，避免按钮长时间挂起 */
const TEST_TIMEOUT_MS = 5000

/** timeoutMs 缺省/非正数表示不限时；超时中止请求并抛出中文错误，交由调用方 Toast 展示 */
export async function requestJson<T>(
  path: string,
  init?: RequestInit,
  timeoutMs = 0,
): Promise<T> {
  const controller = timeoutMs > 0 ? new AbortController() : null
  const timer = controller ? window.setTimeout(() => controller.abort(), timeoutMs) : null
  try {
    const res = await apiFetch(path, {
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      signal: controller?.signal ?? null,
      ...init,
    })
    if (!res.ok) {
      throw new ApiError(res.status, await res.text().catch(() => ''))
    }
    return res.json() as Promise<T>
  } catch (e) {
    if (controller?.signal.aborted) {
      throw new Error(`超过 ${timeoutMs / 1000}s 未响应`)
    }
    throw e
  } finally {
    if (timer !== null) window.clearTimeout(timer)
  }
}

export type Health = { status: string; service: string; time: string }

export type SsoUser = {
  id: string
  username: string
  nickname: string
  email: string
  role: string
  avatarUrl: string
}

export function health(): Promise<Health> {
  return requestJson<Health>('/api/health')
}

/** 当前用户；未接入 hostid（server-url 空）时后端返回 dev 用户 */
export function me(): Promise<SsoUser> {
  return requestJson<SsoUser>('/api/auth/me')
}

export function logout(): Promise<{ success: boolean }> {
  return requestJson<{ success: boolean }>('/api/auth/logout', { method: 'POST' })
}

/* ---------- FAST 检索（F1 home） ---------- */

export type SearchHit = {
  /** entity=图谱锚点实体；doc=文档（chunk 命中按父文档聚合）；url 为文章 slug（内部文档 null） */
  kind: 'chunk' | 'entity' | 'doc' | 'snapshot'
  chunkId: number | null
  entityId: string | null
  title: string
  snippet: string
  source: string
  sourceRef: string
  url: string | null
  score: number
  matched: string[]
  /** kind=chunk（RAG 模式）时为该块状态；其余命中为 null */
  status: ChunkStatus | null
}

/** KV 附加数据的值类型，控制前端渲染方式 */
export type PropType = 'text' | 'number' | 'url' | 'date' | 'bool'
export type Prop = { k: string; v: string; t: PropType }

export type GraphNode = {
  id: string
  name: string
  type: string
  props?: Prop[]
  /** 行级时间戳（毫秒 epoch，旧数据可空）：Props 区只读展示，不可编辑 */
  createdAt?: number | null
  updatedAt?: number | null
}
export type GraphEdge = { id: string; from: string; to: string; predicate: string; props?: Prop[] }
export type SearchGraph = { nodes: GraphNode[]; edges: GraphEdge[] }

export type SearchResponse = {
  query: string
  hits: SearchHit[]
  graph: SearchGraph
  web: {
    enabled: boolean; error?: string | null; warnings?: string[]
    results: { title: string; url: string; snippet: string; summary: string; content: string
      siteName: string; date: string; score: number; authority: number }[]
  }
}

/** rag=true：RAG 检索模式（测试暗门）——全量召回且只返回 chunk 命中 */
export function search(query: string, rag = false): Promise<SearchResponse> {
  return requestJson<SearchResponse>('/api/search', {
    method: 'POST',
    body: JSON.stringify(rag ? { query, rag: true } : { query }),
  })
}

/* ---------- AI 会话（B4 会话桥） ---------- */

export type ChatReply = {
  /** 本条消息实际落入的会话 id（缺省发送时后端取最近会话或新建） */
  sessionId: string | null
  /** 本次提问落库的消息 id，回退到这条提问前时用它定位删除区间 */
  messageId: number | null
  message: string
  rawResponse: string | null
  success: boolean | null
  error: string | null
}

/** 会话列表项；modelId/promptId/showCot 为「…」菜单在该会话的配置
 *  （modelId/promptId 为 null 表示未指定、回退设置页默认项） */
export type SessionItem = {
  id: string
  title: string | null
  modelId: string | null
  promptId: string | null
  /** 本会话跟哪位 Agent 对话（agent.id）；null = 内置 Agent（用全局提示词） */
  agentId: string | null
  /** 本会话所属项目（memory-v6）：项目决定记忆归属与跨项目隔离；null = 无项目（个人工作域） */
  projectId: string | null
  showCot: boolean | null
  /** 思维链分段是否在右侧标轮次计数（「…」菜单开关，缺省不显示） */
  showRounds: boolean | null
  /** 潜意识子体往这个会话投过新消息且用户还没打开过（列表里打未读点）；打开会话即清零 */
  unread: boolean | null
  /** 该会话此刻有在途生成：前端据此发现「刚答完」并弹通知（本地发起与刷新后重挂都算） */
  generating: boolean
  updatedAt: string | null
}

/** assistant 的一次思考分段：type=reasoning（普通思考，text=思考内容）|
 *  tool（工具调用，name=工具名，args=调用参数 JSON）；前端按段渲染折叠块。
 *  round=本段属于第几轮工具往返（0 基）；早于该字段的旧数据为 null（前端按序回推） */
export type TraceSegment = {
  type: 'reasoning' | 'tool'
  text: string | null
  name: string | null
  args: string | null
  round: number | null
}

/** 子代理结论气泡的元信息（kind='subagent' 时才有）：title/summary 由模型把长报告压缩而来；
 *  status=done（跑出结论）| failed（未跑出）；execId 可在潜意识页按它回看检索过程 */
export type SubagentBrief = {
  title: string | null
  summary: string | null
  status: string | null
  execId: number | null
}

export type SessionMessage = {
  id: number
  role: string
  content: string
  /** assistant 的思维链（CoT，历史回放用）；非 assistant 或非推理模型为 null */
  reasoning: string | null
  /** assistant 的结构化思考分段（历史回放用）；无分段（非推理模型或旧消息）为 null */
  trace: TraceSegment[] | null
  /** 消息形态：null=普通对话；'subagent'=子代理结论气泡（前端只渲染标题+摘要，点开看全文） */
  kind: string | null
  /** 子代理结论气泡的元信息；普通对话为 null */
  subagent: SubagentBrief | null
  createdAt: string | null
}

export type NewSession = { id: string; projectId: string | null }

/** 发一条消息；sessionId 缺省 = 后端取最近会话（无则新建）；回复含实际使用的 sessionId。
 *  modelId/promptId 缺省 = 后端设置页默认项 */
export function chat(
  input: string,
  sessionId?: string,
  modelId?: string,
  promptId?: string,
): Promise<ChatReply> {
  return requestJson<ChatReply>('/api/chat', {
    method: 'POST',
    body: JSON.stringify({ sessionId, input, modelId, promptId }),
  })
}

/** 流式会话的阶段回调：meta=提问已落库（拿删除区间定位用的 messageId）；
 *  round=进入新一轮补全（>0 为工具轮之后，上一轮正文需清掉）；reasoning/content=增量片段；
 *  tool=模型发起一次工具调用（name=工具名，args=调用参数 JSON） */
export type ChatStreamHandlers = {
  onMeta?: (meta: { sessionId: string; messageId: number }) => void
  onRound?: (round: number) => void
  onReasoning?: (delta: string) => void
  onContent?: (delta: string) => void
  onTool?: (tool: { name: string; args: string }) => void
}

/**
 * 流式发问（SSE）：增量片段随回调实时到达，resolve 为最终回复（落库正本）。
 *
 * <p>EventSource 只支持 GET 且无法带自定义 body，故用 fetch + ReadableStream 手工解析
 * text/event-stream；非 2xx 时的错误体与 requestJson 一致（401 仍可被 loginUrlOf 解析）。
 *
 * <p>signal：停止按钮中断流读取（abort 后以 AbortError 拒绝；调用方据此静默收尾部分输出）。
 */
export async function chatStream(
  input: string,
  sessionId: string | undefined,
  modelId: string | undefined,
  promptId: string | undefined,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<ChatReply> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const res = await apiFetch('/api/chat/stream', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, input, modelId, promptId }),
    signal,
  })
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  if (!res.body) throw new ApiError(res.status, '响应无流式内容')

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let reply: ChatReply | null = null

  /** 解析一个 SSE 事件块（event: / data: 行，data 可能多行） */
  const dispatch = (block: string) => {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (data.length === 0) return
    const payload = JSON.parse(data.join('\n')) as Record<string, unknown>
    if (name === 'meta') {
      handlers.onMeta?.(payload as unknown as { sessionId: string; messageId: number })
    } else if (name === 'round') {
      handlers.onRound?.(payload.round as number)
    } else if (name === 'reasoning') {
      handlers.onReasoning?.(payload.delta as string)
    } else if (name === 'content') {
      handlers.onContent?.(payload.delta as string)
    } else if (name === 'tool') {
      handlers.onTool?.(payload as unknown as { name: string; args: string })
    } else if (name === 'done') {
      reply = payload as unknown as ChatReply
    } else if (name === 'error') {
      throw new Error(String(payload.error ?? '流式响应失败'))
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let m: RegExpExecArray | null
      /* 事件以空行分隔；缓冲整体重扫，跨 chunk 拆开的换行也能正确配对 */
      while ((m = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, m.index)
        buffer = buffer.slice(m.index + m[0].length)
        dispatch(block)
      }
    }
  } catch (e) {
    await reader.cancel().catch(() => {})
    throw e
  } finally {
    reader.releaseLock()
  }
  if (!reply) throw new Error('流式响应中断')
  return reply
}

/** 停止该会话进行中的流式生成（「停止」按钮）：通知后端当前等待的响应不再需要，
 *  已产出的部分回答照常落库。stopped=false 表示该会话当前没有在生成的流 */
export function stopChat(sessionId: string): Promise<{ stopped: boolean }> {
  return requestJson<{ stopped: boolean }>('/api/chat/stop', {
    method: 'POST',
    body: JSON.stringify({ sessionId }),
  })
}

/** 最近会话列表（按更新时间倒序，首位即最近会话） */
export function listSessions(): Promise<SessionItem[]> {
  return requestJson<SessionItem[]>('/api/sessions')
}

/* ---------- 在途生成（断点续传）：刷新/换标签页后重挂到后端还在跑的生成 ---------- */

/** 后端在途生成快照：running=true 表示此刻仍在生成；其余字段为已产出的部分 */
export type LiveRun = {
  sessionId: string
  running: boolean
  /** 本次提问原文与落库行 id（续看时贴回列表、定位回退区间） */
  userText: string | null
  userMessageId: number | null
  content: string
  reasoning: string
  trace: TraceSegment[]
  error: string | null
}

/** 查某会话此刻是否有在途生成；running=false 即「没有」（照常走历史加载） */
export function sessionLive(id: string): Promise<LiveRun> {
  return requestJson<LiveRun>(`/api/sessions/${encodeURIComponent(id)}/live`)
}

/** 续传流回调：snapshot=整份快照（订阅时先到一份，据此覆盖本地进度；快照即全量，不会漏增量）、
 *  round=进入新一轮（>0 时正文清空）、delta=增量片段、tool=一次工具调用、
 *  terminal=收尾（done / error，payload 同为快照且 running 已是 false） */
export type LiveStreamHandlers = {
  onSnapshot?: (run: LiveRun) => void
  onRound?: (round: number) => void
  onDelta?: (delta: { reasoning: string; content: string }) => void
  onTool?: (tool: { name: string; args: string; round: number }) => void
  onTerminal?: (run: LiveRun, failed: boolean) => void
}

/**
 * 订阅某会话的续传流（SSE）：先到 snapshot，随后是 round / delta / tool，收尾推 done 或 error。
 * resolve 表示这条流已结束（可能只是被服务端掐断，调用方重新查快照再挂即可）。
 */
export async function sessionLiveStream(
  id: string,
  handlers: LiveStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const res = await apiFetch(`/api/sessions/${encodeURIComponent(id)}/live/stream`, {
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  if (!res.body) throw new ApiError(res.status, '响应无流式内容')

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  /** 解析一个 SSE 事件块（event: / data: 行，data 可能多行） */
  const dispatch = (block: string) => {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (data.length === 0) return
    const payload = JSON.parse(data.join('\n')) as Record<string, unknown>
    if (name === 'snapshot') {
      handlers.onSnapshot?.(payload as unknown as LiveRun)
    } else if (name === 'round') {
      handlers.onRound?.(payload.round as number)
    } else if (name === 'delta') {
      handlers.onDelta?.(payload as unknown as { reasoning: string; content: string })
    } else if (name === 'tool') {
      handlers.onTool?.(payload as unknown as { name: string; args: string; round: number })
    } else if (name === 'done') {
      handlers.onTerminal?.(payload as unknown as LiveRun, false)
    } else if (name === 'error') {
      handlers.onTerminal?.(payload as unknown as LiveRun, true)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let m: RegExpExecArray | null
      while ((m = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, m.index)
        buffer = buffer.slice(m.index + m[0].length)
        dispatch(block)
      }
    }
  } catch (e) {
    await reader.cancel().catch(() => {})
    throw e
  } finally {
    reader.releaseLock()
  }
}

/* ---------- 会话事件流（STEP 4）：即时推送，取代前端定时轮询会话列表 ---------- */

/** 一条会话事件：kind=delivered（有新消息投递，如子代理回报）| answered（该会话答完了）
 *  | todo（该会话的个人待办清单更新，前端据此刷新 TODO 卡片） */
export type SessionEvent = {
  /** 事件游标（SSE id）：重连时作为 Last-Event-ID 带去，后端据此补推断线期间漏掉的 */
  id: number
  sessionId: string
  kind: 'delivered' | 'answered' | 'todo'
  /** 该会话此刻是否未读（delivered 有意义：前端据此弹「有新消息」气泡） */
  unread: boolean
  /** 会话标题（便于通知直接显示，省一次列表请求） */
  title: string | null
}

/**
 * 订阅全局会话事件流（SSE）：每条事件实时到达。
 *
 * <p>EventSource 只支持 GET 且不便带自定义头（这里要带 Last-Event-ID），故与其它流一样用
 * fetch + ReadableStream 手工解析；resolve 表示本次连接结束（服务端掐断 / 超时），调用方重挂即可。
 *
 * @param lastEventId 上次收到的最后一条事件 id；带上它后端会把断线期间的事件补推一遍
 */
export async function sessionEventsStream(
  handlers: { onEvent: (e: SessionEvent) => void },
  signal: AbortSignal,
  lastEventId?: number | null,
): Promise<void> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (lastEventId != null) headers['Last-Event-ID'] = String(lastEventId)
  const res = await apiFetch('/api/events/stream', {
    credentials: 'same-origin',
    headers,
    signal,
  })
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  if (!res.body) throw new ApiError(res.status, '响应无流式内容')

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let curId: number | null = null

  /** 解析一个 SSE 事件块（id: / event: / data: 行，data 可能多行） */
  const dispatch = (block: string) => {
    const data: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('id:')) {
        const v = Number(line.slice(3).trim())
        if (Number.isFinite(v)) curId = v
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (data.length === 0) return
    try {
      const payload = JSON.parse(data.join('\n')) as Omit<SessionEvent, 'id'>
      handlers.onEvent({ id: curId ?? 0, ...payload })
    } catch {
      /* 坏帧忽略：推送是尽力而为，漏一条由下一批刷新兜底 */
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let m: RegExpExecArray | null
      while ((m = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, m.index)
        buffer = buffer.slice(m.index + m[0].length)
        dispatch(block)
      }
    }
  } catch (e) {
    await reader.cancel().catch(() => {})
    throw e
  } finally {
    reader.releaseLock()
  }
}

/**
 * 新建空会话；后续 chat 需显式传该 id，否则会续接到最近会话。
 * 可传 projectId 绑定项目（memory-v6 §15.2）：有项目 → 提炼落 PROJECT×L1 且跨项目隔离。
 */
export function createSession(projectId?: string | null): Promise<NewSession> {
  return requestJson<NewSession>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify({ projectId: projectId ?? null }),
  })
}

/** 绑定 / 解绑会话所属项目（记忆作用域切换：旧项目条目不删除、只是不再注入） */
export function bindSessionProject(id: string, projectId: string | null): Promise<NewSession> {
  return requestJson<NewSession>(`/api/sessions/${encodeURIComponent(id)}/project`, {
    method: 'PUT',
    body: JSON.stringify({ projectId }),
  })
}
/** 删除会话（连同其消息正本）；removed=一并删除的消息行数 */
export function deleteSession(id: string): Promise<{ sessionId: string; removed: number }> {
  return requestJson<{ sessionId: string; removed: number }>(
    `/api/sessions/${encodeURIComponent(id)}`,
    { method: 'DELETE' },
  )
}

/** 某会话的历史消息（仅 user/assistant，按行序） */
export function sessionMessages(id: string): Promise<SessionMessage[]> {
  return requestJson<SessionMessage[]>(`/api/sessions/${encodeURIComponent(id)}/messages`)
}

/** 个人待办清单的一项（plan-of-record）：status ∈ pending | in_progress | done | failed */
export type SessionTodo = {
  seq: number
  content: string
  status: string
  note: string | null
}

/** 某会话的个人待办清单（plan-of-record，由 write_todos 覆盖式写入）：会话页 TODO 卡片读当前进度 */
export function sessionTodos(id: string): Promise<SessionTodo[]> {
  return requestJson<SessionTodo[]>(`/api/sessions/${encodeURIComponent(id)}/todos`)
}

/** 请求体（与真实发送一致：model + messages + tools；stream 等由后端在出站前补上） */
export type RawContextBody = {
  model: string
  messages: Record<string, unknown>[]
  tools?: unknown[]
}

/** 一个 Turn 在请求体 messages 中的切片（后端按会话 Turn 序列给出）：
 *  from（含）~ to（不含）；source=触发来源；replyMessageId=回答行 id（在途 / 未落定为 null） */
export type RawContextTurn = {
  from: number
  to: number
  source: string
  replyMessageId: number | null
}

/** 会话的原始上下文预览：body=请求体本身；turns=各 Turn 的切片（仅用于按 Turn 对齐与回看，
 *  不属于请求体——复制 JSON 时只取 body） */
export type RawContext = {
  body: RawContextBody
  turns: RawContextTurn[]
}

/** 查看某会话的原始上下文：此刻在该会话发消息时系统发给大模型接口的内容 */
export function sessionRawContext(id: string): Promise<RawContext> {
  return requestJson<RawContext>(`/api/sessions/${encodeURIComponent(id)}/raw-context`)
}

/** 一次 LLM 调用的留痕：round=该 Turn 内第几轮（0 基）；request=当时真正发出的请求体 JSON 文本；
 *  response=收到的响应体 JSON 文本（该轮失败为 null）；withTools=该轮是否带工具面（末轮撤掉为 false） */
export type TurnLlmCall = {
  round: number
  request: string
  response: string | null
  withTools: boolean
}

/** 某条回答（一个 Turn）的全部 LLM 调用留痕；replyMessageId 取自 raw-context 的 Turn 切片 */
export function sessionTurnCalls(
  id: string,
  replyMessageId: number,
): Promise<{ replyMessageId: number; calls: TurnLlmCall[] }> {
  return requestJson(
    `/api/sessions/${encodeURIComponent(id)}/turns/${replyMessageId}/llm-calls`,
  )
}

/** 会话配置（「…」菜单在该会话的选择）：null=未设置（回退设置页默认 / CoT 默认显示；
 *  未传的配置保持原值） */
export type SessionConfig = {
  modelId: string | null
  promptId: string | null
  /** 跟哪位 Agent 对话（agent.id）；null / 空串 = 内置 Agent */
  agentId: string | null
  showCot: boolean | null
  showRounds: boolean | null
}

/** 更新会话配置：仅传需要改的字段（null 项后端保持原值），返回更新后的完整配置 */
export function updateSessionConfig(id: string, cfg: SessionConfig): Promise<SessionConfig> {
  return requestJson<SessionConfig>(`/api/sessions/${encodeURIComponent(id)}/config`, {
    method: 'PUT',
    body: JSON.stringify(cfg),
  })
}

export type Rewind = { sessionId: string; removed: number }

/** 回退到某条提问之前：该条及其后的消息从正本删除（removed=删除行数）。
 *  messageId 优先定位；发送失败还没拿到落库 id 时由 keep（保留前 N 条）兜底 */
export function rewindSession(
  id: string,
  pos: { messageId: number | null; keep: number },
): Promise<Rewind> {
  return requestJson<Rewind>(`/api/sessions/${encodeURIComponent(id)}/rewind`, {
    method: 'POST',
    body: JSON.stringify(pos),
  })
}

/** 分支：以某条消息为界，新建一个副本会话——源会话到这条（含）为止的转录整段复制过去，
 *  源会话本身不动。返回新会话 id（前端据此打开分支） */
export function branchSession(id: string, messageId: number): Promise<NewSession> {
  return requestJson<NewSession>(`/api/sessions/${encodeURIComponent(id)}/branch`, {
    method: 'POST',
    body: JSON.stringify({ messageId }),
  })
}

/* ---------- 图谱（F3 explorer） ---------- */

/** 实体搜索：q 空 → 关系最多的实体（入口建议） */
export function graphEntities(q: string): Promise<GraphNode[]> {
  const qs = q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ''
  return requestJson<GraphNode[]>(`/api/graph/entities${qs}`)
}

/** 以实体为中心的 1~N 跳子图 */
export function graphNeighbors(entityId: string, depth: number): Promise<SearchGraph> {
  return requestJson<SearchGraph>(
    `/api/graph/neighbors?entityId=${encodeURIComponent(entityId)}&depth=${depth}`,
  )
}

/** 系统中全部锚点（types 含 ANCHOR 标记）及锚点之间的关系子图 */
export function graphAnchors(): Promise<SearchGraph> {
  return requestJson<SearchGraph>('/api/graph/anchors')
}

/** 新增实体（name 必填，type 留空默认 CONCEPT）；同名实体不重复创建，返回落库后的实体 */
export function graphCreateEntity(name: string, type: string): Promise<GraphNode> {
  return requestJson<GraphNode>('/api/graph/entities', {
    method: 'POST',
    body: JSON.stringify({ name, type }),
  })
}

/** 新增关系（subjectId → 目标实体 name；目标不存在时后端按 CONCEPT 自动创建），返回落库后的关系 */
export function graphCreateRelation(
  subjectId: string,
  name: string,
  predicate: string,
): Promise<GraphEdge> {
  return requestJson<GraphEdge>('/api/graph/relations', {
    method: 'POST',
    body: JSON.stringify({ subjectId, name, predicate }),
  })
}

/** 覆盖式更新实体 KV 属性，返回后端规范化后的列表 */
export function graphUpdateEntityProps(id: string, props: Prop[]): Promise<Prop[]> {
  return requestJson<Prop[]>(`/api/graph/entities/${encodeURIComponent(id)}/props`, {
    method: 'PUT',
    body: JSON.stringify(props),
  })
}

/** 覆盖式更新关系 KV 属性，返回后端规范化后的列表 */
export function graphUpdateRelationProps(id: string, props: Prop[]): Promise<Prop[]> {
  return requestJson<Prop[]>(`/api/graph/relations/${encodeURIComponent(id)}/props`, {
    method: 'PUT',
    body: JSON.stringify(props),
  })
}

/** 401 body 中的登录跳转 URL */
export function loginUrlOf(err: unknown): string | null {
  if (!(err instanceof ApiError) || err.status !== 401) return null
  try {
    const json = JSON.parse(err.body) as { loginUrl?: string }
    return json.loginUrl ?? null
  } catch {
    return null
  }
}

/* ---------- 文档导入（粘贴文本 → 直接保存为 markdown 文章） ---------- */

export type ImportResult = {
  url: string
  title: string
}

/** 导入文档：直接作为 markdown 文章保存到 data/article/{url}.md；title 可选（标题栏填写） */
export function importArticle(content: string, title?: string): Promise<ImportResult> {
  return requestJson<ImportResult>('/api/import', {
    method: 'POST',
    body: JSON.stringify({ content, title }),
  })
}

/* ---------- 文档分析（LLM 抽取三元组 → function calling 入库/向量化） ---------- */

/** 实体-关系-实体 三元组；subjectType/objectType 为 LLM 自由判断的实体类型（缺省 CONCEPT，仅新建实体生效）；
 *  subjectProps/objectProps 为 LLM 为两端实体附带并将在提交时合并写入的 KV 属性；
 *  subjectAliases/objectAliases 为两端实体的别名/他称，提交时并入实体 aliases（检索用） */
export type Triple = {
  subject: string
  predicate: string
  object: string
  subjectType?: string
  objectType?: string
  subjectProps?: Prop[]
  objectProps?: Prop[]
  subjectAliases?: string[]
  objectAliases?: string[]
}

export type AnalyzeResponse = {
  triples: Triple[]
  summary: string | null
  viaFunctionCalling: boolean
  model: string
}

export type ToolCallLog = {
  round: number
  tool: string
  arguments: string
  result: string
  ok: boolean
}

export type CommitResult = {
  nodes: number
  edges: number
  vectorized: boolean
  toolCalls: ToolCallLog[]
  finish: string | null
}

/** 分析文档：抽取实体-关系-实体三元组 */
export function analyzeDocument(content: string): Promise<AnalyzeResponse> {
  return requestJson<AnalyzeResponse>('/api/analyze', {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

/** 确认入库：三元组经 function calling 转为点/边入库并向量化 */
export function commitTriples(triples: Triple[]): Promise<CommitResult> {
  return requestJson<CommitResult>('/api/analyze/commit', {
    method: 'POST',
    body: JSON.stringify({ triples }),
  })
}

/* ---------- 文档分析工作流（步骤1：选择 Chunk；文档 ↔ chunk 双向父子） ---------- */

/** chunk 分析状态：未分析 →（知识提取）→ 启用 ⇄ 禁用 */
export type ChunkStatus = 'unanalyzed' | 'enabled' | 'disabled'

/** 文档行；analyzedCount = 已分析块数（全部块已分析 ⇒ 文档状态「已分析」）；contextualizing = 全文概述（步骤 2 上下文补完用）；docType = LLM 判定的文档类型 */
export type DocItem = {
  id: number
  title: string
  createdAt: number
  chunkCount: number
  analyzedCount: number
  contextualizing: string | null
  docType: DocType | null
}

/** 文档类型（摘要生成时 LLM 判定）：技术手册/规范 | 主观叙述/评论 | 对话/聊天记录 */
export type DocType = 'TECHNICAL' | 'NARRATIVE' | 'CONVERSATIONAL'

/** chunk 关联实体（chunk_entity 侧） */
export type ChunkEntityItem = { id: string; name: string; type: string }

/** chunk 产出的关系（relations.source_chunk 侧） */
export type ChunkRelationItem = {
  id: string
  predicate: string
  subject: string
  object: string
}

/** chunk 行：docId 回指父文档（父子关系的 chunk→doc 侧）；
 *  header/questions 为最终提交预案留档（生成时落库，二次进入自动回显） */
export type ChunkItem = {
  id: number
  docId: number
  title: string | null
  content: string
  createdAt: number
  status: ChunkStatus
  header: string | null
  questions: string | null
  entities: ChunkEntityItem[]
  relations: ChunkRelationItem[]
}

export type CreateDocResult = { id: number; title: string; chunks: number }

/** 文档列表（含子块统计） */
export function listDocs(): Promise<DocItem[]> {
  return requestJson<DocItem[]>('/api/analyze/documents')
}

/** 新建文档：分块入库，块状态初始为「未分析」 */
export function createDoc(title: string, content: string): Promise<CreateDocResult> {
  return requestJson<CreateDocResult>('/api/analyze/documents', {
    method: 'POST',
    body: JSON.stringify({ title, content }),
  })
}

/** 触发文档（重新）分块：清旧块后按当前规则重切，返回新块数 */
export function rechunkDoc(docId: number): Promise<CreateDocResult> {
  return requestJson<CreateDocResult>(`/api/analyze/documents/${docId}/rechunk`, { method: 'POST' })
}

export type DocSummaryResult = {
  contextualizing: string
  /** LLM 判定的文档类型；无法判定时为 null */
  docType: DocType | null
  /** true=本次由 LLM 新生成；false=读取已有概述 */
  generated: boolean
}

/** 全文概述：已有直接返回；否则 LLM 生成 100~200 字概述并落库（documents.contextualizing） */
export function summarizeDoc(docId: number): Promise<DocSummaryResult> {
  return requestJson<DocSummaryResult>(`/api/analyze/documents/${docId}/summary`, {
    method: 'POST',
  })
}

/** 文档下的全部 chunk（含关联实体/关系） */
export function listDocChunks(docId: number): Promise<ChunkItem[]> {
  return requestJson<ChunkItem[]>(`/api/analyze/documents/${docId}/chunks`)
}

/** 切换 chunk 状态，返回更新后的 chunk */
export function setChunkStatus(chunkId: number, status: ChunkStatus): Promise<ChunkItem> {
  return requestJson<ChunkItem>(`/api/analyze/chunks/${chunkId}/status`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  })
}

/** 删除 chunk（连带其承载的关系与 FTS/向量/实体链接） */
export function deleteChunk(chunkId: number): Promise<void> {
  return requestJson<void>(`/api/analyze/chunks/${chunkId}`, { method: 'DELETE' })
}

/* ---------- 步骤2：知识提取（消歧评估 + 提交） ---------- */

/** 实体精简视图 */
export type EntityLite = { id: string; name: string; type: string }

/** 一端实体的消歧信息：exact 非空=同名实体已存在；candidates=模糊命中（LIKE name/aliases，供改名参考） */
export type EntityLookup = {
  name: string
  exact: EntityLite | null
  candidates: EntityLite[]
}

/** 两实体间已有的关系（无向：任一方向均列出） */
export type EdgeLite = {
  id: string
  predicate: string
  subjectId: string
  objectId: string
  subjectName: string
  objectName: string
}

/** 单条三元组的消歧评估结果 */
export type TripleEval = {
  subject: EntityLookup
  object: EntityLookup
  edges: EdgeLite[]
}

/** 三元组消歧评估：存在性/候选/两实体间已有关系 */
export function disambiguateTriples(triples: Triple[]): Promise<TripleEval[]> {
  return requestJson<{ triples: TripleEval[] }>('/api/analyze/disambiguate', {
    method: 'POST',
    body: JSON.stringify({ triples }),
  }).then((r) => r.triples)
}

/** 提交结果：nodes/edges=新写入数，duplicates=已存在被忽略的关系数，vectorized=实体是否向量化 */
export type ChunkCommitResult = {
  nodes: number
  edges: number
  duplicates: number
  /** 合并写入的实体属性条数 */
  props: number
  vectorized: boolean
}

/** 提交勾选的三元组：写入图并关联 chunk（chunk_entity + source_chunk），chunk 置已分析 */
export function commitChunkTriples(
  chunkId: number,
  triples: Triple[],
): Promise<ChunkCommitResult> {
  return requestJson<ChunkCommitResult>(`/api/analyze/chunks/${chunkId}/commit`, {
    method: 'POST',
    body: JSON.stringify({ triples }),
  })
}

/** chunk 最近一次知识提取提交的记录：createdAt=提交时间，triples=当时通过该 chunk 写入图的三元组清单 */
export type ChunkExtractionRecord = {
  chunkId: number
  createdAt: number
  triples: Triple[]
}

/** 读取 chunk 的知识提取记录（再次进入步骤 2 时回显）；尚无记录（404）时返回 null */
export function getChunkExtraction(chunkId: number): Promise<ChunkExtractionRecord | null> {
  return requestJson<ChunkExtractionRecord>(`/api/analyze/chunks/${chunkId}/extraction`).catch(
    (e: unknown) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e)),
  )
}

/* ---------- 步骤3：最终提交预案（假设性问题生成 + 分段留档 + 提交） ---------- */

/** 假设性问题生成结果（步骤 3 预案编辑，只读不落库）：questions = 该段落能解答的问题清单 */
export type ChunkQuestionsResult = { chunkId: number; questions: string[] }

/** 生成假设性问题：LLM 基于父文档标题 + 概述 + chunk 原文生成 5 个该段落能解答的问题 */
export function genChunkQuestions(chunkId: number): Promise<ChunkQuestionsResult> {
  return requestJson<ChunkQuestionsResult>(`/api/analyze/chunks/${chunkId}/questions`, {
    method: 'POST',
  })
}

/** Chunk 段落摘要生成结果（步骤 3 Chunk Header 语义定位用，只读不落库）：summary = 该段落一句话摘要 */
export type ChunkSummaryResult = { chunkId: number; summary: string }

/** 生成 chunk 段落摘要：LLM 基于父文档标题 + 概述 + chunk 原文生成一句话摘要（Header 定位用） */
export function genChunkSummary(chunkId: number): Promise<ChunkSummaryResult> {
  return requestJson<ChunkSummaryResult>(`/api/analyze/chunks/${chunkId}/summary`, {
    method: 'POST',
  })
}

/** 保存最终提交预案留档（Chunk Header / 假设性问题生成后落库，二次进入自动回显）；
 *  header/questions：缺省 = 不更新该字段，空白 = 清空 */
export function saveChunkPlan(
  chunkId: number,
  plan: { header?: string; questions?: string },
): Promise<{ success: boolean }> {
  return requestJson<{ success: boolean }>(`/api/analyze/chunks/${chunkId}/plan`, {
    method: 'PUT',
    body: JSON.stringify(plan),
  })
}

/** 提交 Chunk 结果：vectorized = 预案全文是否已向量化写入 chunk_embedding 及规格 */
export type ChunkSubmitResult = { vectorized: boolean; model: string | null; dim: number }

/** 提交 Chunk（步骤 3 收尾）：预案全文向量化写 chunk_embedding + 分段留档 + chunk 启用，结束知识入库流程 */
export function submitChunkPlan(
  chunkId: number,
  body: { text: string; header?: string | null; questions?: string | null },
): Promise<ChunkSubmitResult> {
  return requestJson<ChunkSubmitResult>(`/api/analyze/chunks/${chunkId}/submit`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/* ---------- 文档（博客式文章：markdown 存 data/article，索引在 documents 表） ---------- */

export type ArticleItem = {
  url: string
  title: string
  summary: string | null
  createdAt: number
}

export type ArticlePage = {
  total: number
  page: number
  size: number
  items: ArticleItem[]
}

export type ArticleDetail = ArticleItem & { markdown: string }

/** 分页列表（page 从 1 起） */
export function articles(page: number, size: number): Promise<ArticlePage> {
  return requestJson<ArticlePage>(`/api/articles?page=${page}&size=${size}`)
}

/** 文章详情：含 markdown 原文 */
export function articleDetail(url: string): Promise<ArticleDetail> {
  return requestJson<ArticleDetail>(`/api/articles/${encodeURIComponent(url)}`)
}

/* ---------- 设置页（多模型 / 多人格 / 默认项 / Embedding） ---------- */

/** 模型项：builtin=true 为 .env 内置项（id="env"），不可编辑/删除；
 *  contextWindow=上下文窗口（token 数），留空保存时后端会尝试从模型 API 获取 */
export type ModelView = {
  id: string
  name: string
  baseUrl: string
  apiKey: string
  model: string
  contextWindow: number | null
  builtin: boolean
  maxOutputTokens?: number | null
}

export type ModelRequest = {
  name: string
  baseUrl: string
  apiKey: string
  model: string
  contextWindow: number | null
  maxOutputTokens?: number | null
}

/** 编辑模型时按端点试取上下文窗口的结果；contextWindow=null 表示接口未返回 */
export type ContextWindowView = { contextWindow: number | null }

/** Embedding 配置：mode=llm（复用某模型端点的 base-url/api-key）| custom（独立端点） */
export type EmbeddingView = {
  mode: 'llm' | 'custom'
  llmModelId: string | null
  baseUrl: string | null
  apiKey: string | null
  model: string | null
}

export type EmbeddingRequest = {
  mode: 'llm' | 'custom'
  llmModelId: string | null
  baseUrl: string | null
  apiKey: string | null
  model: string
}

/** Rerank 配置：mode=llm（复用某模型端点）| custom（独立端点）| off（关闭精排） */
export type RerankView = {
  mode: 'llm' | 'custom' | 'off'
  llmModelId: string | null
  baseUrl: string | null
  apiKey: string | null
  model: string | null
}

export type RerankRequest = {
  mode: 'llm' | 'custom' | 'off'
  llmModelId: string | null
  baseUrl: string | null
  apiKey: string | null
  model: string | null
}

/** 提示词位绑定/会话选择里表示「用内置」的保留 id */
export const PROMPT_BUILTIN = 'builtin'

/** 一份用户保存的提示词版本 */
export type PromptVersion = { id: string; name: string; content: string; updatedAt: number }

/** 场景上下文：可拖拽排序的提示词位（parts，顺序即实际拼装顺序）+ 代码控制的运行时素材（runtime，恒在其后） */
export type PromptSlot = {
  key: string
  name: string
  description: string
  builtin: string
  builtinSource: 'env' | 'code'
  activeVersionId: string | null
  versions: PromptVersion[]
}

/** 代码控制的运行时素材块（只读、不可拖拽）：说明执行时这里会被填入什么 */
export type PromptRuntime = { key: string; name: string; description: string }

export type PromptContext = {
  key: string
  name: string
  description: string
  parts: PromptSlot[]
  runtime: PromptRuntime[]
}

/** 大类：提示词页的一级标签，下挂若干上下文（对话 / 开发者选项） */
export type PromptCategory = {
  key: string
  name: string
  description: string
  contexts: PromptContext[]
}

export type PromptsView = { categories: PromptCategory[] }

export type SettingsView = {
  models: ModelView[]
  embedding: EmbeddingView
  rerank: RerankView
  defaultModelId: string
}

export function getSettings(): Promise<SettingsView> {
  return requestJson<SettingsView>('/api/settings')
}

/** 提示词页全量：场景 → 提示词位 → 内置正文 + 用户版本 + 生效项 */
export function getPrompts(): Promise<PromptsView> {
  return requestJson<PromptsView>('/api/prompts')
}

export function createModel(req: ModelRequest): Promise<SettingsView> {
  return requestJson<SettingsView>('/api/settings/models', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function updateModel(id: string, req: ModelRequest): Promise<SettingsView> {
  return requestJson<SettingsView>(`/api/settings/models/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function deleteModel(id: string): Promise<SettingsView> {
  return requestJson<SettingsView>(`/api/settings/models/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

/** 编辑模型时从模型 API 试取上下文窗口（token 数），取不到返回 { contextWindow: null } */
export function fetchModelContextWindow(req: {
  baseUrl: string
  apiKey: string
  model: string
}): Promise<ContextWindowView> {
  return requestJson<ContextWindowView>('/api/settings/models/context-window', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/** 模型连通性测试结果：ok=基准对话是否正常返回；error=失败原因 */
export type ChatTestResult = {
  ok: boolean
  elapsedMs: number
  reply: string | null
  error: string | null
}

/** 基于一条基准对话（Chat 同款 /chat/completions）测试模型接口能否正常返回 */
export function testModelChat(id: string): Promise<ChatTestResult> {
  return requestJson<ChatTestResult>(
    `/api/settings/models/${encodeURIComponent(id)}/test`,
    { method: 'POST' },
    TEST_TIMEOUT_MS,
  )
}

/** Embedding / Rerank 连通性测试结果：ok=探测调用是否正常返回；detail=成功摘要；error=失败原因 */
export type TestResult = {
  ok: boolean
  elapsedMs: number
  detail: string | null
  error: string | null
}

/** 按设置页表单里的草稿端点向量化一条探针文本，测试 Embedding 接口能否正常返回（不落库） */
export function testEmbedding(req: EmbeddingRequest): Promise<TestResult> {
  return requestJson<TestResult>(
    '/api/settings/embedding/test',
    { method: 'POST', body: JSON.stringify(req) },
    TEST_TIMEOUT_MS,
  )
}

/** 按设置页表单里的草稿端点对探针文档打分，测试 Rerank 接口能否正常返回（不落库） */
export function testRerank(req: RerankRequest): Promise<TestResult> {
  return requestJson<TestResult>(
    '/api/settings/rerank/test',
    { method: 'POST', body: JSON.stringify(req) },
    TEST_TIMEOUT_MS,
  )
}

export function createPromptVersion(
  slot: string,
  req: { name: string; content: string },
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/prompts/slots/${encodeURIComponent(slot)}/versions`,
    { method: 'POST', body: JSON.stringify(req) },
  )
}

export function updatePromptVersion(
  id: string,
  req: { name: string; content: string },
): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/prompts/versions/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function deletePromptVersion(id: string): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/prompts/versions/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

/** 选定某提示词位生效的版本；versionId 为 null/PROMPT_BUILTIN = 用内置 */
export function bindPromptSlot(slot: string, versionId: string | null): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/prompts/slots/${encodeURIComponent(slot)}/binding`, {
    method: 'PUT',
    body: JSON.stringify({ versionId }),
  })
}

/**
 * 保存某提示词位「当前生效的正文」：已绑定版本则改那份版本；
 * 仍是内置则后端自动分叉出一份新版本并绑定（内置原文不受影响）。
 */
export function savePromptSlotContent(slot: string, content: string): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/prompts/slots/${encodeURIComponent(slot)}/content`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

/** 覆盖某上下文内提示词位的拼装顺序（拖拽调整位置），slots 为按新顺序排列的 key */
export function savePromptContextOrder(
  context: string,
  slots: string[],
): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/prompts/contexts/${encodeURIComponent(context)}/order`, {
    method: 'PUT',
    body: JSON.stringify({ slots }),
  })
}

/** 默认模型：modelId 为 null/空 = 回退 .env 内置项 */
export function saveDefaults(req: { modelId: string | null }): Promise<SettingsView> {
  return requestJson<SettingsView>('/api/settings/defaults', {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function saveEmbedding(req: EmbeddingRequest): Promise<SettingsView> {
  return requestJson<SettingsView>('/api/settings/embedding', {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function saveRerank(req: RerankRequest): Promise<SettingsView> {
  return requestJson<SettingsView>('/api/settings/rerank', {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

/**
 * 重置知识库的结果：删除的 chunk / 实体 / 关系 / 记忆条数。
 *   memories     = 记忆总数（时序事件 + 记忆条目）
 *   memoryEvents = 其中时序事件；memoryItems = 其中记忆条目
 *   candidates / shares = 一并清除的画像候选 / 共享记录
 */
export type ResetResult = {
  chunks: number
  entities: number
  relations: number
  memories: number
  memoryEvents: number
  memoryItems: number
  candidates: number
  shares: number
  episodes: number
  ingestJobs: number
  checkpoints: number
  recallTraces: number
  deletedProjectDocuments: number
  retainedProjectDocuments: number
}

/** 重置知识库（危险操作）：清空全部 chunk、图谱实体与关系，并连带清除整个记忆子系统及相关 embedding，不可恢复 */
export function resetKnowledgeBase(deleteProjectKnowledge = false): Promise<ResetResult> {
  return requestJson<ResetResult>('/api/settings/knowledge-base/reset', { method: 'POST', body: JSON.stringify({deleteProjectKnowledge}) })
}

/* ---------- 命令行终端（让模型直接控制目标环境） ---------- */

/** 一个终端目标：本机 docker 的一个容器，或一台远程服务器（SSH） */
export type TerminalTarget = {
  id: string
  name: string
  kind: 'docker' | 'ssh'
  enabled: boolean
  workdir: string | null
  timeoutMs: number | null
  maxOutput: number | null
  /** 命令白名单（正则，非空时命令须命中其一）；空数组 = 不限 */
  allowPatterns: string[]
  /** 命令黑名单（正则，命中即拒，先于白名单判定） */
  denyPatterns: string[]
  /* docker */
  dockerHost: string | null
  hostWorkdir: string | null
  image: string | null
  container: string | null
  /* ssh */
  sshHost: string | null
  sshPort: number | null
  sshUser: string | null
  sshAuth: 'password' | 'key'
  sshPassword: string | null
  sshPrivateKey: string | null
  sshPassphrase: string | null
  hostKeyCheck: 'known_hosts' | 'accept-all'
  /** 给模型看的一句话说明（什么时候该用它） */
  note: string | null
}

export type TerminalTargetRequest = Omit<TerminalTarget, 'id'>

/** 工作空间页整页视图：全部目标（没有全局总开关，逐目标开关即唯一闸门） */
export type TerminalView = {
  targets: TerminalTarget[]
}

export type TerminalProbeResult = {
  ok: boolean
  elapsedMs: number
  detail: string | null
  error: string | null
}

/** 一次命令执行的审计（页面的「最近执行」） */
export type TerminalCommand = {
  id: number
  targetName: string
  command: string
  workdir: string | null
  exitCode: number | null
  durationMs: number
  stdout: string | null
  stderr: string | null
  truncated: boolean
  timedOut: boolean
  ok: boolean
  source: string
  createdAt: number
}

export function getTerminal(): Promise<TerminalView> {
  return requestJson<TerminalView>('/api/terminal')
}

export function createTerminalTarget(req: TerminalTargetRequest): Promise<TerminalView> {
  return requestJson<TerminalView>('/api/terminal/targets', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function updateTerminalTarget(
  id: string,
  req: TerminalTargetRequest,
): Promise<TerminalView> {
  return requestJson<TerminalView>(`/api/terminal/targets/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function deleteTerminalTarget(id: string): Promise<TerminalView> {
  return requestJson<TerminalView>(`/api/terminal/targets/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

/** 连通性测试：连得上、能执行即通过（不打洞、不起容器） */
export function probeTerminalTarget(id: string): Promise<TerminalProbeResult> {
  return requestJson<TerminalProbeResult>(
    `/api/terminal/targets/${encodeURIComponent(id)}/probe`,
    { method: 'POST' },
  )
}

/** 人工试跑一条命令：与模型走同一条执行路径（同样上护栏、同样记审计） */
export function runTerminalCommand(id: string, command: string): Promise<TerminalCommand> {
  return requestJson<TerminalCommand>(`/api/terminal/targets/${encodeURIComponent(id)}/run`, {
    method: 'POST',
    body: JSON.stringify({ command }),
  })
}

export function listTerminalCommands(limit = 20): Promise<TerminalCommand[]> {
  return requestJson<TerminalCommand[]>(`/api/terminal/commands?limit=${limit}`)
}

/* ---------- 工作空间（基于命令行终端的扩展：文件浏览 / 预览 / 下载） ---------- */

/** 目录项：link=符号链接（本页按名字下钻，不解析其目标） */
export type WorkspaceFileEntry = {
  name: string
  dir: boolean
  size: number
  /** ls 原样的修改时间字符串（如 "Sep 23 10:00"） */
  modified: string
  link: boolean
}

/** 一次目录浏览；path 为相对工作空间起点目录的沙箱路径（"." = 起点自身） */
export type WorkspaceDirView = { path: string; entries: WorkspaceFileEntry[] }

/** 一次文本预览；binary=true 时 content 为 null（只给类型与大小，正文不渲染） */
export type WorkspaceFileView = {
  path: string
  name: string
  size: number
  truncated: boolean
  binary: boolean
  content: string | null
}

/** 列目录（path 相对起点目录） */
export function listWorkspaceFiles(id: string, path = '.'): Promise<WorkspaceDirView> {
  return requestJson<WorkspaceDirView>(
    `/api/workspaces/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path)}`,
  )
}

/** 预览文本文件（超上限截断并标注；二进制文件回类型与大小） */
export function readWorkspaceFile(id: string, path: string): Promise<WorkspaceFileView> {
  return requestJson<WorkspaceFileView>(
    `/api/workspaces/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`,
  )
}

/** 从 Content-Disposition 取建议文件名（优先 RFC 5987 的 filename*） */
function filenameFromDisposition(dispo: string, fallback: string): string {
  const star = /filename\*=UTF-8''([^;]+)/i.exec(dispo)
  if (star) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      /* 解码失败则退回普通 filename */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(dispo)
  return plain ? plain[1] : fallback
}

/** 下载文件：以附件字节流取回，交由调用方触发保存（二进制安全） */
export async function downloadWorkspaceFile(
  id: string,
  path: string,
): Promise<{ filename: string; blob: Blob }> {
  const res = await apiFetch(
    `/api/workspaces/${encodeURIComponent(id)}/download?path=${encodeURIComponent(path)}`,
    { credentials: 'same-origin' },
  )
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  const fallback = path.split('/').pop() ?? 'download'
  return {
    filename: filenameFromDisposition(res.headers.get('Content-Disposition') ?? '', fallback),
    blob: await res.blob(),
  }
}

/* ---------- 用量&统计 ---------- */
export type StatsMoney = { currency: string; amount: number }
export type StatsModel = { provider: string; model: string }
export type StatsBucket = StatsModel & {
  key: string; promptTokens: number; completionTokens: number; totalTokens: number
  calls: number; unpricedCalls: number; costs: StatsMoney[]
}
export type StatsTotals = {
  promptTokens: number; completionTokens: number; totalTokens: number; calls: number
  reportedCalls: number; estimatedCalls: number; partialCalls: number; unsuccessfulCalls: number
  cachedTokens: number; reasoningTokens: number; unpricedCalls: number; costs: StatsMoney[]
}
export type StatsPrice = StatsModel & {
  id: number; effectiveFrom: string; currency: string
  inputPerMillion: number; outputPerMillion: number; cachedPerMillion: number | null
}
export type StatsPriceRequest = StatsModel & {
  effectiveFrom: string; currency: string
  inputPerMillion: string; outputPerMillion: string; cachedPerMillion: string | null
}
export type StatsCall = StatsModel & {
  id: number; createdAt: number; source: string; promptTokens: number; completionTokens: number
  totalTokens: number; quality: 'reported' | 'estimated' | 'partial'; status: 'success' | 'interrupted' | 'failed'
  cachedTokens: number; reasoningTokens: number; currency: string | null; cost: number | null
}
export type StatsView = {
  total: StatsTotals; bySource: StatsBucket[]; byModel: StatsBucket[]; daily: StatsBucket[]
  days: number; from: string; to: string; timeZone: string; today: string; generatedAt: number
  sources: string[]; models: StatsModel[]; prices: StatsPrice[]; recentCalls: StatsCall[]; recordingFailures: number
}
export type StatsQuery = { days?: number; from?: string; to?: string; source?: string; provider?: string; model?: string }
/** days=-1 为本月；所有聚合采用响应返回的同一日期范围和时区。 */
export function getStats(query: StatsQuery | number = 30, signal?: AbortSignal): Promise<StatsView> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(typeof query === 'number' ? { days: query } : query)) {
    if (value !== undefined) params.set(key, String(value))
  }
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()
  const timer = window.setTimeout(() => { timedOut = true; controller.abort() }, 15000)
  return requestJson<StatsView>('/api/stats?' + params, { signal: controller.signal }).catch(error => {
    if (timedOut) throw new Error('统计读取超过 15 秒，请重试')
    throw error
  }).finally(() => { window.clearTimeout(timer); signal?.removeEventListener('abort', abort) })
}
export function saveStatsPrice(price: StatsPriceRequest): Promise<{ saved: boolean }> {
  return requestJson('/api/stats/prices', { method: 'PUT', body: JSON.stringify(price) }, 15000)
}
export function deleteStatsPrice(id: number): Promise<{ deleted: boolean }> {
  return requestJson('/api/stats/prices/' + id, { method: 'DELETE' }, 15000)
}

/* ---------- 后台（每 Agent 一份：心跳 / 常驻职责 / 子代理 / RAG 召回 / 任务）+ 全局系统 ---------- */

/** 工具词典的一条（全局工具一览用）：名字 + 描述 + 所属工具面 + 写库/子代理可调/可并行标记 + 参数 schema */
export type ToolInfoView = {
  name: string
  description: string
  /** 该工具出现在哪些工具面（CHAT / HEARTBEAT / THREAD / PEER / EXTRACT / COMMIT） */
  surfaces: string[]
  writes: boolean
  agentsMayCall: boolean
  parallelizable: boolean
  parameters: { properties?: Record<string, { type?: string; description?: string }>; required?: string[] }
}

/** 某位 Agent 的心跳配置：正文 + 模型 + 开关 + 节拍 + 最近一次敲响时刻 */
export type AgentHeartbeat = {
  agentId: string
  content: string
  modelId: string | null
  enabled: boolean
  tickMs: number
  lastBeatAt: number | null
}

/** 观测台里一位 Agent 的心跳状态 */
export type HeartbeatState = {
  agentId: string
  name: string
  enabled: boolean
  tickMs: number
  lastBeatAt: number | null
  running: boolean
}

/** 交付渠道：silent=只留痕（结果落库，供回溯）；thread=可自己开会话并往里投递说给人听的消息 */
export type SubChannel = 'silent' | 'thread'

export const SUB_CHANNELS: SubChannel[] = ['silent', 'thread']

export const SUB_CHANNEL_LABELS: Record<SubChannel, string> = {
  silent: '静默留痕',
  thread: '投递首页会话',
}

/** 常驻职责（一份常驻的岗位定义）：自带正文/角色/工具 + 交付渠道；
 *  由大模型经工具立/改/撤（create_heartbeat / update_heartbeat / delete_heartbeat）；
 *  页面只读，唯一的人工写入口是 enabled 这个刹车（停用不删，只挡自动派出）。 */
export type PlanView = {
  id: string
  agentId: string
  name: string
  role: string
  content: string
  tools: string[]
  channels: string[]
  /** 节拍参考：null=每拍都可以考虑派它（只是给主会话的参考，不是硬时刻表） */
  intervalMs: number | null
  input: string | null
  modelId: string | null
  sort: number
  lastBeatAt: number | null
  /** 立它时给的一句「为什么立」：页面展示，供人隔月回看名册时认由来 */
  rationale: string | null
  /** 人工刹车：false=已停用（心跳不派它、模型也看不见它；定义与留痕都保留） */
  enabled: boolean
}

/** 一批执行：一次心跳（beat）/ 页面点名（manual）/ 一次对话委托（chat） */
export type RunView = {
  id: string
  agentId: string
  beat: number
  trigger: string
  status: string
  agentCount: number
  note: string | null
  startedAt: number
  finishedAt: number | null
}

/** 写库清单项：一次执行里的某次写库工具调用 */
export type ExecEdit = {
  id: number
  tool: string
  args: string | null
  summary: string | null
  createdAt: number
}

/** 执行种类：心跳主会话登场 / 常驻职责被派出 / 一次匿名子代理调用 */
export type ExecKind = 'heartbeat' | 'plan' | 'agent'

/** 一次执行；context 仅详情接口返回（messages JSON，可能很大）。
 *  kind 决定 planId 与 title 的含义：heartbeat=主会话；plan=常驻职责被派出；agent=匿名子代理 */
export type ExecView = {
  id: number
  runId: string
  kind: ExecKind
  planId: string | null
  title: string | null
  status: string
  context: string | null
  output: string | null
  reasoning: string | null
  trace: TraceSegment[]
  edits: ExecEdit[]
  error: string | null
  startedAt: number
  finishedAt: number | null
  /** 归属任务 id（仅子代理列表返回；进程重启打断后可据此重跑），其余场景为 null */
  taskId: number | null
}

/** 重跑一条任务的结果：taskId=被重跑的原任务，execId=新一次执行的留痕 id */
export type TaskRunView = {
  taskId: number
  execId: number
}

/** 一次心跳/派活的结果：started=false 表示已有另一轮在跑（或目标不存在），本次被跳过 */
export type BeatView = {
  started: boolean
  runId: string | null
  beat: number
  trigger: string | null
  agents: number
}

/** 一条任务：kind 见后端 TaskStore（session/subagent/plan/code）；
 *  parentId=派它的那个任务（心跳主会话 → 计划 → 子代理），顶层为 null；
 *  execId=它落到的执行留痕（可点开看全过程）；仅真正的执行有值，
 *  前台会话任务没有执行上下文，为 null（该行不可点）。 */
export type TaskView = {
  id: number
  parentId: number | null
  kind: string
  status: string
  title: string | null
  sessionId: string | null
  agentId: string
  execId: number | null
  createdAt: number
  updatedAt: number
  codingRunId?: string | null
}

/** 一批执行 + 这一批里的各次执行 */
export type RunBundle = { run: RunView; execs: ExecView[] }

/** 观测台快照：全部 Agent 的心跳状态 + 最近几批（倒序，各带本批执行） */
export type PulseView = { heartbeats: HeartbeatState[]; recentRuns: RunBundle[] }

/* ---- 资源使用视图 ---- */

/** 窗口内某一类的用量；estTokens 由字符量粗估（量级参考） */
export type Usage = {
  runs: number
  done: number
  failed: number
  avgDurationMs: number
  maxDurationMs: number
  toolRounds: number
  estTokens: number
}

/** 窗口内某份常驻职责被派出的用量 */
export type PlanUsage = Usage & {
  planId: string
  /** 常驻职责名；已被撤掉的历史留痕为 null */
  name: string | null
  lastRunAt: number | null
}

/** 资源快照：名册规模 + 心跳状态 + 窗口内的总量（全部 / 子代理 / 职责 / 心跳四档）+ 逐份职责的用量 */
export type ResourceView = {
  enabled: boolean
  tickMs: number
  running: boolean
  windowHours: number
  rosterCount: number
  totals: Usage
  agents: Usage
  plans: Usage
  heartbeat: Usage
  planUsage: PlanUsage[]
  /** 自启动以来被预筛跳过的拍数（名册里没有到点的职责就没叫模型）= 省下的空转 */
  idleSkips: number
}

/* ---- 每 Agent 后台接口（base /api/agents/{agentId}） ---- */

/** 拼 per-agent 后台接口的 base 路径 */
function agentBase(id: string): string {
  return `/api/agents/${encodeURIComponent(id)}`
}

/** 某位 Agent 的心跳配置（正文/模型/开关/节拍/最近一次） */
export function agentHeartbeat(id: string): Promise<AgentHeartbeat> {
  return requestJson<AgentHeartbeat>(`${agentBase(id)}/heartbeat`)
}

/** 保存某位 Agent 的心跳配置（只传需要改的字段） */
export function saveAgentHeartbeat(
  id: string,
  req: { content?: string; modelId?: string | null; enabled?: boolean; tickMs?: number },
): Promise<AgentHeartbeat> {
  return requestJson<AgentHeartbeat>(`${agentBase(id)}/heartbeat`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

/** 手动敲一次心跳：让这位 Agent 的心跳主会话立刻出场，由它决定这一拍派谁 */
export function agentBeat(id: string): Promise<BeatView> {
  return requestJson<BeatView>(`${agentBase(id)}/heartbeat/beat`, { method: 'POST' })
}

/** 名册：这位 Agent 的全部常驻职责 */
export function agentPlans(id: string): Promise<PlanView[]> {
  return requestJson<PlanView[]>(`${agentBase(id)}/plans`)
}

/** 启用 / 停用一份常驻职责（停用不删，只挡自动派出） */
export function agentSetPlanEnabled(
  id: string,
  planId: string,
  enabled: boolean,
): Promise<PlanView> {
  return requestJson<PlanView>(`${agentBase(id)}/plans/${encodeURIComponent(planId)}/enabled`, {
    method: 'PUT',
    body: JSON.stringify({ enabled }),
  })
}

/** 人工把某份常驻职责单跑一次（「立即执行」）：绕过主会话，独占一批 */
export function agentRunPlan(id: string, planId: string): Promise<BeatView> {
  return requestJson<BeatView>(`${agentBase(id)}/plans/${encodeURIComponent(planId)}/run`, {
    method: 'POST',
  })
}

/** 某份常驻职责最近若干次被派出的记录 */
export function agentPlanExecs(id: string, planId: string, limit = 20): Promise<ExecView[]> {
  return requestJson<ExecView[]>(
    `${agentBase(id)}/plans/${encodeURIComponent(planId)}/execs?limit=${limit}`,
  )
}

/** 最近若干批执行（倒序） */
export function agentRuns(id: string, limit = 30): Promise<RunView[]> {
  return requestJson<RunView[]>(`${agentBase(id)}/runs?limit=${limit}`)
}

/** 某批里的各次执行 */
export function agentRunExecs(id: string, runId: string): Promise<ExecView[]> {
  return requestJson<ExecView[]>(`${agentBase(id)}/runs/${encodeURIComponent(runId)}/execs`)
}

/** 某一次执行的完整过程：组装出的上下文、思维链、过程分段、写库清单 */
export function agentExec(id: string, execId: number): Promise<ExecView> {
  return requestJson<ExecView>(`${agentBase(id)}/execs/${execId}`)
}

/** 最近若干条任务（倒序） */
export function agentTasks(id: string, limit = 100): Promise<TaskView[]> {
  return requestJson<TaskView[]>(`${agentBase(id)}/tasks?limit=${limit}`)
}

/** 重跑一条任务（子代理列表里「重跑」按钮调用）：按任务留下的输入重新派一次 */
export function agentRerunTask(id: string, taskId: number): Promise<TaskRunView> {
  return requestJson<TaskRunView>(`${agentBase(id)}/tasks/${taskId}/run`, { method: 'POST' })
}

/* ---- 子代理（匿名 worker：不建档案，只有跑过的记录） ---- */

/** 最近若干次子代理调用 */
export function agentSubagentRuns(id: string, limit = 30): Promise<ExecView[]> {
  return requestJson<ExecView[]>(`${agentBase(id)}/subagents?limit=${limit}`)
}

/** 最近若干条三路召回留痕（倒序）；channel 缺省表示全部 */
export function agentRagRecalls(
  id: string,
  limit = 100,
  channel?: SubRagChannel | null,
): Promise<RagRecallView[]> {
  const q = new URLSearchParams({ limit: String(limit) })
  if (channel) q.set('channel', channel)
  return requestJson<RagRecallView[]>(`${agentBase(id)}/rag-recalls?${q.toString()}`)
}

/** 清空召回留痕，返回删除条数 */
export function agentClearRagRecalls(id: string): Promise<{ removed: number }> {
  return requestJson<{ removed: number }>(`${agentBase(id)}/rag-recalls`, { method: 'DELETE' })
}

/** 资源使用视图：名册规模 + 窗口内的总量（全部/子代理/职责/心跳）+ 逐份职责的用量 */
export function agentResources(id: string, hours = 24): Promise<ResourceView> {
  return requestJson<ResourceView>(`${agentBase(id)}/resources?hours=${hours}`)
}

/** 观测台快照：这位 Agent 的心跳状态 + 最近几批执行 */
export function agentPulse(id: string): Promise<PulseView> {
  return requestJson<PulseView>(`${agentBase(id)}/pulse`)
}

/**
 * 这位 Agent 的观测台实时流：后端只在快照有变化时推 pulse（整份），无变化时发注释帧保活；
 * 单条流有最长保持时长，故调用方应在结束后重连。
 */
export function agentStream(
  id: string,
  onPulse: (pulse: PulseView) => void,
  signal?: AbortSignal,
): Promise<void> {
  return consumePulseStream(`${agentBase(id)}/stream`, onPulse, signal)
}

/* ---- 全局系统接口（base /api/system）：工具一览 + 全 Agent 观测台 ---- */

/** 工具词典：全部工具的说明（只读，进「工具」子页时拉一次） */
export function systemTools(): Promise<ToolInfoView[]> {
  return requestJson<ToolInfoView[]>('/api/system/tools')
}

/** 观测台快照：全部启用心跳的 Agent + 跨 Agent 的最近几批执行 */
export function systemPulse(): Promise<PulseView> {
  return requestJson<PulseView>('/api/system/dashboard/pulse')
}

/** 全局观测台实时流（跨 Agent）：事件名 pulse，整份快照随事件到达 */
export function systemStream(
  onPulse: (pulse: PulseView) => void,
  signal?: AbortSignal,
): Promise<void> {
  return consumePulseStream('/api/system/dashboard/stream', onPulse, signal)
}

/* ---- RAG 召回留痕（三路召回：工具调用 + 记忆自动注入） ---- */

/** 三路召回：vector=向量 / graph=图 / memory=记忆（含 recall_memory 工具与每轮自动注入） */
export type SubRagChannel = 'vector' | 'graph' | 'memory' | 'web'

export const SUB_RAG_CHANNELS: SubRagChannel[] = ['vector', 'graph', 'memory', 'web']

export const SUB_RAG_CHANNEL_LABELS: Record<SubRagChannel, string> = {
  vector: '向量',
  graph: '图',
  memory: '记忆',
  web: '网页',
}

/** 召回来源：这条检索发生在哪条流（对话 / 智能体群聊 / 心跳 / 职责 / 子代理） */
export const SUB_RAG_SOURCE_LABELS: Record<string, string> = {
  chat: '对话',
  multiagent: '智能体群聊',
  heartbeat: '心跳主会话',
  plan: '常驻职责',
  agent: '子代理',
  unknown: '未知',
}

/** 一条 RAG 召回留痕：query=调用参数原文，result=回给模型的 tool 消息原文（大模型实际拿到的原始数据） */
export type RagRecallView = {
  id: number
  channel: SubRagChannel
  tool: string
  source: string
  agentId: string
  query: string | null
  result: string | null
  /** result 截断前的原始字符数 */
  resultChars: number
  createdAt: number
}

/**
 * 观测台实时流（fetch + ReadableStream 手工解析 SSE，事件名 pulse）：后端只在「有变化」时推整份快照，
 * 无变化时发注释帧保活；单条流有最长保持时长，故调用方应在结束后重连。
 * per-agent 的 agentStream 与全局的 systemStream 都复用这一份解析。
 */
async function consumePulseStream(
  path: string,
  onPulse: (pulse: PulseView) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const res = await apiFetch(path, {
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  if (!res.body) throw new ApiError(res.status, '响应无流式内容')

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (block: string) => {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (name === 'pulse' && data.length > 0) {
      onPulse(JSON.parse(data.join('\n')) as PulseView)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let m: RegExpExecArray | null
      while ((m = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, m.index)
        buffer = buffer.slice(m.index + m[0].length)
        dispatch(block)
      }
    }
  } catch (e) {
    await reader.cancel().catch(() => {})
    throw e
  } finally {
    reader.releaseLock()
  }
}

/* ---------- 多智能体（圆桌：一群具名成员在同一话题里轮流发言） ---------- */

/** 成员：一个具名、常驻的发言者。每位成员自己那套「对话」提示词见下方 *MaPeerPrompt* 接口，
 *  与首页对话的全局提示词隔离 */
export type MaPeer = {
  id: string
  executionKind?: 'CHAT' | 'CODING'
  name: string
  /** 内置 Agent：不可删除、默认存在 */
  builtin: boolean
  /** 额外允许调用的工具名；空数组 = 默认只读检索（外加状态机三件） */
  tools: string[]
  /** 绑定的工作空间（term_target.id）；非空才授予 run_command / list_terminals，且只能访问这几台 */
  terminals: string[]
  modelId: string | null
  sort: number
  note: string | null
  /** 展示用岗位（如「产品经理」），智能体页与执行态共用。 */
  role: string | null
  /** 角色标签的颜色（#RRGGBB）；null=用默认灰 */
  roleColor: string | null
  /** 头像：data:image/...;base64,... 的小图（前端压到 256px 后存库，不落磁盘）；null=用首字母兜底 */
  avatar: string | null
}

/** 新建 / 改写成员的请求体（改时只传要动的字段；avatar 传空串 = 移除头像） */
export type MaPeerRequest = {
  name?: string | null
  tools?: string[] | null
  terminals?: string[] | null
  modelId?: string | null
  sort?: number | null
  note?: string | null
  role?: string | null
  roleColor?: string | null
  avatar?: string | null
}

/** 智能体的工具候选；主 Agent 包含全部对话工具，普通成员的终端工具由工作空间绑定授予。 */
export type MaToolOption = {
  name: string
  description: string
  /** 会写知识库（文档 / 图谱）的工具，页面上给出提醒 */
  writes: boolean
  /** 无需勾选就始终可用；主 Agent 全部为 true，普通成员仅默认的只读工具为 true。 */
  defaultGranted: boolean
  /** 业务领域 key（doc / graph / memory / general），页面按它分 Tab */
  domain: string
  /** 业务领域展示名（文档 / 图谱 / 记忆 / 其他） */
  domainLabel: string
}

/** 图谱人格锚点：这位成员在知识图谱里的第一人称坐标——锚点实体（types 含 ANCHOR）+ 它的一跳子图；
 *  未绑定、或锚点实体已不在图中时 anchor=null、graph 为空 */
export type MaPeerAnchor = {
  peerId: string
  anchorId: string | null
  anchor: GraphNode | null
  graph: SearchGraph
}

/** 多智能体会话：与首页对话完全隔离的一套会话（不带当前话题时为 null） */
export type MaSession = {
  id: string
  projectId: string | null
  title: string | null
  modelId: string | null
  topicId: string | null
  topicStatus: string | null
  floorPeer: string | null
  /** 这张圆桌的成员 id（后端已解好「建会话时没选人 = 全体名册」） */
  memberIds: string[]
  createdAt: number
  updatedAt: number | null
}

/** 一条消息：role=user 是人的话，role=peer 是某位成员的发言，role=event 是状态机的一条动作 */
export type MaMessage = {
  id: number
  sessionId: string
  topicId: string | null
  role: 'user' | 'peer' | 'event'
  peerId: string | null
  peerName: string | null
  content: string
  reasoning: string | null
  trace: TraceSegment[] | null
  createdAt: string | null
  /**
   * 事件种类（仅 role=event 有）：joined / silent / propose_end / turn_end / topic_open …
   *
   * <p>它是「这一行是什么」的<b>协议</b>——界面按它分类与渲染，不再去匹配中文文案
   * （后端改一个字，匹配就会静默错位）。老数据没有这一列，读侧按文案兜底。
   */
  kind?: string | null
}

/** 一次话题：一段有始有终的圆桌讨论（status: open 进行中 / closing 正在向沉默者确认 / closed 已收束） */
export type MaTopic = {
  id: string
  sessionId: string
  status: string
  /** 当前持有发言权的成员 */
  floorPeer: string | null
  /** 已重开次数（沉默者发言 → 全员重新决策，每转一圈 +1） */
  resolveRound: number
  /** 成员名 → 状态名（INACTIVE / ACTIVE / SPEAKING / SILENT / PROMPTED / PROPOSE_END） */
  states: Record<string, string>
  /** 成员名 → 发言状态（SPEAKING / LISTENING / SILENT）：与 executions 构成二维状态（设计一 §四） */
  communications?: Record<string, string>
  /** 成员名 → 执行状态（READY / RUNNING / WAITING_EVENT / BLOCKED / COMPLETED / CANCELLED） */
  executions?: Record<string, string>
  participants: string[]
  createdAt: number
  closedAt: number | null
}

/** 一轮「人说话 + 成员轮流应答」的结果摘要（正文由流式增量给出） */
export type MaTurnResult = {
  sessionId: string
  topicId: string
  topicStatus: string
  speeches: number
}

/** 成员状态的中文标签（与后端 PeerState 一一对应） */
export const MA_STATE_LABELS: Record<string, string> = {
  INACTIVE: '非活跃',
  ACTIVE: '活跃',
  SPEAKING: '发言中',
  SILENT: '沉默',
  PROMPTED: '待表态',
  AWAIT_USER: '等待用户',
  PROPOSE_END: '提议结束',
}

/**
 * 成员的执行状态标签（二维状态的第二维，设计一 §四）：与 MA_STATE_LABELS 一起把
 * 「向不向讨论室发言」×「工作走到哪」分开表达——于是「不说话但在跑」这类场面看得见。
 */
export const MA_EXEC_LABELS: Record<string, string> = {
  READY: '就绪',
  ADVANCED: '待推进',
  RUNNING: '执行中',
  WAITING_EVENT: '等待事件',
  BLOCKED: '被阻塞',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

/**
 * 圆桌的应答者策略（与后端 SpeakerStrategy 的 key 一一对应）：这一轮该谁说话。
 * 轮询是缺省——按名册顺序依次交接；随机每次掷骰子；发言欲望优先让「更想说的人」先开口。
 */
export const MA_SPEAKER_STRATEGIES: { value: string; label: string }[] = [
  { value: 'rotation', label: '轮询' },
  { value: 'random', label: '随机' },
  { value: 'desire', label: '发言欲望优先' },
]

export function listMaPeers(): Promise<MaPeer[]> {
  return requestJson<MaPeer[]>('/api/agents/peers')
}

/** 可授予智能体的工具清单（成员页「可用工具」的候选） */
export function listMaTools(peerId?: string): Promise<MaToolOption[]> {
  return requestJson<MaToolOption[]>('/api/agents/tools' + (peerId ? `?peerId=${encodeURIComponent(peerId)}` : ''))
}

export function createMaPeer(req: MaPeerRequest): Promise<MaPeer> {
  return requestJson<MaPeer>('/api/agents/peers', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function updateMaPeer(id: string, req: MaPeerRequest): Promise<MaPeer> {
  return requestJson<MaPeer>(`/api/agents/peers/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function deleteMaPeer(id: string): Promise<{ id: string }> {
  return requestJson<{ id: string }>(`/api/agents/peers/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

/* ---------- 图谱人格锚点：成员与知识图谱锚点实体（types 含 ANCHOR）的绑定 ---------- */

/** 这位成员的锚点视图（未绑定时 anchor=null） */
export function getPeerAnchor(peerId: string): Promise<MaPeerAnchor> {
  return requestJson<MaPeerAnchor>(`/api/agents/peers/${encodeURIComponent(peerId)}/anchor`)
}

/** 新建并绑定：以成员名落一个锚点实体；已绑定时幂等返回当前视图 */
export function createPeerAnchor(peerId: string): Promise<MaPeerAnchor> {
  return requestJson<MaPeerAnchor>(`/api/agents/peers/${encodeURIComponent(peerId)}/anchor`, {
    method: 'POST',
  })
}

/** 解除绑定：只断开关联，锚点实体与其关系保留在图中 */
export function deletePeerAnchor(peerId: string): Promise<MaPeerAnchor> {
  return requestJson<MaPeerAnchor>(`/api/agents/peers/${encodeURIComponent(peerId)}/anchor`, {
    method: 'DELETE',
  })
}

/* ---------- 成员提示词：每个智能体独立一套「对话」提示词（结构与提示词页一致） ---------- */

/** 这位成员自己那套「对话」提示词的全量视图（大类只有「对话」，版本 / 生效项 / 顺序都是它自己的） */
export function getPeerPrompts(peerId: string): Promise<PromptsView> {
  return requestJson<PromptsView>(`/api/agents/peers/${encodeURIComponent(peerId)}/prompts`)
}

export function createPeerPromptVersion(
  peerId: string,
  slot: string,
  req: { name: string; content: string },
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/slots/${encodeURIComponent(slot)}/versions`,
    { method: 'POST', body: JSON.stringify(req) },
  )
}

export function updatePeerPromptVersion(
  peerId: string,
  id: string,
  req: { name: string; content: string },
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/versions/${encodeURIComponent(id)}`,
    { method: 'PUT', body: JSON.stringify(req) },
  )
}

export function deletePeerPromptVersion(peerId: string, id: string): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/versions/${encodeURIComponent(id)}`,
    { method: 'DELETE' },
  )
}

/** 选定这位成员某提示词位生效的版本；versionId 为 null/PROMPT_BUILTIN = 回退全局 */
export function bindPeerPromptSlot(
  peerId: string,
  slot: string,
  versionId: string | null,
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/slots/${encodeURIComponent(slot)}/binding`,
    { method: 'PUT', body: JSON.stringify({ versionId }) },
  )
}

/** 保存这位成员某提示词位「当前生效的正文」：已绑定版本则改它，否则自动分叉一份专属版本 */
export function savePeerPromptSlotContent(
  peerId: string,
  slot: string,
  content: string,
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/slots/${encodeURIComponent(slot)}/content`,
    { method: 'PUT', body: JSON.stringify({ content }) },
  )
}

export function savePeerPromptContextOrder(
  peerId: string,
  context: string,
  slots: string[],
): Promise<PromptsView> {
  return requestJson<PromptsView>(
    `/api/agents/peers/${encodeURIComponent(peerId)}/prompts/contexts/${encodeURIComponent(context)}/order`,
    { method: 'PUT', body: JSON.stringify({ slots }) },
  )
}

export function listMaSessions(): Promise<MaSession[]> {
  return requestJson<MaSession[]>('/api/agents/sessions')
}

/** 新建会话：memberIds 是勾选上桌的成员；空数组 = 全体名册 */
export function createMaSession(memberIds: string[] = [], projectId?: string): Promise<{ sessionId: string }> {
  return requestJson<{ sessionId: string }>('/api/agents/sessions', {
    method: 'POST',
    body: JSON.stringify({ memberIds, projectId }),
  })
}

export function deleteMaSession(id: string): Promise<{ sessionId: string }> {
  return requestJson<{ sessionId: string }>(
    `/api/agents/sessions/${encodeURIComponent(id)}`,
    { method: 'DELETE' },
  )
}

/** 会话的消息；带 topicId 时只看那一次话题 */
export function maMessages(id: string, topicId?: string): Promise<MaMessage[]> {
  const q = topicId ? `?topicId=${encodeURIComponent(topicId)}` : ''
  return requestJson<MaMessage[]>(`/api/agents/sessions/${encodeURIComponent(id)}/messages${q}`, undefined, 15000)
}

export function maTopics(id: string): Promise<MaTopic[]> {
  return requestJson<MaTopic[]>(`/api/agents/sessions/${encodeURIComponent(id)}/topics`, undefined, 15000)
}

/** 一个智能体此刻的执行态（讨论态 × 执行态 + 名下任务计数） */
export type MaWorkflowAgent = {
  id: string
  name: string
  /** 发言状态：SPEAKING / LISTENING / SILENT；全局视角（不挂会话）下无意义，为 null */
  communication: string | null
  /** 执行状态：READY / RUNNING / WAITING_EVENT / BLOCKED / COMPLETED / CANCELLED */
  execution: string
  /** 此刻是否有执行线程（worker）在跑 */
  working: boolean
  pending: number
  doing: number
  done: number
  /** 角色标签（执行态卡片右上角）：文案 + 颜色（#RRGGBB）；未设置时为 null */
  role: string | null
  roleColor: string | null
}

/** 共享任务区里的一条 */
export type MaWorkflowTodo = {
  id: string
  title: string
  detail: string | null
  /** pending | doing | blocked | done | dropped */
  status: string
  ownerPeer: string | null
  ownerName: string | null
  createdBy: string | null
  createdByName: string | null
  result: string | null
  blockReason: string | null
  seq: number
  /** 前置依赖的 todo id 清单（未完成的那些就是「它在等谁」；标题由页面按 id 解） */
  dependsOn: string[]
}

/** 执行态图的数据源：一张桌子当前的编排全貌（讨论节点 + 成员 + 共享任务区） */
export type MaWorkflow = {
  sessionId: string | null
  title: string | null
  topicId: string | null
  topicStatus: string | null
  floorPeer: string | null
  discussing: boolean
  /** 整桌「推进集结」：全员顺利做完、正等共识决定交付或新一轮（全局视角下恒为 false） */
  advancing: boolean
  agents: MaWorkflowAgent[]
  todos: MaWorkflowTodo[]
  events: MaWorkflowEvent[]
}

export type MaWorkflowEvent = {
  id: string
  kind: string | null
  source: string | null
  targets: string[]
  taskId: string | null
  text: string
  createdAt: number
}

/**
 * 执行态图（Agent 视角）：全局名册上所有智能体此刻的执行态，**不绑定会话**——
 * 成员是否在跑跨会话取并集，任务计数跨会话聚合。
 */
export function maWorkflowOverview(): Promise<MaWorkflow> {
  return requestJson<MaWorkflow>('/api/agents/workflow', undefined, 15000)
}

/** 执行态图（任务视角）：某会话的编排全貌；sessionId 缺省时后端返回全局总览 */
export function maWorkflow(sessionId?: string): Promise<MaWorkflow> {
  const q = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
  return requestJson<MaWorkflow>(`/api/agents/workflow${q}`, undefined, 15000)
}

/** 一次执行的过程留痕（点开一条任务时回放：Agent 到底在干嘛） */
export type MaWorkRun = {
  id: number
  todoId: string
  peerId: string | null
  peerName: string | null
  /** running | done | failed | blocked */
  status: string
  output: string | null
  reasoning: string | null
  trace: TraceSegment[]
  error: string | null
  startedAt: number
  finishedAt: number | null
  /** 持久化的工具往返消息，按 tool_call_id 关联参数与返回内容。 */
  toolRounds?: unknown[]
}

/** 某条待办的执行历史（含思考链与过程分段） */
export function maTodoRuns(todoId: string, limit = 10): Promise<MaWorkRun[]> {
  return requestJson<MaWorkRun[]>(
    `/api/agents/todos/${encodeURIComponent(todoId)}/runs?limit=${limit}`,
  )
}

/** 人对任务板一次干预（放弃 / 暂时跳过 / 立刻检查）的结果：改完之后的这条 */
export type MaTodoPatch = {
  /** 是否真的改了（条目不存在或该动作不适用时为 false） */
  ok: boolean
  id: string
  status: string | null
  priority: number
}

function todoAction(id: string, action: 'drop' | 'defer' | 'check'): Promise<MaTodoPatch> {
  return requestJson<MaTodoPatch>(`/api/agents/todos/${encodeURIComponent(id)}/${action}`, {
    method: 'POST',
  })
}

/** 放弃一条任务：置 dropped（它的下游依赖随之解锁） */
export function dropMaTodo(id: string): Promise<MaTodoPatch> {
  return todoAction(id, 'drop')
}

/** 暂时跳过：把这条压到最低优先级，有别的活就先做别的 */
export function deferMaTodo(id: string): Promise<MaTodoPatch> {
  return todoAction(id, 'defer')
}

/** 立刻检查：拉满优先级插到该成员队首，并把它的执行线程叫起来（不打断正在跑的那次） */
export function checkMaTodo(id: string): Promise<MaTodoPatch> {
  return todoAction(id, 'check')
}

/** 某条发言**当时真正发出去的原始上下文**（组装出的 messages + tools）。
 *  与对话页「查看原始上下文」的区别：那是「此刻再发一条会发什么」，这是当时那一份。 */
export function maMessageContext(
  messageId: number,
): Promise<{ messageId: number; context: string | null }> {
  return requestJson<{ messageId: number; context: string | null }>(
    `/api/agents/messages/${messageId}/context`,
  )
}

/** 圆桌流的回调：一个话题里会依次来好几组 speaker → delta/tool → speakerEnd */
export type MaStreamHandlers = {
  onTopic?: (t: { sessionId: string; topicId: string; status: string }) => void
  onSpeaker?: (s: { peerId: string; peerName: string }) => void
  /** 话题快照：每次状态有变就推一份（谁在说、谁沉默了、谁已提议结束），据它实时刷新状态条 */
  onState?: (topic: MaTopic) => void
  onReasoning?: (delta: string) => void
  onContent?: (delta: string) => void
  /** 成员又开口说了一段（一次 speak）：这一句自成一段，前端把它放进一个新的气泡 */
  onSpeech?: (text: string) => void
  onTool?: (tool: { name: string; args: string }) => void
  onSpeakerEnd?: (s: { peerId: string; state: string }) => void
  /** 一条状态机动作行（开话题、入场、转沉默……）：居中的说明行，一落库就推来 */
  onAction?: (text: string) => void
  onTopicEnd?: (t: { topicId: string }) => void
}

/**
 * 人往圆桌说一句话（SSE）：这一句会依次引出一串成员发言，增量边跑边推。
 *
 * <p>与 chatStream 同样是 fetch + 手工解析 text/event-stream（EventSource 不能带 body）；
 * signal 用于「停止」按钮中断读取——注意中断只停下前端的读取，后端仍会把当前这位的话落库，
 * 要真正打断请再调 {@link stopMaChat}。
 */
export async function maChatStream(
  input: string,
  sessionId: string | undefined,
  modelId: string | undefined,
  speakerStrategy: string | undefined,
  handlers: MaStreamHandlers,
  signal?: AbortSignal,
  redirectWork = false,
): Promise<MaTurnResult> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const res = await apiFetch('/api/agents/chat/stream', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, input, modelId, speakerStrategy, redirectWork }),
    signal,
  })
  if (!res.ok) throw new ApiError(res.status, await res.text().catch(() => ''))
  if (!res.body) throw new ApiError(res.status, '响应无流式内容')

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: MaTurnResult | null = null

  const dispatch = (block: string) => {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split(/\r?\n/)) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (data.length === 0) return
    const payload = JSON.parse(data.join('\n')) as Record<string, unknown>
    if (name === 'topic') {
      handlers.onTopic?.(payload as unknown as { sessionId: string; topicId: string; status: string })
    } else if (name === 'speaker') {
      handlers.onSpeaker?.(payload as unknown as { peerId: string; peerName: string })
    } else if (name === 'state') {
      handlers.onState?.(payload as unknown as MaTopic)
    } else if (name === 'reasoning') {
      handlers.onReasoning?.(payload.delta as string)
    } else if (name === 'content') {
      handlers.onContent?.(payload.delta as string)
    } else if (name === 'speech') {
      handlers.onSpeech?.(payload.text as string)
    } else if (name === 'tool') {
      handlers.onTool?.(payload as unknown as { name: string; args: string })
    } else if (name === 'speakerEnd') {
      handlers.onSpeakerEnd?.(payload as unknown as { peerId: string; state: string })
    } else if (name === 'action') {
      handlers.onAction?.(payload.text as string)
    } else if (name === 'topicEnd') {
      handlers.onTopicEnd?.(payload as unknown as { topicId: string })
    } else if (name === 'done') {
      result = payload as unknown as MaTurnResult
    } else if (name === 'error') {
      throw new Error(String(payload.error ?? '圆桌响应失败'))
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let m: RegExpExecArray | null
      while ((m = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, m.index)
        buffer = buffer.slice(m.index + m[0].length)
        dispatch(block)
      }
    }
  } catch (e) {
    await reader.cancel().catch(() => {})
    throw e
  } finally {
    reader.releaseLock()
  }
  if (!result) throw new Error('圆桌响应中断')
  return result
}

/** 打断当前发言（页面的「停止」按钮）：正在说话的那位就此打住，已说出的部分照常落库 */
export function stopMaChat(sessionId: string): Promise<{ stopped: boolean }> {
  return requestJson<{ stopped: boolean }>('/api/agents/chat/stop', {
    method: 'POST',
    body: JSON.stringify({ sessionId }),
  })
}

/* ---------------- 记忆（记忆体系 v5：四层 × 四域） ---------------- */

/**
 * 一条记忆条目。v5 起条目带「归属三维」：owner（USER/PROJECT/AGENT/TASK）× domain
 * （USER_PROFILE/PROJECT/AGENT/TASK_EPISODIC）× layer（L1 共享/L2 私有/L3 任务/L4 发布）。
 */
export type MemoryItem = {
  id: number
  agentId: string | null
  owner: string | null
  domain: string | null
  layer: string | null
  projectId: string | null
  sessionId: string | null
  taskId: number | null
  scope: string
  scopeLabel: string
  title: string | null
  body: string
  confidence: number
  source: string
  sourceKind: string | null
  evidenceLevel: string | null
  status: string | null
  retention: string | null
  identity: string | null
  version: number | null
  pinned: boolean
  active: boolean
  validUntil: number | null
  supersededBy: number | null
  createdAt: number
  updatedAt: number
  producerAgent?: string | null
  sourceRefs?: string | null
  expiresSoft?: number | null
  promotedFrom?: number | null
  learnedAt?: number | null
  invalidAt?: number | null
  temporalJson?: string | null
}

export type MemoryScopeGroup = { scope: string; label: string; count: number; items: MemoryItem[] }
export type MemorySummary = {
  scopes: MemoryScopeGroup[]
  active: number
  total: number
  pendingCandidates: number
  publishedShares: number
  inboxPending?: number
  workingOpen?: number
}
export type MemoryRequest = {
  scope: string
  title?: string | null
  body: string
  validUntil?: number | null
  owner?: string | null
  domain?: string | null
  layer?: string | null
  projectId?: string | null
  agentId?: string | null
  retention?: string | null
  version?: number | null
  sessionId?: string | null
  taskId?: number | null
  identity?: string | null
}

/** 一条画像候选（审批前不进正式画像） */
export type MemoryCandidate = {
  id: number
  scope: string
  scopeLabel: string
  title: string | null
  body: string
  sourceKind: string
  evidenceLevel: string
  evidenceCount: number
  status: string
  createdAt: number
  updatedAt: number
  evidenceRefs?: string | null
  validUntil?: number | null
}

/** 一条共享记录（L4 发布） */
export type MemoryShare = {
  id: number
  itemId: number
  fromOwner: string
  fromAgent: string | null
  toKind: string
  toRef: string | null
  status: string
  note: string | null
  createdAt: number
}

/** 一条双时态知识边 */
export type MemoryEdge = {
  id: string
  owner: string
  projectId: string | null
  agentId: string | null
  subject: string
  predicate: string
  object: string
  confidence: number
  validAt: number | null
  invalidAt: number | null
  learnedAt: number
  active: boolean
}

export function memorySummary(): Promise<MemorySummary> {
  return requestJson<MemorySummary>('/api/memory/summary')
}

export function memoryItems(q?: {
  scope?: string
  layer?: string
  identity?: string
  sessionId?: string
  owner?: string
  domain?: string
  projectId?: string
  agentId?: string
  status?: string
}): Promise<MemoryItem[]> {
  const params = new URLSearchParams()
  if (q?.scope) params.set('scope', q.scope)
  if (q?.layer) params.set('layer', q.layer)
  if (q?.identity) params.set('identity', q.identity)
  if (q?.sessionId) params.set('sessionId', q.sessionId)
  for (const key of ['owner', 'domain', 'projectId', 'agentId', 'status'] as const) {
    if (q?.[key]) params.set(key, q[key])
  }
  const qs = params.toString()
  return requestJson<MemoryItem[]>(`/api/memory/items${qs ? `?${qs}` : ''}`)
}

export function createMemoryItem(req: MemoryRequest): Promise<MemoryItem> {
  return requestJson<MemoryItem>('/api/memory/items', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function updateMemoryItem(id: number, req: MemoryRequest): Promise<MemoryItem> {
  return requestJson<MemoryItem>(`/api/memory/items/${id}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

export function deleteMemoryItem(id: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/items/${id}`, { method: 'DELETE' })
}

export function pinMemoryItem(id: number, pinned: boolean): Promise<MemoryItem> {
  return requestJson<MemoryItem>(`/api/memory/items/${id}/pin`, {
    method: 'POST',
    body: JSON.stringify({ pinned }),
  })
}

/** 撤销条目（用户动作；画像另写墓碑，同指纹不再自动重建） */
export function revokeMemoryItem(id: number, reason?: string): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/items/${id}/revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? null }),
  })
}

export function consolidateMemory(): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>('/api/memory/consolidate', { method: 'POST' })
}

export function clearMemory(): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>('/api/memory', { method: 'DELETE' })
}

/* ---------- 画像候选（v5） ---------- */

export function memoryCandidates(status = 'pending'): Promise<MemoryCandidate[]> {
  return requestJson<MemoryCandidate[]>(`/api/memory/candidates?status=${encodeURIComponent(status)}`)
}

export function approveCandidate(id: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/candidates/${id}/approve`, { method: 'POST' })
}

export function rejectCandidate(id: number, reason?: string): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/candidates/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason: reason ?? null }),
  })
}

/* ---------- 共享（L4） ---------- */

export function memoryShared(): Promise<MemoryShare[]> {
  return requestJson<MemoryShare[]>('/api/memory/shared')
}

export function shareMemoryItem(
  id: number,
  req: { toKind: string; toRef?: string | null; note?: string | null },
): Promise<MemoryShare> {
  return requestJson<MemoryShare>(`/api/memory/items/${id}/share`, {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

export function revokeMemoryShare(id: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/shared/${id}/revoke`, { method: 'POST' })
}

/* ---------- 知识边（双时态） ---------- */

export function memoryEdges(): Promise<MemoryEdge[]> {
  return requestJson<MemoryEdge[]>('/api/memory/edges')
}

export function addMemoryEdge(req: {
  subject: string
  predicate: string
  object: string
  owner?: string | null
  projectId?: string | null
  agentId?: string | null
  confidence?: number | null
  sourceItem?: number | null
}): Promise<MemoryEdge> {
  return requestJson<MemoryEdge>('/api/memory/edges', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/* ---------- 工作集（L3 运行态） ---------- */

/** 一条工作项（会话/任务级临时物，与长期记忆物理分离） */
export type WorkingItem = {
  id: number
  sessionId: string | null
  taskId: number | null
  agentId: string | null
  projectId: string | null
  kind: string
  title: string | null
  body: string
  status: string
  expiresAt: number | null
  createdAt: number
  updatedAt: number
}

export function memoryWorkingSet(q?: {
  sessionId?: string
  taskId?: number
  limit?: number
}): Promise<WorkingItem[]> {
  const params = new URLSearchParams()
  if (q?.sessionId) params.set('sessionId', q.sessionId)
  if (q?.taskId != null) params.set('taskId', String(q.taskId))
  if (q?.limit != null) params.set('limit', String(q.limit))
  const qs = params.toString()
  return requestJson<WorkingItem[]>(`/api/memory/working-set${qs ? `?${qs}` : ''}`)
}

/** 关闭一条工作项（会话/任务结束时蒸馏的入口） */
export function closeWorkingItem(id: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/working-set/${id}/close`, { method: 'POST' })
}

/** 任务检查点：某任务当前的运行状态与快照（checkpoint 为 JSON 原文） */
export type WorkingCheckpoint = { taskId: number; status: string; checkpoint: string | null }

export function memoryCheckpoint(taskId: number): Promise<WorkingCheckpoint> {
  return requestJson<WorkingCheckpoint>(
    `/api/memory/working-set/checkpoint?taskId=${encodeURIComponent(taskId)}`,
  )
}

/* ---------- 记忆体检（三病报告） ---------- */

/** 记忆体检报告：污染 / 挤占 / 漂移 + 总评级 */
export type MemoryHealth = {
  generatedAt: number
  grade: 'ok' | 'warn' | 'risk'
  pollution: {
    fictionInProfile: number
    blockedFictionCandidates: number
    rejectedCandidates: number
    alerts: string[]
  }
  crowding: {
    domains: { key: string; count: number; chars: number; share: number }[]
    alerts: string[]
  }
  drift: {
    superseded: number
    recent: { itemId: number; scope: string; title: string | null; body: string; supersededBy: number; invalidAt: number }[]
    alerts: string[]
  }
}

export function memoryHealth(): Promise<MemoryHealth> {
  return requestJson<MemoryHealth>('/api/memory/health')
}

/** 运行指标（memory-v6 §17.2）：不变量是否被守住、预算挤出、快照覆盖率（进程内计数，重启清零） */
export type MemoryMetrics = {
  scopeMissing: number
  invariantViolation: number
  writeConflict: number
  budgetEvicted: number
  crossDomainBlocked: number
  snapshotBuilt: number
  snapshotSaved: number
  snapshotCoverage: number
}

export function memoryMetrics(): Promise<MemoryMetrics> {
  return requestJson<MemoryMetrics>('/api/memory/metrics')
}

/** 手动恢复某任务的工作状态（把检查点还原进工作集；幂等，已还原过返回 0） */
export function restoreWorkingSet(taskId: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(
    `/api/memory/restore?taskId=${encodeURIComponent(String(taskId))}`,
    { method: 'POST' },
  )
}

/* ---------------- 记忆中心 P4（双时态点查 / 提升层 / 发布收件箱 / 遗忘） ---------------- */

/** 双时态点查视图：某时刻（at 为毫秒时间戳）仍有效的条目与知识边 */
export type AsOfView = {
  at: number
  items: MemoryItem[]
  edges: MemoryEdge[]
}

/** 一条写入审计（revision）：每次提交留一条，可回放、可追责、可 purge 溯源 */
export type MemoryRevision = {
  id: number
  itemId: number | null
  op: string
  actorKind: string
  actorId: string | null
  turnId: string | null
  beforeJson: string | null
  afterJson: string | null
  reason: string | null
  createdAt: number
}

/** 显式遗忘（purge）请求体：按条目 / 归属 / 时间范围撤回 */
export type PurgeRequest = {
  itemIds?: number[]
  owner?: string
  agentId?: string
  projectId?: string
  scope?: string
  before?: number
  reason?: string
}

/** purge 影响面预览：先看影响多少条（items 为前 20 条样例） */
export type PurgePreview = {
  matched: number
  active: number
  userProfile: number
  items: MemoryItem[]
}

/** 一条提升记录（L2→L4 / L4→L1：candidate → applied） */
export type MemoryPromotion = {
  id: number
  itemId: number
  fromLayer: string
  toLayer: string
  state: string
  evidenceCount: number
  note: string | null
  createdAt: number
}

/** 收件箱一条：待接收的交接 + 条目摘要 */
export type MemoryInboxEntry = {
  handoffId: number
  toKind: string
  toRef: string | null
  state: string
  itemId: number
  title: string | null
  body: string
  layer: string | null
  note: string | null
  createdAt: number
}

/** 双时态点查：返回该时刻有效的记忆视图（valid_at ≤ at < invalid_at） */
export function memoryAsOf(at: number, limit = 100): Promise<AsOfView> {
  const params = new URLSearchParams({ at: String(at), limit: String(limit) })
  return requestJson<AsOfView>(`/api/memory/as-of?${params.toString()}`)
}

/** 某条目所在取代链（按「旧 → 新」排序） */
export function memoryItemHistory(id: number): Promise<MemoryItem[]> {
  return requestJson<MemoryItem[]>(`/api/memory/items/${id}/history`)
}

/** 写入审计（可按条目过滤；limit 缺省 100） */
export function memoryRevisions(q?: { itemId?: number; limit?: number }): Promise<MemoryRevision[]> {
  const params = new URLSearchParams()
  if (q?.itemId != null) params.set('itemId', String(q.itemId))
  params.set('limit', String(q?.limit ?? 100))
  return requestJson<MemoryRevision[]>(`/api/memory/revisions?${params.toString()}`)
}

/** 显式遗忘预览：先给影响面，确认后再调 {@link purgeMemory} */
export function previewPurge(req: PurgeRequest): Promise<PurgePreview> {
  return requestJson<PurgePreview>('/api/memory/purge/preview', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/** 显式遗忘：置 revoked + 写墓碑 + revision 记 purge */
export function purgeMemory(req: PurgeRequest): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>('/api/memory/purge', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/** 扫描提升候选（L2→L4 / L4→L1），返回新生成的候选数 */
export function scanPromotions(): Promise<{ candidates: number }> {
  return requestJson<{ candidates: number }>('/api/memory/promote/scan', { method: 'POST' })
}

/** 待决提升候选列表 */
export function memoryPromotions(): Promise<MemoryPromotion[]> {
  return requestJson<MemoryPromotion[]>('/api/memory/promotions')
}

/** 落地一条提升候选（改 layer + 记来源凭据） */
export function applyPromotion(id: number): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/promotions/${id}/apply`, { method: 'POST' })
}

/** 记一条提升候选（缺省目标层 = L4） */
export function promoteMemoryItem(
  id: number,
  req?: { toLayer?: string; evidenceCount?: number; note?: string },
): Promise<MemoryPromotion> {
  return requestJson<MemoryPromotion>(`/api/memory/items/${id}/promote`, {
    method: 'POST',
    body: JSON.stringify(req ?? {}),
  })
}

/** 收件箱：待接收的交接（人工作用域 = 全量待接收） */
export function memoryInbox(): Promise<MemoryInboxEntry[]> {
  return requestJson<MemoryInboxEntry[]>('/api/memory/inbox')
}

/** 交接状态机：accept / reject / done */
export function moveHandoff(id: number, state: string): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/memory/inbox/${id}/${encodeURIComponent(state)}`, {
    method: 'POST',
  })
}

/* ---------------- 项目（记忆作用域锚点） ---------------- */

export type Project = {
  id: string
  userId: string | null
  name: string
  kind: string
  narrative: boolean
  status: string
  note: string | null
  createdAt: number
  updatedAt: number
}

export type ProjectRequest = {
  name: string
  kind?: string | null
  narrative?: boolean | null
  status?: string | null
  note?: string | null
}

export function listProjects(): Promise<Project[]> {
  return requestJson<Project[]>('/api/projects')
}

export function createProject(req: ProjectRequest): Promise<Project> {
  return requestJson<Project>('/api/projects', { method: 'POST', body: JSON.stringify(req) })
}

export function organizeSessionProject(sessionId: string, req: { name: string; kind: string; note?: string; projectId?: string }): Promise<Project> {
  return requestJson(`/api/projects/sessions/${encodeURIComponent(sessionId)}/organize`, { method: 'POST', body: JSON.stringify(req) })
}

export function continueSessionProject(sessionId: string): Promise<{ id: string; projectId: string | null; agentId: string | null; sourceSessionId: string }> {
  return requestJson(`/api/projects/sessions/${encodeURIComponent(sessionId)}/continue`, { method: 'POST', body: '{}' })
}

export function revokeSessionContinuation(sessionId: string): Promise<{ revoked: boolean }> {
  return requestJson(`/api/projects/sessions/${encodeURIComponent(sessionId)}/continuation`, { method: 'DELETE' })
}

export function updateProject(id: string, req: Partial<ProjectRequest>): Promise<Project> {
  return requestJson<Project>(`/api/projects/${id}`, { method: 'PUT', body: JSON.stringify(req) })
}

export function deleteProject(id: string): Promise<{ changed: number }> {
  return requestJson<{ changed: number }>(`/api/projects/${id}`, { method: 'DELETE' })
}

/** 确认任务内的项目事实，使其成为当前项目的共享记忆。 */
export function confirmProjectMemory(id: number, version: number): Promise<{ changed: number }> {
  return requestJson(`/api/memory/items/${id}/confirm-project`, { method: 'POST', body: JSON.stringify({ version }) })
}

/** 用户明确结束会话工作后提交蒸馏；普通 Turn 和暂停不调用此入口。 */
export function completeSessionMemory(sessionId: string): Promise<{ changed: number }> {
  return requestJson(`/api/memory/sessions/${encodeURIComponent(sessionId)}/complete`, { method: 'POST' })
}
