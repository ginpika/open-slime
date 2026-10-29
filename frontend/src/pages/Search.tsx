import { AppIcon } from '../components/AppIcon'
import { useEffect, useState, type FormEvent } from 'react'
import { loginUrlOf, search, type SearchResponse } from '../api/client'
import ResultCard from '../components/ResultCard'
import WebSearchResults from '../components/WebSearchResults'
import Toast from '../components/Toast'
import { Control, btnPrimary } from '../components/ui'
import { usePageKit } from '../kit'

/** 读取 hash 查询串参数（#/search?q=…&rag=1） */
function paramFromHash(name: string): string {
  const h = window.location.hash
  const qi = h.indexOf('?')
  return qi < 0 ? '' : (new URLSearchParams(h.slice(qi + 1)).get(name) ?? '')
}

function qFromHash(): string {
  return paramFromHash('q')
}

/** RAG 测试模式（暗门）：rag=1 时全量召回且只返回 chunk 命中 */
function ragFromHash(): boolean {
  return paramFromHash('rag') === '1'
}

function SearchIcon() {
  return (
    <AppIcon name="search" className="h-4 w-4" />
  )
}

/** 搜索结果页（#/search?q=…）：Google 式结果列表（文档与实体命中；实体可跳转图谱页）；
 *  rag=1 为 RAG 测试模式（首页滑块「搜索」按钮双击进入），只返回 chunk 命中。
 *  本页只持有检索时序与输入法防护；标题区 / 空态 / 表单外壳交给 PageKit 与主题原语，
 *  满幅骨架（顶栏固定 + 结果区独立滚动）保持不变 */
