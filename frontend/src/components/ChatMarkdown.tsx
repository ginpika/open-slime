import { memo } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import type { PluggableList } from 'unified'
import ComfyImage from './ComfyImage'

/** 聊天气泡内的 markdown 组件映射：比文章页更紧凑的排版以贴合气泡。
 *  onBubble = 位于用户深色气泡内（bg-fg），行内代码底色、引用/表格边框等叠加层
 *  改用前景色 primary-fg 的半透明变体，保证气泡内对比度。
 *  两套映射在模块级构建一次（普通 / 深色气泡），避免每次渲染重建映射对象——否则
 *  ReactMarkdown 每次父级重渲染都要全量重新解析 markdown，是长会话打字卡顿的主因之一 */
const makeMdComponents = (onBubble: boolean): Components => ({
  img: ComfyImage,
  // 固定相对字号，避免嵌套 small 连续缩小；移动端保持 16px 阅读字号。
  small: ({ children }) => <small className="text-[1rem] sm:text-[0.875rem]">{children}</small>,
  h1: (p: object) => (
    <h1 className="my-2 text-lg font-semibold first:mt-0 last:mb-0" {...p} />
  ),
  h2: (p: object) => (
    <h2 className="my-2 text-md font-semibold first:mt-0 last:mb-0" {...p} />
  ),
  h3: (p: object) => (
    <h3 className="my-2 text-base font-semibold first:mt-0 last:mb-0" {...p} />
  ),
  p: (p: object) => <p className="my-1.5 first:mt-0 last:mb-0" {...p} />,
  a: (p: object) => (
    <a
      className={
        onBubble
          ? 'underline decoration-primary-fg/50 underline-offset-2'
          : 'text-accent underline decoration-accent/40 underline-offset-2 transition hover:decoration-accent'
      }
      target="_blank"
      rel="noreferrer"
      {...p}
    />
  ),
  ul: (p: object) => (
    <ul className="my-1.5 list-disc space-y-0.5 pl-5 first:mt-0 last:mb-0" {...p} />
  ),
  ol: (p: object) => (
    <ol className="my-1.5 list-decimal space-y-0.5 pl-5 first:mt-0 last:mb-0" {...p} />
  ),
  blockquote: (p: object) => (
    <blockquote
      className={`my-1.5 border-l pl-3 first:mt-0 last:mb-0 ${
        onBubble ? 'border-primary-fg/30' : 'border-line'
      }`}
      {...p}
    />
  ),
  code: ({ className, children, ...rest }: { className?: string; children?: React.ReactNode }) =>
    className?.includes('language-') ? (
      <code className="font-mono text-sm leading-6" {...rest}>
        {children}
      </code>
    ) : (
      <code
        className={`rounded px-1 py-0.5 font-mono text-sm ${
          onBubble ? 'bg-primary-fg/15' : 'bg-fg/5'
        }`}
        {...rest}
      >
        {children}
      </code>
    ),
  pre: (p: object) => (
    <pre
      className={`scrollbar-slim my-2 overflow-x-auto rounded-lg border p-3 font-mono text-sm leading-6 first:mt-0 last:mb-0 ${
        onBubble ? 'border-primary-fg/20 bg-primary-fg/5' : 'border-line bg-fg/[0.03]'
      }`}
      {...p}
    />
  ),
  table: (p: object) => (
    <table className="my-2 w-full border-collapse text-base first:mt-0 last:mb-0" {...p} />
  ),
  th: (p: object) => (
    <th
      className={`border px-2 py-1 text-left font-semibold ${
        onBubble ? 'border-primary-fg/25 bg-primary-fg/10' : 'border-line bg-fg/5'
      }`}
      {...p} />
  ),
  td: (p: object) => (
    <td
      className={`border px-2 py-1 ${onBubble ? 'border-primary-fg/25' : 'border-line'}`}
      {...p} />
  ),
  hr: () => <hr className={`my-3 border-t ${onBubble ? 'border-primary-fg/25' : 'border-line'}`} />,
})

const MD_COMPONENTS = { plain: makeMdComponents(false), bubble: makeMdComponents(true) }

/** remark 插件列表（模块级常量，保持引用稳定） */
const REMARK_PLUGINS = [remarkGfm]

// 模型输出按不可信 HTML 处理：解析后立即清洗，再交给 React 组件。
// 沿用默认安全白名单，仅补充 small；不开放 style、事件或任意 class。
const REHYPE_PLUGINS = [rehypeRaw, [rehypeSanitize, {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames ?? []), 'small'],
}]] satisfies PluggableList

/** 聊天气泡内的 markdown 渲染：memo 化，content 与气泡态都不变时跳过解析 */
const ChatMarkdown = memo(function ChatMarkdown({
  content,
  onBubble = false,
}: {
  content: string
  onBubble?: boolean
}) {
  return (
    <ReactMarkdown
      remarkPlugins={REMARK_PLUGINS}
      rehypePlugins={onBubble ? undefined : REHYPE_PLUGINS}
      components={onBubble ? MD_COMPONENTS.bubble : MD_COMPONENTS.plain}
    >
      {content}
    </ReactMarkdown>
  )
})

export default ChatMarkdown
