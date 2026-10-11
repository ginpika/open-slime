/**
 * SystemPanel：**全局系统**页——观测台 / 工具 / 开发者选项，内部标签切换。
 *
 * <p>这里只放「全体共享」的东西：观测台（全部 Agent 的心跳与跨 Agent 的最近执行）、工具一览、
 * 开发者选项提示词（文档→图谱流水线）。每位 Agent 自己的后台（心跳 / 常驻职责 / 子代理 / RAG / 任务）
 * 在 {@link AgentBackground}，不在本页。
 *
 * <p>观测台复用旧 dashboard 的观感：心跳状态行 + 最近事件长条（条内内联本批执行，点开看详情）；
 * 详情浮窗与格式化零件复用 {@link AgentBackground} 导出的共用组件。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  systemPulse,
  systemStream,
  systemTools,
  type ExecView,
  type HeartbeatState,
  type PulseView,
  type ToolInfoView,
} from '../api/client'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import { fmtDay, fmtDur, ResultDetail, StatusDot, textPreview, ViewTab } from './AgentBackground'
import PromptsPanel from './PromptsPanel'

/** 心跳重连间隔（后端单条流有最长保持时长，断开属正常） */
const RECONNECT_MS = 1500

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

/** 心跳状态点：执行中=脉冲；待命=实心点缀；停摆=中性 */
function BeatDot({ state }: { state: HeartbeatState }) {
  const tone = state.running
    ? 'animate-pulse bg-accent'
    : state.enabled
      ? 'bg-accent'
      : 'bg-fg/30'
  return <span className={`h-2 w-2 shrink-0 rounded-full ${tone}`} />
}

/* ---------------- 观测台 ---------------- */

