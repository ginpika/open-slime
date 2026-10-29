/**
 * 原始请求体查看器：给首页会话「…」菜单的「查看原始上下文」用的高级 JSON 预览器。
 *
 * 两层结构：
 *  1) <JsonViewer>：通用 JSON 树。对象 / 数组可折叠，超长字符串折叠成一行预览，
 *     字符串内成对的 Prompt 标签可独立折叠（含嵌套，原文不作 HTML 执行）。
 *     值按类型着色（走主题语义色，四主题自适应）。初始展开深度由 openDepth 决定，
 *     之后每个节点各自维护折叠状态、可单独点开——不再是一坨无法收起的纯文本。
 *  2) <RawContextView>：贴合会话 Turn 架构的封装。把 /chat/completions 请求体拆成
 *     「信封（model）+ messages + tools」；其中 messages 按 Turn 分组（一条 user 起
 *     一个 Turn，其后的 assistant / tool 归入该 Turn），每条消息默认折叠——开发者从
 *     Turn 视角逐条展开，就能看清 Agent 每一步看到了什么、调用了什么工具、返回了什么。
 *
 * 交互：行点击折叠（正在选中文字时不触发，避免选字即收起），顶部可一键展开 / 折叠
 * 全部、复制原始 JSON。原始请求体与真实发出去的那一份完全一致（见后端 requestBody）。
 */
import { AppIcon } from './AppIcon'
import { memo, useMemo, useState, type ReactNode } from 'react'
import { parseTaggedText, type TaggedTextPart } from './taggedText'
import './jsonViewer.css'
import {
  sessionTurnCalls,
  type RawContext,
  type RawContextTurn,
  type TurnLlmCall,
} from '../api/client'

/* ============================================================ 值分类与配色 */

type Kind = 'string' | 'number' | 'boolean' | 'null' | 'object' | 'array'

function kindOf(value: unknown): Kind {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  switch (typeof value) {
    case 'string':
      return 'string'
    case 'number':
      return 'number'
    case 'boolean':
      return 'boolean'
    default:
      return 'object'
  }
}

/** 值类型 → 文本配色（全部走主题语义色，深浅底均保证对比） */
const KIND_TEXT: Record<Kind, string> = {
  string: 'text-fg-2',
  number: 'text-info',
  boolean: 'text-warn',
  null: 'text-faint',
  object: 'text-muted',
  array: 'text-muted',
}

/** 超过此长度的字符串（或含换行的）默认折叠成一行预览 */
const STRING_FOLD = 140

/** 压成单行并截断——用于各种摘要 / 预览，避免把多行正文塞进一行 */
function clip(text: string, max: number): string {
  const one = text.replace(/\s+/g, ' ').trim()
  return one.length > max ? `${one.slice(0, max)}…` : one
}

/** 折叠时一个标量的字面量预览（容器只报数量，不再展开） */
function literalPreview(value: unknown): string {
  const kind = kindOf(value)
  if (kind === 'string') return `"${clip(value as string, 36)}"`
  if (kind === 'null') return 'null'
  if (kind === 'array') return `[…${(value as unknown[]).length}]`
  if (kind === 'object') return `{…${Object.keys(value as object).length}}`
  return String(value)
}

/** 折叠时对象内部的行内预览（不含花括号）：`键: 值, …` */
function innerPreview(obj: Record<string, unknown>): string {
  const keys = Object.keys(obj)
  const head = keys.slice(0, 4).map((k) => `${k}: ${literalPreview(obj[k])}`).join(', ')
  return keys.length > 4 ? `${head}, …` : head
}

/** 行点击折叠：正在选中文字时不触发（选完字松手不该顺手把节点收起） */
function toggleOnRow(toggle: () => void) {
  return () => {
    const sel = window.getSelection()
    if (sel && sel.toString().length > 0) return
    toggle()
  }
}

