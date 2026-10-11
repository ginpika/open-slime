/**
 * 提示词 & 上下文工程子页：把「写死在代码里的提示词」变成可覆盖、可编排的用户版本。
 *
 * 模型是「大类 → 上下文 → 提示词位 → 版本」：一次 LLM 调用由若干提示词位 + 代码控制的运行时素材
 * 组成。两个大类按用途分——「系统级提示词」面向用户直接对话，「开发者选项」是文档→图谱流水线上的自动环节
 * （互斥的用法各自成一个上下文，平铺在该大类下）；只有真正拼在同一次调用里的提示词位才归入同一
 * 上下文——目前只有「对话」（身份 + 增强），且这两位可拖拽换序，换序结果落库、直接影响拼装。
 *
 * 页面只做「编排」一件事：每个位是一条长条，自上而下的顺序就是拼装顺序；长条上从左到右依次是
 * 拖拽区、标题与信息（类型 + 生效版本）、「查看提示词」、省略号菜单。正文不铺在页面上，
 * 全部收进「查看提示词」窗口里读写；版本管理与说明收进「⋯」菜单。底色沿用 Material 色调便签
 * （note-tone-*，由主题令牌现混，浅色/深色/自定义主题自适应），仅作类型区分。
 *
 * 所有写操作后端都返回最新全量视图，这里直接覆盖本地状态即可。
 *
 * <p><b>两处复用</b>：系统页（全局，开发者选项）与智能体内部页（按成员，见 {@code peerId}）——
 * 两者的数据结构完全一致，只是读写走的接口不同，于是编排 UI 只有这一份。
 */

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import {
  ApiError,
  bindPeerPromptSlot,
  bindPromptSlot,
  createPeerPromptVersion,
  createPromptVersion,
  deletePeerPromptVersion,
  deletePromptVersion,
  getPeerPrompts,
  getPrompts,
  savePeerPromptContextOrder,
  savePeerPromptSlotContent,
  savePromptContextOrder,
  savePromptSlotContent,
  updatePeerPromptVersion,
  updatePromptVersion,
  type PromptContext,
  type PromptSlot,
  type PromptsView,
  type PromptVersion,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { btnPrimary, btnQuiet, miniBtn, Control } from '../components/ui'
import { usePageKit } from '../kit'

/** 可编辑提示词位的长条色调（按顺序轮换；超出则回落到主色调） */
const PART_TONES = ['note-tone-accent', 'note-tone-secondary', 'note-tone-primary']

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

/** 某位当前生效的正文与来源说明 */
function effective(slot: PromptSlot): { content: string; from: string } {
  const active = slot.versions.find((v) => v.id === slot.activeVersionId)
  if (active) return { content: active.content, from: `版本「${active.name}」` }
  return {
    content: slot.builtin,
    from: slot.builtinSource === 'env' ? '内置 .env' : '内置代码',
  }
}

/* ---------------- 小图标 ---------------- */

/** 拖拽手柄：六点握把 */
function Grip() {
  return (
    <svg className="h-4 w-4" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <circle cx="6" cy="4" r="1.3" />
      <circle cx="10" cy="4" r="1.3" />
      <circle cx="6" cy="8" r="1.3" />
      <circle cx="10" cy="8" r="1.3" />
      <circle cx="6" cy="12" r="1.3" />
      <circle cx="10" cy="12" r="1.3" />
    </svg>
  )
}

function Dots() {
  return (
    <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <circle cx="5" cy="12" r="1.7" />
      <circle cx="12" cy="12" r="1.7" />
      <circle cx="19" cy="12" r="1.7" />
    </svg>
  )
}

function Check() {
  return (
    <svg className="h-3.5 w-3.5 shrink-0" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="m5 12.5 4.5 4.5L19 7"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/* ---------------- 查看 / 编辑提示词 ---------------- */

/**
 * 正文收在这里：整页只排布不铺全文，点「查看提示词」才把这份正文摊开，
 * 并可直接改、直接存（保存语义与便签一致：内置会自动分叉成新版本）。
 */
function PromptViewer({
  slot,
  onClose,
  onSave,
}: {
  slot: PromptSlot
  onClose: () => void
  onSave: (content: string) => Promise<void>
}) {
  const cur = effective(slot)
  const [draft, setDraft] = useState(cur.content)
  const [saving, setSaving] = useState(false)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  /* 生效版本切换 / 后端刷新时同步（内容相同则不动，避免打字被覆盖） */
  useEffect(() => {
    setDraft(cur.content)
  }, [cur.content, slot.activeVersionId])

  const dirty = draft !== cur.content

  const save = async () => {
    if (!dirty || saving) return
    setSaving(true)
    try {
      await onSave(draft)
      setSavedAt(Date.now())
    } catch {
      /* 失败已由上层 toast 提示；草稿保留 */
    } finally {
      setSaving(false)
    }
  }

  return (
    <FloatWindow title={`${slot.name} · ${cur.from}`} onClose={onClose} width={860}>
      <div className="flex flex-col gap-3">
        <p className="text-xs leading-relaxed text-hint">{slot.description}</p>
        <Control>
          <textarea
            className="scrollbar-slim min-h-[420px] resize-y font-mono text-sm leading-6"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
                e.preventDefault()
                void save()
              }
            }}
            spellCheck={false}
            autoFocus
          />
        </Control>
        <div className="flex items-center justify-between gap-3 border-t border-line pt-3">
          <span className={`text-xs ${dirty ? 'text-warn' : 'text-hint'}`}>
            {saving ? '保存中…' : dirty ? '有未保存的改动' : savedAt ? '已保存' : `生效：${cur.from}`}
          </span>
          <div className="flex gap-2">
            <button type="button" className={btnQuiet('rounded-md')} onClick={onClose}>
              关闭
            </button>
            <button
              type="button"
              className={btnPrimary('rounded-md')}
              disabled={!dirty || saving}
              onClick={() => void save()}
            >
              保存
            </button>
          </div>
        </div>
        <p className="text-xs leading-relaxed text-hint">
          当前用的是内置时，保存会自动分叉出一份「自定义版本」并绑定，内置原文不受影响；
          也可以先用「⋯」新建版本，再在这里改它。
        </p>
      </div>
    </FloatWindow>
  )
}