function DashboardTab() {
  const kit = usePageKit()
  const [pulse, setPulse] = useState<PulseView | null>(null)
  const [live, setLive] = useState(false)
  const [detail, setDetail] = useState<ExecView | null>(null)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)

  const notify = useCallback(
    (kind: 'success' | 'error', msg: string) => setToast({ kind, msg }),
    [],
  )

  useEffect(() => {
    let alive = true
    systemPulse()
      .then((p) => {
        if (alive) setPulse(p)
      })
      .catch((e: unknown) => {
        if (alive) notify('error', errMsg(e))
      })
    return () => {
      alive = false
    }
  }, [notify])

  /* 观测台实时流：循环重连（单条流有时长上限，断开属正常） */
  useEffect(() => {
    const ac = new AbortController()
    let stopped = false
    const pump = async () => {
      while (!stopped) {
        try {
          await systemStream((p) => {
            setLive(true)
            setPulse(p)
          }, ac.signal)
        } catch {
          /* 断流 / 后端重启：落到下方重连 */
        }
        if (stopped) break
        setLive(false)
        await new Promise((r) => setTimeout(r, RECONNECT_MS))
      }
    }
    void pump()
    return () => {
      stopped = true
      ac.abort()
    }
  }, [])

  const heartbeats = pulse?.heartbeats ?? []
  const runs = pulse?.recentRuns ?? []
  /* 名册里可能只含被启用的心跳；Run 的归属 Agent 名尽量用观测台给的名字，取不到就回显 id */
  const nameOf = (agentId: string) =>
    heartbeats.find((h) => h.agentId === agentId)?.name ?? agentId

  return (
    <div className="flex flex-col gap-3.5">
      {/* 心跳：每位 Agent 的状态（名字 / 开关 / 节拍 / 最近敲响 / 是否在跑） */}
      <kit.Panel
        title="心跳"
        meta={
          <kit.Badge tone={live ? 'ok' : 'warn'}>
            {live ? 'STREAM LIVE' : 'RECONNECTING'}
          </kit.Badge>
        }
      >
        {heartbeats.length === 0 ? (
          <kit.Empty>还没有启用心跳的智能体 —— 到某位成员的「后台 · 心跳」里打开常驻心跳。</kit.Empty>
        ) : (
          <div className="flex flex-col">
            {heartbeats.map((h) => (
              <kit.Row key={h.agentId} className="flex-wrap gap-x-3 gap-y-1">
                <BeatDot state={h} />
                <span className="truncate text-sm font-medium text-fg">{h.name}</span>
                <span className="mono shrink-0 text-2xs text-dim">{h.agentId}</span>
                <span className="mono text-2xs text-dim">
                  节拍 {h.tickMs}ms
                  <span className="mx-1.5">·</span>最近敲响 {fmtDay(h.lastBeatAt)}
                </span>
                <span
                  className={`mono ml-auto shrink-0 text-2xs ${h.running ? 'text-accent' : 'text-dim'}`}
                >
                  {h.running ? '执行中' : h.enabled ? '待命' : '已停摆'}
                </span>
              </kit.Row>
            ))}
          </div>
        )}
      </kit.Panel>

      {/* 最近事件：每批一条长条，条内内联本批的各次执行（标注归属 Agent） */}
      <kit.Panel title="最近事件" meta={runs.length > 0 ? `LAST ${runs.length} BATCHES` : undefined}>
        {runs.length === 0 ? (
          <kit.Empty>还没有跑过一轮心跳</kit.Empty>
        ) : (
          <div className="flex flex-col gap-2">
            {runs.map(({ run, execs }) => (
              <article
                key={run.id}
                className="flex flex-wrap items-center gap-x-3 gap-y-2 border border-line px-3.5 py-3 transition hover:border-line-strong hover:bg-accent-soft"
              >
                <span className="flex shrink-0 items-center gap-2">
                  <StatusDot status={run.status} />
                  <kit.Badge>{nameOf(run.agentId)}</kit.Badge>
                  <span className="mono border border-line px-1.5 py-0.5 text-2xs font-bold text-fg">
                    #{run.beat}
                  </span>
                  <span className="mono text-2xs text-dim">
                    {run.trigger === 'manual' ? '手动' : run.trigger === 'chat' ? '对话委托' : '心跳'}
                    <span className="mx-1.5">·</span>
                    {fmtDay(run.startedAt)}
                    <span className="mx-1.5">·</span>
                    {fmtDur(run.startedAt, run.finishedAt)}
                    <span className="mx-1.5">·</span>
                    {run.agentCount} 次执行
                    {run.note ? ` · ${run.note}` : ''}
                  </span>
                </span>

                {execs.length === 0 ? (
                  <span className="text-2xs text-dim">本批没有执行产出</span>
                ) : (
                  <div className="flex flex-1 flex-wrap items-center gap-1.5">
                    {execs.map((r) => (
                      <button
                        key={r.id}
                        type="button"
                        onClick={() => setDetail(r)}
                        title={textPreview(r.error ?? r.output, 300) || '（无结论）'}
                        className="flex max-w-full items-center gap-2 border border-line px-2.5 py-1.5 text-left transition hover:border-line-strong hover:bg-accent-soft"
                      >
                        <StatusDot status={r.status} />
                        <span className="max-w-[180px] truncate text-xs font-medium text-fg">
                          {r.title ?? '（未知）'}
                        </span>
                        <span className="mono shrink-0 text-2xs text-dim">
                          {fmtDur(r.startedAt, r.finishedAt)}
                        </span>
                        {r.edits.length > 0 && <kit.Badge>写库 {r.edits.length}</kit.Badge>}
                      </button>
                    ))}
                  </div>
                )}
              </article>
            ))}
          </div>
        )}
      </kit.Panel>

      {detail && <ResultDetail detail={detail} onClose={() => setDetail(null)} />}

      <Toast message={toast?.msg ?? null} kind={toast?.kind} onClose={() => setToast(null)} />
    </div>
  )
}

/* ---------------- 工具（只读词典） ---------------- */

/** 工具面中文名：模型一次调用里能看见哪些工具，由它落在哪些面上决定 */
const TOOL_SURFACE_LABELS: Record<string, string> = {
  CHAT: '对话',
  HEARTBEAT: '心跳主会话',
  THREAD: '子代理会话',
  PEER: '智能体成员',
  EXTRACT: '文档分析 · 抽取',
  COMMIT: '文档分析 · 入库',
}

/**
 * 工具子页：一份从头上到尾的说明文档——系统里每个工具叫什么、干什么、长在哪些工具面上、参数怎么给。
 * 不用卡片、不折叠，所有文字都在页面上，可以直接 Ctrl+F 搜。
 */
