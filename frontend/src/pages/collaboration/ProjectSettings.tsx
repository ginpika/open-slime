import type { MaPeer, MaSession } from '../../api/client'
import { AppIcon } from '../../components/AppIcon'
import FloatWindow from '../../components/FloatWindow'
import PeerAvatar from '../../components/PeerAvatar'
import { Select } from '../../components/formKit'
import TaskActions from './TaskActions'

export default function ProjectSettings({ session, members, modelId, models, busy, archived = false, issue = false, hasIssues = false, onModel, onOrganize, onLifecycle, onClose }: {
  session?: MaSession; members: MaPeer[]; modelId: string; models: { value: string; label: string }[]; busy: boolean; issue?: boolean; hasIssues?: boolean
  archived?: boolean; onModel: (id: string) => void; onOrganize: () => void; onLifecycle: (deleted: boolean) => Promise<void>; onClose: () => void
}) {
  return <FloatWindow title="会话与成员设置" onClose={onClose} width={560}><div className="collab-settings">
    <section><h3>当前会话</h3><p>{session?.title || '尚未开始'}</p><p className="collab-description">{session?.projectId ? '已归属项目，可在同一项目内继续新的协作。' : '这条历史记录尚未归属项目，可整理到现有项目或新项目。'}</p>{session && !issue && <button type="button" className="ui-btn ui-btn--outline" disabled={busy || hasIssues || archived} onClick={onOrganize}><AppIcon name="folder" />{session.projectId ? '调整项目归属' : '整理到项目'}</button>}{busy && <p className="collab-secondary">成员正在推进时，先保留当前项目归属。</p>}</section>
    {hasIssues && <p className="collab-description">本会话已包含议题，保留现有项目归属和完整记录。</p>}<label className="collab-setting-field"><span>兜底模型</span><Select disabled={archived} label="协作兜底模型" value={modelId} onChange={onModel} options={models} /><span className="collab-secondary">成员未指定模型时使用，下一次发言生效。</span></label>
    <section><h3>参与成员 · {members.length}</h3>{members.map(p => <a key={p.id} className="collab-settings-member" href={`#/multi-agent/${encodeURIComponent(p.id)}`}><PeerAvatar name={p.name} avatar={p.avatar} size="h-8 w-8" textClass="text-sm" /><span>{p.name}<span className="collab-secondary">{p.role || '尚未设置角色'}</span></span><AppIcon name="external" /></a>)}</section>
    <p className="collab-description">成员的角色、模型和权限在智能体页维护。此处只展示本次会话成员。</p>
    {session && !issue && <section className="collab-settings-danger"><p className="collab-secondary">归档保留记录，恢复后可继续。删除会话会一并删除其中的工作条目和议题，项目资料、项目文件与独立编码副本会保留。</p><TaskActions id={session.id} title={session.title || '未命名会话'} archived={archived} busy={busy} onChanged={onLifecycle} /></section>}
  </div></FloatWindow>
}
