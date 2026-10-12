import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { articleDetail, loginUrlOf, type ArticleDetail } from '../api/client'
import Toast from '../components/Toast'
import TocRail, { type TocItem } from '../components/TocRail'
import { usePageKit } from '../kit'
import { useThemeState } from '../theme/store'

/** 渲染前预处理：剥离 hexo/hugo front-matter 头部块与 <!--more--> 摘要分割标记（原文存储不动） */
function preprocess(markdown: string): string {
  let md = markdown
  const lines = md.split('\n')
  if (lines.length > 0) {
    const fence = lines[0].trim()
    if (fence === '---' || fence === '+++') {
      for (let i = 1; i < lines.length; i++) {
        if (lines[i].trim() === fence) {
          md = lines.slice(i + 1).join('\n')
          break
        }
      }
    }
  }
  return md.replace(/<!--\s*more\s*-->/gi, '').replace(/^\s+/, '')
}

/** markdown 元素 → 主题令牌样式（主题自动跟随）；标题挂 scroll-mt 供目录锚点定位留白 */
const MD = {
  h1: (p: object) => (
    <h2 className="mb-3 mt-8 scroll-mt-6 text-lg font-bold text-accent underline decoration-accent/40 decoration-1 underline-offset-[6px]" {...p} />
  ),
  h2: (p: object) => (
    <h2 className="mb-3 mt-8 scroll-mt-6 text-md font-bold text-accent underline decoration-accent/40 decoration-1 underline-offset-[6px]" {...p} />
  ),
  h3: (p: object) => (
    <h3 className="mb-2 mt-6 scroll-mt-6 text-base font-bold text-accent underline decoration-accent/40 decoration-1 underline-offset-4" {...p} />
  ),
  p: (p: object) => <p className="my-4 text-md leading-7 text-fg" {...p} />,
  a: (p: object) => (
    <a
      className="text-accent underline decoration-accent/40 underline-offset-2 transition hover:decoration-accent"
      target="_blank"
      rel="noreferrer"
      {...p}
    />
  ),
  ul: (p: object) => <ul className="my-4 list-disc space-y-1 pl-5 text-md leading-7" {...p} />,
  ol: (p: object) => (
    <ol className="my-4 list-decimal space-y-1 pl-5 text-md leading-7" {...p} />
  ),
  blockquote: (p: object) => (
    <blockquote className="my-4 border-l border-line pl-4 text-md leading-7 text-muted" {...p} />
  ),
  code: ({ className, children, ...rest }: { className?: string; children?: React.ReactNode }) =>
    className?.includes('language-') ? (
      <code className="font-mono text-sm leading-6" {...rest}>
        {children}
      </code>
    ) : (
      <code className="bg-fg/5 px-1 py-0.5 font-mono text-sm text-fg" {...rest}>
        {children}
      </code>
    ),
  pre: (p: object) => (
    <pre
      className="scrollbar-slim my-4 overflow-x-auto border border-line bg-fg/[0.03] p-4 text-sm leading-6"
      {...p}
    />
  ),
  table: (p: object) => <table className="my-4 w-full border-collapse text-base" {...p} />,
  th: (p: object) => (
    <th className="border border-line bg-fg/5 px-3 py-1.5 text-left font-semibold" {...p} />
  ),
  td: (p: object) => <td className="border border-line px-3 py-1.5" {...p} />,
  hr: () => <hr className="my-8 border-line" />,
  img: (p: object) => <img className="max-w-full" {...p} />,
}

/** 文档详情页：渲染 markdown 原文（data/article/{url}.md），左侧悬浮目录 */
export default function ArticleDetailPage({ url }: { url: string }) {
  const kit = usePageKit()
  /** 侧边栏档：目录改挂右侧（隐藏式圆点），避免与左侧栏重叠 */
  const sidebar = useThemeState().mono.sidebar
  const [doc, setDoc] = useState<ArticleDetail | null>(null)
  const [loading, setLoading] = useState(true)
  /** 加载失败/文章缺失（err 有值时以错误 message box 提示，页内仅保留返回入口） */
  const [err, setErr] = useState<string | null>(null)
  const [toc, setToc] = useState<TocItem[]>([])
  const articleRef = useRef<HTMLElement>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setErr(null)
    setDoc(null)
    articleDetail(url)
      .then((d) => {
        if (alive) setDoc(d)
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setErr(e instanceof Error ? e.message : String(e))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [url])

  /* 渲染完成后从正文 DOM 提取标题并分配锚点（不解析 markdown 源码，
     天然避开代码块里的 # 行与行内语法；层级取自实际 h2/h3 元素） */
  useEffect(() => {
    const root = articleRef.current
    if (!doc || !root) {
      setToc([])
      return
    }
    const els = Array.from(root.querySelectorAll('h2, h3')) as HTMLHeadingElement[]
    els.forEach((el, i) => {
      el.id = `h-${i}`
    })
    setToc(
      els.map((el, i) => ({
        id: `h-${i}`,
        level: el.tagName === 'H3' ? (3 as const) : (2 as const),
        text: (el.textContent ?? '').trim(),
      })),
    )
  }, [doc])

  /* 渲染用正文：剥离 front-matter / <!--more--> 后的 markdown */
  const body = useMemo(() => (doc ? preprocess(doc.markdown) : ''), [doc])

  if (loading) {
    return (
      <section className="pt-24 text-center text-base text-hint">加载中 …</section>
    )
  }

  if (err || !doc) {
    return (
      <section className="pt-24 text-center">
        {/* 加载失败/文章缺失：错误文案经 message box 提示，页内仅保留返回入口 */}
        <Toast
          kind="error"
          message={err ?? '文章不存在。'}
          onClose={() => setErr(null)}
        />
        <a href="#/articles" className="mt-3 inline-block text-sm text-faint transition hover:text-fg">
          ← 返回文档列表
        </a>
      </section>
    )
  }

  return (
    /* 标题区交给 kit.Page（扇区头 + 工程虚线随主题）；目录 fixed 悬浮在正文左缘外侧
       留白区并垂直居中（2xl 视口以上左侧留白才足够容纳），不挤占文档流空间，也不受 main 裁剪。
       right = 50% + max-w-6xl/2 − main 内边距 + 间距 = 50% + 35.5rem */
    <kit.Page
      title={doc.title}
      en="ARTICLE"
      meta={
        <>
          <a href="#/articles" className="text-sm text-faint transition hover:text-fg">
            ← 返回文档列表
          </a>
          <span className="mono text-2xs text-faint">
            {new Date(doc.createdAt).toLocaleDateString('zh-CN')}
          </span>
        </>
      }
    >
      {toc.length > 0 && (
        <aside
          className={
            sidebar
              /* 侧边栏档：贴在左侧栏右边一点点（圆点轨很窄，贴着即可，不与侧栏重叠） */
              ? 'fixed left-[15.75rem] top-1/2 z-20 -translate-y-1/2'
              /* 常规：正文左缘外侧的留白区（2xl 以上才够宽） */
              : 'fixed left-[calc(50%_-_37rem)] top-1/2 z-10 hidden -translate-y-1/2 2xl:block'
          }
        >
          <TocRail items={toc} />
        </aside>
      )}
      {doc.summary && (
        <p className="border-l border-line pl-3 text-sm leading-relaxed text-muted">
          {doc.summary}
        </p>
      )}
      {/* Markdown 正文：内容优先，不套面板 */}
      <article ref={articleRef}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={MD}>
          {body}
        </ReactMarkdown>
      </article>
    </kit.Page>
  )
}
