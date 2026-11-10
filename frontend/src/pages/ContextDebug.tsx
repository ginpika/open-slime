import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  listSessions,
  sessionContextManifests,
  sessionRawContext,
  sessionScenes,
  type ContextManifest,
  type RawContext,
  type SceneState,
  type SessionItem,
} from '../api/client'
import { JsonViewer, RawContextView } from '../components/JsonViewer'
import { AppIcon } from '../components/AppIcon'
import { miniBtn } from '../components/ui'

/**
 * 上下文调试页（只读，入口 `#/debug`）：把「动态上下文到底装配了什么」摊开看。
 *
 * <p>三块视图对应后端的三个可观察面：
 *  - 装配视图：当前会话此刻会发给模型的请求体，按 Turn 切片，可逐 Turn 查看每轮请求 / 响应；
 *  - 出站 Manifest：每次实际提交的出站记录（模型、轮次、重试、发送次数、任务段、授权代号、选取与裁剪）；
 *  - 任务段：当前活动段与可恢复段，用来核对「跑团 → 代码 → 恢复」的切换是否符合预期。
 *
 * <p>不提供任何写操作；数据都来自既有诊断接口，不额外触发模型调用。
 */
type Tab = 'assembly' | 'manifests' | 'scenes'

async function loadSessionData(id: string) {
  const [raw, manifests, scenes] = await Promise.all([
    sessionRawContext(id),
    sessionContextManifests(id, 20).catch(() => [] as ContextManifest[]),
    sessionScenes(id).catch(() => null as SceneState | null),
  ])
  return { raw, manifests, scenes }
}

function fmtTime(ms: number): string {
  return new Date(ms).toLocaleString()
}

function fmtNum(n: number): string {
  return Math.round(n).toLocaleString('en-US')
}

function reasonSummary(manifest: ContextManifest): string[] {
  const counts = new Map<string, number>()
  for (const entry of manifest.selection.omitted) {
    for (const reason of entry.reasons) counts.set(reason, (counts.get(reason) ?? 0) + 1)
  }
  return [...counts.entries()].map(([reason, n]) => `${reason} × ${n}`)
}

