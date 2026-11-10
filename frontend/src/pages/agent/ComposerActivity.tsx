import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { ChatToolActivity } from '../../api/client'
import type { LiveChat } from '../../components/chatLive'

type ActivityCopy = readonly [plain: string, playful: string]

const THINKING: ActivityCopy = ['正在思考', '脑内会议进行中']
const ANSWERING: ActivityCopy = ['正在回复', '在把脑内草稿写成回复']
const STOPPING: ActivityCopy = ['正在停止当前任务', '在收拾工作台']
const DEFAULT_TOOL: ActivityCopy = ['正在使用工具', '翻出了工具箱']

const TOOL_COPY = new Map<string, ActivityCopy>([
  ['run_command', ['正在执行命令', '在终端敲键盘']],
  ['list_terminals', ['正在查看工作空间', '在挑一张工作台']],
  ['search_web', ['正在搜索网页', '在网上寻宝']],
  ['read_web_snapshot', ['正在阅读网页快照', '翻开了网页存档']],
  ['search_documents_by_title', ['正在查找文档', '在书架上找资料']],
  ['search_vector_chunks', ['正在检索资料', '在资料堆里淘线索']],
  ['read_context_source', ['正在回读资料', '在翻刚才的笔记']],
  ['recall_memory', ['正在回想相关信息', '在记忆抽屉里翻找']],
  ['delegate_subagent', ['正在分派子任务', '在召集小伙伴帮忙']],
  ['read_subagent_run', ['正在查看子任务结果', '在收小伙伴的回报']],
  ['write_todos', ['正在更新待办', '在整理任务小本本']],
  ['roll_dice', ['正在掷骰子', '摇响了骰盅']],
])

function toolCopy({ name, target }: ChatToolActivity): ActivityCopy {
  if (name === 'read_document') {
    return target ? [`正在阅读文档 ${target}`, `翻开了文档 ${target}，在找线索`] : ['正在阅读文档', '在文档里找线索']
  }
  if (name === 'web_browser' || name === 'fetch_web') {
    return target ? [`正在浏览 ${target}`, `去 ${target} 串个门`] : ['正在浏览网页', '在网页里淘线索']
  }
  return TOOL_COPY.get(name) ?? DEFAULT_TOOL
}

function currentActivity(live: LiveChat | null): { key: string; copy: ActivityCopy } | null {
  if (!live?.running) return null
  if (live.stopping) return { key: `${live.sessionId}:stopping`, copy: STOPPING }

  const tool = live.activeTools[0]
  if (tool) return { key: `${live.sessionId}:tool:${tool.callId}`, copy: toolCopy(tool) }
  return live.content
    ? { key: `${live.sessionId}:answering`, copy: ANSWERING }
    : { key: `${live.sessionId}:thinking`, copy: THINKING }
}

function ActivityLabel({ agentName, copy }: { agentName: string; copy: ActivityCopy }) {
  // The key changes with the displayed activity, so token deltas never reroll the copy.
  const [playful] = useState(() => Math.random() < 0.33)
  return <span>
    {agentName} {copy[playful ? 1 : 0]}
    <span className="sl-chat__activity-dots" aria-hidden="true"><span>.</span><span>.</span><span>.</span></span>
  </span>
}

export default function ComposerActivity({ agentName, avatar, live }: {
  agentName: string
  avatar: ReactNode
  live: LiveChat | null
}) {
  const activity = currentActivity(live)
  // Keep the last label mounted while the region fades out.
  const [lastActivity, setLastActivity] = useState(activity)
  if (activity && activity.key !== lastActivity?.key) setLastActivity(activity)

  const region = useRef<HTMLDivElement>(null)
  const displayed = activity ?? lastActivity
  const exiting = !activity && displayed !== null
  useEffect(() => {
    if (!exiting) return
    let cancelled = false
    const animations = region.current?.getAnimations() ?? []
    void Promise.allSettled(animations.map(animation => animation.finished)).then(() => {
      if (!cancelled) setLastActivity(null)
    })
    return () => { cancelled = true }
  }, [exiting])

  return <div ref={region} className="sl-chat__activity" data-visible={activity ? '' : undefined} role="status" aria-atomic="true">
    {displayed && <div className="sl-chat__activity-clip" aria-hidden={exiting || undefined}>
      <div className="sl-chat__activity-row">
        {avatar}
        <ActivityLabel key={displayed.key} agentName={agentName} copy={displayed.copy} />
      </div>
    </div>}
  </div>
}
