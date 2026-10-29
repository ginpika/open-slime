import { useState } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { dropMaTodo, deferMaTodo, checkMaTodo, MA_EXEC_LABELS, type MaWorkflow, type MaWorkflowAgent, type MaWorkflowTodo } from '../api/client'
import PeerAvatar from '../components/PeerAvatar'
import WorkReview from './collaboration/WorkReview'

const ACCENT = 'var(--c-accent, #eab308)'
const COMM_LABELS: Record<string, string> = { SPEAKING: '发言中', LISTENING: '在听', SILENT: '不发言' }
const TODO_LABELS: Record<string, string> = { pending: '待办', doing: '进行中', reviewing: '待检查', checking: '检查中', blocked: '受阻', done: '已结束', dropped: '已放弃' }
function todoTone(status: string) { return status === 'doing' ? ACCENT : status === 'blocked' ? 'var(--c-fg)' : 'var(--c-fg-2)' }
function Badge({ text, color = 'var(--c-fg-2)' }: { text: string; color?: string }) {
  return <span className="inline-flex shrink-0 rounded-full px-2 py-0.5 text-xs leading-4" style={{ color, background: 'color-mix(in srgb, ' + color + ' 10%, transparent)' }}>{text}</span>
}

export function TodoHead({ t, avatar, waiting }: { t: MaWorkflowTodo; avatar?: string | null; waiting: string[] }) {
  const tone = todoTone(t.status)
  const note = t.status === 'blocked' ? t.blockReason : t.status === 'done' ? t.result : t.detail
  return (
    <>
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-sm text-fg">{t.title}</span>
        <span className="shrink-0">
          <Badge text={TODO_LABELS[t.status] ?? t.status} color={tone} />
        </span>
      </div>
      {note && <div className="truncate text-sm text-fg-2" title={note}>{note}</div>}
      {/* 还在等前置：把「为什么它还没开工」写在卡上（未完成的前置标题） */}
      {waiting.length > 0 && (
        <div className="truncate text-xs text-faint">等待：{waiting.join('、')}</div>
      )}
      {/* 两种视角都保留负责人，独立任务也能看出由谁负责。 */}
      {(
        <div className="flex items-center gap-1.5">
          <PeerAvatar
            name={t.ownerName || '公共'}
            avatar={avatar}
            size="h-6 w-6"
            textClass="text-xs"
            bordered={false}
          />
          <span className="truncate text-xs font-medium text-muted">
            {t.ownerName || '公共'}
          </span>
        </div>
      )}
    </>
  )
}

/**
 * 任务卡右上角 ⋯ 弹出的菜单：人对这条任务的三项干预。
 *
 * <p>都<b>不打断</b>正在跑的那次执行（不抢占）：放弃 = 置 dropped（下游依赖随之解锁）；
 * 暂时跳过 = 压到最低优先级（进行中的先放回队列，跑完那条不被收成完成、转而排到最后）；
 * 立刻检查 = 拉满优先级插到该成员队首并叫起它的执行线程（受阻的会先放回待做，才跑得起来）。
 */
export function TodoMenu({ t, onClose }: { t: MaWorkflowTodo; onClose: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = (action: (id: string) => Promise<unknown>) => (e: ReactMouseEvent) => {
    e.stopPropagation()
    if (busy) return
    setBusy(true)
    setError(null)
    void action(t.id)
      .then(result => {
        if (result && typeof result === 'object' && 'ok' in result && !result.ok) throw new Error('任务未能更新，请重试。')
        onClose()
      })
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => {
        setBusy(false)
      })
  }
  const pending = t.status === 'pending'
  const doing = t.status === 'doing'
  const blocked = t.status === 'blocked'
  return (
    <div
      data-todo-menu
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => e.stopPropagation()}
      className="absolute right-1 top-7 z-30 w-48 overflow-hidden rounded-md py-1 text-sm"
      style={{
        background: 'var(--ui-card-fill, var(--c-bg))',
        boxShadow: 'var(--ui-e4, 0 12px 28px rgb(0 0 0 / .35))',
      }}
    >
      <MenuBtn label="放弃" disabled={busy || t.status === 'dropped'} onClick={run(dropMaTodo)} />
      <MenuBtn label="暂时跳过" disabled={busy || !(pending || doing)} onClick={run(deferMaTodo)} />
      <MenuBtn label="立刻检查" disabled={busy || !(pending || blocked || t.status === 'reviewing')} onClick={run(checkMaTodo)} />
      {error && <p role="alert" className="px-3 py-2 text-sm text-fg">{error}</p>}
    </div>
  )
}

function MenuBtn({
  label,
  disabled,
  onClick,
}: {
  label: string
  disabled?: boolean
  onClick: (e: ReactMouseEvent) => void
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="block w-full px-3 py-1.5 text-left text-fg-2 transition hover:bg-fg/[0.08] hover:text-fg disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent"
    >
      {label}
    </button>
  )
}

/** 成员活动沿用紧凑视图；工作详情进入独立的成果与执行审阅器。 */
export function Detail({
  sel,
  data,
  onSelect,
  agentOf,
  todoOf,
}: {
  sel: { kind: 'agent' | 'todo'; id: string } | null
  data: MaWorkflow | null
  onSelect: (s: { kind: 'agent' | 'todo'; id: string }) => void
  agentOf: (id: string) => MaWorkflowAgent | undefined
  todoOf: (id: string) => MaWorkflowTodo | undefined
}) {
  if (sel?.kind === 'todo') {
    const todo = todoOf(sel.id)
    return todo && data?.sessionId
      ? <WorkReview key={`${data.sessionId}:${todo.id}`} todo={todo} sessionId={data.sessionId} todoOf={todoOf} onSelect={onSelect} />
      : <p className="p-4 text-base text-muted">这条工作已经不在了。</p>
  }

  return (
    <div className="scrollbar-slim max-h-[60vh] overflow-y-auto px-4 py-3">
      {!sel && <p className="text-sm text-faint">点画布上的成员或任务，这里会显示它的过程。</p>}

      {sel?.kind === 'agent' &&
        (() => {
          const a = agentOf(sel.id)
          if (!a) return <p className="text-sm text-faint">这个成员已经不在了。</p>
          const mine = data?.todos.filter((t) => t.ownerPeer === a.id) ?? []
          return (
            <div className="space-y-3">
              <div>
                <h3 className="text-sm font-medium text-fg">{a.name || a.id}</h3>
                <div className="mt-1 flex flex-wrap gap-1">
                  {a.communication && <Badge text={COMM_LABELS[a.communication] ?? a.communication} />}
                  <Badge text={MA_EXEC_LABELS[a.execution] ?? a.execution} color={a.working ? ACCENT : undefined} />
                </div>
              </div>
              <div>
                <p className="mb-1 text-xs uppercase tracking-wider text-faint">它名下的任务</p>
                {mine.length === 0 && <p className="text-xs text-faint">暂时没有任务。</p>}
                <div className="space-y-1">
                  {mine.map((t) => (
                    <button
                      key={t.id}
                      type="button"
                      onClick={() => onSelect({ kind: 'todo', id: t.id })}
                      className="flex w-full items-center gap-2 rounded border border-line px-2 py-1.5 text-left transition hover:border-line-strong"
                    >
                      <span className="truncate text-sm text-fg">{t.title}</span>
                      <span className="ml-auto shrink-0">
                        <Badge text={TODO_LABELS[t.status] ?? t.status} color={todoTone(t.status)} />
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )
        })()}

    </div>
  )
}
