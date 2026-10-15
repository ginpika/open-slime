import type { CodingEvent } from '../../api/coding'

/** Older attempt deltas remain in the audit log but must not become the new answer. */
export function currentAttemptText(events: CodingEvent[], attempt: number, executionId?: string): string {
  let boundary = 0
  for (const event of events) {
    if (event.type !== 'run.finished') continue
    try {
      const previous = Number(JSON.parse(event.payload).attempt)
      if (previous > 0 && previous < attempt) boundary = Math.max(boundary, event.id)
    } catch { /* An unreadable audit row does not change the current attempt. */ }
  }
  const textByRound = new Map<number, string>()
  for (const event of [...new Map(events.map(event => [event.id, event])).values()].filter(event => event.id > boundary).sort((a, b) => a.id - b.id)) {
    try {
      const row = JSON.parse(event.payload)
      if ((row.executionId || undefined) !== executionId) continue
      const eventAttempt = Number(executionId ? row.executionAttempt : row.attempt)
      if (eventAttempt > 0 && eventAttempt !== attempt) continue
      const round = Number(row.round) || 0
      if (event.type === 'model.retry') textByRound.set(round, '')
      else if (event.type === 'message.delta') textByRound.set(round, (textByRound.get(round) || '') + String(row.text || ''))
    } catch { /* Ignore malformed audit rows. */ }
  }
  return [...textByRound.values()].join('')
}
