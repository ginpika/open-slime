import { useState } from 'react'
import type { Project } from '../../api/client'
import { AppIcon } from '../../components/AppIcon'
import { Select } from '../../components/Select'
import { Button } from '../../components/ui'
import { duration } from './format'
import { projectKind, projectStatus, PROJECT_STATUSES, type ProjectOverview } from './projectModel'

type Props = {
  projects: ProjectOverview[]; selected: string; disabled: boolean
  onSelect: (id: string) => void; onTodos: (id: string) => void; onEdit: (project: Project) => void
}

export default function ProjectWork({ projects, selected, disabled, onSelect, onTodos, onEdit }: Props) {
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('')
  const [kind, setKind] = useState('')
  const catalog = projects.filter(project => project.id !== 'unassigned')
  const current = catalog.find(project => project.id === selected)
  const kinds = [...new Set(catalog.map(project => project.info?.kind).filter((value): value is string => !!value))]
  const visible = catalog.filter(project => (!status || project.status === status) && (!kind || project.info?.kind === kind)
    && `${project.name} ${project.info?.note ?? ''}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
  const unassigned = projects.find(project => project.id === 'unassigned')

  if (current) return <section className="todo-project-detail" aria-label="项目详情">
    <Button variant="quiet" disabled={disabled} onClick={() => onSelect('')}><AppIcon name="chevron-left" />全部项目</Button>
    <header className="todo-project-detail-heading"><div><div className="todo-project-badges"><span className="todo-tag">{projectStatus(current.status)}</span>{current.info && <span className="todo-tag">{projectKind(current.info.kind)}</span>}</div><h2>{current.name}</h2></div><div className="todo-actions">{current.info && <Button disabled={disabled} onClick={() => onEdit(current.info!)}>编辑项目</Button>}<Button variant="primary" disabled={disabled} onClick={() => onTodos(current.id)}>查看待办 · {current.openCount}<AppIcon name="arrow-right" /></Button></div></header>
    <section className="todo-project-description"><h3>项目目标与说明</h3><p className="todo-prose">{current.info?.note || (current.info ? '还没有项目说明，可以在编辑项目时记下想达成的目标。' : '原项目已删除，关联事项与投入记录仍然保留。')}</p></section>
    <section className="todo-project-results"><h3>投入与成果</h3><dl className="todo-project-statistics"><div><dt>累计投入</dt><dd>{duration(current.spentMillis)}</dd></div><div><dt>已完成事项</dt><dd>{current.completedCount}</dd></div><div><dt>正在推进</dt><dd>{current.doingCount}</dd></div><div><dt>已放下事项</dt><dd>{current.droppedCount}</dd></div></dl><p className="todo-secondary">根据全部历史事项与已记录的投入汇总。事项完成数量不代表项目完成进度。</p></section>
    {current.info && <dl className="todo-project-dates"><div><dt>创建于</dt><dd>{new Date(current.info.createdAt).toLocaleDateString('zh-CN')}</dd></div><div><dt>最近更新</dt><dd>{new Date(current.info.updatedAt).toLocaleDateString('zh-CN')}</dd></div></dl>}
  </section>

  return <section className="todo-project-work" aria-label="项目总览">
    <header className="todo-section-heading"><div><h2>我的项目<span>{catalog.length}</span></h2><p className="todo-project-caption">{catalog.filter(project => project.status === 'active').length} 个进行中 · {catalog.filter(project => project.status === 'paused').length} 个已暂停 · {catalog.filter(project => project.status === 'archived').length} 个已归档</p></div></header>
    <div className="todo-project-toolbar"><label className="todo-project-search"><span className="sr-only">搜索项目</span><AppIcon name="search" /><input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="搜索项目名称或说明" /></label><Select label="筛选项目状态" size="compact" value={status} onChange={setStatus} options={[{ value: '', label: '全部状态' }, ...PROJECT_STATUSES]} /><Select label="筛选项目分类" size="compact" value={kind} onChange={setKind} options={[{ value: '', label: '全部分类' }, ...kinds.map(value => ({ value, label: projectKind(value) }))]} />{(search || status || kind) && <Button variant="quiet" onClick={() => { setSearch(''); setStatus(''); setKind('') }}>重置筛选</Button>}</div>
    <div className="todo-project-grid">{visible.map(project => <button key={project.id} type="button" className="todo-project-card" disabled={disabled} onClick={() => onSelect(project.id)}>
      <div className="todo-project-badges"><span className="todo-tag">{projectStatus(project.status)}</span>{project.info && <span className="todo-tag">{projectKind(project.info.kind)}</span>}</div><h3>{project.name}</h3><p className="todo-project-card-note">{project.info?.note || (project.info ? '还没有填写项目说明。' : '原项目已删除，历史记录保留。')}</p><div className="todo-project-metrics"><span>已完成 {project.completedCount} 件</span><span>累计投入 {duration(project.spentMillis)}</span></div><span className="todo-project-card-link">查看项目<AppIcon name="arrow-right" /></span>
    </button>)}</div>
    {!visible.length && <p className="todo-empty">{catalog.length ? '没有符合条件的项目，试试其他筛选。' : '还没有项目。新建一个项目，记下你想做成的事。'}</p>}
    {unassigned && unassigned.openCount > 0 && <div className="todo-project-unassigned"><p>还有 {unassigned.openCount} 件待办未关联项目。</p><Button variant="quiet" disabled={disabled} onClick={() => onTodos('unassigned')}>查看未关联待办<AppIcon name="arrow-right" /></Button></div>}
  </section>
}
