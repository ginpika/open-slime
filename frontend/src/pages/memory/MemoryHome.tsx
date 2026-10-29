import { useEffect, useState } from 'react'
import { AppIcon, type AppIconName } from '../../components/AppIcon'
import { pinMemoryItem, type MemoryItem } from '../../api/client'
import { memoryCatalog, type CatalogView, type MemoryCatalog, type CatalogEntry } from '../../api/memoryCatalog'
import { MemoryButton, MemoryField, MemorySelect } from './MemoryControls'
import { domainNames, errorText, occurrence, scopeKind, scopeNames, status, time } from './model'
/* oxlint-disable react/set-state-in-effect */

const views: { id: CatalogView; name: string; hint: string; icon: AppIconName }[] = [
  { id: 'remembered', name: '正在记住', hint: '当前可参与记忆召回', icon: 'memory' },
  { id: 'recent', name: '最近记住', hint: '过去 7 天新增', icon: 'clock' },
  { id: 'attention', name: '即将停用', hint: '7 天内到期或下次整理归档', icon: 'archive' },
  { id: 'history', name: '暂未使用', hint: '已归档、失效或待核实', icon: 'file' },
]
type Props = { agentNames: Map<string,string>; projectNames: Map<string,string>; tick: number; inspect: (item: MemoryItem) => void; changed: () => void; manage: () => void; review: () => void }
export default function MemoryHome({agentNames,projectNames,tick,inspect,changed,manage,review}: Props) {
  const [view,setView]=useState<CatalogView>('remembered')
  const [search,setSearch]=useState(''), [query,setQuery]=useState('')
  const [agentId,setAgentId]=useState(''), [projectId,setProjectId]=useState(''), [page,setPage]=useState(1)
  const [revision,setRevision]=useState(0), [busy,setBusy]=useState<number | null>(null)
  const [result,setResult]=useState<{key:string; data:MemoryCatalog} | null>(null)
  const [loading,setLoading]=useState(true), [error,setError]=useState(''), [actionError,setActionError]=useState('')
  const key=JSON.stringify([view,query,agentId,projectId,page])
  const data=result?.key===key ? result.data : null
  useEffect(() => { const timer=window.setTimeout(()=>{setQuery(search.trim());setPage(1)},250);return ()=>window.clearTimeout(timer) },[search])
  useEffect(() => {
    const timer=window.setInterval(()=>{if(document.visibilityState==='visible')setRevision(n=>n+1)},60000)
    return ()=>window.clearInterval(timer)
  },[])
  useEffect(() => {
    let live=true;const controller=new AbortController()
    setLoading(true);setError('')
    memoryCatalog({view,q:query,agentId,projectId,page},controller.signal).then(value=>{if(live)setResult({key,data:value})})
      .catch(e=>{if(live){setError(errorText(e));setResult(null)}}).finally(()=>{if(live)setLoading(false)})
    return ()=>{live=false;controller.abort()}
  },[key,view,query,agentId,projectId,page,tick,revision])
  const pending=loading || search.trim()!==query
  function choose(value: CatalogView) {setView(value);setPage(1)}
  async function pin(item:MemoryItem) {
    setBusy(item.id);setActionError('')
    try {await pinMemoryItem(item.id,!item.pinned);changed()}
    catch(e){setActionError(errorText(e))}finally{setBusy(null)}
  }
  const filters=Boolean(search || agentId || projectId)
  const entries=data?.entries ?? []
  const name=views.find(v=>v.id===view)!
  const reset=()=>{setSearch('');setQuery('');setAgentId('');setProjectId('');setPage(1)}
  const concern=(entry:CatalogEntry)=>entry.stopsAt ? `${entry.stopsAt<=Date.now()?'已于':'将于'} ${time(entry.stopsAt)} 停止自动使用` : '下次整理将归档'
  return <div className="mem-home">
    <header className="mem-home-intro"><div><p className="mem-home-kicker"><AppIcon name="memory" />持续积累的记忆</p><h2>看看 Agent 记住了什么</h2><p>查看记忆原文、记住的时间与来源，决定哪些值得保留。</p></div><MemoryButton onClick={manage}><AppIcon name="plus" />添加记忆</MemoryButton></header>
    <section className="mem-home-stats" aria-label="记忆状态筛选">{views.map(v=><button type="button" key={v.id} onClick={()=>choose(v.id)} aria-pressed={view===v.id} className={view===v.id?'is-selected':''}>
      <span><AppIcon name={v.icon} />{v.name}</span><strong>{data ? data.counts[v.id].toLocaleString() : '—'}</strong><small>{v.hint}</small>
    </button>)}</section>
    {data && (data.pendingProfiles>0 || data.extractingMaterials>0) && <div className="mem-home-pipeline"><div><strong>还有一些信息正在成为记忆</strong><p>全库：{data.pendingProfiles} 条画像待你确认，{data.extractingMaterials} 段材料等待提炼或正在处理。它们尚未计入当前记忆。</p></div>{data.pendingProfiles>0 && <MemoryButton onClick={review}>确认画像<AppIcon name="arrow-right" size={14} /></MemoryButton>}</div>}
    <div className="mem-home-filters">
      <MemoryField label="搜索记忆"><div className="mem-home-search"><AppIcon name="search" /><input type="search" placeholder="搜索记住的内容…" value={search} onChange={e=>setSearch(e.target.value)} /></div></MemoryField>
      <MemorySelect label="归属 Agent" value={agentId} searchable onChange={v=>{setAgentId(v);setPage(1)}} options={[{value:'',label:'全部 Agent'},...[...agentNames].map(([value,label])=>({value,label}))]} />
      <MemorySelect label="所属项目" value={projectId} searchable onChange={v=>{setProjectId(v);setPage(1)}} options={[{value:'',label:'全部项目'},...[...projectNames].map(([value,label])=>({value,label}))]} />
      {filters && <MemoryButton variant="quiet" onClick={reset}>清除筛选</MemoryButton>}
    </div>
    {actionError && <p role="alert" className="mem-error">{actionError}</p>}
    {error && <div role="alert" className="mem-error"><p>记忆读取失败：{error}</p><MemoryButton onClick={()=>setRevision(n=>n+1)}>重新读取</MemoryButton></div>}
    <div className="mem-home-layout">
      <section className="mem-home-catalog" aria-label={name.name} aria-busy={pending}>
        <div className="mem-home-list-heading"><div><h3>{name.name}</h3><p>{view==='remembered'?'按记住时间从新到旧排列；每轮会选取相关内容使用。':view==='recent'?'按首次记住时间排列，编辑旧记忆不会计为新增。':view==='attention'?'这里显示真实的到期和归档规则，停用后仍可查看历史。':'这些内容暂不参与普通召回，详情中可以查看原因与历史。'}</p></div><span>{data ? `${data.total.toLocaleString()} 条` : '读取中'}</span></div>
        {pending ? <div className="mem-home-empty" role="status"><AppIcon name="loading" /><p>正在读取记忆…</p></div> : error ? null : entries.length ? <div className="mem-home-list">{entries.map(entry=>{
          const item=entry.item, occurred=occurrence(item)
          return <article key={item.id} className={`mem-home-card ${view==='attention'?'needs-attention':''}`}>
            <div className="mem-home-card-meta"><span>{domainNames[item.domain ?? ''] || item.scopeLabel}</span>{item.identity==='FICTION' && <span>虚构内容</span>}{item.evidenceLevel==='inferred' && <span>待验证</span>}{view==='history' && <span>{status(item)}</span>}{item.pinned && <span><AppIcon name="permissions" size={14} />已固定</span>}</div>
            <button type="button" className="mem-home-content" onClick={()=>inspect(item)}><h4>{item.title || '一条记忆'}</h4><p>{item.body.length>360 ? `${item.body.slice(0,360)}…` : item.body}</p>{item.body.length>360 && <span className="mem-home-expand">查看完整内容</span>}</button>
            <div className="mem-home-time"><span>记住于 {time(item.createdAt)}</span>{occurred && <span>{occurred}</span>}</div>
            {view==='attention' && <div className="mem-home-warning"><strong><AppIcon name="clock" size={14} />{concern(entry)}</strong><p>{entry.reason}</p>{entry.archiveNext && <p>固定可避免数量归档；已有有效期仍会生效。</p>}</div>}
            <footer><div className="mem-home-origin"><span>{item.agentId ? agentNames.get(item.agentId) || item.agentId : scopeNames[scopeKind(item,[])]}</span>{item.projectId && <span>{projectNames.get(item.projectId) || item.projectId}</span>}</div><div className="mem-home-card-actions"><MemoryButton variant="quiet" disabled={busy!==null} onClick={()=>void pin(item)}>{busy===item.id?'保存中…':item.pinned?'取消固定':'固定'}</MemoryButton><MemoryButton variant="quiet" onClick={()=>inspect(item)}>详情与来源<AppIcon name="arrow-right" size={14} /></MemoryButton></div></footer>
          </article>
        })}</div> : <div className="mem-home-empty"><AppIcon name={name.icon} size={28} /><h4>{view==='attention'?'暂时没有即将停用的记忆':view==='recent'?'过去 7 天没有新增记忆':view==='history'?'暂时没有停用记录':filters?'没有匹配的记忆':'Agent 还没有长期记忆'}</h4><p>{filters?'可以换一个关键词，或清除筛选。':view==='remembered'?'与 Agent 交流并完成提炼后，记忆会出现在这里。你也可以手动添加。':view==='attention'?'没有设定到期或达到归档条件的记忆，不会显示倒计时。':'已保存的其他记忆可以在“正在记住”查看。'}</p>{filters?<MemoryButton onClick={reset}>清除筛选</MemoryButton>:view==='remembered'?<MemoryButton onClick={manage}>添加一条记忆</MemoryButton>:<MemoryButton onClick={()=>choose('remembered')}>查看正在记住的内容</MemoryButton>}</div>}
        {data && data.pages>1 && !pending && <nav className="mem-pagination" aria-label="记忆分页"><span>第 {data.page} / {data.pages} 页</span><MemoryButton disabled={data.page<=1} onClick={()=>setPage(data.page-1)}>上一页</MemoryButton><MemoryButton disabled={data.page>=data.pages} onClick={()=>setPage(data.page+1)}>下一页</MemoryButton></nav>}
      </section>
      <aside className="mem-home-aside"><section><div className="mem-home-aside-title"><AppIcon name="archive" /><h3>哪些记忆需要留意</h3></div><p>归档和到期会停止自动使用，原记录仍然保留。明确遗忘会额外阻止自动重建。</p>{!pending && data?.attention.length ? <div className="mem-home-due-list">{data.attention.map(e=><button type="button" key={e.item.id} onClick={()=>inspect(e.item)}><strong>{e.item.title || e.item.body.slice(0,42)}</strong><span>{concern(e)}</span><p>{e.reason}</p></button>)}<MemoryButton onClick={()=>choose('attention')}>查看全部 {data.counts.attention} 条<AppIcon name="arrow-right" size={14} /></MemoryButton></div>:<p className="mem-home-due-none">{pending?'正在检查保留状态…':error?'保留状态暂不可用。':'当前范围没有即将停用的记忆。'}</p>}</section><section><h3>每条记忆，都能找到来处</h3><p>打开“详情与来源”，查看支持它的原文、发生时间和变更历史，也可以纠正或遗忘。</p><p>归属 Agent 与实际可读范围不同。跨会话权限可在“Agent 视角”检查。</p><small>{data ? `状态更新于 ${time(data.checkedAt)} · 页面可见时每分钟刷新` : '读取成功后显示更新时间'}</small></section></aside>
    </div>
  </div>
}