function ToolsTab() {
  const kit = usePageKit()
  const [tools, setTools] = useState<ToolInfoView[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    systemTools()
      .then(setTools)
      .catch((e: unknown) => setError(errMsg(e)))
  }, [])

  if (error) {
    return <p className="text-sm text-hint">{error}</p>
  }
  if (!tools) {
    return <p className="text-sm text-hint">载入工具词典…</p>
  }

  return (
    <article className="flex max-w-[760px] flex-col gap-7 text-sm leading-relaxed text-muted">
      <header className="flex flex-col gap-1.5">
        <h2 className="ui-heading text-sm">工具</h2>
        <p>
          工具是大模型能亲手做的事，每个工具是一份写死的编程契约：叫什么、干什么、参数怎么给、
          可出现在哪些工具面。一次调用里模型能看见哪些工具，由「工具面」决定——同一个工具可落在多个面上。
          新增一个工具就是新增一个工具类，这份清单自动跟着变。
        </p>
      </header>

      <section className="flex flex-col">
        <h3 className="ui-heading text-sm">
          全部工具
          <span className="mono ml-2 text-2xs font-normal text-faint">共 {tools.length} 个</span>
        </h3>
        <div className="mt-1 flex flex-col divide-y divide-line">
          {tools.map((t) => {
            const required = t.parameters.required ?? []
            const props = Object.entries(t.parameters.properties ?? {})
            return (
              <div key={t.name} className="flex flex-col gap-1.5 py-3.5">
                <div className="flex flex-wrap items-center gap-1.5">
                  <p className="font-mono text-sm font-semibold text-fg">{t.name}</p>
                  {t.surfaces.map((s) => (
                    <kit.Badge key={s}>{TOOL_SURFACE_LABELS[s] ?? s}</kit.Badge>
                  ))}
                  {t.writes && <kit.Badge tone="accent">写库</kit.Badge>}
                  {t.parallelizable && <kit.Badge tone="ok">可并行</kit.Badge>}
                  {!t.agentsMayCall && <kit.Badge tone="warn">子代理不可调</kit.Badge>}
                </div>
                <p>{t.description || '—'}</p>
                <p className="text-hint">参数：{props.length ? '' : '无'}</p>
                {props.length > 0 && (
                  <div className="flex flex-col gap-0.5">
                    {props.map(([name, p]) => (
                      <p key={name} className="text-hint">
                        <span className="font-mono text-muted">{name}</span>
                        {required.includes(name) && <span className="ml-1">（必填）</span>}
                        {p.type ? ` · ${p.type}` : ''}
                        {p.description ? ` — ${p.description}` : ''}
                      </p>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </section>
    </article>
  )
}

/* ---------------- 页面 ---------------- */

type SysView = 'dashboard' | 'tools' | 'dev'

const SYS_TABS: { key: SysView; label: string }[] = [
  { key: 'dashboard', label: '观测台' },
  { key: 'tools', label: '工具' },
  { key: 'dev', label: '开发者选项' },
]

/** 全局系统页：观测台 / 工具 / 开发者选项，内部标签切换。 */
export default function SystemPanel() {
  const [view, setView] = useState<SysView>('dashboard')

  return (
    <div className="flex flex-col gap-5">
      <div className="flex w-fit shrink-0 flex-wrap border border-line-strong">
        {SYS_TABS.map((t) => (
          <ViewTab key={t.key} active={view === t.key} onClick={() => setView(t.key)}>
            {t.label}
          </ViewTab>
        ))}
      </div>

      {view === 'dashboard' && <DashboardTab />}
      {view === 'tools' && <ToolsTab />}
      {view === 'dev' && (
        <div className="flex flex-col gap-4">
          <div className="min-w-0">
            <h2 className="ui-heading text-md">开发者选项</h2>
            <p className="mt-1 max-w-[760px] text-xs leading-relaxed text-muted">
              开发者选项：文档→图谱流水线上的自动环节所用的提示词。
            </p>
          </div>
          <PromptsPanel />
        </div>
      )}
    </div>
  )
}
