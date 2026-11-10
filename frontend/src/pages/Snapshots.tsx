import { useCallback, useEffect, useState } from 'react'
import { loginUrlOf } from '../api/client'
import { readableUrl } from '../api/readableUrl'
import {
  deleteSnapshot, downloadSnapshot, getSnapshot, indexSnapshot, INDEX_LABEL, listSnapshots, toggleSnapshot, webError,
  type WebSnapshot,
} from '../api/webSnapshots'
import BrowserWorkspace from '../components/BrowserWorkspace'
import SnapshotReader from '../components/SnapshotReader'
import SnapshotImageExport from '../components/SnapshotImageExport'
import SnapshotRename from '../components/SnapshotRename'
import './knowledge.css'

const stamp = (time: number) => new Date(time).toLocaleString()
const TAB_STORAGE_KEY = 'minis.snapshots.tab'

export default function Snapshots({ id }: { id?: string }) {
  const [tab, setTab] = useState<'capture' | 'library'>(() => {
    // A detail may also be opened directly from chat or search; returning should show its library.
    if (id) return 'library'
    try { return localStorage.getItem(TAB_STORAGE_KEY) === 'library' ? 'library' : 'capture' }
    catch { return 'capture' }
  })
  const [items, setItems] = useState<WebSnapshot[]>([])
  const [selected, setSelected] = useState<WebSnapshot | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    try { localStorage.setItem(TAB_STORAGE_KEY, tab) }
    catch { /* Storage restrictions must not prevent switching tabs. */ }
  }, [tab])
  const fail = useCallback((e: unknown) => {
    const login = loginUrlOf(e)
    if (login) window.location.assign(login)
    else setError(webError(e))
  }, [])
  useEffect(() => {
    if (!id && tab !== 'library') return
    let active = true
    setLoading(true); setError(null); setSelected(null)
    const load = id ? getSnapshot(id) : listSnapshots(page)
    void load.then(result => {
      if (!active) return
      if (Array.isArray(result)) setItems(result)
      else setSelected(result)
    }).catch(e => { if (active) fail(e) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [id, tab, page, fail])
  useEffect(() => {
    if (!selected || selected.indexStatus !== 'indexing') return
    let active = true
    const timer = setTimeout(() => {
      void getSnapshot(selected.id).then(result => { if (active) setSelected(result) }).catch(e => { if (active) fail(e) })
    }, 3000)
    return () => { active = false; clearTimeout(timer) }
  }, [selected, fail])
  const action = async (fn: () => Promise<void>) => {
    if (busy) return
    setBusy(true); setError(null)
    try { await fn() } catch (e) { fail(e) } finally { setBusy(false) }
  }

  return <div className="kn-page kn-page--wide">
    <div className="kn-head">
      <p className="kn-head__desc">将有价值的网页保存为可阅读、可核查的本地知识。</p>
      {!id && <div className="kn-tabs" role="tablist" aria-label="快照入口">
        <button type="button" role="tab" aria-selected={tab === 'capture'} onClick={() => setTab('capture')} className="kn-tab">从网页获取</button>
        <button type="button" role="tab" aria-selected={tab === 'library'} onClick={() => setTab('library')} className="kn-tab">快照库</button>
      </div>}
    </div>
    {error && <p role="alert" className="kn-alert">{error}</p>}
    {loading && <p role="status" className="kn-hint">正在读取快照…</p>}
    {!id && <div className={tab === 'capture' ? '' : 'hidden'}><BrowserWorkspace onSaved={() => { setPage(1) }} /></div>}
    {!id && tab === 'library' && !loading && <>
      {items.length ? <div className="kn-grid">{items.map(item => <article key={item.id} className="kn-snap">
        <div className="kn-snap__top"><span className="kn-snap__host">{new URL(item.url).hostname}</span><span className={`kn-badge${item.enabled ? ' kn-badge--ok' : ''}`}>{item.enabled ? '已入库' : '已停用'}</span></div>
        <a href={`#/snapshots/${item.id}`} className="kn-snap__title">{item.title}</a>
        <p className="kn-snap__desc">{item.summary || item.rationale}</p>
        <div className="kn-snap__meta"><span>{stamp(item.capturedAt)}</span><span>{INDEX_LABEL[item.indexStatus] || item.indexStatus}</span><span>{item.claims.length} 条蒸馏论点</span></div>
      </article>)}</div> : <div className="kn-empty"><strong>这里还没有快照</strong>输入一个网页地址，或让会话中的 Agent 在研究时保存有价值的网页。</div>}
      <div className="kn-pager">
        <button type="button" className="kn-btn kn-btn--sm" disabled={page === 1 || loading} onClick={() => setPage(p => p - 1)}>上一页</button>
        <span className="kn-hint">第 {page} 页</span>
        <button type="button" className="kn-btn kn-btn--sm" disabled={items.length < 20 || loading} onClick={() => setPage(p => p + 1)}>下一页</button>
      </div>
    </>}
    {id && !loading && selected && <>
      <a href="#/snapshots" className="kn-article__back">← 返回快照库</a>
      <section className="kn-card" style={{ marginTop: '.75rem' }}>
        <div className="kn-card__body kn-stack">
          {selected.indexStatus === 'reset' && <p className="kn-hint">知识索引已清空，网页原件仍保留。重新入库后可生成向量。</p>}
          <div className="kn-detail__head">
            <h2 className="kn-detail__title">{selected.title}</h2>
            <span className={`kn-badge${selected.enabled ? ' kn-badge--ok' : ''}`}>{selected.enabled ? INDEX_LABEL[selected.indexStatus] || selected.indexStatus : '已停用'}</span>
          </div>
          <SnapshotRename key={selected.id} id={selected.id} title={selected.title} disabled={busy} onError={fail} onRenamed={title => setSelected(previous => previous?.id === selected.id ? { ...previous, title } : previous)} />
          <a href={selected.url} target="_blank" rel="noreferrer" className="kn-detail__link">{readableUrl(selected.url)}</a>
          <dl className="kn-detail__facts">
            <div><dt>抓取时间</dt><dd>{stamp(selected.capturedAt)}</dd></div>
            <div><dt>作者 / 原发布时间</dt><dd>{selected.author || '未提供'} · {selected.publishedAt || '未提供'}</dd></div>
            <div><dt>蒸馏来源</dt><dd>{selected.model || '仅保存正文'}</dd></div>
          </dl>
          <p className="kn-detail__quote"><strong>保存理由：</strong>{selected.rationale}</p>
          {selected.warnings.map(w => <p key={w} className="kn-hint">{w}</p>)}
          <div className="kn-detail__actions">
            <button type="button" className="kn-btn kn-btn--sm" disabled={busy} onClick={() => void action(() => downloadSnapshot(selected.id))}>下载离线 HTML</button>
            <button type="button" className="kn-btn kn-btn--sm" disabled={busy} onClick={() => void action(async () => setSelected(await toggleSnapshot(selected.id, !selected.enabled)))}>{selected.enabled ? '停用检索' : '启用检索'}</button>
            {selected.enabled && <button type="button" className="kn-btn kn-btn--sm" disabled={busy || selected.indexStatus === 'indexing' || selected.indexStatus === 'reset'} onClick={() => void action(async () => setSelected(await indexSnapshot(selected.id)))}>重新生成向量</button>}
            {selected.docId && <a href={`#/analyze?doc=${selected.docId}`} className="kn-btn kn-btn--sm">审核图谱提取</a>}
            <button type="button" className="kn-btn kn-btn--sm kn-btn--danger" style={{ marginLeft: 'auto' }} disabled={busy} onClick={() => {
              if (!window.confirm('删除此快照及其检索分块、向量和来源关系？')) return
              void action(async () => { await deleteSnapshot(selected.id); window.location.hash = '#/snapshots' })
            }}>删除快照</button>
          </div>
          <p className="kn-detail__hash">SHA-256 · {selected.contentHash}</p>
        </div>
      </section>
      <div className="kn-detail__section"><SnapshotReader title={selected.title} sourceHtml={selected.sourceHtml} summary={selected.summary} claims={selected.claims} /></div>
      <div className="kn-detail__section"><SnapshotImageExport key={selected.id} snapshot={selected} onError={fail} /></div>
    </>}
  </div>
}
