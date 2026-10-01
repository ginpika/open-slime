/**
 * 潜意识页：心跳主会话驱动「子代理 / 任务计划」的框架，顶部滑块切换七个内部页。
 *
 * dashboard（原页面）：常驻心跳的开关与节拍、手动敲一次、最近事件的长条（每批一条，条内内联
 * 本批的各次执行，点开看组装出的上下文 / 思维链 / 过程分段 / 写库清单）。执行要「对人说话」时用
 * open_thread / send_message 工具往首页 chat 的会话投递，正文会出现在首页那个对话窗口里。
 * heartbeat：心跳主会话自己的那份上下文（总线）。每拍由它先出场，看着编制名册决定派谁、怎么派。
 * prompts：提示词 & 上下文工程——写死在代码里的提示词（场景 → 提示词位）与它们的用户版本，选定版本即覆盖内置。
 * agents：子代理 & 任务计划——两个层次的编制各被用得怎么样。子代理是匿名 worker：当场交代即跑、
 * 不建档案、跑完即逝；任务计划是常驻编制：一份带名字/正文/工具/渠道的岗位定义，由主会话按需派出、
 * 可反复用。立改撤都是大模型在对话里用工具做的事，这一页只读，回答「它们被用得怎么样」。
 * rag：RAG 召回——三路召回（向量 / 图 / 时序事件）每一次工具调用的留痕（查询 + 回给模型的原始数据），
 * 回看检索效果；对话 / 智能体群聊 / 心跳 / 子代理里的召回都记在这里，来源标明。
 * injectors：只读词典——每个可绑定的注入器各自取什么、参数怎么给、从哪儿取。
 * tools：只读词典——系统里每个工具叫什么、干什么、长在哪些工具面上、参数怎么给。
 *
 * 实时性靠 GET /api/subconscious/stream：后端只在快照有变化时才推 pulse，单条流有最长保持时长，
 * 故此处循环重连；断流只影响实时性，页面数据仍可由各动作的返回值刷新。
 */

import { useCallback, useEffect, useState, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  ApiError,
  getSettings,
  SUB_CHANNEL_LABELS,
  SUB_RAG_CHANNEL_LABELS,
  SUB_RAG_SOURCE_LABELS,
  subAgentRuns,
  subBeat,
  subClearRagRecalls,
  subExec,
  subHeartbeat,
  subHeartbeatContext,
  subInjectorGuide,
  subPlanExecs,
  subPlans,
  subRagRecalls,
  subResources,
  subRunPlan,
  subSaveHeartbeatContext,
  subState,
  subStream,
  subTools,
  type InjectorBinding,
  type InjectorGuide,
  type ModelView,
  type SubExec,
  type SubExecKind,
  type SubHeartbeatContext,
  type SubPlan,
  type SubPlanUsage,
  type SubRagChannel,
  type SubRagRecall,
  type SubResources,
  type SubRunBundle,
  type SubState,
  type SubTool,
  type SubUsage,
} from '../api/client'
import { Card } from '../components/Card'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { btnPrimary, btnQuiet, miniBtn } from '../components/ui'
import { usePageKit } from '../kit'
import PromptsPanel from './PromptsPanel'

/** 表单控件基底：与设置页保持一致的输入框风格 */
const INPUT =
  'w-full rounded-md border border-line bg-bg px-3 py-2 text-[13px] text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25'

/** dashboard 的节拍输入：透明底描边输入框（聚焦时点缀色描边） */
const DASH_INPUT =
  'w-[104px] rounded-lg border border-line bg-transparent px-3 py-1.5 font-mono text-[13px] text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/30'

/** 心跳重连间隔（后端单条流有最长保持时长，断开属正常） */
const RECONNECT_MS = 1500

/** 资源视图的统计窗口（小时 + 中文标签） */
const WINDOWS: readonly (readonly [number, string])[] = [
  [24, '24 小时'],
  [24 * 7, '7 天'],
  [24 * 30, '30 天'],
]

/* ---------------- 小工具 ---------------- */

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

