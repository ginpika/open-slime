import { useId, useState } from 'react'
import { domainNames, identityNames, layers, ownerNames, scopeNames } from './model'
import { MemoryButton, MemoryField, MemorySelect } from './MemoryControls'

export type CatalogFilters = { search: string; layer: string; range: string; owner: string; domain: string; identity: string; state: string }
export default function MemoryFilters({ values, change, clear, count }: { values: CatalogFilters; change: (key: keyof CatalogFilters, value: string) => void; clear: () => void; count: number | null }) {
  const [expanded, setExpanded] = useState(false)
  const advancedId = useId()
  const advancedCount = [values.owner, values.domain, values.identity].filter(Boolean).length
  const choices = {
    layer: [{ value: '', label: '全部层级' }, ...layers.map(l => ({ value: l.id, label: `${l.id} ${l.name}` }))],
    range: [{ value: '', label: '全部范围' }, ...Object.entries(scopeNames).map(([value, label]) => ({ value, label }))],
    owner: [{ value: '', label: '全部归属' }, ...Object.entries(ownerNames).map(([value, label]) => ({ value, label }))],
    domain: [{ value: '', label: '全部内容域' }, ...Object.entries(domainNames).map(([value, label]) => ({ value, label }))],
    identity: [{ value: '', label: '全部身份' }, ...Object.entries(identityNames).map(([value, label]) => ({ value, label }))],
    state: [{ value: '', label: '全部状态' }, ...['有效', '已取代', '已过期', '已归档', '已撤销', '冲突', '不可注入'].map(value => ({ value, label: value }))],
  }
  const labels: Record<keyof CatalogFilters, string> = { search: '搜索', layer: '层级', range: '范围', owner: '归属', domain: '内容域', identity: '身份', state: '状态' }
  return <div className="mem-filter-bar">
    <div className="mem-filter-primary">
      <MemoryField label="查找记忆" className="mem-search"><input type="search" aria-label="查找记忆" placeholder="搜索标题、正文或 Agent ID…" value={values.search} onChange={e => change('search', e.target.value)} /></MemoryField>
      {(['layer', 'range', 'state'] as const).map(key => <MemorySelect key={key} label={labels[key]} value={values[key]} onChange={value => change(key, value)} options={choices[key]} />)}
      <MemoryButton variant="quiet" aria-expanded={expanded} aria-controls={advancedId} onClick={() => setExpanded(v => !v)}>{expanded ? '收起筛选' : '更多筛选'}{advancedCount > 0 && ` (${advancedCount})`}</MemoryButton>
    </div>
    {expanded && <div id={advancedId} className="mem-filter-advanced">{(['owner', 'domain', 'identity'] as const).map(key => <MemorySelect key={key} label={labels[key]} value={values[key]} onChange={value => change(key, value)} options={choices[key]} />)}</div>}
    <div className="mem-filter-summary"><span className="mem-muted">{count == null ? '等待目录与权限读取' : `匹配 ${count} 条`}</span><div className="mem-filter-chips">{(Object.entries(values) as [keyof CatalogFilters, string][]).filter(([, value]) => value).map(([key, value]) => <MemoryButton key={key} variant="quiet" title={`${labels[key]}：${value}`} aria-label={`移除${labels[key]}筛选`} onClick={() => change(key, '')}><span>{labels[key]}：{key === 'search' ? value : choices[key].find(o => o.value === value)?.label ?? value}</span><span aria-hidden="true">×</span></MemoryButton>)}</div><MemoryButton variant="quiet" onClick={clear}>清除全部</MemoryButton></div>
  </div>
}
