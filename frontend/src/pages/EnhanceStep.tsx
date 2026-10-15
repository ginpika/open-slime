import { AppIcon } from '../components/AppIcon'
import { useEffect, useState, type ReactNode } from 'react'
import { DraftNotice } from '../components/DraftNotice'
import { useDraftState, useRememberedState } from '../components/windowState'
import {
  genChunkQuestions,
  genChunkSummary,
  getChunkExtraction,
  listDocChunks,
  loginUrlOf,
  saveChunkPlan,
  submitChunkPlan,
  type ChunkItem,
  type ChunkSubmitResult,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import SegmentEditor, { type EditorSegment } from '../components/SegmentEditor'
import Toast, { type ToastKind } from '../components/Toast'
import { btnOutline, btnPrimary } from '../components/ui'
import { usePageKit } from '../kit'
import type { ExtractCtx } from './ExtractStep'

/**
 * 步骤 3：Chunk 增强（stack 布局，自上而下）。
 * ① 增强栏目按钮（横向排列，生成即落库 chunks.header/questions，二次进入自动回显）：
 *    「Chunk Header 生成」：组装 RAG 语义定位头串（多行 [breadcrumb]/[doc_type]/[position]/[summary]/[doc]/[entity]/[ingest_time]/[adjacent]）：
 *    [breadcrumb] 章节层级路径——父文档标题 + 由父文档各 chunk 内容里的 markdown 标题行按序重建的标题栈（越级截断）；
 *    [doc_type] 父文档类型（TECHNICAL/NARRATIVE/CONVERSATIONAL，摘要生成时 LLM 判定，未判定则整行省略）；
 *    [position] 段落序号/总段数 + 块类型（代码/表格/列表/正文，按内容粗判）+ 字数；
 *    [summary] 段落一句话摘要（LLM 生成，失败则降级省略）；
 *    [doc] 父文档全文概述（截断）；
 *    [entity] 关联实体 top5——在上次提交图谱的日志（chunk_extraction）频次基础上，
 *    叠加正文命中次数/标题命中/类型权重排序，并标注实体类型；
 *    [ingest_time] 段落入库时间（chunks.created_at，非内容发布时间，仅供模型区分"何时进入知识库"）；
 *    [event_time] 段落内容实际发生的时间（正文所述事件的日期/时间点或区间），语义上区别于 ingest_time：
 *    前者是"知识何时进库"，后者是"事情何时发生"。暂无数据来源（正文时间尚未抽取），规划中，
 *    未来接入后与 ingest_time 并列输出，找不到则整行省略；
 *    [adjacent] 文档内前后相邻段落 id 指针。头串插入最终提交预案最上方（已存在则覆盖刷新）；
 *    「生成假设性问题」：LLM 生成 5 个该段落能解答的问题，以 [questions] 编号列表
 *    插入 Header 与 chunk 正文之间（重复点击覆盖刷新）；
 * ② 最终提交预案：定制化分段文本编辑框（进入页面按库中留档回显，无留档则仅 chunk 原文为单段；
 *    hover 某部分出现其悬浮提示，点击该部分进入编辑、点击框外回到预览；部分后续将由按钮增删）；
 * ③ footer：左「上一步」回到知识提取；右「提交 Chunk」→ 拼接预案全文（三分节）弹窗预览确认
 *    → 确认后向量化写 chunk_embedding + 分段留档 + chunk 启用，知识入库流程完成。
 */

/** 进入步骤 3 的上下文（同步骤 2：选中 chunk + 父文档标题与全文概述） */
export type EnhanceCtx = ExtractCtx

type Props = {
  ctx: EnhanceCtx
  /** 「上一步」回到知识提取 */
  onPrev?: () => void
}

/** Chunk Header 部分的固定 id（重复生成时覆盖刷新而非追加） */
const HEADER_ID = 'chunk-header'

/** 假设性问题部分的固定 id（重复生成时覆盖刷新而非追加） */
const QUESTION_ID = 'hypothetical-questions'

/** 由 chunk 快照构建预案分段：留档 Header/假设性问题（若有，生成时落库）+ chunk 原文 */
const planSegmentsOf = (chunk: ChunkItem): EditorSegment[] => [
  ...(chunk.header ? [{ id: HEADER_ID, label: 'Chunk Header', text: chunk.header }] : []),
  ...(chunk.questions
    ? [{ id: QUESTION_ID, label: '假设性问题', text: chunk.questions }]
    : []),
  { id: `chunk-${chunk.id}`, label: 'Chunk 原文', text: chunk.content },
]

/** 分节线：=================== [SECTION] =================== */
const sectionLine = (label: string) => `=================== [${label}] ===================`

/** 预案分段 → 最终提交全文：按 HEADER & METADATA / HYPOTHETICAL QUESTIONS / PRIMARY CONTENT
 *  三分节拼接（分节间空一行；缺失的分节整体跳过） */
const buildPlanText = (segs: EditorSegment[], origId: string): string => {
  const byId = new Map(segs.map((s) => [s.id, s.text]))
  return (
    [
      ['HEADER & METADATA', byId.get(HEADER_ID)],
      ['HYPOTHETICAL QUESTIONS', byId.get(QUESTION_ID)],
      ['PRIMARY CONTENT', byId.get(origId)],
    ] as [string, string | undefined][]
  )
    .filter(([, text]) => text !== undefined)
    .map(([label, text]) => `${sectionLine(label)}\n${text ?? ''}`)
    .join('\n\n')
}

/** Spinner 圆环（加载中动画） */
function Spinner({ className = 'h-4 w-4' }: { className?: string }) {
  return (
    <AppIcon name="loading" className={`animate-spin ${className}`} />
  )
}

function errOf(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback
}

/* ---------- Chunk Header 语义定位信息（章节路径 / 块类型 / 实体排序） ---------- */

/** markdown 标题：level=1~6，text=标题文本 */
type Heading = { level: number; text: string }

/** 解析 markdown 标题行（# 后至少一个空格）；非标题返回 null */
const parseHeading = (line: string): Heading | null => {
  const m = /^(#{1,6})\s+(.+)$/.exec(line.trim())
  return m ? { level: m[1].length, text: m[2].trim() } : null
}

/**
 * 重建目标 chunk 的章节层级路径（breadcrumb）：
 * 按文档顺序线性扫描各 chunk 内容，维护标题层级栈（同级/更高级标题先出栈，越级自动截断）；
 * 在目标 chunk 首行正文（非标题行）处定格当前标题链；跳过代码围栏内的 "# " 行。
 */
const breadcrumbOf = (chunks: ChunkItem[], targetId: number): string[] => {
  const stack: Heading[] = []
  let inFence = false
  let crumb: string[] | null = null
  for (const c of chunks) {
    for (const line of c.content.split('\n')) {
      const t = line.trim()
      if (/^(```|~~~)/.test(t)) {
        inFence = !inFence
        continue
      }
      if (inFence) continue
      const h = parseHeading(t)
      if (h) {
        while (stack.length > 0 && stack[stack.length - 1].level >= h.level) stack.pop()
        stack.push(h)
        continue
      }
      if (t !== '' && crumb === null && c.id === targetId) {
        crumb = stack.map((x) => x.text)
      }
    }
    if (c.id === targetId && crumb === null) crumb = stack.map((x) => x.text)
  }
  return crumb ?? []
}

/** 粗判 chunk 主体形态（优先级：代码 > 表格 > 列表 > 正文） */
const blockTypeOf = (content: string): string => {
  const lines = content.split('\n')
  if (lines.some((l) => /^\s*(```|~~~)/.test(l))) return '代码'
  if (
    lines.some((l) => {
      const t = l.trim()
      return t.includes('|') && /^[|\s:-]+$/.test(t) && t.split('|').length >= 3 && t.includes('-')
    })
  ) {
    return '表格'
  }
  const nonBlank = lines.filter((l) => l.trim() !== '')
  const bullets = nonBlank.filter((l) => /^\s*([-*+]|\d+[.)])\s+/.test(l)).length
  if (nonBlank.length > 0 && bullets / nonBlank.length >= 0.6) return '列表'
  return '正文'
}

/** 实体候选（Header 实体排序用）：freq=图谱日志出现次数，type=规范化类型（可能为空） */
type EntityCand = { name: string; type: string; freq: number }

/** 实体重要性打分：图谱频次 + 正文命中次数 + 章节标题命中 + 类型权重（人名/作品等实体优于泛概念） */
const rankEntities = (
  cands: EntityCand[],
  content: string,
  crumb: string[],
  n: number,
): EntityCand[] => {
  const titleText = crumb.join(' ')
  const notable = /^(CHARACTER|PERSON|ORG|ORGANIZATION|WORK|PLACE|ITEM|EVENT|PROJECT|PRODUCT)$/
  const score = (c: EntityCand) => {
    const hits = c.name ? content.split(c.name).length - 1 : 0
    const inTitle = c.name && titleText.includes(c.name) ? 2 : 0
    const typeBonus = notable.test(c.type.toUpperCase()) ? 1 : 0
    return c.freq + hits * 1.5 + inTitle + typeBonus
  }
  return [...cands].sort((a, b) => score(b) - score(a)).slice(0, n)
}

/** 截断超长文本（超出加省略号） */
const truncate = (s: string, max: number): string =>
  s.length <= max ? s : `${s.slice(0, max).trimEnd()}…`

/** epoch 毫秒 → YYYY-MM-DD（空值返回空串） */
const fmtDate = (ms: number): string => {
  if (!ms) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export default function EnhanceStep({ ctx, onPrev }: Props) {
  const kit = usePageKit()
  /** 最终提交预案的分段（初始按 chunk 快照回显留档；后续将由按钮增删部分，文本均可就地编辑） */
  const [segments, setSegments, segmentsState] = useDraftState<EditorSegment[]>(`enhance:${ctx.chunk.id}:segments`, () => planSegmentsOf(ctx.chunk))
  const initializeSegments = segmentsState.initialize
  /** 提交预览全文（打开确认窗口时填充；null = 窗口关闭） */
  const [preview, setPreview] = useState<{ text: string; segments: EditorSegment[] } | null>(null)
  const [submitting, setSubmitting] = useRememberedState(`enhance:${ctx.chunk.id}:submitting`, false)
  /** 已提交结果（成功后填充，禁止重复提交；重新生成不解除） */
  const [submitted, setSubmitted] = useRememberedState<ChunkSubmitResult | null>(`enhance:${ctx.chunk.id}:submitted`, null)
  /** 轻提示（message box）：提交成功 / 生成·留档·提交失败，替代页内文案与横幅 */
  const [toast, setToast] = useState<{ kind: ToastKind; text: ReactNode } | null>(null)

  /** 切换 chunk 时在渲染期重置预案分段（避免残留上一个 chunk 的编辑内容与提交态） */
  const [planChunkId, setPlanChunkId] = useState(ctx.chunk.id)
  if (planChunkId !== ctx.chunk.id) {
    setPlanChunkId(ctx.chunk.id)
    setPreview(null)
    setToast(null)
  }

  /** 进入页面时拉取最新 chunk：ctx.chunk 为步骤 1 快照，二次进入时以库中留档（chunks.header/questions）回显预案 */
  useEffect(() => {
    let alive = true
    listDocChunks(ctx.chunk.docId)
      .then((chunks) => {
        const fresh = chunks.find((c) => c.id === ctx.chunk.id)
        if (alive && fresh && !submitted) initializeSegments(planSegmentsOf(fresh))
      })
      .catch((e: unknown) => {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) window.location.assign(loginUrl)
      })
    return () => {
      alive = false
    }
  }, [ctx.chunk.id, ctx.chunk.docId, initializeSegments, submitted])

  const [headerLoading, setHeaderLoading] = useRememberedState(`enhance:${ctx.chunk.id}:header-loading`, false)
  const [questionsLoading, setQuestionsLoading] = useRememberedState(`enhance:${ctx.chunk.id}:questions-loading`, false)

  /** 预案留档入库（生成成功后调用）；成功返回 null，失败返回错误文案（登录跳转除外） */
  const persistPlan = async (
    plan: { header?: string; questions?: string },
  ): Promise<string | null> => {
    try {
      await saveChunkPlan(ctx.chunk.id, plan)
      return null
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return null
      }
      return errOf(e, '入库失败')
    }
  }

  /** 预案总字数（各部分文本长度之和） */
  const planChars = segments.reduce((n, s) => n + s.text.length, 0)

  /**
   * Chunk Header 生成：组装 RAG 语义定位头串（章节路径/块形态/段落摘要/父文档概述/实体/时间/相邻块），
   * 插入预案最上方（重复点击覆盖刷新）。摘要走 LLM，失败时降级省略该行，不阻断整串生成。
   */
  const genHeader = async () => {
    if (headerLoading) return
    setHeaderLoading(true)
    try {
      // chunk 列表：定位段落序号/相邻块 + 重建章节路径 + 取规范化实体类型；
      // 提取日志（chunk_extraction）承载上次提交到图谱的三元组；段落摘要由 LLM 生成（失败降级为 null）
      const [chunks, rec, summary] = await Promise.all([
        listDocChunks(ctx.chunk.docId),
        getChunkExtraction(ctx.chunk.id),
        genChunkSummary(ctx.chunk.id)
          .then((r) => r.summary)
          .catch(() => null),
      ])
      const idx = chunks.findIndex((c) => c.id === ctx.chunk.id)
      const target = idx >= 0 ? chunks[idx] : ctx.chunk
      const content = target.content
      const crumb = breadcrumbOf(chunks, ctx.chunk.id)

      // 实体：图谱日志频次为底，叠加正文命中/标题命中/类型权重排序（详见 rankEntities）；
      // 类型优先取 chunk 关联实体（已入库的规范化类型），回退三元组自带类型
      const typeByName = new Map(target.entities.map((e) => [e.name, e.type]))
      const cands = new Map<string, EntityCand>()
      const addCand = (name: string, type: string | undefined) => {
        const n = name.trim()
        if (!n) return
        const known = typeByName.get(n) ?? type ?? ''
        const cur = cands.get(n)
        if (cur) {
          cur.freq += 1
          if (!cur.type) cur.type = known
        } else {
          cands.set(n, { name: n, type: known, freq: 1 })
        }
      }
      if (rec && rec.triples.length > 0) {
        for (const t of rec.triples) {
          addCand(t.subject, t.subjectType)
          addCand(t.object, t.objectType)
        }
      } else {
        for (const e of target.entities) addCand(e.name, e.type)
      }
      const top = rankEntities([...cands.values()], content, crumb, 5)

      // 逐行组装（空值行整体跳过）；[doc_type] 为父文档类型（摘要生成时 LLM 判定），未判定（null）整行省略
      const lines: string[] = [`[breadcrumb]${[`《${ctx.docTitle}》`, ...crumb].join(' > ')}`]
      if (ctx.docType) lines.push(`[doc_type]${ctx.docType}`)
      const total = chunks.length > 0 ? chunks.length : 1
      const nth = idx >= 0 ? idx + 1 : ctx.chunk.id
      lines.push(`[position]第 ${nth}/${total} 段 · ${blockTypeOf(content)} · ${content.length} 字`)
      if (summary) lines.push(`[summary]${summary}`)
      if (ctx.contextualizing) lines.push(`[doc]${truncate(ctx.contextualizing, 80)}`)
      lines.push(
        `[entity]${
          top.length > 0
            ? top.map((e) => (e.type ? `${e.name}(${e.type})` : e.name)).join('、')
            : '（暂无关联实体）'
        }`,
      )
      // 入库时间（chunks.created_at），非内容发布时间：命名为 ingest_time，避免模型误当内容时效
      const ingested = fmtDate(target.createdAt)
      if (ingested) lines.push(`[ingest_time]${ingested}`)
      const prev = idx > 0 ? chunks[idx - 1] : null
      const next = idx >= 0 && idx < chunks.length - 1 ? chunks[idx + 1] : null
      const neighbors = [prev ? `上一段 #${prev.id}` : '', next ? `下一段 #${next.id}` : '']
        .filter(Boolean)
      if (neighbors.length > 0) lines.push(`[adjacent]${neighbors.join(' · ')}`)
      const header = lines.join('\n')

      setSegments((ss) => [
        { id: HEADER_ID, label: 'Chunk Header', text: header },
        ...ss.filter((s) => s.id !== HEADER_ID),
      ])
      // 生成即落库（chunks.header），二次进入页面自动回显；入库失败不回滚编辑框内容，仅提示
      const saveErr = await persistPlan({ header })
      if (saveErr) setToast({ kind: 'error', text: `Header 已生成，但留档失败：${saveErr}` })
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast({ kind: 'error', text: errOf(e, 'Header 生成失败') })
    } finally {
      setHeaderLoading(false)
    }
  }

  /** 生成假设性问题：LLM 生成 5 个该段落能解答的问题，插入 Header 与 chunk 正文之间（重复点击覆盖刷新） */
  const genQuestions = async () => {
    if (questionsLoading) return
    setQuestionsLoading(true)
    try {
      const r = await genChunkQuestions(ctx.chunk.id)
      const text = `[questions]\n${r.questions.map((q, i) => `${i + 1}. ${q}`).join('\n')}`
      const origId = `chunk-${ctx.chunk.id}`
      setSegments((ss) => {
        const seg: EditorSegment = { id: QUESTION_ID, label: '假设性问题', text }
        const rest = ss.filter((s) => s.id !== QUESTION_ID)
        const at = rest.findIndex((s) => s.id === origId) // 正文之前 = Header（若已生成）之后
        return at < 0 ? [...rest, seg] : [...rest.slice(0, at), seg, ...rest.slice(at)]
      })
      // 生成即落库（chunks.questions），二次进入页面自动回显；入库失败不回滚编辑框内容，仅提示
      const saveErr = await persistPlan({ questions: text })
      if (saveErr) setToast({ kind: 'error', text: `问题已生成，但留档失败：${saveErr}` })
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast({ kind: 'error', text: errOf(e, '假设性问题生成失败') })
    } finally {
      setQuestionsLoading(false)
    }
  }

  /** 确认并提交（预览窗口内）：预案全文向量化写 chunk_embedding + 分段留档 + chunk 启用，知识入库流程结束 */
  const confirmSubmit = async () => {
    if (submitting || submitted || preview === null || segments !== preview.segments) return
    setSubmitting(true)
    try {
      const seg = (id: string) => segments.find((s) => s.id === id)?.text ?? null
      const r = await submitChunkPlan(ctx.chunk.id, {
        text: preview.text,
        // 用户改后的分段一并留档（该部分不存在时传 null = 不更新）
        header: seg(HEADER_ID),
        questions: seg(QUESTION_ID),
      })
      setSubmitted(r)
      segmentsState.accept(preview.segments, preview.segments)
      setPreview(null)
      setToast({
        kind: 'success',
        text: r.vectorized ? (
          <>
            预案全文已向量化写入 chunk_embedding（
            <span className="font-mono">{r.model}</span>，维度{' '}
            <span className="font-semibold">{r.dim}</span>），chunk #{ctx.chunk.id} 已启用，
            知识入库流程完成。
          </>
        ) : (
          <>embedding 未配置，跳过向量化（仅分段留档），chunk #{ctx.chunk.id} 已启用，知识入库流程完成。</>
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
      setSubmitting(false)
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <DraftNotice dirty={segmentsState.dirty} onDiscard={segmentsState.discard} disabled={submitting || headerLoading || questionsLoading || preview !== null} />
      {/* ① 增强栏目按钮（点缀色实底） */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => void genHeader()}
          disabled={headerLoading || submitting || !!submitted || preview !== null}
          className={`${btnOutline('rounded-md')} gap-2`}
        >
          {headerLoading && <Spinner className="h-3.5 w-3.5" />}
          {headerLoading ? '生成中 …' : 'Chunk Header 生成'}
        </button>
        <button
          type="button"
          onClick={() => void genQuestions()}
          disabled={questionsLoading || submitting || !!submitted || preview !== null}
          className={`${btnOutline('rounded-md')} gap-2`}
        >
          {questionsLoading && <Spinner className="h-3.5 w-3.5" />}
          {questionsLoading ? '生成中 …' : '生成假设性问题'}
        </button>
      </div>

      {/* ② 最终提交预案：定制化分段文本编辑框（hover 出提示、点击编辑、点框外回预览） */}
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="ui-heading text-sm">最终提交预案</h2>
          <p className="mono text-2xs text-faint">
            父文档「{ctx.docTitle}」· chunk #{ctx.chunk.id} · {planChars} 字
          </p>
        </div>
        <SegmentEditor
          segments={segments}
          readOnly={submitting || !!submitted || preview !== null}
          onChangeText={(id, text) =>
            setSegments((ss) => ss.map((s) => (s.id === id ? { ...s, text } : s)))
          }
        />
      </div>

      {/* ③ Footer：左「上一步」 / 右「提交 Chunk」→ 拼接预案全文弹窗预览确认，分隔线形态随 kit.Toolbar */}
      <kit.Toolbar
        left={
          onPrev ? (
            <button type="button" onClick={onPrev} className={btnOutline('rounded-md')}>
              ← 上一步
            </button>
          ) : undefined
        }
        right={
          <button
            type="button"
            onClick={() => setPreview({ text: buildPlanText(segments, `chunk-${ctx.chunk.id}`), segments })}
            disabled={submitting || headerLoading || questionsLoading || !!submitted}
            title="拼接最终提交预案全文，预览确认后提交"
            className={`${btnPrimary('rounded-md')} gap-2`}
          >
            {submitted ? '已提交' : '提交 Chunk'}
          </button>
        }
      />

      {/* 轻提示（message box）：提交成功 / 生成·留档·提交失败，视口顶部居中悬浮 */}
      <Toast kind={toast?.kind} message={toast?.text ?? null} onClose={() => setToast(null)} />

      {/* 提交预览确认窗口：三分节预案全文 + 右下角「确认并提交」 */}
      {preview !== null && (
        <FloatWindow
          title={`提交确认 · chunk #${ctx.chunk.id}`}
          width={640}
          onClose={() => setPreview(null)}
        >
          <div className="flex flex-col gap-3">
            <div className="scrollbar-slim max-h-[50vh] overflow-y-auto overflow-x-hidden whitespace-pre-wrap break-words rounded-md border border-line bg-fg/[0.02] p-3 font-mono text-xs leading-relaxed text-fg">
              {preview.text}
            </div>
            {segments !== preview.segments && <p role="status" className="text-sm text-warn">预案已更新，请关闭此窗口后重新预览。</p>}
            <div className="flex items-center justify-end">
              <button
                type="button"
                onClick={() => void confirmSubmit()}
                disabled={submitting || segments !== preview.segments}
                className={`${btnPrimary('rounded-md')} gap-2`}
              >
                {submitting && <Spinner className="h-3.5 w-3.5" />}
                {submitting ? '提交中 …' : '确认并提交'}
              </button>
            </div>
          </div>
        </FloatWindow>
      )}
    </div>
  )
}
