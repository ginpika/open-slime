import { rallySummary } from './model'

/** 状态徽章保持简短，完整原始记录在徽章之外展开，兼容历史报告。 */
export default function RallyNotice({ text }: { text: string }) {
  const summary = rallySummary(text)
  return (
    <div className="collab-rally-notice">
      <span className="collab-rally-status">{summary}</span>
      {summary !== text && (
        <details className="collab-rally-details">
          <summary>查看完整记录</summary>
          <p>{text}</p>
        </details>
      )}
    </div>
  )
}
