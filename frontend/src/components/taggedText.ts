/** Prompt 标签只作文本分区，不解析为 HTML；保留所有字符与未配对标签。 */
export type TaggedTextPart =
  | { type: 'text'; text: string }
  | { type: 'tag'; name: string; opening: string; closing: string; children: TaggedTextPart[] }

type Range = { name: string; start: number; openEnd: number; closeStart: number; end: number }

export function parseTaggedText(text: string): TaggedTextPart[] {
  const stack: { name: string; start: number; openEnd: number }[] = []
  const ranges: Range[] = []
  // 引号内的 > 不是标签终点；自闭合标签和注释保持原文。
  const tokens = /<!--[\s\S]*?-->|<\/?([A-Za-z_][\w.:-]*)(?:\s+(?:[^<>"']|"[^"]*"|'[^']*')*)?\s*\/?>/g
  for (const match of text.matchAll(tokens)) {
    const token = match[0], name = match[1]
    if (!name || /\/\s*>$/.test(token)) continue
    if (!token.startsWith('</')) {
      stack.push({ name, start: match.index, openEnd: match.index + token.length })
    } else {
      const opening = stack.pop()
      if (opening?.name === name) {
        ranges.push({ ...opening, closeStart: match.index, end: match.index + token.length })
      } else {
        // 交叉或拼写不匹配的标签不能吞掉正文，也不猜测闭合关系。
        stack.length = 0
      }
    }
  }

  ranges.sort((a, b) => a.start - b.start)
  let next = 0
  function parts(from: number, to: number): TaggedTextPart[] {
    const result: TaggedTextPart[] = []
    let cursor = from
    while (next < ranges.length && ranges[next].start < to) {
      const range = ranges[next++]
      if (range.start > cursor) result.push({ type: 'text', text: text.slice(cursor, range.start) })
      result.push({
        type: 'tag', name: range.name,
        opening: text.slice(range.start, range.openEnd),
        closing: text.slice(range.closeStart, range.end),
        children: parts(range.openEnd, range.closeStart),
      })
      cursor = range.end
    }
    if (cursor < to) result.push({ type: 'text', text: text.slice(cursor, to) })
    return result
  }
  return parts(0, text.length)
}
