import openai from '@lobehub/icons-static-svg/icons/openai.svg'
import claude from '@lobehub/icons-static-svg/icons/claude.svg'
import gemini from '@lobehub/icons-static-svg/icons/gemini.svg'
import deepseek from '@lobehub/icons-static-svg/icons/deepseek.svg'
import qwen from '@lobehub/icons-static-svg/icons/qwen.svg'
import grok from '@lobehub/icons-static-svg/icons/grok.svg'
import moonshot from '@lobehub/icons-static-svg/icons/moonshot.svg'
import zhipu from '@lobehub/icons-static-svg/icons/zhipu.svg'
import mistral from '@lobehub/icons-static-svg/icons/mistral.svg'
import { AppIcon } from './AppIcon'

const BRANDS = [
  [/^(gpt(?:-|$)|chatgpt(?:-|$)|o[134](?:-|$)|codex(?:-|$))/, openai],
  [/^claude(?:[-.]|$)/, claude],
  [/^gemini(?:[-.]|$)/, gemini],
  [/^deepseek(?:[-.]|$)/, deepseek],
  [/^(qwen|qwq)(?:[\d.-]|$)/, qwen],
  [/^grok(?:[-.]|$)/, grok],
  [/^(moonshot|kimi)(?:[-.]|$)/, moonshot],
  [/^(glm|chatglm)(?:[\d.-]|$)/, zhipu],
  [/^(mistral|mixtral|codestral)(?:[-.]|$)/, mistral],
] as const

/** 只识别模型家族，不根据代理地址推断供应商；未知模型使用通用图标。 */
export function ModelBrandIcon({ model, size = 16, className = '' }: { model?: string | null; size?: number; className?: string }) {
  const id = (model ?? '').trim().toLowerCase().split('/').at(-1) ?? ''
  const src = BRANDS.find(([pattern]) => pattern.test(id))?.[1]
  if (!src) return <AppIcon name="agent" size={size} className={className} />
  // CSS mask 使本地静态 SVG 跟随 currentColor，兼容所有明暗主题。
  return <span aria-hidden="true" className={`inline-block shrink-0 align-middle ${className}`} style={{
    width: size, height: size, backgroundColor: 'currentColor',
    maskImage: `url("${src}")`, WebkitMaskImage: `url("${src}")`,
    maskSize: 'contain', WebkitMaskSize: 'contain',
    maskRepeat: 'no-repeat', WebkitMaskRepeat: 'no-repeat',
    maskPosition: 'center', WebkitMaskPosition: 'center',
  }} />
}
