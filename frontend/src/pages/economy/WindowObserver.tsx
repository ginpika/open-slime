import { useCallback, useEffect, useRef, useState } from 'react'
import { economyCapture, economyWindows, type ObservedWindow, type ResearchSource, type WindowFrame } from '../../api/economy'
import { isDesktopShell } from '../../api/desktop'
import { webError } from '../../api/webSnapshots'
import { AppIcon } from '../../components/AppIcon'
import { Select } from '../../components/Select'

const WINDOW_SESSION = 'economy.observedWindow'
type WindowSession = { key: string; resume: boolean }
const windowKey = (w: ObservedWindow) => `${w.id}:${w.pid}`
function readSession(): WindowSession {
  try {
    const saved = JSON.parse(sessionStorage.getItem(WINDOW_SESSION) || 'null')
    if (saved && typeof saved.key === 'string' && /^\d+:\d+$/.test(saved.key)) return { key: saved.key, resume: saved.resume === true }
  } catch { /* A missing or inaccessible preference must not block capture. */ }
  return { key: '', resume: false }
}
function saveSession(key: string, resume: boolean) {
  try { sessionStorage.setItem(WINDOW_SESSION, JSON.stringify({ key, resume })) } catch { /* Capture also works without storage. */ }
}

export default function WindowObserver({ onSource }: { onSource: (source: ResearchSource | null) => void }) {
  const desktop = isDesktopShell()
  const [windows, setWindows] = useState<ObservedWindow[]>([])
  const [selected, setSelected] = useState(() => desktop ? readSession().key : '')
  const selectedRef = useRef(selected); selectedRef.current = selected
  const [frame, setFrame] = useState<WindowFrame | null>(null)
  const [status, setStatus] = useState<'idle' | 'starting' | 'watching'>('idle')
  const [listing, setListing] = useState(false)
  const [error, setError] = useState('')
  const alive = useRef(false)
  const generation = useRef(0)
  const listGeneration = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const stream = useRef<MediaStream | null>(null)
  const video = useRef<HTMLVideoElement | null>(null)
  const onSourceRef = useRef(onSource); onSourceRef.current = onSource

  // Navigation releases resources but keeps the native identity/resume intent.
  // Frames and browser permission streams are never restored from storage.
  const release = useCallback(() => {
    generation.current++; clearTimeout(timer.current)
    stream.current?.getTracks().forEach(track => { track.onended = null; track.stop() })
    stream.current = null
    if (video.current) { video.current.pause(); video.current.srcObject = null; video.current = null }
  }, [])
  const stop = useCallback(() => {
    release()
    if (desktop) saveSession(selectedRef.current, false)
    if (alive.current) { setStatus('idle'); setFrame(null); onSourceRef.current(null) }
  }, [desktop, release])

  const start = useCallback(async (windowInfo?: ObservedWindow) => {
    stop(); setError(''); setStatus('starting')
    const token = generation.current
    const current = () => alive.current && token === generation.current
    let capture: () => Promise<WindowFrame>
    try {
      if (desktop) {
        if (!windowInfo) throw new Error('请先选择窗口')
        capture = () => economyCapture(windowInfo.id, windowInfo.pid)
      } else {
        if (!navigator.mediaDevices?.getDisplayMedia) throw new Error('当前环境不支持共享窗口，请使用 Windows/macOS 桌面版，或在 HTTPS/localhost 的 Chrome、Edge 中打开')
        const next = await navigator.mediaDevices.getDisplayMedia({ video: { displaySurface: 'window' }, audio: false })
        if (!current()) { next.getTracks().forEach(t => t.stop()); return }
        stream.current = next
        const track = next.getVideoTracks()[0]
        if (!track || track.getSettings().displaySurface !== 'window') throw new Error('请在共享选择器中选择单个窗口')
        track.onended = () => { if (current()) stop() }
        const player = document.createElement('video'); player.muted = true; player.srcObject = next; video.current = player
        await player.play()
        if (!current()) return
        capture = async () => {
          if (track.readyState !== 'live' || !player.videoWidth || !player.videoHeight) throw new Error('窗口画面不可用，请重新选择')
          const scale = Math.min(1, 1600 / player.videoWidth, 1000 / player.videoHeight)
          const canvas = document.createElement('canvas'); canvas.width = Math.round(player.videoWidth * scale); canvas.height = Math.round(player.videoHeight * scale)
          canvas.getContext('2d')!.drawImage(player, 0, 0, canvas.width, canvas.height)
          return { image: canvas.toDataURL('image/jpeg', .8), title: track.label || '共享窗口', capturedAt: Date.now() }
        }
      }
      const poll = async () => {
        if (!current()) return
        try {
          const next = await capture()
          if (!current()) return
          setFrame(next); setStatus('watching')
          if (desktop && windowInfo) saveSession(windowKey(windowInfo), true)
          onSourceRef.current({ image: next.image, title: next.title, capturedAt: next.capturedAt })
          timer.current = setTimeout(() => void poll(), 3000)
        } catch (e) { if (current()) { stop(); setError(webError(e)) } }
      }
      if (current()) await poll()
    } catch (e) { if (current()) { stop(); setError(webError(e)) } }
  }, [desktop, stop])

  const refresh = useCallback(async (restore = false) => {
    const request = ++listGeneration.current
    const token = generation.current
    setListing(true); setError('')
    try {
      const available = await economyWindows()
      if (!alive.current || request !== listGeneration.current) return
      setWindows(available)
      const saved = restore ? readSession() : { key: selectedRef.current, resume: false }
      const match = available.find(w => windowKey(w) === saved.key)
      // A delayed restore must not override a later Stop or source change.
      if (token !== generation.current) return
      if (saved.key && !match) {
        stop(); selectedRef.current = ''; setSelected(''); saveSession('', false)
        setError('原观察窗口已关闭或最小化，请重新选择一个已打开的窗口。')
      } else if (match) {
        selectedRef.current = saved.key; setSelected(saved.key)
        if (restore && saved.resume) void start(match)
      }
    } catch (e) { if (alive.current && request === listGeneration.current) setError(webError(e)) }
    finally { if (alive.current && request === listGeneration.current) setListing(false) }
  }, [start, stop])

  useEffect(() => {
    alive.current = true
    if (desktop) void refresh(true)
    return () => { alive.current = false; listGeneration.current++; release(); onSourceRef.current(null) }
  }, [desktop, refresh, release])

  const starting = status === 'starting'
  return <section aria-label="窗口观察" className="eco-observer">
    <div className="eco-toolbar">
      {desktop && <><button onClick={() => void refresh()} disabled={listing || starting}>{listing ? '正在列出窗口…' : '列出已打开窗口'}</button><Select className="eco-select" menuClassName="eco-select-menu" label="选择观察窗口" value={selected} disabled={listing || starting} searchable onChange={value => {
        stop(); selectedRef.current = value; setSelected(value); saveSession(value, false); setError('')
      }} options={[{value:'',label:'请选择一个窗口'},...windows.map(w => ({value:windowKey(w),label:`${w.app} · ${w.title}`}))]}/></>}
      <button disabled={starting || (desktop && (listing || !selected || status === 'watching'))} onClick={() => void start(windows.find(w => windowKey(w) === selected))}>{starting ? '正在获取画面…' : desktop ? '开始观察' : status === 'watching' ? '更换共享窗口' : '选择共享窗口'}</button>
      <button disabled={status === 'idle'} onClick={stop}>停止观察</button>
    </div>
    <details className="eco-source-help"><summary>窗口观察说明</summary><p className="eco-help">每 3 秒更新预览，点击分析时保存当前截图。窗口需保持打开且未最小化；macOS 首次使用需开启系统屏幕录制权限。{desktop ? '重进页面会恢复原窗口的实时预览；停止观察后不再自动恢复。' : '离开页面会结束共享，重进后请重新选择共享窗口。'}</p></details>
    {error && <p role="alert" className="eco-error">{error}</p>}
    {frame ? <><p role="status" className="eco-meta">观察中 · {frame.title} · {new Date(frame.capturedAt).toLocaleTimeString()}</p><img className="eco-window-frame" src={frame.image} alt={`窗口画面：${frame.title}`} /></> : <div className="eco-empty"><AppIcon name="workspace" size={32}/><h3>选择一个行情窗口</h3><p>在行情软件中切换周期，这里同步预览。{desktop ? '选择已打开的窗口后，点击开始观察。' : '点击选择共享窗口，在系统选择器中选择单个窗口。'}</p></div>}
  </section>
}
