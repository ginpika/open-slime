/**
 * MemoryPanel：**记忆**页（记忆体系 v5）——查看 / 新增 / 编辑 / 删除 / 固定系统记住的信息，
 * 并对齐 ChatGPT 的 memory summary page（记忆可见、可控）。
 *
 * <p>v5 起把「一张扁平 scope」升级为 <b>Domain × Layer</b>：本页按 **Domain**（用户画像 / 项目 /
 * Agent 私有 / 任务情景）分组展示，并按 <b>Layer</b>（L1 共享 / L2 私有 / L3 任务 / L4 发布）标注；
 * 另设「画像候选」（审批前不进正式画像）与「共享」（L4 发布/撤销）两个视图。
 *
 * <p>记忆是产品能力：开启 `minis.memory.enabled` 后自动提炼与注入；本页只做管理。
 */

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiError,
  applyPromotion,
  approveCandidate,
  confirmProjectMemory,
  completeSessionMemory,
  clearMemory,
  closeWorkingItem,
  consolidateMemory,
  createMemoryItem,
  deleteMemoryItem,
  memoryAsOf,
  memoryCandidates,
  memoryCheckpoint,
  memoryHealth,
  memoryInbox,
  memoryItemHistory,
  memoryItems,
  memoryMetrics,
  memoryPromotions,
  memoryShared,
  memorySummary,
  memoryWorkingSet,
  moveHandoff,
  pinMemoryItem,
  rejectCandidate,
  restoreWorkingSet,
  revokeMemoryItem,
  revokeMemoryShare,
  scanPromotions,
  shareMemoryItem,
  updateMemoryItem,
  type AsOfView,
  type MemoryCandidate,
  type MemoryHealth,
  type MemoryMetrics,
  type MemoryInboxEntry,
  type MemoryItem,
  type MemoryPromotion,
  type MemoryShare,
  type MemorySummary,
  type WorkingCheckpoint,
  type WorkingItem,
} from '../api/client'
import Toast from '../components/Toast'

/** 新增时的 scope 选项（域内细分） */
const SCOPES: { key: string; label: string }[] = [
  { key: 'profile', label: '画像' },
  { key: 'directive', label: '指令' },
  { key: 'fact', label: '事实' },
  { key: 'decision', label: '决策' },
  { key: 'method', label: '方法' },
  { key: 'open_issue', label: '待解事项' },
  { key: 'project', label: '进行中的事' },
  { key: 'episode', label: '情景摘要' },
]

const OWNERS: { key: string; label: string }[] = [
  { key: '', label: '自动推断' },
  { key: 'AGENT', label: 'Agent 私有' },
  { key: 'PROJECT', label: '项目' },
  { key: 'USER', label: '用户画像' },
]

const DOMAIN_ORDER = ['USER_PROFILE', 'PROJECT', 'AGENT', 'TASK_EPISODIC']
const DOMAIN_LABEL: Record<string, string> = {
  USER_PROFILE: '用户画像',
  PROJECT: '项目',
  AGENT: 'Agent 私有',
  TASK_EPISODIC: '任务情景',
}

/** Layer → 人话标签 */
function layerLabel(layer: string | null): string {
  switch (layer) {
    case 'L1':
      return 'L1 共享'
    case 'L2':
      return 'L2 私有'
    case 'L3':
      return 'L3 任务'
    case 'L4':
      return 'L4 发布'
    default:
      return layer ?? '—'
  }
}

/** 状态 → 人话（active 不显示） */
function statusLabel(status: string | null): string {
  switch (status) {
    case 'superseded':
      return '已取代'
    case 'expired':
      return '已过期'
    case 'archived':
      return '已归档'
    case 'revoked':
      return '已撤销'
    case 'conflicted':
      return '冲突'
    default:
      return ''
  }
}

/** 泳道顺序：L1 → L4 */
const LAYER_ORDER = ['L1', 'L2', 'L3', 'L4']

/** identity（身份上下文）徽标文案 */
const IDENTITY_LABEL: Record<string, string> = {
  REAL: '现实',
  FICTION: '虚构',
  PROJECT_PREF: '项目偏好',
  INFERRED: '推断',
}

function identityLabel(identity: string | null): string {
  if (!identity) return ''
  return IDENTITY_LABEL[identity.toUpperCase()] ?? identity
}

/** 体检总评级 → 人话 */
function gradeLabel(grade: string): string {
  switch (grade) {
    case 'ok':
      return '正常'
    case 'warn':
      return '注意'
    case 'risk':
      return '风险'
    default:
      return grade
  }
}

/** 时间戳 → 本地可读（工作项到期 / 体检时间） */
function fmtTs(ts: number | null): string {
  return ts == null ? '—' : new Date(ts).toLocaleString()
}

