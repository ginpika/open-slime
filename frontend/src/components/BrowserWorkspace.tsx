import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import {
  browserCommand, browserViews, captureBrowser, distillCapture, saveSnapshot, webError, DEFAULT_MEDIA,
  type BrowserCommand, type BrowserView, type Capture, type MediaOptions, type SnapshotDraft, type WebSnapshot,
} from '../api/webSnapshots'
import { navigationUrl, readableUrl } from '../api/readableUrl'
import { loginUrlOf } from '../api/client'
import SnapshotReader from './SnapshotReader'
import { bindBrowserWheel, type BrowserWheel } from './browserWheel'

export const WEB_FIELD = 'w-full rounded-lg border border-line bg-bg px-3 py-2.5 text-sm text-fg outline-none placeholder:text-hint focus:border-accent focus:ring-2 focus:ring-accent/20 disabled:opacity-50'
const WEB_BUTTON_BASE = 'inline-flex items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 disabled:cursor-not-allowed disabled:opacity-50'
export const WEB_BUTTON = `${WEB_BUTTON_BASE} border-line bg-bg text-fg hover:border-accent hover:bg-accent/5`
export const WEB_PRIMARY = `${WEB_BUTTON_BASE} border-accent bg-accent text-accent-fg hover:bg-accent-hover`
const INITIAL_INSTRUCTION = '保留有长期价值的知识、分析与论据。检查来源、时效和正文完整性；区分事实与作者观点，保留条件、反例和局限，每个论点引用正文。'
const SCROLL_BUSY = '正在滚动网页…'

