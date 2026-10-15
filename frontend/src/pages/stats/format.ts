import type { StatsBucket, StatsMoney, StatsView } from '../../api/client'

export function statsError(error: unknown): string {
  if (error instanceof Error && 'status' in error && typeof error.status === 'number' && 'body' in error && typeof error.body === 'string') {
    try { const body = JSON.parse(error.body); if (typeof body.message === 'string') return body.message } catch { /* use status */ }
    if (error.status === 400) return '请求参数无效，请检查日期和价格配置'
    if (error.status >= 500) return '服务暂时无法读取统计，请稍后重试'
  }
  return error instanceof Error ? error.message : String(error)
}

export const SOURCE_LABELS: Record<string, string> = {
  chat: '对话', analyze: '文档分析', commit: '分析入库', chunker: '文档分块',
  'doc-summary': '全文概述', 'chunk-questions': '假设性问题', 'chunk-summary': '段落摘要',
  heartbeat: '心跳', embedding: '向量化', rerank: '精排', 'model-test': '模型测试', coding: '编程任务',
}
export const sourceLabel = (source: string) => SOURCE_LABELS[source] ?? source
export const fmtNum = (n: number) => n.toLocaleString('zh-CN')
export function amount(n: number): string {
  if (n > 0 && n < 0.000001) return n.toExponential(2)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: n < 0.01 ? 8 : 4 })
}
export const money = (costs: StatsMoney[]) => costs.length
  ? costs.map(c => c.currency + ' ' + amount(c.amount)).join(' · ') : '未计价'
export const qualityLabel = (quality: string) => ({ reported: '实测', estimated: '估算', partial: '不完整 / 未知' }[quality] ?? '未知')
export const statusLabel = (status: string) => ({ success: '成功', interrupted: '中断 / 取消', failed: '失败' }[status] ?? '未知')
export function dateTime(time: number, timeZone: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(time)
}
export function validateRange(from: string, to: string, today?: string): string | null {
  if (!from || !to) return '请同时填写开始日期和结束日期'
  const start = Date.parse(from + 'T00:00:00Z'), end = Date.parse(to + 'T00:00:00Z')
  if (!Number.isFinite(start) || !Number.isFinite(end) || new Date(start).toISOString().slice(0, 10) !== from
    || new Date(end).toISOString().slice(0, 10) !== to) return '请填写有效日期'
  if (from < '1970-01-01' || to < from) return '结束日期不能早于开始日期，开始日期不能早于 1970 年'
  if (today && to > today) return '结束日期不能晚于今天'
  return (end - start) / 86400000 >= 366 ? '一次最多查询 366 天' : null
}
/** 保护来源、模型等外部文本，避免 Excel 将 CSV 单元格解释为公式。 */
export function csvCell(value: string | number): string {
  let text = String(value)
  if (typeof value === 'string' && /^[\s\uFEFF]*[=+\-@]/.test(text)) text = "'" + text
  return '"' + text.replaceAll('"', '""') + '"'
}
export function statsCsv(data: StatsView, source = '全部来源', model = '全部模型'): string {
  const currencies = [...new Set([...data.daily, ...data.bySource, ...data.byModel].flatMap(b => b.costs.map(c => c.currency)))].sort()
  const rows: (string | number)[][] = [
    ['用量 & 统计', data.from, data.to, data.timeZone, source, model],
    ['读取时间', dateTime(data.generatedAt, data.timeZone)],
    ['说明', '金额为按生效价格计算的估算费用；未计价不代表免费；币种独立；不含未知消耗、税费及非 Token 计费'],
    ['维度', '名称', '供应商端点', '输入 Token', '输出 Token', '总 Token', '调用次数', '未计价调用', '计价状态', ...currencies.map(c => '估算费用（' + c + '）')],
  ]
  function add(kind: string, buckets: StatsBucket[]) {
    for (const b of buckets) {
      const name = kind === '来源' ? sourceLabel(b.key) : kind === '模型' ? b.model || '未记录' : b.key
      rows.push([kind, name, b.provider, b.promptTokens, b.completionTokens, b.totalTokens,
        b.calls, b.unpricedCalls, b.calls === 0 ? '无调用' : b.unpricedCalls ? '含未计价调用' : '已计价',
        ...currencies.map(currency => b.costs.find(c => c.currency === currency)?.amount ?? '')])
    }
  }
  add('每日', data.daily); add('来源', data.bySource); add('模型', data.byModel)
  rows.push(['数据质量', '实测调用', data.total.reportedCalls, '估算调用', data.total.estimatedCalls,
    '不完整/未知调用', data.total.partialCalls, '非成功调用', data.total.unsuccessfulCalls])
  return '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n')
}
export function downloadStats(data: StatsView, source: string, model: string) {
  const url = URL.createObjectURL(new Blob([statsCsv(data, source, model)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = 'usage-' + data.from + '-' + data.to + '.csv'
  document.body.appendChild(link); link.click(); link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

