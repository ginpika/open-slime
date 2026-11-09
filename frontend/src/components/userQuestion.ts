import type { TraceSegment } from '../api/client'

export type UserQuestion = { question: string; options: { label: string; description: string }[] }

/** 一条以问题收尾的回复：问题卡片之前/之后的正文分开渲染，正文不因提问而丢失。 */
export type QuestionTurn = { question: UserQuestion; before: string; after: string }

const IMAGE_TAIL = /^(?:\n\n!\[生成图片\]\(\/api\/comfyui\/images\/[0-9a-f-]{36}\))+$/

export function questionContent(value: UserQuestion): string {
  return value.question + value.options.map((option, i) =>
    `\n\n${i + 1}. ${option.label}${option.description ? `：${option.description}` : ''}`,
  ).join('') + '\n\n也可以自定义回复。'
}

/** The canonical question closes a completed reply: any narration the model produced before the
 * tool call stays ahead of it, and the server may append generated images after it.
 * Failed tool requests and cancelled streams must never become interactive cards. */
export function questionTurn(message: { content: string; trace?: TraceSegment[]; streaming?: boolean }): QuestionTurn | null {
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
    const question = { question: raw.question.trim(), options }
    const content = questionContent(question)
    // 从后往前找：问题之后只允许空或追加的生成图片，之前的任意正文都属于这条回复
    let index = message.content.lastIndexOf(content)
    while (index >= 0) {
      const after = message.content.slice(index + content.length)
      if (!after || IMAGE_TAIL.test(after)) {
        return { question, before: message.content.slice(0, index).trimEnd(), after }
      }
      index = index > 0 ? message.content.lastIndexOf(content, index - 1) : -1
    }
    return null
  } catch { return null }
}