export default function ContextDebug() {
  const [sessions, setSessions] = useState<SessionItem[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('assembly')
  const [raw, setRaw] = useState<RawContext | null>(null)
  const [manifests, setManifests] = useState<ContextManifest[]>([])
  const [scenes, setScenes] = useState<SceneState | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listSessions()
      .then((rows) => {
        setSessions(rows)
        setSelected((current) => current ?? rows[0]?.id ?? null)
      })
      .catch(() => setError('会话列表加载失败'))
  }, [])

  const reload = useCallback((id: string | null) => {
    if (id === null) return
    setLoading(true)
    setError(null)
    loadSessionData(id)
      .then((data) => {
        setRaw(data.raw)
        setManifests(data.manifests)
        setScenes(data.scenes)
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : '加载失败'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    reload(selected)
  }, [selected, reload])

  const visibleSessions = useMemo(() => {
    const keyword = filter.trim().toLowerCase()
    if (keyword === '') return sessions
    return sessions.filter((s) =>
      `${s.title ?? ''} ${s.id}`.toLowerCase().includes(keyword),
    )
  }, [sessions, filter])

  const current = sessions.find((s) => s.id === selected) ?? null
  /* 容量取最近一次真实出站（manifests 已按时间倒序）：总窗口、已用 prompt、输出预留与安全余量。 */
  const latest = manifests[0] ?? null
  const capacity = latest === null
    ? null
    : (() => {
        const total = latest.selection.contextWindow
        const used = Math.max(0, latest.selection.estimatedInputTokens)
        const reserve = Math.max(0, latest.selection.reservedOutputTokens)
        const safety = Math.max(0, latest.selection.safetyTokens)
        const remaining = Math.max(0, total - used)
        const usable = Math.max(0, total - used - reserve - safety)
        const share = (n: number) => (total <= 0 ? 0 : Math.min(100, (n / total) * 100))
        return { total, used, reserve, safety, remaining, usable, usedShare: share(used), reserveShare: share(reserve), safetyShare: share(safety) }
      })()

  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-6">
      <header className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-fg">上下文调试</h1>
          <p className="mt-1 text-sm text-muted">
            只读视图：装配结果、出站 Manifest 与任务段。入口 <code className="text-xs">#/debug</code>，
            后端同时输出 <code className="text-xs">[ctx]</code> 前缀的调度日志。
          </p>
        </div>
        <button
          type="button"
          onClick={() => reload(selected)}
          className={`${miniBtn} border-line bg-bg text-muted hover:text-fg`}
          aria-label="刷新当前会话的上下文诊断"
        >
          <AppIcon name="refresh" className="h-3.5 w-3.5" />
          刷新
        </button>
      </header>

      <div className="grid gap-4 lg:grid-cols-[16rem_1fr]">
        <aside className="rounded-xl border border-line bg-bg p-2">
          <label className="mb-2 flex items-center gap-1.5 rounded-lg border border-line px-2 py-1">
            <AppIcon name="search" className="h-3.5 w-3.5 shrink-0 text-faint" />
            <input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="筛选会话"
              aria-label="筛选会话"
              className="min-w-0 flex-1 bg-transparent text-sm text-fg outline-none placeholder:text-hint"
            />
          </label>
          <div className="max-h-[70vh] overflow-y-auto">
            {visibleSessions.map((s) => (
              <button
                key={s.id}
                type="button"
                onClick={() => setSelected(s.id)}
                aria-pressed={s.id === selected}
                className={`flex w-full flex-col items-start gap-0.5 rounded-lg px-2 py-1.5 text-left transition ${
                  s.id === selected ? 'bg-accent/10 text-accent' : 'text-fg hover:bg-fg/5'
                }`}
              >
                <span className="w-full truncate text-sm">{s.title || '未命名会话'}</span>
                <span className="w-full truncate text-xs text-faint">{s.id}</span>
              </button>
            ))}
            {visibleSessions.length === 0 && (
              <p className="px-2 py-3 text-sm text-faint">没有匹配的会话</p>
            )}
          </div>
        </aside>

        <section className="min-w-0 rounded-xl border border-line bg-bg p-3">
          {current === null ? (
            <p className="py-10 text-center text-sm text-faint">选择左侧会话查看上下文诊断</p>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 border-b border-line pb-2">
                <span className="min-w-0 flex-1 truncate text-sm font-medium text-fg">
                  {current.title || '未命名会话'}
                </span>
                <span className="text-xs text-faint">{current.id}</span>
              </div>

              {capacity === null ? (
                <p className="mt-2 text-xs text-faint">
                  上下文容量未知：暂无出站记录（开启试点并发起一轮对话后显示）
                </p>
              ) : (
                <div className="mt-2 rounded-lg border border-line/70 px-2.5 py-2">
                  <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
                    <span className="text-muted">
                      上下文容量 <span className="text-xs text-faint">（最近一次出站 · Turn {latest?.turnId}）</span>
                    </span>
                    <span className="text-fg">
                      已用 <b>{fmtNum(capacity.used)}</b> / 总 <b>{fmtNum(capacity.total)}</b>
                    </span>
                    <span className="text-muted">剩余 {fmtNum(capacity.remaining)}</span>
                    <span className="ml-auto text-xs text-accent">{capacity.usedShare.toFixed(1)}%</span>
                  </div>
                  <div
                    className="mt-1.5 flex h-2 overflow-hidden rounded-full bg-fg/5"
                    role="img"
                    aria-label={`上下文已用 ${capacity.usedShare.toFixed(1)}%，剩余 ${fmtNum(capacity.remaining)} tokens`}
                  >
                    <div className="h-full bg-accent" style={{ width: `${capacity.usedShare}%` }} />
                    <div className="h-full bg-amber-400/60" style={{ width: `${capacity.reserveShare}%` }} />
                    <div className="h-full bg-fg/15" style={{ width: `${capacity.safetyShare}%` }} />
                  </div>
                  <p className="mt-1 text-xs text-faint">
                    输出预留 {fmtNum(capacity.reserve)} · 安全余量 {fmtNum(capacity.safety)} · 剩余可用（扣除预留后）
                    {' '}{fmtNum(capacity.usable)}
                  </p>
                </div>
              )}

              <div className="mt-3 flex flex-wrap items-center gap-1.5" role="tablist" aria-label="上下文诊断视图">
                {([
                  ['assembly', '装配视图'],
                  ['manifests', `出站 Manifest${manifests.length > 0 ? ` · ${manifests.length}` : ''}`],
                  ['scenes', '任务段'],
                ] as [Tab, string][]).map(([key, label]) => (
                  <button
                    key={key}
                    type="button"
                    role="tab"
                    aria-selected={tab === key}
                    onClick={() => setTab(key)}
                    className={`rounded-full border px-3 py-1 text-sm transition ${
                      tab === key
                        ? 'border-accent/40 bg-accent/10 font-medium text-accent'
                        : 'border-line text-muted hover:text-fg'
                    }`}
                  >
                    {label}
                  </button>
                ))}
                {loading && <span className="text-xs text-faint">加载中…</span>}
                {error && <span className="text-xs text-red-500">{error}</span>}
              </div>

              <div className="mt-3">
                {tab === 'assembly' && (
                  raw === null ? (
                    <p className="py-8 text-center text-sm text-faint">暂无装配结果</p>
                  ) : (
                    <div className="max-h-[70vh] overflow-y-auto">
                      <RawContextView ctx={raw} sessionId={current.id} />
                    </div>
                  )
                )}

                {tab === 'manifests' && (
                  manifests.length === 0 ? (
                    <p className="py-8 text-center text-sm text-faint">
                      暂无出站记录（需要开启 <code className="text-xs">CONTEXT_EPHEMERAL_CHAT</code> 并发起一轮对话）
                    </p>
                  ) : (
                    <div className="max-h-[70vh] space-y-2 overflow-y-auto">
                      {manifests.map((m) => (
                        <article key={`${m.executionId}-${m.round}-${m.retry}-${m.submission}`}
                          className="rounded-lg border border-line/70 p-2.5">
                          <div className="flex flex-wrap items-center gap-2 text-sm">
                            <span className="font-medium text-fg">Turn {m.turnId}</span>
                            <span className="text-xs text-faint">
                              第 {m.round + 1} 轮 · 重试 {m.retry} · 第 {m.submission} 次发送
                            </span>
                            <span className="ml-auto text-xs text-faint">{fmtTime(m.submittedAt)}</span>
                          </div>
                          <dl className="mt-1.5 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-muted sm:grid-cols-4">
                            <div><dt className="inline text-faint">模型 </dt><dd className="inline text-fg">{m.model}</dd></div>
                            <div><dt className="inline text-faint">估算输入 </dt><dd className="inline text-fg">{m.estimatedInputTokens}</dd></div>
                            <div><dt className="inline text-faint">正文 </dt><dd className="inline text-fg">{m.bodyBytes} B</dd></div>
                            <div><dt className="inline text-faint">消息 </dt><dd className="inline text-fg">{m.messageRoles.length}</dd></div>
                            <div><dt className="inline text-faint">任务段 </dt><dd className="inline text-fg">{m.selection.sceneId ?? '普通'}</dd></div>
                            <div><dt className="inline text-faint">授权代号 </dt><dd className="inline text-fg">{m.selection.scopeRevision ?? 0}</dd></div>
                            <div><dt className="inline text-faint">选中 </dt><dd className="inline text-fg">{m.selection.selected.length}</dd></div>
                            <div><dt className="inline text-faint">裁剪 </dt><dd className="inline text-fg">{m.selection.omittedCount}</dd></div>
                          </dl>
                          <p className="mt-1 text-xs text-faint">
                            Epoch {m.selection.epoch ? `${m.selection.epoch.reason}（#${m.selection.epoch.id}）` : '无'}
                            {reasonSummary(m).length > 0 && <> · 原因：{reasonSummary(m).join('、')}</>}
                          </p>
                          {m.selection.selected.length > 0 && (
                            <p className="mt-1 break-all text-xs text-faint">
                              入选：{m.selection.selected.map((entry) => entry.source).join('、')}
                            </p>
                          )}
                          <details className="mt-1.5">
                            <summary className="cursor-pointer text-xs text-accent">查看完整选取 JSON</summary>
                            <div className="mt-1 max-h-72 overflow-y-auto rounded-md bg-fg/[0.02] p-2">
                              <JsonViewer value={m.selection} openDepth={0} />
                            </div>
                          </details>
                        </article>
                      ))}
                    </div>
                  )
                )}

                {tab === 'scenes' && (
                  <div className="space-y-3">
                    <div>
                      <h2 className="text-sm font-medium text-fg">当前任务段</h2>
                      {scenes?.active ? (
                        <div className="mt-1 rounded-lg border border-accent/30 bg-accent/5 p-2.5 text-sm">
                          <p className="text-fg">#{scenes.active.id} · {scenes.active.name || '未命名任务'}</p>
                          {scenes.active.rolePrompt && (
                            <p className="mt-1 whitespace-pre-wrap text-xs text-muted">角色：{scenes.active.rolePrompt}</p>
                          )}
                          {scenes.active.constraints && (
                            <p className="mt-1 whitespace-pre-wrap text-xs text-muted">约束：{scenes.active.constraints}</p>
                          )}
                        </div>
                      ) : (
                        <p className="mt-1 text-sm text-faint">普通聊天（无任务段）</p>
                      )}
                    </div>
                    <div>
                      <h2 className="text-sm font-medium text-fg">可恢复任务段</h2>
                      {(scenes?.resumable.length ?? 0) === 0 ? (
                        <p className="mt-1 text-sm text-faint">暂无</p>
                      ) : (
                        <ul className="mt-1 space-y-1">
                          {scenes?.resumable.map((scene) => (
                            <li key={scene.id} className="rounded-lg border border-line/70 px-2.5 py-1.5 text-sm">
                              <span className="text-fg">#{scene.id} · {scene.name || '未命名任务'}</span>
                              <span className="ml-2 text-xs text-faint">{fmtTime(new Date(scene.createdAt).getTime())}</span>
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                )}
              </div>
            </>
          )}
        </section>
      </div>
    </div>
  )
}
