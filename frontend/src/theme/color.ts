/** 零依赖色彩运算：hex↔rgb↔hsl、插值、去饱和、WCAG 对比度 */

export type Rgb = { r: number; g: number; b: number }
export type Hsl = { h: number; s: number; l: number }

const HEX_RE = /^#?([0-9a-f]{6})$/i

/** 规范化为 #rrggbb；非法输入返回 null */
export function normalizeHex(input: string): string | null {
  const m = HEX_RE.exec(input.trim())
  return m ? `#${m[1].toLowerCase()}` : null
}

export function rgbToHex({ r, g, b }: Rgb): string {
  const to = (v: number) => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, '0')
  return `#${to(r)}${to(g)}${to(b)}`
}

export function hexToRgb(hex: string): Rgb {
  const n = normalizeHex(hex)
  if (!n) return { r: 0, g: 0, b: 0 }
  const v = parseInt(n.slice(1), 16)
  return { r: (v >> 16) & 0xff, g: (v >> 8) & 0xff, b: v & 0xff }
}

export function rgbToHsl({ r, g, b }: Rgb): Hsl {
  const rn = r / 255
  const gn = g / 255
  const bn = b / 255
  const max = Math.max(rn, gn, bn)
  const min = Math.min(rn, gn, bn)
  const d = max - min
  const l = (max + min) / 2
  let h = 0
  let s = 0
  if (d !== 0) {
    s = d / (1 - Math.abs(2 * l - 1))
    if (max === rn) h = 60 * (((gn - bn) / d) % 6)
    else if (max === gn) h = 60 * ((bn - rn) / d + 2)
    else h = 60 * ((rn - gn) / d + 4)
  }
  if (h < 0) h += 360
  return { h, s, l }
}

export function hslToRgb({ h, s, l }: Hsl): Rgb {
  const hn = ((h % 360) + 360) % 360
  const c = (1 - Math.abs(2 * l - 1)) * s
  const x = c * (1 - Math.abs(((hn / 60) % 2) - 1))
  const m = l - c / 2
  let rgb: [number, number, number]
  if (hn < 60) rgb = [c, x, 0]
  else if (hn < 120) rgb = [x, c, 0]
  else if (hn < 180) rgb = [0, c, x]
  else if (hn < 240) rgb = [0, x, c]
  else if (hn < 300) rgb = [x, 0, c]
  else rgb = [c, 0, x]
  return { r: (rgb[0] + m) * 255, g: (rgb[1] + m) * 255, b: (rgb[2] + m) * 255 }
}

export function hexToHsl(hex: string): Hsl {
  return rgbToHsl(hexToRgb(hex))
}

export function hslToHex(hsl: Hsl): string {
  return rgbToHex(hslToRgb(hsl))
}

/** 线性插值混色：t=0 → a，t=1 → b */
export function mix(a: string, b: string, t: number): string {
  const ca = hexToRgb(a)
  const cb = hexToRgb(b)
  const k = Math.min(1, Math.max(0, t))
  return rgbToHex({
    r: ca.r + (cb.r - ca.r) * k,
    g: ca.g + (cb.g - ca.g) * k,
    b: ca.b + (cb.b - ca.b) * k,
  })
}

/** 降低饱和度（保留原色相），sFactor ∈ [0,1] 为保留比例 */
export function desaturate(hex: string, sFactor: number): string {
  const hsl = hexToHsl(hex)
  return hslToHex({ h: hsl.h, s: hsl.s * Math.min(1, Math.max(0, sFactor)), l: hsl.l })
}

/** WCAG 相对亮度 */
function luminance(hex: string): number {
  const { r, g, b } = hexToRgb(hex)
  const ch = (v: number) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
}

/** WCAG 对比度比值（1 ~ 21） */
export function contrast(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  const [hi, lo] = la >= lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}

/** 在色上选取可读文字色（白 / 纯黑中对比度更高者；深色底→白字，浅色底→纯黑字） */
export function pickText(on: string): string {
  const dark = contrast(on, '#000000')
  const light = contrast(on, '#ffffff')
  return light >= dark ? '#ffffff' : '#000000'
}

/** 深浅底判断（供分类色板等派生色选择亮度档） */
export function isDark(hex: string): boolean {
  return luminance(hex) < 0.5
}
