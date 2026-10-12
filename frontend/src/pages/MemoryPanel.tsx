import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import { listMaPeers, listProjects, listSessions, memoryHealth, memoryItems, memoryMetrics, memoryShared, memorySummary, type MaPeer, type MemoryHealth, type MemoryItem, type MemoryMetrics, type MemoryShare, type MemorySummary, type Project, type SessionItem, type WorkingItem } from '../api/client'
import { memoryObservedWork, memorySnapshots, memoryVisibility, type MemoryContext, type MemoryVisibility, type SnapshotSummary } from '../api/memoryObservation'
import MemoryWorkbench, { type WorkbenchTab } from './MemoryWorkbench'
import MemoryInspector from './memory/MemoryInspector'
import MemoryReplay from './memory/MemoryReplay'
import { domainNames, errorText, identityNames, layers, scopeKind, scopeNames, status, time } from './memory/model'
import { usePageKit } from '../kit'
import { MemoryButton, MemoryField, MemorySelect } from './memory/MemoryControls'
import MemoryFilters, { type CatalogFilters } from './memory/MemoryFilters'
import './memory/memory.css'
/* 外部异步查询切换时立即重置可读集合 / 加载状态；筛选变化将分页恢复到第一页。 */
/* oxlint-disable react/set-state-in-effect */

type View = 'overview' | 'agent' | 'replay' | 'governance'
type Data = { items: MemoryItem[]; shares: MemoryShare[]; agents: MaPeer[]; projects: Project[]; sessions: SessionItem[]; work: WorkingItem[]; snapshots: SnapshotSummary[]; summary: MemorySummary | null; health: MemoryHealth | null; metrics: MemoryMetrics | null }
const initial: Data = { items: [], shares: [], agents: [], projects: [], sessions: [], work: [], snapshots: [], summary: null, health: null, metrics: null }
const loaders = { items: memoryItems, shares: memoryShared, agents: listMaPeers, projects: listProjects, sessions: listSessions, work: memoryObservedWork, snapshots: memorySnapshots, summary: memorySummary, health: memoryHealth, metrics: memoryMetrics }
const labels: Record<keyof Data, string> = { items: '记忆条目', shares: '发布授权', agents: 'Agent 目录', projects: '项目目录', sessions: '会话目录', work: '工作集', snapshots: '快照索引', summary: '概要', health: '体检', metrics: '进程指标' }
const views: [View, string, string][] = [['overview', '记忆全景', '层级与边界'], ['agent', 'Agent 视角', '归属与可读'], ['replay', '运行回放', '选入与发送'], ['governance', '治理工作台', '审批与控制']]
const governance: [WorkbenchTab, string, string][] = [['candidates', '画像候选', '审核原文证据，批准后才成为全局规则'], ['publish', '交接与提升', '查看收件箱，决定接收与共享层提升'], ['shared', '发布授权', '检查定向共享，并撤回具体授权'], ['working', '工作集', '关闭临时材料、检查恢复点与会话收尾'], ['health', '记忆体检', '检查污染、挤占、漂移与过程指标'], ['timeline', '时间与取代链', '查看双时态记录和替换历史'], ['items', '条目管理', '人工新增、编辑、固定与批量维护'], ['swimlane', '四层泳道', '按内容域和层级检查存储分布']]
function bounded<T>(promise: Promise<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error('观察请求超过 15 秒，请重试')), 15000)
    promise.then(resolve, reject).finally(() => window.clearTimeout(timer))
  })
}

