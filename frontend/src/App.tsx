import { useEffect, useRef, useState } from 'react'
import { loginUrlOf, logout, me, type SsoUser } from './api/client'
import Home from './pages/Home'
import Search from './pages/Search'
import Graph from './pages/Graph'
import Articles from './pages/Articles'
import ArticleDetailPage from './pages/ArticleDetail'
import Import from './pages/Import'
import Analyze from './pages/Analyze'
import Subconscious from './pages/Subconscious'
import MultiAgent from './pages/MultiAgent'
import AgentDetail from './pages/AgentDetail'
import Roundtable from './pages/Roundtable'
import Workspace from './pages/Workspace'
import Settings from './pages/Settings'
import Stats from './pages/Stats'
import About from './pages/About'
import { ThemePanel } from './theme/ThemePanel'
import { PageSlot } from './theme/overrides'
import { btnOutline } from './components/ui'
import ChatNotifier from './components/ChatNotifier'
import { BRAND } from './branding'

const NAV = [
  { path: '/', label: '首页' },
  { path: '/articles', label: '文档' },
  { path: '/graph', label: '知识图谱' },
  { path: '/import', label: '文档导入' },
  { path: '/analyze', label: '文档分析' },
  { path: '/multi-agent', label: '智能体' },
  { path: '/subconscious', label: '潜意识' },
  { path: '/workspace', label: '工作空间' },
  { path: '/settings', label: '设置' },
  { path: '/stats', label: '统计' },
  { path: '/about', label: '关于' },
]