export default function BrowserWorkspace({ chatSessionId, onSaved }: { chatSessionId?: string; onSaved?: (snapshot: WebSnapshot) => void }) {
  const [url, setUrl] = useState('')
  const [media, setMedia] = useState<MediaOptions>(DEFAULT_MEDIA)
  const [view, setView] = useState<BrowserView | null>(null)
  const [views, setViews] = useState<BrowserView[]>([])
  const [capture, setCapture] = useState<Capture | null>(null)
  const [draft, setDraft] = useState<SnapshotDraft | null>(null)
  const [instruction, setInstruction] = useState(INITIAL_INSTRUCTION)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<WebSnapshot | null>(null)
  const [ref, setRef] = useState('')
  const [input, setInput] = useState('')
  const mounted = useRef(true)
  const working = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  const fail = useCallback((e: unknown) => {
    const login = loginUrlOf(e)
    if (login) window.location.assign(login)
    else if (mounted.current) setError(webError(e))
  }, [])
  const refresh = useCallback(async () => {
    try {
      const next = await browserViews(chatSessionId)
      if (mounted.current) {
        setViews(next)
        setView(previous => next.find(v => v.browserId === previous?.browserId) ?? next[0] ?? null)
      }
    } catch (e) { fail(e) }
  }, [chatSessionId, fail])
  useEffect(() => {
    if (!chatSessionId) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      if (stopped) return
      await refresh()
      if (!stopped) timer = setTimeout(() => void poll(), 4000)
    }
    void poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [chatSessionId, refresh])

  const run = async (label: string, action: () => Promise<void>) => {
    if (working.current) return false
    working.current = true; setBusy(label); setError(null)
    try { await action(); return true } catch (e) { fail(e); return false }
    finally { working.current = false; if (mounted.current) setBusy('') }
  }
  const extracted = async (id: string, selection = media) => {
    const result = await captureBrowser(id, selection)
    if (!mounted.current) return
    setCapture(result); setSaved(null)
    setDraft({ captureId: result.captureId, title: result.title, summary: '', rationale: '用户指定此网页，保存完整正文用于离线阅读和知识检索。', claims: [], model: '' })
  }
  const changeMedia = (key: keyof MediaOptions, checked: boolean) => {
    const next = { ...media, [key]: checked }
    if (!capture || !view || capture.browserId !== view.browserId || capture.url !== view.url || view.blocked) {
      setMedia(next); setCapture(null); setDraft(null); setSaved(null)
      return
    }
    void run('正在更新媒体与快照预览…', async () => {
      setMedia(next)
      try { await extracted(view.browserId, next) }
      catch (e) { if (mounted.current) setMedia(media); throw e }
    })
  }
  const act = (command: BrowserCommand, label = '正在操作浏览器…') => run(label, async () => {
    const next = await browserCommand({ ...command, browserId: view?.browserId })
    if (mounted.current) { setView(next); setRef(''); if (next) setUrl(readableUrl(next.url)) }
  })
  const open = (event: FormEvent) => {
    event.preventDefault()
    if (!url.trim()) return
    void run('正在打开网页并提取正文…', async () => {
      setCapture(null); setDraft(null); setSaved(null)
      const next = await browserCommand({ browserId: view?.browserId, action: 'open', url: navigationUrl(url) })
      if (mounted.current) { setView(next); setRef(''); if (next) setUrl(readableUrl(next.url)) }
      if (next && !next.blocked) await extracted(next.browserId)
    })
  }

  if (chatSessionId) return (
    <section className="space-y-4" aria-label="会话浏览器">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted">跟随 Agent 的网页研究过程，画面每 4 秒更新。</p>
        <button type="button" className={WEB_BUTTON} onClick={() => void refresh()}>刷新画面</button>
      </div>
      {error && <p role="alert" className="rounded-lg border border-red-400/30 bg-red-400/5 p-3 text-sm text-fg">{error}</p>}
      {views.length > 1 && <div className="flex flex-wrap gap-2">{views.map(v => <button key={v.browserId} type="button" className={WEB_BUTTON} aria-pressed={view?.browserId === v.browserId} onClick={() => setView(v)}>{v.title || '浏览器'}</button>)}</div>}
      {view ? <BrowserScreen view={view} /> : <div className="rounded-xl border border-dashed border-line p-12 text-center text-sm text-muted">Agent 尚未打开浏览器。可在会话中要求它搜索 Google、阅读网页并保存有价值的快照。</div>}
      <a href="#/snapshots" className="inline-flex text-sm font-medium text-accent hover:underline">查看已保存的快照 →</a>
    </section>
  )

  return (
    <div className="space-y-5">
      <form onSubmit={open} className="rounded-xl border border-line bg-bg p-4 shadow-sm sm:p-5">
        <label htmlFor="snapshot-url" className="mb-2 block text-sm font-medium text-fg">网页地址</label>
        <div className="flex flex-col gap-3 sm:flex-row">
          <input id="snapshot-url" type="url" required placeholder="https://…" value={url} onChange={e => setUrl(readableUrl(e.target.value))} onBlur={() => setUrl(readableUrl(url))} className={`${WEB_FIELD} flex-1`} disabled={!!busy} />
          <button type="submit" className={`${WEB_PRIMARY} shrink-0`} disabled={!!busy || !url.trim()}>获取正文快照</button>
        </div>
        <p className="mt-2 text-xs leading-relaxed text-faint">打开网页后先提取正文；你可以检查内容、按指令蒸馏，再保存到本地知识库。</p>
        <fieldset className="mt-4 rounded-lg border border-line p-3" disabled={!!busy}>
          <legend className="px-1 text-xs font-medium text-muted">快照包含的媒体</legend>
          <div className="flex flex-wrap gap-x-6 gap-y-3">{([['images', '图片'], ['audio', '音频'], ['video', '视频']] as const).map(([key, label]) =>
            <label key={key} className="inline-flex cursor-pointer items-center gap-2 text-sm text-fg"><input type="checkbox" checked={media[key]} onChange={e => changeMedia(key, e.target.checked)} className="h-4 w-4 rounded border-line accent-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 disabled:cursor-not-allowed" />{label}</label>
          )}</div>
          <p className="mt-2 text-xs leading-relaxed text-faint">切换选项会重新提取并更新预览，同时重置蒸馏草稿。保存的媒体可离线阅读或播放；大文件、流媒体及嵌入播放器可能无法保存。</p>
        </fieldset>
      </form>
      {busy && busy !== SCROLL_BUSY && <p role="status" aria-live="polite" className="flex items-center gap-2 text-sm text-muted"><span className="h-2 w-2 animate-pulse rounded-full bg-accent" />{busy}</p>}
      {error && <p role="alert" className="rounded-lg border border-red-400/30 bg-red-400/5 p-3 text-sm text-fg">{error}</p>}
      {view && <section className="space-y-3 rounded-xl border border-line bg-bg p-4">
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={WEB_BUTTON} disabled={!!busy} onClick={() => void act({ action: 'back' })}>后退</button>
          <button type="button" className={WEB_BUTTON} disabled={!!busy} onClick={() => void act({ action: 'read' })}>刷新观察</button>
          <button type="button" className={WEB_BUTTON} disabled={!!busy} onClick={() => void act({ action: 'scroll', text: 'up' }, SCROLL_BUSY)}>向上</button>
          <button type="button" className={WEB_BUTTON} disabled={!!busy} onClick={() => void act({ action: 'scroll', text: 'down' }, SCROLL_BUSY)}>向下</button>
          <button type="button" className={WEB_BUTTON} disabled={!!busy || view.blocked} onClick={() => void run('正在提取正文…', () => extracted(view.browserId))}>提取当前正文</button>
          <button type="button" className={`${WEB_BUTTON} sm:ml-auto`} disabled={!!busy} onClick={() => void act({ action: 'close' })}>关闭浏览器</button>
        </div>
        <BrowserScreen view={view} scrolling={busy === SCROLL_BUSY} disabled={!!busy && busy !== SCROLL_BUSY} onScroll={async movement => {
          if (!await act({ action: 'scroll', ...movement }, SCROLL_BUSY)) throw new Error('滚动未完成')
        }} />
        <details className="rounded-lg border border-line p-3">
          <summary className="cursor-pointer text-sm font-medium text-fg">页面操作 · 链接与输入框</summary>
          <div className="mt-3 flex flex-col gap-2 sm:flex-row">
            <select aria-label="网页元素" className={`${WEB_FIELD} sm:w-1/2`} value={ref} onChange={e => setRef(e.target.value)} disabled={!!busy}>
              <option value="">选择可操作元素</option>
              {view.elements.map(e => <option key={e.ref} value={e.ref}>{e.tag} · {e.label || readableUrl(e.href) || e.ref}</option>)}
            </select>
            <input aria-label="输入到网页的文字" className={WEB_FIELD} value={input} onChange={e => setInput(e.target.value)} placeholder="输入文字" disabled={!!busy} />
          </div>
          <div className="mt-2 flex flex-wrap gap-2">
            <button type="button" className={WEB_BUTTON} disabled={!!busy || !ref} onClick={() => void act({ action: 'click', ref })}>点击</button>
            <button type="button" className={WEB_BUTTON} disabled={!!busy || !ref} onClick={() => void act({ action: 'fill', ref, text: input })}>填写</button>
            <button type="button" className={WEB_BUTTON} disabled={!!busy || !ref} onClick={() => void act({ action: 'press', ref, text: 'Enter' })}>按 Enter</button>
          </div>
        </details>
      </section>}
      {capture && draft && <section className="space-y-4 rounded-xl border border-line bg-bg p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="text-lg font-semibold text-fg">核查与蒸馏</h2><span className="text-xs text-faint">{capture.paragraphs.length} 段正文 · {capture.paragraphs.reduce((n, p) => n + p.text.length, 0).toLocaleString()} 字符</span></div>
        {capture.warnings.length > 0 && <ul className="space-y-1 rounded-lg border border-amber-500/30 bg-amber-500/5 p-3 text-sm text-fg">{capture.warnings.map(w => <li key={w}>{w}</li>)}</ul>}
        <label className="block text-sm font-medium text-fg">筛选与蒸馏指令<textarea className={`${WEB_FIELD} mt-2 min-h-24`} value={instruction} maxLength={4000} onChange={e => setInstruction(e.target.value)} disabled={!!busy || !!saved} /></label>
        <button type="button" className={WEB_BUTTON} disabled={!!busy || !!saved} onClick={() => void run('模型正在核查正文并生成带引用的论点…', async () => {
          const result = await distillCapture(capture.captureId, instruction); if (mounted.current) setDraft(result)
        })}>按指令蒸馏</button>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block text-sm font-medium text-fg">快照标题<input className={`${WEB_FIELD} mt-2`} value={draft.title} maxLength={500} onChange={e => setDraft({ ...draft, title: e.target.value })} disabled={!!busy || !!saved} /></label>
          <label className="block text-sm font-medium text-fg">保存理由与质量判断<input className={`${WEB_FIELD} mt-2`} value={draft.rationale} maxLength={4000} onChange={e => setDraft({ ...draft, rationale: e.target.value })} disabled={!!busy || !!saved} /></label>
        </div>
        {draft.summary && <label className="block text-sm font-medium text-fg">蒸馏摘要<textarea className={`${WEB_FIELD} mt-2 min-h-24`} maxLength={8000} value={draft.summary} onChange={e => setDraft({ ...draft, summary: e.target.value })} disabled={!!busy || !!saved} /></label>}
        {draft.claims.length > 0 && <div className="space-y-2">{draft.claims.map((claim, i) => <div key={i} className="flex items-start gap-3 rounded-lg border border-line p-3 text-sm"><p className="flex-1 text-fg">{claim.text}<span className="ml-2 text-xs text-faint">{claim.evidence.join(', ')}</span></p><button type="button" aria-label={`移除论点 ${i + 1}`} className="shrink-0 text-muted hover:text-accent" disabled={!!busy || !!saved} onClick={() => setDraft({ ...draft, claims: draft.claims.filter((_, j) => j !== i) })}>移除</button></div>)}</div>}
        <SnapshotReader title={draft.title} sourceHtml={capture.sourceHtml} summary={draft.summary} claims={draft.claims} />
        <div className="flex flex-wrap items-center gap-3">
          <button type="button" className={WEB_PRIMARY} disabled={!!busy || !!saved || !draft.title.trim() || !draft.rationale.trim()} onClick={() => void run('正在保存快照…', async () => {
            const result = await saveSnapshot(draft); if (mounted.current) setSaved(result); onSaved?.(result)
          })}>{saved ? '已保存' : draft.claims.length ? '保存蒸馏与正文证据' : '保存正文快照'}</button>
          {saved && <a href={`#/snapshots/${saved.id}`} className="text-sm font-medium text-accent hover:underline">打开本地快照 →</a>}
          <span className="text-xs text-faint">正文进入检索，模型论点保留为独立视图。</span>
        </div>
      </section>}
    </div>
  )
}

