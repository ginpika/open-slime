import { useCallback, useEffect, useState } from 'react'
import { usePageKit } from '../kit'
import { loginUrlOf } from '../api/client'
import { readableUrl } from '../api/readableUrl'
import {
  deleteSnapshot, downloadSnapshot, getSnapshot, indexSnapshot, INDEX_LABEL, listSnapshots, toggleSnapshot, webError,
  type WebSnapshot,
} from '../api/webSnapshots'
import BrowserWorkspace, { WEB_BUTTON } from '../components/BrowserWorkspace'
import SnapshotReader from '../components/SnapshotReader'
import SnapshotImageExport from '../components/SnapshotImageExport'
import SnapshotRename from '../components/SnapshotRename'

const stamp = (time: number) => new Date(time).toLocaleString()

export default function Snapshots({ id }: { id?: string }) {
  const kit = usePageKit()
  const [tab, setTab] = useState<'capture' | 'library'>('capture')
  const [items, setItems] = useState<WebSnapshot[]>([])
  const [selected, setSelected] = useState<WebSnapshot | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
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

  return <kit.Page title={id ? '网页快照' : '快照'} en="SNAPSHOTS" meta={<span className="text-sm text-muted">将有价值的网页保存为可阅读、可核查的本地知识</span>}>
    <div className="mx-auto w-full max-w-6xl space-y-5">
      {!id && <div className="flex gap-2 rounded-xl border border-line bg-bg p-1.5" role="tablist" aria-label="快照入口">
        <button type="button" role="tab" aria-selected={tab === 'capture'} onClick={() => setTab('capture')} className={`flex-1 rounded-lg px-4 py-2.5 text-sm font-medium transition ${tab === 'capture' ? 'bg-accent text-accent-fg shadow-sm' : 'text-muted hover:bg-fg/5'}`}>从网页获取</button>
        <button type="button" role="tab" aria-selected={tab === 'library'} onClick={() => setTab('library')} className={`flex-1 rounded-lg px-4 py-2.5 text-sm font-medium transition ${tab === 'library' ? 'bg-accent text-accent-fg shadow-sm' : 'text-muted hover:bg-fg/5'}`}>快照库</button>
      </div>}
      {error && <p role="alert" className="rounded-xl border border-red-400/30 bg-red-400/5 p-4 text-sm text-fg">{error}</p>}
      {loading && <p role="status" className="py-12 text-center text-sm text-muted">正在读取快照…</p>}
      {!id && <div className={tab === 'capture' ? '' : 'hidden'}><BrowserWorkspace onSaved={() => { setPage(1) }} /></div>}
      {!id && tab === 'library' && !loading && <>
        {items.length ? <div className="grid gap-4 sm:grid-cols-2">{items.map(item => <article key={item.id} className="rounded-xl border border-line bg-bg p-5 shadow-sm transition hover:border-accent/50">
          <div className="mb-3 flex items-center justify-between gap-2 text-xs text-faint"><span className="truncate">{new URL(item.url).hostname}</span><span className={`shrink-0 rounded-full px-2 py-1 ${item.enabled ? 'bg-accent/10 text-accent' : 'bg-fg/5 text-muted'}`}>{item.enabled ? '已入库' : '已停用'}</span></div>
          <a href={`#/snapshots/${item.id}`} className="text-lg font-semibold leading-snug text-fg hover:text-accent">{item.title}</a>
          <p className="mt-2 line-clamp-3 text-sm leading-relaxed text-muted">{item.summary || item.rationale}</p>
          <div className="mt-4 flex flex-wrap gap-x-3 gap-y-1 border-t border-line pt-3 text-xs text-faint"><span>{stamp(item.capturedAt)}</span><span>{INDEX_LABEL[item.indexStatus] || item.indexStatus}</span><span>{item.claims.length} 条蒸馏论点</span></div>
        </article>)}</div> : <div className="rounded-xl border border-dashed border-line p-14 text-center"><p className="text-base font-medium text-fg">这里还没有快照</p><p className="mt-2 text-sm text-muted">输入一个网页地址，或让会话中的 Agent 在研究时保存有价值的网页。</p></div>}
        <div className="flex items-center justify-center gap-4"><button type="button" className={WEB_BUTTON} disabled={page === 1 || loading} onClick={() => setPage(p => p - 1)}>上一页</button><span className="text-sm text-muted">第 {page} 页</span><button type="button" className={WEB_BUTTON} disabled={items.length < 20 || loading} onClick={() => setPage(p => p + 1)}>下一页</button></div>
      </>}
      {id && !loading && selected && <>
        <a href="#/snapshots" className="inline-flex text-sm text-muted hover:text-accent">← 返回快照</a>
        <section className="space-y-4 rounded-xl border border-line bg-bg p-5">
          <div className="flex flex-wrap items-start justify-between gap-3"><h2 className="min-w-0 flex-1 break-words text-2xl font-semibold text-fg">{selected.title}</h2><span className="rounded-full bg-accent/10 px-3 py-1 text-xs text-accent">{selected.enabled ? INDEX_LABEL[selected.indexStatus] || selected.indexStatus : '已停用'}</span></div>
          <SnapshotRename key={selected.id} id={selected.id} title={selected.title} disabled={busy} onError={fail} onRenamed={title => setSelected(previous => previous?.id === selected.id ? { ...previous, title } : previous)} />
          <a href={selected.url} target="_blank" rel="noreferrer" className="block break-all text-sm text-accent hover:underline">{readableUrl(selected.url)}</a>
          <dl className="grid gap-3 text-sm sm:grid-cols-3"><div><dt className="text-xs text-faint">抓取时间</dt><dd className="mt-1 text-fg">{stamp(selected.capturedAt)}</dd></div><div><dt className="text-xs text-faint">作者 / 原发布时间</dt><dd className="mt-1 text-fg">{selected.author || '未提供'} · {selected.publishedAt || '未提供'}</dd></div><div><dt className="text-xs text-faint">蒸馏来源</dt><dd className="mt-1 text-fg">{selected.model || '仅保存正文'}</dd></div></dl>
          <div className="rounded-lg bg-fg/5 p-3 text-sm leading-relaxed text-muted"><span className="font-medium text-fg">保存理由：</span>{selected.rationale}</div>
          {selected.warnings.map(w => <p key={w} className="text-sm text-muted">{w}</p>)}
          <div className="flex flex-wrap gap-2">
            <button type="button" className={WEB_BUTTON} disabled={busy} onClick={() => void action(() => downloadSnapshot(selected.id))}>下载离线 HTML</button>
            <button type="button" className={WEB_BUTTON} disabled={busy} onClick={() => void action(async () => setSelected(await toggleSnapshot(selected.id, !selected.enabled)))}>{selected.enabled ? '停用检索' : '启用检索'}</button>
            {selected.enabled && <button type="button" className={WEB_BUTTON} disabled={busy || selected.indexStatus === 'indexing'} onClick={() => void action(async () => setSelected(await indexSnapshot(selected.id)))}>重新生成向量</button>}
            {selected.docId && <a href={`#/analyze?doc=${selected.docId}`} className={WEB_BUTTON}>审核图谱提取</a>}
            <button type="button" className={`${WEB_BUTTON} text-red-500 sm:ml-auto`} disabled={busy} onClick={() => {
              if (!window.confirm('删除此快照及其检索分块、向量和来源关系？')) return
              void action(async () => { await deleteSnapshot(selected.id); window.location.hash = '#/snapshots' })
            }}>删除快照</button>
          </div>
          <p className="break-all font-mono text-[11px] text-faint">SHA-256 · {selected.contentHash}</p>
        </section>
        <SnapshotReader title={selected.title} sourceHtml={selected.sourceHtml} summary={selected.summary} claims={selected.claims} />
        <SnapshotImageExport key={selected.id} snapshot={selected} onError={fail} />
      </>}
    </div>
  </kit.Page>
}
