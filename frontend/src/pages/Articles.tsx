/**
 * 文档页：博客式文章分页列表（索引：标题|摘要|url），点击进入 markdown 详情。
 *
 * 呈现为知识库子页：SlimePage 极简页头下的居中列表 —— 与 /dashboard、/knowledge
 * 同一套表面语言（系统字体、细描边、小圆角、柔和灰底）。
 */

import { useEffect, useState } from 'react'
import { articles, loginUrlOf, type ArticlePage } from '../api/client'
import { AppIcon } from '../components/AppIcon'
import { LoadState } from '../components/LoadState'
import './knowledge.css'

const SIZE = 10

function fmtDate(ts: number): string {
  return new Date(ts).toLocaleDateString('zh-CN')
}

/** 页码序列：首末页常驻 + 当前页窗口，跨度大时以省略号折叠（如 1 … 4 5 6 … 20） */
function pageItems(current: number, total: number): (number | 'dots')[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const set = new Set<number>([1, total, current - 1, current, current + 1])
  if (current <= 4) [2, 3, 4].forEach((n) => set.add(n))
  if (current >= total - 3) [total - 3, total - 2, total - 1].forEach((n) => set.add(n))
  const pages = [...set].filter((n) => n >= 1 && n <= total).sort((a, b) => a - b)
  const out: (number | 'dots')[] = []
  let prev = 0
  for (const n of pages) {
    if (n - prev > 1) out.push('dots')
    out.push(n)
    prev = n
  }
  return out
}

export default function Articles() {
  const [page, setPage] = useState(1)
  const [data, setData] = useState<ArticlePage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [loadedPage, setLoadedPage] = useState<number | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    articles(page, SIZE)
      .then((d) => {
        if (alive) { setData(d); setLoadedPage(page) }
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setError(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [page, retry])

  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / SIZE))

  return (
    <div className="kn-page">
      <div className="kn-head">
        <p className="kn-head__desc">已保存的文档，点击回到原文继续阅读。</p>
        <div className="kn-head__actions">
          <span className="kn-head__meta">{data ? `共 ${total} 篇` : '目录尚未读取'}</span>
          <a className="kn-btn kn-btn--sm" href="#/import">导入文档</a>
        </div>
      </div>

      <LoadState loading={loading} error={error} hasData={!!data} label="文档目录" onRetry={() => setRetry(v => v + 1)} stale={`下方保留第 ${loadedPage} 页的上次读取结果，尚未更新。`} />

      {data && data.items.length === 0 && (
        <div className="kn-empty">
          <strong>还没有文档</strong>
          粘贴一份 Markdown 即可入库。
          <a href="#/import">导入第一篇文档 →</a>
        </div>
      )}

      {data && data.items.length > 0 && (
        <div className="kn-list">
          {data.items.map((a) => (
            <a className="kn-row" key={a.url} href={`#/article/${encodeURIComponent(a.url)}`}>
              <div className="kn-row__main">
                <p className="kn-row__title">{a.title}</p>
                {a.summary && <p className="kn-row__desc">{a.summary}</p>}
              </div>
              <span className="kn-row__meta">{fmtDate(a.createdAt)}</span>
            </a>
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <nav className="kn-pager" aria-label="分页">
          <button
            type="button"
            aria-label="上一页"
            disabled={page <= 1 || loading}
            onClick={() => setPage((p) => Math.max(1, p - 1))}
            className="kn-pager__btn"
          >
            <AppIcon name="chevron-left" size={14} />
          </button>
          {pageItems(page, totalPages).map((it, i) =>
            it === 'dots' ? (
              <span key={`dots-${i}`} className="kn-pager__dots">…</span>
            ) : (
              <button
                key={it}
                type="button"
                disabled={loading || it === page}
                aria-current={it === page ? 'page' : undefined}
                onClick={() => setPage(it)}
                className="kn-pager__btn"
              >
                {it}
              </button>
            ),
          )}
          <button
            type="button"
            aria-label="下一页"
            disabled={page >= totalPages || loading}
            onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
            className="kn-pager__btn"
          >
            <AppIcon name="chevron-right" size={14} />
          </button>
        </nav>
      )}
    </div>
  )
}
