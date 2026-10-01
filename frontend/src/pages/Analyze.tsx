import { Fragment, useCallback, useEffect, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  deleteChunk,
  listDocChunks,
  listDocs,
  loginUrlOf,
  rechunkDoc,
  setChunkStatus,
  summarizeDoc,
  type ChunkEntityItem,
  type ChunkItem,
  type ChunkRelationItem,
  type DocItem,
  type DocType,
} from '../api/client'
import { btnPrimary, miniBtn } from '../components/ui'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import ExtractStep, { type ExtractCtx } from './ExtractStep'
import EnhanceStep, { type EnhanceCtx } from './EnhanceStep'
import { usePageKit } from '../kit'

/**
 * 文档分析页：三步工作流 —— 选择 Chunk → 知识提取 → Chunk 增强。
 *
 * <p>本页持有工作流时序与全部数据操作；步骤条、状态徽章、空态、面板与表格外壳
 * 全部来自 PageKit —— 于是同一套工作流在 flat 下是「表单式的表格页」、
 * 在 Endfield 下是「切角仪表盘 + 等宽读数」、在 BlueArchive 下是「窄栏卡片流」。
 */

function fmtTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 工作流三步标签（顺序即流程） */
const STEP_LABELS = ['选择 Chunk', '知识提取', 'Chunk 增强']

/** 文档状态：全部 chunk 均已分析（且至少一块）即「已分析」 */
function DocStatusBadge({ doc }: { doc: DocItem }) {
  const kit = usePageKit()
  const analyzed = doc.chunkCount > 0 && doc.analyzedCount >= doc.chunkCount
  return (
    <span title={`已分析 ${doc.analyzedCount} / ${doc.chunkCount} 块`}>
      <kit.Badge tone={analyzed ? 'ok' : 'default'}>{analyzed ? '已分析' : '未分析'}</kit.Badge>
    </span>
  )
}

/**
 * 文档类型标签（摘要生成时 LLM 判定）：取分类色板取色。
 * 这里刻意保留彩色：它是**语义色**（类型区分），不是装饰，所以不并入 kit.Badge 的通用色调。
 */
const DOC_TYPE_META: Record<DocType, { tip: string; cls: string }> = {
  TECHNICAL: {
    tip: '技术手册、规范、严谨知识',
    cls: 'border-entity-1/40 bg-entity-1/10 text-entity-1',
  },
  NARRATIVE: {
    tip: '主观类表达、叙述、个人博客、评论、发言',
    cls: 'border-entity-2/40 bg-entity-2/10 text-entity-2',
  },
  CONVERSATIONAL: {
    tip: '对话，聊天记录',
    cls: 'border-entity-3/40 bg-entity-3/10 text-entity-3',
  },
}

function DocTypeBadge({ type }: { type: DocType | null }) {
  if (!type) return null
  const meta = DOC_TYPE_META[type]
  return (
    <span
      title={`文档类型：${meta.tip}`}
      className={`inline-flex shrink-0 items-center whitespace-nowrap rounded-full border px-1.5 py-0.5 text-2xs leading-none font-medium ${meta.cls}`}
    >
      {type}
    </span>
  )
}

function ChunkStatusBadge({ status }: { status: ChunkItem['status'] }) {
  const kit = usePageKit()
  if (status === 'enabled') return <kit.Badge tone="ok">启用</kit.Badge>
  if (status === 'disabled') return <kit.Badge tone="default">禁用</kit.Badge>
  return <kit.Badge tone="default">未分析</kit.Badge>
}

/** 展开指示箭头（展开时旋转 90°） */
function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className={`h-3.5 w-3.5 shrink-0 text-faint transition-transform ${open ? 'rotate-90' : ''}`}
    >
      <path
        d="M9 6l6 6-6 6"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** chunk 关联实体：pill 列表（超 3 个折叠为 +N，type 作悬浮提示） */