/* ---------------- 版本命名弹窗（新建 / 重命名） ---------------- */

function NameDialog({
  slot,
  mode,
  editing,
  onClose,
  onSubmit,
}: {
  slot: PromptSlot
  mode: 'create' | 'rename'
  editing: PromptVersion | null
  onClose: () => void
  onSubmit: (req: { name: string; content: string }) => Promise<void>
}) {
  const kit = usePageKit()
  const rename = mode === 'rename'
  const [name, setName] = useState(editing?.name ?? `${slot.name} 副本`)
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (!name.trim() || busy) return
    setBusy(true)
    try {
      /* 新建 = 复制当前生效正文起稿，正文随后在「查看提示词」里改 */
      await onSubmit({ name: name.trim(), content: effective(slot).content })
    } finally {
      setBusy(false)
    }
  }

  return (
    <FloatWindow
      title={`${rename ? '重命名版本' : '新建版本'} · ${slot.name}`}
      onClose={onClose}
      width={520}
    >
      <div className="flex flex-col gap-3.5">
        <kit.Field label="版本名">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void submit()
            }}
            placeholder="如 严谨助手 / 精简版"
            autoFocus
          />
        </kit.Field>
        {!rename && (
          <p className="text-xs leading-relaxed text-hint">
            新版本将以当前生效正文起稿（不覆盖内置），保存后用「查看提示词」改它。
          </p>
        )}
        <div className="flex justify-end gap-2 border-t border-line pt-3">
          <button type="button" className={btnQuiet('rounded-md')} onClick={onClose}>
            取消
          </button>
          <button
            type="button"
            className={btnPrimary('rounded-md')}
            disabled={!name.trim() || busy}
            onClick={submit}
          >
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </FloatWindow>
  )
}

/* ---------------- 菜单 ---------------- */

