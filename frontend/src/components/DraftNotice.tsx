export function DraftNotice({ dirty, onDiscard, disabled = false }: { dirty: boolean; onDiscard: () => void; disabled?: boolean }) {
  if (!dirty) return null
  return <div className="draft-notice"><span role="status">未保存草稿已在当前窗口保留；换主题或切页面后可以继续编辑。</span><button type="button" disabled={disabled} onClick={onDiscard}>放弃修改</button></div>
}
