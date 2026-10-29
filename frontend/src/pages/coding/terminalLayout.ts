export type TerminalLayout = { inEditor: boolean; open: boolean; view: boolean }

const key = (project: string) => `minis.coding.terminalLayout.${project}`

export function readTerminalLayout(project: string): TerminalLayout {
  const defaults = { inEditor: false, open: true, view: false }
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(key(project)) || 'null')
    if (saved && typeof saved === 'object') {
      const layout = saved as Partial<TerminalLayout>
      return {
        inEditor: typeof layout.inEditor === 'boolean' ? layout.inEditor : defaults.inEditor,
        open: typeof layout.open === 'boolean' ? layout.open : defaults.open,
        view: typeof layout.view === 'boolean' ? layout.view : defaults.view,
      }
    }
    // Preserve the previous tab-scoped placement on the first visit after upgrading.
    const inEditor = sessionStorage.getItem(key(project)) === 'editor'
    return { ...defaults, inEditor, view: inEditor }
  } catch { return defaults }
}

export function saveTerminalLayout(project: string, layout: TerminalLayout): void {
  try { localStorage.setItem(key(project), JSON.stringify(layout)) } catch { /* Layout remains usable when storage is unavailable. */ }
}
