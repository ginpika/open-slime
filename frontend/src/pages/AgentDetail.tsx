/**
 * 智能体内部页（{@code /multi-agent/{id}}）：一位成员的完整资料 + 它自己那套「对话」提示词。
 *
 * <p>从成员页的卡片点进来，独占一整页：左侧放资料（头像 / 名字 / 模型 / 备注），右侧是一个 tab 容器，
 * 点标签切换显示<b>能力</b> / <b>图谱人格锚点</b> / <b>提示词 &amp; 上下文工程</b>——三块内容都不短，
 * 竖着堆会拖成一条长栏。提示词面板与潜意识页那张「对话」tab 是同一套编排 UI，只是数据换成这位成员
 * 自己的：改这里只影响它，不碰首页对话，也不碰别的成员。
 *
 * <p>「能力」逐成员各一份：<b>可用工具</b>（在默认的只读检索 + 发言 / 状态机工具之上追加）与
 * <b>工作空间</b>（绑定 {@code term_target}；绑了才授予 {@code run_command} / {@code list_terminals}，
 * 且只能访问绑定的这几台）。它与左侧资料共用同一份草稿，因此只有一个保存动作。
 *
 * <p>三个动作各归其位：<b>返回上级</b>在面包屑左侧（回成员列表，取代了原先的「取消」按钮）；
 * <b>保存</b>在面包屑那一行的最右端（微小圆角，与导航同处一行，不占正文也不悬空）；
 * <b>移出圆桌</b>收在左侧资料卡片底部（成员的破坏性动作，与右栏 tab 无关）。
 *
 * <p>提示词面板由 {@link PromptsPanel}（传 {@code peerId}）承载：版本、生效项、拼装顺序都落在
 * {@code ma_peer_prompt_*} 上；没配过的提示词位自动回退全局（提示词页绑定 → 内置），
 * 所以新成员开箱即用。
 *
 * <p>头像走库存储：选了图先在前端裁成方图并压到 256px，转成 data URL 再随成员一起存。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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
  listMaTools,
  updateMaPeer,
  type MaPeer,
  type MaPeerAnchor,
  type MaToolOption,
  type TerminalTarget,
} from '../api/client'
import { btnOutline, btnPrimary, btnQuiet, miniBtn } from '../components/ui'
import { Field, INPUT, Select } from '../components/formKit'
import GraphMini from '../components/GraphMini'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import Toast from '../components/Toast'
import PromptsPanel from './PromptsPanel'

/** 路径末段用 new 表示「新建成员」（成员 id 是 uuid，不会撞） */
const NEW_ID = 'new'

/** 右栏 tab：能力 / 图谱人格锚点 / 提示词三块都不短，切换显示而不是竖着堆成一整条长栏 */
const RIGHT_TABS = [
  { key: 'cap', label: '能力' },
  { key: 'anchor', label: '图谱人格锚点' },
  { key: 'prompt', label: '提示词 & 上下文工程' },
] as const

type RightTab = (typeof RIGHT_TABS)[number]['key']

type Option = { value: string; label: string }

type Draft = {
  name: string
  modelId: string
  note: string
  avatar: string | null
  /** 额外可用工具（工具名）；空 = 只有默认的只读检索 + 发言 / 状态机工具 */
  tools: string[]
  /** 绑定的工作空间（term_target.id）；非空才授予 run_command / list_terminals */
  terminals: string[]
}

/**
 * 一项可勾选的能力卡片（工具 / 工作空间）：勾选态用色块与描边表示，整卡可点。
 *
 * <p>不用 {@link Field}（它渲染 {@code <label>}）：label 会把点击转给内部第一个 labelable 元素，
 * 一格里放多个按钮时会误触第一项。这里自己写同款小标题。
 */
