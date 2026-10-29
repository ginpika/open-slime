import { useEffect, useState } from 'react'
import { maWorkflow, type MaWorkflow } from '../../api/client'

/** 一个项目一份快照，所有视图共享；过期响应不能覆盖切换后的项目。 */
export function useProjectWorkflow(sessionId: string | null, revision: number) {
  const [snapshot, setSnapshot] = useState<{ id: string; data: MaWorkflow; at: number } | null>(null)
  const [failure, setFailure] = useState<{ id: string; text: string } | null>(null)
  useEffect(() => {
    if (!sessionId) return
    let alive = true
    let timer: ReturnType<typeof setTimeout>
    let fingerprint = ''
    const load = async () => {
      try {
        const data = await maWorkflow(sessionId)
        if (!alive) return
        if (data.sessionId !== sessionId) throw new Error('项目状态与当前选择不一致，请重试。')
        const next = JSON.stringify(data)
        if (next !== fingerprint) {
          fingerprint = next
          setSnapshot({ id: sessionId, data, at: Date.now() })
        }
        setFailure(null)
      } catch (e) {
        if (alive) setFailure({ id: sessionId, text: e instanceof Error ? e.message : String(e) })
      } finally {
        if (alive) timer = setTimeout(load, document.hidden ? 15000 : 3000)
      }
    }
    void load()
    return () => { alive = false; clearTimeout(timer) }
  }, [sessionId, revision])
  const data = snapshot?.id === sessionId ? snapshot.data : null
  const error = failure?.id === sessionId ? failure.text : null
  return { data, error, loading: !!sessionId && !data && !error }
}
