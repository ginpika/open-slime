import { useState } from 'react'
import type { Prop, PropType } from '../api/client'
import Toast from './Toast'
import { btnPrimary, btnQuiet, miniBtn } from './ui'

/**
 * KV 附加数据编辑器（实体卡片 / 边卡片共用）：
 * 只读态按类型渲染值（text 文本 / number 数字 / url 链接 / date 日期 / bool 勾选）；
 * 编辑态支持增删改行、切换类型，保存走覆盖式 PUT（后端负责校验与规范化）。
 */

const TYPES: PropType[] = ['text', 'number', 'url', 'date', 'bool']

const boolOf = (v: string) => v === 'true' || v === '1'

/** 只读值渲染：按类型展示 */
function PropValue({ p }: { p: Prop }) {
  if (p.t === 'url') {
    return (
      <a
        href={p.v}
        target="_blank"
        rel="noreferrer"
        title={p.v}
        className="block truncate text-sm text-faint underline underline-offset-4 transition hover:text-fg"
      >
        {p.v}
      </a>
    )
  }
  if (p.t === 'bool') {
    return (
      <span className={`text-sm ${boolOf(p.v) ? 'text-fg' : 'text-faint'}`}>
        {boolOf(p.v) ? '✓ 是' : '✗ 否'}
      </span>
    )
  }
  return (
    <span
      title={p.v}
      className={`block truncate text-right text-sm text-fg ${p.t === 'number' ? 'font-mono' : ''}`}
    >
      {p.v || <span className="text-faint">—</span>}
    </span>
  )
}

/* 行内小输入框：形状随主题（Endfield 下自动切角），focus 描边走点缀色令牌 */
const inputCls =
  'min-w-0 rounded-md border border-line bg-bg px-1.5 py-1 text-xs text-fg outline-none transition focus:border-accent'

type Props = {
  props: Prop[]
  /** 只读展示的系统属性（如 created_at/updated_at）：渲染在列表顶部，不进入编辑草稿、不随保存提交 */
  readOnlyProps?: Prop[]
  /** 覆盖式保存；resolve 后回到只读态并以后端返回值刷新 */
  onSave: (next: Prop[]) => Promise<Prop[]>
}

export default function PropsEditor({ props, readOnlyProps, onSave }: Props) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Prop[]>([])
  const [saving, setSaving] = useState(false)
  /** 保存失败轻提示（message box，替代编辑器下方错误文案） */
  const [toast, setToast] = useState<string | null>(null)

  const startEdit = () => {
    setDraft(props.map((p) => ({ ...p })))
    setEditing(true)
  }

  const save = async () => {
    const next = draft.filter((p) => p.k.trim())
    setSaving(true)
    setToast(null)
    try {
      await onSave(next)
      setEditing(false)
    } catch (e: unknown) {
      setToast(`保存失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setSaving(false)
    }
  }

  if (!editing) {
    return (
      <div className="mt-3">
        <div className="flex items-center justify-between">
          <p className="ui-label">Props</p>
          <button type="button" onClick={startEdit} className={miniBtn}>
            编辑
          </button>
        </div>
        {props.length === 0 && (readOnlyProps?.length ?? 0) === 0 ? (
          <p className="mt-2 text-xs text-faint">暂无附加数据。</p>
        ) : (
          <ul className="mt-1.5 space-y-1">
            {(readOnlyProps ?? []).map((p) => (
              <li key={p.k} className="flex min-w-0 items-baseline justify-between gap-2">
                <span className="shrink-0 text-sm text-faint">{p.k}</span>
                <span className="min-w-0 flex-1">
                  <PropValue p={p} />
                </span>
              </li>
            ))}
            {props.map((p) => (
              <li key={p.k} className="flex min-w-0 items-baseline justify-between gap-2">
                <span className="shrink-0 text-sm text-muted">{p.k}</span>
                <span className="min-w-0 flex-1">
                  <PropValue p={p} />
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    )
  }

  const patch = (i: number, part: Partial<Prop>) => {
    setDraft((prev) => prev.map((p, j) => (j === i ? { ...p, ...part } : p)))
  }

  return (
    <div className="mt-3">
      {/* 保存失败轻提示（message box）：视口顶部居中悬浮，自动/点击关闭 */}
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      <div className="flex items-center justify-between">
        <p className="ui-label">Props</p>
        <button
          type="button"
          onClick={() => setDraft((prev) => [...prev, { k: '', v: '', t: 'text' }])}
          disabled={draft.length >= 32}
          className={miniBtn}
        >
          + 添加
        </button>
      </div>
      <ul className="mt-1.5 max-h-52 space-y-1.5 overflow-y-auto pr-0.5">
        {draft.map((p, i) => (
          <li key={i} className="flex items-center gap-1">
            <input
              value={p.k}
              onChange={(e) => patch(i, { k: e.target.value })}
              placeholder="key"
              className={`${inputCls} flex-1`}
            />
            <select
              value={p.t}
              onChange={(e) => patch(i, { t: e.target.value as PropType })}
              className={`${inputCls} w-16 shrink-0`}
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
            {p.t === 'bool' ? (
              <label className="flex shrink-0 cursor-pointer items-center px-0.5">
                <input
                  type="checkbox"
                  checked={boolOf(p.v)}
                  onChange={(e) => patch(i, { v: e.target.checked ? 'true' : 'false' })}
                  className="h-3.5 w-3.5 accent-[color:var(--c-accent)]"
                />
              </label>
            ) : (
              <input
                type={p.t === 'date' ? 'date' : p.t === 'number' ? 'text' : 'text'}
                inputMode={p.t === 'number' ? 'decimal' : undefined}
                value={p.v}
                onChange={(e) => patch(i, { v: e.target.value })}
                placeholder="value"
                className={`${inputCls} flex-1`}
              />
            )}
            <button
              type="button"
              onClick={() => setDraft((prev) => prev.filter((_, j) => j !== i))}
              aria-label="删除该行"
              className="shrink-0 px-0.5 text-base leading-none text-faint transition hover:text-danger"
            >
              ×
            </button>
          </li>
        ))}
      </ul>
      <div className="mt-2 flex gap-1.5">
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving}
          className={btnPrimary('rounded-sm')}
        >
          {saving ? '保存中…' : '保存'}
        </button>
        <button type="button" onClick={() => setEditing(false)} className={btnQuiet('rounded-sm')}>
          取消
        </button>
      </div>
    </div>
  )
}
