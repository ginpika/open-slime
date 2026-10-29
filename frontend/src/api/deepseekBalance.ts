export type DeepSeekBalance = {
  status: 'ready' | 'error' | 'unsupported' | 'unconfigured'
  available: boolean | null
  balances: { currency: 'CNY' | 'USD'; totalBalance: string; grantedBalance: string; toppedUpBalance: string }[]
  checkedAt: number | null
  attemptedAt: number | null
  error: string | null
}

/** Mirror the server allowlist for presentation; the server independently validates saved endpoints. */
export function isOfficialDeepSeek(baseUrl: string): boolean {
  try {
    const url = new URL(baseUrl.trim())
    const path = url.pathname.replace(/\/+$/, '')
    return url.protocol === 'https:' && url.hostname === 'api.deepseek.com' && (!url.port || url.port === '443')
      && !url.username && !url.password && !url.search && !url.hash && (path === '' || path === '/v1')
  } catch { return false }
}
