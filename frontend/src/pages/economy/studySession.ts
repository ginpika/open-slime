// Shared handoff between the database and the workbench. No image data here.
const ACTIVE_STUDY = 'economy.activeStudy'
export function activeStudyId(): string | null {
  try { return sessionStorage.getItem(ACTIVE_STUDY) } catch { return null }
}
export function rememberStudy(id: string | null) {
  try {
    if (id) sessionStorage.setItem(ACTIVE_STUDY, id)
    else sessionStorage.removeItem(ACTIVE_STUDY)
  } catch { /* The current page remains usable without session storage. */ }
}