export default function MemoryPanel() {
  const kit = usePageKit()
  const [view, setView] = useState<View>('overview')
  const [data, setData] = useState<Data>(initial)
  const [errors, setErrors] = useState<Partial<Record<keyof Data, string>>>({})
  const [loaded, setLoaded] = useState<Partial<Record<keyof Data, boolean>>>({})
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)
  const [updated, setUpdated] = useState<number | null>(null)
  const [agentId, setAgentId] = useState('builtin')
  const [projectId, setProjectId] = useState('')
  const [sessionId, setSessionId] = useState('')
  const [taskInput, setTaskInput] = useState('')
  const [observedVisibility, setVisibility] = useState<MemoryVisibility | null>(null)
  const [visError, setVisError] = useState('')
  const [visLoading, setVisLoading] = useState(false)
  const [search, setSearch] = useState('')
  const [layer, setLayer] = useState('')
  const [range, setRange] = useState('')
  const [owner, setOwner] = useState('')
  const [domain, setDomain] = useState('')
  const [identity, setIdentity] = useState('')
  const [state, setState] = useState('')
  const [page, setPage] = useState(1)
  const [selected, setSelected] = useState<number | null>(null)
  const [workbench, setWorkbench] = useState<WorkbenchTab>('candidates')
  const refresh = () => setTick(v => v + 1)
  useEffect(() => {
    let live = true
    setLoading(true)
    const keys = Object.keys(loaders) as (keyof Data)[]
    Promise.allSettled(keys.map(k => bounded<unknown>(loaders[k]()).then(value => {
      if (live) { setData(old => ({ ...old, [k]: value })); setLoaded(old => ({ ...old, [k]: true })); setErrors(old => ({ ...old, [k]: undefined })) }
      return value
    }))).then(results => {
      if (!live) return
      const patch: Partial<Data> = {}, failed: Partial<Record<keyof Data, string>> = {}, success: Partial<Record<keyof Data, boolean>> = {}
      results.forEach((result, i) => {
        const key = keys[i]
        if (result.status === 'fulfilled') { Object.assign(patch, { [key]: result.value }); success[key] = true }
        else failed[key] = errorText(result.reason)
      })
      setData(old => ({ ...old, ...patch })); setErrors(failed); setLoaded(old => ({ ...old, ...success })); setLoading(false); setUpdated(Date.now())
    })
    return () => { live = false }
  }, [tick])
  const validTask = taskInput === '' || (/^[0-9]+$/.test(taskInput) && Number.isSafeInteger(Number(taskInput)) && Number(taskInput) > 0)
  const context: MemoryContext = useMemo(() => ({ agentId, projectId: projectId || undefined, sessionId: sessionId || undefined, taskId: taskInput && validTask ? Number(taskInput) : undefined }), [agentId, projectId, sessionId, taskInput, validTask])
  useEffect(() => {
    let live = true
    setVisibility(null); setVisError('')
    if (view !== 'agent' || !validTask) { setVisLoading(false); return }
    setVisLoading(true)
    bounded(memoryVisibility(context)).then(v => { if (live) setVisibility(v) }).catch(e => { if (live) setVisError(errorText(e)) }).finally(() => { if (live) setVisLoading(false) })
    return () => { live = false }
  }, [context, tick, view, validTask])
  // 在 effect 重置之前也不能把旧上下文的权限结果展示成新 Agent 的结果。
  const visibility = observedVisibility && observedVisibility.context.agentId === context.agentId
    && (observedVisibility.context.projectId ?? '') === (context.projectId ?? '')
    && (observedVisibility.context.sessionId ?? '') === (context.sessionId ?? '')
    && (observedVisibility.context.taskId ?? null) === (context.taskId ?? null) ? observedVisibility : null
  const agentNames = useMemo(() => {
    const map = new Map(data.agents.map(a => [a.id, a.name]))
    if (!map.has('builtin')) map.set('builtin', '内置 Agent')
    for (const id of [...data.items.flatMap(i => [i.agentId, i.producerAgent]), ...data.work.map(w => w.agentId), ...data.snapshots.map(s => s.agentId), ...data.sessions.map(s => s.agentId), ...data.shares.filter(s => s.toKind === 'agent').map(s => s.toRef)]) if (id && !map.has(id)) map.set(id, `未登记 Agent · ${id}`)
    return map
  }, [data])
  const projectNames = useMemo(() => {
    const map = new Map(data.projects.map(p => [p.id, p.name]))
    for (const id of [...data.items.map(i => i.projectId), ...data.work.map(w => w.projectId), ...data.snapshots.map(s => s.projectId), ...data.sessions.map(s => s.projectId), ...data.shares.filter(s => s.toKind === 'project').map(s => s.toRef)]) if (id && !map.has(id)) map.set(id, `未登记项目 · ${id}`)
    return map
  }, [data])
  const sessionNames = useMemo(() => {
    const map = new Map(data.sessions.map(s => [s.id, s.title || s.id]))
    for (const id of [...data.items.map(i => i.sessionId), ...data.work.map(w => w.sessionId), ...data.snapshots.map(s => s.sessionId), ...data.shares.filter(s => s.toKind === 'session').map(s => s.toRef)]) if (id && !map.has(id)) map.set(id, `未登记会话 · ${id}`)
    return map
  }, [data])
  const access = new Map(visibility?.entries.map(v => [v.itemId, v]) ?? [])
  const active = data.items.filter(i => status(i) === '有效')
  const scopedItems = view === 'agent' ? data.items.filter(i => access.has(i.id)) : data.items.filter(i => (!projectId || i.projectId === projectId) && (!sessionId || i.sessionId === sessionId) && (!taskInput || i.taskId === Number(taskInput)))
  const filtered = scopedItems.filter(i => (!layer || i.layer === layer) && (!range || scopeKind(i, data.shares) === range) && (!owner || i.owner === owner) && (!domain || i.domain === domain) && (!identity || i.identity === identity) && (!state || status(i) === state) && (!search || `${i.id} ${i.title ?? ''} ${i.body} ${i.agentId ?? ''} ${i.producerAgent ?? ''}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()))).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt || b.id - a.id)
  useEffect(() => setPage(1), [search, layer, range, owner, domain, identity, state, view, agentId, projectId, sessionId, taskInput])
  const pages = Math.max(1, Math.ceil(filtered.length / 25)), currentPage = Math.min(page, pages)
  const selectedItem = data.items.find(i => i.id === selected)
  const selectedCount = selected == null || errors.snapshots || !loaded.snapshots ? null : data.snapshots.filter(s => s.itemIds.includes(selected)).length
  const metric = (key: keyof Data, value: number | string | undefined) => !loaded[key] || errors[key] ? '—' : value ?? '—'
  const resetFilters = () => { setSearch(''); setLayer(''); setRange(''); setOwner(''); setDomain(''); setIdentity(''); setState(''); setProjectId(''); setSessionId(''); setTaskInput('') }
  const chooseSession = (id: string) => {
    setSessionId(id); setTaskInput('')
    const s = data.sessions.find(s => s.id === id)
    if (s) { setAgentId(s.agentId || 'builtin'); setProjectId(s.projectId || '') }
  }
  const work = view === 'agent' ? visibility?.work ?? [] : data.work.filter(w => (!projectId || w.projectId === projectId) && (!sessionId || w.sessionId === sessionId) && (!taskInput || w.taskId === Number(taskInput)))
  const inspect = (id: number) => { setSelected(id) }
  const scopeLabel = (item: MemoryItem) => !loaded.shares || errors.shares ? '发布授权未读取 · 默认 ' + scopeNames[scopeKind(item, [])] : scopeNames[scopeKind(item, data.shares)]
  const filterValues: CatalogFilters = { search, layer, range, owner, domain, identity, state }
  const changeFilter = (key: keyof CatalogFilters, value: string) => {
    const setters = { search: setSearch, layer: setLayer, range: setRange, owner: setOwner, domain: setDomain, identity: setIdentity, state: setState }
    setters[key](value)
  }
  const hasCatalog = !!loaded.items && (view !== 'agent' || !!visibility)
  const directoryOptions = (names: Map<string, string>) => [...names].map(([value, label]) => ({ value, label }))
  return <div className="mem-observatory">
    <kit.Page title="记忆" en="MEMORY OBSERVATORY">
      <nav className="mem-view-nav" aria-label="Memory 观察视图">
        {views.map(([id, name, desc]) => <button type="button" key={id} onClick={() => { if (id === 'agent' && !agentId) setAgentId('builtin'); setView(id) }} aria-pressed={view === id} className={view === id ? 'is-selected' : ''}>
          <strong>{name}</strong><small>{desc}</small>
        </button>)}
      </nav>
      <kit.Toolbar left={<span className="mem-muted">{view === 'overview' ? '查看系统保存的记忆与共享边界。点击层级、范围或 Agent 继续探索。' : view === 'agent' ? '用真实权限区分「归属这个 Agent」和「这个 Agent 能读到」。' : view === 'replay' ? '对照当时选中的版本与实际请求，检查记忆是否送达模型。' : '审核来源和目标，控制记忆的保留、发布与遗忘。'}</span>} right={
        <div className="mem-refresh"><small>{updated ? `最近读取完成 ${time(updated)}` : '正在连接观察接口'}</small><MemoryButton disabled={loading} onClick={refresh}>{loading ? '读取中…' : '刷新观察'}</MemoryButton></div>
      } />
      {Object.values(errors).some(Boolean) && <div role="alert" className="mem-error">
        <strong>部分数据读取失败。相关数字显示「—」，已有列表仅作上次读取参考。</strong>
        {(Object.entries(errors) as [keyof Data, string][]).filter(([, message]) => message).map(([key, message]) => <p key={key}>{labels[key]}：{message}</p>)}
        <MemoryButton onClick={refresh} disabled={loading}>重试读取</MemoryButton>
      </div>}
      {view === 'overview' && <>
        <div className="mem-stats">
          <div><kit.Stat label="有效长期条目 · 全库" value={String(metric('items', active.length))} hint="已存储，不代表每轮都会用到" /></div>
          <div><kit.Stat label="全局用户规则 · 全库" value={String(metric('items', active.filter(i => i.owner === 'USER' && i.domain === 'USER_PROFILE').length))} hint="所有 Agent 可读的正式画像 / 指令" /></div>
          <div><kit.Stat label="待审批画像" value={String(metric('summary', data.summary?.pendingCandidates))} hint="确认来源后，再批准为全局画像" /><MemoryButton variant="quiet" onClick={() => { setWorkbench('candidates'); setView('governance') }}>检查证据 →</MemoryButton></div>
          <div><kit.Stat label="启动快照 · 最近窗口" value={String(metric('snapshots', data.snapshots.length))} hint="最多 100 份，不代表正确利用次数" /></div>
        </div>
        <section className="mem-layers" aria-label="四层架构">
          {layers.map(l => <button type="button" className={`mem-layer layer-${l.id} ${layer === l.id ? 'is-selected' : ''}`} key={l.id} onClick={() => setLayer(layer === l.id ? '' : l.id)} aria-pressed={layer === l.id}>
            <div className="mem-layer-heading"><span className="mem-eyebrow">{l.id}</span><strong>{l.name}</strong><b>{metric('items', active.filter(i => i.layer === l.id).length)}</b></div>
            <p>{l.desc}</p><small>{l.route}{l.id === 'L3' && ` · 工作项窗口 ${metric('work', data.work.length)}`}</small>
          </button>)}
        </section>
        <div className="mem-overview-grid">
          <kit.Panel title="共享与独享的边界" meta={<span className="text-xs text-muted">有效条目 · 全库</span>}>
            <p className="mem-muted">定向发布单列。点击范围筛选目录；接收目标在详情中查看。</p>
            <div className="mem-scope-grid">{Object.entries(scopeNames).map(([key, name]) => <button type="button" key={key} className={range === key ? 'is-selected' : ''} onClick={() => setRange(range === key ? '' : key)} aria-pressed={range === key}>
              <strong>{!loaded.items || !loaded.shares || errors.items || errors.shares ? '—' : active.filter(i => scopeKind(i, data.shares) === key).length}</strong><span>{name}</span>
            </button>)}</div>
          </kit.Panel>
          <kit.Panel title="Agent 归属地图" meta={<span className="text-xs text-muted">{agentNames.size} 位</span>}>
            <p className="mem-muted">按 agent_id 绑定统计。点击 Agent 查看实际可读范围。</p>
            <div className="mem-agent-list scrollbar-slim" tabIndex={0} aria-label="所有 Agent 的归属分布">
              {!loaded.items ? <p className="mem-muted">{loading ? '正在读取记忆归属…' : '归属目录未读取，请重试。'}</p> : [...agentNames].map(([id, name]) => {
                const own = active.filter(i => i.agentId === id), total = own.length
                return <button type="button" className="mem-agent-line" key={id} onClick={() => { setAgentId(id); setSessionId(''); setTaskInput(''); setView('agent') }}>
                  <strong>{name}</strong><div className="mem-distribution" aria-label={`${total} 条绑定记忆`}>
                    {layers.map(l => { const n = own.filter(i => i.layer === l.id).length; return n ? <span key={l.id} className={`layer-${l.id}`} style={{ flex: n }}>{l.id} · {n}</span> : null })}
                    {!total && <small>尚无绑定条目</small>}
                  </div><span>{errors.items ? '—' : total} 条 →</span>
                </button>
              })}
            </div>
          </kit.Panel>
        </div>
        <details className="mem-learning">
          <summary>架构说明 · 归属、内容域、层级和权限有什么区别？</summary>
          <div className="mem-learning-grid"><p><strong>Owner · 谁拥有</strong>用户、项目、Agent 或任务；生产者只是来源。</p><p><strong>Domain · 存什么</strong>用户规则、项目知识、Agent 经验或任务情景。</p><p><strong>Layer · 怎样流转</strong>L1 共享、L2 私有、L3 临时、L4 显式发布。</p><p><strong>权限 · 谁能用</strong>上下文与发布共同决定。L1 未必全局；L2 发布后也可共享。</p></div>
        </details>
      </>}
      {(view === 'overview' || view === 'agent' || view === 'replay') && <section className="mem-context" aria-label="观察上下文">
        <div className="mem-context-heading"><strong>{view === 'agent' ? '观察上下文' : view === 'replay' ? '执行记录筛选' : '目录范围'}</strong><span className="mem-muted">{view === 'agent' ? '选择会话会带入所属 Agent 和项目；可手动调整。' : view === 'replay' ? '筛选历史执行，不改变 Agent 的记忆。' : '选择 Agent 进入权限视角；项目与会话筛选目录。'}</span></div>
        <div className="mem-context-fields">
          <MemorySelect label={view === 'replay' ? '执行 Agent' : view === 'agent' ? '观察 Agent' : 'Agent 入口'} value={view === 'overview' ? '' : agentId} searchable onChange={value => { setAgentId(value); setSessionId(''); if (view === 'overview') setView('agent') }} options={[...(view !== 'agent' ? [{ value: '', label: view === 'overview' ? '选择后进入权限视角' : '全部 Agent' }] : []), ...directoryOptions(agentNames)]} />
          <MemorySelect label="项目上下文" value={projectId} searchable onChange={value => { setProjectId(value); setSessionId('') }} options={[{ value: '', label: view === 'agent' ? '无项目上下文' : '全部项目' }, ...directoryOptions(projectNames)]} />
          <MemorySelect label="会话上下文" value={sessionId} searchable onChange={chooseSession} options={[{ value: '', label: view === 'agent' ? '无会话绑定' : '全部会话' }, ...directoryOptions(sessionNames)]} />
          {view !== 'replay' && <MemoryField label="任务绑定"><input aria-label="任务绑定" placeholder="任务 ID（可选）" inputMode="numeric" value={taskInput} onChange={e => setTaskInput(e.target.value)} /></MemoryField>}
        </div>
        {!validTask && view !== 'replay' && <p className="mem-error" role="alert">任务 ID 必须是正整数。</p>}
      </section>}
      {view === 'agent' && <section className="mem-agent-perspective">
        <h3>{agentNames.get(agentId) ?? agentId} 的实际可读范围</h3>
        <p className="mem-muted">后端运行时权限查询。没有会话 / 任务绑定时，不包含仅由该绑定授权的记忆。</p>
        {visLoading ? <p role="status">正在校验此上下文的权限…</p> : visError ? <p className="mem-error" role="alert">{visError}<MemoryButton onClick={refresh}>重试</MemoryButton></p> : visibility && <>
          <div className="mem-stats">
            <div><kit.Stat label="可读长期条目" value={String(visibility.entries.length)} hint="权限命中，不等于本轮选入" /></div>
            <div><kit.Stat label="绑定该 Agent · 全库" value={String(metric('items', active.filter(i => i.agentId === agentId).length))} hint="归属统计，不等于可读范围" /></div>
            <div><kit.Stat label="可读工作材料" value={String(visibility.work.length)} hint="运行时工作集，上限 200" /></div>
            <div><kit.Stat label="Agent 可编辑" value={String(visibility.entries.filter(e => e.editableByAgent).length)} hint="用户管理权限独立于 Agent" /></div>
          </div>
          <p className="mem-muted">权限校验时间 {time(visibility.checkedAt)}{visibility.entries.some(e => !data.items.some(i => i.id === e.itemId)) && ' · 部分可读条目未在目录中读取，请刷新。'}</p>
        </>}
      </section>}
      {view === 'replay' && <MemoryReplay context={{ agentId: agentId || undefined, projectId: projectId || undefined, sessionId: sessionId || undefined }} items={data.items} agentNames={agentNames} projectNames={projectNames} inspect={inspect} refresh={tick} />}
      {(view === 'overview' || view === 'agent') && <>
        <section className="mem-catalog">
          <h3>{view === 'agent' ? '这个上下文能读取的记忆' : '长期记忆目录'}</h3>
          <MemoryFilters values={filterValues} change={changeFilter} clear={resetFilters} count={hasCatalog ? filtered.length : null} />
          {!loaded.items ? <kit.Empty>{loading ? '正在读取记忆目录…' : '目录读取失败，请重试。'}</kit.Empty> : view === 'agent' && (visLoading || !visibility) ? <kit.Empty>{visLoading ? '等待权限查询完成…' : visError ? '权限查询失败，暂不展示可读列表。' : '请选择有效的观察上下文。'}</kit.Empty> : !filtered.length ? <kit.Empty>{data.items.length ? '没有匹配的记忆。可清除筛选或补充会话与任务上下文。' : '还没有长期记忆。可在治理工作台添加条目，或完成一次执行后观察提炼结果。'}</kit.Empty> : <>
            <div className="mem-item-list">{filtered.slice((currentPage - 1) * 25, currentPage * 25).map(i => <button type="button" className={`mem-item ${selected === i.id ? 'is-selected' : ''}`} key={i.id} onClick={() => inspect(i.id)}>
              <div className="mem-row"><strong>{i.pinned && '◆ '}{i.title || '未命名记忆'}</strong><span className="mem-tag">{i.layer} · #{i.id} · v{i.version ?? 0}</span></div>
              <p>{i.body}</p><div className="mem-tags"><kit.Badge>{scopeLabel(i)}</kit.Badge><kit.Badge>{domainNames[i.domain ?? ''] ?? '域未分类'}</kit.Badge><kit.Badge tone={i.identity === 'FICTION' ? 'warn' : 'default'}>{identityNames[i.identity ?? ''] ?? '身份未标记'}</kit.Badge><kit.Badge>{status(i)}</kit.Badge>{i.agentId && <kit.Badge>{agentNames.get(i.agentId) ?? i.agentId}</kit.Badge>}{i.projectId && <kit.Badge>{projectNames.get(i.projectId) ?? i.projectId}</kit.Badge>}</div>
              {view === 'agent' && <small className="mem-access-reason">可读原因：{access.get(i.id)?.reasons.join('；')}</small>}
            </button>)}</div>
            <div className="mem-pagination"><span>每页 25 条 · {currentPage} / {pages}</span><MemoryButton disabled={currentPage === 1} onClick={() => setPage(currentPage - 1)}>上一页</MemoryButton><MemoryButton disabled={currentPage === pages} onClick={() => setPage(currentPage + 1)}>下一页</MemoryButton></div>
          </>}
        </section>
        <details className="mem-working" open={view === 'agent'}>
          <summary><strong>L3 临时工作材料</strong><span>{view === 'agent' ? visibility?.work.length ?? '—' : metric('work', work.length)} 条 · 与长期条目分开存储</span></summary>
          <p className="mem-muted">{view === 'agent' ? '当前执行上下文的有效工作集，最多 200 条。' : '最近最多 200 条（含关闭、蒸馏与过期），不是全库总数。'}</p>
          {errors.work && view !== 'agent' ? <p className="mem-error">工作集读取失败。</p> : work.length ? <div className="mem-work-list">{work.slice(0, 20).map(w => <details key={w.id}>
            <summary><strong>{w.title || w.kind}</strong><span>#{w.id} · {w.status}{w.expiresAt != null && w.expiresAt <= (updated ?? w.updatedAt) ? ' / 已过期' : ''} · {w.agentId ? agentNames.get(w.agentId) ?? w.agentId : '未绑定 Agent'}</span></summary>
            <p className="mem-body">{w.body}</p><p className="mem-muted">会话 {w.sessionId ?? '无'} · 任务 {w.taskId ?? '无'} · 到期 {time(w.expiresAt)}</p>
          </details>)}</div> : <p className="mem-muted">{loading || visLoading ? '读取中…' : '此观察范围暂无工作材料。'}</p>}
          {work.length > 20 && <p className="mem-muted">先展示 20 条，可进入治理工作台按会话查看。</p>}
          <MemoryButton onClick={() => { setWorkbench('working'); setView('governance') }}>管理工作集</MemoryButton>
        </details>
      </>}
      {view === 'governance' && <>
        <div className="mem-governance-toolbar">
          <MemorySelect label="治理模块" value={workbench} onChange={value => setWorkbench(value as WorkbenchTab)} options={governance.map(([value, label]) => ({ value, label }))} />
          <p className="mem-muted">{governance.find(([key]) => key === workbench)?.[2]}</p>
        </div>
        <details className="mem-learning"><summary>查看典型流转路径</summary><div className="mem-flow"><span>原始证据</span><b>→</b><span>L3 工作材料</span><b>→</b><span>L2 提炼经验</span><b>→</b><span>L4 定向发布</span><b>→</b><span>L1 人工确认</span></div><p className="mem-muted">画像候选需单独审批；发布、提升与撤销要核对目标与证据。</p></details>
        <MemoryWorkbench key={workbench} initialTab={workbench} onChanged={refresh} embedded />
      </>}
      {view === 'overview' && <details className="mem-health-summary" open={!!errors.health || data.health?.grade === 'risk'}>
        <summary><strong>质量信号与观察边界</strong><span>体检：{!loaded.health || errors.health ? '未读取' : data.health?.grade === 'ok' ? '正常' : data.health?.grade === 'warn' ? '需留意' : '存在风险'}</span></summary>
        <p className="mem-muted">这些是结构与过程信号，不是回答效果评分。</p>
        {data.health && !errors.health && [...data.health.pollution.alerts, ...data.health.crowding.alerts, ...data.health.drift.alerts].map((a, i) => <p className="mem-note" key={i}>{a}</p>)}
        <p className="mem-muted">进程内计数（重启清零）：预算挤出 {metric('metrics', data.metrics?.budgetEvicted)} / 写入冲突 {metric('metrics', data.metrics?.writeConflict)} / 越域阻断 {metric('metrics', data.metrics?.crossDomainBlocked)}。普通预算不含保护条目，快照覆盖率不代表记忆准确率。</p>
      </details>}
    </kit.Page>
    {selectedItem && createPortal(<div className="mem-observatory mem-detail-overlay" onClick={e => { if (e.target === e.currentTarget) setSelected(null) }}><MemoryInspector key={selectedItem.id} item={selectedItem} shares={data.shares} sharesAvailable={!!loaded.shares && !errors.shares} access={view === 'agent' ? access.get(selectedItem.id) : undefined} selectedCount={selectedCount} agentNames={agentNames} projectNames={projectNames} sessionNames={sessionNames} changed={refresh} close={() => setSelected(null)} /></div>, document.body)}
  </div>
}
