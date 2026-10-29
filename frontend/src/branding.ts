/**
 * 品牌文案的唯一出口：读取仓库根目录 branding.json，派生出界面直接可用的字符串。
 * 改正式名称只需改根目录 branding.json（前端各处的标题/页脚文案随之统一）。
 */
import raw from '../../branding.json'

/** 面向用户显示的正式名称 */
export const BRAND_NAME = raw.displayName

export const BRAND = {
  /** 正式名称 */
  name: raw.displayName,
  /** 正式名称的大写形式（用于 Mono 排版的小标签） */
  nameUpper: raw.displayName.toUpperCase(),
  /** 一句话标语 */
  tagline: raw.tagline,
  /** 浏览器标签标题：<名称> · <后缀>（后缀为空时仅显示名称） */
  title: raw.webTitleSuffix ? `${raw.displayName} · ${raw.webTitleSuffix}` : raw.displayName,
  /** 页脚：<名称> · <标语> */
  footer: `${raw.displayName} · ${raw.tagline}`,
  /** 技术标识（小写）：用于容器名等 */
  slug: raw.slug,
  /** 默认后端容器名：<slug>-backend */
  backendContainer: `${raw.slug}-backend`,
  /** 终端会话容器名前缀：<slug>-term- */
  termPrefix: `${raw.slug}-term-`,
} as const
