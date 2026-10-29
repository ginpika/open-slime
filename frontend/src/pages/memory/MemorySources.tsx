import { useEffect, useState } from 'react'
import { memoryEvidencePage, type MemoryEvidencePage } from '../../api/memoryCatalog'
import { MemoryButton } from './MemoryControls'
import { errorText, time } from './model'

export default function MemorySources({id}: {id:number}) {
  const [offset,setOffset]=useState(0),[revision,setRevision]=useState(0)
  const [data,setData]=useState<MemoryEvidencePage | null>(null),[error,setError]=useState(''),[loading,setLoading]=useState(true)
  useEffect(()=>{
    let live=true
    memoryEvidencePage(id,offset).then(v=>{if(live){setData(v);setError('')}}).catch(e=>{if(live)setError(errorText(e))}).finally(()=>{if(live)setLoading(false)})
    return ()=>{live=false}
  },[id,offset,revision])
  const page=(value:number)=>{setLoading(true);setData(null);setOffset(value)}
  return <section><h4>支持这条记忆的原文</h4>{loading?<p role="status">正在读取来源…</p>:error?<div className="mem-error" role="alert">{error}<MemoryButton onClick={()=>{setLoading(true);setRevision(n=>n+1)}}>重新读取</MemoryButton></div>:data?.evidence.length?<>
    {data.evidence.map(e=><details className="mem-evidence" key={e.id}><summary>{e.sourceRef.startsWith('chat_message:')?'原始对话':e.sourceRef.startsWith('project_document:')?'项目文档':e.sourceRef.startsWith('chunk:')?'知识文档':'来源记录'} · 采集于 {time(e.observedAt)}{e.status!=='active'?' · 已撤回':''}</summary>{e.readable && e.payload ? <blockquote>{e.payload}</blockquote>:<p className="mem-muted">{e.readable?'来源仅保留了引用，未留存原文。':'当前范围不能读取来源正文。'}</p>}<small>{e.sourceRef}</small></details>)}
    <div className="mem-pagination"><span>{offset+1}–{offset+data.evidence.length} / {data.totalEvidence} 个来源</span><MemoryButton disabled={offset===0} onClick={()=>page(Math.max(0,offset-4))}>上一页</MemoryButton><MemoryButton disabled={offset+data.evidence.length>=data.totalEvidence} onClick={()=>page(offset+4)}>下一页</MemoryButton></div>
  </>:<p className="mem-muted">尚未关联原始经历；可以在下方变更历史中核对人工记录。</p>}</section>
}
