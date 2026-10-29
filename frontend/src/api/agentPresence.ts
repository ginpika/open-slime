import { useEffect, useState } from 'react'
import { maWorkflowOverview, type MaWorkflow } from './client'

/** 两个页面共用忙闲来源；失败与首次读取都不能推断为「空闲」。 */
export function useAgentOverview(enabled: boolean) {
  const [data, setData] = useState<MaWorkflow | null>(null)
  const [stale, setStale] = useState(false)
  useEffect(() => {
    if (!enabled) return
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    const load = async () => {
      try {
        const value = await maWorkflowOverview()
        if (alive) { setData(value); setStale(false) }
      } catch {
        if (alive) setStale(true)
      } finally {
        if (alive) timer = setTimeout(load, 2500)
      }
    }
    void load()
    return () => { alive = false; clearTimeout(timer) }
  }, [enabled])
  return { data, stale }
}

export function presenceLabel(working: boolean | undefined, stale = false) {
  if (stale) return '状态更新失败'
  if (working === undefined) return '状态加载中'
  return working ? '工作中' : '空闲中'
}
