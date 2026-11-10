import { useEffect, useRef, useState, type ReactNode } from 'react'
import { loginUrlOf, logout, me, type SsoUser } from './api/client'
import { isDesktopShell, backToLauncher, openDesktopHome } from './api/desktop'
import AgentHome from './pages/agent/AgentHome'
import AgentConversation from './pages/agent/AgentConversation'
import ContextDebug from './pages/ContextDebug'
import Dashboard from './pages/Dashboard'
import Knowledge from './pages/Knowledge'
import Economy from './pages/Economy'
import EconomyResearch from './pages/economy/EconomyResearch'
import EconomyDatabase from './pages/economy/EconomyDatabase'
import Search from './pages/Search'
import Graph from './pages/Graph'
import Articles from './pages/Articles'
import ArticleDetailPage from './pages/ArticleDetail'
import Import from './pages/Import'
import Snapshots from './pages/Snapshots'
import Analyze from './pages/Analyze'
import MultiAgent from './pages/MultiAgent'
import SystemPanel from './pages/SystemPanel'
import MemoryPanel from './pages/MemoryPanel'
import AgentDetail from './pages/agent/AgentDetail'
import Roundtable from './pages/Roundtable'
import Workspace from './pages/Workspace'
import Coding from './pages/Coding'
import Settings from './pages/Settings'
import Stats from './pages/Stats'
import Todos from './pages/Todos'
import { ThemePanel } from './theme/registry'
import { useThemeState } from './theme/store'
import { PageSlot } from './theme/overrides'
import { AppShell } from './shell'
import { DesktopFrame } from './shell/DesktopFrame'
import { ShellIcon } from './shell/icons'
import { activePathOf } from './shell/nav'
import { isKnownRoute, routeFromHash } from './shell/routes'
import { btnOutline } from './components/ui'
import ChatNotifier from './components/ChatNotifier'
import SlimePage from './components/SlimePage'

