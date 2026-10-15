import { useEffect, useRef, useState } from 'react'
import BrowserWorkspace, { WEB_BUTTON } from './BrowserWorkspace'

export default function ConversationBrowser({ sessionId }: { sessionId: string }) {
  const [open, setOpen] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    if (open && !dialog.current?.open) dialog.current?.showModal()
    if (!open && dialog.current?.open) dialog.current.close()
  }, [open])
  return <>
    <button type="button" onClick={() => setOpen(true)} className="shrink-0 rounded-lg border border-line px-2.5 py-2 text-sm text-muted transition hover:border-accent hover:text-fg" title="查看 Agent 正在操作的浏览器">浏览器</button>
    <dialog ref={dialog} onClose={() => setOpen(false)} className="m-auto max-h-[90dvh] w-[min(1000px,94vw)] overflow-auto rounded-2xl border border-line bg-bg p-5 text-fg shadow-2xl backdrop:bg-black/40">
      <div className="mb-4 flex items-center justify-between gap-3"><h2 className="text-lg font-semibold">会话浏览器</h2><button type="button" className={WEB_BUTTON} onClick={() => setOpen(false)}>关闭预览</button></div>
      {open && <BrowserWorkspace key={sessionId} chatSessionId={sessionId} />}
    </dialog>
  </>
}
