import { useEffect, useRef, useState } from 'react'

/**
 * 定制化分段文本编辑框（「最终提交预案」承载）：
 * 内部分为多个部分（segment），部分由调用方持有（后续将由前端按钮增删）；
 * 预览态：各部分只读展示，hover 某部分出现该部分的悬浮提示（部分名称 + 操作引导）；
 * 点击某部分 → 该部分变为可编辑（textarea 就地编辑，随内容自动增高，实时回调）；
 * 点击编辑框外任意区域 → 整体回到不可编辑的预览态（点击另一部分则直接切换编辑目标）。
 */

/** 编辑框的一个部分（id 供增删与编辑定位，label 供悬浮提示） */
export type EditorSegment = {
  id: string
  label: string
  text: string
}

type Props = {
  segments: EditorSegment[]
  /** 某部分文本被编辑（实时回调，无需失焦提交） */
  onChangeText: (id: string, text: string) => void
  readOnly?: boolean
}

/** 单个部分：预览态（hover 提示 + 点击进入编辑）/ 编辑态（textarea） */
function SegmentBlock({
  seg,
  editing,
  readOnly,
  onEnterEdit,
  onExitEdit,
  onChangeText,
}: {
  seg: EditorSegment
  editing: boolean
  readOnly: boolean
  onEnterEdit: () => void
  onExitEdit: () => void
  onChangeText: (text: string) => void
}) {
  const taRef = useRef<HTMLTextAreaElement>(null)

  /** textarea 随内容自动增高（保持与预览态近似的视觉高度） */
  useEffect(() => {
    const ta = taRef.current
    if (!ta) return
    ta.style.height = 'auto'
    ta.style.height = `${ta.scrollHeight}px`
  }, [seg.text, editing])

  return (
    <div
      className={`group relative rounded-sm border transition ${
        editing
          ? 'border-line-strong bg-bg'
          : readOnly ? 'border-line/70 bg-bg/60' : 'cursor-text border-line/70 bg-bg/60 hover:border-line-strong hover:bg-bg'
      }`}
    >
      {/* hover 悬浮提示（仅预览态）：部分名称 + 操作引导 */}
      {!editing && !readOnly && (
        <span className="pointer-events-none absolute -top-2.5 left-2 z-20 hidden rounded-sm border border-line bg-bg px-1.5 py-0.5 text-2xs font-medium text-faint shadow-sm group-hover:block">
          {seg.label} · 点击编辑
        </span>
      )}
      {editing && !readOnly ? (
        <textarea
          ref={taRef}
          className="scrollbar-slim block w-full resize-none bg-transparent px-2.5 py-2 text-sm leading-relaxed text-fg outline-none"
          value={seg.text}
          placeholder="（空）"
          autoFocus
          aria-label={`编辑部分：${seg.label}`}
          onChange={(e) => onChangeText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onExitEdit()
          }}
        />
      ) : (
        <p
          onClick={readOnly ? undefined : onEnterEdit}
          role={readOnly ? undefined : 'button'}
          tabIndex={readOnly ? undefined : 0}
          aria-label={readOnly ? undefined : `编辑${seg.label}`}
          onKeyDown={event => {
            if (!readOnly && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onEnterEdit() }
          }}
          className={`whitespace-pre-wrap break-words px-2.5 py-2 text-sm leading-relaxed ${
            seg.text ? 'text-fg' : 'text-faint'
          }`}
        >
          {seg.text || '（空 · 点击填写）'}
        </p>
      )}
    </div>
  )
}

export default function SegmentEditor({ segments, onChangeText, readOnly = false }: Props) {
  /** 当前编辑中的部分 id（null = 整体预览态） */
  const [editingId, setEditingId] = useState<string | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)

  /** 点击编辑框外任意区域：退出编辑，回到预览态（框内点击不触发，可跨部分切换编辑） */
  useEffect(() => {
    if (editingId === null) return
    const onPointerDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setEditingId(null)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [editingId])

  return (
    <div
      ref={rootRef}
      className="scrollbar-slim flex max-h-133.5 flex-col gap-2 overflow-y-auto rounded-md border border-line bg-fg/[0.02] p-2.5"
    >
      {segments.map((seg) => (
        <SegmentBlock
          key={seg.id}
          seg={seg}
          editing={editingId === seg.id && !readOnly}
          readOnly={readOnly}
          onEnterEdit={() => setEditingId(seg.id)}
          onExitEdit={() => setEditingId(null)}
          onChangeText={(text) => onChangeText(seg.id, text)}
        />
      ))}
    </div>
  )
}