function useHashRoute(): string {
  const [route, setRoute] = useState(() => routeFromHash(window.location.hash))
  useEffect(() => {
    const onChange = () => {
      setRoute(routeFromHash(window.location.hash))
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

/** hostid 角色 → 展示名（未知角色原样回显） */
const ROLE_LABELS: Record<string, string> = {
  admin: '管理员',
  user: '普通用户',
}

/** 用户菜单里的一行只读信息：左标签右值（值可换行，长 id / 邮箱不截断） */
function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-3">
      <dt className="shrink-0 text-faint">{label}</dt>
      <dd className="min-w-0 flex-1 break-all text-right text-fg-2">{value}</dd>
    </div>
  )
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
  placement = 'down',
  withName = false,
  variant = 'plain',
}: {
  user: SsoUser
  onLogout: () => void
  open: boolean
  onOpenChange: (next: boolean) => void
  /** 面板弹出方向：底部栏向上，顶部栏向下 */
  placement?: 'up' | 'down'
  /** 按钮内是否展示昵称（底部栏的「头像 + 昵称」整体可点） */
  withName?: boolean
  /**
   * chip = 桌面框架标题栏里的身份胶囊（外观与引导页 .user-chip 同源，见 desktopFrame.css）；
   * plain = 外壳自己的用户按钮（跟随主题）
   */
  variant?: 'plain' | 'chip'
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
  const roleLabel = user.role ? (ROLE_LABELS[user.role] ?? user.role) : ''
  const initial = name ? name[0].toUpperCase() : '?'
  const chip = variant === 'chip'
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
    <div ref={boxRef} className="relative min-w-0">
      <button
        type="button"
        aria-label="用户"
        aria-expanded={open}
        title={name}
        onClick={() => onOpenChange(!open)}
        className={
          chip
            ? 'df-chip'
            : withName
              ? `flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left transition hover:bg-fg/5 ${
                  open ? 'bg-fg/10' : ''
                }`
              : `ui-btn ui-btn--icon h-[30px] w-[30px] overflow-hidden rounded-full border border-line transition hover:border-line-strong ${
                  open ? 'border-line-strong' : ''
                }`
        }
      >
        <Avatar size={chip ? 'df-chip__avatar' : withName ? 'h-7 w-7' : 'h-full w-full'} />
        {(withName || chip) && (
          <span
            className={
              chip
                ? 'df-chip__name min-w-0 flex-1'
                : 'min-w-0 flex-1 truncate text-sm font-medium text-fg'
            }
          >
            {name}
          </span>
        )}
      </button>
      {open && (
        <div
          className={`ui-card ui-surface menu-pop z-[60] ${
            placement === 'up'
              ? 'absolute inset-x-0 bottom-[calc(100%+10px)]'
              : 'absolute right-0 top-[calc(100%+10px)] w-64'
          }`}
        >
          <div className="ui-card__in p-4 text-left">
            <div className="flex items-center gap-3">
              <Avatar size="h-11 w-11" />
              <div className="min-w-0 flex-1">
                <p className="ui-heading truncate text-base">{name}</p>
                {user.username && (
                  <p className="truncate text-xs text-faint">@{user.username}</p>
                )}
              </div>
              {roleLabel && (
                <span
                  className="shrink-0 rounded-full px-2.5 py-0.5 text-2xs font-bold"
                  style={{ background: 'var(--c-accent-soft)', color: 'var(--c-accent)' }}
                >
                  {roleLabel}
                </span>
              )}
            </div>

            <dl className="mt-3 space-y-1 border-t border-line pt-3 text-xs">
              {user.email && <InfoRow label="邮箱" value={user.email} />}
              {user.id && <InfoRow label="用户 ID" value={user.id} />}
            </dl>

            {user.id !== 'local-dev' && (
              <button
                type="button"
                onClick={() => {
                  onOpenChange(false)
                  onLogout()
                }}
                className={btnOutline('mt-3 w-full')}
              >
                登出
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

/** 页面自管滚动、外壳不再套容器的路由 */
const FULL_BLEED = ['/collaboration', '/roundtable', '/workflow', '/coding', '/economy/research', '/economy/database']

function App() {
  const route = useHashRoute()
  /* /article/{url}：文档详情路由，url 为 slug（可含 URL 编码） */
  const articleMatch = /^\/article\/(.+)$/.exec(route)
  const snapshotMatch = /^\/snapshots\/([^/]+)$/.exec(route)
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
    // 桌面端：交回壳处理——回到引导页，别让窗口被后端 302 到 hostid 登录页
    if (isDesktopShell()) {
      backToLauncher()
      return
    }
    window.location.reload()
  }

  /* 每页包一层 data-page（语义锚点），内容由 PageSlot 决定：原生页面或注册表中的渲染版本 */
  const page = (
    <>
      {route === '/economy' && <div data-page="economy"><Economy /></div>}
      {route === '/economy/research' && <EconomyResearch />}
      {route === '/economy/database' && <EconomyDatabase />}
      {route === '/search' && (
        <div data-page="search" className="h-full">
          <PageSlot route="search" props={{}} fallback={<Search />} />
        </div>
      )}
      {route === '/debug' && (
        <div data-page="debug">
          <ContextDebug />
        </div>
      )}
      {route === '/memory' && (
        <div data-page="memory">
          <PageSlot route="memory" props={{}} fallback={<MemoryPanel />} />
        </div>
      )}
      {['/collaboration', '/roundtable', '/workflow'].includes(route) && (
        <div data-page="collaboration" className="h-full">
          <PageSlot route="collaboration" props={{}} fallback={<Roundtable initialView={route === '/workflow' ? 'graph' : 'discussion'} />} />
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
      {route === '/coding' && <div data-page="coding" className="h-full min-h-0"><Coding /></div>}
      {route === '/todos' && <div data-page="todos"><PageSlot route="todos" props={{}} fallback={<Todos />} /></div>}
      {route === '/stats' && (
        <div data-page="stats">
          <PageSlot route="stats" props={{}} fallback={<Stats />} />
        </div>
      )}
      {!isKnownRoute(route) && <p className="pt-24 text-base text-faint">404 —— 没有这个页面。</p>}
    </>
  )

  /* 桌面壳里窗口是无边框的，外壳外面还要套一层自绘框架（菜单栏 / 标题栏 / 窗口按钮）。
     身份胶囊随之挪到框架标题栏——那儿才是窗口的右上角，和窗口按钮同一行（与引导页一致）；
     因此桌面端要把外壳自己的那个槽位留空，否则同一个用户会渲染两次。
     浏览器访问时没有框架，外壳照旧自己渲染用户按钮。 */
  const desktop = isDesktopShell()
  /* 桌面端沉浸排布（默认开启，可在主题菜单里关闭）：隐藏外壳的 header/footer，
     主题按钮随之从 header 挪到窗口标题栏、身份胶囊左侧。关闭时一切照旧。 */
  const appearance = useThemeState()
  const hideChrome = desktop && (appearance.themeId === 'astrolabe' ? appearance.astrolabe.hideChrome : appearance.mono.hideChrome)
  const themePanel = (
    <ThemePanel
      open={themeOpen}
      onOpenChange={setThemeOpen}
      /* 挪到标题栏时改用深色轨按钮样式，与右侧窗口按钮统一 */
      variant={hideChrome ? 'chrome' : 'plain'}
    />
  )
  /* 桌面端标题栏的首页入口：回引导页的「启动」视图（视图一），落在身份胶囊左侧。
     点击让壳把窗口带回引导页（不登出，见 api/desktop 的 openDesktopHome）；
     样式复用标题栏工具钮（df__toolbtn） */
  const homeButton = (
    <button
      type="button"
      className="df__toolbtn"
      title="首页"
      aria-label="首页"
      onClick={openDesktopHome}
    >
      <ShellIcon name="home" />
    </button>
  )
  const renderUser = (slot: { placement: 'up' | 'down'; withName: boolean }, variant?: 'chip') =>
    user ? (
      <UserMenu
        user={user}
        onLogout={handleLogout}
        open={userOpen}
        onOpenChange={setUserOpen}
        placement={slot.placement}
        withName={slot.withName}
        variant={variant}
      />
    ) : null

  /* Dashboard 及其独立页面与对话首页共用无侧栏窗口。 */
  let standalone: ReactNode = null
  switch (route) {
    case '/':
      standalone = <AgentHome connErr={connErr} userSlot={renderUser({ placement: 'up', withName: false })} />
      break
    case '/dashboard':
      standalone = <Dashboard />
      break
    case '/knowledge':
      /* 与首页 / Dashboard 一致：不套外壳顶栏与页脚，只保留极简页头 */
      standalone = (
        <SlimePage title="知识库" backHref="#/dashboard" backLabel="返回 Dashboard">
          <div data-page="knowledge"><Knowledge /></div>
        </SlimePage>
      )
      break
    /* 知识库子页与起始页 / Dashboard 同一套独立窗口：极简页头 + 返回知识库 */
    case '/articles':
      standalone = (
        <SlimePage key={route} title="文档" backHref="#/knowledge" backLabel="返回知识库">
          <div data-page="articles"><PageSlot route="articles" props={{}} fallback={<Articles />} /></div>
        </SlimePage>
      )
      break
    case '/import':
      standalone = (
        <SlimePage key={route} title="文档导入" backHref="#/knowledge" backLabel="返回知识库">
          <div data-page="import"><PageSlot route="import" props={{}} fallback={<Import />} /></div>
        </SlimePage>
      )
      break
    case '/analyze':
      standalone = (
        <SlimePage key={route} title="文档分析" backHref="#/knowledge" backLabel="返回知识库">
          <div data-page="analyze"><PageSlot route="analyze" props={{}} fallback={<Analyze />} /></div>
        </SlimePage>
      )
      break
    case '/graph':
      standalone = (
        <SlimePage key={route} title="知识图谱" backHref="#/knowledge" backLabel="返回知识库">
          <div data-page="graph" className="h-full"><PageSlot route="graph" props={{}} fallback={<Graph />} /></div>
        </SlimePage>
      )
      break
    case '/snapshots':
      standalone = (
        <SlimePage key={route} title="网页快照" backHref="#/knowledge" backLabel="返回知识库">
          <div data-page="snapshots"><Snapshots /></div>
        </SlimePage>
      )
      break
    case '/agent-conversation':
      standalone = <AgentConversation />
      break
    case '/multi-agent':
      standalone = (
        <SlimePage key={route} title="智能体" backHref="#/dashboard" backLabel="返回 Dashboard">
          <div data-page="multi-agent">
            <PageSlot route="multi-agent" props={{}} fallback={<MultiAgent />} />
          </div>
        </SlimePage>
      )
      break
    case '/system':
      standalone = (
        <SlimePage key={route} title="系统" backHref="#/dashboard" backLabel="返回 Dashboard">
          <div data-page="system">
            {/* 沿用原系统视图的页面覆盖键。 */}
            <PageSlot route="subconscious" props={{}} fallback={<SystemPanel />} />
          </div>
        </SlimePage>
      )
      break
  }

  /* 文档详情与快照详情跟随所属列表页：独立页面、极简页头 */
  if (articleMatch && !standalone) {
    const articleUrl = decodeURIComponent(articleMatch[1])
    standalone = (
      <SlimePage key={articleUrl} title="文档" backHref="#/articles" backLabel="返回文档列表">
        <div data-page="article">
          <PageSlot route="article" props={{ url: articleUrl }} fallback={<ArticleDetailPage url={articleUrl} />} />
        </div>
      </SlimePage>
    )
  }

  if (snapshotMatch && !standalone) {
    const snapshotId = snapshotMatch[1]
    standalone = (
      <SlimePage key={snapshotId} title="网页快照" backHref="#/snapshots" backLabel="返回快照库">
        <div data-page="snapshots"><Snapshots id={snapshotId} /></div>
      </SlimePage>
    )
  }

  /* 智能体详情与成员名册一致：独立页面、极简页头，不再套外壳导航与页脚 */
  if (agentMatch && !standalone) {
    const peerId = decodeURIComponent(agentMatch[1])
    standalone = (
      <SlimePage
        title={peerId === 'new' ? '新建成员' : '智能体'}
        backHref="#/multi-agent"
        backLabel="返回成员列表"
      >
        <PageSlot route="agent" props={{ id: peerId }} fallback={<AgentDetail key={peerId} peerId={peerId} />} />
      </SlimePage>
    )
  }

  if (standalone) {
    return (
      <>
        {desktop ? (
          <DesktopFrame
            hideRail
            activePath={route}
            toolsSlot={null}
            homeSlot={homeButton}
            userSlot={renderUser({ placement: 'down', withName: true }, 'chip')}
          >
            {standalone}
          </DesktopFrame>
        ) : (
          standalone
        )}
        <ChatNotifier />
      </>
    )
  }

  const shell = (
    <AppShell
      route={route}
      activePath={activePathOf(route)}
      fullBleed={FULL_BLEED.includes(route)}
      hideChrome={hideChrome}
      tools={hideChrome ? null : themePanel}
      userSlot={desktop ? () => null : (slot) => renderUser(slot)}
      mainRef={mainRef}
    >
      {page}
    </AppShell>
  )

  return (
    <>
      {desktop ? (
        <DesktopFrame
          activePath={activePathOf(route)}
          toolsSlot={hideChrome ? themePanel : null}
          homeSlot={homeButton}
          userSlot={renderUser({ placement: 'down', withName: true }, 'chip')}
        >
          {shell}
        </DesktopFrame>
      ) : (
        shell
      )}

      {/* 页级消息通知：大模型把应答异步投回会话时，无论当前在哪一页，左上角弹气泡提醒 */}
      <ChatNotifier />
    </>
  )
}

export default App