export default function Search() {
  const kit = usePageKit()
  const [q, setQ] = useState(qFromHash)
  /* 已提交的检索词：输入框只更新 q，不触发检索（输入法组合期间逐字符重搜会把输入法卡死） */
  const [query, setQuery] = useState(qFromHash)
  const [rag, setRag] = useState(ragFromHash)
  const [res, setRes] = useState<SearchResponse | null>(null)
  /** 检索失败轻提示（message box，替代页内错误文案） */
  const [toast, setToast] = useState<string | null>(null)
  /** RAG 测试模式进入提示（message box，替代原页顶横幅；自动/点击关闭） */
  const [ragToast, setRagToast] = useState(false)
  const [busy, setBusy] = useState(false)
  const [ms, setMs] = useState<number | null>(null)

  /* hash 变化（含本页改写 q、浏览器前进后退）时同步输入框、已提交检索词与模式并重新检索 */
  useEffect(() => {
    const onChange = () => {
      const h = qFromHash()
      setQ(h)
      setQuery(h)
      setRag(ragFromHash())
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])

  /* 经暗门（rag=1）进入 RAG 测试模式时弹出轻提示（替代原页顶常驻横幅；
     页内继续检索时 rag 不变化，不会重复弹出） */
  useEffect(() => {
    if (rag) setRagToast(true)
  }, [rag])

  /* 仅在提交的检索词变化时检索（点击搜索按钮 / 前进后退），输入过程不再触发 */
  useEffect(() => {
    const text = query.trim()
    if (!text) {
      setRes(null)
      setToast(null)
      return
    }
    let alive = true
    setBusy(true)
    setToast(null)
    const t0 = performance.now()
    search(text, rag)
      .then((r) => {
        if (!alive) return
        setRes(r)
        setMs(Math.round(performance.now() - t0))
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setRes(null)
        setToast(`检索失败：${e instanceof Error ? e.message : String(e)}`)
      })
      .finally(() => {
        if (alive) setBusy(false)
      })
    return () => {
      alive = false
    }
  }, [query, rag])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const text = q.trim()
    if (!text) return
    setQuery(text)
    window.location.assign(
      `#/search?q=${encodeURIComponent(text)}${rag ? '&rag=1' : ''}`,
    )
  }

  /* 未提交过检索词且非 RAG 模式：引导态（编辑中的输入不会切走当前结果视图，避免输入法被打断）；
     标题区交给 kit.Page，搜索框外壳交给 Control（描边环 / 玻璃 / 焦点色随主题）；
     RAG 模式直接进入贴顶搜索页布局 */
  if (!query.trim() && !rag) {
    return (
      <kit.Page title="检索" en="SEARCH">
        <p className="text-sm text-muted">输入关键词开始检索。</p>
        <form onSubmit={submit} className="w-full max-w-2xl">
          <Control className="w-full gap-2 rounded-full py-1.5 pl-5 pr-1.5">
            <span className="shrink-0 text-faint">
              <SearchIcon />
            </span>
            <input
              value={q}
              onChange={(ev) => setQ(ev.target.value)}
              placeholder="输入关键词或问题…"
              className="min-w-0 flex-1"
              autoFocus
            />
            <button type="submit" className={btnPrimary()}>
              搜索
            </button>
          </Control>
        </form>
      </kit.Page>
    )
  }

  return (
    /* 顶栏固定在滚动容器之外、结果区独立滚动：搜索栏不再随滚动抖动（满幅页骨架保留） */
    <section className="flex h-full min-h-0 flex-col">
      {/* 检索失败 / RAG 测试模式进入提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      <Toast
        message={ragToast ? '当前处于 RAG 测试模式 —— 全量召回，仅返回 chunk 命中' : null}
        onClose={() => setRagToast(false)}
      />
      {/* 顶栏：可编辑的搜索框（Google 结果页式样），固定不随内容滚动 */}
      <div className="-mx-6 shrink-0 border-b border-line bg-bg px-6 pb-3 pt-4">
        <form onSubmit={submit} className="flex w-full max-w-2xl items-center gap-2">
          <Control className="min-w-0 flex-1 gap-2 rounded-full py-1 pl-4 pr-1.5">
            <span className="shrink-0 text-faint">
              <SearchIcon />
            </span>
            <input
              value={q}
              onChange={(ev) => setQ(ev.target.value)}
              className="min-w-0 flex-1"
            />
          </Control>
          <button
            type="submit"
            disabled={busy}
            className={`${btnPrimary()} flex shrink-0 items-center gap-1.5 disabled:opacity-40`}
          >
            <SearchIcon />
            搜索
          </button>
        </form>
      </div>

      {/* 内容滚动区：TailwindCSS 风格细滚动条，顶栏不参与滚动 */}
      <div className="scrollbar-slim min-h-0 flex-1 overflow-y-auto">
        {/* 统计行：Google 式「约 N 条结果（用时 X 秒）」 */}
        <div className="w-full max-w-3xl pt-5">
          {busy ? (
            <p className="text-sm text-hint">正在检索 …</p>
          ) : res ? (
            <p className="text-sm text-faint">
              约 {res.hits.length + (res.web?.results.length ?? 0)} 条结果
              {ms !== null && (<>（用时 {(ms / 1000).toFixed(2)} 秒）</>)}
            </p>
          ) : null}
        </div>

        {/* 结果列表（重检索时保留旧结果，Google 式原地刷新） */}
        {res && (
          <div className="mt-2 w-full max-w-3xl pb-10">
            {res.hits.length === 0 && !res.web?.results.length ? (
              <div className="py-10">
                <kit.Empty>未找到与「{q.trim()}」相符的结果。</kit.Empty>
                <ul className="mt-3 list-disc space-y-1.5 pl-5 text-base leading-relaxed text-muted">
                  <li>请检查输入字词有无错误，或去掉多余的条件</li>
                  <li>尝试更简短、更笼统的关键词</li>
                  <li>换个说法，或试试同义词</li>
                </ul>
              </div>
            ) : res.hits.length > 0 ? (
              /* 每条命中自带主题化 interactive 卡片，容器只负责以间隙分组 */
              <section className="w-full space-y-3" aria-label="内部搜索结果">
                <h2 className="text-xl font-bold">内部结果 <span className="text-sm font-normal text-muted">知识库</span></h2>
                <div className="flex flex-col gap-3">
                {res.hits.map((hit, i) => (
                  <ResultCard
                    key={`${hit.kind}-${hit.chunkId ?? hit.entityId ?? i}`}
                    hit={hit}
                  />
                ))}
                </div>
              </section>
            ) : null}
            <WebSearchResults web={res.web} />
          </div>
        )}
      </div>
    </section>
  )
}
