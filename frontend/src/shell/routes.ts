import { ECONOMY_NAV, KNOWLEDGE_NAV, NAV } from './nav'

const hiddenRoutes = new Set(['/search', '/subconscious', '/roundtable', '/workflow', '/dashboard', '/agent-conversation', '/debug'])

export function agentConversationHref(sessionId: string, messageId: number, origin = window.location.hash): string {
  const params = new URLSearchParams({ session: sessionId, message: String(messageId), from: origin })
  return `#/agent-conversation?${params}`
}

export function routeFromHash(hash: string): string {
  const raw = hash.replace(/^#/, '').split('?')[0]
  const path = raw.startsWith('/') ? raw : `/${raw}`
  if (path === '/subconscious') return '/system'
  if(path==='/economy/market'||path==='/economy/stock')return '/economy/research'
  return path
}

/** 功能页即使收进起始页，原有直达链接也仍是合法路由。 */
export function isKnownRoute(route: string): boolean {
  return NAV.some(item => item.path === route) || ECONOMY_NAV.some(item => item.path === route) || KNOWLEDGE_NAV.some(item => item.path === route) || hiddenRoutes.has(route)
    || route==='/economy/market' || route==='/economy/stock'
    || /^\/article\/(.+)$/.test(route)
    || /^\/snapshots\/([^/]+)$/.test(route)
    || /^\/multi-agent\/(.+)$/.test(route)
}
