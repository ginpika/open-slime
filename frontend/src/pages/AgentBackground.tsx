/**
 * AgentBackground：**单个 Agent** 的「后台」——心跳 / 常驻职责 / 行动日志 / RAG 召回，内部子标签切换。
 *
 * <p>后台已从「全体共享」改为「每 Agent 一份」：每位智能体有自己的心跳、常驻职责名册、行动日志、
 * RAG 召回。全局的东西（观测台 / 工具一览 / 开发者选项提示词）不在这里，它们只在 {@link SystemPanel}。
 *
 * <p>本文件同时承载后台各处共用的展示零件（StatusDot / ViewTab / 各类格式化 / 执行详情浮窗），
 * 供 {@link SystemPanel} 的全局观测台复用。
 *
 * <p>各子标签按需挂载即拉取（懒加载）；写操作只有心跳开关/节拍、常驻职责的启用开关与「立即执行」。
 */

import { useCallback, useEffect, useState, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  ApiError,
  SUB_CHANNEL_LABELS,
  SUB_RAG_CHANNEL_LABELS,
  SUB_RAG_SOURCE_LABELS,
  agentBeat,
  agentClearRagRecalls,
  agentExec,
  agentHeartbeat,
  agentPlanExecs,
  agentPlans,
  agentPulse,
  agentRagRecalls,
  agentResources,
  agentRerunTask,
  agentRunPlan,
  agentSetPlanEnabled,
  agentStream,
  agentTasks,
  saveAgentHeartbeat,
  type AgentHeartbeat,
  type ExecKind,
  type ExecView,
  type ModelView,
  type PlanUsage,
  type PlanView,
  type RagRecallView,
  type ResourceView,
  type SubRagChannel,
  type TaskView,
  type Usage,
} from '../api/client'
import { Card } from '../components/Card'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { btnPrimary, btnQuiet, miniBtn } from '../components/ui'
import { usePageKit } from '../kit'

/** 表单控件基底：与设置页保持一致的输入框风格 */
const INPUT =
  'w-full rounded-md border border-line bg-bg px-3 py-2 text-base text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25'

/** 心跳控制条的节拍输入：透明底描边输入框（聚焦时点缀色描边） */
const DASH_INPUT =
  'w-[104px] rounded-lg border border-line bg-transparent px-3 py-1.5 font-mono text-base text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/30'

/** 心跳重连间隔（后端单条流有最长保持时长，断开属正常） */
const RECONNECT_MS = 1500

/** 资源视图的统计窗口（小时 + 中文标签） */
const WINDOWS: readonly (readonly [number, string])[] = [
  [24, '24 小时'],
  [24 * 7, '7 天'],
  [24 * 30, '30 天'],
]

/** Toast 回调：kind=success 走成功态，error 走错误态 */
export type Notify = (kind: 'success' | 'error', msg: string) => void

/* ---------------- 共用小工具 ---------------- */

/** 后排错误 → 可读文案：Spring 的错误体是 JSON，取出 message 后展示 */
function errMsg(e: unknown): string {
  if (e instanceof ApiError) {
    const raw = e.body?.trim()
    if (raw) {
      try {
        const j = JSON.parse(raw) as { message?: string }
        if (j.message) return j.message
      } catch {
        /* 非 JSON（如网关文本）：原样展示 */
      }
      return raw
    }
    return `请求失败（${e.status}）`
  }
  return e instanceof Error ? e.message : String(e)
}

