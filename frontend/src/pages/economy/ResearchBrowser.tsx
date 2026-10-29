import { useCallback, useEffect, useRef, useState, type PointerEvent } from 'react'
import { webError, type BrowserCommand, type BrowserView } from '../../api/webSnapshots'
import { navigationUrl } from '../../api/readableUrl'
import { economyBrowserCommand, type ResearchSource } from '../../api/economy'
import { bindBrowserWheel } from '../../components/browserWheel'
import { AppIcon } from '../../components/AppIcon'

export default function ResearchBrowser({ onSource }: { onSource: (source: ResearchSource | null) => void }) {
  const [url, setUrl] = useState('https://www.tradingview.com/chart/?symbol=SSE%3A000001&interval=W')
  const [view, setView] = useState<BrowserView | null>(null)
  const [input, setInput] = useState('')
  const [pending, setPending] = useState(0)
  const [error, setError] = useState('')
  const surface = useRef<HTMLDivElement>(null)
  const current = useRef<BrowserView | null>(null)
  const alive = useRef(true)
  const queue = useRef(Promise.resolve())
  const queued = useRef(0)
  const pointer = useRef<{ x: number; y: number } | null>(null)
  const dragged = useRef(false)
  const onSourceRef = useRef(onSource); onSourceRef.current = onSource
  const send = useCallback((command: BrowserCommand): Promise<void> => {
    if (queued.current >= 40) return Promise.reject(new Error('操作过快，请等待画面更新'))
    queued.current++; if (alive.current) setPending(queued.current)
    if(command.action!=='read')onSourceRef.current(null)
    const operation = queue.current.then(async () => {
      if (!alive.current) return
      const next = await economyBrowserCommand({ ...command, browserId: current.current?.browserId })
      if (!alive.current) return
      current.current = next; setView(next); setError('')
      if (next) {
        sessionStorage.setItem('economy.browser.research', next.browserId)
        if (command.action === 'open' || command.action === 'back') setUrl(next.url)
      } else sessionStorage.removeItem('economy.browser.research')
    }).catch(e => { if (alive.current) setError(webError(e)); throw e })
      .finally(() => {
        queued.current--
        if (alive.current) {
          setPending(queued.current)
          const next=current.current
          if(!queued.current)onSourceRef.current(next && !next.blocked ? {browserId:next.browserId,title:next.title,capturedAt:Date.now()}:null)
        }
      })
    queue.current = operation.catch(() => {})
    return operation
  }, [])
  const act = (command: BrowserCommand) => { void send(command).catch(() => {}) }
  useEffect(() => {
    alive.current = true
    const id = sessionStorage.getItem('economy.browser.research') || sessionStorage.getItem('economy.browser.market') || sessionStorage.getItem('economy.browser.stock')
    if (id) { current.current = { browserId: id } as BrowserView; void send({ action: 'read' }).catch(() => { sessionStorage.removeItem('economy.browser.research'); current.current = null;onSourceRef.current(null) }) }
    const timer = setInterval(() => { if (document.visibilityState === 'visible' && current.current && !queued.current) void send({ action: 'read' }).catch(() => {}) }, 3000)
    return () => { alive.current = false; clearInterval(timer) }
  }, [send])
  const hasScreen = !!view?.screenshot
  useEffect(() => {
    if (!surface.current || !hasScreen) return
    return bindBrowserWheel(surface.current, movement => send({ action: 'scroll', ...movement }))
  }, [hasScreen, send])
  const position = (e: PointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    return { x: Math.max(0, Math.min(1279, Math.round((e.clientX - rect.left) * 1280 / rect.width))), y: Math.max(0, Math.min(799, Math.round((e.clientY - rect.top) * 800 / rect.height))) }
  }
  return <section className="eco-browser" aria-label="交互浏览器">
    <form className="eco-toolbar" onSubmit={e => { e.preventDefault(); act({ action: 'open', url: navigationUrl(url) }) }}>
      <input aria-label="图表网址" type="url" required value={url} onChange={e => setUrl(e.target.value)} />
      <button className="eco-primary" disabled={pending > 0}><AppIcon name="external"/>打开图表</button>
    </form>
    <div className="eco-toolbar">
      <button disabled={!view || !!pending} onClick={() => act({ action: 'back' })}><AppIcon name="arrow-left"/>后退</button>
      <button disabled={!view || !!pending} onClick={() => act({ action: 'read' })}><AppIcon name="refresh"/>刷新画面</button>
      <span role="status">{pending ? `正在更新 · ${pending}` : view ? '可点击、拖动与滚动' : '等待打开图表'}</span>
      <button disabled={!view || !!pending} onClick={() => act({ action: 'close' })}><AppIcon name="close"/>关闭图表</button>
    </div>
    {error && <p role="alert" className="eco-error">{error}</p>}
    {view?.blocked && <p role="alert" className="eco-error">页面需要验证或暂时拒绝访问，请在画面中处理后刷新。</p>}
    {hasScreen ? <div ref={surface} className="eco-screen" tabIndex={0} role="application" aria-label="图表画面，可点击拖动、滚动和键盘输入"
      onPointerDown={e => { if (e.button !== 0) return; e.preventDefault(); e.currentTarget.focus(); e.currentTarget.setPointerCapture(e.pointerId); pointer.current = position(e); dragged.current=false }}
      onPointerCancel={() => { pointer.current = null }}
      onPointerUp={e => { const start = pointer.current; pointer.current = null; if (!start) return; const end = position(e); const dx = end.x - start.x, dy = end.y - start.y;
        if (Math.hypot(dx, dy) > 5) { dragged.current=true; act({ action: 'drag', ...start, deltaX: dx, deltaY: dy }) }
      }}
      onClick={e => { if(dragged.current)return; const r=e.currentTarget.getBoundingClientRect(); act({ action:'click',text:e.detail===2?'double':undefined,x:Math.max(0,Math.min(1279,Math.round((e.clientX-r.left)*1280/r.width))),y:Math.max(0,Math.min(799,Math.round((e.clientY-r.top)*800/r.height))) }) }}
      onKeyDown={e => {
        if (e.nativeEvent.isComposing || e.key === 'Tab') return
        const key = (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a' ? 'ControlOrMeta+A' : e.key
        if (['Enter', 'Escape', 'ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Backspace', 'Delete', 'ControlOrMeta+A', '+', '-'].includes(key)) { e.preventDefault(); act({ action: 'press', text: key }) }
        else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); act({ action: 'type', text: e.key }) }
      }}>
      <img src={`data:image/jpeg;base64,${view!.screenshot}`} alt={`图表：${view!.title}`} width={1280} height={800} draggable={false} />
    </div> : <div className="eco-empty"><AppIcon name="economy" size={32}/><h3>打开你的行情图表</h3><p>输入 TradingView 或其他行情网址，在画面内点击、拖动和滚动。先从周线开始，保留标的、周期和日期。</p></div>}
    <details className="eco-input-tools"><summary>网页文字输入</summary>
    <form className="eco-toolbar" onSubmit={e => { e.preventDefault(); act({ action: 'type', text: input }); setInput('') }}>
      <input aria-label="输入到图表" placeholder="先点击网页输入框，再输入文字（支持中文）" value={input} onChange={e => setInput(e.target.value)} maxLength={4000} />
      <button disabled={!view || !input}>输入文字</button>
      <button type="button" disabled={!view} onClick={() => act({ action: 'press', text: 'Enter' })}>Enter</button>
      <button type="button" disabled={!view} onClick={() => act({ action: 'press', text: 'Escape' })}>Esc</button>
    </form>
    </details>
  </section>
}
