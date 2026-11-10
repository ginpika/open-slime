import { useEffect, useMemo, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { articleDetail, loginUrlOf, type ArticleDetail } from '../api/client'
import Toast from '../components/Toast'
import TocRail, { type TocItem } from '../components/TocRail'
import './knowledge.css'

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

/**
 * react-markdown 会把自身的 AST node 一并传给自定义组件；整个展开到 DOM 会产生
 * node="[object Object]" 这类非法属性，这里统一剔除后再透传。
 */
function domProps(p: object): object {
  const { node: _node, ...rest } = p as { node?: unknown } & Record<string, unknown>
  return rest
}

/** markdown 元素 → 令牌样式（颜色随知识库表面）；标题挂 scroll-mt 供目录锚点定位留白 */
const MD = {
  h1: (p: object) => (
    <h2 className="mb-3 mt-8 scroll-mt-6 text-lg font-semibold leading-snug text-fg" {...domProps(p)} />
  ),
  h2: (p: object) => (
    <h2 className="mb-3 mt-8 scroll-mt-6 text-md font-semibold leading-snug text-fg" {...domProps(p)} />
  ),
  h3: (p: object) => (
    <h3 className="mb-2 mt-6 scroll-mt-6 text-base font-semibold leading-snug text-fg" {...domProps(p)} />
  ),
  p: (p: object) => <p className="my-4 text-md leading-7 text-fg" {...domProps(p)} />,
  a: (p: object) => (
    <a
      className="text-accent underline decoration-accent/40 underline-offset-2 transition hover:decoration-accent"
      target="_blank"
      rel="noreferrer"
      {...domProps(p)}
    />
  ),
  ul: (p: object) => <ul className="my-4 list-disc space-y-1 pl-5 text-md leading-7" {...domProps(p)} />,
  ol: (p: object) => (
    <ol className="my-4 list-decimal space-y-1 pl-5 text-md leading-7" {...domProps(p)} />
  ),
  blockquote: (p: object) => (
    <blockquote className="my-4 border-l-2 border-line pl-4 text-md leading-7 text-muted" {...domProps(p)} />
  ),
  code: ({ className, children }: { className?: string; children?: React.ReactNode }) =>
    className?.includes('language-') ? (
      <code className="font-mono text-sm leading-6">{children}</code>
    ) : (
      <code className="rounded-sm bg-fg/5 px-1 py-0.5 font-mono text-sm text-fg">{children}</code>
    ),
  pre: (p: object) => (
    <pre
      className="scrollbar-slim my-4 overflow-x-auto rounded-md border border-line bg-fg/[0.03] p-4 text-sm leading-6"
      {...domProps(p)}
    />
  ),
  table: (p: object) => <table className="my-4 w-full border-collapse text-base" {...domProps(p)} />,
  th: (p: object) => (
    <th className="border border-line bg-fg/5 px-3 py-1.5 text-left font-semibold" {...domProps(p)} />
  ),
  td: (p: object) => <td className="border border-line px-3 py-1.5" {...domProps(p)} />,
  hr: () => <hr className="my-8 border-line" />,
  img: (p: object) => <img className="max-w-full" {...domProps(p)} />,
}

/** 文档详情页：渲染 markdown 原文（data/article/{url}.md），左侧悬浮目录 */
export default function ArticleDetailPage({ url }: { url: string }) {
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
      <div className="kn-page kn-page--reading">
        <p className="kn-hint">加载中 …</p>
      </div>
    )
  }

  if (err || !doc) {
    return (
      <div className="kn-page kn-page--reading">
        {/* 加载失败/文章缺失：错误文案经 message box 提示，页内仅保留返回入口 */}
        <Toast
          kind="error"
          message={err ?? '文章不存在。'}
          onClose={() => setErr(null)}
        />
        <a href="#/articles" className="kn-article__back">← 返回文档列表</a>
      </div>
    )
  }

  return (
    /* 目录固定悬浮在正文左缘外侧留白区并垂直居中（超宽视口才显示），不挤占文档流空间 */
    <div className="kn-page kn-page--reading">
      {toc.length > 0 && (
        <aside className="kn-article__toc">
          <TocRail items={toc} />
        </aside>
      )}

      <header>
        <a href="#/articles" className="kn-article__back">← 返回文档列表</a>
        <h1 className="kn-article__title">{doc.title}</h1>
        <p className="kn-article__meta">{new Date(doc.createdAt).toLocaleDateString('zh-CN')}</p>
      </header>

      {doc.summary && <p className="kn-article__summary">{doc.summary}</p>}

      {/* Markdown 正文：内容优先，不套面板 */}
      <article ref={articleRef} className="kn-article__body">
        <ReactMarkdown remarkPlugins={[remarkGfm]} components={MD}>
          {body}
        </ReactMarkdown>
      </article>
    </div>
  )
}