function PickCard({
  checked,
  title,
  badge,
  mono,
  description,
  onToggle,
}: {
  checked: boolean
  /** 主标题（工具名 / 工作空间名） */
  title: string
  /** 右上角小角标（工具用「写库」；工作空间用 kind：docker / ssh） */
  badge?: string
  /** 主标题用等宽字体（工具名） */
  mono?: boolean
  /** 副文本（工具用描述；工作空间用「未启用」等提示），同时作为悬停提示 */
  description?: string
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      title={description}
      onClick={onToggle}
      className={`flex flex-col gap-1.5 rounded-xl border p-3 text-left transition ${
        checked ? 'border-secondary bg-secondary/10' : 'border-line hover:border-line-strong'
      }`}
    >
      <span className="flex w-full items-center gap-2">
        <span
          className={`h-3.5 w-3.5 shrink-0 rounded-[4px] border ${
            checked ? 'border-secondary bg-secondary' : 'border-line-strong'
          }`}
        />
        <span
          className={`min-w-0 flex-1 truncate text-[12.5px] font-medium text-fg ${
            mono ? 'font-mono text-[12px]' : ''
          }`}
        >
          {title}
        </span>
        {badge && (
          <span className="shrink-0 rounded-md bg-fg/5 px-1.5 py-0.5 font-mono text-[10px] text-muted">
            {badge}
          </span>
        )}
      </span>
      {description && (
        <span className="line-clamp-3 text-[11px] leading-snug text-hint">{description}</span>
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
          <h2 className="text-[14px] font-semibold text-fg">图谱人格锚点</h2>
          <p className="mt-1 text-[11.5px] leading-relaxed text-muted">
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
        <p className="text-[12px] text-hint">载入锚点…</p>
      ) : !anchor ? (
        <div className="flex flex-col items-start gap-3 rounded-xl border border-dashed border-line px-4 py-6">
          <p className="text-[12px] leading-relaxed text-muted">
            还没有锚点。建立后会以「{peerName}」为名在图谱里新建一个锚点实体。
          </p>
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
          <div className="flex flex-wrap items-center gap-2 text-[12px]">
            <span className="rounded-md border border-line px-2 py-0.5 font-medium text-fg">
              {anchor.name}
            </span>
            <span className="rounded-md bg-fg/5 px-2 py-0.5 font-mono text-[10.5px] text-muted">
              {anchor.type}
            </span>
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
            <p className="text-center text-[11.5px] text-hint">
              该锚点还没有任何关系。到图谱页挂上第一条，画像就会长在这里。
            </p>
          )}
        </div>
      )}
    </div>
  )
}

