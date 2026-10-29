import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent } from 'react'
import type { MaPeer, MaWorkflow, MaWorkflowEvent } from '../../api/client'
import { AgentAvatar, AgentRole } from '../../components/AgentPresence'
import { AppIcon } from '../../components/AppIcon'
import { TodoHead } from '../WorkflowDetails'
import { agentActivity, NODE_H, NODE_W, workflowLayout, type WorkflowEdge, type WorkflowNode } from '../workflowModel'
import { graphTasks, type WorkSelection } from './model'
import '../workflow.css'

const EDGE_LABELS = { participation: '参与话题', ownership: '负责', dependency: '前置依赖', discussion: '所选历史定向请求' }
function edgePath(a: WorkflowNode, b: WorkflowNode, kind: WorkflowEdge['kind']) {
  if (kind === 'ownership') {
    const x = Math.max(a.x, b.x) + NODE_W + 28
    return `M ${a.x + NODE_W} ${a.y + NODE_H / 2} L ${x} ${a.y + NODE_H / 2} L ${x} ${b.y + NODE_H / 2} L ${b.x + NODE_W} ${b.y + NODE_H / 2}`
  }
  const x1 = a.x + NODE_W / 2, x2 = b.x + NODE_W / 2
  if (kind === 'participation' || kind === 'discussion') {
    if (a.y === b.y) return `M ${x1} ${a.y} C ${x1} ${a.y - 55}, ${x2} ${b.y - 55}, ${x2} ${b.y}`
    const down = b.y > a.y, y1 = a.y + (down ? NODE_H : 0), y2 = b.y + (down ? 0 : NODE_H)
    return `M ${x1} ${y1} C ${x1} ${(y1 + y2) / 2}, ${x2} ${(y1 + y2) / 2}, ${x2} ${y2}`
  }
  const start = a.x + NODE_W, end = b.x, y1 = a.y + NODE_H / 2, y2 = b.y + NODE_H / 2
  return `M ${start} ${y1} C ${(start + end) / 2} ${y1}, ${(start + end) / 2} ${y2}, ${end} ${y2}`
}
type Props = { data: MaWorkflow; peers: MaPeer[]; selected: WorkSelection | null; event: MaWorkflowEvent | null; active: boolean; stale?: boolean; onSelect: (selection: WorkSelection) => void; onDiscuss: () => void }
type Transform = { x: number; y: number; k: number }

