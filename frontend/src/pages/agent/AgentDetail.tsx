/**
 * 智能体详情（新版 UI）：/multi-agent/:id
 *
 * 与成员名册、Dashboard 使用同一套 Slime 视觉语言：极简页头、细描边分区、
 * 单色强调、紧凑的 13–15px 排版。页面壳、资料编辑与能力配置整体重写；
 * 提示词、后台、语音、编程任务等既有功能面板按原样嵌入，避免功能回退。
 */

import { useCallback, useEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import {
  ApiError,
  createMaPeer,
  createPeerAnchor,
  deleteMaPeer,
  deletePeerAnchor,
  getPeerAnchor,
  getSettings,
  getTerminal,
  listMaPeers,
  updateMaPeer,
  type MaPeer,
  type MaPeerAnchor,
  type ModelView,
  type TerminalTarget,
} from '../../api/client'
import { CODING_AGENT_ID, CORE_AGENT_ID } from '../../api/agentRuntime'
import { AppIcon } from '../../components/AppIcon'
import GraphMini from '../../components/GraphMini'
import PageBoundary from '../../components/PageBoundary'
import PeerAvatar from '../../components/PeerAvatar'
import Toast from '../../components/Toast'
import TtsPanel from '../../components/TtsPanel'
import { resetWindowStates, useDraftState, useRememberedState } from '../../components/windowState'
import AgentBackground, { SUB_TABS, type SubView } from '../AgentBackground'
import PromptsPanel from '../PromptsPanel'
import AgentTasks from '../coding/AgentTasks'
import './agentDetail.css'

/** 路径末段用 new 表示「新建成员」（成员 id 是 uuid，不会撞） */
const NEW_ID = 'new'

/** 角色标签的默认色：新建成员、或没挑过颜色时用它（中性灰，不抢眼） */
const DEFAULT_ROLE_COLOR = '#6b7280'

type RightTab = 'cap' | SubView | 'anchor' | 'prompt' | 'tts' | 'coding'

const BASE_TABS: { key: RightTab; label: string }[] = [
  { key: 'prompt', label: '提示词' },
  { key: 'cap', label: '能力' },
  { key: 'tts', label: '语音' },
  ...SUB_TABS,
  { key: 'anchor', label: '图谱锚点' },
]

const BACKGROUND_TABS: SubView[] = SUB_TABS.map((item) => item.key)

type Draft = {
  name: string
  modelId: string
  note: string
  role: string
  roleColor: string
  avatar: string | null
  terminals: string[]
}

function errMsg(error: unknown): string {
  if (error instanceof ApiError) {
    const raw = error.body?.trim()
    if (raw) {
      try {
        const parsed = JSON.parse(raw) as { message?: string }
        if (parsed.message) return parsed.message
      } catch {
        /* 非 JSON：原样展示 */
      }
      return raw
    }
    return `请求失败（${error.status}）`
  }
  return error instanceof Error ? error.message : String(error)
}

/** 头像存的是文本，先裁成方图并压到 256px，避免把整张原图塞进成员表 */
function fileToAvatarDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(objectUrl)
      const side = 256
      const canvas = document.createElement('canvas')
      canvas.width = side
      canvas.height = side
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        reject(new Error('这个浏览器不支持图片处理'))
        return
      }
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, side, side)
      const scale = Math.max(side / image.width, side / image.height)
      const width = image.width * scale
      const height = image.height * scale
      ctx.drawImage(image, (side - width) / 2, (side - height) / 2, width, height)
      resolve(canvas.toDataURL('image/jpeg', 0.85))
    }
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error('这张图读不出来，换一张试试'))
    }
    image.src = objectUrl
  })
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="ad-field">
      <span className="ad-field__label">{label}</span>
      {children}
      {hint && <span className="ad-field__hint">{hint}</span>}
    </div>
  )
}