/** 折叠三角（纯装饰，点击由所在行统一接管） */
function Caret({ open }: { open: boolean }) {
  return (
    <AppIcon name="chevron-right" className={`mt-[2px] h-3 w-3 shrink-0 text-faint transition-transform ${open ? 'rotate-90' : ''}`} />
  )
}

/* ================================================================ JSON 树 */

function TaggedText({ parts, defaultOpen, insideTag = false }: { parts: TaggedTextPart[]; defaultOpen: boolean; insideTag?: boolean }) {
  const structured = insideTag || parts.some((part) => part.type === 'tag')
  return <div className="jv-tagged-text">{parts.map((part, i) => {
    if (part.type === 'tag') return <TagBlock key={i} part={part} defaultOpen={defaultOpen} />
    // 块级标签之间的缩进 / 空行是源文本的排版，不应生成额外行盒。
    // 仅调整结构化阅读视图，解析结果与复制的原始 JSON 保持不变。
    const text = structured
      ? part.text.replace(/^(?:[\t ]*\r?\n)+/, '').replace(/(?:\r?\n[\t ]*)+$/, '')
      : part.text
    return text.trim() || !structured ? <span className="jv-text-run" key={i}>{text}</span> : null
  })}</div>
}

function TagBlock({ part, defaultOpen }: {
  part: Extract<TaggedTextPart, { type: 'tag' }>
  defaultOpen: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="jv-tag" data-open={open}>
      <button
        type="button"
        aria-label={`${open ? '收起' : '展开'} ${part.name} 标签`}
        aria-expanded={open}
        onClick={toggleOnRow(() => setOpen((v) => !v))}
        className="jv-tag-toggle"
      >
        <Caret open={open} />
        <span className="jv-tag-name">
          {part.opening}
        </span>
        {!open && <span className="jv-tag-fold">…</span>}
      </button>
      {open && <>
        <div className="jv-tag-body">
          <TaggedText parts={part.children} defaultOpen={defaultOpen} insideTag />
        </div>
        <div className="jv-tag-close">{part.closing}</div>
      </>}
    </div>
  )
}

type NodeProps = {
  value: unknown
  /** 对象键名（根节点与数组项不传） */
  name?: string
  /** 数组索引（仅数组项） */
  index?: number
  depth: number
  /** 初始展开深度：depth < openDepth 的容器默认展开 */
  openDepth: number
  /** 是否为其父容器中的最后一项（决定末尾逗号） */
  last?: boolean
}