/** 只负责画布与手势，项目数据与详情由工作台统一持有。 */
export default memo(function ProjectGraph({ data, peers, selected, event, active, stale, onSelect, onDiscuss }: Props) {
  const [view, setView] = useState<'agents' | 'todos'>('agents')
  const [history, setHistory] = useState(false)
  const [tf, setTf] = useState<Transform>({ x: 32, y: 48, k: 1 })
  const transform = useRef(tf), canvas = useRef<HTMLDivElement>(null), world = useRef<HTMLDivElement>(null)
  const frame = useRef<number | null>(null)
  const pan = useRef<{ id: number; capture: Element; x: number; y: number; tx: number; ty: number; moved: boolean } | null>(null)
  const suppressClick = useRef(false)
  const selectedTask = selected?.kind === 'todo' ? selected.id : null
  const scoped = useMemo(() => ({ ...data, todos: graphTasks(data.todos, selectedTask, history) }), [data, selectedTask, history])
  const { nodes, edges } = useMemo(() => workflowLayout(scoped, view, event), [scoped, view, event])
  const byId = useMemo(() => new Map(nodes.map(n => [n.id, n])), [nodes])
  const width = Math.max(0, ...nodes.map(n => n.x + NODE_W)) + 100, height = Math.max(0, ...nodes.map(n => n.y + NODE_H)) + 100
  const paint = useCallback(() => {
    const next = transform.current
    if (world.current) world.current.style.transform = `translate(${next.x}px, ${next.y}px) scale(${next.k})`
    if (canvas.current) { canvas.current.style.backgroundSize = `${24 * next.k}px ${24 * next.k}px`; canvas.current.style.backgroundPosition = `${next.x}px ${next.y}px` }
  }, [])
  const change = useCallback((next: Transform) => { transform.current = next; setTf(next); paint() }, [paint])
  const fit = useCallback(() => {
    const rect = canvas.current?.getBoundingClientRect()
    if (!rect) return
    const k = Math.max(.35, Math.min(1, (rect.width - 48) / width, (rect.height - 110) / height))
    change({ k, x: Math.max(24, (rect.width - width * k) / 2), y: 65 })
  }, [width, height, change])
  const fitted = useRef('')
  useEffect(() => {
    if (!active) return
    const key = `${data.sessionId}:${view}:${history}:${selectedTask ?? ''}`
    if (key !== fitted.current) { fitted.current = key; fit() }
  }, [data.sessionId, view, history, selectedTask, fit, active])
  const zoom = useCallback((factor: number, x?: number, y?: number) => {
    const rect = canvas.current?.getBoundingClientRect()
    if (!rect) return
    const t = transform.current, cx = x ?? rect.width / 2, cy = y ?? rect.height / 2, k = Math.max(.35, Math.min(2, t.k * factor))
    change({ k, x: cx - (cx - t.x) * k / t.k, y: cy - (cy - t.y) * k / t.k })
  }, [change])
  useEffect(() => {
    const el = canvas.current
    if (!el) return
    const wheel = (e: WheelEvent) => { e.preventDefault(); const rect = el.getBoundingClientRect(); zoom(e.deltaY < 0 ? 1.1 : .9, e.clientX - rect.left, e.clientY - rect.top) }
    el.addEventListener('wheel', wheel, { passive: false })
    return () => el.removeEventListener('wheel', wheel)
  }, [zoom])
  const endPan = useCallback(() => {
    const drag = pan.current
    pan.current = null
    if (frame.current !== null) { cancelAnimationFrame(frame.current); frame.current = null }
    if (!drag) return
    if (drag.moved) suppressClick.current = true
    const next = { ...transform.current }
    paint(); setTf(next)
    if (drag.capture.hasPointerCapture(drag.id)) drag.capture.releasePointerCapture(drag.id)
  }, [paint])
  useEffect(() => {
    const hidden = () => { if (document.hidden) endPan() }
    window.addEventListener('blur', endPan); document.addEventListener('visibilitychange', hidden)
    return () => { window.removeEventListener('blur', endPan); document.removeEventListener('visibilitychange', hidden); if (frame.current !== null) cancelAnimationFrame(frame.current) }
  }, [endPan])
  const startPan = (e: ReactPointerEvent) => {
    if ((e.button !== 0 && e.button !== 1) || !e.isPrimary || pan.current) return
    if (e.target instanceof Element && e.target.closest('button, a, input, [data-todo-menu]')) return
    e.preventDefault(); window.getSelection()?.removeAllRanges(); suppressClick.current = false
    const capture = e.target instanceof Element ? e.target.closest('[data-node]') ?? e.currentTarget : e.currentTarget
    pan.current = { id: e.pointerId, capture, x: e.clientX, y: e.clientY, tx: transform.current.x, ty: transform.current.y, moved: false }
    capture.setPointerCapture(e.pointerId)
  }
  const movePan = (e: ReactPointerEvent) => {
    const drag = pan.current
    if (!drag || e.pointerId !== drag.id) return
    if (e.pointerType === 'mouse' && !(e.buttons & 3)) { endPan(); return }
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y
    if (!drag.moved && Math.hypot(dx, dy) < 5) return
    drag.moved = true
    transform.current = { ...transform.current, x: drag.tx + dx, y: drag.ty + dy }
    // 每帧只更新合成层，避免拖动时反复渲染整张任务图。
    if (frame.current === null) frame.current = requestAnimationFrame(() => { frame.current = null; paint() })
  }
  const focusNode = (node: WorkflowNode) => {
    const rect = canvas.current?.getBoundingClientRect()
    if (rect) change({ ...transform.current, x: rect.width / 2 - (node.x + NODE_W / 2) * transform.current.k, y: rect.height / 2 - (node.y + NODE_H / 2) * transform.current.k })
  }
  return <div className="wf-page wf-embedded">
    <div className="wf-toolbar"><div className="wf-tabs" role="group" aria-label="协作图视角">{([['agents', '成员协作'], ['todos', '工作依赖']] as const).map(([value, label]) => <button type="button" className={`ui-btn ui-btn--sm ${view === value ? 'ui-btn--primary' : 'ui-btn--quiet'}`} aria-pressed={view === value} key={value} onClick={() => setView(value)}>{label}</button>)}</div><label className="collab-history-toggle"><input type="checkbox" checked={history} onChange={e => setHistory(e.target.checked)} />展开已结束工作</label><span className="collab-secondary">显示 {scoped.todos.length} / {data.todos.length} 项工作</span><button type="button" className="ui-btn ui-btn--quiet ui-btn--sm" onClick={onDiscuss}><AppIcon name="chat" />进入讨论</button></div>
    <div ref={canvas} className="wf-canvas" aria-label="协作画布" aria-description="拖动平移，滚轮缩放。默认只展示当前工作及其前置依赖。" onPointerDown={startPan} onPointerMove={movePan} onPointerUp={endPan} onPointerCancel={endPan} onLostPointerCapture={endPan} onDragStartCapture={e => e.preventDefault()} onClickCapture={e => { if (suppressClick.current && e.detail !== 0) { suppressClick.current = false; e.preventDefault(); e.stopPropagation() } }} style={{ backgroundSize: `${24 * tf.k}px ${24 * tf.k}px`, backgroundPosition: `${tf.x}px ${tf.y}px` }}>
      <div className="wf-caption">{event ? '虚线来自所选历史定向请求，不代表实时消息流。' : view === 'agents' ? '话题、成员与当前工作；点击节点查看过程。' : '连线仅表示声明的前置依赖。'}</div>
      <div ref={world} className="wf-world" style={{ transform: `translate(${tf.x}px, ${tf.y}px) scale(${tf.k})` }}>
        <svg className="wf-edges" aria-hidden="true" width={width} height={height}><defs><marker id="collab-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="context-stroke" /></marker></defs>{edges.map((edge, i) => { const a = byId.get(edge.from), b = byId.get(edge.to);return a && b ? <path key={`${edge.from}:${edge.to}:${i}`} data-relation={edge.kind} data-from={edge.from} data-to={edge.to} d={edgePath(a, b, edge.kind)} className={`wf-edge wf-edge--${edge.kind} ${edge.accent ? 'is-active' : ''}`} fill="none" strokeDasharray={edge.dashed ? '7 5' : undefined} markerEnd={edge.kind === 'participation' ? undefined : 'url(#collab-arrow)'}><title>{EDGE_LABELS[edge.kind]}</title></path> : null })}</svg>
        {nodes.map(node => {
          const id = node.id.slice(node.id.indexOf(':') + 1), agent = scoped.agents.find(a => a.id === id), task = scoped.todos.find(t => t.id === id), interactive = node.kind === 'agent' || node.kind === 'todo'
          const pick = () => { if (interactive) onSelect({ kind: node.kind as 'agent' | 'todo', id }) }
          return <div key={node.id} data-node={node.id} className={`wf-node wf-node--${node.kind} ${selected?.id === id ? 'is-selected' : ''} ${task?.status === 'blocked' ? 'is-blocked' : ''} ${agent?.working || task?.status === 'doing' ? 'is-working' : ''}`} style={{ left: node.x, top: node.y, width: NODE_W, minHeight: NODE_H }} role={interactive ? 'button' : undefined} tabIndex={interactive ? 0 : undefined} aria-label={agent ? `${agent.name}，${agentActivity(data, id)}` : task ? `${task.title}，查看任务执行过程` : undefined} onFocus={e => { if (interactive && e.target === e.currentTarget) focusNode(node) }} onClick={pick} onKeyDown={e => { if (e.target === e.currentTarget && interactive && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); pick() } }}>
            {agent && node.kind === 'agent' && <><div className="wf-agent-main"><AgentAvatar name={agent.name} avatar={peers.find(p => p.id === id)?.avatar} working={agent.working} stale={stale} /><div className="min-w-0 flex-1"><p className="font-medium text-fg">{agent.name}</p><AgentRole role={agent.role} color={agent.roleColor} /></div></div><p className="wf-agent-activity">{agentActivity(data, id)}</p></>}
            {node.kind === 'topic' && <><span className="wf-eyebrow"><AppIcon name="chat" />本次协作</span><p className="wf-topic-title">{data.title || '未命名会话'}</p><span className="text-sm text-muted">{data.advancing ? '集结讨论' : data.discussing ? '讨论已开启' : '当前没有发言'}</span></>}
            {node.kind === 'user' && <><span className="wf-eyebrow">所选历史请求的目标</span><p className="wf-topic-title">用户</p></>}
            {task && node.kind === 'todo' && <TodoHead t={task} avatar={peers.find(p => p.id === task.ownerPeer)?.avatar} waiting={task.dependsOn.map(dep => data.todos.find(t => t.id === dep)).filter(t => t && !['done', 'dropped'].includes(t.status)).map(t => t!.title)} />}
          </div>
        })}
      </div>
      {!nodes.length && <div className="wf-empty"><AppIcon name="flow" size={28} /><p>当前没有可展示的工作</p><span className="text-sm text-muted">切换到成员协作，或展开已结束工作。</span></div>}
      <div className="wf-canvas-tools" onPointerDown={e => e.stopPropagation()}><button type="button" className="ui-btn ui-btn--quiet" aria-label="缩小画布" onClick={() => zoom(1 / 1.15)}><AppIcon name="zoom-out" /></button><span>{Math.round(tf.k * 100)}%</span><button type="button" className="ui-btn ui-btn--quiet" aria-label="放大画布" onClick={() => zoom(1.15)}><AppIcon name="zoom-in" /></button><button type="button" className="ui-btn ui-btn--quiet" onClick={fit}><AppIcon name="center" />适应视图</button></div>
    </div>
  </div>
})
