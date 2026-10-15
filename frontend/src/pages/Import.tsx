/**
 * 文档导入页：粘贴文本 → 直接作为 markdown 文章保存（data/article/{url}.md）
 *
 * 呈现来自 PageKit：编辑器本体是表单控件（走令牌，圆角/配色/字体随主题），
 * 字数与提交动作放进 kit.Toolbar，结果卡与底部说明换成 kit.Panel / kit.Note。
 */

import { useState } from 'react'
import { importArticle, loginUrlOf, type ImportResult } from '../api/client'
import Toast from '../components/Toast'
import { btnPrimary } from '../components/ui'
import { usePageKit } from '../kit'
import { DraftNotice } from '../components/DraftNotice'
import { useDraftState, useRememberedState } from '../components/windowState'

/** 从 hexo/hugo front-matter（--- YAML / +++ TOML 头部块）中提取 title；无则返回 null */
function frontMatterTitle(content: string): string | null {
  const lines = content.split('\n')
  if (lines.length === 0) return null
  const fence = lines[0].trim()
  if (fence !== '---' && fence !== '+++') return null
  const re = fence === '---' ? /^title:\s*(.*)$/ : /^title\s*=\s*(.*)$/
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === fence) return null // 块结束仍未找到
    const m = re.exec(lines[i])
    if (m) {
      let v = m[1].trim()
      if (
        v.length >= 2 &&
        ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'")))
      ) {
        v = v.slice(1, -1)
      }
      return v.trim() || null
    }
  }
  return null
}

/** 表单控件基类：圆角/描边/字体全部走令牌，因此各主题自适应 */
const FIELD =
  'w-full rounded-md border border-line bg-bg px-3.5 py-2.5 text-base text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25'

export default function Import() {
  const kit = usePageKit()
  const [draft, setDraft, draftState] = useDraftState('import:article', { content: '', title: '', autoTitle: '' })
  const { content, title, autoTitle } = draft
  const setContent = (value: string) => setDraft(v => ({ ...v, content: value }))
  const setTitle = (value: string) => setDraft(v => ({ ...v, title: value }))
  /** 上次自动填入的标题：标题栏为空或仍等于它时才允许自动覆盖，避免冲掉用户手动输入 */
  const setAutoTitle = (value: string) => setDraft(v => ({ ...v, autoTitle: value }))
  const [importing, setImporting] = useRememberedState('import:busy', false)
  const [result, setResult] = useRememberedState<ImportResult | null>('import:result', null)
  const [savedDraft, setSavedDraft] = useRememberedState<typeof draft | null>('import:saved-draft', null)
  const alreadySaved = !!result && savedDraft?.content === content && savedDraft?.title === title
  /** 导入失败轻提示（message box，替代页内错误文案） */
  const [toast, setToast] = useState<string | null>(null)

  const onContentChange = (text: string) => {
    setContent(text)
    const detected = frontMatterTitle(text)
    if (detected !== null && (title === '' || title === autoTitle)) {
      setTitle(detected)
      setAutoTitle(detected)
    }
  }

  const submit = async () => {
    const text = content.trim()
    if (!text || importing || alreadySaved) return
    setImporting(true)
    setToast(null)
    setResult(null)
    try {
      const saved = await importArticle(text, title.trim() || undefined)
      setResult(saved)
      setSavedDraft(draft)
      draftState.accept(draft, draft)
    } catch (e: unknown) {
      const loginUrl = loginUrlOf(e)
      if (loginUrl) {
        window.location.assign(loginUrl)
        return
      }
      setToast(`导入失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setImporting(false)
    }
  }

  return (
    <kit.Page
      title="文档导入"
      en="IMPORT"
      meta={<span className="text-sm text-muted">粘贴 Markdown → 保存为文章</span>}
    >
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      <DraftNotice dirty={draftState.dirty} onDiscard={draftState.discard} disabled={importing} />

      <div className="flex min-h-[calc(100dvh-18rem)] flex-col gap-3">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          aria-label="文章标题"
          placeholder="文章标题（粘贴 hexo / hugo 文档时自动识别 front-matter 的 title，可修改）"
          spellCheck={false}
          className={FIELD}
        />
        <textarea
          value={content}
          onChange={(e) => onContentChange(e.target.value)}
          aria-label="文章内容"
          placeholder="在此粘贴或输入文档内容…（支持 Markdown / 纯文本）"
          spellCheck={false}
          className={`scrollbar-slim min-h-[320px] flex-1 resize-none rounded-md border border-line bg-bg p-4 text-base leading-relaxed text-fg outline-none transition placeholder:text-hint hover:border-line-strong focus:border-accent focus:ring-2 focus:ring-accent/25`}
        />

        <kit.Toolbar
          left={`${content.length} 字`}
          right={
            <button
              type="button"
              onClick={() => void submit()}
              disabled={importing || !content.trim() || alreadySaved}
              className={`${btnPrimary('rounded-md')} disabled:cursor-not-allowed disabled:opacity-50`}
            >
              {importing ? '导入中 …' : alreadySaved ? '已导入' : '导入'}
            </button>
          }
        />

        {result ? (
          <kit.Panel title="已保存" meta={`${result.url}.md`}>
            <p className="ui-heading text-md">{result.title}</p>
            <p className="mono mt-1.5 text-2xs text-faint">data/article/{result.url}.md</p>
            <div className="mt-3 flex flex-wrap items-center gap-3">{alreadySaved && <button type="button" className={btnPrimary('rounded-md')} disabled={importing} onClick={() => {
              draftState.accept({ content: '', title: '', autoTitle: '' })
              setResult(null)
              setSavedDraft(null)
            }}>导入下一篇</button>}<a
              href={`#/article/${encodeURIComponent(result.url)}`}
              className="inline-block text-sm font-semibold text-accent hover:underline"
            >
              查看文章 →
            </a></div>
          </kit.Panel>
        ) : (
          <kit.Note>
            标题栏自动识别 front-matter 的 title；留空时取首个 # 标题行，否则取首行文本。
          </kit.Note>
        )}
      </div>
    </kit.Page>
  )
}
