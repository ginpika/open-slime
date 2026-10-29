import type { ITheme } from '@xterm/xterm'
import { isDark, mix, readableText } from '../../theme/color'

export function terminalTheme(vars: Record<string, string>): ITheme {
  const background = vars['--c-bg'], foreground = vars['--c-fg'], accent = vars['--c-accent']
  const dark = isDark(background)
  const color = (token: string) => readableText(vars[token] || accent, background)
  return {
    background, foreground, cursor: color('--c-accent'), cursorAccent: background,
    selectionBackground: mix(background, accent, .25), selectionForeground: foreground,
    black: dark ? mix(background, foreground, .12) : foreground, brightBlack: color('--c-muted'),
    white: dark ? mix(background, foreground, .85) : mix(background, foreground, .15), brightWhite: dark ? foreground : background,
    red: color('--c-danger'), brightRed: color('--c-danger'), green: color('--c-ok'), brightGreen: color('--c-ok'),
    yellow: color('--c-warn'), brightYellow: color('--c-warn'), blue: color('--c-entity-1'), brightBlue: color('--c-entity-1'),
    magenta: color('--c-entity-3'), brightMagenta: color('--c-entity-3'), cyan: color('--c-entity-2'), brightCyan: color('--c-entity-2'),
  }
}
