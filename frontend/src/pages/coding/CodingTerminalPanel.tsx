import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { codingError, type CodingEvent, type CodingProject } from '../../api/coding'
import { createCodingTerminal, deleteCodingTerminal, listCodingTerminals, type CodingTerminal } from '../../api/codingTerminal'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'
import { eventData } from './timeline'
import CodingTerminalSession from './CodingTerminalSession'

type Props = { project: CodingProject; events: CodingEvent[]; hasRun: boolean; embedded: boolean; visible: boolean; open: boolean; onOpenChange: (open: boolean) => void; tabsHost: HTMLElement | null; onActivate: () => void; onEmpty: () => void }
const compactTitle = (terminal: CodingTerminal): CodingTerminal => ({ ...terminal, title: terminal.title.replace(/^终端\s*(\d+)$/, 'T$1') })

export default function CodingTerminalPanel({ project, events, hasRun, embedded, visible, open, onOpenChange, tabsHost, onActivate, onEmpty }: Props) {
  const [terminals, setTerminals] = useState<CodingTerminal[]>([]), [active, setActive] = useState(() => sessionStorage.getItem(`minis.coding.terminal.${project.id}`) || '')
  const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false), [error, setError] = useState(''), [revision, setRevision] = useState(0)
  const id = useId(), alive = useRef(true)
  const panel = useRef<HTMLElement>(null)
  const controls = useRef<HTMLDivElement>(null)
  const current = useRef({ terminals, active, onEmpty })
  useLayoutEffect(() => { current.current = { terminals, active, onEmpty } }, [terminals, active, onEmpty])
  const forget = useCallback((terminalId: string, reason?: string) => {
    const previous = current.current, index = previous.terminals.findIndex(item => item.id === terminalId)
    const next = previous.terminals.filter(item => item.id !== terminalId)
    const restore = previous.active === terminalId && (panel.current?.contains(document.activeElement) || controls.current?.contains(document.activeElement))
    current.current = { ...previous, terminals: next, active: previous.active === terminalId ? next[Math.max(0, index - 1)]?.id || '' : previous.active }
    setTerminals(next); setActive(current.current.active)
    if (previous.active === terminalId && !current.current.active) previous.onEmpty()
    if (reason) setError(reason)
    if (restore) window.requestAnimationFrame(() => (controls.current?.querySelector<HTMLButtonElement>('[role="tab"][aria-selected="true"]') ?? controls.current?.querySelector<HTMLButtonElement>('[aria-label="新建终端"]'))?.focus())
  }, [])
  const exited = useCallback((terminalId: string, reason?: string) => {
    forget(terminalId, reason)
    void deleteCodingTerminal(project.id, terminalId).catch(e => { if (alive.current) setError(codingError(e)) })
  }, [forget, project.id])
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    let stopped = false; setError(''); setLoaded(false)
    void listCodingTerminals(project.id).then(items => { if (!stopped) { const running = items.filter(item => item.running).map(compactTitle); setTerminals(running); items.filter(item => !item.running).forEach(item => { void deleteCodingTerminal(project.id, item.id).catch(() => undefined) }); setActive(previous => running.some(item => item.id === previous) || previous === 'agent' ? previous : running[0]?.id || '') } }).catch(e => { if (!stopped) setError(codingError(e)) }).finally(() => { if (!stopped) setLoaded(true) })
    return () => { stopped = true }
  }, [project.id, revision])
  useEffect(() => { if (!hasRun) setActive(previous => previous === 'agent' ? terminals[0]?.id || '' : previous) }, [hasRun, terminals])
  useEffect(() => { sessionStorage.setItem(`minis.coding.terminal.${project.id}`, active) }, [project.id, active])
  const create = async () => {
    if (busy) return; setBusy(true); setError('')
    try { const terminal = compactTitle(await createCodingTerminal(project.id)); if (alive.current) { setTerminals(previous => [...previous, terminal]); setActive(terminal.id); onOpenChange(true); if (embedded) onActivate() } }
    catch (e) { if (alive.current) setError(codingError(e)) }
    finally { if (alive.current) setBusy(false) }
  }
  const remove = async (terminal: CodingTerminal) => {
    if (busy) return; setBusy(true); setError('')
    try {
      await deleteCodingTerminal(project.id, terminal.id)
      if (alive.current) forget(terminal.id)
    } catch (e) { if (alive.current) setError(codingError(e)) }
    finally { if (alive.current) setBusy(false) }
  }
  const logs = events.filter(event => event.type.startsWith('process.') || event.type === 'validation.finished').map(event => {
    const row = eventData(event)
    return event.type === 'process.output' ? String(row.text || '') : event.type === 'process.started' ? `$ ${String(row.command)}\n` : event.type === 'process.finished' ? `\n退出码 ${String(row.exitCode)}\n` : event.type === 'validation.finished' ? `\n验证：${String(row.status)}\n${String(row.command || '')}\n` : `${String(row.error || '')}\n`
  }).join('')
  const select = (terminalId: string) => { setActive(terminalId); onOpenChange(true); if (embedded) onActivate() }
  const tabControls = <div ref={controls} className="coding-terminal-controls">
      <div className="coding-terminal-tabs scrollbar-slim" role="tablist" aria-label="终端标签" onKeyDown={event => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || !(event.target as HTMLElement).matches('[role="tab"]')) return
        const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')], index = buttons.indexOf(event.target as HTMLButtonElement)
        if (!buttons.length) return
        event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length
        buttons[next].click(); buttons[next].focus()
      }}>
        {hasRun && <button id={`${id}-agent-tab`} role="tab" aria-selected={active === 'agent' && (!embedded || visible)} aria-controls={`${id}-agent-panel`} tabIndex={active === 'agent' || !terminals.length ? 0 : -1} onClick={() => select('agent')}>Agent 执行日志</button>}
        {terminals.map(terminal => <div className="coding-terminal-tab" key={terminal.id}><button id={`${id}-${terminal.id}-tab`} role="tab" aria-selected={active === terminal.id && (!embedded || visible)} aria-controls={`${id}-${terminal.id}-panel`} tabIndex={active === terminal.id || !active && terminals[0]?.id === terminal.id ? 0 : -1} onClick={() => select(terminal.id)}><AppIcon name="terminal" size={14} />{terminal.title}</button><button className="coding-terminal-close" aria-label={`删除${terminal.title}`} title={`删除${terminal.title}并结束会话`} disabled={busy} onClick={() => void remove(terminal)}><AppIcon name="close" size={14} /></button></div>)}
      </div>
      <button type="button" className="coding-terminal-create" aria-label="新建终端" title="新建终端" disabled={busy || !project.enabled || !loaded} onClick={() => void create()}><AppIcon name={embedded ? 'terminal' : 'plus'} /></button>
    </div>
  return <section ref={panel} className={`coding-terminals${embedded ? ' is-embedded' : ''}`} aria-label="项目终端" hidden={embedded && !visible}>
    {embedded ? tabsHost && createPortal(tabControls, tabsHost) : <header><button className="coding-terminal-toggle" aria-expanded={open} aria-controls={`${id}-body`} onClick={() => onOpenChange(!open)}><AppIcon name="terminal" /><span>终端</span><AppIcon name={open ? 'chevron-down' : 'chevron-right'} /></button>{tabControls}<span className="coding-terminal-location" title={`${project.workspaceName} / ${project.directory}`}>{project.workspaceName} · 项目目录</span></header>}
    {error && <div className="coding-inline-error" role="alert">{error}<Button size="sm" variant="quiet" disabled={busy} onClick={() => setRevision(value => value + 1)}>刷新终端列表</Button></div>}
    <div id={`${id}-body`} className="coding-terminal-content" hidden={!embedded && !open}>
      <div className="coding-terminal-body">
        {terminals.map(terminal => <div key={terminal.id} role="tabpanel" id={`${id}-${terminal.id}-panel`} aria-labelledby={`${id}-${terminal.id}-tab`} hidden={active !== terminal.id}><CodingTerminalSession onExit={exited} project={project.id} terminal={terminal} active={(embedded ? visible : open) && active === terminal.id} /></div>)}
        {hasRun && <pre role="tabpanel" id={`${id}-agent-panel`} aria-labelledby={`${id}-agent-tab`} hidden={active !== 'agent'} className="coding-terminal-log scrollbar-slim">{logs || '尚未执行命令。此标签保留 Agent 的执行记录，可通过右上角 + 新建交互终端。'}</pre>}
        {!active && <div className="coding-terminal-empty"><AppIcon name="terminal" size={24} /><p>{loaded ? '打开终端，在项目环境中运行命令' : '正在读取终端…'}</p><Button size="sm" disabled={busy || !loaded || !project.enabled} onClick={() => void create()}>{busy ? '正在连接…' : '新建终端'}</Button><small>保留工作目录与环境变量 · 离开页面 30 分钟后自动回收</small></div>}
      </div>
    </div>
  </section>
}