function RoleColorPicker({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <label className="ad-color" title="角色标签颜色">
      <span className="ad-color__dot" style={{ background: value }} aria-hidden="true" />
      <input
        type="color"
        aria-label="角色标签颜色"
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function PickCard({
  checked,
  title,
  badge,
  description,
  locked,
  lockedReason,
  onToggle,
}: {
  checked: boolean
  title: string
  badge?: string
  description?: string
  locked?: boolean
  lockedReason?: string
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      role="checkbox"
      aria-checked={checked}
      aria-disabled={locked || undefined}
      title={locked ? lockedReason : description}
      className={`ad-pick${checked ? ' is-on' : ''}${locked ? ' is-locked' : ''}`}
      onClick={locked ? undefined : onToggle}
    >
      <span className="ad-pick__box" aria-hidden="true">
        {checked && <AppIcon name="check" size={11} />}
      </span>
      <span className="ad-pick__body">
        <span className="ad-pick__title">
          <span>{title}</span>
          {badge && <span className="ad-tag">{badge}</span>}
        </span>
        {description && <span className="ad-pick__desc">{description}</span>}
      </span>
    </button>
  )
}

/** 图谱人格锚点：成员与知识图谱锚点实体的绑定 + 该锚点的 1 跳子图 */
function PeerAnchorPanel({
  peerId,
  peerName,
  notify,
}: {
  peerId: string
  peerName: string
  notify: (message: string, kind?: 'ok' | 'err') => void
}) {
  const [view, setView] = useState<MaPeerAnchor | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    void (async () => {
      try {
        const result = await getPeerAnchor(peerId)
        if (alive) setView(result)
      } catch (error) {
        if (alive) notify(errMsg(error), 'err')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [peerId, notify])

  const create = async () => {
    setBusy(true)
    try {
      setView(await createPeerAnchor(peerId))
      notify(`已为「${peerName}」建立图谱人格锚点`, 'ok')
    } catch (error) {
      notify(errMsg(error), 'err')
    } finally {
      setBusy(false)
    }
  }

  const unbind = async () => {
    if (!window.confirm(`解除「${peerName}」的图谱人格锚点？锚点实体及其关系会保留在知识图谱中。`)) return
    setBusy(true)
    try {
      setView(await deletePeerAnchor(peerId))
      notify('已解除图谱人格锚点', 'ok')
    } catch (error) {
      notify(errMsg(error), 'err')
    } finally {
      setBusy(false)
    }
  }

  const anchor = view?.anchor ?? null
  const graph = view?.graph ?? { nodes: [], edges: [] }

  return (
    <div className="ad-sections">
      <div className="ad-section-head">
        <div className="ad-section-title">
          <h2>图谱人格锚点</h2>
          <p className="ad-desc">
            把这位智能体钉在知识图谱的一个锚点实体上——与系统预置的「用户」锚点同一套机制。
            模型会把它的人设、知识、立场挂在这个锚点上，这就是它的第一人称坐标。
          </p>
        </div>
        {anchor && (
          <div className="ad-head-actions">
            <a className="ad-btn" href={`#/graph?entity=${encodeURIComponent(anchor.id)}`}>在图谱中打开</a>
            <button type="button" className="ad-btn" disabled={busy} onClick={() => void unbind()}>解除锚点</button>
          </div>
        )}
      </div>

      {loading ? (
        <p className="ad-empty">载入锚点…</p>
      ) : !anchor ? (
        <div className="ad-empty ad-empty--action">
          <p>还没有锚点。建立后会以「{peerName}」为名在图谱里新建一个锚点实体。</p>
          <button type="button" className="ad-btn ad-btn--primary" disabled={busy} onClick={() => void create()}>
            {busy ? '建立中…' : '新建图谱人格锚点'}
          </button>
        </div>
      ) : (
        <div className="ad-anchor">
          <div className="ad-tags">
            <span className="ad-tag ad-tag--accent">{anchor.name}</span>
            <span className="ad-tag">{anchor.type}</span>
            <span className="ad-hint">一跳邻居 {Math.max(graph.nodes.length - 1, 0)} 个 · 关系 {graph.edges.length} 条</span>
          </div>
          <div className="ad-graph">
            <GraphMini nodes={graph.nodes} edges={graph.edges} emphasized={new Set([anchor.id])} className="h-full w-full" />
          </div>
          {graph.edges.length === 0 && (
            <p className="ad-hint">该锚点还没有任何关系。到图谱页挂上第一条，画像就会长在这里。</p>
          )}
        </div>
      )}
    </div>
  )
}

export default function AgentDetail({ peerId: requestedPeerId }: { peerId: string }) {
  const peerId = requestedPeerId === CODING_AGENT_ID ? CORE_AGENT_ID : requestedPeerId
  const isNew = peerId === NEW_ID
  const coreAgent = peerId === CORE_AGENT_ID

  const [peer, setPeer] = useState<MaPeer | null>(null)
  const [loading, setLoading] = useState(true)
  const [models, setModels] = useState<ModelView[]>([])
  const [workspaceOptions, setWorkspaceOptions] = useState<TerminalTarget[]>([])
  const [toast, setToast] = useState<{ msg: string; kind?: 'ok' | 'err' } | null>(null)

  const [draft, setDraft, draftState] = useDraftState<Draft | null>(`agent:${peerId}:profile`, null)
  const initializeDraft = draftState.initialize
  const [savingProfile, setSavingProfile] = useRememberedState(`agent:${peerId}:saving`, false)
  const [tab, setTab] = useRememberedState<RightTab>(`agent:${peerId}:tab`, isNew ? 'cap' : 'prompt')
  const fileRef = useRef<HTMLInputElement>(null)
  const loadedIdRef = useRef<string | null>(null)

  const notify = useCallback((msg: string, kind?: 'ok' | 'err') => setToast({ msg, kind }), [])

  const reloadPeer = useCallback(async () => {
    if (isNew) return
    try {
      const list = await listMaPeers()
      setPeer(list.find((item) => item.id === peerId) ?? null)
    } catch (error) {
      notify(errMsg(error), 'err')
    }
  }, [isNew, peerId, notify])

  const loadSettings = useCallback(async () => {
    try {
      const settings = await getSettings()
      setModels(settings.models)
    } catch (error) {
      notify(errMsg(error), 'err')
    }
  }, [notify])

  const loadCapabilityOptions = useCallback(async () => {
    try {
      const terminal = await getTerminal()
      setWorkspaceOptions(terminal.targets)
    } catch (error) {
      notify(errMsg(error), 'err')
    }
  }, [notify])

  useEffect(() => {
    void (async () => {
      await Promise.all([reloadPeer(), loadSettings(), loadCapabilityOptions()])
      setLoading(false)
    })()
  }, [reloadPeer, loadSettings, loadCapabilityOptions])

  useEffect(() => {
    if (isNew) {
      initializeDraft({
        name: '', modelId: '', note: '', role: '', roleColor: DEFAULT_ROLE_COLOR,
        avatar: null, terminals: [],
      })
      return
    }
    if (!peer || loadedIdRef.current === peer.id) return
    loadedIdRef.current = peer.id
    initializeDraft({
      name: peer.name,
      modelId: peer.modelId ?? '',
      note: peer.note ?? '',
      role: peer.role ?? '',
      roleColor: peer.roleColor ?? DEFAULT_ROLE_COLOR,
      avatar: peer.avatar,
      terminals: peer.terminals ?? [],
    })
  }, [peer, isNew, initializeDraft])

  const onFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    try {
      const dataUrl = await fileToAvatarDataUrl(file)
      setDraft((current) => (current ? { ...current, avatar: dataUrl } : current))
    } catch (error) {
      notify(errMsg(error), 'err')
    }
  }

  const toggleTerminal = (id: string) =>
    setDraft((current) => {
      if (!current || coreAgent) return current
      const values = current.terminals
      return { ...current, terminals: values.includes(id) ? values.filter((item) => item !== id) : [...values, id] }
    })

  const saveProfile = async () => {
    if (!draft || savingProfile) return
    const name = draft.name.trim()
    if (!name) {
      notify('先给成员起个名字（它会用在 @ 里）', 'err')
      return
    }
    setSavingProfile(true)
    try {
      if (peer) {
        await updateMaPeer(peer.id, {
          name,
          modelId: draft.modelId,
          note: draft.note,
          role: draft.role,
          roleColor: draft.roleColor,
          avatar: draft.avatar ?? '',
          terminals: coreAgent ? undefined : draft.terminals,
        })
        draftState.accept({ ...draft, name }, draft)
        notify(`「${name}」的资料已保存`, 'ok')
        await reloadPeer()
      } else {
        const created = await createMaPeer({
          name,
          modelId: draft.modelId,
          note: draft.note,
          role: draft.role,
          roleColor: draft.roleColor,
          avatar: draft.avatar ?? undefined,
          terminals: draft.terminals,
        })
        draftState.accept(null, draft)
        window.location.assign(`#/multi-agent/${encodeURIComponent(created.id)}`)
      }
    } catch (error) {
      notify(errMsg(error), 'err')
    } finally {
      setSavingProfile(false)
    }
  }

  const remove = async () => {
    if (!peer) return
    if (!window.confirm(`把「${peer.name}」移出圆桌？`)) return
    try {
      await deleteMaPeer(peer.id)
      resetWindowStates(`agent:${peer.id}:`, `prompt:${peer.id}:`, `prompts:${peer.id}:`)
      window.location.assign('#/multi-agent')
    } catch (error) {
      notify(errMsg(error), 'err')
    }
  }

  if (loading) {
    return <div className="ad-page"><p className="ad-empty" role="status">载入成员资料…</p></div>
  }

  if (!isNew && !peer) {
    return (
      <div className="ad-page">
        <p className="ad-empty">这位成员不存在，或已被移出圆桌。</p>
        <a className="ad-btn" href="#/multi-agent">返回成员列表</a>
      </div>
    )
  }

  const preview = draft?.name?.trim() || peer?.name || (isNew ? '新成员' : '成员')
  const roleValue = draft?.role?.trim() ?? ''
  const roleColor = draft?.roleColor || DEFAULT_ROLE_COLOR
  const modelName = models.find((model) => model.id === draft?.modelId)?.name
  const tabs = coreAgent ? [...BASE_TABS, { key: 'coding' as const, label: '编程任务' }] : BASE_TABS

  return (
    <div className="ad-page" data-page="agent">
      <header className="ad-hero">
        <div className="ad-hero__identity">
          <PeerAvatar name={preview} avatar={draft?.avatar ?? null} size="h-14 w-14" textClass="text-xl" />
          <div className="ad-hero__text">
            <div className="ad-hero__title">
              <h1>{preview}</h1>
              {coreAgent && <span className="ad-tag">系统主控</span>}
              {!coreAgent && peer?.builtin && <span className="ad-tag">内置</span>}
              {roleValue && (
                <span className="ad-role"><i style={{ background: roleColor }} aria-hidden="true" />{roleValue}</span>
              )}
            </div>
            <p className="ad-hero__meta">
              模型 · {modelName ?? '跟随默认设置'}
              {draft?.note?.trim() ? ` · ${draft.note.trim()}` : ''}
            </p>
          </div>
        </div>
        <div className="ad-hero__actions">
          {coreAgent && <a className="ad-btn" href="#/coding">编程工作台 ↗</a>}
          <button
            type="button"
            className="ad-btn ad-btn--primary"
            disabled={savingProfile}
            onClick={() => void saveProfile()}
          >
            {savingProfile ? '保存中…' : peer ? '保存资料' : '加入圆桌'}
          </button>
        </div>
      </header>

      {draftState.dirty && (
        <div className="ad-dirty" role="status">
          <span>资料有未保存的修改</span>
          <button type="button" className="ad-linkbtn" disabled={savingProfile} onClick={draftState.discard}>
            放弃修改
          </button>
        </div>
      )}

      <PageBoundary>
        <div className="ad-layout">
          <aside className="ad-profile" aria-label="成员资料">
            <div className="ad-avatar">
              <button
                type="button"
                className="ad-avatar__button"
                title="点击上传头像"
                aria-label="上传或更换头像"
                onClick={() => fileRef.current?.click()}
              >
                <PeerAvatar name={preview} avatar={draft?.avatar ?? null} size="h-20 w-20" textClass="text-[26px]" />
                <span className="ad-avatar__overlay">更换</span>
              </button>
              <div className="ad-avatar__actions">
                <button type="button" className="ad-linkbtn" onClick={() => fileRef.current?.click()}>上传头像</button>
                {draft?.avatar && (
                  <button
                    type="button"
                    className="ad-linkbtn"
                    onClick={() => setDraft((current) => (current ? { ...current, avatar: null } : current))}
                  >
                    移除
                  </button>
                )}
              </div>
              <p className="ad-hint">PNG / JPG，会自动裁成方图并压缩</p>
            </div>

            <Field label="名字" hint="发言时被 @ 的名字，圆桌上唯一">
              <input
                className="ad-input"
                aria-label="名字"
                value={draft?.name ?? ''}
                placeholder="如：张三"
                onChange={(event) => setDraft((current) => (current ? { ...current, name: event.target.value } : current))}
              />
            </Field>

            <Field label="角色" hint="执行态卡片右上角的小标签；留空则不显示">
              <span className="ad-inline">
                <input
                  className="ad-input"
                  aria-label="角色"
                  value={draft?.role ?? ''}
                  placeholder="如：产品经理"
                  onChange={(event) => setDraft((current) => (current ? { ...current, role: event.target.value } : current))}
                />
                <RoleColorPicker
                  value={roleColor}
                  onChange={(value) => setDraft((current) => (current ? { ...current, roleColor: value } : current))}
                />
              </span>
            </Field>

            <Field label="模型" hint="不指定则跟随设置页默认">
              <select
                className="ad-input"
                aria-label="模型"
                value={draft?.modelId ?? ''}
                onChange={(event) => setDraft((current) => (current ? { ...current, modelId: event.target.value } : current))}
              >
                <option value="">跟随设置页默认</option>
                {models.map((model) => <option key={model.id} value={model.id}>{model.name}</option>)}
              </select>
            </Field>

            <Field label="备注" hint="写给人看的一句话，不会注入模型">
              <textarea
                className="ad-input ad-textarea"
                aria-label="备注"
                rows={3}
                value={draft?.note ?? ''}
                placeholder="如：负责挑刺的那位"
                onChange={(event) => setDraft((current) => (current ? { ...current, note: event.target.value } : current))}
              />
            </Field>

            {peer && !peer.builtin && (
              <div className="ad-danger">
                <button type="button" className="ad-btn ad-btn--danger" onClick={() => void remove()}>移出圆桌</button>
                <p className="ad-hint">移出后，它自己那套提示词（版本 / 生效项 / 顺序）会一并清除。</p>
              </div>
            )}
          </aside>

          <section className="ad-main" aria-label="成员配置">
            <div className="ad-tabs" role="group" aria-label="配置分类">
              {tabs.map((item) => (
                <button
                  key={item.key}
                  type="button"
                  aria-pressed={tab === item.key}
                  onClick={() => setTab(item.key)}
                >
                  {item.label}
                </button>
              ))}
            </div>

            <div className="ad-panel">
              {coreAgent && tab === 'coding' && <AgentTasks />}

              {tab === 'tts' && (peer ? <div className="ad-embed"><TtsPanel agentId={peer.id} /></div> : <p className="ad-empty">请先保存 Agent，再配置语音档案。</p>)}

              {tab === 'cap' && (
                <div className="ad-sections">
                  <p className="ad-desc">
                    {coreAgent
                      ? '内置 Agent 是系统主控，直接代表软件向你负责，协调其他 Agent 并检查交付。全部能力、工具和工作空间自动开放，无需逐项授权。'
                      : '所有智能体默认获得全部可用工具（圆桌发言时另有 speak / 状态机工具）。绑定工作空间后它才能执行命令，且只能访问绑定的这几台。'}
                  </p>

                  <section className="ad-section">
                    <div className="ad-section-head">
                      <h2>工作空间</h2>
                      <span className="ad-count">{workspaceOptions.length} 个</span>
                    </div>
                    {workspaceOptions.length === 0 ? (
                      <p className="ad-empty">还没有工作空间。先到「工作空间」页添加一个（docker 容器或 SSH 服务器）。</p>
                    ) : (
                      <div className="ad-grid">
                        {workspaceOptions.map((target) => (
                          <PickCard
                            key={target.id}
                            checked={coreAgent || (draft?.terminals ?? []).includes(target.id)}
                            locked={coreAgent}
                            lockedReason="系统主控自动拥有全部工作空间，无需绑定"
                            title={target.name}
                            badge={target.kind}
                            description={target.enabled ? undefined : '未启用'}
                            onToggle={() => toggleTerminal(target.id)}
                          />
                        ))}
                      </div>
                    )}
                    <p className="ad-hint">
                      {coreAgent ? '全部工作空间自动纳入访问范围；连接与启用状态在工作空间页管理。' : '勾选即授予 run_command / list_terminals，且只在这几台上执行。'}
                    </p>
                  </section>
                </div>
              )}

              {BACKGROUND_TABS.includes(tab as SubView) && (
                peer ? (
                  <div className="ad-embed">
                    <AgentBackground peerId={peer.id} peerName={peer.name} models={models} view={tab as SubView} hideTabs />
                  </div>
                ) : <p className="ad-empty">先把这位成员加入圆桌，再回来配置它的后台。</p>
              )}

              {tab === 'anchor' && (
                peer
                  ? <PeerAnchorPanel peerId={peer.id} peerName={peer.name} notify={notify} />
                  : <p className="ad-empty">先把这位成员加入圆桌，再回来建立它的图谱人格锚点。</p>
              )}

              {tab === 'prompt' && (
                peer
                  ? <div className="ad-embed ad-embed--prompt"><PromptsPanel peerId={peer.id} /></div>
                  : (
                    <div className="ad-sections">
                      <p className="ad-desc">
                        每位智能体自己一套「对话」提示词（身份 / 增强 / 终端 / 子代理），
                        与首页对话的全局提示词隔离；「圆桌」那一类是全桌共用的协作规程。
                      </p>
                      <p className="ad-empty">先把这位成员加入圆桌，再回来为它配置自己的提示词。</p>
                    </div>
                  )
              )}
            </div>
          </section>
        </div>
      </PageBoundary>

      <input
        ref={fileRef}
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif"
        className="hidden"
        onChange={(event) => void onFile(event)}
      />

      <Toast message={toast?.msg ?? null} kind={toast?.kind === 'err' ? 'error' : 'success'} onClose={() => setToast(null)} />
    </div>
  )
}