/** 毫秒时间戳 → datetime-local 输入值（本地时区 YYYY-MM-DDTHH:mm） */
function toLocalInput(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

/** datetime-local 输入值 → 毫秒时间戳（非法返回 null） */
function fromLocalInput(v: string): number | null {
  const ts = new Date(v).getTime()
  return Number.isFinite(ts) ? ts : null
}

/** 检查点 JSON：能解析则缩进美化，否则原样展示 */
function checkpointText(cp: WorkingCheckpoint): string {
  if (!cp.checkpoint) return '（无检查点）'
  try {
    return JSON.stringify(JSON.parse(cp.checkpoint), null, 2)
  } catch {
    return cp.checkpoint
  }
}

/** 后排错误 → 可读文案（Spring 错误体是 JSON，取出 message 后展示） */
function errMsg(e: unknown): string {
  if (e instanceof ApiError) {
    const raw = e.body?.trim()
    if (raw) {
      try {
        const j = JSON.parse(raw) as { message?: string }
        if (j.message) return j.message
      } catch {
        /* 非 JSON：原样展示 */
      }
      return raw
    }
    return `请求失败（${e.status}）`
  }
  return e instanceof Error ? e.message : String(e)
}

type Tab = 'items' | 'candidates' | 'shared' | 'swimlane' | 'working' | 'health' | 'timeline' | 'publish'

function evidenceQuotes(refs: string): string[] {
  try { return (JSON.parse(refs) as { text?: string }[]).map(r => r.text ?? "").filter(Boolean) }
  catch { return [] }
}

export default function MemoryPanel() {
  const [tab, setTab] = useState<Tab>('items')
  const [items, setItems] = useState<MemoryItem[]>([])
  const [summary, setSummary] = useState<MemorySummary | null>(null)
  const [candidates, setCandidates] = useState<MemoryCandidate[]>([])
  const [shares, setShares] = useState<MemoryShare[]>([])
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<number | null>(null)
  const [editVersion, setEditVersion] = useState(0)
  const [editTitle, setEditTitle] = useState('')
  const [editBody, setEditBody] = useState('')
  const [draft, setDraft] = useState({ scope: 'fact', owner: '', title: '', body: '' })
  const [swimlaneMode, setSwimlaneMode] = useState<'layer' | 'domain'>('layer')
  const [workItems, setWorkItems] = useState<WorkingItem[]>([])
  const [workSession, setWorkSession] = useState('')
  const [cpTaskId, setCpTaskId] = useState('')
  const [checkpoint, setCheckpoint] = useState<WorkingCheckpoint | null>(null)
  const [health, setHealth] = useState<MemoryHealth | null>(null)
  const [metrics, setMetrics] = useState<MemoryMetrics | null>(null)
  const [asOfInput, setAsOfInput] = useState(() => toLocalInput(Date.now()))
  const [asOf, setAsOf] = useState<AsOfView | null>(null)
  const [historyId, setHistoryId] = useState('')
  const [history, setHistory] = useState<MemoryItem[] | null>(null)
  const [inbox, setInbox] = useState<MemoryInboxEntry[]>([])
  const [promotions, setPromotions] = useState<MemoryPromotion[]>([])

  const notify = useCallback(
    (kind: 'success' | 'error', msg: string) => setToast({ kind, msg }),
    [],
  )

  const load = useCallback(() => {
    Promise.all([memoryItems(), memorySummary()])
      .then(([list, s]) => {
        setItems(list)
        setSummary(s)
      })
      .catch((e: unknown) => notify('error', errMsg(e)))
    memoryCandidates('pending').then(setCandidates).catch(() => setCandidates([]))
    memoryShared().then(setShares).catch(() => setShares([]))
  }, [notify])

  useEffect(() => {
    load()
  }, [load])

  /** 工作集：按 sessionId 过滤（缺省全量），随输入变化重取 */
  const loadWork = useCallback(() => {
    const sid = workSession.trim()
    return memoryWorkingSet(sid ? { sessionId: sid } : undefined)
      .then(setWorkItems)
      .catch(() => setWorkItems([]))
  }, [workSession])

  useEffect(() => {
    if (tab === 'working') void loadWork()
  }, [tab, loadWork])

  const loadHealth = useCallback(() => {
    return Promise.all([memoryHealth().then(setHealth), memoryMetrics().then(setMetrics)])
      .catch((e: unknown) => notify('error', errMsg(e)))
  }, [notify])

  useEffect(() => {
    if (tab === 'health') void loadHealth()
  }, [tab, loadHealth])

  /** 时间轴：按选定时刻做双时态点查 */
  const loadAsOf = useCallback(async () => {
    const at = fromLocalInput(asOfInput)
    if (at == null) {
      notify('error', '请选择有效的时间点')
      return
    }
    setBusy(true)
    try {
      setAsOf(await memoryAsOf(at))
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }, [asOfInput, notify])

  /** 取代链：某条记忆的演化（旧 → 新） */
  const loadHistory = useCallback(async (raw: string | number) => {
    const text = typeof raw === 'number' ? String(raw) : raw.trim()
    if (text === '') {
      notify('error', '请输入条目 id')
      return
    }
    const id = Number(text)
    if (!Number.isFinite(id) || id <= 0) {
      notify('error', '请输入有效的条目 id')
      return
    }
    setBusy(true)
    try {
      setHistory(await memoryItemHistory(id))
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }, [notify])

  const loadInbox = useCallback(() => {
    return memoryInbox()
      .then(setInbox)
      .catch(() => setInbox([]))
  }, [])

  const loadPromotions = useCallback(() => {
    return memoryPromotions()
      .then(setPromotions)
      .catch(() => setPromotions([]))
  }, [])

  useEffect(() => {
    if (tab === 'publish') {
      void loadInbox()
      void loadPromotions()
    }
  }, [tab, loadInbox, loadPromotions])

  /** 交接状态机：接受 / 拒绝 / 完成 */
  const onHandoff = async (id: number, state: 'accept' | 'reject' | 'done', ok: string) => {
    setBusy(true)
    try {
      await moveHandoff(id, state)
      notify('success', ok)
      await loadInbox()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  /** 落地一条提升候选 */
  const onApplyPromotion = async (id: number) => {
    setBusy(true)
    try {
      await applyPromotion(id)
      notify('success', '已落地提升')
      await loadPromotions()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  /** 扫描提升候选 */
  const onScanPromotions = async () => {
    setBusy(true)
    try {
      const r = await scanPromotions()
      notify('success', `已扫描，发现 ${r.candidates} 个提升候选`)
      await loadPromotions()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true)
    try {
      await fn()
      notify('success', ok)
      load()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const onCloseWork = async (id: number) => {
    setBusy(true)
    try {
      await closeWorkingItem(id)
      notify('success', '已关闭工作项')
      await loadWork()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const onCheckpoint = async () => {
    const raw = cpTaskId.trim()
    const tid = Number(raw)
    if (raw === '' || !Number.isFinite(tid)) {
      notify('error', '请输入有效的任务 id')
      return
    }
    setBusy(true)
    try {
      setCheckpoint(await memoryCheckpoint(tid))
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  /** 手动恢复：把任务检查点还原进工作集（重启后也可点，幂等） */
  const onRestore = async (taskId: number) => {
    setBusy(true)
    try {
      const r = await restoreWorkingSet(taskId)
      notify('success', r.changed > 0 ? '已还原工作状态' : '此前已还原过，无新增')
      await loadWork()
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  /** 泳道：按层（列）→ 域（组）或按域（列）→ 层（组）两视角 */
  const swimlanes = useMemo(() => {
    const byLayer = swimlaneMode === 'layer'
    const topOrder = byLayer ? LAYER_ORDER : DOMAIN_ORDER
    const subOrder = byLayer ? DOMAIN_ORDER : LAYER_ORDER
    const topLabel = (k: string) => (byLayer ? layerLabel(k) : DOMAIN_LABEL[k] ?? k)
    const subLabel = (k: string) => (byLayer ? DOMAIN_LABEL[k] ?? k : layerLabel(k))
    const topOf = (it: MemoryItem) => (byLayer ? it.layer ?? 'L2' : it.domain ?? 'AGENT')
    const subOf = (it: MemoryItem) => (byLayer ? it.domain ?? 'AGENT' : it.layer ?? 'L2')
    return topOrder.map((top) => {
      const inTop = items.filter((it) => topOf(it) === top)
      const subs = subOrder
        .map((sub) => ({
          key: sub,
          label: subLabel(sub),
          items: inTop.filter((it) => subOf(it) === sub),
        }))
        .filter((s) => s.items.length > 0)
      return { key: top, label: topLabel(top), total: inTop.length, subs }
    })
  }, [items, swimlaneMode])

  /** 工作集按 sessionId 分组 */
  const workGroups = useMemo(() => {
    const groups = new Map<string, WorkingItem[]>()
    for (const w of workItems) {
      const list = groups.get(w.sessionId) ?? []
      list.push(w)
      groups.set(w.sessionId, list)
    }
    return [...groups.entries()].map(([sessionId, list]) => ({ sessionId, items: list }))
  }, [workItems])

  /** 体检告警列表（三张卡共用） */
  const alertList = (alerts: string[]) =>
    alerts.length === 0 ? (
      <p className="text-xs text-muted">无告警</p>
    ) : (
      <ul className="flex list-disc flex-col gap-1 pl-5 text-xs">
        {alerts.map((a, i) => (
          <li key={i}>{a}</li>
        ))}
      </ul>
    )

  /** 按 Domain 分组（v5 语义域），组内新建在前 */
  const byDomain = useMemo(() => {
    const groups = new Map<string, MemoryItem[]>()
    for (const it of items) {
      const d = it.domain ?? 'AGENT'
      const list = groups.get(d) ?? []
      list.push(it)
      groups.set(d, list)
    }
    return DOMAIN_ORDER.filter((d) => groups.has(d)).map((d) => ({
      domain: d,
      label: DOMAIN_LABEL[d] ?? d,
      items: groups.get(d) ?? [],
    }))
  }, [items])

  const onAdd = () => {
    if (!draft.body.trim()) {
      notify('error', '正文不能为空')
      return
    }
    void run(
      () =>
        createMemoryItem({
          scope: draft.scope,
          title: draft.title.trim() || null,
          body: draft.body.trim(),
          owner: draft.owner || null,
        }),
      '已新增记忆',
    )
    setDraft({ scope: draft.scope, owner: draft.owner, title: '', body: '' })
  }

  const onSaveEdit = (id: number) => {
    if (!editBody.trim()) {
      notify('error', '正文不能为空')
      return
    }
    void run(
      () => updateMemoryItem(id, { scope: '', title: editTitle.trim() || null, body: editBody.trim(), version: editVersion }),
      '已更新记忆',
    )
    setEditing(null)
  }

  const onDelete = (it: MemoryItem) => {
    if (!window.confirm(`删除这条记忆？\n${it.body.slice(0, 60)}`)) return
    void run(() => deleteMemoryItem(it.id), '已删除')
  }

  const onPin = (it: MemoryItem) =>
    void run(() => pinMemoryItem(it.id, !it.pinned), it.pinned ? '已取消固定' : '已固定')

  const onRevoke = (it: MemoryItem) => {
    if (!window.confirm('撤销这条记忆？画像会写墓碑，同内容不再自动重建。')) return
    void run(() => revokeMemoryItem(it.id), '已撤销')
  }

  const onShare = (it: MemoryItem) => {
    const toKind = window.prompt('共享给谁？（user / project / agent / session）', 'user')
    if (!toKind) return
    const toRef = toKind === 'user' ? null : window.prompt('接收方 id（projectId / agentId / sessionId）', '')
    void run(() => shareMemoryItem(it.id, { toKind, toRef }), '已发布共享')
  }

  const onClear = () => {
    if (!window.confirm('清空全部记忆？此操作不可撤销。')) return
    void run(() => clearMemory(), '已清空')
  }

  const total = summary?.total ?? items.length

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="ui-heading text-md">记忆</h2>
          <p className="mt-1 max-w-[820px] text-xs leading-relaxed text-muted">
            按<b>语义域</b>（用户画像 / 项目 / Agent 私有 / 任务情景）分组，按<b>流转层</b>
            （L1 共享 · L2 私有 · L3 任务 · L4 发布）标注。画像类信息须经「候选 → 审批」才成为正式画像；
            创作类项目的记忆永不升级为现实画像。
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <span className="text-xs text-muted">
            共 {total} 条 · 有效 {summary?.active ?? 0} · 候选 {summary?.pendingCandidates ?? 0} · 共享{' '}
            {summary?.publishedShares ?? 0}
          </span>
          <button
            type="button"
            className="ui-btn ui-btn--sm ui-btn--quiet"
            disabled={busy}
            onClick={() => void run(() => consolidateMemory(), '已整理')}
          >
            整理
          </button>
          <button
            type="button"
            className="ui-btn ui-btn--sm ui-btn--danger"
            disabled={busy || total === 0}
            onClick={onClear}
          >
            清空
          </button>
        </div>
      </div>

      {/* 视图切换 */}
      <div className="flex items-center gap-2 text-xs">
        {(
          [
            ['items', `条目 ${items.length}`],
            ['candidates', `画像候选 ${candidates.length}`],
            ['shared', `共享 ${shares.length}`],
            ['swimlane', '泳道'],
            ['working', '工作集'],
            ['health', '体检'],
            ['timeline', '时间轴'],
            ['publish', '发布与收件箱'],
          ] as [Tab, string][]
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            className={`ui-btn ui-btn--sm ${tab === key ? 'ui-btn--primary' : 'ui-btn--quiet'}`}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'items' && (
        <>
          {/* 新增 */}
          <div className="flex flex-wrap items-end gap-2 border border-line-strong p-3">
            <label className="flex flex-col gap-1 text-xs text-muted">
              类型
              <select
                className="ui-control"
                value={draft.scope}
                onChange={(e) => setDraft((d) => ({ ...d, scope: e.target.value }))}
              >
                {SCOPES.map((s) => (
                  <option key={s.key} value={s.key}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              归属
              <select
                className="ui-control"
                value={draft.owner}
                onChange={(e) => setDraft((d) => ({ ...d, owner: e.target.value }))}
              >
                {OWNERS.map((o) => (
                  <option key={o.key} value={o.key}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex min-w-[160px] flex-1 flex-col gap-1 text-xs text-muted">
              标题（可选）
              <input
                className="ui-control"
                value={draft.title}
                onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))}
              />
            </label>
            <label className="flex min-w-[240px] flex-[2] flex-col gap-1 text-xs text-muted">
              正文
              <input
                className="ui-control"
                value={draft.body}
                onChange={(e) => setDraft((d) => ({ ...d, body: e.target.value }))}
              />
            </label>
            <button
              type="button"
              className="ui-btn ui-btn--sm ui-btn--primary"
              disabled={busy}
              onClick={onAdd}
            >
              新增
            </button>
          </div>

          {/* 按 Domain 分组 */}
          {byDomain.length === 0 && <p className="text-xs text-muted">（暂无记忆）</p>}
          {byDomain.map((g) => (
            <div key={g.domain} className="flex flex-col gap-2">
              <div className="flex items-center gap-2 text-xs text-muted">
                <span className="ui-heading text-sm">{g.label}</span>
                <span>{g.items.length}</span>
              </div>
              {g.items.map((it) => (
                <div
                  key={it.id}
                  className={`flex flex-col gap-2 border p-3 ${
                    it.active ? 'border-line-strong' : 'border-line opacity-60'
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      {editing === it.id ? (
                        <input
                          className="ui-control mb-1 w-full"
                          value={editTitle}
                          placeholder="标题"
                          onChange={(e) => setEditTitle(e.target.value)}
                        />
                      ) : (
                        it.title && <div className="text-sm font-medium">{it.title}</div>
                      )}
                      {editing === it.id ? (
                        <textarea
                          className="ui-control w-full"
                          rows={3}
                          value={editBody}
                          onChange={(e) => setEditBody(e.target.value)}
                        />
                      ) : (
                        <div className="text-sm">{it.body}</div>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-1 text-xs text-muted">
                      <span>{layerLabel(it.layer)}</span>
                      {it.pinned && <span>· 已固定</span>}
                      {statusLabel(it.status) && <span>· {statusLabel(it.status)}</span>}
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                    <span>
                      {it.source === 'manual' ? '人工' : it.source === 'explicit' ? '用户明说' : '自动'}
                    </span>
                    {it.owner && <span>· {it.owner}</span>}
                    {it.evidenceLevel && <span>· 证据 {it.evidenceLevel}</span>}
                    {editing === it.id ? (
                      <>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--primary"
                          disabled={busy}
                          onClick={() => onSaveEdit(it.id)}
                        >
                          保存
                        </button>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--quiet"
                          onClick={() => setEditing(null)}
                        >
                          取消
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--quiet"
                          onClick={() => {
                            setEditing(it.id)
                            setEditVersion(it.version ?? 0)
                            setEditTitle(it.title ?? '')
                            setEditBody(it.body)
                          }}
                        >
                          编辑
                        </button>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--quiet"
                          disabled={busy}
                          onClick={() => onPin(it)}
                        >
                          {it.pinned ? '取消固定' : '固定'}
                        </button>
                        {it.owner === 'PROJECT' && it.layer === 'L3' && it.active && (
                          <button type="button" className="ui-btn ui-btn--sm ui-btn--quiet" disabled={busy}
                            onClick={() => void run(() => confirmProjectMemory(it.id, it.version ?? 0), '已确认为项目共享记忆')}>
                            确认项目事实
                          </button>
                        )}
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--quiet"
                          disabled={busy}
                          onClick={() => onShare(it)}
                        >
                          共享
                        </button>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--quiet"
                          disabled={busy}
                          onClick={() => onRevoke(it)}
                        >
                          撤销
                        </button>
                        <button
                          type="button"
                          className="ui-btn ui-btn--sm ui-btn--danger"
                          disabled={busy}
                          onClick={() => onDelete(it)}
                        >
                          删除
                        </button>
                      </>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ))}
        </>
      )}

      {tab === 'candidates' && (
        <div className="flex flex-col gap-2">
          {candidates.length === 0 && <p className="text-xs text-muted">（暂无待审候选）</p>}
          {candidates.map((c) => (
            <div key={c.id} className="flex flex-col gap-2 border border-line-strong p-3">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  {c.title && <div className="text-sm font-medium">{c.title}</div>}
                  <div className="text-sm">{c.body}</div>
                  {c.evidenceRefs && <details className="mt-1 text-xs text-muted"><summary>查看原话证据</summary>
                    {evidenceQuotes(c.evidenceRefs).map((quote, i) => <p key={i} className="mt-1 whitespace-pre-wrap">{quote}</p>)}
                  </details>}
                  {c.validUntil && <p className="text-xs text-muted">有效至 {fmtTs(c.validUntil)}</p>}
                </div>
                <div className="shrink-0 text-xs text-muted">
                  {c.scopeLabel} · 证据 {c.evidenceLevel} × {c.evidenceCount}
                </div>
              </div>
              <div className="flex items-center gap-2 text-xs text-muted">
                <span>来源 {c.sourceKind}</span>
                <button
                  type="button"
                  className="ui-btn ui-btn--sm ui-btn--primary"
                  disabled={busy}
                  onClick={() => void run(() => approveCandidate(c.id), '已批准为正式画像')}
                >
                  批准
                </button>
                <button
                  type="button"
                  className="ui-btn ui-btn--sm ui-btn--danger"
                  disabled={busy}
                  onClick={() => void run(() => rejectCandidate(c.id), '已拒绝（写墓碑）')}
                >
                  拒绝
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {tab === 'shared' && (
        <div className="flex flex-col gap-2">
          {shares.length === 0 && <p className="text-xs text-muted">（暂无共享）</p>}
          {shares.map((s) => (
            <div key={s.id} className="flex items-center justify-between gap-3 border border-line-strong p-3">
              <div className="min-w-0 text-sm">
                <span className="text-muted">条目 #{s.itemId}</span>
                {' → '}
                <span>
                  {s.toKind}
                  {s.toRef ? `：${s.toRef}` : ''}
                </span>
                {s.note && <span className="text-muted"> · {s.note}</span>}
              </div>
              <button
                type="button"
                className="ui-btn ui-btn--sm ui-btn--danger"
                disabled={busy}
                onClick={() => void run(() => revokeMemoryShare(s.id), '已撤销共享')}
              >
                撤销
              </button>
            </div>
          ))}
        </div>
      )}

      {tab === 'swimlane' && (
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2 text-xs">
            <span className="text-muted">分组</span>
            <button
              type="button"
              className={`ui-btn ui-btn--sm ${swimlaneMode === 'layer' ? 'ui-btn--primary' : 'ui-btn--quiet'}`}
              onClick={() => setSwimlaneMode('layer')}
            >
              按层
            </button>
            <button
              type="button"
              className={`ui-btn ui-btn--sm ${swimlaneMode === 'domain' ? 'ui-btn--primary' : 'ui-btn--quiet'}`}
              onClick={() => setSwimlaneMode('domain')}
            >
              按域
            </button>
          </div>
          {items.length === 0 && <p className="text-xs text-muted">（暂无记忆）</p>}
          <div className="flex gap-3 overflow-x-auto">
            {swimlanes.map((lane) => (
              <div
                key={lane.key}
                className="flex min-w-[260px] flex-1 flex-col gap-3 border border-line-strong p-3"
              >
                <div className="flex items-center gap-2 text-xs text-muted">
                  <span className="ui-heading text-sm">{lane.label}</span>
                  <span>{lane.total}</span>
                </div>
                {lane.subs.length === 0 && <p className="text-xs text-muted">（空）</p>}
                {lane.subs.map((sub) => (
                  <div key={sub.key} className="flex flex-col gap-2">
                    <div className="text-xs text-muted">
                      {sub.label} · {sub.items.length}
                    </div>
                    {sub.items.map((it) => (
                      <div key={it.id} className="flex flex-col gap-1 border border-line p-2">
                        <div className="text-sm font-medium">{it.title || it.body.slice(0, 40)}</div>
                        {it.title && <div className="text-xs">{it.body}</div>}
                        <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                          <span>归属 {it.owner ?? '—'}</span>
                          {identityLabel(it.identity) && (
                            <span className="border border-line px-1">{identityLabel(it.identity)}</span>
                          )}
                          {statusLabel(it.status) && <span>{statusLabel(it.status)}</span>}
                        </div>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            ))}
          </div>
        </div>
      )}

      {tab === 'working' && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-2 border border-line-strong p-3">
            <label className="flex min-w-[240px] flex-1 flex-col gap-1 text-xs text-muted">
              会话 id 过滤
              <input
                className="ui-control"
                value={workSession}
                placeholder="留空显示全部会话"
                onChange={(e) => setWorkSession(e.target.value)}
              />
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              任务 id
              <input
                className="ui-control"
                value={cpTaskId}
                placeholder="查看检查点"
                onChange={(e) => setCpTaskId(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="ui-btn ui-btn--sm ui-btn--quiet"
              disabled={busy}
              onClick={() => void onCheckpoint()}
            >
              查看任务检查点
            </button>
          </div>

          {checkpoint && (
            <div className="flex flex-col gap-2 border border-line-strong p-3">
              <div className="flex flex-wrap items-center gap-3 text-xs text-muted">
                <span>
                  任务 #{checkpoint.taskId} · 状态 {checkpoint.status}
                </span>
                {checkpoint.checkpoint && (
                  <button
                    type="button"
                    className="ui-btn ui-btn--sm ui-btn--quiet"
                    disabled={busy}
                    onClick={() => void onRestore(checkpoint.taskId)}
                  >
                    恢复工作状态
                  </button>
                )}
              </div>
              <pre className="max-h-[320px] overflow-auto border border-line p-2 text-xs whitespace-pre-wrap">
                {checkpointText(checkpoint)}
              </pre>
            </div>
          )}

          {workGroups.length === 0 && <p className="text-xs text-muted">（暂无未关闭工作项）</p>}
          {workGroups.map((g) => (
            <div key={g.sessionId} className="flex flex-col gap-2">
              <div className="flex items-center gap-2 text-xs text-muted">
                <span className="ui-heading text-sm">会话 {g.sessionId}</span>
                <button type="button" className="ui-btn ui-btn--sm ui-btn--quiet" disabled={busy}
                  onClick={() => void run(() => completeSessionMemory(g.sessionId), '已提交会话收尾；提炼失败会保留工作项供重试')}>
                  结束会话工作并整理
                </button>
                <span>{g.items.length}</span>
              </div>
              {g.items.map((w) => (
                <div key={w.id} className="flex items-start justify-between gap-3 border border-line p-3">
                  <div className="min-w-0 flex-1">
                    {w.title && <div className="text-sm font-medium">{w.title}</div>}
                    <div className="text-sm">{w.body}</div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted">
                      <span>{w.kind}</span>
                      {w.taskId != null && <span>· 任务 #{w.taskId}</span>}
                      <span>· 到期 {fmtTs(w.expiresAt)}</span>
                    </div>
                  </div>
                  <button
                    type="button"
                    className="ui-btn ui-btn--sm ui-btn--quiet shrink-0"
                    disabled={busy}
                    onClick={() => void onCloseWork(w.id)}
                  >
                    关闭
                  </button>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}

      {tab === 'health' && (
        <div className="flex flex-col gap-3">
          {!health && <p className="text-xs text-muted">（正在读取体检报告…）</p>}
          {health && (
            <>
              <div className="flex flex-wrap items-center gap-3">
                <span className="border border-line-strong px-2 py-1 text-sm">
                  总评级：{gradeLabel(health.grade)}
                </span>
                <span className="text-xs text-muted">生成于 {fmtTs(health.generatedAt)}</span>
                <button
                  type="button"
                  className="ui-btn ui-btn--sm ui-btn--quiet"
                  disabled={busy}
                  onClick={() => void loadHealth()}
                >
                  重新体检
                </button>
              </div>

              <div className="flex flex-col gap-2 border border-line-strong p-3">
                <div className="ui-heading text-sm">记忆污染</div>
                <div className="flex flex-wrap gap-4 text-xs text-muted">
                  <span>虚构进入画像 {health.pollution.fictionInProfile}</span>
                  <span>被拦下的虚构候选 {health.pollution.blockedFictionCandidates}</span>
                  <span>被拒候选 {health.pollution.rejectedCandidates}</span>
                </div>
                {alertList(health.pollution.alerts)}
              </div>

              <div className="flex flex-col gap-2 border border-line-strong p-3">
                <div className="ui-heading text-sm">记忆挤占</div>
                {health.crowding.domains.length === 0 ? (
                  <p className="text-xs text-muted">（无占用数据）</p>
                ) : (
                  health.crowding.domains.map((d) => {
                    const pct = Math.round(d.share * 100)
                    return (
                      <div key={d.key} className="flex items-center gap-2">
                        <span className="w-24 shrink-0 text-xs text-muted">
                          {DOMAIN_LABEL[d.key] ?? d.key}
                        </span>
                        <div className="h-2 flex-1 overflow-hidden rounded-full bg-fg/10">
                          <div
                            className="h-full rounded-full bg-accent"
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                        <span className="w-44 shrink-0 text-right text-xs text-muted">
                          {pct}% · {d.count} 条 · {d.chars} 字
                        </span>
                      </div>
                    )
                  })
                )}
                {alertList(health.crowding.alerts)}
              </div>

              <div className="flex flex-col gap-2 border border-line-strong p-3">
                <div className="ui-heading text-sm">记忆漂移</div>
                <div className="text-xs text-muted">被取代条目 {health.drift.superseded}</div>
                {health.drift.recent.length > 0 && (
                  <div className="flex flex-col gap-1">
                    {health.drift.recent.map((r) => (
                      <div key={r.itemId} className="text-xs text-muted">
                        #{r.itemId}（{r.scope}）{r.title || r.body.slice(0, 40)} → 被 #{r.supersededBy}{' '}
                        取代 · {fmtTs(r.invalidAt)}
                      </div>
                    ))}
                  </div>
                )}
                {alertList(health.drift.alerts)}
              </div>

              {metrics && (
                <div className="flex flex-col gap-2 border border-line-strong p-3">
                  <div className="ui-heading text-sm">运行指标</div>
                  <div className="flex flex-wrap gap-4 text-xs text-muted">
                    <span>无作用域写入被拒 {metrics.scopeMissing}</span>
                    <span>归属违例 {metrics.invariantViolation}</span>
                    <span>乐观锁冲突 {metrics.writeConflict}</span>
                    <span>预算挤出 {metrics.budgetEvicted}</span>
                    <span>跨域写入被拦 {metrics.crossDomainBlocked}</span>
                    <span>
                      快照覆盖率 {Math.round(metrics.snapshotCoverage * 100)}%（
                      {metrics.snapshotSaved}/{metrics.snapshotBuilt}）
                    </span>
                  </div>
                  <p className="text-2xs text-faint">
                    进程内计数，重启清零；前四项长期非 0 说明有写入点没守好不变量
                  </p>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {tab === 'timeline' && (
        <div className="flex flex-col gap-3">
          {/* 时间刻度 + 点查 */}
          <div className="flex flex-wrap items-end gap-2 border border-line-strong p-3">
            <label className="flex flex-col gap-1 text-xs text-muted">
              时间点
              <input
                type="datetime-local"
                className="ui-control"
                value={asOfInput}
                onChange={(e) => setAsOfInput(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="ui-btn ui-btn--sm ui-btn--primary"
              disabled={busy}
              onClick={() => void loadAsOf()}
            >
              查看该时刻
            </button>
            <span className="text-xs text-muted">
              按「该时刻仍有效」回溯：valid_at ≤ 时刻 &lt; invalid_at
            </span>
          </div>

          {asOf && (
            <div className="flex flex-wrap gap-3">
              <div className="flex min-w-[280px] flex-1 flex-col gap-2 border border-line-strong p-3">
                <div className="flex items-center gap-2 text-xs text-muted">
                  <span className="ui-heading text-sm">该时刻有效条目</span>
                  <span>{asOf.items.length}</span>
                  <span>· {fmtTs(asOf.at)}</span>
                </div>
                {asOf.items.length === 0 && <p className="text-xs text-muted">（该时刻无有效条目）</p>}
                {asOf.items.map((it) => (
                  <div key={it.id} className="flex flex-col gap-1 border border-line p-2">
                    <div className="text-sm font-medium">{it.title || it.body.slice(0, 40)}</div>
                    {it.title && <div className="text-xs">{it.body}</div>}
                    <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                      <span>#{it.id}</span>
                      <span>· {layerLabel(it.layer)}</span>
                      {identityLabel(it.identity) && (
                        <span className="border border-line px-1">{identityLabel(it.identity)}</span>
                      )}
                      {statusLabel(it.status) && <span>· {statusLabel(it.status)}</span>}
                      <button
                        type="button"
                        className="ui-btn ui-btn--sm ui-btn--quiet"
                        disabled={busy}
                        onClick={() => {
                          setHistoryId(String(it.id))
                          void loadHistory(it.id)
                        }}
                      >
                        历史
                      </button>
                    </div>
                  </div>
                ))}
              </div>

              <div className="flex min-w-[280px] flex-1 flex-col gap-2 border border-line-strong p-3">
                <div className="flex items-center gap-2 text-xs text-muted">
                  <span className="ui-heading text-sm">该时刻有效知识边</span>
                  <span>{asOf.edges.length}</span>
                </div>
                {asOf.edges.length === 0 && <p className="text-xs text-muted">（该时刻无有效知识边）</p>}
                {asOf.edges.map((e) => (
                  <div key={e.id} className="flex flex-col gap-1 border border-line p-2">
                    <div className="text-sm">
                      {e.subject} · {e.predicate} · {e.object}
                    </div>
                    <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                      <span>{e.owner}</span>
                      <span>· 置信 {e.confidence}</span>
                      {e.validAt != null && <span>· 生效 {fmtTs(e.validAt)}</span>}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* 取代链 */}
          <div className="flex flex-col gap-2 border border-line-strong p-3">
            <div className="ui-heading text-sm">取代链</div>
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-xs text-muted">
                条目 id
                <input
                  className="ui-control"
                  value={historyId}
                  placeholder="如 12"
                  onChange={(e) => setHistoryId(e.target.value)}
                />
              </label>
              <button
                type="button"
                className="ui-btn ui-btn--sm ui-btn--quiet"
                disabled={busy}
                onClick={() => void loadHistory(historyId)}
              >
                查看演化
              </button>
            </div>
            {history === null && (
              <p className="text-xs text-muted">
                （输入条目 id，或在上方条目卡片点「历史」，查看这条记忆按旧 → 新的演化）
              </p>
            )}
            {history !== null && history.length === 0 && (
              <p className="text-xs text-muted">（该条目暂无取代链记录）</p>
            )}
            {history !== null &&
              history.map((h, i) => (
                <div key={h.id} className="flex items-start gap-2 border border-line p-2">
                  <span className="shrink-0 text-xs text-muted">{i + 1}</span>
                  <div className="min-w-0 flex-1">
                    {h.title && <div className="text-sm font-medium">{h.title}</div>}
                    <div className="text-sm">{h.body}</div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted">
                      <span>#{h.id}</span>
                      <span>· {layerLabel(h.layer)}</span>
                      {identityLabel(h.identity) && (
                        <span className="border border-line px-1">{identityLabel(h.identity)}</span>
                      )}
                      {statusLabel(h.status) && <span>· {statusLabel(h.status)}</span>}
                      <span>· {fmtTs(h.createdAt)}</span>
                    </div>
                  </div>
                </div>
              ))}
          </div>
        </div>
      )}

      {tab === 'publish' && (
        <div className="flex flex-col gap-3">
          {/* 待接收 */}
          <div className="flex flex-col gap-2 border border-line-strong p-3">
            <div className="flex items-center gap-2 text-xs text-muted">
              <span className="ui-heading text-sm">待接收</span>
              <span>{inbox.length}</span>
            </div>
            {inbox.length === 0 && <p className="text-xs text-muted">（暂无待接收交接）</p>}
            {inbox.map((e) => (
              <div key={e.handoffId} className="flex flex-col gap-2 border border-line p-2">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    {e.title && <div className="text-sm font-medium">{e.title}</div>}
                    <div className="text-sm">{e.body}</div>
                  </div>
                  <div className="shrink-0 text-xs text-muted">
                    {e.toKind}
                    {e.toRef ? `：${e.toRef}` : ''}
                    {e.layer ? ` · ${layerLabel(e.layer)}` : ''}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted">
                  <span>条目 #{e.itemId}</span>
                  <span>· 状态 {e.state}</span>
                  {e.note && <span>· {e.note}</span>}
                  <button
                    type="button"
                    className="ui-btn ui-btn--sm ui-btn--primary"
                    disabled={busy}
                    onClick={() => void onHandoff(e.handoffId, 'accept', '已接受交接')}
                  >
                    接受
                  </button>
                  <button
                    type="button"
                    className="ui-btn ui-btn--sm ui-btn--danger"
                    disabled={busy}
                    onClick={() => void onHandoff(e.handoffId, 'reject', '已拒绝交接')}
                  >
                    拒绝
                  </button>
                  <button
                    type="button"
                    className="ui-btn ui-btn--sm ui-btn--quiet"
                    disabled={busy}
                    onClick={() => void onHandoff(e.handoffId, 'done', '已完成交接')}
                  >
                    完成
                  </button>
                </div>
              </div>
            ))}
          </div>

          {/* 已发布 */}
          <div className="flex flex-col gap-2 border border-line-strong p-3">
            <div className="flex items-center gap-2 text-xs text-muted">
              <span className="ui-heading text-sm">已发布</span>
              <span>{shares.length}</span>
            </div>
            {shares.length === 0 && <p className="text-xs text-muted">（暂无共享）</p>}
            {shares.map((s) => (
              <div
                key={s.id}
                className="flex items-center justify-between gap-3 border border-line p-2"
              >
                <div className="min-w-0 text-sm">
                  <span className="text-muted">条目 #{s.itemId}</span>
                  {' → '}
                  <span>
                    {s.toKind}
                    {s.toRef ? `：${s.toRef}` : ''}
                  </span>
                  {s.note && <span className="text-muted"> · {s.note}</span>}
                </div>
                <button
                  type="button"
                  className="ui-btn ui-btn--sm ui-btn--danger shrink-0"
                  disabled={busy}
                  onClick={() => void run(() => revokeMemoryShare(s.id), '已撤销共享')}
                >
                  撤销
                </button>
              </div>
            ))}
          </div>

          {/* 提升候选 */}
          <div className="flex flex-col gap-2 border border-line-strong p-3">
            <div className="flex items-center gap-2 text-xs text-muted">
              <span className="ui-heading text-sm">提升候选</span>
              <span>{promotions.length}</span>
              <button
                type="button"
                className="ui-btn ui-btn--sm ui-btn--quiet"
                disabled={busy}
                onClick={() => void onScanPromotions()}
              >
                扫描提升候选
              </button>
            </div>
            {promotions.length === 0 && <p className="text-xs text-muted">（暂无提升候选）</p>}
            {promotions.map((p) => (
              <div
                key={p.id}
                className="flex items-center justify-between gap-3 border border-line p-2"
              >
                <div className="min-w-0 text-xs text-muted">
                  <span>条目 #{p.itemId}</span>
                  <span> · {layerLabel(p.fromLayer)} → {layerLabel(p.toLayer)}</span>
                  <span> · 证据 {p.evidenceCount}</span>
                  <span> · 状态 {p.state}</span>
                  {p.note && <span> · {p.note}</span>}
                </div>
                <button
                  type="button"
                  className="ui-btn ui-btn--sm ui-btn--primary shrink-0"
                  disabled={busy}
                  onClick={() => void onApplyPromotion(p.id)}
                >
                  落地
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      <Toast message={toast?.msg ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    </div>
  )
}