function EntityPills({ entities }: { entities: ChunkEntityItem[] }) {
  if (entities.length === 0) {
    return <span className="text-sm text-faint">—</span>
  }
  const shown = entities.slice(0, 3)
  return (
    <span className="flex flex-wrap items-center gap-1">
      {shown.map((e) => (
        <span
          key={e.id}
          title={`${e.type} · ${e.name}`}
          className="max-w-[9rem] truncate rounded-sm bg-fg/[0.04] px-1.5 py-0.5 text-sm text-muted"
        >
          {e.name}
        </span>
      ))}
      {entities.length > shown.length && (
        <span
          title={entities
            .slice(3)
            .map((e) => e.name)
            .join('、')}
          className="text-sm text-faint"
        >
          +{entities.length - shown.length}
        </span>
      )}
    </span>
  )
}

/** chunk 关联关系：紧凑三元组列表（主 —谓→ 宾），超 2 条折叠 */
function RelationList({ relations }: { relations: ChunkRelationItem[] }) {
  if (relations.length === 0) {
    return <span className="text-sm text-faint">—</span>
  }
  const shown = relations.slice(0, 2)
  return (
    <span className="flex flex-col items-start gap-1">
      {shown.map((r) => (
        <span key={r.id} className="flex w-full min-w-0 items-baseline gap-1 text-sm leading-snug">
          <span className="min-w-0 truncate text-fg">{r.subject}</span>
          <span aria-hidden="true" className="shrink-0 text-faint">
            —
          </span>
          <span className="shrink-0 rounded-sm bg-fg/[0.04] px-1 text-muted">{r.predicate}</span>
          <span aria-hidden="true" className="shrink-0 text-faint">
            →
          </span>
          <span className="min-w-0 truncate text-fg">{r.object}</span>
        </span>
      ))}
      {relations.length > shown.length && (
        <span
          title={relations
            .slice(2)
            .map((r) => `${r.subject} —${r.predicate}→ ${r.object}`)
            .join('\n')}
          className="text-sm text-faint"
        >
          +{relations.length - shown.length} 条
        </span>
      )}
    </span>
  )
}

/**
 * 表头单元格：用 .ui-label 让「全大写 + 大字距」由主题排印层决定
 * （Endfield 会变成等宽全大写的仪器表头，flat / BA 保持常规小标签）。
 */
const TH = 'ui-label px-4 py-2.5 text-left'

