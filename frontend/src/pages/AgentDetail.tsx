/**
 * ARCHIVED —— 旧版智能体详情页（旧 UI），仅作存档，不再被任何路由或组件引用。
 * 现行页面见 pages/agent/AgentDetail.tsx。
 *
 * 智能体内部页（{@code /multi-agent/{id}}）：一位成员的完整资料 + 它自己那套「对话」提示词。
 *
 * <p>从成员页的卡片点进来，独占一整页：左侧放资料（头像 / 名字 / 模型 / 备注），右侧是一个 tab 容器，
 * 点标签切换显示<b>能力</b> / <b>图谱人格锚点</b> / <b>提示词 &amp; 上下文工程</b>——三块内容都不短，
 * 竖着堆会拖成一条长栏。提示词面板与潜意识页那张「对话」tab 是同一套编排 UI，只是数据换成这位成员
 * 自己的：改这里只影响它，不碰首页对话，也不碰别的成员。
 *
 * <p>内置主 Agent 自动拥有全部工作空间；能力页展示实际范围，不保存授权快照。
 * 所有智能体默认获得全部可用工具；成员的「能力」逐成员只配<b>工作空间</b>
 * （绑定 {@code term_target}；绑了才授予 {@code run_command} / {@code list_terminals}，
 * 且只能访问绑定的这几台）。它与左侧资料共用同一份草稿，因此只有一个保存动作。
 *
 * <p>三个动作各归其位：<b>返回上级</b>在页头左侧（回成员列表，取代了原先的「取消」按钮）；
 * <b>保存</b>在页头那一行的最右端（与导航同处一行，不占正文也不悬空）；
 * <b>移出圆桌</b>收在左侧资料卡片底部（成员的破坏性动作，与右栏 tab 无关）。
 *
 * <p>提示词面板由 {@link PromptsPanel}（传 {@code peerId}）承载：版本、生效项、拼装顺序都落在
 * {@code ma_peer_prompt_*} 上；没配过的提示词位自动回退全局（提示词页绑定 → 内置），
 * 所以新成员开箱即用。
 *
 * <p>头像走库存储：选了图先在前端裁成方图并压到 256px，转成 data URL 再随成员一起存。
 */

import { AppIcon } from '../components/AppIcon'
import { useCallback, useEffect, useRef, useState } from 'react'
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
} from '../api/client'
import { btnOutline, btnPrimary, btnQuiet, miniBtn, Control } from '../components/ui'
import { Select } from '../components/formKit'
import { Card, CardHead } from '../components/Card'
import GraphMini from '../components/GraphMini'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import { DraftNotice } from '../components/DraftNotice'
import { resetWindowStates, useDraftState, useRememberedState } from '../components/windowState'
import AgentBackground, { SUB_TABS, type SubView } from './AgentBackground'
import PromptsPanel from './PromptsPanel'
import TtsPanel from '../components/TtsPanel'
import { CODING_AGENT_ID, CORE_AGENT_ID } from '../api/agentRuntime'
import AgentTasks from './coding/AgentTasks'

/** 路径末段用 new 表示「新建成员」（成员 id 是 uuid，不会撞） */
const NEW_ID = 'new'

/** 右栏 tab：提示词（最优先配置，放最左）/ 能力 / 后台各子页（心跳·常驻职责·行动日志·RAG 召回）/
 *  图谱人格锚点。各块都不短，切换显示而不是竖着堆成一整条长栏 */
type RightTab = 'cap' | SubView | 'anchor' | 'prompt' | 'tts' | 'coding'

const RIGHT_TABS: { key: RightTab; label: string }[] = [
  { key: 'prompt', label: '提示词 & 上下文工程' },
  { key: 'cap', label: '能力' },
  { key: 'tts', label: '语音配置' },
  ...SUB_TABS,
  { key: 'anchor', label: '图谱人格锚点' },
]

/** 后台各子页的 key：顶层 Tab 命中它们时，渲染受控的 {@link AgentBackground} */
const BACKGROUND_TABS: SubView[] = SUB_TABS.map((t) => t.key)

type Option = { value: string; label: string }

