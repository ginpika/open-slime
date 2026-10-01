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
import { AppShell } from './shell'
import { activePathOf } from './shell/nav'
import { btnOutline } from './components/ui'
import ChatNotifier from './components/ChatNotifier'

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

/**
 * header 最右侧的圆形头像按钮：点击展开简单用户菜单（信息 + 登出）。
 * 开合状态由 App 持有：切换主题会替换 shell 组件导致子树重挂，
 * 状态放在这里才不会在切主题时被重置。
 */
function UserMenu({
  user,
  onLogout,
  open,
  onOpenChange,
}: {
  user: SsoUser
  onLogout: () => void
  open: boolean
  onOpenChange: (next: boolean) => void
}) {
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) onOpenChange(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onOpenChange(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onOpenChange])

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
        onClick={() => onOpenChange(!open)}
        className={`ui-btn ui-btn--icon h-[30px] w-[30px] overflow-hidden rounded-full border border-line transition hover:border-line-strong ${
          open ? 'border-line-strong' : ''
        }`}
      >
        <Avatar size="h-full w-full" />
      </button>
      {open && (
        <div className="ui-card ui-surface absolute right-0 top-[calc(100%+10px)] z-[60] w-56">
          <div className="ui-card__in p-4 text-left">
            <div className="flex items-center gap-3">
              <Avatar size="h-10 w-10" />
              <div className="min-w-0">
                <p className="truncate text-base font-semibold text-fg">{name}</p>
                <p className="truncate text-xs text-faint">{user.email || `@${user.username}`}</p>
              </div>
            </div>
            {user.id !== 'local-dev' && (
              <div className="mt-4 border-t border-line pt-3">
                <button
                  type="button"
                  onClick={() => {
                    onOpenChange(false)
                    onLogout()
                  }}
                  className={btnOutline('rounded-none')}
                >
                  登出
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

/** 页面自管滚动、外壳不再套容器的路由 */
const FULL_BLEED = ['/graph', '/roundtable']

function App() {
  const route = useHashRoute()
  /* /article/{url}：文档详情路由，url 为 slug（可含 URL 编码） */
  const articleMatch = /^\/article\/(.+)$/.exec(route)
  /* /multi-agent/{id}：智能体内部页，id 为成员 uuid（末段 new = 新建） */
  const agentMatch = /^\/multi-agent\/(.+)$/.exec(route)
  const [user, setUser] = useState<SsoUser | null>(null)
  const [connErr, setConnErr] = useState<string | null>(null)
  const mainRef = useRef<HTMLElement>(null)
  /* 外壳内的弹层开合状态必须放在这里（App 在 shell 之上，不会因换主题而重挂） */
  const [themeOpen, setThemeOpen] = useState(false)
  const [userOpen, setUserOpen] = useState(false)

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

  /* 每页包一层 data-page（语义锚点），内容由 PageSlot 决定：原生页面或注册表中的渲染版本 */
  const page = (
    <>
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
      {![
        '/',
        '/search',
        '/graph',
        '/articles',
        '/import',
        '/analyze',
        '/subconscious',
        '/multi-agent',
        '/roundtable',
        '/workspace',
        '/settings',
        '/stats',
        '/about',
      ].includes(route) &&
        !articleMatch &&
        !agentMatch && <p className="pt-24 text-base text-faint">404 —— 没有这个页面。</p>}
    </>
  )

  return (
    <>
      <AppShell
        route={route}
        activePath={activePathOf(route)}
        fullBleed={FULL_BLEED.includes(route)}
        tools={<ThemePanel open={themeOpen} onOpenChange={setThemeOpen} />}
        userSlot={
          user ? (
            <UserMenu
              user={user}
              onLogout={handleLogout}
              open={userOpen}
              onOpenChange={setUserOpen}
            />
          ) : null
        }
        mainRef={mainRef}
      >
        {page}
      </AppShell>

      {/* 页级消息通知：大模型把应答异步投回会话时，无论当前在哪一页，左上角弹气泡提醒 */}
      <ChatNotifier />
    </>
  )
}

export default App