/** 单个 JSON 节点（递归）：一行 `键: 值`，容器展开后子节点缩进一级 */
const JsonNode = memo(function JsonNode({ value, name, index, depth, openDepth, last }: NodeProps) {
  const kind = kindOf(value)
  const isArray = kind === 'array'
  const isContainer = kind === 'object' || isArray
  const containerSize = isArray
    ? (value as unknown[]).length
    : isContainer
      ? Object.keys(value as object).length
      : 0
  const emptyContainer = isContainer && containerSize === 0
  const text = kind === 'string' ? (value as string) : null
  const taggedParts = useMemo(() => text !== null ? parseTaggedText(text) : [], [text])
  const isLongString = text !== null && (text.includes('\n') || text.length > STRING_FOLD)
  const isFoldableString = isLongString || taggedParts.some((p) => p.type === 'tag')
  /** 空容器没什么可折的，当叶子内联展示（`{}` / `[]`） */
  const foldable = !emptyContainer && (isContainer || isFoldableString)

  const [open, setOpen] = useState(depth < openDepth)
  const toggle = () => setOpen((v) => !v)

  /* 值那一半：容器折叠态报数量 / 预览，展开态只画左括号（右括号在子节点之后单独一行） */
  let valueEl: ReactNode
  if (isContainer && open && !emptyContainer) {
    valueEl = <span className="text-dim">{isArray ? '[' : '{'}</span>
  } else if (emptyContainer) {
    valueEl = <span className="text-dim">{isArray ? '[]' : '{}'}</span>
  } else if (isContainer) {
    valueEl = (
      <span className="text-faint">
        <span className="text-dim">{isArray ? '[' : '{'}</span>
        {isArray ? ` … ${containerSize} 项 ` : ` ${innerPreview(value as Record<string, unknown>)} `}
        <span className="text-dim">{isArray ? ']' : '}'}</span>
      </span>
    )
  } else if (isFoldableString && !open) {
    valueEl = <span className={KIND_TEXT.string}>{`"${clip(text ?? '', 90)}"`}</span>
  } else if (isFoldableString) {
    valueEl = <span className={KIND_TEXT.string}>{'"'}</span>
  } else {
    valueEl = (
      <span className={KIND_TEXT[kind]}>
        {kind === 'null' ? 'null' : kind === 'string' ? `"${text}"` : String(value)}
      </span>
    )
  }

  /* 展开的容器 / 字符串：逗号挪到闭合符号那行；其余情况逗号跟在值后面 */
  const commaHere = !last && !(foldable && open)

  return (
    <div>
      <div
        role={foldable ? 'button' : undefined}
        tabIndex={foldable ? 0 : undefined}
        aria-expanded={foldable ? open : undefined}
        onClick={foldable ? toggleOnRow(toggle) : undefined}
        onKeyDown={
          foldable
            ? (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  toggle()
                }
              }
            : undefined
        }
        className={`jv-node-row flex items-start gap-1 rounded-sm${
          foldable ? ' cursor-pointer hover:bg-fg/[0.04]' : ''
        }`}
      >
        {foldable ? <Caret open={open} /> : <span className="mt-[2px] h-3 w-3 shrink-0" />}
        <span className="min-w-0 flex-1 whitespace-pre-wrap break-words">
          {name !== undefined && (
            <>
              <span className="text-fg">{name}</span>
              <span className="text-dim">: </span>
            </>
          )}
          {index !== undefined && <span className="text-faint">{index}: </span>}
          {valueEl}
          {commaHere && <span className="text-dim">,</span>}
        </span>
      </div>

      {isFoldableString && open && (
        <>
          <div className="jv-string-body">
            <TaggedText parts={taggedParts} defaultOpen={depth < openDepth} />
          </div>
          <div className="ml-3.5">
            <span className={KIND_TEXT.string}>{'"'}</span>
            {!last && <span className="text-dim">,</span>}
          </div>
        </>
      )}

      {/* 只有容器遍历子项；Object.entries(string) 会把整段正文再逐字符渲染一次。 */}
      {isContainer && open && !emptyContainer && (
        <>
          <div className="ml-3.5 border-l border-line/50 pl-2">
            {isArray
              ? (value as unknown[]).map((v, i, arr) => (
                  <JsonNode
                    key={i}
                    value={v}
                    index={i}
                    depth={depth + 1}
                    openDepth={openDepth}
                    last={i === arr.length - 1}
                  />
                ))
              : Object.entries(value as Record<string, unknown>).map(([k, v], i, arr) => (
                  <JsonNode
                    key={k}
                    value={v}
                    name={k}
                    depth={depth + 1}
                    openDepth={openDepth}
                    last={i === arr.length - 1}
                  />
                ))}
          </div>
          <div className="flex gap-1">
            <span className="h-3 w-3 shrink-0" />
            <span className="text-dim">{isArray ? ']' : '}'}</span>
            {!last && <span className="text-dim">,</span>}
          </div>
        </>
      )}
    </div>
  )
})

/** 通用 JSON 查看器：根级 value + 初始展开深度（默认 1：根展开，一层容器收起） */
export function JsonViewer({
  value,
  openDepth = 1,
  className = '',
}: {
  value: unknown
  openDepth?: number
  className?: string
}) {
  return (
    <div className={`json-viewer font-mono text-fg ${className}`}>
      <JsonNode value={value} depth={0} openDepth={openDepth} last />
    </div>
  )
}

