/**
 * 外壳图标：线性、24×24、currentColor、strokeWidth 1.8。
 * 各 kit 只负责给容器上色与尺寸，图标本身不随主题变形，
 * 保证"同一套语义符号"在任何设计语言里都成立。
 */
import type { IconName } from './nav'

const PATHS: Record<IconName, string> = {
  home: 'M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z',
  doc: 'M7 3h7l5 5v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM14 3v5h5M9 13h7M9 17h5',
  graph: 'M12 4.5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM5.5 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM18.5 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM10.6 8.6 7 14.4M13.4 8.6 17 14.4M7.5 17h9',
  import: 'M12 3v11M8 10l4 4 4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2',
  analyze: 'M4 19V5M4 19h16M8 15l3-5 3 3 4-7',
  agent: 'M9 8.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM3.5 20c0-3 2.5-4.8 5.5-4.8s5.5 1.8 5.5 4.8M16.5 5.6a2.8 2.8 0 0 1 0 5.3M18 15.4c1.8.6 3 1.9 3 4',
  subcons: 'M12 3a6 6 0 0 0-3.5 10.9V17h7v-3.1A6 6 0 0 0 12 3zM10 19.5h4M10.5 21.5h3',
  workspace: 'M3 8.5 12 4l9 4.5-9 4.5-9-4.5zM3 12.5 12 17l9-4.5M3 16.5 12 21l9-4.5',
  settings: 'M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM19.4 13.6a7.7 7.7 0 0 0 0-3.2l2-1.5-2-3.4-2.4 1a7.7 7.7 0 0 0-2.8-1.6L13.8 2h-3.6l-.4 2.9a7.7 7.7 0 0 0-2.8 1.6l-2.4-1-2 3.4 2 1.5a7.7 7.7 0 0 0 0 3.2l-2 1.5 2 3.4 2.4-1a7.7 7.7 0 0 0 2.8 1.6l.4 2.9h3.6l.4-2.9a7.7 7.7 0 0 0 2.8-1.6l2.4 1 2-3.4z',
  stats: 'M5 20V10M12 20V4M19 20v-7',
  about: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 10.5v6M12 7.5h.01',
}

export function ShellIcon({ name, className = '' }: { name: IconName; className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={PATHS[name]} />
    </svg>
  )
}
