import type { SearchResponse } from '../api/client'
import ResultCard from './ResultCard'

export default function WebSearchResults({ web }: { web: SearchResponse['web'] }) {
  if (!web?.enabled) return null
  return <section className="mt-8 w-full space-y-3" aria-label="网页搜索结果">
    <h2 className="text-xl font-bold">网页结果 <span className="text-sm font-normal text-muted">DeepSeek Search</span></h2>
    {web.error && <p role="status" className="text-sm leading-6 text-muted">网页检索失败：{web.error}。本地结果仍可使用。</p>}
    {web.warnings?.map((warning, i) => <p key={i} role="status" className="text-sm leading-6 text-muted">{warning}</p>)}
    {!web.error && !web.results.length && <p className="text-sm text-muted">没有匹配的网页结果。</p>}
    {web.results.map(r => <ResultCard web={r} key={r.url} />)}
  </section>
}
