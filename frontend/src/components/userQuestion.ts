import type { TraceSegment } from '../api/client'

export type UserQuestion = { question: string; options: { label: string; description: string }[] }

export function questionContent(value: UserQuestion): string {
  return value.question + value.options.map((option, i) =>
    `\n\n${i + 1}. ${option.label}${option.description ? `：${option.description}` : ''}`,
  ).join('') + '\n\n也可以自定义回复。'
}

/** Only a completed question turn has the canonical question as its final answer.
 * Failed tool requests and cancelled streams must never become interactive cards. */
export function userQuestion(message: { content: string; trace?: TraceSegment[]; streaming?: boolean }): UserQuestion | null {
  if (message.streaming) return null
  const last = message.trace?.filter(segment => segment.type === 'tool').at(-1)
  if (last?.name !== 'request_user_input' || !last.args) return null
  try {
    const raw = JSON.parse(last.args)
    if (typeof raw?.question !== 'string' || !raw.question.trim() || raw.question.trim().length > 1000
      || !Array.isArray(raw.options) || raw.options.length < 2 || raw.options.length > 6) return null
    const options: UserQuestion['options'] = []
    for (const option of raw.options) {
      if (typeof option?.label !== 'string' || !option.label.trim() || option.label.trim().length > 120
        || (option.description != null && typeof option.description !== 'string')) return null
      const description = option.description?.trim() ?? ''
      if (description.length > 500) return null
      options.push({ label: option.label.trim(), description })
    }
    if (new Set(options.map(option => option.label)).size !== options.length) return null
    const value = { question: raw.question.trim(), options }
    const content = questionContent(value)
    if (!message.content.startsWith(content)) return null
    const remainder = message.content.slice(content.length)
    // The server may append images generated earlier in this turn to the final reply.
    return !remainder || /^(?:\n\n!\[生成图片\]\(\/api\/comfyui\/images\/[0-9a-f-]{36}\))+$/.test(remainder) ? value : null
  } catch { return null }
}
