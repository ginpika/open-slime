/**
 * SystemPanel：**全局系统**页——观测台 / 工具 / 开发者选项，内部标签切换。
 *
 * <p>这里只放「全体共享」的东西：观测台（全部 Agent 的心跳与跨 Agent 的最近执行）、工具一览、
 * 开发者选项提示词（文档→图谱流水线）。每位 Agent 自己的后台（心跳 / 常驻职责 / 子代理 / RAG / 任务）
 * 在 {@link AgentBackground}，不在本页。
 *
 * 观测台的执行详情与格式化复用 {@link AgentBackground}。
 */

import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  systemPulse,
  systemStream,
  systemTools,
  type ExecView,
  type PulseView,
  type ToolInfoView,
} from '../api/client'
import Toast from '../components/Toast'
import { fmtDay, fmtDur, ResultDetail, StatusDot, textPreview } from './AgentBackground'
import PromptsPanel from './PromptsPanel'
import './systemPanel.css'

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

const STATUS_LABELS: Record<string, string> = {
  done: '已完成',
  failed: '失败',
  interrupted: '已中断',
  running: '执行中',
}

function RunStatus({ status }: { status: string }) {
  return (
    <span className="sys-status">
      <StatusDot status={status} />
      {STATUS_LABELS[status] ?? status}
    </span>
  )
}

/* ---------------- 观测台 ---------------- */

function DashboardTab() {
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
    <div className="sys-sections">
      <section aria-labelledby="sys-heartbeat-heading">
        <header className="sys-section-head">
          <h2 id="sys-heartbeat-heading">心跳</h2>
          <span className="sys-status" role="status">
            <span className={`sys-dot${live ? ' is-active' : ''}`} aria-hidden="true" />
            {live ? '实时更新' : pulse ? '正在重连' : '正在连接'}
          </span>
        </header>
        {heartbeats.length === 0 ? (
          <p className="sys-empty">
            {pulse ? '暂无心跳记录。可在成员的「后台」中启用心跳。' : '正在加载心跳…'}
          </p>
        ) : (
          <ul className="sys-list" role="list">
            {heartbeats.map((h) => (
              <li key={h.agentId} className="sys-entry">
                <div className="sys-entry-head">
                  <strong>{h.name}</strong>
                  <span className="sys-status">
                    <span className={`sys-dot${h.running || h.enabled ? ' is-active' : ''}`} aria-hidden="true" />
                    {h.running ? '执行中' : h.enabled ? '待命' : '已停用'}
                  </span>
                </div>
                <p className="sys-meta">
                  <code>{h.agentId}</code>
                  <span>节拍 {h.tickMs} ms</span>
                  <span>最近敲响 {fmtDay(h.lastBeatAt)}</span>
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="sys-recent-heading">
        <header className="sys-section-head">
          <h2 id="sys-recent-heading">最近事件</h2>
          {runs.length > 0 && <span className="sys-label">{runs.length} 批</span>}
        </header>
        {runs.length === 0 ? (
          <p className="sys-empty">{pulse ? '暂无执行记录。' : '正在加载最近事件…'}</p>
        ) : (
          <ul className="sys-list" role="list">
            {runs.map(({ run, execs }) => (
              <li key={run.id} className="sys-entry">
                <div className="sys-entry-head">
                  <strong>{nameOf(run.agentId)} <span className="sys-meta">#{run.beat}</span></strong>
                  <RunStatus status={run.status} />
                </div>
                <p className="sys-meta">
                  <span>{run.trigger === 'manual' ? '手动' : run.trigger === 'chat' ? '对话委托' : '心跳'}</span>
                  <span>{fmtDay(run.startedAt)}</span>
                  <span>{fmtDur(run.startedAt, run.finishedAt)}</span>
                  <span>{run.agentCount} 次执行</span>
                </p>
                {run.note && <p className="sys-meta">{run.note}</p>}

                {execs.length === 0 ? (
                  <p className="sys-meta">本批没有执行产出</p>
                ) : (
                  <div className="sys-execs">
                    {execs.map((r) => (
                      <button
                        key={r.id}
                        type="button"
                        onClick={() => setDetail(r)}
                        title={textPreview(r.error ?? r.output, 300) || '（无结论）'}
                        className="sys-exec"
                      >
                        <span>{r.title ?? '（未知）'}</span>
                        <RunStatus status={r.status} />
                        <span className="sys-meta">{fmtDur(r.startedAt, r.finishedAt)}</span>
                        {r.edits.length > 0 && <span className="sys-label">写库 {r.edits.length}</span>}
                      </button>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

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
  const [tools, setTools] = useState<ToolInfoView[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    systemTools()
      .then(setTools)
      .catch((e: unknown) => setError(errMsg(e)))
  }, [])

  if (error) {
    return <p className="sys-error" role="alert">{error}</p>
  }
  if (!tools) {
    return <p className="sys-empty" role="status">正在加载工具…</p>
  }

  return (
    <section aria-labelledby="sys-tools-heading">
      <header className="sys-section-head">
        <h2 id="sys-tools-heading">工具清单</h2>
        <span className="sys-label">{tools.length} 个</span>
      </header>
      {tools.length === 0 ? (
        <p className="sys-empty">暂无可用工具。</p>
      ) : (
        <ul className="sys-list" role="list">
          {tools.map((t) => {
            const required = t.parameters.required ?? []
            const props = Object.entries(t.parameters.properties ?? {})
            return (
              <li key={t.name} className="sys-entry">
                <h3><code>{t.name}</code></h3>
                <div className="sys-labels">
                  {t.surfaces.map((s) => (
                    <span key={s} className="sys-label">{TOOL_SURFACE_LABELS[s] ?? s}</span>
                  ))}
                  {t.writes && <span className="sys-label">写库</span>}
                  {t.parallelizable && <span className="sys-label">可并行</span>}
                  {!t.agentsMayCall && <span className="sys-label">子代理不可调</span>}
                </div>
                {t.description && <p className="sys-description">{t.description}</p>}
                {props.length > 0 ? (
                  <dl className="sys-parameters" aria-label="参数">
                    {props.map(([name, p]) => (
                      <div key={name}>
                        <dt><code>{name}</code>{required.includes(name) && '（必填）'}</dt>
                        <dd>{[p.type, p.description].filter(Boolean).join(' · ') || '—'}</dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <p className="sys-meta">无参数</p>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

/* ---------------- 页面 ---------------- */

const SYS_TABS = [
  { key: 'dashboard', label: '观测台' },
  { key: 'tools', label: '工具' },
  { key: 'dev', label: '开发者选项' },
] as const

type SysView = (typeof SYS_TABS)[number]['key']

/** 全局系统页：观测台 / 工具 / 开发者选项，内部标签切换。 */
export default function SystemPanel() {
  const [view, setView] = useState<SysView>('dashboard')

  return (
    <div className="sys-panel">
      <div className="sys-tabs" role="group" aria-label="系统视图">
        {SYS_TABS.map((t) => (
          <button
            key={t.key}
            id={`sys-tab-${t.key}`}
            type="button"
            aria-pressed={view === t.key}
            aria-controls="sys-content"
            onClick={() => setView(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <section id="sys-content" aria-labelledby={`sys-tab-${view}`}>
        {view === 'dashboard' && <DashboardTab />}
        {view === 'tools' && <ToolsTab />}
        {view === 'dev' && <div className="sys-dev"><PromptsPanel /></div>}
      </section>
    </div>
  )
}
