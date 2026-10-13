import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '../api/desktop'
import { miniBtn } from './ui'
import Toast from './Toast'

let stopCurrent: (() => void) | null = null
export default function SpeechButton({ text, agentId }: { text: string; agentId: string }) {
  const [state, setState] = useState<'idle' | 'loading' | 'playing'>('idle')
  const [error, setError] = useState('')
  const audio = useRef<HTMLAudioElement | null>(null)
  const url = useRef<string | null>(null)
  const controller = useRef<AbortController | null>(null)
  function stop() {
    if (stopCurrent === stop) stopCurrent = null
    controller.current?.abort(); controller.current = null
    audio.current?.pause(); audio.current = null
    if (url.current) URL.revokeObjectURL(url.current)
    url.current = null; setState('idle')
  }
  useEffect(() => () => stop(), [text, agentId])
  async function speak() {
    if (state !== 'idle') { stop(); return }
    stopCurrent?.(); stopCurrent = stop
    setError(''); setState('loading')
    const abort = new AbortController(); controller.current = abort
    try {
      const res = await apiFetch('/api/tts/speech', { method: 'POST', cache: 'no-store', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, signal: abort.signal, body: JSON.stringify({ text, agentId }) })
      if (!res.ok) {
        let message = res.status === 409 ? '请先在设置页配置 TTS 档案' : `语音合成失败（${res.status}）`
        try {
          const body = await res.json() as { message?: unknown; detail?: unknown }
          const detail = body.message ?? body.detail
          if (typeof detail === 'string' && detail.trim()) message = detail
        } catch { /* 代理返回 HTML 或空正文时保留 HTTP 状态提示 */ }
        throw new Error(message)
      }
      const blob = await res.blob()
      if (abort.signal.aborted) return
      url.current = URL.createObjectURL(blob)
      const player = new Audio(url.current); audio.current = player
      player.onended = stop
      player.onerror = () => { setError('音频播放失败'); stop() }
      await player.play()
      if (!abort.signal.aborted) setState('playing')
    } catch (e) { if (!abort.signal.aborted) { stop(); setError(e instanceof Error ? e.message : '语音播放失败') } }
  }
  return <><button type="button" className={miniBtn} disabled={!text.trim()} onClick={() => void speak()} title="朗读 Agent 回复">{state === 'loading' ? '取消合成' : state === 'playing' ? '停止 Speech' : 'Speech'}</button><Toast message={error || null} kind="error" onClose={() => setError('')} /></>
}
