import type { ReactNode } from 'react'
import type { SearchHit } from '../api/client'
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

/** 单条搜索命中：文档行 / 图谱锚点实体行（Google 结果式：面包屑 + 链接标题 + 摘要）；
 *  外层容器交给主题化 Card（interactive：悬停描边加强 + 抬升），内部排版保留语义类 */
export default function ResultCard({ hit }: { hit: SearchHit }) {
  const kit = usePageKit()
  const isEntity = hit.kind === 'entity'
  const isChunk = hit.kind === 'chunk'
  const statusTone = hit.status ? STATUS_TONES[hit.status] : undefined
  /* 外链（http）/ 文档命中跳文章页 / 实体命中跳图谱页（该实体 1 跳展开） */
  const external = /^https?:\/\//i.test(hit.sourceRef)
  const snapshotHref = hit.kind === 'snapshot' && hit.sourceRef.startsWith('snapshot:')
    ? `#/snapshots/${hit.sourceRef.slice('snapshot:'.length)}` : null
  const docHref = hit.kind === 'doc' && hit.url ? `#/article/${hit.url}` : null
  const graphHref = isEntity && hit.entityId ? `#/graph?entity=${hit.entityId}` : null
  const href = snapshotHref ?? (external ? hit.sourceRef : (docHref ?? graphHref))
  return (
    <Card as="article" interactive>
      {/* 面包屑行：等宽元数据（类型牌 / 来源 / chunk 号 / 状态徽章 / 相似度读数） */}
      <div className="mono flex items-center gap-3 text-xs text-faint">
        <span
          className={
            isEntity
              ? 'font-bold uppercase tracking-[0.18em] text-fg'
              : 'font-bold uppercase tracking-[0.18em]'
          }
        >
          {isEntity ? 'Entity' : isChunk ? 'Chunk' : hit.kind === 'snapshot' ? '网页快照' : hit.kind === 'doc' ? 'Document' : hit.source || 'note'}
        </span>
        {hit.sourceRef && (
          <>
            <span>/</span>
            <span className="truncate">{fileName(hit.sourceRef)}</span>
          </>
        )}
        {isChunk && hit.chunkId != null && (
          <>
            <span>/</span>
            <span className="shrink-0">#{hit.chunkId}</span>
          </>
        )}
        {isChunk && statusTone && (
          <kit.Badge tone={statusTone.tone}>{statusTone.label}</kit.Badge>
        )}
        <span className="ml-auto shrink-0">{hit.score.toFixed(3)}</span>
      </div>
      <h3 className="mt-2.5 text-xl font-bold leading-snug tracking-tight">
        {href ? (
          <a
            href={href}
            target={external ? '_blank' : undefined}
            rel={external ? 'noreferrer' : undefined}
            className="text-fg transition hover:text-accent hover:underline"
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
        <p className="mt-2 max-w-2xl text-base leading-relaxed text-muted [overflow-wrap:anywhere]">
          <Mark text={hit.snippet} terms={hit.matched} />
        </p>
      )}
    </Card>
  )
}
