/**
 * 智能体头像：有图渲染图，无图用名字首字兜底（与顶栏用户头像同一手感）。
 * 头像存的是 base64 data URL 小图（见成员页的上传），所以直接喂给 <img src> 即可。
 */
export default function PeerAvatar({
  name,
  avatar,
  size = 'h-9 w-9',
  textClass = 'text-[13px]',
  className = '',
}: {
  name: string
  avatar?: string | null
  /** 尺寸类（如 h-9 w-9 / h-20 w-20） */
  size?: string
  /** 兜底首字的字号类 */
  textClass?: string
  className?: string
}) {
  const trimmed = name.trim()
  if (avatar) {
    return (
      <img
        src={avatar}
        alt={name}
        className={`${size} shrink-0 rounded-full border border-line object-cover ${className}`}
      />
    )
  }
  return (
    <span
      aria-hidden="true"
      className={`${size} ${textClass} flex shrink-0 items-center justify-center rounded-full border border-line bg-fg/[0.06] font-semibold text-muted ${className}`}
    >
      {trimmed ? trimmed[0].toUpperCase() : '?'}
    </span>
  )
}