function BrowserScreen({ view, onScroll, disabled = false, scrolling = false }: { view: BrowserView; onScroll?: (wheel: BrowserWheel) => Promise<void>; disabled?: boolean; scrolling?: boolean }) {
  const surface = useRef<HTMLDivElement>(null)
  const latestScroll = useRef(onScroll)
  useEffect(() => { latestScroll.current = onScroll }, [onScroll])
  const interactive = !!onScroll && !disabled
  useEffect(() => {
    if (!interactive || !surface.current) return
    return bindBrowserWheel(surface.current, async movement => { await latestScroll.current?.(movement) })
  }, [interactive, view.browserId])
  return <div className="overflow-hidden rounded-xl border border-line bg-bg">
    <div className="flex items-center gap-3 border-b border-line px-3 py-2">
      <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-fg">{view.title || '浏览器'}</p><p className="truncate text-xs text-faint" title={readableUrl(view.url)}>{readableUrl(view.url)}</p></div>
      {onScroll && <div role="status" aria-live="polite" className="flex h-5 w-28 shrink-0 items-center justify-end gap-2 text-xs text-muted"><span className={`h-1.5 w-1.5 shrink-0 rounded-full bg-accent ${scrolling ? 'animate-pulse' : 'invisible'}`} /><span>{scrolling ? '正在滚动网页…' : ''}</span></div>}
    </div>
    {view.blocked && <p role="alert" className="border-b border-amber-500/30 bg-amber-500/5 p-3 text-sm text-fg">网页要求验证或拒绝访问，暂时不能提取正文。请处理后重试，或更换来源。</p>}
    <div ref={surface} title={onScroll ? '在画面上使用鼠标滚轮浏览网页' : undefined}>
      {view.screenshot ? <img src={`data:image/jpeg;base64,${view.screenshot}`} alt={`浏览器画面：${view.title}`} draggable={false} width={1280} height={800} className="block h-auto w-full bg-white" /> : <pre className="max-h-96 overflow-auto whitespace-pre-wrap p-4 text-sm text-muted">{view.text}</pre>}
    </div>
  </div>
}
