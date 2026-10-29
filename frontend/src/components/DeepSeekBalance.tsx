import { useEffect, useRef, useState } from 'react'
import { ApiError, loginUrlOf, requestJson } from '../api/client'
import type { DeepSeekBalance as BalanceView } from '../api/deepseekBalance'
import { btnQuiet } from './ui'
import './deepseekBalance.css'

/** Only saved settings are queried. A new settings revision discards the previous account's view. */
export default function DeepSeekBalance({ path, revision }: { path: string; revision: object }) {
  const [state, setState] = useState<{ revision: object; path: string; view: BalanceView | null; loading: boolean; error: string | null }>(
    () => ({ revision, path, view: null, loading: true, error: null }),
  )
  // Hide a previous account's result immediately, including the render before effect cleanup.
  const { view, loading, error } = state.revision === revision && state.path === path ? state : { view: null, loading: true, error: null }
  const refresh = useRef<() => void>(() => {})

  useEffect(() => {
    let alive = true, pending = false
    async function query(manual = false) {
      if (pending || (!manual && document.visibilityState === 'hidden')) return
      pending = true
      setState(previous => ({ revision, path, loading: true,
        view: previous.revision === revision && previous.path === path ? previous.view : null,
        error: previous.revision === revision && previous.path === path ? previous.error : null }))
      try {
        const next = await requestJson<BalanceView>(`${path}?refresh=${manual}`, { cache: 'no-store' }, 12_000)
        if (alive) setState({ revision, path, view: next, loading: false, error: null })
      } catch (e) {
        if (!alive) return
        const login = loginUrlOf(e)
        if (login) window.location.assign(login)
        else setState(previous => ({ ...previous, loading: false,
          error: e instanceof ApiError ? `余额查询失败（HTTP ${e.status}），请稍后刷新` : '余额查询网络失败或超时，请稍后刷新' }))
      } finally {
        pending = false
      }
    }
    refresh.current = () => { void query(true) }
    const visible = () => { if (document.visibilityState === 'visible') void query() }
    void query()
    const timer = window.setInterval(() => { void query() }, 30_000)
    document.addEventListener('visibilitychange', visible)
    return () => {
      alive = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', visible)
      refresh.current = () => {}
    }
  }, [path, revision])

  const failure = error || view?.error
  const stale = !!failure && view?.checkedAt != null
  return <section className="deepseek-balance" aria-label="DeepSeek 官方账户余额" aria-busy={loading}>
    <div className="deepseek-balance-heading">
      <span className="font-semibold">DeepSeek 官方账户余额</span>
      <button type="button" className={btnQuiet()} disabled={loading} onClick={() => refresh.current()}>
        {loading ? '查询余额中…' : '刷新余额'}
      </button>
    </div>
    {view && view.balances.length > 0 && <>
      <p className={`deepseek-balance-state ${view.available === false ? 'text-danger' : 'text-muted'}`}>
        {stale ? '上次状态：' : ''}{view.available ? '余额可用' : '余额不足，无法调用 API'}
      </p>
      {view.balances.map((balance, index) => <div className="deepseek-balance-amounts" key={`${balance.currency}:${index}`}>
        <div><span className="text-muted">{stale ? '上次总余额' : '总余额'} · {balance.currency}</span>
          <strong className="deepseek-balance-total">{balance.totalBalance}</strong></div>
        <div className="deepseek-balance-breakdown text-muted">
          <span>赠送余额 <span className="font-mono text-fg">{balance.grantedBalance}</span></span>
          <span>充值余额 <span className="font-mono text-fg">{balance.toppedUpBalance}</span></span>
        </div>
      </div>)}
    </>}
    {failure && <p role="alert" className="deepseek-balance-error text-danger">{stale ? '刷新失败，当前显示上次成功结果。' : ''}{failure}</p>}
    <p className="deepseek-balance-meta text-muted">
      {view?.checkedAt != null ? <>上次成功查询 <time dateTime={new Date(view.checkedAt).toISOString()}>{new Date(view.checkedAt).toLocaleString('zh-CN', { hour12: false })}</time> · </> : !loading && !failure ? '尚未查询到余额 · ' : ''}
      页面可见时每 30 秒刷新
    </p>
    <p className="deepseek-balance-meta text-muted">使用已保存配置查询账户余额；同一账户的模型与联网检索共享余额。
      {' '}<a href="https://platform.deepseek.com/" target="_blank" rel="noreferrer" className="underline hover:text-accent">官方控制台</a>
    </p>
  </section>
}
