import { AppIcon, type AppIconName } from '../components/AppIcon'
import SlimePage from '../components/SlimePage'
import { NAV } from '../shell/nav'
import './dashboard.css'

/* 首屏优先智能体、记忆、知识库；系统、用量统计、系统设置固定在末尾 */
const SHORTCUTS: { path: string; label?: string; icon?: AppIconName }[] = [
  { path: '/multi-agent' },
  { path: '/memory' },
  { path: '/knowledge', label: '知识库', icon: 'folder-open' },
  { path: '/todos', label: '项目与待办' },
  { path: '/coding' },
  { path: '/collaboration' },
  { path: '/economy' },
  { path: '/workspace' },
  { path: '/system' },
  { path: '/stats', label: '用量与统计' },
  { path: '/settings', label: '系统设置' },
]

const tools = SHORTCUTS.flatMap(({ path, label, icon }) => {
  const item = NAV.find((entry) => entry.path === path)
  return item ? [{ path, label: label ?? item.label, icon: icon ?? item.icon }] : []
})

export default function Dashboard() {
  return (
    <SlimePage title="Dashboard" backHref="#/" backLabel="回到对话">
      <nav className="dash-launcher" aria-label="工具入口">
        <ul className="dash-grid" role="list">
          {tools.map(({ path, label, icon }) => (
            <li key={path}>
              <a className="dash-tool" href={`#${path}`}>
                <span className="dash-tool__icon"><AppIcon name={icon} size={24} /></span>
                <span>{label}</span>
              </a>
            </li>
          ))}
        </ul>
      </nav>
    </SlimePage>
  )
}
