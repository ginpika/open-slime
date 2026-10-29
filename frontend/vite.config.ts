import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 品牌信息来自仓库根目录 branding.json，构建期注入 index.html 的标题
const branding = JSON.parse(
  readFileSync(fileURLToPath(new URL('../branding.json', import.meta.url)), 'utf-8'),
)
const webTitle = branding.webTitleSuffix
  ? `${branding.displayName} · ${branding.webTitleSuffix}`
  : branding.displayName

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    {
      // 把 index.html 里的占位符替换为 branding.json 派生的标题（改根 branding.json 即生效）
      name: 'branding-title',
      transformIndexHtml(html) {
        return html.replace(/__BRAND_TITLE__/g, webTitle)
      },
    },
  ],
  server: {
    port: 5173,
    proxy: {
      // dev：前端同源走 /api，由 vite 代理到 minis backend
      '/api': {
        target: process.env.VITE_DEV_API ?? 'http://localhost:18081',
        changeOrigin: true,
      },
      '/ws': {
        target: process.env.VITE_DEV_API ?? 'http://localhost:18081',
        changeOrigin: true,
        ws: true,
      },
    },
    allowedHosts:[
      'local.minis.ginpika.cc'
    ]
  },
})
