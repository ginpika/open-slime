/**
 * 文档导入页：粘贴文本 → 直接作为 markdown 文章保存（data/article/{url}.md）
 *
 * 呈现为知识库子页：输入区与结果卡沿用 kn-* 构件（细描边、小圆角、系统字体），
 * 与 /dashboard、/knowledge 一致。
 */

import { useState } from 'react'
import { importArticle, loginUrlOf, type ImportResult } from '../api/client'
import Toast from '../components/Toast'
import { DraftNotice } from '../components/DraftNotice'
import { useDraftState, useRememberedState } from '../components/windowState'
import './knowledge.css'

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

export default function Import() {
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
    <div className="kn-page kn-page--reading">
      <Toast kind="error" message={toast} onClose={() => setToast(null)} />
      <DraftNotice dirty={draftState.dirty} onDiscard={draftState.discard} disabled={importing} />

      <div className="kn-stack kn-import">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          aria-label="文章标题"
          placeholder="文章标题（粘贴 hexo / hugo 文档时自动识别 front-matter 的 title，可修改）"
          spellCheck={false}
          className="kn-input"
        />
        <textarea
          value={content}
          onChange={(e) => onContentChange(e.target.value)}
          aria-label="文章内容"
          placeholder="在此粘贴或输入文档内容…（支持 Markdown / 纯文本）"
          spellCheck={false}
          className="kn-input kn-textarea scrollbar-slim"
        />

        <div className="kn-toolbar">
          <span className="kn-toolbar__left">{content.length} 字</span>
          <div className="kn-toolbar__right">
            <button
              type="button"
              onClick={() => void submit()}
              disabled={importing || !content.trim() || alreadySaved}
              className="kn-btn kn-btn--primary"
            >
              {importing ? '导入中 …' : alreadySaved ? '已导入' : '导入'}
            </button>
          </div>
        </div>

        {result ? (
          <section className="kn-card">
            <div className="kn-card__head">
              <h2 className="kn-card__title">已保存</h2>
              <span className="kn-head__meta">{result.url}.md</span>
            </div>
            <div className="kn-card__body">
              <p className="kn-row__title">{result.title}</p>
              <p className="kn-detail__hash" style={{ marginTop: '.375rem' }}>data/article/{result.url}.md</p>
              <div className="kn-detail__actions" style={{ marginTop: '.875rem' }}>
                {alreadySaved && (
                  <button type="button" className="kn-btn" disabled={importing} onClick={() => {
                    draftState.accept({ content: '', title: '', autoTitle: '' })
                    setResult(null)
                    setSavedDraft(null)
                  }}>导入下一篇</button>
                )}
                <a href={`#/article/${encodeURIComponent(result.url)}`} className="kn-btn kn-btn--primary">
                  查看文章 →
                </a>
              </div>
            </div>
          </section>
        ) : (
          <p className="kn-note">
            标题栏自动识别 front-matter 的 title；留空时取首个 # 标题行，否则取首行文本。
          </p>
        )}
      </div>
    </div>
  )
}
