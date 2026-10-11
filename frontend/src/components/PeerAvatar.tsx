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
  bordered = true,
}: {
  name: string
  avatar?: string | null
  /** 尺寸类（如 h-9 w-9 / h-20 w-20） */
  size?: string
  /** 兜底首字的字号类 */
  textClass?: string
  className?: string
  /** 是否带描边环（画布等「不用描边」的场景可关掉，只留图本身） */
  bordered?: boolean
}) {
  const trimmed = name.trim()
  const ring = bordered ? ' border border-line' : ''
  if (avatar) {
    return (
      <img
        src={avatar}
        alt={name}
        className={`${size} shrink-0 rounded-full object-cover${ring} ${className}`}
      />
    )
  }
  return (
    <span
      aria-hidden="true"
      className={`${size} ${textClass} flex shrink-0 items-center justify-center rounded-full bg-fg/[0.06] font-semibold text-muted${ring} ${className}`}
    >
      {trimmed ? trimmed[0].toUpperCase() : '?'}
    </span>
  )
}