/* ==================================================== 贴合 Turn 架构的封装 */

type Msg = Record<string, unknown>

function roleName(msg: Msg): string {
  return String(msg.role ?? '?')
}

function hasToolCalls(msg: Msg): boolean {
  return Array.isArray(msg.tool_calls) && msg.tool_calls.length > 0
}

/** 折叠态摘要：一眼看出这条消息是什么（工具调用 / 正文首句 / 结果长度） */
function summarize(msg: Msg): string {
  const role = roleName(msg)
  const content = typeof msg.content === 'string' ? msg.content : ''
  const calls = Array.isArray(msg.tool_calls) ? msg.tool_calls : []
  const hasCot = typeof msg.reasoning_content === 'string' && msg.reasoning_content.length > 0
  if (calls.length > 0) {
    const names = calls.map((c) =>
      String((c as { function?: { name?: unknown } })?.function?.name ?? '?'),
    )
    return `调用 ${names.join('、')}${hasCot ? ' · 含思维链' : ''}`
  }
  if (role === 'tool') return `${content.length} 字${content ? ` · ${clip(content, 70)}` : ''}`
  if (role === 'system') return `${content.length} 字`
  return content ? clip(content, 90) : '（空）'
}

/* 分组：一条 user 起一个 Turn，其后的 assistant / tool 归入该 Turn；
   开头的 system 与无归属的散落消息不套 Turn 外壳，直接平铺 */
type Group = { turn: boolean; index: number; messages: Msg[] }

function groupTurns(messages: Msg[]): Group[] {
  const groups: Group[] = []
  let current: Msg[] = []
  let turnNo = 0
  const flush = () => {
    if (current.length === 0) return
    if (roleName(current[0]) === 'user') {
      turnNo += 1
      groups.push({ turn: true, index: turnNo, messages: current })
    } else {
      groups.push({ turn: false, index: 0, messages: current })
    }
    current = []
  }
  for (const m of messages) {
    const role = roleName(m)
    if (role === 'user' || role === 'system') {
      flush()
      current = [m]
    } else {
      current.push(m)
    }
  }
  flush()
  return groups
}

/** 触发来源 → 展示标签；不在表内的原样显示 */
const SOURCE_LABEL: Record<string, string> = {
  USER_INPUT: '用户提问',
  SUBAGENT_REPORT: '子代理回报',
  HEARTBEAT: '心跳',
  PLAN: '计划',
  SYSTEM: '系统',
}

/** 渲染单元：一个 Turn（带后端切片与回答行 id）或一段无归属的消息（如前置 system） */
type Item =
  | { type: 'turn'; index: number; span: RawContextTurn; msgs: Msg[] }
  | { type: 'loose'; msgs: Msg[] }

/**
 * 用后端给的 Turn 切片把 messages 切成渲染单元：切片覆盖不到的收尾（如前置 system）平铺。
 * 后端没给切片时（旧接口 / 异常）退回「一条 user 起一个 Turn」的粗分——此时没有回答行 id，
 * 也就没有「请求 / 响应」按钮（对齐不了）。
 */
function buildItems(messages: Msg[], turns: RawContextTurn[]): Item[] {
  if (turns.length === 0) {
    return groupTurns(messages).map((g) =>
      g.turn
        ? {
            type: 'turn',
            index: g.index,
            span: { from: 0, to: 0, source: 'USER_INPUT', replyMessageId: null },
            msgs: g.messages,
          }
        : { type: 'loose', msgs: g.messages },
    )
  }
  const items: Item[] = []
  let cursor = 0
  turns.forEach((t, i) => {
    if (t.from > cursor) {
      items.push({ type: 'loose', msgs: messages.slice(cursor, t.from) })
    }
    items.push({ type: 'turn', index: i + 1, span: t, msgs: messages.slice(t.from, t.to) })
    cursor = t.to
  })
  if (cursor < messages.length) {
    items.push({ type: 'loose', msgs: messages.slice(cursor) })
  }
  return items
}

