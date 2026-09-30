import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { initTheme } from './theme/store'
import './index.css'
import App from './App.tsx'

// 首帧前应用持久化主题（变量 + 用户 CSS），避免二次闪烁
initTheme()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
