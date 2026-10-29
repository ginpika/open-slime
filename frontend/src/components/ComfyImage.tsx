import { useEffect, useState } from 'react'
import { apiFetch } from '../api/desktop'
import ImagePreview from './ImagePreview'

/** 本地图片也经过 API 认证；blob URL 仅用于当前视图，消息中始终保存稳定的应用地址。 */
export default function ComfyImage({ src, alt }: { src?: string; alt?: string }) {
  const internal = !!src && /^\/api\/comfyui\/images\/[0-9a-f-]{36}$/.test(src)
  const [image, setImage] = useState<{ source: string; url: string } | null>(null)
  const [error, setError] = useState<{ source: string; message: string } | null>(null)
  useEffect(() => {
    if (!internal || !src) return
    const controller = new AbortController()
    let url: string | null = null
    let alive = true
    void apiFetch(src, { credentials: 'same-origin', signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error('图片加载失败，请检查登录状态后重新打开会话')
        const blob = await response.blob()
        if (!alive) return
        url = URL.createObjectURL(blob)
        setImage({ source: src, url })
      })
      .catch(e => { if (alive) setError({ source: src, message: e instanceof Error ? e.message : '图片加载失败' }) })
    return () => { alive = false; controller.abort(); if (url) URL.revokeObjectURL(url) }
  }, [internal, src])
  if (!internal) return <ImagePreview src={src} alt={alt ?? ''} loading="lazy" className="max-w-full rounded-lg" />
  if (error?.source === src) return <span role="alert" className="block text-sm text-danger">{error.message}</span>
  if (!image || image.source !== src) return <span role="status" className="block text-sm text-muted">正在加载图片…</span>
  return <ImagePreview src={image.url} alt={alt || '生成图片'} className="max-h-[640px] max-w-full rounded-lg object-contain" />
}