/** 一条消息卡：头部是角色 + 摘要，展开后是该消息的 JSON 原文（默认折叠） */
const MessageCard = memo(function MessageCard({
  msg,
  defaultOpen,
  openDepth,
}: {
  msg: Msg
  defaultOpen: boolean
  openDepth: number
}) {
  const [open, setOpen] = useState(defaultOpen)
  const role = roleName(msg)
  return (
    <div className="raw-message" data-role={role}>
      <div
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={toggleOnRow(() => setOpen((v) => !v))}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setOpen((v) => !v)
          }
        }}
        className="raw-message-heading"
      >
        <Caret open={open} />
        <span className="raw-role">
          {role}
        </span>
        <span className="raw-message-summary">{summarize(msg)}</span>
      </div>
      {open && (
        <div className="raw-message-body">
          <JsonViewer value={msg} openDepth={openDepth} />
        </div>
      )}
    </div>
  )
})

/** 调用留痕里 request / response 是 JSON 文本：解析成树；解析失败原样返回字符串 */
function parseMaybe(text: string | null): unknown {
  if (text == null) return null
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

/** 请求体 / 响应体这类子树的折叠块 */
function SubTree({ label, value, tone }: { label: string; value: unknown; tone?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={toggleOnRow(() => setOpen((v) => !v))}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setOpen((v) => !v)
          }
        }}
        className="flex cursor-pointer items-start gap-1 rounded-sm hover:bg-fg/[0.04]"
      >
        <Caret open={open} />
        <span className={`text-xs ${tone ?? 'text-muted'}`}>{label}</span>
        {value === null && <span className="text-2xs text-faint">（无）</span>}
      </div>
      {open && value !== null && (
        <div className="ml-3.5 border-l border-line/50 pl-2">
          <JsonViewer value={value} openDepth={1} />
        </div>
      )}
    </div>
  )
}

/** 一轮 LLM 调用：第几轮 + 是否带工具面，展开后是这一轮的请求体与响应体（各自默认折叠） */
function CallRound({ call }: { call: TurnLlmCall }) {
  const [open, setOpen] = useState(false)
  const req = useMemo(() => parseMaybe(call.request), [call.request])
  const res = useMemo(() => parseMaybe(call.response), [call.response])
  return (
    <div className="rounded-sm border border-line/60 bg-fg/[0.02]">
      <div
        role="button"
        tabIndex={0}
        aria-expanded={open}
        onClick={toggleOnRow(() => setOpen((v) => !v))}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            setOpen((v) => !v)
          }
        }}
        className="flex cursor-pointer items-center gap-1.5 rounded-sm px-1.5 py-1 hover:bg-fg/[0.04]"
      >
        <Caret open={open} />
        <span className="mono text-xs text-fg">第 {call.round + 1} 轮</span>
        <span className="text-2xs text-faint">
          {call.withTools ? '带工具面' : '已撤工具面（末轮）'}
        </span>
      </div>
      {open && (
        <div className="space-y-1 border-t border-line/50 px-1.5 pb-1.5 pt-1">
          <SubTree label="请求体" value={req} tone="text-accent" />
          <SubTree label="响应体" value={res} tone="text-ok" />
        </div>
      )}
    </div>
  )
}

/** 一个 Turn：标题行为触发预览，默认收纳；展开后是它的消息卡列表。
 *  右侧「请求 / 响应」按钮回看这一 Turn 每一轮真正发出去的请求体与收到的响应体（首次点开才拉取） */
