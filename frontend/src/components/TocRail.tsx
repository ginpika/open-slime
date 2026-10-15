import { useEffect, useRef, useState, type MouseEvent } from 'react'

/** 目录项：文档渲染页由正文标题生成；首页会话页由每一次用户提问生成 */
export type TocItem = { id: string; level?: 2 | 3; text: string }

/** 向上找最近的滚动祖先（如文档页的 main）；找不到再退回最近的 main */
function findScroller(from: HTMLElement | null): HTMLElement | null {
  for (let n = from?.parentElement ?? null; n; n = n.parentElement) {
    const oy = getComputedStyle(n).overflowY
    if (oy === 'auto' || oy === 'scroll') return n
  }
  return from?.closest('main') ?? null
}

/** 紧凑档（首页会话页）圆点轨最多显示的点数：长会话下不让轨道无限拉长 */
const RAIL_MAX = 32

/**
 * 隐藏式目录（文档渲染页与首页会话页共用）：一列圆点——每项一个点、当前项加长高亮；
 * 鼠标移到圆点附近才浮出卡片目录。圆点本身不占正文宽度，落在哪一侧、跟着谁滚动
 * 都由调用处给定（见 {@code ArticleDetailPage} / {@code Home}）。
 *
 * @param side     贴内容哪一侧：left=圆点在左、卡片向右展开；right=圆点在右、卡片向左展开
 * @param scroller 滚动容器（缺省自动向上找最近的滚动祖先）；首页会话页的滚动在内层 div，需显式传入
 * @param label    浮出卡片上的小标题（{@code compact} 档不渲染）
 * @param compact  紧凑档（首页会话目录用）：去掉小标题与列表竖线、圆点轨至多 {@link RAIL_MAX} 个点、
 *                 卡片限高并改为内部滚动（细滚动条）、整体再右移 8px
 */