function fmtTime(ms: number | null | undefined): string {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtDay(ms: number | null | undefined): string {
  if (!ms) return '—'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${fmtTime(ms)}`
}

/** 时长：毫秒 / 秒 / 分秒 */
function fmtMs(ms: number): string {
  if (!ms) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`
}

/** 用时：进行中 / 毫秒 / 秒 / 分秒 */
export function fmtDur(start: number, end: number | null): string {
  return end ? fmtMs(Math.max(0, end - start)) : '进行中'
}

/** 距今多久（列表里「最近一次」用，比绝对时刻好读） */
function fmtAgo(ms: number | null): string {
  if (!ms) return '尚未被派过'
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

/** 大数紧凑显示（token 估算、字符量等） */
function fmtNum(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}

/** 节拍标签：null=每次心跳都跑 */
function intervalLabel(ms: number | null): string {
  if (ms == null) return '每拍'
  if (ms % 60_000 === 0) return `${ms / 60_000} 分钟`
  if (ms % 1000 === 0) return `${ms / 1000} 秒`
  return `${ms}ms`
}

function jsonPretty(s: string | null): string {
  if (!s) return ''
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

export function textPreview(s: string | null, n: number): string {
  const t = (s ?? '').replace(/\s+/g, ' ').trim()
  return t.length > n ? `${t.slice(0, n)}…` : t
}

/** 成功率（窗口内 done / runs）；没跑过时不给百分比 */
function successRate(done: number, runs: number): string {
  if (runs <= 0) return '—'
  return `${Math.round((done / runs) * 100)}%`
}

/** 状态点：进行中=点缀色脉冲；完成 / 失败 / 中断用语义色令牌 */
export function StatusDot({ status }: { status: string }) {
  const tone =
    status === 'done'
      ? 'bg-ok'
      : status === 'failed'
        ? 'bg-danger'
        : status === 'interrupted'
          ? 'bg-warn'
          : 'animate-pulse bg-accent'
  return <span className={`h-2 w-2 shrink-0 rounded-full ${tone}`} />
}

/** 开关：心跳常驻——开=点缀色实底 + 滑块靠右，关=描边 + 中性底 */
function Switch({
  on,
  disabled,
  onToggle,
}: {
  on: boolean
  disabled?: boolean
  onToggle: (v: boolean) => void
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      disabled={disabled}
      onClick={() => onToggle(!on)}
      className={`relative h-6 w-11 shrink-0 rounded-full border transition disabled:opacity-40 ${
        on ? 'border-accent bg-accent' : 'border-line-strong bg-fg/10'
      }`}
    >
      <span
        className={`absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full transition-all ${
          on ? 'left-[21px] bg-accent-fg' : 'left-[1px] bg-fg/30'
        }`}
      />
    </button>
  )
}

/** 分段按钮的一段：顶部内部页滑块与浮窗内的 tab 共用（观感对齐 kit.Range 的分段带） */
export function ViewTab({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`mono px-3 py-1.5 text-xs font-bold tracking-wider transition hover:bg-accent-soft first:pl-3.5 last:pr-3.5 ${
        active ? 'bg-accent text-accent-fg' : 'text-muted hover:text-fg'
      }`}
    >
      {children}
    </button>
  )
}

/* ---------------- 执行详情（组装出的上下文 / 思维链 / 写库清单） ---------------- */

/** 执行种类 → 人话 */
function kindLabel(kind: ExecKind): string {
  return kind === 'heartbeat' ? '心跳主会话' : kind === 'plan' ? '常驻职责' : '子代理'
}

export function ResultDetail({ detail, onClose }: { detail: ExecView; onClose: () => void }) {
  const kit = usePageKit()
  const [tab, setTab] = useState<'out' | 'ctx' | 'trace' | 'edit'>('out')
  const writes = detail.edits
  return (
    <FloatWindow
      title={`${detail.title ?? '（未知）'} · ${kindLabel(detail.kind)} · ${detail.status} · ${fmtTime(detail.startedAt)} · ${fmtDur(detail.startedAt, detail.finishedAt)}`}
      onClose={onClose}
      width={760}
    >
      <div className="mb-3 flex w-fit border border-line-strong">
        {(
          [
            ['out', `结论${detail.output ? '' : '（空）'}`],
            ['ctx', '上下文'],
            ['trace', `过程${detail.trace.length ? ` ${detail.trace.length}` : ''}`],
            ['edit', `写库 ${writes.length}`],
          ] as const
        ).map(([k, label]) => (
          <ViewTab key={k} active={tab === k} onClick={() => setTab(k)}>
            {label}
          </ViewTab>
        ))}
      </div>

      {tab === 'out' && (
        <div className="text-base leading-relaxed text-fg">
          {detail.error && (
            <p className="mb-2 border-l-2 border-danger bg-fg/[0.03] px-2 py-1 text-sm text-muted">
              {detail.error}
            </p>
          )}
          {detail.output ? (
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{detail.output}</ReactMarkdown>
          ) : (
            <kit.Empty>本次未产出结论文本</kit.Empty>
          )}
        </div>
      )}

      {tab === 'ctx' && (
        <div className="flex flex-col gap-3">
          <p className="text-xs leading-relaxed text-hint">
            这是执行前组装出、直接交付给 LLM 的上下文（消息数组）。LLM 调用本身无状态，
            状态化的正是这份「模板 + 存储层数据」的构造结果。
          </p>
          <pre className="scrollbar-slim max-h-[52vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-xs leading-5 text-muted">
            {jsonPretty(detail.context) || '（未留存上下文）'}
          </pre>
        </div>
      )}

      {tab === 'trace' && (
        <div className="flex flex-col gap-2">
          {detail.trace.length === 0 && <kit.Empty>无过程记录</kit.Empty>}
          {detail.trace.map((s, i) => (
            <div key={i} className="rounded-md border border-line">
              <div className="flex items-center gap-2 border-b border-line bg-fg/[0.03] px-2.5 py-1.5">
                <span className="mono text-2xs font-bold tracking-wide text-faint">
                  {s.type === 'tool' ? '工具调用' : '思考'}
                </span>
                {s.name && <span className="mono text-xs font-bold text-fg">{s.name}</span>}
              </div>
              <pre className="scrollbar-slim max-h-[40vh] overflow-auto p-2.5 font-mono text-xs leading-5 whitespace-pre-wrap text-muted">
                {s.args ? jsonPretty(s.args) : (s.text ?? '')}
              </pre>
            </div>
          ))}
          {detail.reasoning && (
            <details className="rounded-md border border-line">
              <summary className="cursor-pointer px-2.5 py-1.5 text-xs text-faint">
                完整思维链
              </summary>
              <pre className="scrollbar-slim max-h-[40vh] overflow-auto border-t border-line p-2.5 font-mono text-xs leading-5 whitespace-pre-wrap text-muted">
                {detail.reasoning}
              </pre>
            </details>
          )}
        </div>
      )}

      {tab === 'edit' && (
        <div className="flex flex-col gap-2">
          {writes.length === 0 && <kit.Empty>本次未写库</kit.Empty>}
          {writes.map((w) => (
            <div key={w.id} className="rounded-md border border-line">
              <div className="flex items-center justify-between gap-2 border-b border-line bg-fg/[0.03] px-2.5 py-1.5">
                <span className="mono text-xs font-bold text-fg">{w.tool}</span>
                <span className="mono text-2xs text-dim">{fmtTime(w.createdAt)}</span>
              </div>
              <pre className="scrollbar-slim max-h-[28vh] overflow-auto p-2.5 font-mono text-xs leading-5 whitespace-pre-wrap text-muted">
                {jsonPretty(w.args)}
              </pre>
              {w.summary && (
                <p className="border-t border-line px-2.5 py-1.5 text-xs text-faint">
                  {w.summary}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </FloatWindow>
  )
}

/* ---------------- 常驻职责 / 子代理 / 任务 的零件 ---------------- */

/** 任务种类 → 人话：心跳主会话与常驻职责同属「职责」，子代理是匿名 worker，IDE 为后续预留 */
const TASK_KIND_LABELS: Record<string, string> = {
  session: '会话',
  subagent: '子代理',
  plan: '职责',
  code: 'IDE',
}

/** 一条行动日志：状态、id、种类、标题、父子链、耗时、时刻；点开看该次执行全过程，中断的子代理可重跑 */
function TaskLine({
  task,
  onOpenExec,
  onRerun,
}: {
  task: TaskView
  onOpenExec: (id: number) => void
  onRerun?: () => void
}) {
  const kit = usePageKit()
  const execId = task.execId
  /* 耗时：跑完就以 updatedAt 为终点；还在跑显示「进行中」 */
  const ended = task.status === 'running' ? null : task.updatedAt
  return (
    <kit.Row
      onClick={execId == null ? undefined : () => onOpenExec(execId)}
      className="flex-wrap gap-x-3 gap-y-1"
    >
      <StatusDot status={task.status} />
      <span className="mono shrink-0 text-2xs text-dim">#{task.id}</span>
      <kit.Badge>{TASK_KIND_LABELS[task.kind] ?? task.kind}</kit.Badge>
      <span className="max-w-[240px] truncate text-sm font-medium text-fg">
        {task.title ?? '（未命名）'}
      </span>
      {task.parentId != null && (
        <span className="mono shrink-0 text-2xs text-dim">↳ 由 #{task.parentId} 派出</span>
      )}
      <span className="mono shrink-0 text-2xs text-dim">{fmtDur(task.createdAt, ended)}</span>
      <span className="mono ml-auto shrink-0 text-2xs text-dim">{fmtDay(task.createdAt)}</span>
      {onRerun && (
        <button
          type="button"
          className={miniBtn}
          onClick={(e) => {
            /* 行本身点了是看详情；重跑按钮只重跑，不连带打开详情 */
            e.stopPropagation()
            onRerun()
          }}
        >
          重跑
        </button>
      )}
    </kit.Row>
  )
}

/** 一条执行记录：状态、时刻、耗时、结论摘要，点开看全过程（中断的可重跑）。 */
function ExecLine({
  exec,
  showTitle,
  onClick,
  onRerun,
}: {
  exec: ExecView
  showTitle?: boolean
  onClick: () => void
  onRerun?: () => void
}) {
  const kit = usePageKit()
  return (
    <kit.Row onClick={onClick} className="flex-wrap gap-x-3 gap-y-1">
      <StatusDot status={exec.status} />
      {showTitle && (
        <span className="max-w-[220px] truncate text-sm font-medium text-fg">
          {exec.title ?? '（未命名子代理）'}
        </span>
      )}
      <span className="mono shrink-0 text-2xs text-dim">{fmtDay(exec.startedAt)}</span>
      <span className="mono shrink-0 text-2xs text-dim">{fmtDur(exec.startedAt, exec.finishedAt)}</span>
      {exec.edits.length > 0 && <kit.Badge>写库 {exec.edits.length}</kit.Badge>}
      <span className="min-w-[160px] flex-1 truncate text-xs text-muted">
        {textPreview(exec.error ?? exec.output, 80) || '（无结论）'}
      </span>
      {onRerun && (
        <button
          type="button"
          className={miniBtn}
          onClick={(e) => {
            /* 行本身点了是看详情；重跑按钮只重跑，不连带打开详情 */
            e.stopPropagation()
            onRerun()
          }}
        >
          重跑
        </button>
      )}
    </kit.Row>
  )
}

/**
 * 一份常驻职责：岗位定义（名字/正文/工具/渠道/节拍参考）+ 它在本 Agent 窗口内的用量，
 * 展开是「它被派出过哪几次」。启用开关是人踩的刹车，「立即执行」当场单跑验证。
 */
function PlanRow({
  plan,
  usage,
  busy,
  onRun,
  onLoadExecs,
  onOpenExec,
  onToggle,
  onToast,
}: {
  plan: PlanView
  usage: PlanUsage | undefined
  busy: boolean
  onRun: (p: PlanView) => void
  /** 拉取这份职责最近若干次被派出的记录（闭包持有 peerId） */
  onLoadExecs: (planId: string) => Promise<ExecView[]>
  onOpenExec: (id: number) => void
  onToggle: (p: PlanView) => void
  onToast: Notify
}) {
  const kit = usePageKit()
  const [open, setOpen] = useState(false)
  const [history, setHistory] = useState<ExecView[] | null>(null)
  const u = usage

  /** 每次展开都重拉：历史与统计窗口无关（后端给的是最近若干次） */
  const toggle = async () => {
    const next = !open
    setOpen(next)
    if (!next) return
    setHistory(null)
    try {
      setHistory(await onLoadExecs(plan.id))
    } catch (e) {
      onToast('error', errMsg(e))
    }
  }

  return (
    <Card as="li" pad="none">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3 px-4 py-3.5">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate text-md font-semibold text-fg">{plan.name}</span>
            {plan.channels.map((c) => (
              <kit.Badge key={c}>{(SUB_CHANNEL_LABELS as Record<string, string>)[c] ?? c}</kit.Badge>
            ))}
            <kit.Badge>{intervalLabel(plan.intervalMs)}</kit.Badge>
          </div>
          <p
            className={`mt-1 line-clamp-2 text-xs leading-relaxed ${
              plan.enabled ? 'text-muted' : 'text-dim'
            }`}
          >
            {textPreview(plan.content, 110) || '（无正文）'}
          </p>
          {plan.rationale && (
            <p className="mt-1 text-2xs leading-relaxed text-hint">
              由来 · {textPreview(plan.rationale, 140)}
            </p>
          )}
          <p className="mono mt-1.5 text-2xs text-dim">
            工具 {plan.tools.length ? plan.tools.join('、') : '无'}
            <span className="mx-1.5">·</span>
            最近派出 {fmtAgo(plan.lastBeatAt)}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <label className="flex items-center gap-1.5 text-2xs text-muted">
            <Switch on={plan.enabled} disabled={busy} onToggle={() => onToggle(plan)} />
            {plan.enabled ? '启用' : '已停用'}
          </label>
          <div className="mono flex flex-col items-end gap-0.5 text-2xs text-dim">
            <span className="text-sm font-bold text-fg">
              {u?.runs ?? 0}
              <span className="ml-1 font-normal text-dim">次</span>
            </span>
            <span>
              成功 {successRate(u?.done ?? 0, u?.runs ?? 0)}
              {u?.failed ? ` · 失败 ${u.failed}` : ''}
            </span>
          </div>
          <button type="button" className={miniBtn} disabled={busy} onClick={() => onRun(plan)}>
            立即执行
          </button>
          <button type="button" className={miniBtn} onClick={toggle}>
            {open ? '收起' : '记录'}
          </button>
        </div>
      </div>

      {open && (
        <div className="border-t border-line px-4 py-3">
          <div className="mono flex flex-wrap gap-x-6 gap-y-1.5 text-2xs text-dim">
            <span>
              平均耗时 <span className="font-bold text-fg">{fmtMs(u?.avgDurationMs ?? 0)}</span>
            </span>
            <span>
              最长耗时 <span className="font-bold text-fg">{fmtMs(u?.maxDurationMs ?? 0)}</span>
            </span>
            <span>
              工具往返 <span className="font-bold text-fg">{u?.toolRounds ?? 0}</span> 次
            </span>
            <span>
              ≈ <span className="font-bold text-fg">{fmtNum(u?.estTokens ?? 0)}</span> tokens
              <span className="ml-1">（按上下文字符量粗估）</span>
            </span>
            <span>
              窗口内最近一次{' '}
              <span className="font-bold text-fg">{u?.lastRunAt ? fmtDay(u.lastRunAt) : '—'}</span>
            </span>
          </div>

          <div className="mt-3 flex flex-col gap-1.5">
            <span className="ui-label">最近执行</span>
            {history === null && <p className="text-xs text-muted">载入中…</p>}
            {history?.length === 0 && <p className="text-xs text-muted">还没有被派出过</p>}
            {history && history.length > 0 && (
              <div className="flex flex-col">
                {history.map((r) => (
                  <ExecLine key={r.id} exec={r} onClick={() => onOpenExec(r.id)} />
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </Card>
  )
}

/** 资源用量四件套：次数、成功率、平均耗时、工具往返 */
function UsageCards({ usage, unitHint }: { usage: Usage | undefined; unitHint: string }) {
  const kit = usePageKit()
  const runs = usage?.runs ?? 0
  const done = usage?.done ?? 0
  const failed = usage?.failed ?? 0
  return (
    <div className={kit.statRow}>
      <kit.Stat label={unitHint} value={String(runs)} hint={`成功 ${done} · 失败 ${failed}`} />
      <kit.Stat
        label="成功率"
        value={successRate(done, runs)}
        hint={done > 0 ? `${done}/${runs}` : '窗口内还没有跑过'}
        tone={failed > 0 ? 'default' : 'accent'}
      />
      <kit.Stat
        label="平均耗时"
        value={fmtMs(usage?.avgDurationMs ?? 0)}
        hint={`最长 ${fmtMs(usage?.maxDurationMs ?? 0)}`}
      />
      <kit.Stat
        label="工具往返"
        value={String(usage?.toolRounds ?? 0)}
        hint={`≈ ${fmtNum(usage?.estTokens ?? 0)} tokens`}
      />
    </div>
  )
}

/* ---------------- 子页：心跳 ---------------- */

function HeartbeatTab({
  peerId,
  peerName,
  models,
  onToast,
}: {
  peerId: string
  peerName: string
  models: ModelView[]
  onToast: Notify
}) {
  const kit = usePageKit()
  const [hb, setHb] = useState<AgentHeartbeat | null>(null)
  const [content, setContent] = useState('')
  const [modelId, setModelId] = useState('')
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState(false)
  const [tickDraft, setTickDraft] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  const [live, setLive] = useState(false)

  useEffect(() => {
    let alive = true
    agentHeartbeat(peerId)
      .then((h) => {
        if (!alive) return
        setHb(h)
        setContent(h.content)
        setModelId(h.modelId ?? '')
        setReady(true)
      })
      .catch((e: unknown) => {
        if (alive) onToast('error', errMsg(e))
      })
    agentPulse(peerId)
      .then((p) => {
        if (alive) setRunning(p.heartbeats.find((s) => s.agentId === peerId)?.running ?? false)
      })
      .catch(() => {
        /* 观测台拉不到不影响配置编辑 */
      })
    return () => {
      alive = false
    }
  }, [peerId, onToast])

  /* 观测台实时流：循环重连（单条流有时长上限，断开属正常），据它刷新运行状态与最近敲响时刻 */
  useEffect(() => {
    const ac = new AbortController()
    let stopped = false
    const pump = async () => {
      while (!stopped) {
        try {
          await agentStream(
            peerId,
            (p) => {
              const s = p.heartbeats.find((h) => h.agentId === peerId)
              if (!s) return
              setLive(true)
              setRunning(s.running)
              setHb((prev) =>
                prev ? { ...prev, enabled: s.enabled, tickMs: s.tickMs, lastBeatAt: s.lastBeatAt } : prev,
              )
            },
            ac.signal,
          )
        } catch {
          /* 断流 / 后端重启：落到下方重连 */
        }
        if (stopped) break
        setLive(false)
        await new Promise((r) => setTimeout(r, RECONNECT_MS))
      }
    }
    void pump()
    return () => {
      stopped = true
      ac.abort()
    }
  }, [peerId])

  const tickValue = tickDraft ?? (hb ? String(hb.tickMs) : '')
  const enabled = hb?.enabled ?? false

  const saveContent = async () => {
    if (!content.trim()) return onToast('error', '正文不能为空')
    setBusy(true)
    try {
      const h = await saveAgentHeartbeat(peerId, { content, modelId: modelId || null })
      setHb(h)
      setContent(h.content)
      setModelId(h.modelId ?? '')
      onToast('success', '心跳已保存')
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const toggle = async (on: boolean) => {
    try {
      setHb(await saveAgentHeartbeat(peerId, { enabled: on }))
      onToast('success', on ? '心跳已常驻' : '心跳已停摆')
    } catch (e) {
      onToast('error', errMsg(e))
    }
  }

  const saveTick = async () => {
    const n = Number(tickValue)
    if (!Number.isFinite(n) || n <= 0) return onToast('error', '节拍需为正整数毫秒')
    try {
      setHb(await saveAgentHeartbeat(peerId, { tickMs: Math.round(n) }))
      setTickDraft(null)
      onToast('success', `节拍已改为 ${Math.round(n)}ms`)
    } catch (e) {
      onToast('error', errMsg(e))
    }
  }

  const beat = async () => {
    setBusy(true)
    try {
      const r = await agentBeat(peerId)
      onToast(
        r.started ? 'success' : 'error',
        r.started
          ? `第 ${r.beat} 拍已敲响：主会话发起 ${r.agents} 次执行`
          : '已有一轮在跑，本次跳过',
      )
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  if (!ready) return <kit.Empty>正在读取「{peerName}」的心跳…</kit.Empty>

  return (
    <div className="flex flex-col gap-4">
      <section className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3 border border-line px-4 py-3">
        <div className="flex min-w-0 items-start gap-3">
          <span
            className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${
              running ? 'animate-pulse bg-accent' : enabled ? 'bg-accent' : 'bg-fg/30'
            }`}
          />
          <div className="min-w-0">
            <p className="text-sm font-medium text-fg">「{peerName}」的心跳</p>
            <p className="mono mt-0.5 text-2xs text-dim">
              {running ? '本轮心跳执行中' : enabled ? '常驻心跳待命' : '心跳已停摆'}
              <span className="mx-1.5">·</span>节拍 {hb?.tickMs ?? '—'}ms
              <span className="mx-1.5">·</span>最近 {fmtDay(hb?.lastBeatAt)}
              <span className="mx-1.5">·</span>
              {live ? '观测台已连' : '观测台重连中…'}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2 pr-1 text-base text-muted">
            <Switch on={enabled} onToggle={toggle} />
            常驻心跳
          </label>
          <input
            className={DASH_INPUT}
            value={tickValue}
            onChange={(e) => setTickDraft(e.target.value)}
            aria-label="节拍（毫秒）"
          />
          <button type="button" className={btnQuiet()} onClick={saveTick}>
            改节拍
          </button>
          <button type="button" className={btnPrimary()} disabled={busy} onClick={beat}>
            敲一次
          </button>
        </div>
      </section>

      <kit.Field
        label="提示词"
        hint="这位智能体心跳主会话的那份正文：每拍由它先出场，看着常驻职责名册决定派谁。工具不在这里配。"
        wrap={false}
      >
        <textarea
          className={`${INPUT} scrollbar-slim min-h-[320px] font-mono text-sm leading-6`}
          value={content}
          onChange={(e) => setContent(e.target.value)}
        />
      </kit.Field>

      <div className="max-w-[260px]">
        <kit.Field label="模型" wrap={false}>
          <select className={INPUT} value={modelId} onChange={(e) => setModelId(e.target.value)}>
            <option value="">跟随默认模型</option>
            {models.map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
          </select>
        </kit.Field>
      </div>

      <div className="flex justify-end border-t border-line pt-3">
        <button type="button" className={btnPrimary('rounded-sm')} disabled={busy} onClick={saveContent}>
          {busy ? '保存中…' : '保存'}
        </button>
      </div>
    </div>
  )
}

/* ---------------- 子页：常驻职责 ---------------- */

function PlansTab({
  peerId,
  onOpenExec,
  onToast,
}: {
  peerId: string
  onOpenExec: (id: number) => void
  onToast: Notify
}) {
  const kit = usePageKit()
  const [plans, setPlans] = useState<PlanView[]>([])
  const [resources, setResources] = useState<ResourceView | null>(null)
  const [hours, setHours] = useState(24)
  const [busy, setBusy] = useState(false)

  const loadPlans = useCallback(async () => {
    try {
      setPlans(await agentPlans(peerId))
    } catch (e) {
      onToast('error', errMsg(e))
    }
  }, [peerId, onToast])

  const loadResources = useCallback(
    async (h: number) => {
      try {
        setResources(await agentResources(peerId, h))
      } catch (e) {
        onToast('error', errMsg(e))
      }
    },
    [peerId, onToast],
  )

  useEffect(() => {
    void loadPlans()
  }, [loadPlans])

  useEffect(() => {
    void loadResources(hours)
  }, [hours, loadResources])

  const runPlan = async (p: PlanView) => {
    setBusy(true)
    try {
      const r = await agentRunPlan(peerId, p.id)
      onToast(r.started ? 'success' : 'error', r.started ? `已单独派出「${p.name}」` : '已有一轮在跑，本次跳过')
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
      void loadPlans()
      void loadResources(hours)
    }
  }

  const togglePlan = async (p: PlanView) => {
    setBusy(true)
    try {
      await agentSetPlanEnabled(peerId, p.id, !p.enabled)
      onToast('success', p.enabled ? `已停用「${p.name}」` : `已启用「${p.name}」`)
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
      void loadPlans()
      void loadResources(hours)
    }
  }

  const windowLabel = WINDOWS.find((w) => w[0] === hours)?.[1] ?? ''
  const usageOf = (planId: string) => resources?.planUsage.find((u) => u.planId === planId)

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <p className="max-w-[720px] text-xs leading-relaxed text-muted">
          常驻的岗位定义：一份带名字、正文、工具与交付渠道的职责。节拍只是给主会话的参考，派不派由本
          Agent 的心跳主会话每拍裁量；立、改、撤都是大模型在对话里用
          <span className="mx-1 font-mono text-xs">create_heartbeat / update_heartbeat / delete_heartbeat</span>
          做的事，页面上唯一能改的是每行的启用开关（停用不删）。
        </p>
        <kit.Range
          value={hours}
          options={WINDOWS.map(([v, label]) => ({ value: v, label }))}
          onChange={setHours}
        />
      </header>

      <UsageCards usage={resources?.plans} unitHint={`被派出（${windowLabel}）`} />

      {plans.length === 0 ? (
        <kit.Empty>
          这位智能体的名册还是空的 —— 去对话里说一句「以后帮我盯着 X」，大模型就会用
          <span className="mx-1 font-mono">create_heartbeat</span>
          为它立一份。
        </kit.Empty>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {plans.map((p) => (
            <PlanRow
              key={p.id}
              plan={p}
              usage={usageOf(p.id)}
              busy={busy}
              onRun={runPlan}
              onLoadExecs={(planId) => agentPlanExecs(peerId, planId)}
              onOpenExec={onOpenExec}
              onToggle={togglePlan}
              onToast={onToast}
            />
          ))}
        </ul>
      )}
    </div>
  )
}

/* ---------------- 子页：行动日志 ---------------- */

/**
 * 行动日志（合并原「子代理」与「任务」两页）：这位智能体的每一次执行——
 * 心跳主会话登场、常驻职责被派出、子代理被交代——都以一条任务行呈现（身份、状态、父子链、耗时）；
 * 点开看该次执行的全过程（上下文 / 思考链 / 写库），进程重启打断的子代理可当场重跑。
 */
function ActionLogTab({
  peerId,
  onOpenExec,
  onToast,
}: {
  peerId: string
  onOpenExec: (id: number) => void
  onToast: Notify
}) {
  const kit = usePageKit()
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [resources, setResources] = useState<ResourceView | null>(null)
  const [hours, setHours] = useState(24)
  const [busy, setBusy] = useState(false)

  const loadTasks = useCallback(async () => {
    try {
      setTasks(await agentTasks(peerId))
    } catch (e) {
      onToast('error', errMsg(e))
    }
  }, [peerId, onToast])

  const loadResources = useCallback(
    async (h: number) => {
      try {
        setResources(await agentResources(peerId, h))
      } catch (e) {
        onToast('error', errMsg(e))
      }
    },
    [peerId, onToast],
  )

  useEffect(() => {
    void loadTasks()
  }, [loadTasks])

  useEffect(() => {
    void loadResources(hours)
  }, [hours, loadResources])

  const rerun = async (t: TaskView) => {
    setBusy(true)
    try {
      await agentRerunTask(peerId, t.id)
      onToast('success', `已重新派出 #${t.id}「${t.title ?? '任务'}」`)
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
      void loadTasks()
      void loadResources(hours)
    }
  }

  const windowLabel = WINDOWS.find((w) => w[0] === hours)?.[1] ?? ''

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <p className="max-w-[720px] text-xs leading-relaxed text-muted">
          这位智能体每一次行动的日志：心跳主会话登场、常驻职责被派出、子代理被交代，各有身份与状态；
          <span className="mono mx-1 text-xs">↳ 由 #N 派出</span>
          标出它属于谁的子任务。有执行留痕的点开可看全过程。
        </p>
        <kit.Range
          value={hours}
          options={WINDOWS.map(([v, label]) => ({ value: v, label }))}
          onChange={setHours}
        />
      </header>

      <UsageCards usage={resources?.totals} unitHint={`执行（${windowLabel}）`} />

      {tasks.length === 0 ? (
        <kit.Empty>
          还没有行动 —— 敲一次心跳、或去对话里派一个子代理，这里就会出现它们的身份与状态。
        </kit.Empty>
      ) : (
        <div className="flex flex-col">
          {tasks.map((t) => (
            <TaskLine
              key={t.id}
              task={t}
              onOpenExec={onOpenExec}
              onRerun={
                t.kind === 'subagent' && t.status === 'interrupted' ? () => void rerun(t) : undefined
              }
            />
          ))}
        </div>
      )}
      {busy && <p className="text-2xs text-hint">处理中…</p>}
    </div>
  )
}

/* ---------------- 子页：RAG 召回 ---------------- */

/** 三路各自一个徽章色调（向量=点缀 / 图=成功 / 时序=警示），列表里一眼可辨 */
const RAG_CHANNEL_TONE: Record<SubRagChannel, 'accent' | 'ok' | 'warn'> = {
  vector: 'accent',
  graph: 'ok',
  memory: 'warn',
}

/** 一条召回留痕的详情：查询原文 + 回给模型的 tool 消息原文 */
function RagRecallDetail({ row, onClose }: { row: RagRecallView; onClose: () => void }) {
  return (
    <FloatWindow
      title={`${row.tool} · ${SUB_RAG_CHANNEL_LABELS[row.channel] ?? row.channel} · ${
        SUB_RAG_SOURCE_LABELS[row.source] ?? row.source
      } · ${fmtDay(row.createdAt)}`}
      onClose={onClose}
      width={760}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <p className="ui-label">查询参数</p>
          <pre className="scrollbar-slim max-h-[20vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-xs leading-5 whitespace-pre-wrap text-muted">
            {row.query ? jsonPretty(row.query) : '（无参数）'}
          </pre>
        </div>
        <div className="flex flex-col gap-1.5">
          <p className="ui-label">
            回给模型的原始数据
            <span className="mono ml-2 font-normal tracking-normal text-hint">
              共 {fmtNum(row.resultChars)} 字（大模型调用该工具实际拿到的内容）
            </span>
          </p>
          <pre className="scrollbar-slim max-h-[52vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-xs leading-5 whitespace-pre-wrap text-muted">
            {row.result || '（无返回）'}
          </pre>
        </div>
      </div>
    </FloatWindow>
  )
}

function RagTab({ peerId, onToast }: { peerId: string; onToast: Notify }) {
  const kit = usePageKit()
  const [channel, setChannel] = useState<SubRagChannel | null>(null)
  const [rows, setRows] = useState<RagRecallView[] | null>(null)
  const [active, setActive] = useState<RagRecallView | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(
    async (c: SubRagChannel | null) => {
      try {
        setRows(await agentRagRecalls(peerId, 150, c))
      } catch (e) {
        onToast('error', errMsg(e))
      }
    },
    [peerId, onToast],
  )

  useEffect(() => {
    void load(channel)
  }, [channel, load])

  const clear = async () => {
    setBusy(true)
    try {
      const r = await agentClearRagRecalls(peerId)
      onToast('success', `已清空 ${r.removed} 条召回留痕`)
      await load(channel)
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const FILTERS: readonly (readonly [SubRagChannel | null, string])[] = [
    [null, '全部'],
    ['vector', SUB_RAG_CHANNEL_LABELS.vector],
    ['graph', SUB_RAG_CHANNEL_LABELS.graph],
    ['memory', SUB_RAG_CHANNEL_LABELS.memory],
  ]

  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <p className="max-w-[720px] text-xs leading-relaxed text-muted">
          这位智能体三路召回（向量 / 图 / 记忆）的留痕：既含每次检索工具调用，也含每轮
          <b>自动注入的记忆召回</b>——模型问了什么、实际拿到了什么原始数据，都原样记在这里，用来回看检索效果。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <div className="mono flex w-fit shrink-0 border border-line-strong">
            {FILTERS.map(([v, label]) => (
              <button
                key={v ?? 'all'}
                type="button"
                onClick={() => setChannel(v)}
                className={`px-3 py-1.5 text-xs font-bold tracking-wider transition hover:bg-accent-soft first:pl-3.5 last:pr-3.5 ${
                  channel === v ? 'bg-accent text-accent-fg' : 'text-muted hover:text-fg'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <button type="button" className={btnQuiet('rounded-sm')} onClick={() => void load(channel)}>
            刷新
          </button>
          <button type="button" className={btnQuiet('rounded-sm')} disabled={busy} onClick={clear}>
            清空
          </button>
        </div>
      </header>

      {rows === null ? (
        <kit.Empty>正在读取召回留痕…</kit.Empty>
      ) : rows.length === 0 ? (
        <kit.Empty>
          还没有召回记录 —— 当这位智能体的对话或后台执行调用检索工具（
          <span className="mx-1 font-mono">search_vector_chunks</span>/
          <span className="mx-1 font-mono">recall_memory</span>/ 图谱查询）时，或每轮
          <span className="mx-1 font-mono">memory_inject</span>自动注入记忆时，
          这里会记下它问了什么、拿到了什么。
        </kit.Empty>
      ) : (
        <ul className="flex flex-col gap-2">
          {rows.map((r) => (
            <li key={r.id}>
              <button
                type="button"
                onClick={() => setActive(r)}
                className="flex w-full flex-col gap-1.5 border border-line px-4 py-3 text-left transition hover:border-line-strong hover:bg-accent-soft"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <kit.Badge tone={RAG_CHANNEL_TONE[r.channel]}>
                    {SUB_RAG_CHANNEL_LABELS[r.channel] ?? r.channel}
                  </kit.Badge>
                  <span className="mono text-xs font-bold text-fg">{r.tool}</span>
                  <kit.Badge>{SUB_RAG_SOURCE_LABELS[r.source] ?? r.source}</kit.Badge>
                  <span className="mono text-2xs text-dim">{fmtDay(r.createdAt)}</span>
                  <span className="mono ml-auto shrink-0 text-2xs text-dim">
                    {fmtNum(r.resultChars)} 字
                  </span>
                </div>
                <p className="text-xs text-muted">
                  <span className="text-faint">查询 </span>
                  <span className="font-mono">{textPreview(r.query, 120) || '—'}</span>
                </p>
                <p className="line-clamp-2 text-xs leading-relaxed text-muted">
                  {textPreview(r.result, 200) || '（无返回）'}
                </p>
              </button>
            </li>
          ))}
        </ul>
      )}

      {active && <RagRecallDetail row={active} onClose={() => setActive(null)} />}
    </div>
  )
}

/* ---------------- 页面 ---------------- */

export type SubView = 'heartbeat' | 'plans' | 'actions' | 'rag'

export const SUB_TABS: { key: SubView; label: string }[] = [
  { key: 'heartbeat', label: '心跳' },
  { key: 'plans', label: '常驻职责' },
  { key: 'actions', label: '行动日志' },
  { key: 'rag', label: 'RAG 召回' },
]

/**
 * 一位智能体的「后台」：内部子标签切换心跳 / 常驻职责 / 行动日志 / RAG 召回。
 * 各子页按需挂载即拉取；执行详情浮窗与 Toast 收在这里共用。
 */
export function AgentBackground({
  peerId,
  peerName,
  models,
  view: controlledView,
  hideTabs = false,
}: {
  peerId: string
  peerName: string
  models: ModelView[]
  /** 受控子视图：外层把它提到同级 Tab 时传入（此时用这个值，不再用内部 state） */
  view?: SubView
  /** 隐藏内部子 Tab 栏：外层已有同级 Tab 栏时用（见 AgentDetail） */
  hideTabs?: boolean
}) {
  const [innerView, setInnerView] = useState<SubView>('heartbeat')
  const view = controlledView ?? innerView
  const [detail, setDetail] = useState<ExecView | null>(null)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)

  const notify = useCallback<Notify>((kind, msg) => setToast({ kind, msg }), [])

  const openExec = useCallback(
    async (id: number) => {
      try {
        setDetail(await agentExec(peerId, id))
      } catch (e) {
        notify('error', errMsg(e))
      }
    },
    [peerId, notify],
  )

  return (
    <div className="flex flex-col gap-4">
      {!hideTabs && (
        <div className="flex w-fit shrink-0 flex-wrap border border-line-strong">
          {SUB_TABS.map((t) => (
            <ViewTab key={t.key} active={view === t.key} onClick={() => setInnerView(t.key)}>
              {t.label}
            </ViewTab>
          ))}
        </div>
      )}

      {view === 'heartbeat' && (
        <HeartbeatTab peerId={peerId} peerName={peerName} models={models} onToast={notify} />
      )}
      {view === 'plans' && <PlansTab peerId={peerId} onOpenExec={openExec} onToast={notify} />}
      {view === 'actions' && <ActionLogTab peerId={peerId} onOpenExec={openExec} onToast={notify} />}
      {view === 'rag' && <RagTab peerId={peerId} onToast={notify} />}

      {detail && <ResultDetail detail={detail} onClose={() => setDetail(null)} />}

      <Toast message={toast?.msg ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    </div>
  )
}

export default AgentBackground