export default function AgentDetail({ peerId }: { peerId: string }) {
  const isNew = peerId === NEW_ID
  const [peer, setPeer] = useState<MaPeer | null>(null)
  const [loading, setLoading] = useState(true)
  const [modelOptions, setModelOptions] = useState<Option[]>([])
  /** 能力面的候选：可授予的工具 与 可绑定的工作空间（都来自各自的管理页，这里只做选择） */
  const [toolOptions, setToolOptions] = useState<MaToolOption[]>([])
  const [workspaceOptions, setWorkspaceOptions] = useState<TerminalTarget[]>([])
  const [toast, setToast] = useState<{ msg: string; kind?: 'ok' | 'err' } | null>(null)

  const [draft, setDraft] = useState<Draft | null>(null)
  const [savingProfile, setSavingProfile] = useState(false)
  /** 右栏当前显示哪一块（能力 / 锚点 / 提示词）；缺省停在能力，新建时一眼能配上 */
  const [tab, setTab] = useState<RightTab>('cap')
  /** 「可用工具」当前停在哪个业务领域 Tab（null = 默认选中第一个领域） */
  const [toolDomain, setToolDomain] = useState<string | null>(null)
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
      setModelOptions([
        { value: '', label: '跟随设置页默认' },
        ...settings.models.map((m) => ({ value: m.id, label: m.name })),
      ])
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }, [notify])

  /**
   * 能力面的候选清单：工具从 {@code /api/multi-agent/tools} 取（后端已滤掉不该由名字授予的），
   * 工作空间复用终端模块的全量目标（含未启用的，页面上标注出来）。
   */
  const loadCapabilityOptions = useCallback(async () => {
    try {
      const [terminal, toolList] = await Promise.all([getTerminal(), listMaTools()])
      setWorkspaceOptions(terminal.targets)
      setToolOptions(toolList)
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
      setDraft({ name: '', modelId: '', note: '', avatar: null, tools: [], terminals: [] })
      return
    }
    if (!peer || loadedIdRef.current === peer.id) return
    loadedIdRef.current = peer.id
    setDraft({
      name: peer.name,
      modelId: peer.modelId ?? '',
      note: peer.note ?? '',
      avatar: peer.avatar,
      tools: peer.tools ?? [],
      terminals: peer.terminals ?? [],
    })
  }, [peer, isNew])

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

  /** 勾选 / 取消勾选一项能力（工具或工作空间）：整份清单随成员一起保存 */
  const togglePick = (key: 'tools' | 'terminals', id: string) =>
    setDraft((d) => {
      if (!d) return d
      const cur = d[key]
      return { ...d, [key]: cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id] }
    })

  /** 可用工具按业务领域分组（后端已按领域序返回，这里按首次出现顺序成组） */
  const toolGroups = useMemo(() => {
    const map = new Map<string, { key: string; label: string; tools: MaToolOption[] }>()
    for (const t of toolOptions) {
      const g = map.get(t.domain) ?? { key: t.domain, label: t.domainLabel, tools: [] }
      g.tools.push(t)
      map.set(t.domain, g)
    }
    return [...map.values()]
  }, [toolOptions])
  const activeToolGroup = toolGroups.find((g) => g.key === toolDomain) ?? toolGroups[0] ?? null

  const saveProfile = async () => {
    if (!draft) return
    const clean = draft.name.trim()
    if (!clean) {
      notify('先给成员起个名字（它会用在 @ 里）', 'err')
      return
    }
    setSavingProfile(true)
    try {
      if (peer) {
        /* 空串是「清空」的语言：modelId 置空 = 回到跟随默认，avatar 置空 = 移除头像；
           工具 / 工作空间传数组，空数组即清空这层额外能力 */
        await updateMaPeer(peer.id, {
          name: clean,
          modelId: draft.modelId,
          note: draft.note,
          avatar: draft.avatar ?? '',
          tools: draft.tools,
          terminals: draft.terminals,
        })
        notify(`「${clean}」的资料已保存`, 'ok')
        await reloadPeer()
      } else {
        const created = await createMaPeer({
          name: clean,
          modelId: draft.modelId,
          note: draft.note,
          avatar: draft.avatar ?? undefined,
          tools: draft.tools,
          terminals: draft.terminals,
        })
        /* 建好了就换到它自己的内部页（换掉地址而非 push，免得后退又回到新建态） */
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
      window.location.assign('#/multi-agent')
    } catch (e) {
      notify(errMsg(e), 'err')
    }
  }

  if (loading) {
    return <p className="pt-10 text-[12.5px] text-hint">载入成员资料…</p>
  }

  if (!isNew && !peer) {
    return (
      <section className="flex flex-col gap-4 pt-6">
        <p className="text-[13px] text-muted">这位成员不存在，或已被移出圆桌。</p>
        <a href="#/multi-agent" className={`${btnOutline('rounded-lg')} self-start`}>
          返回成员列表
        </a>
      </section>
    )
  }

  const preview = draft?.name ?? peer?.name ?? ''

  return (
    <section className="flex flex-col gap-5 pt-6 pb-4">
      {/* 返回上级 + 面包屑：内部页从成员页进来，给一条明确的回路（原先的「取消」按钮由它取代）；
          保存按钮挂这一行最右端，与导航同处一行，不占正文也不悬空 */}
      <div className="flex items-center gap-3">
        <a href="#/multi-agent" className={`${miniBtn} gap-1`} title="返回多智能体成员列表">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3.5 w-3.5">
            <path
              d="M15 6l-6 6 6 6"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          返回上级
        </a>
        <nav className="flex items-center gap-2 text-[12px]">
          <a href="#/multi-agent" className="text-muted transition hover:text-fg">
            多智能体
          </a>
          <span className="text-hint">/</span>
          <span className="text-fg">{isNew ? '新建成员' : (peer?.name ?? '')}</span>
        </nav>
        <button
          type="button"
          className={`ml-auto ${btnPrimary('rounded-sm')}`}
          disabled={savingProfile}
          onClick={() => void saveProfile()}
        >
          {savingProfile ? '保存中…' : peer ? '保存资料' : '加入圆桌'}
        </button>
      </div>

      <PageBoundary>
        <div className="grid gap-5 lg:grid-cols-[320px_1fr]">
          {/* 左：资料 */}
          <div className="flex h-fit flex-col gap-5 rounded-2xl border border-line p-5">
            <div className="flex flex-col items-center gap-3">
              <button
                type="button"
                onClick={() => fileRef.current?.click()}
                title="点击上传头像"
                className="group relative shrink-0 cursor-pointer rounded-full"
              >
                <PeerAvatar
                  name={preview || '新'}
                  avatar={draft?.avatar ?? null}
                  size="h-28 w-28"
                  textClass="text-[36px]"
                />
                <span className="absolute inset-0 flex items-center justify-center rounded-full bg-black/45 text-[12px] font-medium text-white opacity-0 transition group-hover:opacity-100">
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
              <p className="text-center text-[11px] text-hint">
                PNG / JPG，会自动裁成方图并压缩
              </p>
            </div>

            <Field label="名字" hint="发言时被 @ 的名字，圆桌上唯一">
              <input
                className={INPUT}
                value={draft?.name ?? ''}
                placeholder="如：张三"
                onChange={(e) => setDraft((d) => (d ? { ...d, name: e.target.value } : d))}
              />
            </Field>

            <Field label="模型" hint="不指定则跟随设置页默认">
              <Select
                value={draft?.modelId ?? ''}
                onChange={(v) => setDraft((d) => (d ? { ...d, modelId: v } : d))}
                options={modelOptions}
              />
            </Field>

            <Field label="备注" hint="写给人看的一句话，不会注入模型">
              <textarea
                className={`${INPUT} min-h-[72px] resize-none`}
                rows={3}
                value={draft?.note ?? ''}
                placeholder="如：负责挑刺的那位"
                onChange={(e) => setDraft((d) => (d ? { ...d, note: e.target.value } : d))}
              />
            </Field>

            {/* 移出圆桌：成员的破坏性动作，收在资料卡片底部（保存按钮已改为右下角悬浮） */}
            {peer && (
              <div className="flex flex-col items-start gap-2 border-t border-line pt-4">
                <button
                  type="button"
                  className={btnQuiet('rounded-lg')}
                  onClick={() => void remove()}
                >
                  移出圆桌
                </button>
                <span className="text-[11px] leading-relaxed text-hint">
                  移出后，它自己那套提示词（版本 / 生效项 / 顺序）会一并清除。
                </span>
              </div>
            )}
          </div>

          {/* 右：能力 / 图谱人格锚点 / 提示词——三块内容都不短，用 tab 切换，免得竖着堆成一条长栏 */}
          <div className="flex flex-col gap-5 rounded-2xl border border-line p-5">
            <div className="flex flex-wrap items-center gap-1.5 border-b border-line pb-3">
              {RIGHT_TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setTab(t.key)}
                  className={`rounded-md px-3 py-1.5 text-[12.5px] font-medium transition ${
                    tab === t.key
                      ? 'bg-accent/10 text-fg'
                      : 'text-muted hover:bg-fg/5 hover:text-fg'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            {/* 能力：逐成员各配一份——额外可用工具（按业务领域分 Tab）+ 可访问的工作空间（绑了才给终端工具） */}
            {tab === 'cap' && (
              <div className="flex flex-col gap-5">
                <p className="text-[12.5px] leading-relaxed text-muted">
                  在默认的只读检索 + 发言 / 状态机工具之上追加。绑定工作空间后它才能执行命令，
                  且只能访问绑定的这几台。
                </p>

                <div>
                  <span className="mb-2 block text-[11px] font-medium uppercase tracking-[0.12em] text-faint">
                    工作空间
                  </span>
                  {workspaceOptions.length === 0 ? (
                    <p className="rounded-xl border border-dashed border-line px-3 py-4 text-[11.5px] leading-relaxed text-hint">
                      还没有工作空间。先到「工作空间」页添加一个（docker 容器或 SSH 服务器）。
                    </p>
                  ) : (
                    <div className="scrollbar-slim grid max-h-72 gap-2 overflow-y-auto pr-0.5 sm:grid-cols-2">
                      {workspaceOptions.map((t) => (
                        <PickCard
                          key={t.id}
                          checked={(draft?.terminals ?? []).includes(t.id)}
                          title={t.name}
                          badge={t.kind}
                          description={t.enabled ? undefined : '未启用'}
                          onToggle={() => togglePick('terminals', t.id)}
                        />
                      ))}
                    </div>
                  )}
                  <span className="mt-2 block text-[11px] text-hint">
                    勾选即授予 run_command / list_terminals，且只在这几台上执行。
                  </span>
                </div>

                <div>
                  <span className="mb-2 block text-[11px] font-medium uppercase tracking-[0.12em] text-faint">
                    可用工具
                  </span>
                  {toolGroups.length === 0 ? (
                    <p className="rounded-xl border border-dashed border-line px-3 py-4 text-[11.5px] leading-relaxed text-hint">
                      没有可额外授予的工具。
                    </p>
                  ) : (
                    <div className="flex flex-col gap-2.5">
                      {/* 业务领域 Tab：角标是「已勾选 / 该领域总数」，一眼看出哪类还空着 */}
                      <div className="flex flex-wrap items-center gap-1.5">
                        {toolGroups.map((g) => {
                          const selected = g.tools.filter((t) =>
                            (draft?.tools ?? []).includes(t.name),
                          ).length
                          const active = g.key === activeToolGroup?.key
                          return (
                            <button
                              key={g.key}
                              type="button"
                              onClick={() => setToolDomain(g.key)}
                              className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-[11.5px] font-medium transition ${
                                active
                                  ? 'bg-accent/10 text-fg'
                                  : 'text-muted hover:bg-fg/5 hover:text-fg'
                              }`}
                            >
                              {g.label}
                              <span
                                className={`rounded px-1 text-[10.5px] ${
                                  selected > 0 ? 'text-accent' : 'text-hint'
                                }`}
                              >
                                {selected}/{g.tools.length}
                              </span>
                            </button>
                          )
                        })}
                      </div>
                      <div className="scrollbar-slim grid max-h-80 gap-2 overflow-y-auto pr-0.5 sm:grid-cols-2">
                        {(activeToolGroup?.tools ?? []).map((t) => (
                          <PickCard
                            key={t.name}
                            checked={(draft?.tools ?? []).includes(t.name)}
                            title={t.name}
                            badge={t.writes ? '写库' : undefined}
                            mono
                            description={t.description}
                            onToggle={() => togglePick('tools', t.name)}
                          />
                        ))}
                      </div>
                    </div>
                  )}
                  <span className="mt-2 block text-[11px] text-hint">
                    只读检索与发言 / 状态机工具始终可用，这里勾的是额外放开的。
                  </span>
                </div>
              </div>
            )}

            {/* 图谱人格锚点：成员与知识图谱锚点实体的绑定 + 该锚点的 1 跳子图 */}
            {tab === 'anchor' &&
              (peer ? (
                <PeerAnchorPanel peerId={peer.id} peerName={peer.name} notify={notify} />
              ) : (
                <p className="rounded-xl border border-dashed border-line px-4 py-8 text-center text-[12px] text-muted">
                  先把这位成员加入圆桌，再回来建立它的图谱人格锚点。
                </p>
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
                    <h2 className="text-[14px] font-semibold text-fg">
                      提示词 &amp; 上下文工程
                    </h2>
                    <p className="mt-1 text-[11.5px] leading-relaxed text-muted">
                      每位智能体自己一套「对话」提示词（身份 / 增强 / 终端 / 子代理），
                      与首页对话的全局提示词隔离；「圆桌」那一类是全桌共用的协作规程。
                    </p>
                  </div>
                  <p className="rounded-xl border border-dashed border-line px-4 py-8 text-center text-[12px] text-muted">
                    先把这位成员加入圆桌，再回来为它配置自己的提示词。
                  </p>
                </div>
              ))}
          </div>
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
    </section>
  )
}
