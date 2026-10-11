import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  analyzeDocument,
  commitChunkTriples,
  disambiguateTriples,
  getChunkExtraction,
  graphNeighbors,
  loginUrlOf,
  type ChunkItem,
  type DocType,
  type EntityLite,
  type Prop,
  type SearchGraph,
  type Triple,
  type TripleEval,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import GraphMini from '../components/GraphMini'
import Toast, { type ToastKind } from '../components/Toast'
import { btnOutline, btnPrimary } from '../components/ui'
import { usePageKit } from '../kit'

/**
 * 步骤 2：知识提取（步骤条下方为左右布局）。
 * 左：父文档摘要 + Chunk 原文（提取时由父文档标题 + 全文概述组装上下文补完串送入 LLM），
 *    标题栏最右为「知识提取」按钮 → LLM 抽取实体-关系-实体 → 自动消歧评估（存在性/候选/两实体间已有关系）；
 * 右：提取到的三元组横长条卡片竖向堆叠：卡片为「点—边—点」SVG（圆点节点 + 带箭头直线边，
 *    类 graph 渲染），圆点与名称按实体名哈希取色（同名实体恒定同色、不同实体近似随机异色），
 *    实体存在性/疑似标签直接标在对应实体正下方（同关系重复标在关系正下方）；
 *    点击实体弹窗查看以该实体（及各疑似实体）为中心的 1 跳图，可在弹窗内合并/重命名并重新消歧；
 *    待添加 props 以图右上角闪烁蓝点承载，hover 展开标签气泡、点击标签可忽略对应属性；
 *    卡片最右侧小区域为提交勾选框；卡片列表下方「提交」→ 勾选的三元组写入图并关联 chunk → 成功弹出 message box 轻提示；
 * 底部 footer：左「返回选择」、右「下一步」。
 * 若该 chunk 已提交过（chunk_extraction 有记录），进入页面时先只读回显上次写入的三元组，
 * 点「重新提取」可重走提取流程（提交后记录覆盖为最近一次）。
 */

/** 进入步骤 2 的上下文（步骤 1 中选中的 chunk + 父文档标题、全文概述与类型） */
export type ExtractCtx = {
  chunk: ChunkItem
  docTitle: string
  contextualizing: string
  /** 父文档类型（摘要生成时 LLM 判定，未判定为 null）：上下文补完串 <docType> 与步骤 3 Header [doc_type] 头串用 */
  docType: DocType | null
}

type Props = {
  ctx: ExtractCtx
  /** 提交成功后点击「进入下一阶段」 */
  onAdvance: () => void
  onBack: () => void
}

/** 三元组编辑行：本地编辑态 + 消歧评估结果（lastEval=发起评估时的快照，编辑后据此判断是否重评） */
type Row = {
  key: number
  triple: Triple
  checked: boolean
  evaluating: boolean
  eval: TripleEval | null
  lastEval: Triple | null
  /** 已忽略的待添加属性（key=`s:${k}`/`o:${k}`，提交时从 triple 的 props 中剔除） */
  propsOff: Set<string>
}

/** 实体查看弹窗：以实体为中心 1 跳邻域图（已存在实体 + 各疑似实体各一个 tab） */
type EntityInspect = {
  rowKey: number
  side: 'subject' | 'object'
  name: string
  /** tab 目标：已存在实体在前、疑似实体在后 */
  targets: { ent: EntityLite; kind: 'exact' | 'candidate' }[]
  tab: number
  /** 各 tab 的 1 跳邻域（加载完成后填充；邻居过多时截取前 MAX_NEIGHBORS 个） */
  graphs: (SearchGraph | null)[]
  /** 各 tab 截断前的邻居总数（> 展示数时提示「仅展示前 N」） */
  totals: number[]
  loading: boolean
  /** 只读（已提交/历史回显）：仅查看，不提供合并/重命名 */
  locked: boolean
}

/** 弹窗邻域图最多展示的直接邻居数（过多会挤压成一团、标签重叠，丧失可读性） */
const MAX_NEIGHBORS = 12