/** chunk 子表：ID / 创建时间 / 状态 / 实体 / 关系 / 操作 */
function ChunkTable({
  chunks,
  loading,
  busyId,
  onInspect,
  onToggleStatus,
  onExtract,
  onDelete,
}: {
  chunks: ChunkItem[] | undefined
  loading: boolean
  busyId: number | null
  onInspect: (chunk: ChunkItem) => void
  onToggleStatus: (chunk: ChunkItem) => void
  onExtract: (chunk: ChunkItem) => void
  onDelete: (chunk: ChunkItem) => void
}) {
  if (loading && !chunks) {
    return <p className="py-6 text-center text-sm text-faint">加载 chunk …</p>
  }
  if (!chunks || chunks.length === 0) {
    return <p className="py-6 text-center text-sm text-faint">该文档下没有 chunk</p>
  }
  return (
    <div className="scrollbar-slim overflow-x-auto">
      <table className="w-full table-fixed text-left text-sm">
        <colgroup>
          <col className="w-14" />
          <col className="w-36" />
          <col className="w-20" />
          <col className="w-[24%]" />
          <col className="w-[28%]" />
          <col className="w-72" />
        </colgroup>
        <thead>
          <tr className="border-b border-line">
            <th className={TH}>ID</th>
            <th className={TH}>创建时间</th>
            <th className={TH}>状态</th>
            <th className={TH}>实体</th>
            <th className={TH}>关系</th>
            <th className={`${TH} text-right`}>操作</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {chunks.map((c) => (
            <tr
              key={c.id}
              className={`align-top transition hover:bg-accent-soft ${
                c.status === 'disabled' ? 'opacity-50' : ''
              }`}
            >
              <td className="mono py-2.5 pr-3 pl-4 text-faint" title={c.title ?? undefined}>
                {c.id}
              </td>
              <td className="mono py-2.5 px-3 text-muted">{fmtTime(c.createdAt)}</td>
              <td className="py-2.5 px-3">
                <ChunkStatusBadge status={c.status} />
              </td>
              <td className="py-2.5 px-3">
                <EntityPills entities={c.entities} />
              </td>
              <td className="py-2.5 px-3">
                <RelationList relations={c.relations} />
              </td>
              <td className="py-2.5 pr-4 text-right">
                <span className="inline-flex items-center justify-end gap-1.5">
                  <button
                    type="button"
                    onClick={() => onInspect(c)}
                    title="查看 chunk 原文"
                    className={miniBtn}
                  >
                    查看
                  </button>
                  <button
                    type="button"
                    onClick={() => onExtract(c)}
                    title="进入步骤 2：对该 chunk 做知识提取（已分析块可重新提取）"
                    className={miniBtn}
                  >
                    知识提取
                  </button>
                  <button
                    type="button"
                    onClick={() => onToggleStatus(c)}
                    disabled={busyId === c.id || c.status === 'unanalyzed'}
                    title={
                      c.status === 'unanalyzed'
                        ? '未分析块需先完成步骤 2 知识提取与步骤 3 增强'
                        : c.status === 'enabled'
                          ? '禁用该 chunk（检索时跳过）'
                          : '启用该 chunk'
                    }
                    className={miniBtn}
                  >
                    {busyId === c.id ? '…' : c.status === 'enabled' ? '禁用' : '启用'}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDelete(c)}
                    title="删除 chunk（连带其承载的关系与向量/实体链接）"
                    className={`${miniBtn} border-transparent text-hint hover:border-hint/40 hover:bg-hint/5 hover:text-hint`}
                  >
                    删除
                  </button>
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** chunk 原文 markdown 元素映射：字号改用语义刻度（不再写死 px），随主题字号谱系 */
const MD_COMPONENTS = {
  h1: (p: object) => <h1 className="ui-heading mt-4 mb-2 text-md first:mt-0" {...p} />,
  h2: (p: object) => <h2 className="ui-heading mt-4 mb-2 text-md first:mt-0" {...p} />,
  h3: (p: object) => <h3 className="ui-heading mt-4 mb-2 text-base first:mt-0" {...p} />,
  h4: (p: object) => <h4 className="ui-heading mt-3 mb-1.5 text-base first:mt-0" {...p} />,
  h5: (p: object) => <h5 className="ui-heading mt-3 mb-1.5 text-sm first:mt-0" {...p} />,
  h6: (p: object) => <h6 className="ui-heading mt-3 mb-1.5 text-sm first:mt-0" {...p} />,
  p: (p: object) => <p className="my-2 first:mt-0 last:mb-0" {...p} />,
  a: (p: object) => (
    <a
      className="text-accent underline decoration-accent/40 underline-offset-2 transition hover:decoration-accent"
      target="_blank"
      rel="noreferrer"
      {...p}
    />
  ),
  ul: (p: object) => <ul className="my-2 list-disc space-y-1 pl-5 first:mt-0 last:mb-0" {...p} />,
  ol: (p: object) => (
    <ol className="my-2 list-decimal space-y-1 pl-5 first:mt-0 last:mb-0" {...p} />
  ),
  blockquote: (p: object) => (
    <blockquote className="my-2 border-l border-line pl-3 text-muted first:mt-0 last:mb-0" {...p} />
  ),
  code: ({ className, children, ...rest }: { className?: string; children?: React.ReactNode }) =>
    className?.includes('language-') ? (
      <code className="font-mono text-sm leading-6" {...rest}>
        {children}
      </code>
    ) : (
      <code className="rounded-sm bg-fg/5 px-1 py-0.5 font-mono text-sm" {...rest}>
        {children}
      </code>
    ),
  pre: (p: object) => (
    <pre
      className="scrollbar-slim my-2 overflow-x-auto rounded-sm border border-line bg-fg/[0.03] p-3 font-mono text-sm leading-6 first:mt-0 last:mb-0"
      {...p}
    />
  ),
  table: (p: object) => (
    <table className="my-2 w-full border-collapse text-base first:mt-0 last:mb-0" {...p} />
  ),
  th: (p: object) => (
    <th className="border border-line bg-fg/5 px-2 py-1 text-left font-semibold" {...p} />
  ),
  td: (p: object) => <td className="border border-line px-2 py-1" {...p} />,
  hr: () => <hr className="my-4 border-line" />,
}

/** remark 插件列表（模块级常量，保持引用稳定） */
const REMARK_PLUGINS = [remarkGfm]

/** chunk 内容查看窗：可拖动悬浮窗，显示元信息 + 原文（markdown 渲染） */
function ChunkInspector({ chunk, onClose }: { chunk: ChunkItem; onClose: () => void }) {
  return (
    <FloatWindow title={`chunk #${chunk.id} · ${chunk.title ?? ''}`} onClose={onClose}>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-faint">
          <ChunkStatusBadge status={chunk.status} />
          <span className="mono">{fmtTime(chunk.createdAt)}</span>
          <span>父文档 #{chunk.docId}</span>
          <span>{chunk.content.length} 字</span>
        </div>
        <div className="text-base leading-relaxed break-words text-fg">
          <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={MD_COMPONENTS}>
            {chunk.content}
          </ReactMarkdown>
        </div>
      </div>
    </FloatWindow>
  )
}

/** 全文概述弹窗状态：doc=目标文档；loading=LLM 生成中（失败直接关窗并 message box 提示） */
type SummaryState = {
  doc: DocItem
  loading: boolean
  contextualizing: string | null
}

/** 全文概述查看窗：加载中显示 spinner，完成后展示 100~200 字概述（存 documents.contextualizing） */
function SummaryWindow({ state, onClose }: { state: SummaryState; onClose: () => void }) {
  const kit = usePageKit()
  return (
    <FloatWindow title={`全文概述 · ${state.doc.title}`} onClose={onClose}>
      {state.loading ? (
        <div className="flex flex-col items-center gap-3 px-2 py-10 text-faint">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
            className="h-6 w-6 animate-spin text-muted"
          >
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" opacity="0.2" />
            <path
              d="M21 12a9 9 0 0 0-9-9"
              stroke="currentColor"
              strokeWidth="3"
              strokeLinecap="round"
            />
          </svg>
          <p className="text-sm">正在通过大模型总结全文 …</p>
        </div>
      ) : (
        <kit.Note>{state.contextualizing}</kit.Note>
      )}
    </FloatWindow>
  )
}

export default function Analyze() {
  const kit = usePageKit()
  const [step, setStep] = useState<1 | 2 | 3>(1)
  /** 步骤 2 上下文（选中 chunk + 父文档标题/概述） */
  const [extractCtx, setExtractCtx] = useState<ExtractCtx | null>(null)
  /** 步骤 3 上下文（同步骤 2 结构：选中 chunk + 父文档标题/概述） */
  const [enhanceCtx, setEnhanceCtx] = useState<EnhanceCtx | null>(null)
  /** 全文概述生成中的文档 id（进入步骤 2/3 前的缺失概述补生成） */
  const [summaryPending, setSummaryPending] = useState<number | null>(null)

  // 步骤 1：文档与 chunk 数据
  const [docs, setDocs] = useState<DocItem[] | null>(null)
  /** 展开的文档行（折叠面板；同一时间仅一行展开） */
  const [openDocs, setOpenDocs] = useState<Record<number, boolean>>({})
  /** chunk 惰性加载缓存（docId → chunks；父子关系 doc→chunk 的前端镜像） */
  const [chunksCache, setChunksCache] = useState<Record<number, ChunkItem[]>>({})
  const [loadingDoc, setLoadingDoc] = useState<number | null>(null)
  /** 错误轻提示（message box，替代页内错误文案：列表/分块/概述/chunk 操作失败） */
  const [toast, setToast] = useState<string | null>(null)
  /** 状态切换中的 chunk id */
  const [busyChunk, setBusyChunk] = useState<number | null>(null)
  /** 查看中的 chunk（悬浮窗） */
  const [inspect, setInspect] = useState<ChunkItem | null>(null)
  /** 重新分块中的文档 id */
  const [rechunkBusy, setRechunkBusy] = useState<number | null>(null)
  /** 全文摘要弹窗（可拖动悬浮窗） */
  const [summaryWin, setSummaryWin] = useState<SummaryState | null>(null)

  const loadDocs = useCallback(async () => {
    try {
      setDocs(await listDocs())
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`文档列表加载失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }, [])

  useEffect(() => {
    void loadDocs()
  }, [loadDocs])

  /** 展开文档行时惰性拉取其 chunk（未缓存才请求） */
  const fetchChunks = (docId: number) => {
    if (loadingDoc === docId) return
    setLoadingDoc(docId)
    listDocChunks(docId)
      .then((cs) => setChunksCache((prev) => ({ ...prev, [docId]: cs })))
      .catch((e: unknown) => {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setToast(`chunk 加载失败：${e instanceof Error ? e.message : String(e)}`)
      })
      .finally(() => setLoadingDoc(null))
  }

  const toggleDoc = (doc: DocItem) => {
    const willOpen = !openDocs[doc.id]
    // 互斥展开：展开新行时收起其余行
    setOpenDocs(willOpen ? { [doc.id]: true } : {})
    if (willOpen && !chunksCache[doc.id]) {
      fetchChunks(doc.id)
    }
  }

  /** 启用 ⇄ 禁用（未分析块需先经步骤 2 知识提取） */
  const toggleChunkStatus = async (chunk: ChunkItem) => {
    if (busyChunk) return
    const next = chunk.status === 'enabled' ? 'disabled' : 'enabled'
    setBusyChunk(chunk.id)
    try {
      const updated = await setChunkStatus(chunk.id, next)
      setChunksCache((prev) => ({
        ...prev,
        [chunk.docId]: (prev[chunk.docId] ?? []).map((c) => (c.id === chunk.id ? updated : c)),
      }))
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`chunk 状态切换失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusyChunk(null)
    }
  }

  /** 删除 chunk（确认后）：从子表移除并刷新文档统计；若悬浮窗正展示它则一并关闭 */
  const removeChunk = async (chunk: ChunkItem) => {
    if (!window.confirm(`确定删除 chunk #${chunk.id}？其承载的关系与向量/实体链接将被一并清除。`)) {
      return
    }
    try {
      await deleteChunk(chunk.id)
      setInspect((cur) => (cur?.id === chunk.id ? null : cur))
      setChunksCache((prev) => ({
        ...prev,
        [chunk.docId]: (prev[chunk.docId] ?? []).filter((c) => c.id !== chunk.id),
      }))
      void loadDocs()
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`chunk 删除失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  /** 文档级「重新分块」：已启用旧块先禁用并从向量库移除，再清旧块及其承载的关系/向量/实体链接后重切 */
  const rechunkDocFlow = async (doc: DocItem) => {
    if (rechunkBusy) return
    const hint =
      doc.chunkCount > 0
        ? `「${doc.title}」已有 ${doc.chunkCount} 个 chunk，重新分块将先禁用已启用块并从向量库移除，再删除旧块及其关联的关系/向量/实体链接。继续？`
        : `为「${doc.title}」重新分块？`
    if (!window.confirm(hint)) {
      return
    }
    setRechunkBusy(doc.id)
    try {
      await rechunkDoc(doc.id)
      setChunksCache((prev) => {
        const next = { ...prev }
        delete next[doc.id]
        return next
      })
      // 面板正展开时立即拉取新块
      if (openDocs[doc.id]) {
        fetchChunks(doc.id)
      }
      void loadDocs()
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`重新分块失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setRechunkBusy(null)
    }
  }

  /** 全文概述：弹窗立即展示 loading（已有概述后端直返，几乎无感）；生成后展示并刷新文档列表（文档类型标签随之更新） */
  const openSummary = async (doc: DocItem) => {
    if (summaryWin) return
    setSummaryWin({ doc, loading: true, contextualizing: null })
    try {
      const r = await summarizeDoc(doc.id)
      setSummaryWin({ doc, loading: false, contextualizing: r.contextualizing })
      void loadDocs()
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      // 失败直接关窗（窗内无错误态可展示），错误经 message box 提示
      setSummaryWin(null)
      setToast(
        `全文概述生成失败：${e instanceof Error ? e.message : String(e)}（已有概述不会被覆盖）`,
      )
    }
  }

  /**
   * 概述就绪后进入目标步骤：同一文档的 chunk 共用同一全文概述——
   * 已有概述直接进入；缺失则弹窗确认 → 生成（loading）→ 完成后进入。
   */
  const enterWithSummary = async (
    doc: DocItem,
    enter: (contextualizing: string, docType: DocType | null) => void,
  ) => {
    if (doc.contextualizing) {
      enter(doc.contextualizing, doc.docType)
      return
    }
    const ok = window.confirm(
      `「${doc.title}」尚未生成全文概述（同一文档的 chunk 共用同一概述），是否现在生成？`,
    )
    if (!ok) return
    setSummaryPending(doc.id)
    try {
      const r = await summarizeDoc(doc.id)
      setSummaryPending(null)
      enter(r.contextualizing, r.docType)
      void loadDocs()
    } catch (e: unknown) {
      setSummaryPending(null)
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`概述生成失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  /** 进入步骤 2（知识提取） */
  const gotoExtract = (chunk: ChunkItem, doc: DocItem) =>
    void enterWithSummary(doc, (contextualizing, docType) => {
      setExtractCtx({ chunk, docTitle: doc.title, contextualizing, docType })
      setStep(2)
    })

  /** 提交成功进入下一阶段（步骤 3 增强）：携带同一 chunk，刷新步骤 1 的 chunk/文档统计 */
  const advanceAfterCommit = () => {
    if (extractCtx) {
      const docId = extractCtx.chunk.docId
      setChunksCache((prev) => {
        const next = { ...prev }
        delete next[docId]
        return next
      })
      if (openDocs[docId]) {
        fetchChunks(docId)
      }
      setEnhanceCtx(extractCtx)
    }
    setStep(3)
    void loadDocs()
  }

  /** 从步骤 2 返回选择：刷新该 chunk 所属文档的 chunk 缓存与统计（提取提交/增强可能已改变状态） */
  const backFromExtract = () => {
    const docId = extractCtx?.chunk.docId
    if (docId !== undefined) {
      setChunksCache((prev) => {
        const next = { ...prev }
        delete next[docId]
        return next
      })
      if (openDocs[docId]) {
        fetchChunks(docId)
      }
    }
    void loadDocs()
    setStep(1)
  }

  return (
    <kit.Page
      title="文档分析"
      en="ANALYZE"
      meta={<span className="text-sm text-muted">{docs?.length ?? 0} 篇文档</span>}
    >
      {/* 错误轻提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />

      {/* 步骤条：形态由 kit 决定（圆点连线 / 斜切编号牌 / 发光胶囊链） */}
      <kit.Steps
        items={STEP_LABELS.map((label, i) => ({
          label,
          state:
            i + 1 === step ? ('current' as const) : i + 1 < step ? ('done' as const) : ('todo' as const),
        }))}
      />

      {/* 步骤 1：选择 Chunk */}
      {step === 1 && (
        <div className="flex flex-col gap-4">
          <kit.Toolbar
            left="文档发布后按 markdown 标题与段落自动分块（约 500~800 字，最长 1200），超长段落由 AI 语义切分，chunk 初始状态为「未分析」。"
            right={
              <a href="#/import" className={btnPrimary('rounded-md')}>
                新建文档
              </a>
            }
          />

          {summaryPending !== null && (
            <p className="flex items-center gap-2 text-sm text-muted">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
                className="h-3.5 w-3.5 animate-spin"
              >
                <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3" opacity="0.2" />
                <path
                  d="M21 12a9 9 0 0 0-9-9"
                  stroke="currentColor"
                  strokeWidth="3"
                  strokeLinecap="round"
                />
              </svg>
              正在通过大模型生成全文概述，完成后进入下一步 …
            </p>
          )}

          {docs && docs.length === 0 && (
            <kit.Empty>
              还没有文档 —— 点击右上「新建文档」前往导入页发布，系统将自动分块并建立文档与 chunk
              的父子关系。
            </kit.Empty>
          )}

          {docs && docs.length > 0 && (
            <kit.Panel title="文档列表" meta={`${docs.length} 篇`}>
              <div className="scrollbar-slim -mx-3.5 overflow-x-auto">
                <table className="w-full text-left text-base">
                  <thead>
                    <tr className="border-b border-line">
                      <th className={`${TH} w-1/2`}>标题</th>
                      <th className={TH}>状态</th>
                      <th className={`${TH} text-right`}>Chunk 数</th>
                      <th className={`${TH} text-right whitespace-nowrap`}>操作</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {docs.map((doc) => {
                      const isOpen = !!openDocs[doc.id]
                      return (
                        <Fragment key={doc.id}>
                          <tr
                            onClick={() => toggleDoc(doc)}
                            aria-expanded={isOpen}
                            className="cursor-pointer transition hover:bg-accent-soft"
                          >
                            <td className="px-4 py-3">
                              <span className="flex min-w-0 items-center gap-2">
                                <Chevron open={isOpen} />
                                <span className="truncate font-semibold text-fg">{doc.title}</span>
                                <DocTypeBadge type={doc.docType} />
                              </span>
                            </td>
                            <td className="px-4 py-3">
                              <DocStatusBadge doc={doc} />
                            </td>
                            <td className="mono px-4 py-3 text-right text-sm text-muted">
                              {doc.chunkCount}
                            </td>
                            <td className="px-4 py-3 text-right">
                              <span className="inline-flex items-center justify-end gap-1.5">
                                <button
                                  type="button"
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    void rechunkDocFlow(doc)
                                  }}
                                  disabled={rechunkBusy === doc.id}
                                  title="重新分块：已启用旧块先禁用并从向量库移除，再按当前规则重切"
                                  className={miniBtn}
                                >
                                  {rechunkBusy === doc.id ? '…' : '重新分块'}
                                </button>
                                <button
                                  type="button"
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    void openSummary(doc)
                                  }}
                                  title="查看/生成全文摘要（100~200 字）与文档类型标签"
                                  className={miniBtn}
                                >
                                  摘要
                                </button>
                              </span>
                            </td>
                          </tr>
                          {isOpen && (
                            <tr className="bg-fg/[0.02]">
                              <td colSpan={4} className="p-0">
                                <ChunkTable
                                  chunks={chunksCache[doc.id]}
                                  loading={loadingDoc === doc.id}
                                  busyId={busyChunk}
                                  onInspect={setInspect}
                                  onToggleStatus={(c) => void toggleChunkStatus(c)}
                                  onExtract={(c) => gotoExtract(c, doc)}
                                  onDelete={(c) => void removeChunk(c)}
                                />
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </kit.Panel>
          )}
        </div>
      )}

      {/* 步骤 2：知识提取（上下文补完 → LLM 抽取 → 消歧评估 → 提交） */}
      {step === 2 && extractCtx && (
        <ExtractStep ctx={extractCtx} onAdvance={advanceAfterCommit} onBack={backFromExtract} />
      )}

      {/* 步骤 3：Chunk 增强（语义补全/摘要/假设性问题 + Graph-Chunk 混合向量；完成后启用 chunk） */}
      {step === 3 && enhanceCtx && <EnhanceStep ctx={enhanceCtx} onPrev={() => setStep(2)} />}

      {/* chunk 内容查看窗（可拖动） */}
      {inspect && <ChunkInspector chunk={inspect} onClose={() => setInspect(null)} />}

      {/* 全文摘要窗（可拖动） */}
      {summaryWin && <SummaryWindow state={summaryWin} onClose={() => setSummaryWin(null)} />}
    </kit.Page>
  )
}
