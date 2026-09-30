/**
 * 统计页：整个应用运行期间的 LLM token 消耗。
 * 顶部为总量（总/输入/输出/调用次数）与时间范围切换；中部按来源与按模型分布；
 * 底部为按日趋势折线图（输入/输出两条曲线，X 轴由前端补零保证连续）。
 * 数据来自 GET /api/stats（一次 LLM 调用一行，供应商没回传 usage 时按文本估算）。
 */

import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { getStats, loginUrlOf, type StatsBucket, type StatsView } from '../api/client'
import StatsTrendChart from '../components/StatsTrendChart'
import Toast from '../components/Toast'

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

/** 时间范围切换：胶囊按钮组 */
function RangeTab({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full px-3.5 py-1 text-[12px] font-semibold transition ${
        active ? 'bg-accent text-accent-fg' : 'text-muted hover:bg-fg/5 hover:text-fg'
      }`}
    >
      {children}
    </button>
  )
}

/** 总量里的一个次级指标 */
function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-[92px]">
      <p className="text-[11px] text-faint">{label}</p>
      <p className="mt-1 font-mono text-[15px] font-semibold text-fg">{value}</p>
    </div>
  )
}

/**
 * 一个维度的分布列表：每行「名称 + 总 token + 调用次数」，背景条按占比铺开。
 * 无数据时给出空态文案。
 */
function BucketList({
  title,
  buckets,
  labelOf,
  empty,
}: {
  title: string
  buckets: StatsBucket[]
  labelOf: (key: string) => string
  empty: string
}) {
  const max = Math.max(1, ...buckets.map((b) => b.totalTokens))
  return (
    <div className="border border-line p-5">
      <h2 className="text-[13px] font-semibold text-fg">{title}</h2>
      {buckets.length === 0 && <p className="mt-3 text-[12.5px] text-faint">{empty}</p>}
      <ul className="mt-3 flex flex-col gap-2.5">
        {buckets.map((b) => (
          <li key={b.key}>
            <div className="flex items-baseline justify-between gap-3 text-[12.5px]">
              <span className="truncate text-muted" title={labelOf(b.key)}>
                {labelOf(b.key)}
              </span>
              <span className="shrink-0 font-mono text-fg">
                {fmtNum(b.totalTokens)}
                <span className="ml-2 text-[11px] font-normal text-faint">{b.calls} 次</span>
              </span>
            </div>
            {/* 占比条：以最大项为满格，直观对比各来源的相对消耗 */}
            <div className="mt-1.5 h-1 w-full bg-fg/5">
              <div
                className="h-full bg-hint"
                style={{ width: `${Math.max(2, (b.totalTokens / max) * 100)}%` }}
              />
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** 统计页：整个应用消耗的 token */
export default function Stats() {
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

  return (
    <section className="flex flex-col gap-5 pt-6">
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />

      {/* 总量卡片 + 时间范围切换 */}
      <div className="flex flex-wrap items-end justify-between gap-4 border border-line p-5">
        <div className="flex flex-wrap items-end gap-x-10 gap-y-4">
          <div>
            <p className="text-[11px] tracking-[0.2em] text-faint">TOTAL TOKENS</p>
            <p className="mt-1.5 font-mono text-[34px] leading-none font-semibold text-fg">
              {fmtNum(total?.totalTokens ?? 0)}
            </p>
          </div>
          <Metric label="输入" value={fmtNum(total?.promptTokens ?? 0)} />
          <Metric label="输出" value={fmtNum(total?.completionTokens ?? 0)} />
          <Metric label="调用次数" value={fmtNum(total?.calls ?? 0)} />
        </div>
        <div className="flex w-fit gap-1 rounded-full border border-line p-1">
          {RANGES.map((d) => (
            <RangeTab key={d} active={d === days} onClick={() => setDays(d)}>
              {d} 天
            </RangeTab>
          ))}
        </div>
      </div>

      {empty && (
        <p className="border border-dashed border-line p-6 text-[13px] leading-relaxed text-faint">
          还没有 token 记录 —— 发起一次对话、文档分析或向量化后，这里会显示各来源的消耗明细。
        </p>
      )}

      {/* 分来源 / 分模型分布 */}
      <div className="grid gap-5 lg:grid-cols-2">
        <BucketList
          title="按来源"
          buckets={data?.bySource ?? []}
          labelOf={sourceLabel}
          empty="暂无来源数据"
        />
        <BucketList
          title="按模型"
          buckets={data?.byModel ?? []}
          labelOf={modelLabel}
          empty="暂无模型数据"
        />
      </div>

      {/* 按日趋势 */}
      <div className="border border-line p-5">
        <div className="flex items-baseline justify-between gap-3">
          <h2 className="text-[13px] font-semibold text-fg">按日趋势</h2>
          <span className="text-[11px] text-faint">最近 {days} 天 · 输入 / 输出 token</span>
        </div>
        <div className="mt-3 h-[260px]">
          <StatsTrendChart
            labels={trend.labels}
            prompt={trend.prompt}
            completion={trend.completion}
          />
        </div>
      </div>
    </section>
  )
}
