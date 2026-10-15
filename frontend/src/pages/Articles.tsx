/**
 * 文档页：博客式文章分页列表（索引：标题|摘要|url），点击进入 markdown 详情。
 *
 * 本页只持有数据与分页时序；列表行的形态、空态、分页按钮的样式全部来自 PageKit ——
 * 于是同一页在 flat / Endfield / BlueArchive 下分别是「表格感 / 仪表感 / 卡片流」。
 */

import { AppIcon } from '../components/AppIcon'
import { useEffect, useState } from 'react'
import { articles, loginUrlOf, type ArticlePage } from '../api/client'
import { LoadState } from '../components/LoadState'
import { usePageKit } from '../kit'

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

function Chevron({ dir }: { dir: 'left' | 'right' }) {
  return (
    <AppIcon name={dir === 'left' ? 'chevron-left' : 'chevron-right'} className="h-3.5 w-3.5" />
  )
}

/** 分页按钮：走 .ui-btn，于是形状（胶囊 / 切角）随主题 */
const PAGER = 'ui-btn ui-btn--quiet h-[30px] w-[30px] shrink-0 rounded-none [--ui-btn-px:0px] [--ui-btn-py:0px]'
const PAGER_ON = 'ui-btn ui-btn--primary h-[30px] w-[30px] shrink-0 rounded-none [--ui-btn-px:0px] [--ui-btn-py:0px]'

export default function Articles() {
  const kit = usePageKit()
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
    <kit.Page
      title="文档"
      en="ARTICLES"
      meta={<span className="text-sm text-muted">{data ? `共 ${total} 篇` : '目录尚未读取'}</span>}
    >
      <LoadState loading={loading} error={error} hasData={!!data} label="文档目录" onRetry={() => setRetry(v => v + 1)} stale={`下方保留第 ${loadedPage} 页的上次读取结果，尚未更新。`} />

      {data && data.items.length === 0 && (
        <kit.Empty>
          还没有文档。<a href="#/import" className="text-accent underline underline-offset-4">导入第一篇文档 →</a>
        </kit.Empty>
      )}

      {data && data.items.length > 0 && (
        <div className="flex flex-col gap-2">
          {data.items.map((a) => (
            <kit.Row
              key={a.url}
              href={`#/article/${encodeURIComponent(a.url)}`}
              className="articles-row group items-start gap-4"
            >
              <div className="min-w-0 flex-1">
                <p className="truncate text-md font-semibold text-fg transition group-hover:text-accent group-hover:underline group-hover:underline-offset-4">
                  {a.title}
                </p>
                {a.summary && (
                  <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-muted">{a.summary}</p>
                )}
              </div>
              <span className="mono shrink-0 pt-0.5 text-2xs text-faint">
                {fmtDate(a.createdAt)}
              </span>
            </kit.Row>
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex justify-center pt-6">
          <nav aria-label="分页" className="flex items-center gap-1">
            <button
              type="button"
              aria-label="上一页"
              disabled={page <= 1 || loading}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className={`${PAGER} disabled:pointer-events-none disabled:opacity-40`}
            >
              <Chevron dir="left" />
            </button>
            {pageItems(page, totalPages).map((it, i) =>
              it === 'dots' ? (
                <span
                  key={`dots-${i}`}
                  className="mono flex h-[30px] items-center px-1.5 text-sm text-faint"
                >
                  …
                </span>
              ) : (
                <button
                  key={it}
                  type="button"
                  disabled={loading || it === page}
                  aria-current={it === page ? 'page' : undefined}
                  onClick={() => setPage(it)}
                  className={`${it === page ? PAGER_ON : PAGER} mono disabled:pointer-events-none`}
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
              className={`${PAGER} disabled:pointer-events-none disabled:opacity-40`}
            >
              <Chevron dir="right" />
            </button>
          </nav>
        </div>
      )}
    </kit.Page>
  )
}
