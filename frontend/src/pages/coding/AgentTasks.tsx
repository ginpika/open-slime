import { useCallback, useEffect, useRef, useState } from 'react'
import { codingError, codingProjects, codingRuns, type CodingProject, type CodingRun } from '../../api/coding'
import { Button } from '../../components/ui'
import CodingRoleSettings from './CodingRoleSettings'

const statuses: Record<string, string> = { QUEUED: '等待开始', PREPARING: '准备项目', RUNNING: '执行中', VERIFYING: '验证中', REVIEWABLE: '等待审阅', PAUSED: '已暂停', INTERRUPTED: '已中断', FAILED: '失败', CANCELLED: '已停止', CANCELLING: '正在停止', ACCEPTING: '应用中', COMPLETED: '已应用' }
statuses.WAITING_AGENTS = '等待子 Agent'

/** Reads the same owner-scoped tasks as the coding studio, without starting another runtime. */
export default function AgentTasks() {
  const [runs, setRuns] = useState<CodingRun[]>([])
  const [projects, setProjects] = useState<CodingProject[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const pending = useRef(false)
  const load = useCallback(async (showLoading = true) => {
    if (pending.current) return
    pending.current = true
    if (showLoading) setLoading(true)
    setError('')
    const [tasks, items] = await Promise.allSettled([codingRuns(), codingProjects()])
    if (tasks.status === 'fulfilled') setRuns(tasks.value)
    if (items.status === 'fulfilled') setProjects(items.value)
    setError([tasks, items].flatMap(item => item.status === 'rejected' ? [codingError(item.reason)] : []).join('；'))
    setLoading(false)
    pending.current = false
  }, [])
  useEffect(() => { void load() }, [load])
  const active = runs.some(run => ['QUEUED', 'PREPARING', 'RUNNING', 'WAITING_AGENTS', 'VERIFYING', 'CANCELLING', 'ACCEPTING'].includes(run.status))
  useEffect(() => {
    if (!active || error) return
    const timer = window.setInterval(() => void load(false), 3000)
    return () => window.clearInterval(timer)
  }, [active, error, load])
  return <section aria-label="默认 Agent 的编程项目与任务" className="flex flex-col gap-4">
    <CodingRoleSettings />
    <p className="text-sm leading-relaxed text-muted">编程 IDE 使用默认 Agent 的身份和模型，并仅在项目任务中加载编程 Skill。可在「提示词 & 上下文工程 → Skills · 状态化提示词」中编辑规程。新任务保存配置快照，已有任务沿用检查点。每个项目沿用所选工作空间，修改审阅后再应用。</p>
    <div className="flex flex-wrap items-center gap-2"><a href="#/coding" className="ui-btn ui-btn--primary ui-btn--sm">进入编程工作台 ↗</a><Button size="sm" disabled={loading} onClick={() => void load()}>刷新编码任务</Button></div>
    {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
    <h3 className="ui-heading text-sm">我的编码任务</h3>
    {loading ? <p role="status" className="text-sm text-muted">正在读取编码任务…</p> : !runs.length ? <p className="text-sm text-muted">还没有编码任务。在编程工作台新建或打开项目后即可开始，也可以在对话中交给默认 Agent 处理。</p> : <div className="flex flex-col gap-2">{runs.map(run => {
      const project = projects.find(item => item.id === run.repositoryId)
      return <a key={run.id} href={`#/coding?project=${encodeURIComponent(run.repositoryId)}&run=${encodeURIComponent(run.id)}`} className="flex min-w-0 items-start gap-3 rounded-lg border border-line px-3 py-3 hover:bg-fg/5">
        <span className="min-w-0 flex-1"><strong className="block truncate text-sm">{run.prompt}</strong><span className="mt-1 block text-xs text-muted">{project?.name || '仓库任务'} · {run.mode} · #{run.taskId}</span></span><span className="shrink-0 text-xs text-muted">{statuses[run.status] || run.status} ↗</span>
      </a>
    })}</div>}
  </section>
}
