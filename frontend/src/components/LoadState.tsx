import { btnOutline } from './ui'

/** Important failures remain next to their data; short notices are only supplementary. */
export function LoadState({ loading, error, hasData, onRetry, label = '内容', stale }: {
  loading: boolean; error: string | null; hasData: boolean; onRetry: () => void; label?: string; stale?: string
}) {
  if (error) return <div className="load-state load-state--error" role="alert"><div><strong>{label}读取失败</strong><p>{error}</p>{hasData && <p>{stale ?? '下方保留上次成功读取的内容，尚未更新。'}</p>}</div><button type="button" className={btnOutline()} disabled={loading} onClick={onRetry}>{loading ? '重试中…' : '重试读取'}</button></div>
  if (!loading) return null
  if (hasData) return <p className="load-state__refresh" role="status">正在更新{label}，下方为上次读取结果…</p>
  return <div className="load-state__loading" role="status" aria-label={`正在读取${label}`}><p>正在读取{label}…</p><div className="load-state__skeleton" aria-hidden="true"><i /><i /><i /></div></div>
}
