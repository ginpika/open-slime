import { requestJson } from './client'

export type TodoState = 'idea' | 'ready' | 'doing' | 'done' | 'dropped'
export type TodoItem = {
  id: string; title: string; note: string; projectId: string | null; projectName: string | null
  projectStatus: string | null; state: TodoState; plannedOn: string | null; dueAt: number | null
  createdAt: number; updatedAt: number; firstStartedAt: number | null; completedAt: number | null
  closedAt: number | null; version: number; spentMillis: number
}
export type PersonalFocus = {
  id: string; todoId: string; title: string; projectId: string | null; projectName: string | null
  startedAt: number; endedAt: number | null; source: 'timer' | 'manual'; note: string; version: number
}
export type TodoEvent = { id: number; kind: string; detail: string; occurredAt: number }
export type TodoProject = { id: string; name: string; status: string }
export type DayNote = { day: string; body: string; version: number }
export type TodoProjectSummary = TodoProject & { openCount: number; doingCount: number; completedCount: number; droppedCount: number; spentMillis: number }
export type TodoDayWork = {
  day: string; zone: string; now: number; rangeStart: number; rangeEnd: number
  hours: { label: string; startedAt: number; endedAt: number }[]; focus: PersonalFocus[]; items: TodoItem[]
}
export type TodoOverview = {
  today: string; zone: string; now: number; dayStart: number; dayEnd: number
  items: TodoItem[]; todayFocus: PersonalFocus[]; active: PersonalFocus | null; projects: TodoProject[]; dayNote: DayNote
}
export type TodoDetail = { item: TodoItem; focus: PersonalFocus[]; events: TodoEvent[] }
export type TodoAllocation = { todoId: string; title: string; projectId: string | null; projectName: string | null; millis: number }
export type TodoReview = {
  month: string; now: number; rangeStart: number; rangeEnd: number; completedCount: number; droppedCount: number
  spentMillis: number; activeDays: number; allocations: TodoAllocation[]; closed: TodoItem[]
  footprints: { todoId: string; title: string; detail: string; occurredAt: number }[]; notes: DayNote[]
}
export type TodoInput = { title: string; note: string; projectId: string | null; plannedOn: string | null; dueAt: number | null; version?: number }
export type FocusInput = { todoId: string; startedAt: number; endedAt: number; note: string; version?: number }
export type TodoAction = 'complete' | 'drop' | 'reopen'

export const personalTimeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
const query = (projectId: string, month?: string) => new URLSearchParams({ zone: personalTimeZone(), ...(projectId ? { projectId } : {}), ...(month ? { month } : {}) })
const id = encodeURIComponent
const write = <T,>(path: string, method: string, body?: unknown) => requestJson<T>(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }, 15000)
export const getTodoOverview = (projectId: string, signal?: AbortSignal) => requestJson<TodoOverview>(`/api/todos/overview?${query(projectId)}`, { signal })
export const getTodoReview = (projectId: string, month: string, signal?: AbortSignal) => requestJson<TodoReview>(`/api/todos/review?${query(projectId, month)}`, { signal })
export const getTodoProjects = (signal?: AbortSignal) => requestJson<TodoProjectSummary[]>('/api/todos/projects', { signal })
export const getTodoDay = (projectId: string, day: string, signal?: AbortSignal) => requestJson<TodoDayWork>(`/api/todos/day?${query(projectId)}${day ? `&day=${encodeURIComponent(day)}` : ''}`, { signal })
export const getTodoDetail = (todoId: string, signal?: AbortSignal) => requestJson<TodoDetail>(`/api/todos/${id(todoId)}`, { signal })
export const createTodo = (body: TodoInput) => write<TodoItem>('/api/todos', 'POST', body)
export const saveTodo = (todoId: string, body: TodoInput) => write<TodoItem>(`/api/todos/${id(todoId)}`, 'PUT', body)
export const transitionTodo = (todo: TodoItem, action: TodoAction) => write<TodoItem>(`/api/todos/${id(todo.id)}/state`, 'POST', { action, version: todo.version })
export const startPersonalFocus = (todoId: string, expectedActiveId: string | null) => write<PersonalFocus>(`/api/todos/${id(todoId)}/start`, 'POST', { expectedActiveId })
export const stopPersonalFocus = (focusId: string) => write<PersonalFocus>(`/api/focus-sessions/${id(focusId)}/stop`, 'POST')
export const addPersonalFocus = (body: FocusInput) => write<PersonalFocus>('/api/focus-sessions', 'POST', body)
export const editPersonalFocus = (focusId: string, body: FocusInput) => write<PersonalFocus>(`/api/focus-sessions/${id(focusId)}`, 'PUT', body)
export const deletePersonalFocus = (focus: PersonalFocus) => write<{ deleted: boolean }>(`/api/focus-sessions/${id(focus.id)}?version=${focus.version}`, 'DELETE')
export const savePersonalDayNote = (note: DayNote, body: string) => write<DayNote>(`/api/day-notes/${id(note.day)}?${query('')}`, 'PUT', { body, version: note.version })