function fmtDay(ms: number | null | undefined): string {
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
function fmtDur(start: number, end: number | null): string {
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

function textPreview(s: string | null, n: number): string {
  const t = (s ?? '').replace(/\s+/g, ' ').trim()
  return t.length > n ? `${t.slice(0, n)}…` : t
}

/** 成功率（窗口内 done / runs）；没跑过时不给百分比 */
function successRate(done: number, runs: number): string {
  if (runs <= 0) return '—'
  return `${Math.round((done / runs) * 100)}%`
}

/** 状态点：进行中=点缀色脉冲；完成 / 失败用语义色令牌 */
function StatusDot({ status }: { status: string }) {
  const tone =
    status === 'done' ? 'bg-ok' : status === 'failed' ? 'bg-danger' : 'animate-pulse bg-accent'
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
function ViewTab({
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

/** 插槽 → 注入器绑定行：正文里的 {{slot}} 由该注入器从存储层现取内容填充 */
function InjectorRows({
  value,
  options,
  onChange,
}: {
  value: InjectorBinding[]
  options: string[]
  onChange: (v: InjectorBinding[]) => void
}) {
  const set = (i: number, patch: Partial<InjectorBinding>) =>
    onChange(value.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  return (
    <div className="flex flex-col gap-2">
      {value.map((r, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            className={`${INPUT} font-mono`}
            placeholder="插槽名"
            value={r.slot}
            onChange={(e) => set(i, { slot: e.target.value })}
          />
          <select
            className={INPUT}
            value={r.injector}
            onChange={(e) => set(i, { injector: e.target.value })}
          >
            <option value="">— 注入器 —</option>
            {options.map((o) => (
              <option key={o} value={o}>
                {o}
              </option>
            ))}
          </select>
          <input
            className={INPUT}
            placeholder="参数（可空）"
            value={r.args ?? ''}
            onChange={(e) => set(i, { args: e.target.value })}
          />
          <button
            type="button"
            className={miniBtn}
            onClick={() => onChange(value.filter((_, idx) => idx !== i))}
          >
            移除
          </button>
        </div>
      ))}
      <button
        type="button"
        className={`${miniBtn} self-start`}
        onClick={() => onChange([...value, { slot: '', injector: '', args: null }])}
      >
        + 添加插槽
      </button>
    </div>
  )
}

/* ---------------- 心跳主会话（总线） ---------------- */

/**
 * 心跳主会话：心跳本身的那份上下文，也是整个系统的唯一入口。
 * 每拍由它先出场——看清现在是什么情况、决定这一拍派谁：派一份常驻编制，还是当场交代一个匿名子代理。
 * 只配两样东西：正文与模型；工具写死为「派计划 / 立改撤计划 / 派子代理 / 翻过程 / 只读检索」，不在这里配。
 */
function HeartbeatPage({
  injectors,
  models,
  onToast,
}: {
  injectors: string[]
  models: ModelView[]
  onToast: (kind: 'success' | 'error', msg: string) => void
}) {
  const kit = usePageKit()
  const [content, setContent] = useState('')
  const [bound, setBound] = useState<InjectorBinding[]>([])
  const [modelId, setModelId] = useState('')
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    subHeartbeatContext()
      .then((h: SubHeartbeatContext) => {
        setContent(h.content)
        setBound(h.injectors)
        setModelId(h.modelId ?? '')
        setReady(true)
      })
      .catch((e: unknown) => onToast('error', errMsg(e)))
  }, [onToast])

  const save = async () => {
    if (!content.trim()) return onToast('error', '正文不能为空')
    setBusy(true)
    try {
      const h = await subSaveHeartbeatContext({
        content,
        injectors: bound
          .filter((b) => b.slot.trim() && b.injector)
          .map((b) => ({
            slot: b.slot.trim(),
            injector: b.injector,
            args: b.args?.trim() ? b.args.trim() : null,
          })),
        modelId: modelId || null,
      })
      setContent(h.content)
      setBound(h.injectors)
      setModelId(h.modelId ?? '')
      onToast('success', '心跳主会话已保存')
    } catch (e) {
      onToast('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  if (!ready) return <kit.Empty>正在读心跳主会话…</kit.Empty>

  return (
    <div className="flex flex-col gap-3.5">
      <kit.Field
        label="提示词"
        hint="{{插槽}} 由下方绑定的注入器现取填充。工具不在这里配：它只做「派计划 / 立改撤计划 / 派子代理 / 翻过程 / 只读检索」。"
        wrap={false}
      >
        <textarea
          className={`${INPUT} scrollbar-slim min-h-[320px] font-mono text-[12.5px] leading-6`}
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

      <kit.Field label="插槽注入器" wrap={false}>
        <InjectorRows value={bound} options={injectors} onChange={setBound} />
      </kit.Field>

      <div className="flex justify-end border-t border-line pt-3">
        <button
          type="button"
          className={btnPrimary('rounded-sm')}
          disabled={busy}
          onClick={save}
        >
          {busy ? '保存中…' : '保存'}
        </button>
      </div>
    </div>
  )
}

/* ---------------- 插槽（只读词典） ---------------- */

/**
 * 插槽子页：一份从头上到尾的说明文档——每个注入器各取什么、参数怎么给、从哪儿取。
 * 不用卡片、不折叠，所有文字都在页面上，可以直接 Ctrl+F 搜。
 */
function InjectorGuidePage() {
  const [guide, setGuide] = useState<InjectorGuide | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    subInjectorGuide()
      .then(setGuide)
      .catch((e: unknown) => setError(errMsg(e)))
  }, [])

  if (error) {
    return <p className="text-[12.5px] text-hint">{error}</p>
  }
  if (!guide) {
    return <p className="text-[12.5px] text-hint">载入插槽词典…</p>
  }

  return (
    <article className="flex max-w-[760px] flex-col gap-7 text-[12.5px] leading-relaxed text-muted">
      <header className="flex flex-col gap-1.5">
        <h2 className="text-[13px] font-semibold text-fg">插槽</h2>
        <p>
          正文里写 {'{{插槽名}}'}，再用「插槽注入器」把这个名字指向一个注入器；执行的那一刻，
          由注入器从存储层把素材现取出来填进去。插槽名随你起，注入器名必须在下面这份清单里。
        </p>
      </header>

      <section className="flex flex-col gap-1.5">
        <h3 className="text-[12.5px] font-semibold text-fg">取值发生在什么时候</h3>
        <p>
          每次执行的那一刻：模板与绑定都是静态的，素材现取现填，组装结果落进执行记录的
          context，所以「当时到底给模型看了什么」永远可回溯。没绑定、或绑了却没取到值的插槽，会落成
          「（本项素材为空）」；单个注入器的结果上限 4000 字；注入器名写错或取数出错只会留空，
          不会中断这一拍心跳。
        </p>
      </section>

      <section className="flex flex-col">
        <h3 className="text-[12.5px] font-semibold text-fg">
          可绑定的注入器
          <span className="ml-2 text-[11.5px] font-normal text-hint">
            共 {guide.injectors.length} 个
          </span>
        </h3>
        <div className="mt-1 flex flex-col divide-y divide-line">
          {guide.injectors.map((i) => (
            <div key={i.name} className="flex flex-col gap-1 py-3.5">
              <p className="font-mono text-[12.5px] font-semibold text-fg">{`{{${i.name}}}`}</p>
              <p>{i.summary || '—'}</p>
              <p className="text-hint">参数：{i.args || '无'}</p>
              <p className="text-hint">取值：{i.source || '—'}</p>
            </div>
          ))}
        </div>
      </section>
    </article>
  )
}

/* ---------------- 工具（只读词典） ---------------- */

/** 工具面中文名：模型一次调用里能看见哪些工具，由它落在哪些面上决定 */
const TOOL_SURFACE_LABELS: Record<string, string> = {
  CHAT: '对话',
  HEARTBEAT: '心跳主会话',
  THREAD: '子代理会话',
  PEER: '智能体成员',
  EXTRACT: '文档分析 · 抽取',
  COMMIT: '文档分析 · 入库',
}

/**
 * 工具子页：一份从头上到尾的说明文档——系统里每个工具叫什么、干什么、长在哪些工具面上、参数怎么给。
 * 不用卡片、不折叠，所有文字都在页面上，可以直接 Ctrl+F 搜。
 */
function ToolsPage() {
  const kit = usePageKit()
  const [tools, setTools] = useState<SubTool[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    subTools()
      .then(setTools)
      .catch((e: unknown) => setError(errMsg(e)))
  }, [])

  if (error) {
    return <p className="text-[12.5px] text-hint">{error}</p>
  }
  if (!tools) {
    return <p className="text-[12.5px] text-hint">载入工具词典…</p>
  }

  return (
    <article className="flex max-w-[760px] flex-col gap-7 text-[12.5px] leading-relaxed text-muted">
      <header className="flex flex-col gap-1.5">
        <h2 className="ui-heading text-sm">工具</h2>
        <p>
          工具是大模型能亲手做的事，每个工具是一份写死的编程契约：叫什么、干什么、参数怎么给、
          可出现在哪些工具面。一次调用里模型能看见哪些工具，由「工具面」决定——同一个工具可落在多个面上。
          新增一个工具就是新增一个工具类，这份清单自动跟着变。
        </p>
      </header>

      <section className="flex flex-col">
        <h3 className="ui-heading text-sm">
          全部工具
          <span className="mono ml-2 text-2xs font-normal text-faint">共 {tools.length} 个</span>
        </h3>
        <div className="mt-1 flex flex-col divide-y divide-line">
          {tools.map((t) => {
            const required = t.parameters.required ?? []
            const props = Object.entries(t.parameters.properties ?? {})
            return (
              <div key={t.name} className="flex flex-col gap-1.5 py-3.5">
                <div className="flex flex-wrap items-center gap-1.5">
                  <p className="font-mono text-[12.5px] font-semibold text-fg">{t.name}</p>
                  {t.surfaces.map((s) => (
                    <kit.Badge key={s}>{TOOL_SURFACE_LABELS[s] ?? s}</kit.Badge>
                  ))}
                  {t.writes && <kit.Badge tone="accent">写库</kit.Badge>}
                  {t.parallelizable && <kit.Badge tone="ok">可并行</kit.Badge>}
                  {!t.agentsMayCall && <kit.Badge tone="warn">子代理不可调</kit.Badge>}
                </div>
                <p>{t.description || '—'}</p>
                <p className="text-hint">参数：{props.length ? '' : '无'}</p>
                {props.length > 0 && (
                  <div className="flex flex-col gap-0.5">
                    {props.map(([name, p]) => (
                      <p key={name} className="text-hint">
                        <span className="font-mono text-muted">{name}</span>
                        {required.includes(name) && <span className="ml-1">（必填）</span>}
                        {p.type ? ` · ${p.type}` : ''}
                        {p.description ? ` — ${p.description}` : ''}
                      </p>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </section>
    </article>
  )
}

/* ---------------- 子代理 & 任务计划（资源使用视图） ---------------- */

/** 执行种类 → 人话：一次执行可能是主会话登场、编制被派出，或匿名子代理被当场交代 */
function kindLabel(kind: SubExecKind): string {
  return kind === 'heartbeat' ? '心跳主会话' : kind === 'plan' ? '任务计划' : '子代理'
}

/**
 * 一条执行记录（子代理列表 / 编制展开后的历史共用）：状态、时刻、耗时、结论摘要，点开看全过程。
 * 行的形态交给 kit.Row（分隔线 / 圆角块随主题）；showTitle 只在混排列表里开——
 * 编制历史里的 title 与所属编制同名，重复显示没意义。
 */
function ExecLine({
  exec,
  showTitle,
  onClick,
}: {
  exec: SubExec
  showTitle?: boolean
  onClick: () => void
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
    </kit.Row>
  )
}

/**
 * 一份常驻编制：左边是岗位定义（名字/正文/工具/渠道/节拍参考），右边是它在窗口内的用量。
 * 展开是「在这个窗口里它被派出过哪几次」——用量数字背后的原始记录。
 */
function PlanRow({
  plan,
  usage,
  busy,
  onRun,
  onOpenExec,
  onToast,
}: {
  plan: SubPlan
  usage: SubPlanUsage | undefined
  busy: boolean
  onRun: (p: SubPlan) => void
  onOpenExec: (id: number) => void
  onToast: (kind: 'success' | 'error', msg: string) => void
}) {
  const kit = usePageKit()
  const [open, setOpen] = useState(false)
  const [history, setHistory] = useState<SubExec[] | null>(null)
  const u = usage

  /** 每次展开都重拉：历史与统计窗口无关（后端给的是最近若干次），不留缓存就不会在单跑之后看到旧记录 */
  const toggle = async () => {
    const next = !open
    setOpen(next)
    if (!next) return
    setHistory(null)
    try {
      setHistory(await subPlanExecs(plan.id))
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
              <kit.Badge key={c}>{SUB_CHANNEL_LABELS[c] ?? c}</kit.Badge>
            ))}
            <kit.Badge>{intervalLabel(plan.intervalMs)}</kit.Badge>
          </div>
          <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-muted">
            {textPreview(plan.content, 110) || '（无正文）'}
          </p>
          <p className="mono mt-1.5 text-2xs text-dim">
            工具 {plan.tools.length ? plan.tools.join('、') : '无'}
            <span className="mx-1.5">·</span>
            最近派出 {fmtAgo(plan.lastBeatAt)}
          </p>
        </div>

        <div className="flex shrink-0 items-center gap-2">
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

/** 资源用量四件套（子代理与任务计划各一组）：次数、成功率、平均耗时、工具往返 */
function UsageCards({ usage, unitHint }: { usage: SubUsage | undefined; unitHint: string }) {
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

/**
 * 「子代理 & 任务计划」页：共用一份窗口与一个总览，下面两块各自回答「被用得怎么样」。
 *
 * <p>两块都没有新建/编辑/删除入口——立改撤都是大模型在对话里用工具做的事
 * （delegate_subagent / create_plan / update_plan / delete_plan）。整页只读，
 * 唯一的手动动作是「立即执行」：让人当场把某份编制单跑一次，便于验证。
 */
function AgentsPage({
  plans,
  agentRuns,
  resources,
  hours,
  onHours,
  busy,
  onRunPlan,
  onOpenExec,
  onToast,
}: {
  plans: SubPlan[]
  agentRuns: SubExec[]
  resources: SubResources | null
  hours: number
  onHours: (h: number) => void
  busy: boolean
  onRunPlan: (p: SubPlan) => void
  onOpenExec: (id: number) => void
  onToast: (kind: 'success' | 'error', msg: string) => void
}) {
  const kit = usePageKit()
  const hb = resources?.heartbeat
  const totals = resources?.totals
  /** 编制用量按 planId 对齐（不是名字：编制被改名后历史仍挂在同一个 id 上） */
  const usageOf = (planId: string) => resources?.planUsage.find((u) => u.planId === planId)
  const windowLabel = WINDOWS.find((w) => w[0] === hours)?.[1] ?? ''

  return (
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="flex flex-col gap-1">
          <h2 className="ui-heading text-md">子代理 &amp; 任务计划</h2>
          <p className="max-w-[720px] text-xs leading-relaxed text-muted">
            两个层次：子代理是匿名 worker——当场交代即跑，不建档案、跑完即逝；任务计划是常驻编制——
            一份带名字、正文、工具与渠道的岗位定义，由心跳主会话按需派出、可反复用。本页只读，
            回答的是「它们被用得怎么样」。
          </p>
        </div>
        <kit.Range
          value={hours}
          options={WINDOWS.map(([v, label]) => ({ value: v, label }))}
          onChange={onHours}
        />
      </header>

      {/* 总览：窗口内的执行总量与心跳主会话那一档；细项留给下面两块各自的四张卡 */}
      <div className={kit.statRow}>
        <kit.Stat
          label={`执行（${windowLabel}）`}
          value={String(totals?.runs ?? 0)}
          hint={`成功 ${totals?.done ?? 0} · 失败 ${totals?.failed ?? 0}`}
        />
        <kit.Stat
          label="成功率"
          value={successRate(totals?.done ?? 0, totals?.runs ?? 0)}
          hint={`子代理 ${resources?.agents.runs ?? 0} · 计划 ${resources?.plans.runs ?? 0}`}
          tone={(totals?.failed ?? 0) > 0 ? 'default' : 'accent'}
        />
        <kit.Stat
          label="单次耗时"
          value={fmtMs(totals?.avgDurationMs ?? 0)}
          hint={`最长 ${fmtMs(totals?.maxDurationMs ?? 0)}`}
        />
        <kit.Stat
          label="工具往返"
          value={String(totals?.toolRounds ?? 0)}
          hint={`≈ ${fmtNum(totals?.estTokens ?? 0)} tokens`}
        />
        <kit.Stat
          label="心跳主会话"
          value={String(hb?.runs ?? 0)}
          unit="次出场"
          hint={`${resources?.running ? '执行中' : resources?.heartbeatEnabled ? '待命' : '已停摆'} · 节拍 ${resources?.tickMs ?? '—'}ms · 工具往返 ${hb?.toolRounds ?? 0}`}
        />
      </div>

      {/* 子代理：匿名 worker，不建档案，所以这里只有跑过的记录 */}
      <section className="flex flex-col gap-3">
        <h3 className="ui-heading text-sm">子代理</h3>
        <p className="max-w-[720px] text-xs leading-relaxed text-muted">
          匿名 worker：主对话或心跳主会话当场用
          <span className="mx-1 font-mono text-[11.5px]">delegate_subagent</span>
          交代一句就派出去，不建档、跑完即逝，同一批可以并行跑好几个。
          它有自己的上下文（看不到派它的那次对话）、默认只有只读检索的窄工具集，只把结论回流给派它的人。
        </p>

        <UsageCards usage={resources?.agents} unitHint={`调用（${windowLabel}）`} />

        {agentRuns.length === 0 ? (
          <kit.Empty>
            还没有子代理被派过 ——
            它不由人在页面上新建。去对话里交代一句——比如「派个子代理去查一下 X」，或让心跳主会话自己决定；
            跑完只把结论带回来，不留下编制，所以这里只看到跑过的记录。
          </kit.Empty>
        ) : (
          <div className="flex flex-col">
            {agentRuns.map((r) => (
              <ExecLine key={r.id} exec={r} showTitle onClick={() => onOpenExec(r.id)} />
            ))}
          </div>
        )}
      </section>

      {/* 任务计划：常驻编制，立改撤都在大模型的工具里 */}
      <section className="flex flex-col gap-3">
        <div className="flex items-baseline justify-between gap-3">
          <h3 className="ui-heading text-sm">任务计划</h3>
          {plans.length > 0 && (
            <span className="mono text-2xs text-dim">
              {plans.length} 份编制 · 名册 {resources?.rosterCount ?? plans.length}
            </span>
          )}
        </div>
        <p className="max-w-[720px] text-xs leading-relaxed text-muted">
          常驻编制：一份带名字、正文、工具与交付渠道的岗位定义，还有节拍参考（给主会话的参考，
          不是硬时刻表）。由心跳主会话按需派出，可以反复用。立、改、撤都是大模型在对话里用
          <span className="mx-1 font-mono text-[11.5px]">create_plan / update_plan / delete_plan</span>
          做的事，本页只读。
        </p>

        <UsageCards usage={resources?.plans} unitHint={`被派出（${windowLabel}）`} />

        {plans.length === 0 ? (
          <kit.Empty>
            名册还是空的 ——
            编制不由人在页面上新建。去对话里说一句「以后定期做 X」，大模型就会用
            <span className="mx-1 font-mono">create_plan</span>
            立一份——之后每拍由心跳主会话看着名册决定派不派它。
          </kit.Empty>
        ) : (
          <ul className="flex flex-col gap-2.5">
            {plans.map((p) => (
              <PlanRow
                key={p.id}
                plan={p}
                usage={usageOf(p.id)}
                busy={busy}
                onRun={onRunPlan}
                onOpenExec={onOpenExec}
                onToast={onToast}
              />
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/* ---------------- RAG 召回（三路召回留痕） ---------------- */

/** 三路各自一个徽章色调（向量=点缀 / 图=成功 / 时序=警示），列表里一眼可辨 */
const RAG_CHANNEL_TONE: Record<SubRagChannel, 'accent' | 'ok' | 'warn'> = {
  vector: 'accent',
  graph: 'ok',
  memory: 'warn',
}

/**
 * 「RAG 召回」子页：三路召回（向量 / 图 / 时序事件）每一次工具调用的留痕——
 * 模型问了什么（query）与工具回给它的原始数据（result）原样在此，用来回看 RAG 效果。
 * 对话、智能体群聊、心跳与子代理里发生的召回都会记进来，source 标明来源。
 */
function RagRecallPage({
  onToast,
}: {
  onToast: (kind: 'success' | 'error', msg: string) => void
}) {
  const kit = usePageKit()
  const [channel, setChannel] = useState<SubRagChannel | null>(null)
  const [rows, setRows] = useState<SubRagRecall[] | null>(null)
  const [active, setActive] = useState<SubRagRecall | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(
    async (c: SubRagChannel | null) => {
      try {
        setRows(await subRagRecalls(150, c))
      } catch (e) {
        onToast('error', errMsg(e))
      }
    },
    [onToast],
  )

  useEffect(() => {
    load(channel)
  }, [channel, load])

  const clear = async () => {
    setBusy(true)
    try {
      const r = await subClearRagRecalls()
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
    <div className="flex flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="flex flex-col gap-1">
          <h2 className="ui-heading text-md">RAG 召回</h2>
          <p className="max-w-[760px] text-xs leading-relaxed text-muted">
            三路召回（向量 / 图 / 时序事件）每一次工具调用的留痕：模型问了什么、工具回给它的原始数据是什么，
            都原样记在这里。用来回看检索效果——命中了什么、拼进上下文的内容对不对、模型据此答得对不对。
            对话、智能体群聊、心跳与子代理里发生的召回都会记进来，来源标在每条上。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* 渠道过滤是字符串值（含 null=全部），kit.Range 只收数字，故自绘同款分段带 */}
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
          <button type="button" className={btnQuiet('rounded-sm')} onClick={() => load(channel)}>
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
          还没有召回记录 ——
          当对话、智能体群聊或潜意识里的执行调用检索工具（
          <span className="mx-1 font-mono">search_vector_chunks</span>/
          <span className="mx-1 font-mono">recall_memory</span>/ 图谱查询）时，
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

/** 一条召回留痕的详情：查询原文 + 回给模型的 tool 消息原文（大模型实际拿到的原始数据） */
function RagRecallDetail({ row, onClose }: { row: SubRagRecall; onClose: () => void }) {
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
          <pre className="scrollbar-slim max-h-[20vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-[11.5px] leading-5 whitespace-pre-wrap text-muted">
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
          <pre className="scrollbar-slim max-h-[52vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-[11.5px] leading-5 whitespace-pre-wrap text-muted">
            {row.result || '（无返回）'}
          </pre>
        </div>
      </div>
    </FloatWindow>
  )
}

/* ---------------- 执行详情（组装出的上下文 / 思维链 / 写库清单） ---------------- */

function ResultDetail({ detail, onClose }: { detail: SubExec; onClose: () => void }) {
  const kit = usePageKit()
  const [tab, setTab] = useState<'out' | 'ctx' | 'trace' | 'edit'>('out')
  const writes = detail.edits
  return (
    <FloatWindow
      // 类型放在名字后面：同一条记录既可能是匿名子代理，也可能是某份编制被派出
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
        <div className="text-[13px] leading-relaxed text-fg">
          {detail.error && (
            <p className="mb-2 border-l-2 border-danger bg-fg/[0.03] px-2 py-1 text-[12.5px] text-muted">
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
          <p className="text-[11.5px] leading-relaxed text-hint">
            这是执行前组装出、直接交付给 LLM 的上下文（消息数组）。LLM 调用本身无状态，
            状态化的正是这份「模板 + 注入器 + 存储层数据」的构造结果。
          </p>
          <pre className="scrollbar-slim max-h-[52vh] overflow-auto rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-[11.5px] leading-5 text-muted">
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
              <pre className="scrollbar-slim max-h-[40vh] overflow-auto p-2.5 font-mono text-[11.5px] leading-5 whitespace-pre-wrap text-muted">
                {s.args ? jsonPretty(s.args) : (s.text ?? '')}
              </pre>
            </div>
          ))}
          {detail.reasoning && (
            <details className="rounded-md border border-line">
              <summary className="cursor-pointer px-2.5 py-1.5 text-[11.5px] text-faint">
                完整思维链
              </summary>
              <pre className="scrollbar-slim max-h-[40vh] overflow-auto border-t border-line p-2.5 font-mono text-[11.5px] leading-5 whitespace-pre-wrap text-muted">
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
              <pre className="scrollbar-slim max-h-[28vh] overflow-auto p-2.5 font-mono text-[11.5px] leading-5 whitespace-pre-wrap text-muted">
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

/* ---------------- 页面 ---------------- */

export default function Subconscious() {
  const [state, setState] = useState<SubState | null>(null)
  const [runs, setRuns] = useState<SubRunBundle[]>([])
  const [plans, setPlans] = useState<SubPlan[]>([])
  const [agentRuns, setAgentRuns] = useState<SubExec[]>([])
  const [resources, setResources] = useState<SubResources | null>(null)
  const [hours, setHours] = useState<number>(24)
  const [models, setModels] = useState<ModelView[]>([])
  const [detail, setDetail] = useState<SubExec | null>(null)
  const [tickDraft, setTickDraft] = useState<string | null>(null)
  /** 内部页：dashboard=原页面（心跳 + 观测台），heartbeat=主会话总线，prompts=提示词 & 上下文工程，agents=子代理 & 任务计划，rag=RAG 召回留痕，injectors=插槽词典，tools=工具词典 */
  const [view, setView] = useState<
    'dashboard' | 'heartbeat' | 'prompts' | 'agents' | 'rag' | 'injectors' | 'tools'
  >('dashboard')
  const [live, setLive] = useState(false)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)

  const kit = usePageKit()

  const notify = useCallback(
    (kind: 'success' | 'error', msg: string) => setToast({ kind, msg }),
    [],
  )

  /** 资源快照：窗口变化与每次动作后重拉（用量是聚合读，比 SSE 快照更适合这页） */
  const loadResources = useCallback(
    async (h: number) => {
      try {
        setResources(await subResources(h))
      } catch (e) {
        notify('error', errMsg(e))
      }
    },
    [notify],
  )

  const loadMeta = useCallback(async () => {
    try {
      const [ps, ar, st, cfg] = await Promise.all([
        subPlans(),
        subAgentRuns(),
        subState(),
        getSettings(),
      ])
      setPlans(ps)
      setAgentRuns(ar)
      setState(st)
      setModels(cfg.models)
    } catch (e) {
      notify('error', errMsg(e))
    }
  }, [notify])

  useEffect(() => {
    loadMeta()
  }, [loadMeta])

  useEffect(() => {
    loadResources(hours)
  }, [hours, loadResources])

  /* 观测台实时流：循环重连（单条流有时长上限，断开属正常） */
  useEffect(() => {
    const ac = new AbortController()
    let stopped = false
    const pump = async () => {
      while (!stopped) {
        try {
          await subStream((p) => {
            setLive(true)
            setState(p.state)
            setRuns(p.recentRuns)
          }, ac.signal)
        } catch {
          /* 断流 / 后端重启：落到下方重连 */
        }
        if (stopped) break
        setLive(false)
        await new Promise((r) => setTimeout(r, RECONNECT_MS))
      }
    }
    pump()
    return () => {
      stopped = true
      ac.abort()
    }
  }, [])

  const tickValue = tickDraft ?? (state ? String(state.tickMs) : '')

  const beat = async () => {
    setBusy(true)
    try {
      const r = await subBeat()
      notify(
        r.started ? 'success' : 'error',
        r.started
          ? `第 ${r.beat} 拍已敲响：主会话发起 ${r.agents} 次执行`
          : '已有一轮在跑，本次跳过',
      )
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
      loadMeta()
      loadResources(hours)
    }
  }

  /** 人工单跑一份编制：绕过主会话，独占一批，用于当场验证编制写对了没有 */
  const runPlan = async (p: SubPlan) => {
    setBusy(true)
    try {
      const r = await subRunPlan(p.id)
      notify(r.started ? 'success' : 'error', r.started ? `已单独派出「${p.name}」` : '已有一轮在跑，本次跳过')
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
      loadMeta()
      loadResources(hours)
    }
  }

  const toggleHeartbeat = async (on: boolean) => {
    try {
      setState(await subHeartbeat({ enabled: on }))
      notify('success', on ? '心跳已常驻' : '心跳已停摆')
    } catch (e) {
      notify('error', errMsg(e))
    }
  }

  const saveTick = async () => {
    const n = Number(tickValue)
    if (!Number.isFinite(n) || n <= 0) return notify('error', '节拍需为正整数毫秒')
    try {
      setState(await subHeartbeat({ tickMs: Math.round(n) }))
      setTickDraft(null)
      notify('success', `节拍已改为 ${Math.round(n)}ms`)
    } catch (e) {
      notify('error', errMsg(e))
    }
  }

  const openDetail = async (id: number) => {
    try {
      setDetail(await subExec(id))
    } catch (e) {
      notify('error', errMsg(e))
    }
  }

  const injectorOptions = state?.injectors ?? []
  const running = state?.running ?? false
  const heartbeatOn = state?.heartbeatEnabled ?? false

  return (
    <kit.Page
      title="潜意识"
      en="SUBCONSCIOUS"
      meta={
        <kit.Badge tone={live ? 'ok' : 'warn'}>
          {live ? 'STREAM LIVE' : 'RECONNECTING'}
        </kit.Badge>
      }
    >
      {/* 顶部滑块：dashboard（原页面）/ heartbeat（主会话总线）/ 提示词 & 上下文工程 / 子代理 & 任务计划 / RAG 召回 / 插槽 / 工具（内部页） */}
      <div className="flex w-fit shrink-0 flex-wrap border border-line-strong">
        <ViewTab active={view === 'dashboard'} onClick={() => setView('dashboard')}>
          dashboard
        </ViewTab>
        <ViewTab active={view === 'heartbeat'} onClick={() => setView('heartbeat')}>
          heartbeat
        </ViewTab>
        <ViewTab active={view === 'prompts'} onClick={() => setView('prompts')}>
          提示词 &amp; 上下文工程
        </ViewTab>
        <ViewTab active={view === 'agents'} onClick={() => setView('agents')}>
          子代理 &amp; 任务计划
        </ViewTab>
        <ViewTab active={view === 'rag'} onClick={() => setView('rag')}>
          RAG 召回
        </ViewTab>
        <ViewTab active={view === 'injectors'} onClick={() => setView('injectors')}>
          插槽
        </ViewTab>
        <ViewTab active={view === 'tools'} onClick={() => setView('tools')}>
          工具
        </ViewTab>
      </div>

      {view === 'dashboard' && (
        <div className="flex flex-col gap-3.5">
          {/* 心跳控制：状态 + 常驻开关 + 节拍 + 手动敲一拍 */}
          <kit.Panel
            title="心跳控制"
            meta={running ? 'RUNNING' : heartbeatOn ? 'STANDBY' : 'HALTED'}
          >
            <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
              <div className="flex min-w-0 items-start gap-3">
                <span
                  className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${
                    running ? 'animate-pulse bg-accent' : heartbeatOn ? 'bg-accent' : 'bg-fg/30'
                  }`}
                />
                <div className="min-w-0">
                  <p className="text-sm font-medium text-fg">潜意识</p>
                  <p className="mt-0.5 text-xs text-muted">
                    心跳主会话 + 子代理 / 任务计划 · 每拍由主会话决定派谁
                  </p>
                  <p className="mono mt-1.5 text-2xs text-dim">
                    {running ? '本轮心跳执行中' : heartbeatOn ? '常驻心跳待命' : '心跳已停摆'}
                    <span className="mx-1.5">·</span>
                    节拍 {state?.tickMs ?? '—'}ms
                    <span className="mx-1.5">·</span>
                    {live ? '观测台已连' : '观测台重连中…'}
                  </p>
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <label className="flex items-center gap-2 pr-1 text-[13px] text-muted">
                  <Switch on={heartbeatOn} onToggle={toggleHeartbeat} />
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
                  敲一次心跳
                </button>
              </div>
            </div>
          </kit.Panel>

          {/* 最近事件：每批一条长条，条内内联本批的各次执行 */}
          <kit.Panel
            title="最近事件"
            meta={runs.length > 0 ? `LAST ${runs.length} BATCHES` : undefined}
          >
            {runs.length === 0 ? (
              <kit.Empty>还没有跑过一轮心跳</kit.Empty>
            ) : (
              <div className="flex flex-col gap-2">
                {runs.map(({ run, execs }) => (
                  <article
                    key={run.id}
                    className="flex flex-wrap items-center gap-x-3 gap-y-2 border border-line px-3.5 py-3 transition hover:border-line-strong hover:bg-accent-soft"
                  >
                    <span className="flex shrink-0 items-center gap-2">
                      <StatusDot status={run.status} />
                      <span className="mono border border-line px-1.5 py-0.5 text-2xs font-bold text-fg">
                        #{run.beat}
                      </span>
                      <span className="mono text-2xs text-dim">
                        {run.trigger === 'manual' ? '手动' : run.trigger === 'chat' ? '对话委托' : '心跳'}
                        <span className="mx-1.5">·</span>
                        {fmtDay(run.startedAt)}
                        <span className="mx-1.5">·</span>
                        {fmtDur(run.startedAt, run.finishedAt)}
                        <span className="mx-1.5">·</span>
                        {run.agentCount} 次执行
                        {run.note ? ` · ${run.note}` : ''}
                      </span>
                    </span>

                    {execs.length === 0 ? (
                      <span className="text-2xs text-dim">本批没有执行产出</span>
                    ) : (
                      <div className="flex flex-1 flex-wrap items-center gap-1.5">
                        {execs.map((r) => (
                          <button
                            key={r.id}
                            type="button"
                            onClick={() => openDetail(r.id)}
                            title={textPreview(r.error ?? r.output, 300) || '（无结论）'}
                            className="flex max-w-full items-center gap-2 border border-line px-2.5 py-1.5 text-left transition hover:border-line-strong hover:bg-accent-soft"
                          >
                            <StatusDot status={r.status} />
                            <span className="max-w-[180px] truncate text-xs font-medium text-fg">
                              {r.title ?? '（未知）'}
                            </span>
                            <span className="mono shrink-0 text-2xs text-dim">
                              {fmtDur(r.startedAt, r.finishedAt)}
                            </span>
                            {r.edits.length > 0 && (
                              <kit.Badge>写库 {r.edits.length}</kit.Badge>
                            )}
                          </button>
                        ))}
                      </div>
                    )}
                  </article>
                ))}
              </div>
            )}
          </kit.Panel>
        </div>
      )}

      {/* 心跳主会话：总线自己的那份上下文 */}
      {view === 'heartbeat' && (
        <HeartbeatPage injectors={injectorOptions} models={models} onToast={notify} />
      )}

      {/* 提示词 & 上下文工程：写死在代码里的提示词与它们的用户版本（选定版本即覆盖内置） */}
      {view === 'prompts' && <PromptsPanel />}

      {/* 子代理 & 任务计划：两个层次的编制各被用得怎么样（只读，无新建/编辑入口） */}
      {view === 'agents' && (
        <AgentsPage
          plans={plans}
          agentRuns={agentRuns}
          resources={resources}
          hours={hours}
          onHours={setHours}
          busy={busy}
          onRunPlan={runPlan}
          onOpenExec={openDetail}
          onToast={notify}
        />
      )}

      {/* RAG 召回：三路召回（向量 / 图 / 时序事件）的留痕，回看检索效果 */}
      {view === 'rag' && <RagRecallPage onToast={notify} />}

      {/* 插槽：一份只读说明文档（不套框，便于 Ctrl+F 通读） */}
      {view === 'injectors' && <InjectorGuidePage />}

      {/* 工具：整个系统的工具清单，同样是一份只读说明文档 */}
      {view === 'tools' && <ToolsPage />}

      {detail && <ResultDetail detail={detail} onClose={() => setDetail(null)} />}

      <Toast message={toast?.msg ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    </kit.Page>
  )
}
