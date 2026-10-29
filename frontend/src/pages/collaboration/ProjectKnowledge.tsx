import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import type { MaPeer, MaSession } from '../../api/client'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { collaborationError, projectKnowledge, projectKnowledgeProgress, projectDocument, projectDocumentHistory, saveProjectDocument, type ProjectDocument, type ProjectDocumentEntry, type ProjectDocumentKind, type ProjectKnowledgePage, type ProjectKnowledgeProgress } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'
import FloatWindow from '../../components/FloatWindow'
import { Select } from '../../components/formKit'
import './knowledge.css'

const kinds: Record<ProjectDocumentKind, string> = { overview: '项目现状', decision: '关键决策', reference: '成果与参考', delivery: '交付记录' }
const states: Record<string, string> = { pending: '待推进', doing: '执行中', reviewing: '待检查', checking: '检查中', blocked: '受阻' }
const date = (at: number) => new Date(at).toLocaleString('zh-CN')

function DocumentEditor({ project, document, onClose, onSaved }: { project: string; document: ProjectDocument | null; onClose: () => void; onSaved: (doc: ProjectDocument) => void }) {
  const [key] = useState(() => document?.key || `note-${globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`}`)
  const [kind, setKind] = useState<ProjectDocumentKind>(document?.kind || 'overview')
  const [title, setTitle] = useState(document?.title || ''), [content, setContent] = useState(document?.content || '')
  const [retired, setRetired] = useState(document?.retired || false), [saving, setSaving] = useState(false), [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (saving) return
    setSaving(true); setError('')
    try { const saved = await saveProjectDocument(project, key, { kind, title, content, expectedVersion: document?.version || 0, retired }); onSaved(saved) }
    catch (e) { setError(collaborationError(e)) } finally { setSaving(false) }
  }
  return <FloatWindow title={document ? '修订项目文档' : '新建项目文档'} width={800} onClose={() => { if (!saving) onClose() }}><form className="collab-form collab-document-editor" onSubmit={submit}>
    <label>文档标题<input autoFocus required maxLength={160} value={title} disabled={saving} onChange={e => setTitle(e.target.value)} /></label>
    <label>类型<Select label="文档类型" value={kind} disabled={saving || document?.kind === 'delivery'} onChange={v => setKind(v as ProjectDocumentKind)} options={Object.entries(kinds).filter(([k]) => k !== 'delivery' || document?.kind === 'delivery').map(([value, label]) => ({ value, label }))} /></label>
    <label>内容（Markdown）<textarea rows={16} required maxLength={40000} value={content} disabled={saving} onChange={e => setContent(e.target.value)} placeholder="记录当前阶段、已完成与未完成工作、决策依据、成果位置、验证限制和下一步…" /></label>
    <label className="collab-document-retired"><input type="checkbox" checked={retired} disabled={saving} onChange={e => setRetired(e.target.checked)} />已过期或被替代，保留历史供追溯</label>
    {error && <p role="alert">{error}</p>}
    <div className="collab-form-actions"><button type="button" className="ui-btn ui-btn--quiet" disabled={saving} onClick={onClose}>取消</button><button className="ui-btn ui-btn--primary" disabled={saving || !title.trim() || !content.trim()}>{saving ? '正在保存…' : '保存版本'}</button></div>
  </form></FloatWindow>
}

export default function ProjectKnowledge({ projectId, peers = [], sessions = [], onSession }: { projectId: string; peers?: MaPeer[]; sessions?: MaSession[]; onSession: (id: string) => void }) {
  const [docs, setDocs] = useState<ProjectKnowledgePage<ProjectDocumentEntry> | null>(null), [progress, setProgress] = useState<ProjectKnowledgeProgress | null>(null)
  const [query, setQuery] = useState(''), [search, setSearch] = useState(''), [offset, setOffset] = useState(0), [workOffset, setWorkOffset] = useState(0), [retired, setRetired] = useState(false)
  const [selected, setSelected] = useState<ProjectDocument | null>(null), [history, setHistory] = useState<ProjectDocument[]>([])
  const [editor, setEditor] = useState(false), [editDocument, setEditDocument] = useState<ProjectDocument | null>(null)
  const [error, setError] = useState(''), [docError, setDocError] = useState(''), [reading, setReading] = useState(false), [refresh, setRefresh] = useState(0)
  const [settledRequest, setSettledRequest] = useState('')
  const requestKey = JSON.stringify([projectId, search, offset, retired, workOffset, refresh])
  const loading = settledRequest !== requestKey
  const readGeneration = useRef(0)
  const selectedKey = useRef<string | null>(null)
  const [listOpen, setListOpen] = useState(true)
  useEffect(() => { const timer = setTimeout(() => { setSearch(query); setOffset(0) }, 250); return () => clearTimeout(timer) }, [query])
  useEffect(() => () => { readGeneration.current++ }, [projectId])
  const read = useCallback(async (key: string, version?: number) => {
    selectedKey.current = key
    const generation = ++readGeneration.current
    setReading(true); setDocError('')
    try { const [d, h] = await Promise.all([projectDocument(projectId, key, version), projectDocumentHistory(projectId, key)]); if (generation === readGeneration.current) { setSelected(d); setHistory(h); setListOpen(false) } }
    catch (e) { if (generation === readGeneration.current) setDocError(collaborationError(e)) }
    finally { if (generation === readGeneration.current) setReading(false) }
  }, [projectId])
  useEffect(() => {
    let active = true
    Promise.all([projectKnowledge(projectId, search, offset, retired), projectKnowledgeProgress(projectId, workOffset)]).then(([d, p]) => {
      if (!active) return
      setDocs(d); setProgress(p); setError('')
      if (!selectedKey.current && d.items.length) void read((d.items.find(doc => doc.kind === 'overview') || d.items[0]).key)
    }).catch(e => { if (active) setError(collaborationError(e)) }).finally(() => { if (active) setSettledRequest(requestKey) })
    return () => { active = false }
  }, [projectId, search, offset, retired, workOffset, refresh, read, requestKey])
  const author = selected ? (selected.author.startsWith('user:') ? '用户修订' : peers.find(p => p.id === selected.author)?.name || '协作成员') : ''
  const historical = !!selected && !!history.length && selected.version !== history[0].version
  const sourceAvailable = !!selected?.sourceSessionId && sessions.some(session => session.id === selected.sourceSessionId)
  const download = () => {
    if (!selected) return
    const blob = new Blob([`<!-- 项目文档 ${selected.key} v${selected.version}；更新 ${date(selected.updatedAt)}；来源 ${selected.sourceTitle || selected.author} -->\n\n${selected.content}`], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob), anchor = document.createElement('a')
    anchor.href = url; anchor.download = `${selected.key}-v${selected.version}.md`; document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000)
  }
  return <section className="collab-knowledge" aria-label="项目资料与进度">
    <div className="collab-list-heading"><div><h2>项目资料与进度 {docs && <span>{docs.total}</span>}</h2><p className="collab-description">保存已完成的成果、决策依据和当前现状，供下一次协作接着推进。</p></div><div className="collab-knowledge-actions"><button className="ui-btn ui-btn--quiet" disabled={loading} onClick={() => setRefresh(v => v + 1)}><AppIcon name="refresh" />刷新</button><button className="ui-btn ui-btn--outline" onClick={() => { setEditDocument(null); setEditor(true) }}><AppIcon name="plus" />新建文档</button></div></div>
    {error && !loading && <p role="alert">{error}<button className="ui-btn ui-btn--quiet" onClick={() => setRefresh(v => v + 1)}>重试</button></p>}
    {loading && !docs && <p role="status">正在载入项目资料…</p>}
    {progress && <details className="collab-project-progress"><summary><AppIcon name="todo" /><span>当前未完成工作 · {progress.openTodos} 项{progress.blockedTodos > 0 && <span className="collab-progress-blocked">{` · ${progress.blockedTodos} 项受阻`}</span>}</span><AppIcon name="chevron-down" /></summary><p className="collab-description">汇总未归档会话中的工作记录，进入对应会话核对进展与阻塞。</p>
      {progress.work.items.map(w => <button className="collab-progress-row" key={w.todoId} onClick={() => onSession(w.sessionId)}><span><strong>{w.title}</strong><small>{w.sessionTitle}{w.blocker && ` · ${w.blocker}`}</small></span><span>{states[w.status] || w.status}{w.paused && ' · 已暂停'}</span></button>)}
      {!progress.openTodos && <p>当前没有记录中的未完成工作；这不代表整个项目已经完成。</p>}
      {progress.work.total > 20 && <div className="collab-knowledge-pagination"><button className="ui-btn ui-btn--quiet" disabled={workOffset === 0 || loading} onClick={() => setWorkOffset(v => Math.max(0, v - 20))}>上一页工作</button><span>{workOffset + 1}–{Math.min(workOffset + 20, progress.work.total)} / {progress.work.total}</span><button className="ui-btn ui-btn--quiet" disabled={workOffset + 20 >= progress.work.total || loading} onClick={() => setWorkOffset(v => v + 20)}>下一页工作</button></div>}
    </details>}
    <div className="collab-knowledge-toolbar"><label className="collab-search"><AppIcon name="search" /><input aria-label="搜索项目资料" placeholder="搜索标题和正文…" value={query} onChange={e => { setQuery(e.target.value); setListOpen(true) }} /></label><label><input type="checkbox" checked={retired} onChange={e => { setRetired(e.target.checked); setOffset(0); setListOpen(true) }} />包含过期资料</label></div>
    <button className="collab-document-list-toggle" aria-expanded={listOpen} aria-controls="collab-project-document-list" onClick={() => setListOpen(v => !v)}><AppIcon name="doc" /><span>{listOpen ? '收起文档列表' : '选择其他文档'} · {docs?.total || 0} 份资料</span><AppIcon name={listOpen ? 'chevron-down' : 'chevron-right'} /></button>
    <div className="collab-knowledge-layout"><div id="collab-project-document-list" className="collab-document-list scrollbar-slim" data-expanded={listOpen} aria-label="项目文档列表">
      {docs?.items.map(d => <button key={d.key} aria-pressed={selected?.key === d.key} className="collab-document-row" onClick={() => void read(d.key)}><span>{kinds[d.kind]}{d.retired && ' · 已过期'}</span><strong>{d.title}</strong><small>v{d.version} · {date(d.updatedAt)}</small></button>)}
      {docs && !docs.items.length && <p className="collab-secondary">{search || retired ? '没有匹配的项目资料。' : '尚未沉淀项目资料。确认交付后会自动保存成果，也可以先补充项目现状和决策。'}</p>}
      {docs && docs.total > 20 && <div className="collab-knowledge-pagination"><button className="ui-btn ui-btn--quiet" disabled={offset === 0 || loading} onClick={() => setOffset(v => Math.max(0, v - 20))}>上一页</button><span>{offset + 1}–{Math.min(offset + 20, docs.total)} / {docs.total}</span><button className="ui-btn ui-btn--quiet" disabled={offset + 20 >= docs.total || loading} onClick={() => setOffset(v => v + 20)}>下一页</button></div>}
    </div><div className="collab-document-reader" aria-busy={reading}>
      {reading && <p role="status">正在读取文档…</p>}{docError && <p role="alert">{docError}</p>}
      {selected ? <><header><div className="collab-document-heading"><span className="collab-document-kind">{kinds[selected.kind]}</span>{selected.retired && <span className="collab-document-kind">已过期</span>}</div><h3>{selected.title}</h3><p className="collab-secondary">v{selected.version} · {date(selected.updatedAt)} · {author}</p>{selected.sourceTitle && (sourceAvailable ? <button className="collab-document-source" onClick={() => onSession(selected.sourceSessionId!)}><AppIcon name="chat" />来源会话：{selected.sourceTitle}<AppIcon name="arrow-right" size={14} /></button> : <p className="collab-secondary">来源会话：{selected.sourceTitle} · 原会话当前不可用</p>)}<div className="collab-knowledge-actions"><button className="ui-btn ui-btn--outline" onClick={download}><AppIcon name="download" />导出 Markdown</button><button className="ui-btn ui-btn--quiet" disabled={reading || historical} onClick={() => { setEditDocument(selected); setEditor(true) }}><AppIcon name="edit" />修订</button><Select label="文档历史版本" value={String(selected.version)} disabled={reading} onChange={v => void read(selected.key, Number(v))} options={history.map(h => ({ value: String(h.version), label: `v${h.version} · ${date(h.updatedAt)}` }))} /></div>{historical && <div className="collab-document-revision"><span><AppIcon name="clock" />正在查看历史版本 v{selected.version}</span><button className="ui-btn ui-btn--quiet" disabled={reading} onClick={() => void read(selected.key)}>返回最新版</button></div>}</header><div key={`${selected.key}:${selected.version}`} className="collab-document-markdown scrollbar-slim" role="region" aria-label="文档正文" tabIndex={0}><Markdown remarkPlugins={[remarkGfm]}>{selected.content}</Markdown></div></> : !reading && <div className="collab-document-placeholder"><AppIcon name="doc" size={28} /><strong>让项目保留每次推进的成果</strong><p>选择资料查阅全文，也可以新建项目现状或关键决策。正式交付后，成果与验证记录会自动保存在这里。</p></div>}
    </div></div>
    <p className="collab-knowledge-note"><AppIcon name="about" />资料属于项目，删除来源会话后仍保留；需要独立文件时，可导出 Markdown。</p>
    {editor && <DocumentEditor project={projectId} document={editDocument} onClose={() => setEditor(false)} onSaved={d => { setEditor(false); setRefresh(v => v + 1); void read(d.key) }} />}
  </section>
}
