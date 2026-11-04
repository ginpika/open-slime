import { useCallback, useEffect, useRef, useState } from 'react'

type RecognitionResult = { isFinal: boolean; [index: number]: { transcript: string } }
type RecognitionEvent = { resultIndex: number; results: { length: number; [index: number]: RecognitionResult } }
type Recognition = {
  lang: string
  continuous: boolean
  interimResults: boolean
  onstart: (() => void) | null
  onend: (() => void) | null
  onresult: ((event: RecognitionEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  start: () => void
  stop: () => void
  abort: () => void
}
type RecognitionConstructor = new () => Recognition

function recognitionConstructor(): RecognitionConstructor | undefined {
  if (typeof window === 'undefined') return undefined
  const browser = window as unknown as {
    SpeechRecognition?: RecognitionConstructor
    webkitSpeechRecognition?: RecognitionConstructor
  }
  return browser.SpeechRecognition ?? browser.webkitSpeechRecognition
}

/** Dictation only starts from toggle; final text is inserted, never sent. */
export function useDictation({ onText, onError }: {
  onText: (text: string) => void
  onError: (message: string) => void
}) {
  const [supported] = useState(() => Boolean(recognitionConstructor()))
  const [listening, setListening] = useState(false)
  const active = useRef<Recognition | null>(null)
  const callbacks = useRef({ onText, onError })
  callbacks.current = { onText, onError }

  const stop = useCallback(() => {
    const recognition = active.current
    if (!recognition) return
    try { recognition.stop() } catch {
      recognition.onstart = null; recognition.onend = null
      recognition.onresult = null; recognition.onerror = null
      active.current = null
      try { recognition.abort() } catch { /* Capture may already be unavailable. */ }
    }
    setListening(false)
  }, [])

  const toggle = useCallback(() => {
    if (active.current) { stop(); return }
    const Constructor = recognitionConstructor()
    if (!Constructor) return
    const recognition = new Constructor()
    active.current = recognition
    const completed = new Set<number>()
    const release = () => {
      recognition.onstart = null; recognition.onend = null
      recognition.onresult = null; recognition.onerror = null
      if (active.current === recognition) active.current = null
      setListening(false)
    }
    recognition.lang = typeof navigator === 'undefined' ? 'zh-CN' : navigator.language || 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = false
    recognition.onstart = () => setListening(true)
    recognition.onend = release
    recognition.onresult = event => {
      const parts: string[] = []
      for (let index = event.resultIndex; index < event.results.length; index++) {
        const result = event.results[index]
        if (!result.isFinal || completed.has(index)) continue
        completed.add(index)
        const transcript = result[0]?.transcript.trim()
        if (transcript) parts.push(transcript)
      }
      if (parts.length) callbacks.current.onText(parts.join(' '))
    }
    recognition.onerror = event => {
      release()
      try { recognition.abort() } catch { /* The browser may have already ended capture. */ }
      if (event.error === 'aborted') return
      const messages: Record<string, string> = {
        'not-allowed': '无法使用麦克风，请检查浏览器权限。',
        'service-not-allowed': '当前浏览器未允许语音识别。',
        'audio-capture': '没有找到可用的麦克风。',
        'no-speech': '没有听清，请再试一次。',
        network: '语音识别连接失败，请稍后重试。',
        'language-not-supported': '当前浏览器暂不支持此语言的语音识别。',
      }
      callbacks.current.onError(messages[event.error] ?? '语音识别暂时不可用，请稍后重试。')
    }
    try { recognition.start() }
    catch { release(); callbacks.current.onError('无法开始听写，请稍后重试。') }
  }, [stop])

  useEffect(() => () => {
    const recognition = active.current
    active.current = null
    if (!recognition) return
    recognition.onstart = null; recognition.onend = null
    recognition.onresult = null; recognition.onerror = null
    try { recognition.abort() } catch { /* Capture may have already stopped. */ }
  }, [])

  return { supported, listening, toggle, stop }
}
