/** UI fixture only; no API requests or user data. */
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import ProjectDialog, { type ProjectChoice } from '../src/components/ProjectDialog'
import { initTheme, themeActions } from '../src/theme/store'
import '../src/index.css'

initTheme()
const project = { id: 'demo', userId: null, name: '已存在的项目', kind: 'story', narrative: true, status: 'active', note: null, createdAt: 0, updatedAt: 0 }
function Preview() {
  const [open, setOpen] = useState(true)
  const [organize, setOrganize] = useState(false)
  const [result, setResult] = useState<ProjectChoice | null>(null)
  const [failure, setFailure] = useState(false)
  return <main className="min-h-screen bg-bg p-6 text-fg">
    <div className="flex gap-4">
      <button onClick={() => { setOrganize(false); setOpen(true) }}>创建项目</button>
      <button onClick={() => { setOrganize(true); setOpen(true) }}>整理会话</button>
      <button onClick={() => themeActions.setSchemeMode('dark')}>深色</button>
      <button onClick={() => themeActions.setSchemeMode('light')}>浅色</button>
      <label><input type="checkbox" checked={failure} onChange={e => setFailure(e.target.checked)} />模拟保存失败</label>
    </div>
    <pre>{result ? JSON.stringify(result) : '等待保存'}</pre>
    {open && <ProjectDialog organize={organize} projects={[project]} initialName="" onClose={() => setOpen(false)} onConfirm={async choice => {
      if (failure) throw new Error('模拟网络错误，请重试')
      setResult(choice)
    }} />}
  </main>
}
createRoot(document.getElementById('root')!).render(<Preview />)