/** 角色标签的默认色：新建成员、或没挑过颜色时用它（中性灰，不抢眼） */
const DEFAULT_ROLE_COLOR = '#6b7280'

type Draft = {
  name: string
  modelId: string
  note: string
  /** 角色：展示用的一句话岗位（如「产品经理」）；执行态卡片右上角的小标签 */
  role: string
  /** 角色标签的颜色（#RRGGBB） */
  roleColor: string
  avatar: string | null
  /** 绑定的工作空间（term_target.id）；非空才授予 run_command / list_terminals */
  terminals: string[]
}

/**
 * 角色标签的颜色拾取：一块可点的色板（Tailwind 风格）。
 *
 * <p>原生 {@code <input type=color>} 在各浏览器的外观差异极大、也不跟主题走，
 * 于是把它藏进 label 里、外观自己画——点色板即唤起系统取色器，键盘可达（保留 aria 标签）。
 */
function RoleColorPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <label
      title="角色标签的颜色"
      className="relative inline-flex w-9 shrink-0 cursor-pointer items-center justify-center self-stretch rounded-md border border-line bg-bg transition hover:border-line-strong focus-within:border-secondary focus-within:ring-2 focus-within:ring-secondary/30"
    >
      <span
        aria-hidden="true"
        className="h-5 w-5 rounded-full border border-black/10"
        style={{ background: value }}
      />
      <input
        type="color"
        aria-label="角色标签颜色"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
      />
    </label>
  )
}

/**
 * 一项可勾选的能力卡片（工作空间）：勾选态用色块与描边表示，整卡可点。
 *
 * <p>不用 {@link Field}（它渲染 {@code <label>}）：label 会把点击转给内部第一个 labelable 元素，
 * 一格里放多个按钮时会误触第一项。这里自己写同款小标题。
 *
 * <p>{@code locked} 给「系统主控自动拥有」的工作空间用：勾选态固定为选上、点击不再切换。
 */
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
  /** 主标题（工作空间名） */
  title: string
  /** 右上角小角标（工作空间用 kind：docker / ssh） */
  badge?: string
  /** 副文本（工作空间用「未启用」等提示），同时作为悬停提示 */
  description?: string
  /** 锁定：系统主控自动拥有，点不动（勾选态由外部定死） */
  locked?: boolean
  lockedReason?: string
  onToggle: () => void
}) {
  const kit = usePageKit()
  return (
    <button
      type="button"
      title={locked ? `${lockedReason ?? '系统主控自动拥有，无需勾选'}${description ? `\n${description}` : ''}` : description}
      onClick={locked ? undefined : onToggle}
      aria-disabled={locked || undefined}
      aria-pressed={checked}
      className={`flex flex-col gap-1.5 rounded-xl border p-3 text-left transition ${
        checked ? 'border-accent bg-accent-soft' : 'border-line hover:border-line-strong'
      } ${locked ? 'cursor-default' : ''}`}
    >
      <span className="flex w-full items-center gap-2">
        <span
          className={`h-3.5 w-3.5 shrink-0 rounded-[4px] border transition ${
            /* 选中格补一枚主题光晕：shadow-md 在终末地映射为黄光晕 */
            checked ? 'border-accent bg-accent shadow-md' : 'border-line-strong'
          }`}
        />
        <span
          className="min-w-0 flex-1 break-all text-md font-medium text-fg sm:text-base"
        >
          {title}
        </span>
        {badge && <kit.Badge>{badge}</kit.Badge>}
      </span>
      {description && (
        <span className="line-clamp-3 text-md leading-relaxed text-muted sm:text-base">{description}</span>
      )}
    </button>
  )
}

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

/**
 * 把选中的图片裁成正方形并压到 256px，转成 JPEG data URL。
 * 头像存的是文本，必须先压下来；居中裁剪（cover）保证不变形。
 */
function fileToAvatarDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
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
      /* JPEG 没有透明通道：先铺白底，免得 PNG 的透明区变成黑块 */
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, side, side)
      const scale = Math.max(side / img.width, side / img.height)
      const w = img.width * scale
      const h = img.height * scale
      ctx.drawImage(img, (side - w) / 2, (side - h) / 2, w, h)
      resolve(canvas.toDataURL('image/jpeg', 0.85))
    }
    img.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      reject(new Error('这张图读不出来，换一张试试'))
    }
    img.src = objectUrl
  })
}

