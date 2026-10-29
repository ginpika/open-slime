import type { CollaborationPlan } from '../../api/collaboration'
import type { MaPeer } from '../../api/client'
import { AppIcon } from '../../components/AppIcon'

export default function PlanStatus({ plan, peers }: { plan: CollaborationPlan; peers: MaPeer[] }) {
  const name = (id: string | null) => peers.find(p => p.id === id)?.name || id || '未分配'
  const assignments = plan.assignments || []
  const phase = plan.continuationStatus === 'paused' && plan.phase !== 'completed' ? '已暂停' : { planning: '主控规划', executing: '执行中', checking: '独立检查中', completed: '已确认交付' }[plan.phase]
  return <details className="collab-plan">
    <summary><span className="collab-plan-owner"><AppIcon name="agent" />主控 · {name(plan.coordinatorId)}</span><span className="collab-plan-phase">{phase}{assignments.length > 1 && ` · ${assignments.length} 项在途`}</span><span className="collab-plan-caption">本次范围与验收</span><AppIcon name="chevron-down" /></summary>
    <div className="collab-plan-body">{plan.scope ? <>
      <p><strong>本次交付物</strong>{plan.scope.deliverables}</p>
      <p><strong>本次不包含</strong>{plan.scope.exclusions}</p>
      <p><strong>现状与证据</strong>{plan.scope.baseline}</p>
      {plan.scope.roadmap && <p><strong>后续方向</strong>{plan.scope.roadmap}</p>}
    </> : <p>主控需要先核对当前项目，明确本次交付物、范围外事项与现状依据。</p>}
      <p><strong>验收标准</strong>{plan.criteria || '主控将在首次推进时明确目标、边界和验证方法。'}</p>
      {plan.continuationStatus === 'paused' && plan.pauseReason && <p><strong>暂停原因</strong>{plan.pauseReason}</p>}
      {plan.decision && <p><strong>最近决定</strong>{plan.decision}</p>}
      {assignments.length > 0 ? assignments.map(a => <p key={a.todoId}><strong>{a.title}</strong>{a.phase === 'checking' ? '独立检查中' : '执行中'} · 执行：{name(a.executorId)} · 检查：{name(a.reviewerId)}</p>)
        : ['executing', 'checking'].includes(plan.phase) && <p>执行：{name(plan.executorId)} · 检查：{name(plan.reviewerId)}</p>}
      {plan.evidence && <p><strong>整体验证</strong>{plan.evidence}</p>}
    </div>
  </details>
}
