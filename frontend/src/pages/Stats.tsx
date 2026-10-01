/**
 * 统计页：整个应用运行期间的 LLM token 消耗。
 *
 * 本页只持有「数据与时序」：取数、范围切换、补零。
 * 呈现全部交给 PageKit（src/kit）——同一个页面在 flat / Endfield / BlueArchive
 * 下可以是完全不同的密度与章法，而这里的逻辑一行都不用改。
 *
 * 数据来自 GET /api/stats（一次 LLM 调用一行，供应商没回传 usage 时按文本估算）。
 */

import { useEffect, useMemo, useState } from 'react'
import { getStats, loginUrlOf, type StatsBucket, type StatsView } from '../api/client'
import StatsTrendChart from '../components/StatsTrendChart'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import type { PageKit } from '../kit/types'

/** 趋势区间候选（天） */
const RANGES = [7, 30, 90]

/** 来源标识 → 中文标签（后端 TokenUsageService 的 SOURCE_* 常量） */
const SOURCE_LABELS: Record<string, string> = {
  chat: '对话',
  analyze: '文档分析',
  commit: '分析入库',
  chunker: '文档分块',
  'doc-summary': '全文概述',
  'chunk-questions': '假设性问题',
  'chunk-summary': '段落摘要',
  subconscious: '潜意识',
  embedding: '向量化',
  rerank: '精排',
  'model-test': '模型测试',
}

const sourceLabel = (key: string) => SOURCE_LABELS[key] ?? key
const modelLabel = (key: string) => (key ? key : '未记录')
const fmtNum = (n: number) => n.toLocaleString('zh-CN')

/** 补零：把稀疏的每日汇总按最近 days 天（含今天）对齐成连续序列 */
function fillDays(buckets: StatsBucket[], days: number) {
  const byKey = new Map(buckets.map((b) => [b.key, b]))
  const labels: string[] = []
  const prompt: number[] = []
  const completion: number[] = []
  const today = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
      d.getDate(),
    ).padStart(2, '0')}`
    const b = byKey.get(key)
    labels.push(key.slice(5))
    prompt.push(b?.promptTokens ?? 0)
    completion.push(b?.completionTokens ?? 0)
  }
  return { labels, prompt, completion }
}

/** 一个维度的分布列表（呈现交给 kit.Bar） */
function BucketBody({
  kit,
  buckets,
  labelOf,
  empty,
}: {
  kit: PageKit
  buckets: StatsBucket[]
  labelOf: (key: string) => string
  empty: string
}) {
  if (buckets.length === 0) return <p className="text-sm text-faint">{empty}</p>
  const max = Math.max(1, ...buckets.map((b) => b.totalTokens))
  return (
    <div className="flex flex-col gap-3">
      {buckets.map((b, i) => (
        <kit.Bar
          key={b.key}
          index={i}
          name={labelOf(b.key)}
          value={fmtNum(b.totalTokens)}
          sub={`${b.calls} 次`}
          ratio={b.totalTokens / max}
        />
      ))}
    </div>
  )
}

/** 统计页：整个应用消耗的 token */
export default function Stats() {
  const kit = usePageKit()
  const [days, setDays] = useState(30)
  const [data, setData] = useState<StatsView | null>(null)
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setLoading(true)
    getStats(days)
      .then((d) => {
        if (alive) setData(d)
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setToast(`加载失败：${e instanceof Error ? e.message : String(e)}`)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [days])

  const trend = useMemo(() => fillDays(data?.daily ?? [], days), [data, days])
  const total = data?.total
  const empty = !loading && (total?.calls ?? 0) === 0
  const sum = Math.max(1, (total?.promptTokens ?? 0) + (total?.completionTokens ?? 0))
  const pctOf = (n?: number) => Math.round(((n ?? 0) / sum) * 100)
  const sourceCount = data?.bySource?.length ?? 0
  const modelCount = data?.byModel?.length ?? 0

  return (
    <kit.Page
      title="Token 消耗"
      en="TOKEN USAGE"
      meta={
        <kit.Range
          options={RANGES.map((d) => ({ value: d, label: `${d} 天` }))}
          value={days}
          onChange={setDays}
        />
      }
    >
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />

      {empty && (
        <kit.Note>
          还没有 token 记录 —— 发起一次对话、文档分析或向量化后，这里会显示各来源的消耗明细。
        </kit.Note>
      )}

      {/* 关键指标：排布由 kit.statRow 决定（内联条 / 四栏密排 / 糖果卡换行） */}
      <div className={kit.statRow}>
        <kit.Stat
          hero
          label="总 TOKEN"
          value={fmtNum(total?.totalTokens ?? 0)}
          hint={`最近 ${days} 天`}
        />
        <kit.Stat
          label="输入"
          value={fmtNum(total?.promptTokens ?? 0)}
          progress={pctOf(total?.promptTokens)}
          tone="muted"
          hint={`占 ${pctOf(total?.promptTokens)}%`}
        />
        <kit.Stat
          label="输出"
          value={fmtNum(total?.completionTokens ?? 0)}
          progress={pctOf(total?.completionTokens)}
          tone="accent"
          hint={`占 ${pctOf(total?.completionTokens)}%`}
        />
        <kit.Stat label="调用次数" value={fmtNum(total?.calls ?? 0)} hint="LLM 请求总数" />
      </div>

      {/* 分布与趋势 */}
      <div className={kit.panelGrid}>
        <kit.Panel title="按来源" meta={`${sourceCount} 项`}>
          <BucketBody kit={kit} buckets={data?.bySource ?? []} labelOf={sourceLabel} empty="暂无来源数据" />
        </kit.Panel>

        <kit.Panel title="按模型" meta={`${modelCount} 项`}>
          <BucketBody kit={kit} buckets={data?.byModel ?? []} labelOf={modelLabel} empty="暂无模型数据" />
        </kit.Panel>

        <kit.Panel
          title="按日趋势"
          meta={`输入 / 输出 · 最近 ${days} 天`}
          className={kit.panelWide}
        >
          <div className="h-[260px]">
            <StatsTrendChart
              labels={trend.labels}
              prompt={trend.prompt}
              completion={trend.completion}
            />
          </div>
        </kit.Panel>
      </div>
    </kit.Page>
  )
}