/**
 * 图谱人格锚点：把这位成员钉在知识图谱的一个锚点实体上（与系统预置的「用户」锚点同一套机制）。
 * 没绑过 → 一个「新建」按钮（以成员名落一个锚点实体）；绑过了 → 直接展示该锚点的 1 跳子图，
 * 这张小图就是这位智能体人设与知识的圆心。解除绑定只断开关联，锚点实体仍留在图谱里。
 *
 * <p>不带卡片外框：它作为右栏「图谱人格锚点」tab 的内容渲染，外框由 tab 容器统一给。
 */
function PeerAnchorPanel({
  peerId,
  peerName,
  notify,
}: {
  peerId: string
  peerName: string
  notify: (msg: string, kind?: 'ok' | 'err') => void
}) {
  const kit = usePageKit()
  const [view, setView] = useState<MaPeerAnchor | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    setLoading(true)
    void (async () => {
      try {
        const v = await getPeerAnchor(peerId)
        if (alive) setView(v)
      } catch (e) {
        if (alive) notify(errMsg(e), 'err')
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
    } catch (e) {
      notify(errMsg(e), 'err')
    } finally {
      setBusy(false)
    }
  }

  const unbind = async () => {
    if (!window.confirm(`解除「${peerName}」的图谱人格锚点？锚点实体及其关系会保留在知识图谱中。`)) {
      return
    }
    setBusy(true)
    try {
      setView(await deletePeerAnchor(peerId))
      notify('已解除图谱人格锚点', 'ok')
    } catch (e) {
      notify(errMsg(e), 'err')
    } finally {
      setBusy(false)
    }
  }

  const anchor = view?.anchor ?? null
  const graph = view?.graph ?? { nodes: [], edges: [] }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="ui-heading text-md">图谱人格锚点</h2>
          <p className="mt-1 text-xs leading-relaxed text-muted">
            把这位智能体钉在知识图谱的一个锚点实体上——与系统预置的「用户」锚点同一套机制。
            模型会把它的人设、知识、立场这些关系挂在这个锚点上，这就是它的第一人称坐标。
          </p>
        </div>
        {anchor && (
          <div className="flex shrink-0 items-center gap-2">
            <a href={`#/graph?entity=${encodeURIComponent(anchor.id)}`} className={miniBtn}>
              在图谱中打开
            </a>
            <button type="button" className={miniBtn} disabled={busy} onClick={() => void unbind()}>
              解除锚点
            </button>
          </div>
        )}
      </div>

      {loading ? (
        <p className="text-xs text-hint">载入锚点…</p>
      ) : !anchor ? (
        <div className="flex flex-col items-start gap-3">
          <kit.Empty>还没有锚点。建立后会以「{peerName}」为名在图谱里新建一个锚点实体。</kit.Empty>
          <button
            type="button"
            className={btnPrimary('rounded-lg')}
            disabled={busy}
            onClick={() => void create()}
          >
            {busy ? '建立中…' : '新建图谱人格锚点'}
          </button>
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <kit.Badge tone="accent">{anchor.name}</kit.Badge>
            <kit.Badge>{anchor.type}</kit.Badge>
            <span className="text-hint">
              一跳邻居 {Math.max(graph.nodes.length - 1, 0)} 个 · 关系 {graph.edges.length} 条
            </span>
          </div>
          {/* 没有关系时也照常渲染这张图：锚点自身就是一个点，画像正是从这里长出来的。
              图随内容撑满右栏剩余高度：容器 flex-1 + min-h-0，SVG 铺满容器；
              select-none 关掉浏览器默认的拖动选取（免得拖着看图时把节点文字选蓝） */}
          <div className="flex min-h-40 w-full flex-1 select-none items-center justify-center rounded-xl border border-line bg-bg/40 p-1">
            <GraphMini
              nodes={graph.nodes}
              edges={graph.edges}
              emphasized={new Set([anchor.id])}
              className="h-full w-full"
            />
          </div>
          {graph.edges.length === 0 && (
            <p className="text-center text-xs text-hint">
              该锚点还没有任何关系。到图谱页挂上第一条，画像就会长在这里。
            </p>
          )}
        </div>
      )}
    </div>
  )
}

