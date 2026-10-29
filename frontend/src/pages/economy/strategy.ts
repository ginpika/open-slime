export const STAGES = ['机会很低', '机会偏低', '中性观察', '机会较高', '机会很高'] as const
export const TIMEFRAMES = ['weekly','daily','intraday'] as const
export const TIMEFRAME_LABEL = {weekly:'周线',daily:'日线',intraday:'分时'} as const
export const ENTRY_LABEL = {ENTER:'是，符合进场条件',WAIT:'等待确认（历史报告）',AVOID:'否，当前不进场'} as const
export const OVERNIGHT_LABEL = {BUY_CLOSE:'是，尾盘买入',WAIT:'暂不尾盘买入（历史报告）',AVOID:'否，当前不买入'} as const
export const localTradeDate = () => {
  const now=new Date()
  return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`
}

/** Long entry at today's close, exit next session; fees in basis points on each leg. */
export function overnightReturn(entry: number, exit: number, capital: number, feeBps: number, slippageBps: number) {
  if (![entry, exit, capital, feeBps, slippageBps].every(Number.isFinite) || entry <= 0 || exit <= 0 || capital <= 0 || feeBps < 0 || slippageBps < 0 || feeBps + slippageBps >= 10000) return null
  const cost = (feeBps + slippageBps) / 10000
  const gross = exit / entry - 1
  const net = exit * (1 - cost) / (entry * (1 + cost)) - 1
  return { gross, net, profit: capital * net, breakEven: entry * (1 + cost) / (1 - cost) }
}
