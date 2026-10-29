import { useState, type ReactNode } from 'react'
import type { SearchHit, SearchResponse } from '../api/client'
import { Card } from './Card'
import { usePageKit } from '../kit'

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

/** chunk 状态标签（RAG 测试模式）：kit.Badge 印章式徽章，语义与 Analyze 页 ChunkStatusBadge 一致 */
const STATUS_TONES: Record<string, { tone: 'ok' | 'default'; label: string }> = {
  enabled: { tone: 'ok', label: '启用' },
  disabled: { tone: 'default', label: '禁用' },
  unanalyzed: { tone: 'default', label: '未分析' },
}

/** 来源文件名：文章 slug / note 路径补全 .md 扩展名（外链与内部文档 ref 原样返回） */
function fileName(ref: string): string {
  if (ref.startsWith('snapshot:')) return '本地快照'
  if (!ref || /^https?:\/\//i.test(ref) || ref.startsWith('doc:')) return ref
  const base = ref.slice(ref.lastIndexOf('/') + 1)
  return base.includes('.') ? ref : `${ref}.md`
}

type Props = { hit: SearchHit; web?: never } | { hit?: never; web: SearchResponse['web']['results'][number] }

/** Internal and DeepSeek hits share the same presentation; their links and source metadata stay distinct. */
export default function ResultCard({ hit, web }: Props) {
  const kit = usePageKit()
  const [expandedText, setExpandedText] = useState<string | null>(null)
  const isEntity = hit?.kind === 'entity'
  const isChunk = hit?.kind === 'chunk'
  const statusTone = hit?.status ? STATUS_TONES[hit.status] : undefined
  /* 外链（http）/ 文档命中跳文章页 / 实体命中跳图谱页（该实体 1 跳展开） */
  const external = /^https?:\/\//i.test(web?.url ?? hit?.sourceRef ?? '')
  const snapshotHref = hit?.kind === 'snapshot' && hit.sourceRef.startsWith('snapshot:')
    ? `#/snapshots/${hit.sourceRef.slice('snapshot:'.length)}` : null
  const docHref = hit?.kind === 'doc' && hit.url ? `#/article/${hit.url}` : null
  const graphHref = isEntity && hit?.entityId ? `#/graph?entity=${hit.entityId}` : null
  const href = web ? (external ? web.url : null) : snapshotHref ?? (external ? hit?.sourceRef : (docHref ?? graphHref))
  const title = web ? web.title || web.url : hit?.title ?? ''
  const source = web ? web.siteName || '网页' : isEntity ? '图谱实体' : isChunk ? '知识片段'
    : hit?.kind === 'snapshot' ? '网页快照' : hit?.kind === 'doc' ? '内部文档' : '本地知识库'
  const location = web ? web.url : isEntity ? `知识图谱 / ${title}` : fileName(hit?.sourceRef ?? '') || '本地知识库'
  const description = (web ? web.snippet || web.summary : hit?.snippet ?? '').replace(/\s+/g, ' ').trim()
  const chars = Array.from(description)
  const expanded = expandedText === description
  const excerpt = chars.length > 200 && !expanded ? chars.slice(0, 200).join('') + '…' : description
  const matched = hit?.matched ?? []
  return (
    <Card as="article" interactive className="search-result w-full min-w-0">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
        <span>{source}</span>
        {web?.date && <time>{web.date}</time>}
        {isChunk && hit?.chunkId != null && <span>#{hit.chunkId}</span>}
        {isChunk && statusTone && (
          <kit.Badge tone={statusTone.tone}>{statusTone.label}</kit.Badge>
        )}
      </div>
      <h3 className="mt-2 break-words text-xl font-bold leading-snug">
        {href ? (
          <a
            href={href}
            target={external ? '_blank' : undefined}
            rel={external ? 'noreferrer' : undefined}
            className="text-fg hover:text-accent hover:underline"
          >
            <Mark text={title} terms={matched} />
          </a>
        ) : (
          <span className="text-fg">
            <Mark text={title} terms={matched} />
          </span>
        )}
      </h3>
      <p className="mt-1 break-all text-xs text-muted">{location}</p>
      {description && <p className="mt-2 text-base leading-relaxed text-muted [overflow-wrap:anywhere]">
        <Mark text={excerpt} terms={matched} />
      </p>}
      {chars.length > 200 && <button type="button" aria-expanded={expanded}
        className="mt-1 text-base text-muted underline hover:text-accent sm:text-sm"
        onClick={() => setExpandedText(expanded ? null : description)}>{expanded ? '收起摘要' : '展开摘要'}</button>}
    </Card>
  )
}
