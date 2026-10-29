import { useEffect, useRef, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { ApiError } from '../api/client'
import { terminalError, type TerminalView } from '../api/terminal'
import { useThemeState } from '../theme/store'
import { Button } from './ui'
import { terminalTheme } from './terminal/terminalTheme'
import './terminal/session.css'

export default function TerminalSession({ scope, terminal, active, onExit, connect: openConnection, shellName = 'Shell' }: { scope: string; terminal: TerminalView; active: boolean; onExit: (id: string, error?: string) => void; connect: (scope: string, id: string, after: number) => Promise<WebSocket>; shellName?: string }) {
  const host = useRef<HTMLDivElement>(null), instance = useRef<Terminal | null>(null)
  const { vars } = useThemeState(), currentTheme = useRef(vars)
  const [error, setError] = useState(''), [status, setStatus] = useState(`正在连接 ${shellName}…`), [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!host.current) return
    let stopped = false, exited = false, fatal = false, after = 0, attempts = 0, reconnectTimer = 0, resizeTimer = 0
    let socket: WebSocket | null = null, delivery = Promise.resolve(), output: { offset: number; reset: boolean } | null = null
    const term = new Terminal({ cursorBlink: true, disableStdin: true, fontSize: 14, lineHeight: 1.3, fontFamily: 'Consolas, "Noto Sans Mono", monospace', scrollback: 5000, minimumContrastRatio: 4.5, theme: terminalTheme(currentTheme.current) })
    const fit = new FitAddon(); term.loadAddon(fit); term.open(host.current); instance.current = term
    term.textarea?.setAttribute('aria-label', `${terminal.title}命令输入`)
    setError('')
    const send = (value: unknown) => {
      if (socket?.readyState !== WebSocket.OPEN) return false
      if (socket.bufferedAmount > 262144) throw new Error('终端发送缓冲已满，请等待连接恢复后继续输入')
      socket.send(JSON.stringify(value)); return true
    }
    const fail = (e: unknown) => {
      if (stopped) return
      fatal = true; term.options.disableStdin = true; setError(terminalError(e)); setStatus('连接已断开'); socket?.close()
    }
    const resize = () => { if (!stopped) try { send({ type: 'resize', cols: Math.min(500, term.cols), rows: Math.min(200, term.rows) }) } catch (e) { fail(e) } }
    const connect = async () => {
      if (stopped || exited || fatal) return
      setStatus(attempts ? '正在重新连接…' : `正在连接 ${shellName}…`)
      try {
        const connection = await openConnection(scope, terminal.id, after)
        if (stopped || exited) { connection.close(); return }
        socket = connection; connection.binaryType = 'arraybuffer'; output = null
        connection.onopen = () => { if (stopped) { connection.close(); return }; term.options.disableStdin = false; setError(''); setStatus(`${shellName} · Ctrl+C 中断命令`); resize() }
        connection.onmessage = event => {
          delivery = delivery.then(async () => {
            if (stopped || exited) return
            if (typeof event.data === 'string') {
              const message = JSON.parse(event.data) as { type: string; offset?: number; reset?: boolean; error?: string; message?: string }
              if (message.type === 'output') {
                if (!Number.isSafeInteger(message.offset) || message.offset! < 0 || output) throw new Error('终端输出游标无效')
                output = { offset: message.offset!, reset: !!message.reset }
              } else if (message.type === 'exit') { exited = true; term.options.disableStdin = true; connection.close(); onExit(terminal.id, message.error) }
              else if (message.type === 'error') fail(new Error(message.message || '终端连接失败'))
              else throw new Error('未知终端消息')
            } else {
              if (!output || !(event.data instanceof ArrayBuffer)) throw new Error('终端输出帧无效')
              const frame = output; output = null
              if (frame.reset) { term.reset(); term.writeln('较早的输出已超出缓存，显示最近输出。') }
              await new Promise<void>(resolve => term.write(new Uint8Array(event.data), resolve))
              after = frame.offset; attempts = 0
              if (!stopped && connection.readyState === WebSocket.OPEN) connection.send(JSON.stringify({ type: 'ack', offset: after }))
            }
          }).catch(fail)
        }
        connection.onclose = () => {
          if (stopped || exited || fatal) return
          term.options.disableStdin = true
          if (++attempts > 3) { setStatus('连接已断开'); setError('终端连接中断，请重新连接。未确认的输入不会重放。'); return }
          setStatus('正在重新连接…')
          reconnectTimer = window.setTimeout(() => { void delivery.then(connect).catch(fail) }, 500 * 2 ** (attempts - 1))
        }
        connection.onerror = () => { /* onclose handles network recovery without replaying input. */ }
      } catch (e) {
        if (stopped) return
        if (e instanceof ApiError && e.status === 404) { exited = true; onExit(terminal.id); return }
        fail(e)
      }
    }
    const subscription = term.onData(text => {
      try {
        for (let start = 0; start < text.length;) {
          let end = Math.min(text.length, start + 4000)
          if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--
          if (!send({ type: 'input', data: text.slice(start, end) })) throw new Error('连接已断开，请重新连接后继续输入')
          start = end
        }
      } catch (e) { fail(e) }
    })
    const geometry = term.onResize(() => { window.clearTimeout(resizeTimer); resizeTimer = window.setTimeout(resize, 100) })
    const observer = new ResizeObserver(() => { if (host.current && host.current.clientWidth > 0 && host.current.clientHeight > 0) fit.fit() })
    observer.observe(host.current); void connect()
    return () => { stopped = true; window.clearTimeout(reconnectTimer); window.clearTimeout(resizeTimer); socket?.close(); subscription.dispose(); geometry.dispose(); observer.disconnect(); instance.current = null; term.dispose() }
  }, [scope, terminal.id, terminal.title, retry, onExit, openConnection, shellName])
  useEffect(() => { currentTheme.current = vars; if (instance.current) instance.current.options.theme = terminalTheme(vars) }, [vars])
  useEffect(() => { if (active && document.activeElement?.getAttribute('role') !== 'tab') instance.current?.focus() }, [active, retry])
  return <div className="coding-terminal-session"><div className="coding-terminal-emulator" ref={host} /><div className="coding-terminal-state"><span role="status">{status}</span>{error && <><span role="alert">{error}</span><Button size="sm" variant="quiet" onClick={() => setRetry(value => value + 1)}>重新连接</Button></>}</div></div>
}
