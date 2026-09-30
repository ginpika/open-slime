import type { ReactNode } from 'react'
import type { SearchHit } from '../api/client'

/** 命中词高亮（mark 语义令牌：随主题的点缀底 + 前景字，无圆角、无彩） */
export function Mark({ text, terms }: { text: string; terms: string[] }) {
  const keys = [...new Set(terms)]
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
    .sort((a, b) => b.length - a.length)
  if (keys.length === 0) return text
  const re = new RegExp(`(${keys.map(escapeRe).join('|')})`, 'gi')
  const parts = text.split(re)
  const lowerKeys = keys.map((k) => k.toLowerCase())
  return (
    <>
      {parts.map((part, i): ReactNode =>
        lowerKeys.includes(part.toLowerCase()) ? (
          <mark key={i} className="bg-mark text-fg">
            {part}
          </mark>
        ) : (
          part
        ),
      )}
    </>
  )
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** chunk 状态标签（RAG 测试模式）：Tailwind 风格 pill（圆角 + 描边 + 淡底），复用分析页配色 */
const STATUS_TONES: Record<string, { cls: string; label: string }> = {
  enabled: { cls: 'border-secondary/40 bg-secondary/10 text-secondary', label: '启用' },
  disabled: { cls: 'border-line bg-fg/5 text-hint', label: '禁用' },
  unanalyzed: { cls: 'border-line bg-fg/5 text-faint', label: '未分析' },
}

/** 来源文件名：文章 slug / note 路径补全 .md 扩展名（外链与内部文档 ref 原样返回） */
function fileName(ref: string): string {
  if (!ref || /^https?:\/\//i.test(ref) || ref.startsWith('doc:')) return ref
  const base = ref.slice(ref.lastIndexOf('/') + 1)
  return base.includes('.') ? ref : `${ref}.md`
}

/** 单条搜索命中：文档行 / 图谱锚点实体行（Google 结果式：面包屑 + 链接标题 + 摘要） */
export default function ResultCard({ hit }: { hit: SearchHit }) {
  const isEntity = hit.kind === 'entity'
  const isChunk = hit.kind === 'chunk'
  const statusTone = hit.status ? STATUS_TONES[hit.status] : undefined
  /* 外链（http）/ 文档命中跳文章页 / 实体命中跳图谱页（该实体 1 跳展开） */
  const external = /^https?:\/\//i.test(hit.sourceRef)
  const docHref = hit.kind === 'doc' && hit.url ? `#/article/${hit.url}` : null
  const graphHref = isEntity && hit.entityId ? `#/graph?entity=${hit.entityId}` : null
  const href = external ? hit.sourceRef : (docHref ?? graphHref)
  return (
    <article className="py-6">
      <div className="flex items-center gap-3 text-[11px] text-faint">
        <span
          className={
            isEntity
              ? 'font-semibold uppercase tracking-[0.18em] text-fg'
              : 'font-medium uppercase tracking-[0.18em]'
          }
        >
          {isEntity ? 'Entity' : isChunk ? 'Chunk' : hit.kind === 'doc' ? 'Document' : hit.source || 'note'}
        </span>
        {hit.sourceRef && (
          <>
            <span className="text-faint">/</span>
            <span className="truncate">{fileName(hit.sourceRef)}</span>
          </>
        )}
        {isChunk && hit.chunkId != null && (
          <>
            <span className="text-faint">/</span>
            <span className="shrink-0 font-mono">#{hit.chunkId}</span>
          </>
        )}
        {isChunk && statusTone && (
          <span
            className={`inline-flex shrink-0 items-center rounded-full border px-2 py-0.5 text-[10px] font-medium leading-none ${statusTone.cls}`}
          >
            {statusTone.label}
          </span>
        )}
        <span className="ml-auto shrink-0 font-mono text-faint">
          {hit.score.toFixed(3)}
        </span>
      </div>
      <h3 className="mt-2.5 text-[21px] font-bold leading-snug tracking-tight">
        {href ? (
          <a
            href={href}
            target={external ? '_blank' : undefined}
            rel={external ? 'noreferrer' : undefined}
            className="text-accent transition hover:underline"
          >
            <Mark text={hit.title} terms={hit.matched} />
          </a>
        ) : (
          <span className="text-fg">
            <Mark text={hit.title} terms={hit.matched} />
          </span>
        )}
      </h3>
      {!isEntity && (
        <p className="mt-2 max-w-2xl text-[14px] leading-relaxed text-muted [overflow-wrap:anywhere]">
          <Mark text={hit.snippet} terms={hit.matched} />
        </p>
      )}
    </article>
  )
}
