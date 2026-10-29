import { useEffect, useId, useRef } from 'react'
import { AppIcon } from '../../components/AppIcon'
import { Button } from '../../components/ui'

export default function CodingRunSettings({ minutes, command, onClose }: { minutes: number; command: string | null; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const title = useId()
  useEffect(() => {
    const element = dialog.current
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    element?.showModal()
    return () => { element?.close(); if (previous?.isConnected) previous.focus() }
  }, [])
  return <dialog ref={dialog} role="dialog" aria-labelledby={title} className="coding-run-settings" onCancel={event => { event.preventDefault(); onClose() }} onClick={event => { if (event.target === event.currentTarget) { const rect = event.currentTarget.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose() } }}>
    <header><div><AppIcon name="settings" /><h2 id={title}>运行设置</h2></div><button type="button" aria-label="关闭运行设置" onClick={onClose}><AppIcon name="close" /></button></header>
    <div className="coding-settings-content"><dl><div><dt>工具调用</dt><dd>不限制轮数</dd></div><div><dt>运行时限</dt><dd>每次最多 {minutes} 分钟</dd></div><div><dt>项目验证命令</dt><dd><code>{command || '未配置固定验证命令'}</code></dd></div></dl></div>
    <footer><Button variant="primary" onClick={onClose}>完成</Button></footer>
  </dialog>
}
