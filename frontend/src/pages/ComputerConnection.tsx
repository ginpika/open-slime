import { useState } from 'react'
import { requestJson } from '../api/client'
import { apiFetch } from '../api/desktop'
import { terminalError } from '../api/terminal'
import FloatWindow from '../components/FloatWindow'
import { btnOutline, btnPrimary, btnQuiet } from '../components/ui'

type Status = { desktopLocal: boolean; chromeConnected: boolean; windowCapture: boolean; isolatedChrome: boolean }

export default function ComputerConnection() {
  const [open, setOpen] = useState(false)
  const [status, setStatus] = useState<Status | null>(null)
  const [connection, setConnection] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function load() {
    setError('')
    try { setStatus(await requestJson<Status>('/api/computer')) } catch (e) { setError(terminalError(e)) }
  }
  async function pair() {
    setBusy(true); setError('')
    try {
      const result = await requestJson<{ connectionUrl: string }>('/api/computer/chrome/pair', { method: 'POST' })
      setConnection(result.connectionUrl)
      await load()
    } catch (e) { setError(terminalError(e)) } finally { setBusy(false) }
  }
  async function disconnect() {
    setBusy(true); setError('')
    try {
      await requestJson('/api/computer/chrome/connection', { method: 'DELETE' })
      setConnection(''); await load()
    } catch (e) { setError(terminalError(e)) } finally { setBusy(false) }
  }
  async function downloadExtension() {
    setBusy(true); setError('')
    try {
      const response = await apiFetch('/api/computer/chrome/extension', { credentials: 'same-origin' })
      if (!response.ok) throw new Error('扩展下载失败，请重新组装后端运行时后重试')
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a'); link.href = url; link.download = 'slime-chrome-extension.zip'; link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) { setError(terminalError(e)) } finally { setBusy(false) }
  }
  return <>
    <button type="button" className={btnQuiet('rounded-sm')} onClick={() => { setOpen(true); void load() }}>Chrome</button>
    {open && <FloatWindow title="电脑与 Chrome" onClose={() => { setOpen(false); setConnection('') }} width={560}>
      <div className="space-y-4 text-sm leading-relaxed">
        <p>对话中的 Chrome 工具可以在独立后台标签页打开网页、读取内容、填写表单和点击按钮。</p>
        {status && <>
          <p className="text-muted">独立 Chrome：始终后台运行，与个人浏览器隔离。{status.desktopLocal ? '本机模式还可以通过扩展沿用个人 Chrome 的登录状态。' : '当前为 Web / 远程模式，仅开放隔离 Chrome，不访问本机桌面与个人浏览器。'}</p>
          {status.desktopLocal && <>
            <p>个人 Chrome：{status.chromeConnected ? '已连接' : '未连接'}。Cookie 与登录凭证留在浏览器里；工具不操作你的原有标签页，也不会切换当前标签页。</p>
            <p className="text-muted">首次使用：下载并解压扩展，在 Chrome 扩展管理页开启开发者模式，选择「加载已解压的扩展程序」。再将下面生成的连接地址粘贴到扩展中。连接地址只用于本机配对。</p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={btnOutline('rounded-sm')} disabled={busy} onClick={() => void downloadExtension()}>下载 Chrome 扩展</button>
              <button type="button" className={btnPrimary('rounded-sm')} disabled={busy} onClick={() => void pair()}>{status.chromeConnected ? '重新配对' : '生成连接地址'}</button>
              <button type="button" className={btnOutline('rounded-sm')} disabled={busy} onClick={() => void load()}>刷新状态</button>
              {status.chromeConnected && <button type="button" className={btnOutline('rounded-sm')} disabled={busy} onClick={() => void disconnect()}>断开连接</button>}
            </div>
            {connection && <label className="block">连接地址（十分钟内有效）
              <textarea readOnly value={connection} onFocus={e => e.target.select()} className="mt-2 h-24 w-full rounded-md border border-line bg-panel p-3 font-mono text-xs" />
            </label>}
            <p className="text-muted">指定窗口截图：{status.windowCapture ? '已就绪，可以在对话中指定应用窗口。' : '需要更新后的 Windows / macOS 桌面端本机模式。'}系统可能要求屏幕录制权限；截图不会激活或恢复窗口。</p>
          </>}
        </>}
        {error && <p role="alert" className="text-error">{error}</p>}
      </div>
    </FloatWindow>}
  </>
}