function routeFromHash(): string {
  const h = window.location.hash.replace(/^#/, '')
  if (!h) return '/'
  const p = h.startsWith('/') ? h : `/${h}`
  return p.split('?')[0]
}

function useHashRoute(): string {
  const [route, setRoute] = useState(routeFromHash)
  useEffect(() => {
    const onChange = () => {
      setRoute(routeFromHash())
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

/** header 最右侧的圆形头像按钮：点击展开简单用户菜单（信息 + 登出） */
function UserMenu({ user, onLogout }: { user: SsoUser; onLogout: () => void }) {
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const name = user.nickname || user.username
  const initial = name ? name[0].toUpperCase() : '?'
  const Avatar = ({ size }: { size: string }) =>
    user.avatarUrl ? (
      <img src={user.avatarUrl} alt={name} className={`${size} shrink-0 rounded-full object-cover`} />
    ) : (
      <span
        className={`${size} flex shrink-0 items-center justify-center rounded-full bg-fg/10 font-semibold text-fg`}
      >
        {initial}
      </span>
    )

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        aria-label="用户"
        aria-expanded={open}
        title={name}
        onClick={() => setOpen((v) => !v)}
        className={`flex h-[30px] w-[30px] items-center justify-center overflow-hidden rounded-full border border-line transition hover:border-line-strong ${
          open ? 'border-line-strong' : ''
        }`}
      >
        <Avatar size="h-full w-full" />
      </button>
      {open && (
        <div className="absolute right-0 top-[calc(100%+10px)] z-50 w-56 border border-line bg-bg p-4 text-left">
          <div className="flex items-center gap-3">
            <Avatar size="h-10 w-10" />
            <div className="min-w-0">
              <p className="truncate text-[13px] font-semibold text-fg">{name}</p>
              <p className="truncate text-[11px] text-faint">{user.email || `@${user.username}`}</p>
            </div>
          </div>
          {user.id !== 'local-dev' && (
            <div className="mt-4 border-t border-line pt-3">
              <button
                type="button"
                onClick={() => {
                  setOpen(false)
                  onLogout()
                }}
                className={btnOutline('rounded-none')}
              >
                登出
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function App() {
  const route = useHashRoute()
  /* /article/{url}：文档详情路由，url 为 slug（可含 URL 编码） */
  const articleMatch = /^\/article\/(.+)$/.exec(route)
  /* /multi-agent/{id}：智能体内部页，id 为成员 uuid（末段 new = 新建） */
  const agentMatch = /^\/multi-agent\/(.+)$/.exec(route)
  const [user, setUser] = useState<SsoUser | null>(null)
  const [connErr, setConnErr] = useState<string | null>(null)
  const mainRef = useRef<HTMLElement>(null)

  /* 非图谱路由的滚动容器是 main：切路由时回到顶部 */
  useEffect(() => {
    mainRef.current?.scrollTo(0, 0)
  }, [route])

  useEffect(() => {
    me()
      .then(setUser)
      .catch((e: unknown) => {
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
        } else {
          setConnErr(e instanceof Error ? e.message : String(e))
        }
      })
  }, [])

  const handleLogout = async () => {
    try {
      await logout()
    } catch {
      // 即使后端不可达也刷新本地状态
    }
    window.location.reload()
  }

  return (
    /* header + content + footer 三段占满 100vh；长内容在 main 内部滚动。
       图谱与圆桌自己管滚动（各自占满剩余高度），所以这两条路由的 main 不滚动、不加内边距 */
    <div className="flex h-screen flex-col overflow-hidden bg-bg text-fg antialiased">
      {/* 顶栏：品牌 + 导航 + 主题入口 + 用户菜单 */}
      <header className="shrink-0 border-b border-line">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-6 px-6 py-4">
          <div className="flex min-w-0 items-center gap-5">
            <nav className="flex items-center gap-5 overflow-x-auto">
              {NAV.map((n) => {
                /* 文章详情路由 /article/* 也归属「文档」导航项 */
                const active = route === n.path || (n.path === '/articles' && !!articleMatch)
                return (
                  <a
                    key={n.path}
                    href={`#${n.path}`}
                    className={
                      active
                        ? 'text-[12px] font-semibold text-fg underline decoration-fg underline-offset-8'
                        : 'text-[12px] font-medium text-faint underline-offset-8 transition hover:text-fg'
                    }
                  >
                    {n.label}
                  </a>
                )
              })}
            </nav>
          </div>
          {/* 主题面板居中偏右，用户按钮最右 */}
          <div className="flex shrink-0 items-center gap-4 text-[13px]">
            <ThemePanel />
            {user && <UserMenu user={user} onLogout={handleLogout} />}
          </div>
        </div>
      </header>

      {/* 每个路由包一层 data-page（语义锚点），内容由 PageSlot 决定：原生页面或注册表中的渲染版本。
          图谱路由满幅占满剩余高度；其余路由保持 max-w 排版并在内部滚动 */}
      <main
        ref={mainRef}
        className={
          route === '/graph' || route === '/roundtable'
            ? 'min-h-0 flex-1'
            : `mx-auto w-full max-w-6xl min-h-0 flex-1 overflow-y-auto px-6 pb-16${
                route === '/' ? ' scrollbar-hide' : ''
              }${articleMatch ? ' scrollbar-accent' : ''}${
                ['/analyze', '/subconscious', '/multi-agent', '/workspace', '/settings', '/stats', '/about'].includes(route) ||
                !!agentMatch
                  ? ' scrollbar-slim'
                  : ''
              }`
        }
      >
        {route === '/' && (
          <div data-page="home">
            <PageSlot route="home" props={{ connErr }} fallback={<Home connErr={connErr} />} />
          </div>
        )}
        {route === '/search' && (
          <div data-page="search" className="h-full">
            <PageSlot route="search" props={{}} fallback={<Search />} />
          </div>
        )}
        {route === '/graph' && (
          <div data-page="graph" className="h-full">
            <PageSlot route="graph" props={{}} fallback={<Graph />} />
          </div>
        )}
        {route === '/articles' && (
          <div data-page="articles">
            <PageSlot route="articles" props={{}} fallback={<Articles />} />
          </div>
        )}
        {articleMatch && (
          <div data-page="article">
            <PageSlot
              route="article"
              props={{ url: decodeURIComponent(articleMatch[1]) }}
              fallback={<ArticleDetailPage url={decodeURIComponent(articleMatch[1])} />}
            />
          </div>
        )}
        {route === '/import' && (
          <div data-page="import">
            <PageSlot route="import" props={{}} fallback={<Import />} />
          </div>
        )}
        {route === '/analyze' && (
          <div data-page="analyze">
            <PageSlot route="analyze" props={{}} fallback={<Analyze />} />
          </div>
        )}
        {route === '/subconscious' && (
          <div data-page="subconscious">
            <PageSlot route="subconscious" props={{}} fallback={<Subconscious />} />
          </div>
        )}
        {route === '/multi-agent' && (
          <div data-page="multi-agent">
            <PageSlot route="multi-agent" props={{}} fallback={<MultiAgent />} />
          </div>
        )}
        {agentMatch && (
          <div data-page="agent">
            <PageSlot
              route="agent"
              props={{ id: decodeURIComponent(agentMatch[1]) }}
              fallback={<AgentDetail peerId={decodeURIComponent(agentMatch[1])} />}
            />
          </div>
        )}
        {route === '/roundtable' && (
          <div data-page="roundtable" className="h-full">
            <PageSlot route="roundtable" props={{}} fallback={<Roundtable />} />
          </div>
        )}
        {route === '/settings' && (
          <div data-page="settings">
            <PageSlot route="settings" props={{}} fallback={<Settings />} />
          </div>
        )}
        {route === '/workspace' && (
          <div data-page="workspace" className="h-full">
            <PageSlot route="workspace" props={{}} fallback={<Workspace />} />
          </div>
        )}
        {route === '/stats' && (
          <div data-page="stats">
            <PageSlot route="stats" props={{}} fallback={<Stats />} />
          </div>
        )}
        {route === '/about' && (
          <div data-page="about">
            <PageSlot route="about" props={{}} fallback={<About />} />
          </div>
        )}
        {!['/', '/search', '/graph', '/articles', '/import', '/analyze', '/subconscious', '/multi-agent', '/roundtable', '/workspace', '/settings', '/stats', '/about'].includes(route) &&
          !articleMatch && !agentMatch && <p className="pt-24 text-[14px] text-faint">404 —— 没有这个页面。</p>}
      </main>

      <footer className="shrink-0 border-t border-line">
        <p className="mx-auto max-w-6xl px-6 py-6 text-[12px] text-faint">
          {BRAND.footer}
        </p>
      </footer>

      {/* 页级消息通知：大模型把应答异步投回会话时，无论当前在哪一页，左上角弹气泡提醒 */}
      <ChatNotifier />
    </div>
  )
}

export default App
