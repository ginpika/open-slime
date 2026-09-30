import { useEffect, useState } from 'react'
import { articles, loginUrlOf, type ArticlePage } from '../api/client'
import Toast from '../components/Toast'

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

/** 分页按钮：30px 正圆 ghost 风格（Tailwind 圆形分页），当前页点缀色实底。
 *  注意：text-muted 只挂在 idle 态——若挂基类会与激活态 text-accent-fg 同层竞争，
 *  且 .text-muted 在样式表中后置，会把激活页码文字覆盖成灰色。 */
const pagerBtn =
  'inline-flex h-[30px] w-[30px] items-center justify-center rounded-full text-[12px] font-semibold transition'
const pagerIdle = `${pagerBtn} text-muted hover:bg-fg/5 hover:text-fg`
const pagerActive = `${pagerBtn} bg-accent text-accent-fg`

function Chevron({ dir }: { dir: 'left' | 'right' }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="h-3.5 w-3.5" aria-hidden="true">
      <path
        d={dir === 'left' ? 'M15 18l-6-6 6-6' : 'M9 6l6 6-6 6'}
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** 文档页：博客式文章分页列表（索引：标题|摘要|url），点击进入 markdown 详情 */
export default function Articles() {
  const [page, setPage] = useState(1)
  const [data, setData] = useState<ArticlePage | null>(null)
  const [loading, setLoading] = useState(true)
  /** 列表加载失败轻提示（message box，替代页内错误文案） */
  const [toast, setToast] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setToast(null)
    articles(page, SIZE)
      .then((d) => {
        if (alive) setData(d)
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setToast(`加载失败：${e instanceof Error ? e.message : String(e)}`)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [page])

  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / SIZE))

  return (
    <section className="flex min-h-[calc(100dvh-8.5rem)] flex-col pt-6">
      {/* 列表加载失败轻提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] text-faint">共 {total} 篇</span>
      </div>

      <div className="mt-3 min-h-0 flex-1">
        {data && data.items.length === 0 && (
          <p className="border border-dashed border-line p-6 text-[13px] leading-relaxed text-faint">
            还没有文档 —— 通过 POST /api/admin/articles 发布（markdown 原文存 data/article，索引进
            documents 表）。
          </p>
        )}
        {data && data.items.length > 0 && (
          <ul className="divide-y divide-line">
            {data.items.map((a) => (
              <li key={a.url}>
                <a href={`#/article/${encodeURIComponent(a.url)}`} className="group block py-4">
                  <div className="flex items-baseline justify-between gap-4">
                    <h2 className="text-[13.5px] font-medium text-accent underline-offset-4 transition group-hover:underline">
                      {a.title}
                    </h2>
                    <span className="shrink-0 text-[11px] text-faint">{fmtDate(a.createdAt)}</span>
                  </div>
                  {a.summary && (
                    <p className="mt-1.5 text-[12px] leading-relaxed text-muted">{a.summary}</p>
                  )}
                </a>
              </li>
            ))}
          </ul>
        )}
        {/* 分页：在分割线以下的剩余空间内垂直居中（与 footer 等距） */}
        <div className="flex min-h-0 flex-1 items-center justify-center">
          <nav aria-label="分页" className="flex items-center gap-1">
            <button
              type="button"
              aria-label="上一页"
              disabled={page <= 1 || loading}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className={`${pagerIdle} disabled:pointer-events-none disabled:opacity-40`}
            >
              <Chevron dir="left" />
            </button>
            {pageItems(page, totalPages).map((it, i) =>
              it === 'dots' ? (
                <span key={`dots-${i}`} className="flex h-[30px] items-center px-1.5 text-[12px] text-faint">
                  …
                </span>
              ) : (
                <button
                  key={it}
                  type="button"
                  disabled={loading || it === page}
                  aria-current={it === page ? 'page' : undefined}
                  onClick={() => setPage(it)}
                  className={`${it === page ? pagerActive : pagerIdle} disabled:pointer-events-none`}
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
              className={`${pagerIdle} disabled:pointer-events-none disabled:opacity-40`}
            >
              <Chevron dir="right" />
            </button>
          </nav>
        </div>
      </div>
    </section>
  )
}