/** 邻域图截断：中心 + 按边出现顺序取前 max 个直接邻居（确定性），边仅保留两端均在集内的 */
function trimNeighbors(g: SearchGraph, centerId: string, max: number): SearchGraph {
  if (g.nodes.length <= max + 1) return g
  const keep = new Set<string>([centerId])
  for (const e of g.edges) {
    if (keep.size >= max + 1) break
    if (e.from === centerId && !keep.has(e.to)) keep.add(e.to)
    else if (e.to === centerId && !keep.has(e.from)) keep.add(e.from)
  }
  return {
    nodes: g.nodes.filter((n) => keep.has(n.id)),
    edges: g.edges.filter((e) => keep.has(e.from) && keep.has(e.to)),
  }
}

const inputCls =
  'w-full min-w-0 rounded-sm border border-line bg-bg px-2 py-1 text-sm text-fg outline-none transition focus:border-line-strong disabled:opacity-60'

/** Spinner 圆环（加载中动画） */
function Spinner({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className={`animate-spin ${className}`}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" opacity="0.2" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  )
}

function tripleDiff(a: Triple, b: Triple): boolean {
  return a.subject !== b.subject || a.predicate !== b.predicate || a.object !== b.object
}

/** 同关系重复：两端均存在，且两实体间已有 subject→object 且关系名相同的边 */
function isDuplicate(triple: Triple, ev: TripleEval): boolean {
  const s = ev.subject.exact
  const o = ev.object.exact
  if (!s || !o) return false
  return ev.edges.some(
    (e) => e.predicate === triple.predicate && e.subjectId === s.id && e.objectId === o.id,
  )
}

function errOf(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/** 提交时间格式化（历史回显提示用） */
function fmtTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function ExtractStep({ ctx, onAdvance, onBack }: Props) {
  const kit = usePageKit()
  /** 上下文补完串（Anthropic Contextual：文档标题 + 类型 + 全文概述 + chunk 组合，与后端 chunkContext 保持一致） */
  const contextString = useMemo(() => {
    return `<document>\n<title>${ctx.docTitle}</title>\n<docType>${ctx.docType ?? 'UNKNOWN'}</docType>\n<abstract>${ctx.contextualizing}</abstract>\n</document>\n\n<chunk>\n${ctx.chunk.content}\n</chunk>`
  }, [ctx])

  const [extracting, setExtracting] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  /** 实体查看弹窗（点击卡片实体打开） */
  const [inspect, setInspect] = useState<EntityInspect | null>(null)
  /** 弹窗内重命名草稿 */
  const [rename, setRename] = useState('')
  const [committing, setCommitting] = useState(false)
  const [committed, setCommitted] = useState<{
    nodes: number
    edges: number
    duplicates: number
    props: number
  } | null>(null)
  /** 轻提示（message box）：提交成功 / 提取·评估·提交失败，替代页内文案与横幅 */
  const [toast, setToast] = useState<{ kind: ToastKind; text: ReactNode } | null>(null)
  /** 历史回显：该 chunk 已提交过的提取记录（createdAt=提交时间；三元组只读展示） */
  const [history, setHistory] = useState<{ createdAt: number } | null>(null)
  /** 历史回显时序守卫：点击「重新提取」后，在途的回显结果作废 */
  const historyEpoch = useRef(0)

  /** 进入页面时回显：该 chunk 提交过知识提取则加载记录中的三元组（消歧标签照常评估，卡片只读） */
  useEffect(() => {
    const epoch = ++historyEpoch.current
    getChunkExtraction(ctx.chunk.id)
      .then((rec) => {
        if (epoch !== historyEpoch.current || !rec || rec.triples.length === 0) return
        return disambiguateTriples(rec.triples)
          .catch(() => [] as TripleEval[])
          .then((evals) => {
            if (epoch !== historyEpoch.current) return
            setRows(
              rec.triples.map((t, i) => ({
                key: i,
                triple: t,
                checked: true,
                evaluating: false,
                eval: evals[i] ?? null,
                lastEval: t,
                propsOff: new Set<string>(),
              })),
            )
            setHistory({ createdAt: rec.createdAt })
          })
      })
      .catch((e: unknown) => {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
        }
      })
  }, [ctx.chunk.id])

  /** 单条三元组重新评估（编辑/改名后） */
  const evaluateRow = (key: number, triple: Triple) => {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, evaluating: true } : r)))
    disambiguateTriples([triple])
      .then((evals) => {
        setRows((rs) =>
          rs.map((r) =>
            r.key === key
              ? { ...r, evaluating: false, eval: evals[0] ?? null, lastEval: triple }
              : r,
          ),
        )
      })
      .catch((e: unknown) => {
        setRows((rs) => rs.map((r) => (r.key === key ? { ...r, evaluating: false } : r)))
        setToast({ kind: 'error', text: errOf(e, '评估失败') })
      })
  }

  /** 字段提交（失焦）：更新显示值；与上次评估快照不同则重新消歧 */
  const commitField = (key: number, patch: Partial<Triple>) => {
    const row = rows.find((r) => r.key === key)
    if (!row) return
    const next = { ...row.triple, ...patch }
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, triple: next } : r)))
    if (!row.evaluating && (!row.lastEval || tripleDiff(next, row.lastEval))) {
      evaluateRow(key, next)
    }
  }

  /** 知识提取：组合串 → LLM 抽三元组 → 逐条消歧评估（历史回显态下即「重新提取」，覆盖旧回显） */
  const extract = async () => {
    if (extracting) return
    historyEpoch.current++
    setExtracting(true)
    setToast(null)
    setCommitted(null)
    setHistory(null)
    setRows([])
    try {
      const resp = await analyzeDocument(contextString)
      if (resp.triples.length === 0) {
        setToast({ kind: 'error', text: '未提取到任何三元组' })
        return
      }
      const evals = await disambiguateTriples(resp.triples)
      setRows(
        resp.triples.map((t, i) => ({
          key: i,
          triple: t,
          // 同关系重复（两端均存在且方向一致）默认不勾选
          checked: !isDuplicate(t, evals[i]),
          evaluating: false,
          eval: evals[i] ?? null,
          lastEval: t,
          propsOff: new Set<string>(),
        })),
      )
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast({ kind: 'error', text: errOf(e, '提取失败') })
    } finally {
      setExtracting(false)
    }
  }
  /** 点击实体打开查看弹窗：已存在实体 + 各疑似实体各一个 tab，均加载 1 跳邻域图 */
  const openInspect = async (row: Row, side: 'subject' | 'object') => {
    const lookup = row.eval?.[side]
    const targets: EntityInspect['targets'] = [
      ...(lookup?.exact ? [{ ent: lookup.exact, kind: 'exact' as const }] : []),
      ...(lookup?.candidates ?? []).map((c) => ({ ent: c, kind: 'candidate' as const })),
    ]
    setRename(row.triple[side])
    setInspect({
      rowKey: row.key,
      side,
      name: row.triple[side],
      targets,
      tab: 0,
      graphs: targets.map(() => null),
      totals: targets.map(() => 0),
      loading: targets.length > 0,
      locked: !!committed || !!history,
    })
    if (targets.length === 0) return
    try {
      const loaded = await Promise.all(targets.map((t) => graphNeighbors(t.ent.id, 1)))
      // 邻居过多时截取前 MAX_NEIGHBORS 个，避免小图里节点挤成一团
      const graphs = loaded.map((g, i) => trimNeighbors(g, targets[i].ent.id, MAX_NEIGHBORS))
      const totals = loaded.map((g) => Math.max(0, g.nodes.length - 1))
      setInspect((s) =>
        s && s.rowKey === row.key && s.side === side ? { ...s, graphs, totals, loading: false } : s,
      )
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      // 图谱加载失败：关闭弹窗（窗内无错误态可展示），错误经 message box 提示
      setInspect(null)
      setToast({ kind: 'error', text: errOf(e, '图谱加载失败') })
    }
  }

  /** 忽略/恢复一条待添加属性（被忽略的属性不随提交写入） */
  const toggleProp = (rowKey: number, key: string) => {
    setRows((rs) =>
      rs.map((r) => {
        if (r.key !== rowKey) return r
        const propsOff = new Set(r.propsOff)
        if (propsOff.has(key)) propsOff.delete(key)
        else propsOff.add(key)
        return { ...r, propsOff }
      }),
    )
  }

  /** 弹窗内合并/重命名 → 替换该行实体名并重评估 */
  const pickEntity = (rowKey: number, side: 'subject' | 'object', name: string) => {
    const row = rows.find((r) => r.key === rowKey)
    if (!row || row.triple[side] === name) return
    const next = { ...row.triple, [side]: name }
    setRows((rs) => rs.map((r) => (r.key === rowKey ? { ...r, triple: next } : r)))
    if (!row.evaluating) {
      evaluateRow(rowKey, next)
    }
  }

  /** 提交勾选的三元组（propsOff 中被忽略的属性不随提交写入） */
  const commit = async () => {
    if (committing || committed) return
    const picked = rows
      .filter((r) => r.checked)
      .map((r) => {
        if (r.propsOff.size === 0) return r.triple
        return {
          ...r.triple,
          subjectProps: r.triple.subjectProps?.filter((p) => !r.propsOff.has(`s:${p.k}`)),
          objectProps: r.triple.objectProps?.filter((p) => !r.propsOff.has(`o:${p.k}`)),
        }
      })
    if (picked.length === 0) return
    setCommitting(true)
    try {
      const r = await commitChunkTriples(ctx.chunk.id, picked)
      setCommitted({ nodes: r.nodes, edges: r.edges, duplicates: r.duplicates, props: r.props })
      setToast({
        kind: 'success',
        text: (
          <>
            已写入 <span className="font-semibold">{r.nodes}</span> 个新实体、
            <span className="font-semibold">{r.edges}</span> 条关系、
            <span className="font-semibold">{r.props}</span> 项实体属性
            {r.duplicates > 0 && `（重复 ${r.duplicates} 条已忽略）`}
            ，chunk #{ctx.chunk.id} 保持未分析状态，完成下一阶段「Chunk 增强」后才启用。
          </>
        ),
      })
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast({ kind: 'error', text: errOf(e, '提交失败') })
    } finally {
      setCommitting(false)
    }
  }

  /** 返回选择：已解析出且未提交的三元组会随返回丢弃，需先确认；否则直接返回 */
  const back = () => {
    if (rows.length > 0 && !committed && !history) {
      if (!window.confirm('已解析出的三元组尚未提交，返回将丢弃这些结果。确定返回？')) {
        return
      }
    }
    onBack()
  }

  const checkedCount = rows.filter((r) => r.checked).length

  return (
    <div className="flex flex-col gap-5">
      {/* 主区域：左（父文档摘要 + Chunk 原文，标题栏最右为提取按钮）右（提取到的三元组） */}
      <div className="flex items-stretch gap-5">
        {/* 左：上下文区 */}
        <div className="flex min-w-165 flex-1 flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex min-w-0 flex-col">
              <h2 className="ui-heading text-base">「{ctx.docTitle}」· chunk #{ctx.chunk.id}</h2>
            </div>
            <button
              type="button"
              onClick={() => void extract()}
              disabled={extracting || (!!committed && !history)}
              className={`${btnPrimary('rounded-md')} shrink-0 gap-2`}
            >
              {extracting && <Spinner className="h-3.5 w-3.5" />}
              {extracting ? '大模型提取中 …' : history ? '重新提取' : '知识提取'}
            </button>
          </div>

          {/* 父文档摘要（LLM 生成的全文概述，提取时组装进上下文补完串） */}
          <p className="ui-label">父文档摘要</p>
          <div className="scrollbar-slim max-h-32 overflow-y-auto overflow-x-hidden whitespace-pre-wrap break-words rounded-md border border-line bg-fg/[0.02] p-3 text-sm leading-relaxed text-muted">
            {ctx.contextualizing || '（暂无概述）'}
          </div>

          {/* Chunk 原文（横向自动换行，竖向滚动条；提取时仍组装上下文补完串送入 LLM） */}
          <p className="ui-label">Chunk 原文</p>
          <div className="scrollbar-slim max-h-96 overflow-y-auto overflow-x-hidden whitespace-pre-wrap break-words rounded-md border border-line bg-fg/[0.02] p-3.5 text-sm leading-relaxed text-muted">
            {ctx.chunk.content}
          </div>
        </div>

        {/* 右：提取到的三元组横长条卡片（stack）+ 提交 */}
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="ui-heading text-base">
              三元组（{rows.length}）{history ? ' · 已提交' : ` · 勾选 ${checkedCount}`}
            </h2>
            {history ? (
              <p className="text-sm text-faint">
                该 chunk 已于 {fmtTime(history.createdAt)} 提交过知识提取，以下为当时写入图的三元组（只读回显）；
                点击「重新提取」可再次抽取并覆盖记录。
              </p>
            ) : (
              <p className="text-sm text-faint">
                点击实体可查看 1 跳图谱并合并/重命名；点击关系可编辑名称，失焦后自动重新消歧。
              </p>
            )}
          </div>
          {rows.length > 0 ? (
            <div className="scrollbar-slim flex max-h-[32rem] flex-col gap-2 overflow-y-auto pr-0.5">
              {rows.map((row) => (
                <TripleCard
                  key={row.key}
                  row={row}
                  committed={!!committed || !!history}
                  onCheck={(key, checked) =>
                    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, checked } : r)))
                  }
                  onCommitField={commitField}
                  onInspect={(r, side) => void openInspect(r, side)}
                  onToggleProp={toggleProp}
                />
              ))}
            </div>
          ) : (
            <div className="flex-1">
              <kit.Empty>
                尚未提取到三元组——点击左侧「知识提取」，由大模型从 Chunk 原文中抽取实体与关系。
              </kit.Empty>
            </div>
          )}
        </div>
      </div>

      {/* Footer：返回选择（左） / 提交 + 下一步（右），分隔线形态随 kit.Toolbar */}
      <kit.Toolbar
        left={
          <button type="button" onClick={back} className={btnOutline('rounded-md')}>
            ← 返回选择
          </button>
        }
        right={
          <>
            {rows.length > 0 && !committed && (
              <button
                type="button"
                onClick={() => void commit()}
                disabled={committing || checkedCount === 0}
                className={`${btnPrimary('rounded-md')} gap-2`}
              >
                {committing && <Spinner className="h-3.5 w-3.5" />}
                {committing ? '提交中 …' : `提交（${checkedCount}）`}
              </button>
            )}
            <button
              type="button"
              onClick={onAdvance}
              title="携带当前 chunk 进入 Chunk 增强（步骤 3）"
              className={btnOutline('rounded-md')}
            >
              下一步 →
            </button>
          </>
        }
      />

      {/* 轻提示（message box）：提交成功 / 提取·评估·提交失败，视口顶部居中悬浮 */}
      <Toast kind={toast?.kind} message={toast?.text ?? null} onClose={() => setToast(null)} />

      {/* 实体查看弹窗：tab 切换已存在/疑似实体的 1 跳邻域图；疑似可合并，支持重命名重新消歧 */}
      {inspect && (
        <FloatWindow title={`实体 · ${inspect.name}`} width={560} onClose={() => setInspect(null)}>
          <div className="flex flex-col gap-3">
            {inspect.targets.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5">
                {inspect.targets.map((t, i) => (
                  <button
                    key={t.ent.id}
                    type="button"
                    onClick={() => setInspect((s) => (s ? { ...s, tab: i } : s))}
                    className={`rounded-sm px-2 py-1 text-sm transition ${
                      inspect.tab === i
                        ? 'bg-fg/[0.08] font-medium text-fg'
                        : 'text-muted hover:text-fg'
                    }`}
                  >
                    {t.kind === 'exact' ? '已存在 · ' : '疑似 · '}
                    {t.ent.name}
                  </button>
                ))}
              </div>
            )}
            {inspect.loading ? (
              <div className="flex flex-col items-center gap-3 py-10 text-faint">
                <Spinner className="h-5 w-5 text-muted" />
                <p className="text-sm">加载 1 跳邻域子图 …</p>
              </div>
            ) : inspect.targets.length === 0 ? (
              <p className="py-8 text-center text-sm leading-relaxed text-faint">
                图中不存在该实体，也无疑似同名实体——提交后将作为新实体写入。
              </p>
            ) : (
              <div className="flex flex-col gap-1.5">
                <div className="rounded-md border border-line bg-bg p-1">
                  {inspect.graphs[inspect.tab]?.edges.length ||
                  (inspect.graphs[inspect.tab]?.nodes.length ?? 0) > 1 ? (
                    <GraphMini
                      nodes={inspect.graphs[inspect.tab]!.nodes}
                      edges={inspect.graphs[inspect.tab]!.edges}
                      emphasized={new Set([inspect.targets[inspect.tab].ent.id])}
                    />
                  ) : (
                    <p className="py-8 text-center text-sm text-faint">该实体暂无邻域关系</p>
                  )}
                </div>
                {inspect.totals[inspect.tab] >
                  (inspect.graphs[inspect.tab]?.nodes.length ?? 0) - 1 && (
                  <p className="text-xs leading-relaxed text-faint">
                    该实体共 {inspect.totals[inspect.tab]} 个直接关联，图幅有限仅展示前{' '}
                    {Math.max(0, (inspect.graphs[inspect.tab]?.nodes.length ?? 1) - 1)} 个；
                    完整邻域请前往图谱页查看。
                  </p>
                )}
              </div>
            )}
            {!inspect.locked && (
              <div className="flex flex-col gap-2.5 border-t border-line pt-2.5">
                {inspect.targets[inspect.tab]?.kind === 'candidate' && (
                  <button
                    type="button"
                    onClick={() => {
                      pickEntity(inspect.rowKey, inspect.side, inspect.targets[inspect.tab].ent.name)
                      setInspect(null)
                    }}
                    title="将该端实体名替换为疑似实体（视为同一实体合并），替换后重新消歧"
                    className={btnPrimary('rounded-md')}
                  >
                    合并为「{inspect.targets[inspect.tab].ent.name}」
                  </button>
                )}
                <div className="flex gap-2">
                  <input
                    className={inputCls}
                    value={rename}
                    onChange={(e) => setRename(e.target.value)}
                    placeholder="实体名称（重命名后自动重新消歧）"
                  />
                  <button
                    type="button"
                    disabled={rename.trim() === '' || rename.trim() === inspect.name}
                    onClick={() => {
                      pickEntity(inspect.rowKey, inspect.side, rename.trim())
                      setInspect(null)
                    }}
                    className={`${btnOutline('rounded-md')} shrink-0`}
                  >
                    重命名
                  </button>
                </div>
              </div>
            )}
          </div>
        </FloatWindow>
      )}
    </div>
  )
}

