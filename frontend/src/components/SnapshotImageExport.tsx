import { AppIcon } from './AppIcon'
import { useEffect, useMemo, useRef, useState } from 'react'
import { loginUrlOf } from '../api/client'
import {
  downloadSnapshotImage, downloadSnapshotImages, getSnapshotImages, webError,
  type SnapshotImage, type SnapshotImageCatalog, type WebSnapshot,
} from '../api/webSnapshots'
import { WEB_BUTTON, WEB_PRIMARY } from './BrowserWorkspace'

const fileSize = (bytes: number) => bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
const locationLabels = (image: SnapshotImage) => [...new Set(image.occurrences.map(item => [item.section, item.version].filter(Boolean).join(' · ')))]

function DownloadIcon() {
  return <AppIcon name="download" width="16" height="16" />
}

export default function SnapshotImageExport({ snapshot, onError }: { snapshot: WebSnapshot; onError: (error: unknown) => void }) {
  const [catalog, setCatalog] = useState<SnapshotImageCatalog | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [filter, setFilter] = useState<'all' | 'gif' | 'static'>('all')
  const [limit, setLimit] = useState(12)
  const [downloading, setDownloading] = useState('')
  const working = useRef(false)
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => {
    let active = true
    void getSnapshotImages(snapshot.id).then(result => { if (active) setCatalog(result) }).catch(e => {
      if (!active) return
      if (loginUrlOf(e)) onError(e)
      else setError(webError(e))
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [snapshot.id, retry, onError])

  // Reuse the saved images already delivered with the detail; no source-site requests or extra thumbnail downloads.
  const sources = useMemo(() => {
    if (!catalog?.imageCount) return []
    const doc = new DOMParser().parseFromString(snapshot.sourceHtml, 'text/html')
    return [...doc.images].map(image => {
      const src = image.getAttribute('src') || ''
      return /^data:image\/(?:png|jpeg|webp|gif);base64,/.test(src) ? src : ''
    })
  }, [catalog, snapshot.sourceHtml])
  const images = catalog?.images ?? []
  const gifs = images.filter(image => image.mimeType === 'image/gif').length
  const filtered = images.filter(image => filter === 'all' || (filter === 'gif' ? image.mimeType === 'image/gif' : image.mimeType !== 'image/gif'))
  const download = async (key: string, action: () => Promise<void>) => {
    if (working.current) return
    working.current = true; setDownloading(key); setError(null)
    try { await action() } catch (e) {
      if (mounted.current) {
        if (loginUrlOf(e)) onError(e)
        else setError(webError(e))
      }
    } finally { working.current = false; if (mounted.current) setDownloading('') }
  }

  return <section aria-labelledby="snapshot-images-title" className="overflow-hidden rounded-xl border border-line bg-bg">
    <div className="flex flex-wrap items-start justify-between gap-4 border-b border-line p-5 sm:p-6">
      <div className="flex min-w-0 items-start gap-3">
        <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-accent/10 text-accent"><AppIcon name="image" width="22" height="22" /></div>
        <div><h2 id="snapshot-images-title" className="text-lg font-semibold text-fg">图片资源</h2><p className="mt-1 text-sm leading-relaxed text-muted">提取快照中已保存的图片，包含 Gallery 和所有版本。</p></div>
      </div>
      <button type="button" className={`${WEB_PRIMARY} w-full sm:w-auto`} disabled={loading || !catalog?.imageCount || !!downloading} onClick={() => void download('zip', () => downloadSnapshotImages(snapshot.id))}>
        <DownloadIcon />{downloading === 'zip' ? '正在打包下载…' : '下载全部图片 ZIP'}
      </button>
    </div>
    <div className="space-y-5 p-5 sm:p-6">
      {error && <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-400/30 bg-red-400/5 p-3 text-sm text-fg"><p>{error}</p>{!catalog && <button type="button" className={WEB_BUTTON} disabled={loading} onClick={() => { setLoading(true); setError(null); setRetry(value => value + 1) }}>重新读取</button>}</div>}
      {loading ? <p role="status" className="py-8 text-center text-sm text-muted">正在整理快照中的图片…</p> : catalog && <>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[['图片位置', catalog.totalOccurrences.toLocaleString()], ['独立图片', catalog.imageCount.toLocaleString()], ['GIF 动画', gifs.toLocaleString()], ['导出图片大小', fileSize(catalog.totalBytes)]].map(([label, value]) => <div key={label} className="rounded-lg bg-fg/5 px-4 py-3"><dt className="text-xs text-faint">{label}</dt><dd className="mt-1 text-xl font-semibold tabular-nums text-fg">{value}</dd></div>)}
        </dl>
        {catalog.imageCount > 0 ? <>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex gap-1 rounded-lg bg-fg/5 p-1" role="group" aria-label="图片类型筛选">
              {([['all', '全部', catalog.imageCount], ['gif', 'GIF', gifs], ['static', '静态图', catalog.imageCount - gifs]] as const).map(([value, label, count]) => <button key={value} type="button" aria-pressed={filter === value} onClick={() => { setFilter(value); setLimit(12) }} className={`rounded-md px-3 py-1.5 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 ${filter === value ? 'bg-bg text-fg shadow-sm' : 'text-muted hover:text-fg'}`}>{label}<span className="ml-1.5 text-xs tabular-nums text-faint">{count}</span></button>)}
            </div>
            <p className="text-xs text-faint">已合并 {catalog.duplicateCount} 处重复图片</p>
          </div>
          {filtered.length ? <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">{filtered.slice(0, limit).map(image => {
            const locations = locationLabels(image)
            const src = sources[image.sourceIndex] || ''
            const format = image.mimeType.slice('image/'.length).toUpperCase()
            return <article key={image.id} aria-label={image.filename} className="group min-w-0 overflow-hidden rounded-lg border border-line transition hover:border-accent/40">
              <div className="relative flex aspect-[4/3] items-center justify-center bg-fg/5 p-3">
                {src ? <img src={src} alt={image.filename} loading="lazy" decoding="async" className="h-full w-full object-contain" /> : <span className="text-xs text-faint">图片无法预览</span>}
                <span className={`absolute left-2 top-2 rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${format === 'GIF' ? 'bg-accent text-accent-fg' : 'border border-line bg-bg text-muted'}`}>{format}</span>
              </div>
              <div className="space-y-2 p-3">
                <p className="truncate text-sm font-medium text-fg" title={image.filename}>{image.filename}</p>
                <p className="truncate text-[11px] text-faint" title={locations.join(' / ')}>{locations.join(' / ') || '正文'}{image.occurrences.length > 1 ? ` · ${image.occurrences.length} 处` : ''}</p>
                <div className="flex flex-wrap items-center justify-between gap-1 border-t border-line pt-2"><span className="text-xs tabular-nums text-muted">{fileSize(image.bytes)}</span><button type="button" aria-label={`下载 ${image.filename}`} disabled={!!downloading || !src} onClick={() => void download(image.id, () => downloadSnapshotImage(image, src))} className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-accent transition hover:bg-accent/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 disabled:opacity-50"><DownloadIcon />{downloading === image.id ? '下载中…' : '下载'}</button></div>
              </div>
            </article>
          })}</div> : <p className="rounded-lg border border-dashed border-line py-8 text-center text-sm text-muted">这个快照中没有{filter === 'gif' ? ' GIF 动画' : '静态图片'}。</p>}
          {filtered.length > limit && <div className="flex flex-wrap items-center justify-center gap-3"><button type="button" className={WEB_BUTTON} onClick={() => setLimit(value => value + 12)}>显示更多图片</button><span className="text-xs text-faint">已显示 {Math.min(limit, filtered.length)} / {filtered.length} 张</span></div>}
          <p className="border-t border-line pt-4 text-xs leading-relaxed text-faint">ZIP 包含图片文件和来源清单（manifest.json）。重复图片按内容合并，GIF 保留动画；文件名依据快照中的图片说明生成。筛选只影响此处展示，ZIP 始终包含全部图片。</p>
        </> : <div className="rounded-lg border border-dashed border-line px-5 py-10 text-center"><p className="text-sm font-medium text-fg">这个快照没有保存图片</p><p className="mt-2 text-sm text-muted">获取快照时勾选「图片」，即可在这里提取和下载。</p></div>}
        {catalog.skippedCount > 0 && <p className="text-xs text-muted">另有 {catalog.skippedCount} 处图片未保存完整或格式不支持，未加入导出。</p>}
      </>}
    </div>
  </section>
}
