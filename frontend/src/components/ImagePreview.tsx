import { lazy, Suspense, useState } from 'react'
import type { SlideImage } from 'yet-another-react-lightbox'
import { AppIcon } from './AppIcon'
import './imagePreview.css'

const ImageLightbox = lazy(() => import('./ImageLightbox'))

/** 通用图片缩略图入口；认证与资源加载由调用方负责，查看器仅在首次打开时加载。 */
export default function ImagePreview({ src, alt = '', loading, className }: {
  src?: string
  alt?: string
  loading?: 'eager' | 'lazy'
  className?: string
}) {
  const [preview, setPreview] = useState<{ slide: SlideImage; open: boolean } | null>(null)
  if (!src) return <img src={src} alt={alt} loading={loading} className={className} />
  return <span className="image-preview">
    <button type="button" className="image-preview-trigger" aria-label={'预览' + (alt || '图片')} aria-haspopup="dialog"
      onClick={event => {
        event.preventDefault()
        event.stopPropagation()
        const image = event.currentTarget.querySelector('img')
        setPreview({ open: true, slide: { src, alt: alt || '图片', width: image?.naturalWidth || undefined, height: image?.naturalHeight || undefined } })
      }}>
      <img src={src} alt={alt} loading={loading} className={className} />
    </button>
    {preview?.slide.src === src && <Suspense fallback={<span role="status" aria-label="正在加载图片预览" className="image-preview-loading">
      <span className="image-preview-spinner"><AppIcon name="loading" size={24} /></span>
    </span>}>
      <ImageLightbox open={preview.open} slide={preview.slide} close={() => setPreview(current => current && { ...current, open: false })} />
    </Suspense>}
  </span>
}