export default function TocRail({
  items,
  side = 'left',
  scroller,
  label = 'Contents',
  compact = false,
  expanded = false,
  plain = false,
}: {
  items: TocItem[]
  side?: 'left' | 'right'
  scroller?: () => HTMLElement | null
  label?: string
  compact?: boolean
  /** Always-visible directory for settings and other pages with room beside the content. */
  expanded?: boolean
  /** Render the expanded directory as text without a card or decorative rail. */
  plain?: boolean
}) {
  const [activeId, setActiveId] = useState<string | null>(null)
  const navRef = useRef<HTMLElement>(null)

  /* 侦测只靠 id 序列：会话页流式逐 token 时 items 会换新数组，但用户轮次未变——
     idsKey 不变就不重挂滚动侦听（文案变化不影响锚点定位） */
  const idsKey = items.map((it) => it.id).join('|')

  /* 滚动侦测：最后一个越过容器顶部附近（96px）的项为当前项；贴底时选中末项 */
  useEffect(() => {
    const ids = idsKey ? idsKey.split('|') : []
    const sc = scroller ? scroller() : findScroller(navRef.current)
    if (!sc) return
    const onScroll = () => {
      if (sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 4) {
        setActiveId(ids[ids.length - 1] ?? null)
        return
      }
      const top = sc.getBoundingClientRect().top
      let current: string | null = expanded ? ids[0] ?? null : null
      for (const id of ids) {
        const el = document.getElementById(id)
        if (el && el.getBoundingClientRect().top - top <= 96) current = id
      }
      setActiveId(current)
    }
    onScroll()
    sc.addEventListener('scroll', onScroll, { passive: true })
    return () => sc.removeEventListener('scroll', onScroll)
  }, [idsKey, scroller, expanded])

  /* 点击用 scrollIntoView：直接改 hash 会与 hash 路由冲突 */
  const jump = (e: MouseEvent<HTMLAnchorElement>, id: string) => {
    e.preventDefault()
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  /* 卡片朝正文侧展开：左侧轨道向右，右侧轨道向左 */
  const popoutSide = side === 'right' ? 'right-full' : 'left-full'

  /* 圆点轨要渲染的项：紧凑档只保留最近 RAIL_MAX 个点（卡片列表仍是全量，靠滚动看全） */
  const railItems = compact ? items.slice(-RAIL_MAX) : items

  if (expanded) return (
    <nav ref={navRef} aria-label={label} className={plain ? 'bg-transparent p-4' : 'ui-card ui-surface p-4'}>
      <p className="ui-label mb-3">{label}</p>
      <TocList items={items} activeId={activeId} jump={jump} rail={!plain} />
    </nav>
  )

  return (
    <nav
      ref={navRef}
      aria-label="目录"
      className={`group relative py-2 ${side === 'right' ? 'pl-3 pr-2' : 'pl-2 pr-3'}`}
    >
      {/* 圆点轨：一项一个点（三级标题的点小一号），当前项加长高亮。
          项多时（长会话）限高滚动，避免圆点轨超出视口 */}
      <ul className="scrollbar-hide flex max-h-[72vh] flex-col items-center gap-2 overflow-y-auto">
        {railItems.map((it) => {
          const active = it.id === activeId
          return (
            <li key={it.id}>
              <a
                href={`#${it.id}`}
                onClick={(e) => jump(e, it.id)}
                title={it.text}
                aria-label={it.text}
                className={`block rounded-full transition-all ${
                  it.level === 3 ? 'h-1 w-1' : 'h-1.5 w-1.5'
                } ${active ? 'h-3 w-1.5 bg-accent' : 'bg-fg/25 group-hover:bg-fg/45'}`}
              />
            </li>
          )
        })}
      </ul>
      {/* hover 才浮出的卡片目录：朝正文侧展开，默认隐身且不接收指针。
          **紧贴圆点列、不留缝隙**——中间只要有一段鼠标到不了的空当，指针穿过去时 group 就失焦、
          卡片当场消失，于是"看得到却点不着"（详见 nav 的 padding：间距都归它）。
          紧凑档：去掉小标题与列表竖线、整体再右移 8px，并限高改为卡片内部滚动（细滚动条） */}
      <div
        className={`pointer-events-none absolute top-1/2 w-64 -translate-y-1/2 opacity-0 transition duration-150 group-hover:pointer-events-auto group-hover:opacity-100 ${popoutSide}${
          compact ? ' translate-x-2' : ''
        }`}
      >
        <div
          className={`ui-card ui-surface overflow-y-auto p-3 ${
            compact ? 'scrollbar-slim max-h-[360px]' : 'max-h-[70vh]'
          }`}
        >
          {!compact && <p className="ui-label mb-2">{label}</p>}
          <TocList items={items} activeId={activeId} jump={jump} rail={!compact} />
        </div>
      </div>
    </nav>
  )
}

/** 目录列表（圆点轨旁浮出的卡片内使用）。
 *  rail=true 时画一条左侧竖线并用其高亮当前项；紧凑档去掉竖线，只用字色/字重标当前项 */
function TocList({
  items,
  activeId,
  jump,
  rail = true,
}: {
  items: TocItem[]
  activeId: string | null
  jump: (e: MouseEvent<HTMLAnchorElement>, id: string) => void
  rail?: boolean
}) {
  return (
    <ul className={rail ? 'border-l border-line' : ''}>
      {items.map((it) => {
        const active = it.id === activeId
        const cls = rail
          ? `-ml-px block truncate border-l py-1 pr-2 text-sm leading-5 transition ${
              it.level === 3 ? 'pl-7' : 'pl-3.5'
            } ${
              active
                ? 'border-accent font-medium text-accent'
                : 'border-transparent text-muted hover:text-fg'
            }`
          : `block truncate py-1.5 pr-2 text-sm leading-5 transition ${
              active ? 'font-medium text-accent' : 'text-muted hover:text-fg'
            }`
        return (
          <li key={it.id}>
            <a href={`#${it.id}`} onClick={(e) => jump(e, it.id)} title={it.text} aria-current={active ? 'location' : undefined} className={cls}>
              {it.text}
            </a>
          </li>
        )
      })}
    </ul>
  )
}