const TurnBlock = memo(function TurnBlock({
  index,
  source,
  replyMessageId,
  sessionId,
  messages,
  turnOpen,
  msgOpen,
  nodeDepth,
}: {
  index: number
  source: string
  replyMessageId: number | null
  sessionId: string
  messages: Msg[]
  /** 初始是否展开（受顶部「展开全部 / 折叠全部」控制；之后各自记住手动开合） */
  turnOpen: boolean
  msgOpen: boolean
  nodeDepth: number
}) {
  const [open, setOpen] = useState(turnOpen)
  const [showCalls, setShowCalls] = useState(false)
  const [calls, setCalls] = useState<TurnLlmCall[] | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  const rounds = messages.filter(hasToolCalls).length
  const label = SOURCE_LABEL[source] ?? source

  /* 首次点开「请求 / 响应」才拉取：留痕可能很大，不随主请求体一起加载 */
  const toggleCalls = () => {
    const next = !showCalls
    setShowCalls(next)
    if (next && calls === null && !loading && replyMessageId !== null) {
      setLoading(true)
      setErr(null)
      sessionTurnCalls(sessionId, replyMessageId)
        .then((r) => setCalls(r.calls))
        .catch(() => setErr('调用留痕加载失败'))
        .finally(() => setLoading(false))
    }
  }

  return (
    <div className="raw-turn">
      <div className="raw-turn-heading">
        <div
          role="button"
          tabIndex={0}
          aria-expanded={open}
          onClick={toggleOnRow(() => setOpen((v) => !v))}
          onKeyDown={(e) => {
            if ((e.target as HTMLElement).closest('button')) return
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              setOpen((v) => !v)
            }
          }}
          className="flex min-w-0 flex-1 cursor-pointer items-center gap-1.5 rounded-sm hover:bg-fg/[0.04]"
        >
          <Caret open={open} />
          <span className="raw-turn-title">Turn {index}</span>
          <span className="raw-turn-source">{label}</span>
          <span className="min-w-0 flex-1 truncate text-xs text-muted">
            {summarize(messages[0])}
          </span>
          <span className="raw-turn-count">
            {messages.length} 条{rounds > 0 ? ` · ${rounds} 轮工具` : ''}
          </span>
        </div>
        {replyMessageId !== null && (
          <button
            type="button"
            onClick={toggleCalls}
            title="查看这一 Turn 每一轮真正发出去的请求体与收到的响应体"
            className="raw-context-action shrink-0"
          >
            请求 / 响应
          </button>
        )}
      </div>

      {open && (
        <div className="raw-message-list">
          {messages.map((m, i) => (
            <MessageCard key={i} msg={m} defaultOpen={msgOpen} openDepth={nodeDepth} />
          ))}
        </div>
      )}

      {showCalls && replyMessageId !== null && (
        <div className="mb-1 mr-1.5 space-y-1.5 rounded-sm border border-line/60 bg-fg/[0.03] p-1.5">
          <div className="text-2xs text-faint">
            {loading
              ? '加载中…'
              : err
                ? err
                : calls && calls.length > 0
                  ? `共 ${calls.length} 轮 LLM 调用`
                  : '暂无调用留痕（该回答早于本功能，或本轮未调用模型）'}
          </div>
          {calls?.map((c) => (
            <CallRound key={c.round} call={c} />
          ))}
        </div>
      )}
    </div>
  )
})

/** 原始请求体入口：messages（按后端 Turn 切片分组）+ 请求参数 + tools。
 *  sessionId 用于回看某个 Turn 的调用留痕（见 TurnBlock 的「请求 / 响应」按钮） */
