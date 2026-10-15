import { AppIcon, type AppIconName } from '../../components/AppIcon'

const icons = {
  plus: 'plus', folder: 'folder', file: 'file', chat: 'chat',
  chevron: 'chevron-right', down: 'chevron-down', refresh: 'refresh',
  close: 'close', back: 'arrow-left', arrow: 'arrow-right',
  external: 'external', send: 'arrow-up', search: 'search',
  review: 'alert', settings: 'settings',
} as const satisfies Record<string, AppIconName>

export default function CodingIcon({ name, className = '' }: { name: keyof typeof icons; className?: string }) {
  return <AppIcon name={icons[name]} className={`coding-icon ${className}`} />
}