function MenuItem({
  active,
  onClick,
  children,
}: {
  active?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition ${
        active ? 'bg-accent/10 font-medium text-fg' : 'text-muted hover:bg-fg/5 hover:text-fg'
      }`}
    >
      <span className="flex-1 truncate">{children}</span>
      {active && <Check />}
    </button>
  )
}

function MenuLabel({ children }: { children: ReactNode }) {
  return (
    <p className="px-2 pb-1 pt-2 text-2xs font-medium uppercase tracking-[0.18em] text-faint">
      {children}
    </p>
  )
}

/** 「⋯」菜单：点外部或 Esc 收起；面板贴着长条下沿右对齐 */
function EllipsisMenu({
  open,
  onToggle,
  onClose,
  children,
}: {
  open: boolean
  onToggle: () => void
  onClose: () => void
  children: ReactNode
}) {
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) onClose()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onClose])

  return (
    <div ref={boxRef} className="relative shrink-0">
      <button
        type="button"
        aria-label="更多"
        aria-expanded={open}
        onClick={onToggle}
        className="flex h-7 w-7 items-center justify-center rounded-full border border-line text-muted transition hover:border-line-strong hover:text-fg"
      >
        <Dots />
      </button>
      {open && (
        <div className="absolute right-0 top-[calc(100%+6px)] z-20 w-[268px] rounded-xl border border-line bg-bg p-1.5 shadow-xl">
          {children}
        </div>
      )}
    </div>
  )
}

/* ---------------- 长条：可编辑提示词位 ---------------- */

function SlotBar({
  slot,
  tone,
  reorderable,
  grabbing,
  dropTarget,
  busy,
  onGrab,
  onGrabMove,
  onRelease,
  onView,
  onBind,
  onCreate,
  onRename,
  onDelete,
}: {
  slot: PromptSlot
  tone: string
  reorderable: boolean
  grabbing: boolean
  dropTarget: boolean
  busy: boolean
  onGrab: () => void
  onGrabMove: (x: number, y: number) => void
  onRelease: () => void
  onView: () => void
  onBind: (versionId: string | null) => void
  onCreate: () => void
  onRename: (v: PromptVersion) => void
  onDelete: (v: PromptVersion) => void
}) {
  const kit = usePageKit()
  const [menuOpen, setMenuOpen] = useState(false)
  const cur = effective(slot)
  const active = slot.versions.find((v) => v.id === slot.activeVersionId) ?? null

  return (
    <div
      data-slot-key={slot.key}
      /* 重排走 pointer 事件（不用 HTML5 DnD）：浏览器不参与拖放状态机，不会把页面卡在拖拽态 */
      onPointerDown={(e) => {
        if (!reorderable) return
        if ((e.target as HTMLElement).closest('button')) return // 按钮保持可点
        if (e.button !== 0) return
        e.preventDefault() // 别让按下变成选文字
        e.currentTarget.setPointerCapture(e.pointerId)
        onGrab()
      }}
      onPointerMove={(e) => {
        if (grabbing) onGrabMove(e.clientX, e.clientY)
      }}
      onPointerUp={() => {
        if (grabbing) onRelease()
      }}
      onPointerCancel={() => {
        if (grabbing) onRelease()
      }}
      className={`${tone} flex select-none items-center gap-2.5 rounded-xl border border-line py-2 pl-2 pr-2.5 shadow-sm transition ${
        grabbing ? 'cursor-grabbing opacity-60' : reorderable ? 'cursor-grab' : ''
      } ${reorderable ? 'touch-none' : ''} ${
        dropTarget ? 'ring-2 ring-accent ring-offset-2 ring-offset-bg' : ''
      }`}
    >
      {/* 1. 拖拽区域 */}
      <span
        title={reorderable ? '按住拖动，调整它在上下文中的位置' : undefined}
        className={`w-5 shrink-0 ${reorderable ? 'text-hint' : 'text-transparent'}`}
      >
        <Grip />
      </span>

      {/* 2. 标题与信息：名称 + 类型 + 生效版本 */}
      <div className="flex min-w-0 flex-1 items-center gap-2">
        <span className="truncate text-sm font-semibold text-fg">{slot.name}</span>
        <kit.Badge>提示词</kit.Badge>
        <span className="truncate text-xs text-muted">{cur.from}</span>
      </div>

      {/* 3. 查看提示词 */}
      <button type="button" className={miniBtn} disabled={busy} onClick={onView}>
        查看提示词
      </button>

      {/* 4. 省略号 */}
      <EllipsisMenu
        open={menuOpen}
        onToggle={() => setMenuOpen((v) => !v)}
        onClose={() => setMenuOpen(false)}
      >
        <MenuLabel>说明</MenuLabel>
        <p className="px-2 pb-1.5 text-xs leading-relaxed text-muted">{slot.description}</p>

        <MenuLabel>生效版本</MenuLabel>
        <MenuItem active={slot.activeVersionId == null} onClick={() => onBind(null)}>
          {slot.builtinSource === 'env' ? '内置（.env）' : '内置（代码）'}
        </MenuItem>
        {slot.versions.map((v) => (
          <MenuItem key={v.id} active={slot.activeVersionId === v.id} onClick={() => onBind(v.id)}>
            {v.name}
          </MenuItem>
        ))}

        <MenuLabel>管理</MenuLabel>
        <MenuItem onClick={onCreate}>+ 新建版本</MenuItem>
        {active && <MenuItem onClick={() => onRename(active)}>重命名当前版本</MenuItem>}
        {active && <MenuItem onClick={() => onDelete(active)}>删除当前版本</MenuItem>}
      </EllipsisMenu>
    </div>
  )
}

/* ---------------- 长条：代码控制的运行时素材 ---------------- */

function RuntimeBar({ name, description }: { name: string; description: string }) {
  const kit = usePageKit()
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="note-tone-neutral flex items-center gap-2.5 rounded-xl border border-dashed border-line py-2 pl-2 pr-2.5">
      {/* 位置由协议固定，拖拽区留空以对齐 */}
      <span className="w-5 shrink-0" />

      <div className="flex min-w-0 flex-1 items-center gap-2">
        <span className="truncate text-sm font-medium text-muted">{name}</span>
        <kit.Badge>运行时</kit.Badge>
        <span className="truncate text-xs text-hint">代码控制，运行时填充</span>
      </div>

      <EllipsisMenu
        open={menuOpen}
        onToggle={() => setMenuOpen((v) => !v)}
        onClose={() => setMenuOpen(false)}
      >
        <MenuLabel>说明</MenuLabel>
        <p className="px-2 pb-1.5 text-xs leading-relaxed text-muted">{description}</p>
      </EllipsisMenu>
    </div>
  )
}

/* ---------------- 大类标签 ---------------- */

/**
 * 大类标签（一级）：每个标签自成一个胶囊（各自带边框），不套外层圆角容器。
 *
 * 大类数量会随功能扩展变多，故这里用「独立胶囊 + 外层 flex-wrap」而非
 * 「圆角容器 + 内部滑块」——后者只在单行时成立，换行时外层容器会横跨多行而破形。
 */
function CategoryTab({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={active ? btnPrimary() : btnQuiet()}
    >
      {children}
    </button>
  )
}

/* ---------------- 页面 ---------------- */

/** 编排面板要用的全部读写；全局（首页对话）与按成员（智能体内部页）各实现一份 */
type PromptApi = {
  load: () => Promise<PromptsView>
  createVersion: (slot: string, req: { name: string; content: string }) => Promise<PromptsView>
  updateVersion: (id: string, req: { name: string; content: string }) => Promise<PromptsView>
  deleteVersion: (id: string) => Promise<PromptsView>
  bind: (slot: string, versionId: string | null) => Promise<PromptsView>
  saveContent: (slot: string, content: string) => Promise<PromptsView>
  saveOrder: (context: string, slots: string[]) => Promise<PromptsView>
}

/**
 * 全局（系统 · 开发者选项）：维护文档→图谱流水线在用的那一套提示词。
 */
export default function PromptsPanel({ peerId }: { peerId?: string } = {}) {
  const kit = usePageKit()
  const [data, setData] = useState<PromptsView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)
  /* 当前大类（空 = 用第一个） */
  const [catKey, setCatKey] = useState('')
  /* 正文查看窗口：只记位 key，写的对象从最新 data 派生（避免持有过期快照） */
  const [viewerKey, setViewerKey] = useState<string | null>(null)
  /* 版本命名弹窗 */
  const [dlg, setDlg] = useState<{
    slotKey: string
    mode: 'create' | 'rename'
    versionId: string | null
  } | null>(null)
  /* 重排「抓取」：ref 记录正在拖的位与落点（pointermove 高频触发，只有落点真的变了才 setState），
     state 只驱动视觉。全部走 pointer 事件，不依赖浏览器的拖放状态机。 */
  const dragRef = useRef<{ key: string; targets: string[] } | null>(null)
  const overRef = useRef<string | null>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)

  /* 按作用域选一套接口：给了 peerId 就是这位智能体自己的那套「对话」提示词 */
  const api: PromptApi = useMemo(
    () =>
      peerId
        ? {
            load: () => getPeerPrompts(peerId),
            createVersion: (slot, req) => createPeerPromptVersion(peerId, slot, req),
            updateVersion: (id, req) => updatePeerPromptVersion(peerId, id, req),
            deleteVersion: (id) => deletePeerPromptVersion(peerId, id),
            bind: (slot, versionId) => bindPeerPromptSlot(peerId, slot, versionId),
            saveContent: (slot, content) => savePeerPromptSlotContent(peerId, slot, content),
            saveOrder: (context, slots) => savePeerPromptContextOrder(peerId, context, slots),
          }
        : {
            load: getPrompts,
            createVersion: createPromptVersion,
            updateVersion: updatePromptVersion,
            deleteVersion: deletePromptVersion,
            bind: bindPromptSlot,
            saveContent: savePromptSlotContent,
            saveOrder: savePromptContextOrder,
          },
    [peerId],
  )

  const clearGrab = useCallback(() => {
    dragRef.current = null
    overRef.current = null
    setDragKey(null)
    setOverKey(null)
  }, [])

  /* 兜底：指针在任何地方抬起/被取消都清掉抓取态（指针捕获丢失时也不会卡住） */
  useEffect(() => {
    window.addEventListener('pointerup', clearGrab)
    window.addEventListener('pointercancel', clearGrab)
    return () => {
      window.removeEventListener('pointerup', clearGrab)
      window.removeEventListener('pointercancel', clearGrab)
    }
  }, [clearGrab])

  const notify = useCallback(
    (kind: 'success' | 'error', msg: string) => setToast({ kind, msg }),
    [],
  )

  const load = useCallback(async () => {
    try {
      setData(await api.load())
      setError(null)
    } catch (e) {
      setError(errMsg(e))
    }
  }, [api])

  useEffect(() => {
    void load()
  }, [load])

  /** 统一提交：成功后以后端返回的最新视图覆盖本地 */
  const run = async (fn: () => Promise<PromptsView>, ok?: string) => {
    setBusy(true)
    try {
      setData(await fn())
      if (ok) notify('success', ok)
    } catch (e) {
      notify('error', errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  if (error) {
    return (
      <div className="flex flex-col gap-2">
        <kit.Note tone="warn">{error}</kit.Note>
        <button type="button" className={`${miniBtn} self-start`} onClick={() => void load()}>
          重试
        </button>
      </div>
    )
  }
  if (!data) {
    return <p className="text-sm text-hint">载入提示词…</p>
  }

  /* 当前大类：选中的标签 → 首个（写操作后 data 会被整体替换，标签选择按 key 保持） */
  const category = data.categories.find((c) => c.key === catKey) ?? data.categories[0]
  /* 全部提示词位：弹窗按 key 从这里派生最新快照 */
  const allParts = data.categories.flatMap((c) => c.contexts).flatMap((c) => c.parts)
  const viewerSlot = viewerKey ? (allParts.find((p) => p.key === viewerKey) ?? null) : null
  const dlgSlot = dlg ? (allParts.find((p) => p.key === dlg.slotKey) ?? null) : null

  /** 抓起某条长条：记住来源与同一上下文内的合法落点 */
  const grab = (ctx: PromptContext, key: string) => {
    dragRef.current = { key, targets: ctx.parts.map((p) => p.key) }
    overRef.current = key
    setDragKey(key)
    setOverKey(key)
  }

  /** 指针移动：命中测试找出落在哪条长条上（来源自身与其它上下文的块都不算）；只有落点变了才 setState */
  const trackGrab = (x: number, y: number) => {
    const from = dragRef.current
    if (!from) return
    const el = document.elementFromPoint(x, y)
    const bar = el instanceof HTMLElement ? el.closest('[data-slot-key]') : null
    const key = bar instanceof HTMLElement ? (bar.dataset.slotKey ?? null) : null
    const next = key && key !== from.key && from.targets.includes(key) ? key : null
    if (overRef.current === next) return
    overRef.current = next
    setOverKey(next)
  }

  /** 松手：把来源插到落点的位置并落库；落点为空或就是自己则视为放弃 */
  const releaseGrab = (context: PromptContext) => {
    const from = dragRef.current
    const target = overRef.current
    clearGrab()
    if (!from || !target || target === from.key) return
    const keys = context.parts.map((p) => p.key)
    const i = keys.indexOf(from.key)
    const j = keys.indexOf(target)
    if (i < 0 || j < 0) return
    keys.splice(j, 0, ...keys.splice(i, 1))
    void run(() => api.saveOrder(context.key, keys), '顺序已保存')
  }

  const submitVersion = async (req: { name: string; content: string }) => {
    if (!dlg) return
    const { slotKey, mode, versionId } = dlg
    /* 重命名只改名字：正文取该版本自己的内容，避免误用当前生效版本的内容覆盖它 */
    const target = versionId
      ? (dlgSlot?.versions.find((v) => v.id === versionId) ?? null)
      : null
    await run(
      () =>
        mode === 'rename' && target
          ? api.updateVersion(target.id, { name: req.name, content: target.content })
          : api.createVersion(slotKey, req),
      mode === 'rename' ? '版本已重命名' : '版本已新建',
    )
    setDlg(null)
  }

  const removeVersion = (slot: PromptSlot, v: PromptVersion) => {
    if (!window.confirm(`删除「${slot.name}」的版本「${v.name}」？`)) return
    void run(() => api.deleteVersion(v.id), '版本已删除')
  }

  return (
    <div className="flex max-w-[900px] flex-col gap-5">
      <header className="flex flex-col gap-1.5">
        <h2 className="ui-heading text-sm">提示词 &amp; 上下文工程</h2>
        <p className="text-sm leading-relaxed text-muted">
          {peerId
            ? '「系统级提示词」是这位智能体自己的一套，改了只影响它；「圆桌」那一类是全桌共用的协作机制（协作规程 + 调度文书），改了所有成员都受影响。每个上下文下方，长条自上而下就是交给模型的拼装顺序。'
            : '「开发者选项」是文档→图谱流水线上的自动环节（抽取 / 入库等）。每个上下文下方，长条自上而下就是它交给模型的拼装顺序；长条上只放类型与生效版本，正文在「查看提示词」里看和改，版本管理在「⋯」里。'}
        </p>
      </header>

      {/* 大类标签：提示词页是「系统级提示词 / 开发者选项」，智能体内部页是「系统级提示词 / 圆桌」 */}
      {data.categories.length > 1 && (
        <div className="flex flex-wrap gap-1.5">
          {data.categories.map((c) => (
            <CategoryTab key={c.key} active={category.key === c.key} onClick={() => setCatKey(c.key)}>
              {c.name}
            </CategoryTab>
          ))}
        </div>
      )}

      <div className="flex flex-col gap-6">
        <p className="text-xs leading-relaxed text-faint">{category.description}</p>

        {category.contexts.map((ctx) => (
          <section key={ctx.key} className="flex flex-col gap-2.5">
            {/* 一个大类下多个上下文时才需要小标题；只有一个时描述已足够说明 */}
            {category.contexts.length > 1 && (
              <h3 className="ui-heading text-base">{ctx.name}</h3>
            )}
            <p className="text-xs leading-relaxed text-faint">{ctx.description}</p>

            {ctx.parts.map((slot, i) => (
              <SlotBar
                key={slot.key}
                slot={slot}
                tone={PART_TONES[i] ?? PART_TONES[PART_TONES.length - 1]}
                reorderable={ctx.parts.length > 1}
                grabbing={dragKey === slot.key}
                dropTarget={overKey === slot.key}
                busy={busy}
                onGrab={() => grab(ctx, slot.key)}
                onGrabMove={trackGrab}
                onRelease={() => releaseGrab(ctx)}
                onView={() => setViewerKey(slot.key)}
                onBind={(versionId) =>
                  void run(
                    () => api.bind(slot.key, versionId),
                    versionId ? '已切换生效版本' : '已改回内置',
                  )
                }
                onCreate={() => setDlg({ slotKey: slot.key, mode: 'create', versionId: null })}
                onRename={(v) => setDlg({ slotKey: slot.key, mode: 'rename', versionId: v.id })}
                onDelete={(v) => removeVersion(slot, v)}
              />
            ))}

            {ctx.runtime.map((rt) => (
              <RuntimeBar key={rt.key} name={rt.name} description={rt.description} />
            ))}
          </section>
        ))}

        <kit.Note>虚线灰块是代码控制的运行时素材，位置由 API 协议固定，不可拖拽。</kit.Note>
      </div>

      {viewerSlot && (
        <PromptViewer
          slot={viewerSlot}
          onClose={() => setViewerKey(null)}
          onSave={async (content) => {
            try {
              setData(await api.saveContent(viewerSlot.key, content))
            } catch (e) {
              notify('error', errMsg(e))
              throw e
            }
          }}
        />
      )}

      {dlgSlot && dlg && (
        <NameDialog
          slot={dlgSlot}
          mode={dlg.mode}
          editing={
            dlg.versionId
              ? (dlgSlot.versions.find((v) => v.id === dlg.versionId) ?? null)
              : null
          }
          onClose={() => setDlg(null)}
          onSubmit={submitVersion}
        />
      )}

      <Toast message={toast?.msg ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    </div>
  )
}