export default function AgentDetail({ peerId: requestedPeerId }: { peerId: string }) {
  const peerId = requestedPeerId === CODING_AGENT_ID ? CORE_AGENT_ID : requestedPeerId
  const kit = usePageKit()
  const isNew = peerId === NEW_ID
  const coreAgent = peerId === CORE_AGENT_ID
  const [peer, setPeer] = useState<MaPeer | null>(null)
  const [loading, setLoading] = useState(true)
  const [modelOptions, setModelOptions] = useState<Option[]>([])
  /** 原始模型清单（后台心跳的模型下拉要的是 ModelView，不是 Option） */
  const [models, setModels] = useState<ModelView[]>([])
  /** 能力面的候选：可绑定的工作空间（来自工作空间管理页，这里只做选择） */
  const [workspaceOptions, setWorkspaceOptions] = useState<TerminalTarget[]>([])
  const [toast, setToast] = useState<{ msg: string; kind?: 'ok' | 'err' } | null>(null)

  const [draft, setDraft, draftState] = useDraftState<Draft | null>(`agent:${peerId}:profile`, null)
  const initializeDraft = draftState.initialize
  const [savingProfile, setSavingProfile] = useRememberedState(`agent:${peerId}:saving`, false)
  /** 右栏当前显示哪一块：已有成员缺省停在「提示词」（最先要配的身份设定，放最左）；
   *  新建成员还没有 peer，提示词无从配起，先落在「能力」把资料与工作空间配上 */
  const [tab, setTab] = useRememberedState<RightTab>(`agent:${peerId}:tab`, isNew ? 'cap' : 'prompt')
  const fileRef = useRef<HTMLInputElement>(null)
  /** 草稿只在「首次载入这位成员」时初始化，之后刷新资料不覆盖用户正在改的内容 */
  const loadedIdRef = useRef<string | null>(null)

  const notify = useCallback((msg: string, kind?: 'ok' | 'err') => setToast({ msg, kind }), [])

  const reloadPeer = useCallback(async () => {
    if (isNew) return
    try {
      const list = await listMaPeers()
      setPeer(list.find((p) => p.id === peerId) ?? null)
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [isNew, peerId, notify])

  const loadModelOptions = useCallback(async () => {
    try {
      const settings = await getSettings()
      setModels(settings.models)
      setModelOptions([
        { value: '', label: '跟随设置页默认' },
        ...settings.models.map((m) => ({ value: m.id, label: m.name })),
      ])
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  /**
   * 能力面的候选清单：工作空间复用终端模块的全量目标（含未启用的，页面上标注出来）。
   */
  const loadCapabilityOptions = useCallback(async () => {
    try {
      const terminal = await getTerminal()
      setWorkspaceOptions(terminal.targets)
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  useEffect(() => {
    void (async () => {
      await Promise.all([reloadPeer(), loadModelOptions(), loadCapabilityOptions()])
      setLoading(false)
    })()
  }, [reloadPeer, loadModelOptions, loadCapabilityOptions])

  /* 草稿初始化：新建给空表；编辑等这位成员到手后填一次 */
  useEffect(() => {
    if (isNew) {
      initializeDraft({
        name: '',
        modelId: '',
        note: '',
        role: '',
        roleColor: DEFAULT_ROLE_COLOR,
        avatar: null,
        terminals: [],
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

  const onFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    /* 先清空取值：同一张图再选一次也要能触发 change */
    e.target.value = ''
    if (!file) return
    try {
      const dataUrl = await fileToAvatarDataUrl(file)
      setDraft((d) => (d ? { ...d, avatar: dataUrl } : d))
    } catch (err) {
      notify(errMsg(err), 'err')
    }
  }

  /** 勾选 / 取消勾选一项工作空间：整份清单随成员一起保存 */
  const toggleTerminal = (id: string) =>
    setDraft((d) => {
      if (!d || coreAgent) return d
      const cur = d.terminals
      return { ...d, terminals: cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id] }
    })

  const saveProfile = async () => {
    if (!draft || savingProfile) return
    const clean = draft.name.trim()
    if (!clean) {
      notify('先给成员起个名字（它会用在 @ 里）', 'err')
      return
    }
    setSavingProfile(true)
    try {
      if (peer) {
        /* 空串是「清空」的语言：modelId 置空 = 回到跟随默认，avatar 置空 = 移除头像；
           工作空间传数组，空数组即清空这层能力 */
        await updateMaPeer(peer.id, {
          name: clean,
          modelId: draft.modelId,
          note: draft.note,
          role: draft.role,
          roleColor: draft.roleColor,
          avatar: draft.avatar ?? '',
          terminals: coreAgent ? undefined : draft.terminals,
        })
        draftState.accept({ ...draft, name: clean }, draft)
        notify(`「${clean}」的资料已保存`, 'ok')
        await reloadPeer()
      } else {
        const created = await createMaPeer({
          name: clean,
          modelId: draft.modelId,
          note: draft.note,
          role: draft.role,
          roleColor: draft.roleColor,
          avatar: draft.avatar ?? undefined,
          terminals: draft.terminals,
        })
        /* 建好了就换到它自己的内部页（换掉地址而非 push，免得后退又回到新建态） */
        draftState.accept(null, draft)
        window.location.assign(`#/multi-agent/${encodeURIComponent(created.id)}`)
      }
    } catch (e) {
      notify(errMsg(e), 'err')
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
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }

  if (loading) {
    return (
      <kit.Page title={isNew ? '新建成员' : '成员'} en="AGENT">
        <p className="text-sm text-hint">载入成员资料…</p>
      </kit.Page>
    )
  }

  if (!isNew && !peer) {
    return (
      <kit.Page title="成员" en="AGENT">
        <kit.Empty>这位成员不存在，或已被移出圆桌。</kit.Empty>
        <a href="#/multi-agent" className={`${btnOutline('rounded-lg')} self-start`}>
          返回成员列表
        </a>
      </kit.Page>
    )
  }

  const preview = draft?.name ?? peer?.name ?? ''

  return (
    <kit.Page
      title={isNew ? '新建成员' : (peer?.name ?? '成员')}
      en="AGENT"
      meta={
        /* 返回上级给一条明确的回路（原先的「取消」按钮由它取代）；保存挂页头最右端，
           与导航同处一行，不占正文也不悬空 */
        <span className="flex items-center gap-2.5">
          {coreAgent && <a href="#/coding" className={miniBtn}>编程工作台 ↗</a>}
          <a href="#/multi-agent" className={miniBtn} title="返回多智能体成员列表">
            <AppIcon name="chevron-left" className="h-3.5 w-3.5" />
            返回上级
          </a>
          <button
            type="button"
            className={btnPrimary('rounded-sm')}
            disabled={savingProfile}
            onClick={() => void saveProfile()}
          >
            {savingProfile ? '保存中…' : peer ? '保存资料' : '加入圆桌'}
          </button>
        </span>
      }
    >
      <DraftNotice dirty={draftState.dirty} onDiscard={draftState.discard} disabled={savingProfile} />
      <PageBoundary>
        <div className="grid gap-5 lg:grid-cols-[320px_1fr]">
          {/* 左：资料 */}
          <Card pad="none" className="h-fit">
            <CardHead
              title="资料"
              meta={coreAgent ? <kit.Badge>系统主控</kit.Badge> : peer?.builtin ? <kit.Badge>内置</kit.Badge> : undefined}
            />
            <div className="flex flex-col gap-5 p-5">
              {coreAgent && (
                <p className="text-sm leading-relaxed text-muted">
                  对话与编程 IDE 共用这份身份和模型。编程规程在「提示词 & 上下文工程 → Skills · 状态化提示词」中维护，仅在 IDE 项目任务中启用。
                </p>
              )}
              <div className="flex flex-col items-center gap-3">
                <button
                  type="button"
                  onClick={() => fileRef.current?.click()}
                  title="点击上传头像"
                  aria-label="上传或更换头像"
                  className="group relative shrink-0 cursor-pointer rounded-full"
                >
                  <PeerAvatar
                    name={preview || '新'}
                    avatar={draft?.avatar ?? null}
                    size="h-28 w-28"
                    textClass="text-[36px]"
                  />
                  <span className="absolute inset-0 flex items-center justify-center rounded-full bg-black/45 text-sm font-medium text-white opacity-0 transition group-hover:opacity-100">
                    换头像
                  </span>
                </button>
                <div className="flex items-center gap-2">
                  <button type="button" className={miniBtn} onClick={() => fileRef.current?.click()}>
                    上传头像
                  </button>
                  {draft?.avatar && (
                    <button
                      type="button"
                      className={miniBtn}
                      onClick={() => setDraft((d) => (d ? { ...d, avatar: null } : d))}
                    >
                      移除
                    </button>
                  )}
                </div>
                <p className="text-center text-2xs text-hint">
                  PNG / JPG，会自动裁成方图并压缩
                </p>
              </div>

              <kit.Field label="名字" hint="发言时被 @ 的名字，圆桌上唯一">
                <input
                  value={draft?.name ?? ''}
                  placeholder="如：张三"
                  onChange={(e) => setDraft((d) => (d ? { ...d, name: e.target.value } : d))}
                />
              </kit.Field>

              <kit.Field label="角色" hint="执行态卡片右上角的小标签；留空则不显示" wrap={false}>
                <div className="flex items-center gap-2">
                  <Control className="min-w-0 flex-1">
                    <input
                      value={draft?.role ?? ''}
                      placeholder="如：产品经理"
                      onChange={(e) => setDraft((d) => (d ? { ...d, role: e.target.value } : d))}
                    />
                  </Control>
                  <RoleColorPicker
                    value={draft?.roleColor || DEFAULT_ROLE_COLOR}
                    onChange={(v) => setDraft((d) => (d ? { ...d, roleColor: v } : d))}
                  />
                </div>
              </kit.Field>

              <kit.Field label="模型" hint="不指定则跟随设置页默认" wrap={false}>
                <Select
                  value={draft?.modelId ?? ''}
                  onChange={(v) => setDraft((d) => (d ? { ...d, modelId: v } : d))}
                  options={modelOptions}
                />
              </kit.Field>

              <kit.Field label="备注" hint="写给人看的一句话，不会注入模型">
                <textarea
                  className="min-h-[72px]"
                  rows={3}
                  value={draft?.note ?? ''}
                  placeholder="如：负责挑刺的那位"
                  onChange={(e) => setDraft((d) => (d ? { ...d, note: e.target.value } : d))}
                />
              </kit.Field>

              {/* 移出圆桌：成员的破坏性动作，收在资料卡片底部（不与页头的保存动作抢位置）；
                  内置 Agent 不可删除，故整块不出现 */}
              {peer && !peer.builtin && (
                <div className="flex flex-col items-start gap-2 border-t border-line pt-4">
                  <button
                    type="button"
                    className={btnQuiet('rounded-lg')}
                    onClick={() => void remove()}
                  >
                    移出圆桌
                  </button>
                  <span className="text-2xs leading-relaxed text-hint">
                    移出后，它自己那套提示词（版本 / 生效项 / 顺序）会一并清除。
                  </span>
                </div>
              )}
            </div>
          </Card>

          {/* 右：能力 / 后台各子页 / 图谱人格锚点 / 提示词——各块都不短，用 tab 切换，免得竖着堆成一条长栏。
              tab 选中 = 主行动钮（黄色实底印章），未选中走安静钮 */}
          <Card pad="none" className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 border-b border-line px-3.5 pb-2.5 pt-3">
              {(coreAgent ? [...RIGHT_TABS, { key: 'coding' as const, label: '编程项目与任务' }] : RIGHT_TABS).map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTab(t.key)}
                  className={`ui-btn ui-btn--sm ${
                    tab === t.key ? 'ui-btn--primary' : 'ui-btn--quiet'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <div className="p-5">
            {coreAgent && tab === 'coding' && <AgentTasks />}
            {/* 能力：所有智能体默认获得全部工具；逐成员可配的只有可访问的工作空间（绑了才给终端工具） */}
            {tab === 'tts' && (peer ? <TtsPanel agentId={peer.id} /> : <kit.Empty>请先保存 Agent，再配置语音档案。</kit.Empty>)}
            {tab === 'cap' && (
              <div className="flex flex-col gap-5">
                <p className="text-md leading-relaxed text-muted">
                  {coreAgent ? '内置 Agent 是系统主控，直接代表软件向你负责，协调其他 Agent 并检查交付。全部能力、工具和工作空间自动开放，无需逐项授权。' : '所有智能体默认获得全部可用工具（圆桌发言时另有 speak / 状态机工具）。绑定工作空间后它才能执行命令，且只能访问绑定的这几台。'}
                </p>

                <div>
                  <span className="ui-label mb-2 block">工作空间</span>
                  {workspaceOptions.length === 0 ? (
                    <kit.Empty>
                      还没有工作空间。先到「工作空间」页添加一个（docker 容器或 SSH 服务器）。
                    </kit.Empty>
                  ) : (
                    <div className="scrollbar-slim grid max-h-72 gap-2 overflow-y-auto pr-0.5 sm:grid-cols-2">
                      {workspaceOptions.map((t) => (
                        <PickCard
                          key={t.id}
                          checked={coreAgent || (draft?.terminals ?? []).includes(t.id)}
                          locked={coreAgent}
                          lockedReason="系统主控自动拥有全部工作空间，无需绑定"
                          title={t.name}
                          badge={t.kind}
                          description={t.enabled ? undefined : '未启用'}
                          onToggle={() => toggleTerminal(t.id)}
                        />
                      ))}
                    </div>
                  )}
                  <span className="mt-2 block text-xs text-hint">
                    {coreAgent ? '全部工作空间自动纳入访问范围；连接与启用状态在工作空间页管理。' : '勾选即授予 run_command / list_terminals，且只在这几台上执行。'}
                  </span>
                </div>
              </div>
            )}

            {/* 后台各子页（心跳 / 常驻职责 / 子代理 / RAG 召回 / 任务）已提到与「能力」同级的顶层 Tab；
                这几块共用一个受控的 AgentBackground 实例，切换子页时它的 state（执行详情浮窗等）不丢 */}
            {BACKGROUND_TABS.includes(tab as SubView) &&
              (peer ? (
                <AgentBackground
                  peerId={peer.id}
                  peerName={peer.name}
                  models={models}
                  view={tab as SubView}
                  hideTabs
                />
              ) : (
                <kit.Empty>先把这位成员加入圆桌，再回来配置它的后台。</kit.Empty>
              ))}

            {/* 图谱人格锚点：成员与知识图谱锚点实体的绑定 + 该锚点的 1 跳子图 */}
            {tab === 'anchor' &&
              (peer ? (
                <PeerAnchorPanel peerId={peer.id} peerName={peer.name} notify={notify} />
              ) : (
                <kit.Empty>先把这位成员加入圆桌，再回来建立它的图谱人格锚点。</kit.Empty>
              ))}

            {/* 提示词 & 上下文工程（与潜意识页那张对话 tab 同一套编排 UI） */}
            {tab === 'prompt' &&
              (peer ? (
                <div className="flex min-h-[420px] flex-col">
                  <PromptsPanel peerId={peer.id} />
                </div>
              ) : (
                <div className="flex flex-col gap-4">
                  <div className="min-w-0">
                    <h2 className="ui-heading text-md">提示词 &amp; 上下文工程</h2>
                    <p className="mt-1 text-xs leading-relaxed text-muted">
                      每位智能体自己一套「对话」提示词（身份 / 增强 / 终端 / 子代理），
                      与首页对话的全局提示词隔离；「圆桌」那一类是全桌共用的协作规程。
                    </p>
                  </div>
                  <kit.Empty>先把这位成员加入圆桌，再回来为它配置自己的提示词。</kit.Empty>
                </div>
              ))}
            </div>
          </Card>
        </div>
      </PageBoundary>

      {/* 隐藏的取图入口：头像与「上传头像」都点它 */}
      <input
        ref={fileRef}
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif"
        className="hidden"
        onChange={(e) => void onFile(e)}
      />

      <Toast
        message={toast?.msg ?? null}
        kind={toast?.kind === 'err' ? 'error' : 'success'}
        onClose={() => setToast(null)}
      />
    </kit.Page>
  )
}
