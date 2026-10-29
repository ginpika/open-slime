import { useMemo } from 'react'
import Lightbox, { type SlideImage } from 'yet-another-react-lightbox'
import Zoom from 'yet-another-react-lightbox/plugins/zoom'
import { AppIcon } from './AppIcon'
import 'yet-another-react-lightbox/styles.css'
import './imageLightbox.css'

const plugins = [Zoom]
const labels = {
  Lightbox: '图片预览',
  Close: '关闭图片预览',
  Next: '下一张图片',
  Previous: '上一张图片',
  'Zoom in': '放大图片',
  'Zoom out': '缩小图片',
  'Photo gallery': '图片预览',
  Carousel: '图片浏览',
  Slide: '图片',
  '{index} of {total}': '第 {index} 张，共 {total} 张',
}
const render = {
  iconClose: () => <AppIcon name="close" size={20} />,
  iconZoomIn: () => <AppIcon name="zoom-in" size={20} />,
  iconZoomOut: () => <AppIcon name="zoom-out" size={20} />,
  iconLoading: () => <AppIcon name="loading" size={20} className="animate-spin" />,
  iconError: () => <AppIcon name="alert" size={20} />,
  buttonPrev: () => null,
  buttonNext: () => null,
}

export default function ImageLightbox({ open, slide, close }: { open: boolean; slide: SlideImage; close: () => void }) {
  const slides = useMemo(() => [slide], [slide])
  return <Lightbox open={open} close={close} slides={slides} plugins={plugins} labels={labels} render={render}
    className="image-lightbox" carousel={{ finite: true, preload: 0 }}
    controller={{ closeOnBackdropClick: true, closeOnEscape: true }} zoom={{ scrollToZoom: true, maxZoomPixelRatio: 4 }}
    portal={{ container: { onClick: event => event.stopPropagation() } }} />
}