export function RawContextView({ ctx, sessionId }: { ctx: RawContext; sessionId: string }) {
  const [gen, setGen] = useState(0)
  /** 全局展开态：null=初始（消息默认折叠），true=全展开，false=全折叠 */
  const [all, setAll] = useState<boolean | null>(null)
  const [copied, setCopied] = useState(false)

  const body = ctx.body
  const messages = useMemo(() => (body.messages ?? []) as Msg[], [body])
  const items = useMemo(() => buildItems(messages, ctx.turns ?? []), [messages, ctx.turns])
  const tools = Array.isArray(body.tools) ? body.tools : null
  const parameters = useMemo(
    () => Object.fromEntries(Object.entries(body).filter(([k]) => k !== 'messages' && k !== 'tools')),
    [body],
  )
  const turnCount = useMemo(() => items.filter((i) => i.type === 'turn').length, [items])
  const roundCount = useMemo(() => messages.filter(hasToolCalls).length, [messages])

  const msgOpen = all === true
  const nodeDepth = all === true ? 999 : 1

  const bust = (next: boolean) => {
    setAll(next)
    setGen((g) => g + 1) // 重新挂载子树，让各节点按新默认值重置折叠态
  }

  const copy = async () => {
    try {
      /* 只复制请求体本身（turns 切片是预览用的元信息，不属于发给大模型的内容） */
      await navigator.clipboard.writeText(JSON.stringify(body, null, 2))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      /* 剪贴板不可用时静默忽略 */
    }
  }

  return (
    <div className="raw-context-view select-text text-fg">
      {/* 工具条：统计 + 展开 / 折叠全部 + 复制 */}
      <div className="raw-context-toolbar">
        <div className="raw-context-overview">
          <span className="raw-context-model"><AppIcon name="code" size={16} />{body.model}</span>
          <span className="raw-context-stats">
            {messages.length} 条消息 · {turnCount} 个 Turn
            {roundCount > 0 && ` · ${roundCount} 轮工具往返`}
            {tools && ` · ${tools.length} 个工具`}
          </span>
        </div>
        <div className="raw-context-actions">
          <button type="button" className="raw-context-action" onClick={() => bust(true)}>
            <AppIcon name="chevron-down" size={16} />展开全部
          </button>
          <button type="button" className="raw-context-action" onClick={() => bust(false)}>
            <AppIcon name="chevron-right" size={16} />折叠全部
          </button>
          <button type="button" className="raw-context-action raw-context-copy" onClick={() => void copy()}>
            <AppIcon name={copied ? 'check' : 'restore'} size={16} />{copied ? '已复制' : '复制 JSON'}
          </button>
        </div>
      </div>

      <div key={gen} className="raw-context-scroll scrollbar-slim">
          <section className="raw-context-section" aria-label="上下文消息">
            <div className="raw-context-section-heading"><h3>上下文消息</h3><span>messages · 按会话轮次排列</span></div>
            <div className="raw-message-list">
              {items.map((it, i) =>
                it.type === 'turn' ? (
                  <TurnBlock
                    key={i}
                    index={it.index}
                    source={it.span.source}
                    replyMessageId={it.span.replyMessageId}
                    sessionId={sessionId}
                    messages={it.msgs}
                    turnOpen={all === true}
                    msgOpen={msgOpen}
                    nodeDepth={nodeDepth}
                  />
                ) : (
                  <div key={i} className="raw-message-list">
                    {it.msgs.map((m, j) => (
                      <MessageCard key={j} msg={m} defaultOpen={msgOpen} openDepth={nodeDepth} />
                    ))}
                  </div>
                ),
              )}
              {messages.length === 0 && <p className="raw-context-empty">暂无消息</p>}
            </div>
          </section>

          <section className="raw-context-section" aria-label="请求参数">
            <div className="raw-context-section-heading"><h3>请求参数</h3><span>model 与其他顶层字段</span></div>
            <JsonViewer value={parameters} openDepth={nodeDepth} />
          </section>
          {tools && (
            <section className="raw-context-section" aria-label="工具定义">
              <div className="raw-context-section-heading"><h3>工具定义</h3><span>tools · {tools.length} 项</span></div>
              <JsonViewer value={tools} openDepth={all === true ? 999 : 0} />
            </section>
          )}
      </div>
    </div>
  )
}