/** SVG 文本截断（超长省略，完整内容见悬浮提示） */
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, Math.max(1, n - 1))}…` : s)

/** 节点状态色（正下方状态标签用，表达消歧结果）：
 *  已存在=翠绿 / 疑似=琥珀 / 新实体=湛蓝 / 未评估=石板灰 */
function nodeColor(
  lookup: { exact: EntityLite | null; candidates: EntityLite[] } | null | undefined,
): string {
  if (!lookup) return '#94a3b8'
  if (lookup.exact) return '#10b981'
  if (lookup.candidates.length > 0) return '#f59e0b'
  return '#3b82f6'
}

/** 实体配色池（伪随机分配的醒目色，避开状态色系歧义可接受） */
const ENTITY_PALETTE = [
  '#0ea5e9', '#8b5cf6', '#ec4899', '#f97316',
  '#14b8a6', '#f43f5e', '#6366f1', '#84cc16',
  '#06b6d4', '#d946ef', '#a855f7', '#e11d48',
] as const

/** 实体名 → 稳定颜色：djb2 哈希取模选色，不同实体近似随机分配不同色，
 *  同名实体（跨卡片、跨重渲染）恒定同色，便于一眼追踪同一实体 */
function entityColor(name: string): string {
  let h = 5381
  for (let i = 0; i < name.length; i++) {
    h = ((h << 5) + h + name.charCodeAt(i)) >>> 0
  }
  return ENTITY_PALETTE[h % ENTITY_PALETTE.length]
}

/** 三元组横长条卡片：主体为「点—边—点」SVG（类 graph 渲染），实体存在性/疑似标签直接标在
 *  对应实体正下方（同关系重复标在关系正下方）；点击实体打开 1 跳邻域查看弹窗、点击关系原位编辑；
 *  待添加 props 以图右上角闪烁蓝点承载（hover 展开标签气泡，点击标签忽略对应属性）；
 *  最右侧小区域为提交勾选框。 */
function TripleCard({
  row,
  committed,
  onCheck,
  onCommitField,
  onInspect,
  onToggleProp,
}: {
  row: Row
  committed: boolean
  onCheck: (key: number, checked: boolean) => void
  onCommitField: (key: number, patch: Partial<Triple>) => void
  onInspect: (row: Row, side: 'subject' | 'object') => void
  onToggleProp: (rowKey: number, key: string) => void
}) {
  const ev = row.eval
  const dup = ev ? isDuplicate(row.triple, ev) : false
  /** 关系原位编辑态 */
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const uid = useId().replace(/[^a-zA-Z0-9]/g, '')
  const arrowId = `tri-arr-${uid}`

  /** 待添加属性（实体1/实体2 两侧合并；key 与 propsOff 对应，用于忽略标记） */
  const pendingProps: { key: string; side: 'subject' | 'object'; p: Prop }[] = [
    ...(row.triple.subjectProps ?? []).map((p) => ({ key: `s:${p.k}`, side: 'subject' as const, p })),
    ...(row.triple.objectProps ?? []).map((p) => ({ key: `o:${p.k}`, side: 'object' as const, p })),
  ]

  /** 实体状态短标签（标在实体正下方，颜色与节点圆点一致） */
  const statusOf = (side: 'subject' | 'object') => {
    const lookup = ev?.[side]
    if (!lookup) return '未评估'
    if (lookup.exact) return '已存在'
    if (lookup.candidates.length > 0) return `${lookup.candidates.length} 疑似`
    return '新实体'
  }

  const startEdit = () => {
    if (committed) return
    setDraft(row.triple.predicate)
    setEditing(true)
  }

  /** 结束编辑：submit 时取 trim 后非空且发生变化的值提交（触发重新消歧） */
  const finishEdit = (submit: boolean) => {
    if (!editing) return
    setEditing(false)
    const v = draft.trim()
    if (submit && v && v !== row.triple.predicate) {
      onCommitField(row.key, { predicate: v })
    }
  }

  return (
    <div
      className={`flex items-center gap-2.5 rounded-md border px-3 py-2 transition ${
        dup
          ? 'border-hint/40 bg-hint/5'
          : row.checked
            ? 'border-line bg-bg'
            : 'border-line bg-bg opacity-60'
      }`}
    >
      {/* 点—边—点（类 graph 渲染）：存在性/疑似标签在实体正下方，同关系重复在关系正下方 */}
      <div className="relative min-w-0 flex-1">
        <svg
          viewBox="0 0 320 106"
          className="block h-auto w-full"
          role="img"
          aria-label={`${row.triple.subject} —${row.triple.predicate}→ ${row.triple.object}`}
        >
          <defs>
            <marker
              id={arrowId}
              viewBox="0 0 8 8"
              refX="8"
              refY="4"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M0,0 L8,4 L0,8 z" style={{ fill: 'var(--c-line-strong)' }} />
            </marker>
          </defs>
          {/* 边（关系）：带箭头直线 + 关系名，点击原位编辑；悬浮展示两实体间已有关系 */}
          <g
            onClick={committed ? undefined : startEdit}
            style={{ cursor: committed ? 'default' : 'pointer' }}
          >
            <title>
              {`关系（点击编辑）：${row.triple.predicate}`}
              {ev && ev.edges.length > 0
                ? `\n两实体间已有关系：\n${ev.edges
                    .map((e) => `${e.subjectName} —${e.predicate}→ ${e.objectName}`)
                    .join('\n')}`
                : ''}
            </title>
            <line
              x1="75.5"
              y1="36"
              x2="244.5"
              y2="36"
              strokeWidth="1.5"
              markerEnd={`url(#${arrowId})`}
              style={{ stroke: 'var(--c-line-strong)' }}
            />
            <rect x="100" y="8" width="120" height="56" fill="transparent" />
            <text
              x="160"
              y="25"
              fontSize="16"
              fontWeight="500"
              textAnchor="middle"
              style={{
                paintOrder: 'stroke',
                stroke: 'var(--c-bg)',
                strokeWidth: 3,
                fill: row.triple.predicate ? 'var(--c-muted)' : 'var(--c-faint)',
              }}
            >
              {clip(row.triple.predicate || '关系', 9)}
            </text>
          </g>
          {/* 同关系重复：标在关系正下方 */}
          {dup && (
            <g>
              <rect
                x="140"
                y="43"
                width="40"
                height="15"
                rx="2"
                style={{ fill: 'var(--c-hint)', opacity: 0.12 }}
              />
              <text
                x="160"
                y="54.5"
                fontSize="14"
                fontWeight="500"
                textAnchor="middle"
                style={{ fill: 'var(--c-hint)' }}
              >
                重复
              </text>
            </g>
          )}
          {/* 点（实体1/实体2）：圆点 + 名称按实体名哈希取色（同名恒定同色），
              正下方状态标签保留消歧状态色，点击打开查看弹窗 */}
          {(
            [
              ['subject', 48, '实体1'],
              ['object', 272, '实体2'],
            ] as const
          ).map(([field, cx, label]) => (
            <g key={field} onClick={() => onInspect(row, field)} style={{ cursor: 'pointer' }}>
              <title>{`${label}（点击查看 1 跳图谱）：${row.triple[field]}`}</title>
              <circle
                cx={cx}
                cy="36"
                r="26"
                strokeWidth="1.5"
                style={{ fill: entityColor(row.triple[field]), stroke: 'var(--c-bg)' }}
              />
              <circle cx={cx} cy="36" r="42" fill="transparent" />
              <text
                x={cx}
                y="82"
                fontSize="16"
                fontWeight="500"
                textAnchor="middle"
                style={{
                  paintOrder: 'stroke',
                  stroke: 'var(--c-bg)',
                  strokeWidth: 3,
                  fill: entityColor(row.triple[field]),
                }}
              >
                {clip(row.triple[field], 9)}
              </text>
              <text
                x={cx}
                y="98"
                fontSize="14"
                textAnchor="middle"
                style={{
                  paintOrder: 'stroke',
                  stroke: 'var(--c-bg)',
                  strokeWidth: 3,
                  fill: nodeColor(ev?.[field]),
                }}
              >
                {statusOf(field)}
              </text>
            </g>
          ))}
        </svg>
        {/* 关系原位编辑输入框（覆盖在边上方） */}
        {editing && (
          <input
            className="absolute left-[28%] right-[28%] top-1/3 z-10 -translate-y-1/2 rounded-full border border-line-strong bg-bg px-2 py-0.5 text-center text-sm text-fg outline-none"
            placeholder="关系"
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => finishEdit(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') e.currentTarget.blur()
              if (e.key === 'Escape') finishEdit(false)
            }}
          />
        )}
        {/* 待添加 props：图右上角闪烁蓝点，hover 展开标签气泡（点击标签忽略对应属性） */}
        {pendingProps.length > 0 && (
          <div className="group/dot absolute right-1 top-1 z-10">
            <span
              className="block h-2.5 w-2.5 animate-pulse rounded-full bg-blue-500"
              title={`待添加属性（${pendingProps.length} 项，hover 查看）`}
            />
            <div className="absolute right-0 top-2.5 z-20 hidden w-max max-w-72 flex-wrap items-center gap-1 rounded-md border border-line bg-bg p-2 shadow-md group-hover/dot:flex">
              {pendingProps.map(({ key, side, p }) => {
                const off = row.propsOff.has(key)
                return (
                  <button
                    key={key}
                    type="button"
                    disabled={committed}
                    onClick={() => onToggleProp(row.key, key)}
                    title={off ? '已忽略（点击恢复随提交写入）' : '点击忽略（该属性不随提交写入）'}
                    className={`rounded-sm px-1.5 py-0.5 text-left text-xs font-medium transition ${
                      off
                        ? 'bg-fg/[0.06] text-faint line-through opacity-70'
                        : 'hover:opacity-80 disabled:hover:opacity-100'
                    }`}
                    style={off ? undefined : { backgroundColor: 'rgba(139,92,246,0.12)', color: '#8b5cf6' }}
                  >
                    {side === 'subject' ? '实体1' : '实体2'}·{p.k}:{p.v}
                  </button>
                )
              })}
              <p className="w-full text-xs leading-relaxed text-faint">
                点击标签可忽略对应属性，被忽略的不会随提交写入。
              </p>
            </div>
          </div>
        )}
      </div>
      {/* 最右侧小区域：提交勾选框 */}
      <div className="flex shrink-0 items-center border-l border-line/60 pl-2.5">
        <input
          type="checkbox"
          checked={row.checked}
          disabled={committed}
          onChange={(e) => onCheck(row.key, e.target.checked)}
          className="h-3 w-3 accent-[var(--c-accent)]"
          aria-label="勾选该三元组"
        />
      </div>
    </div>
  )
}

