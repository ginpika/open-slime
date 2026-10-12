import { useEffect, useRef, useState, type FormEvent } from 'react'
import {
  createModel,
  deleteModel,
  fetchModelContextWindow,
  getSettings,
  loginUrlOf,
  resetKnowledgeBase,
  saveDefaults,
  saveEmbedding,
  saveRerank,
  testEmbedding,
  testModelChat,
  testRerank,
  updateModel,
  type EmbeddingView,
  type ModelRequest,
  type ModelView,
  type RerankView,
  type SettingsView,
} from '../api/client'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { btnDanger, btnPrimary, btnQuiet, miniBtn } from '../components/ui'
import { ModeBtn, Select } from '../components/formKit'
import { usePageKit } from '../kit'

/** Embedding 表单草稿（可编辑态） */
type EmbDraft = {
  mode: 'llm' | 'custom'
  llmModelId: string
  baseUrl: string
  apiKey: string
  model: string
}

function draftFromEmbedding(e: EmbeddingView): EmbDraft {
  return {
    mode: e.mode,
    llmModelId: e.llmModelId ?? 'env',
    baseUrl: e.baseUrl ?? '',
    apiKey: e.apiKey ?? '',
    model: e.model ?? '',
  }
}

/** Rerank 表单草稿（可编辑态）；mode=off 表示关闭精排 */
type RnkDraft = {
  mode: 'llm' | 'custom' | 'off'
  llmModelId: string
  baseUrl: string
  apiKey: string
  model: string
}

function draftFromRerank(r: RerankView): RnkDraft {
  return {
    mode: r.mode,
    llmModelId: r.llmModelId ?? 'env',
    baseUrl: r.baseUrl ?? '',
    apiKey: r.apiKey ?? '',
    model: r.model ?? '',
  }
}

/** api-key 脱敏展示：仅保留头尾少量字符 */
function maskKey(k: string): string {
  if (!k) return '—'
  if (k.length <= 8) return '••••••'
  return `${k.slice(0, 3)}••••${k.slice(-4)}`
}

/** 上下文窗口标签：262144 → 256K（1024 进制）、200000 → 200K（1000 进制）、1048576 → 1M */
function ctxLabel(n: number | null | undefined): string | null {
  if (n == null || n <= 0) return null
  if (n < 1000) return String(n)
  const k = n / (n % 1024 === 0 ? 1024 : 1000)
  if (k >= 1000) return `${+(k / 1000).toFixed(1)}M`
  return `${+k.toFixed(k >= 100 ? 0 : 1)}K`
}

/** 添加 / 编辑模型弹窗（OpenAI Compatible API 端点） */
function ModelDialog({
  initial,
  busy,
  onClose,
  onSubmit,
}: {
  initial: ModelView | null
  busy: boolean
  onClose: () => void
  onSubmit: (req: ModelRequest) => void
}) {
  const kit = usePageKit()
  const [name, setName] = useState(initial?.name ?? '')
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState(initial?.apiKey ?? '')
  const [model, setModel] = useState(initial?.model ?? '')
  /* 上下文窗口（token 数）：留空则由后端从模型 API 获取，取不到用默认值 */
  const [contextWindow, setContextWindow] = useState(
    initial?.contextWindow != null ? String(initial.contextWindow) : '',
  )
  /* 编辑已有模型时自动从模型 API 试取上下文窗口并回填；用户已手动改过则不覆盖 */
  const [probing, setProbing] = useState(false)
  const ctxTouched = useRef(false)
  const valid = !!(name.trim() && baseUrl.trim() && apiKey.trim() && model.trim())

  useEffect(() => {
    if (!initial) return
    const { baseUrl: base, apiKey: key, model: mid } = initial
    if (!base.trim() || !key.trim() || !mid.trim()) return
    let alive = true
    setProbing(true)
    fetchModelContextWindow({ baseUrl: base, apiKey: key, model: mid })
      .then((r) => {
        if (alive && !ctxTouched.current && r.contextWindow != null && r.contextWindow > 0) {
          setContextWindow(String(r.contextWindow))
        }
      })
      .catch(() => {
        /* 取不到就保留原值 */
      })
      .finally(() => {
        if (alive) setProbing(false)
      })
    return () => {
      alive = false
    }
  }, [initial])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!valid || busy) return
    const win = Number(contextWindow.trim())
    onSubmit({
      name: name.trim(),
      baseUrl: baseUrl.trim(),
      apiKey: apiKey.trim(),
      model: model.trim(),
      contextWindow: contextWindow.trim() && Number.isFinite(win) && win > 0 ? win : null,
    })
  }

  return (
    <FloatWindow title={initial ? '编辑模型' : '添加模型'} onClose={onClose} width={480}>
      <form onSubmit={submit} className="space-y-3">
        <kit.Field label="名称" hint="用于在列表中区分，例如 GPT-4o / 本地 vLLM">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="GPT-4o"
            autoFocus
          />
        </kit.Field>
        <kit.Field label="Base URL" hint="OpenAI Compatible 接口根地址，通常以 /v1 结尾">
          <input
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            className="font-mono"
            placeholder="https://api.openai.com/v1"
            spellCheck={false}
          />
        </kit.Field>
        <kit.Field label="API Key">
          <input
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            className="font-mono"
            placeholder="sk-..."
            spellCheck={false}
            autoComplete="off"
          />
        </kit.Field>
        <kit.Field label="模型名" hint="请求体 model 字段，例如 gpt-4o / deepseek-chat">
          <input
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="font-mono"
            placeholder="gpt-4o"
            spellCheck={false}
          />
        </kit.Field>
        <kit.Field
          label="上下文窗口"
          hint={
            probing
              ? '正在从模型 API 获取上下文窗口 …'
              : 'token 数；留空则保存时自动从模型 API（/models）获取，取不到用默认值 262144'
          }
        >
          <input
            value={contextWindow}
            onChange={(e) => {
              ctxTouched.current = true
              setContextWindow(e.target.value.replace(/[^\d]/g, ''))
            }}
            className="font-mono"
            placeholder={probing ? '获取中 …' : '自动获取'}
            inputMode="numeric"
            spellCheck={false}
          />
        </kit.Field>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className={btnQuiet('rounded-md')} onClick={onClose}>
            取消
          </button>
          <button
            type="submit"
            className={btnPrimary('rounded-md')}
            disabled={!valid || busy}
          >
            {busy ? '保存中 …' : '保存'}
          </button>
        </div>
      </form>
    </FloatWindow>
  )
}

/** 重置知识库弹窗：须输入「确认」二字方可提交的二次确认 */
function ResetKnowledgeDialog({
  busy,
  onClose,
  onSubmit,
}: {
  busy: boolean
  onClose: () => void
  onSubmit: () => void
}) {
  const kit = usePageKit()
  const [text, setText] = useState('')
  const confirmed = text.trim() === '确认'

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!confirmed || busy) return
    onSubmit()
  }

  return (
    <FloatWindow title="重置知识库" onClose={onClose} width={440}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-base leading-relaxed text-fg">
          此操作将永久删除全部 chunk、知识图谱内的所有实体与关系，并<b>连带清除整个记忆子系统</b>
          （时序记忆、记忆条目、画像候选、共享、知识边与提炼水位），移除所有相关的 embedding，
          <span className="font-semibold text-danger">且不可恢复</span>。文档条目与模型配置不受影响。
        </p>
        <kit.Field label="输入「确认」二字以继续">
          <input
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="确认"
            spellCheck={false}
            autoComplete="off"
            autoFocus
          />
        </kit.Field>
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className={btnQuiet('rounded-md')} onClick={onClose}>
            取消
          </button>
          <button type="submit" className={btnDanger('rounded-md')} disabled={!confirmed || busy}>
            {busy ? '重置中 …' : '确认重置'}
          </button>
        </div>
      </form>
    </FloatWindow>
  )
}

export default function Settings() {
  const kit = usePageKit()
  const [data, setData] = useState<SettingsView | null>(null)
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)

  const [modelDlg, setModelDlg] = useState<{ open: boolean; editing: ModelView | null }>({
    open: false,
    editing: null,
  })
  /* 重置知识库确认弹窗 */
  const [resetOpen, setResetOpen] = useState(false)
  const [emb, setEmb] = useState<EmbDraft>({
    mode: 'llm',
    llmModelId: 'env',
    baseUrl: '',
    apiKey: '',
    model: '',
  })
  const [rnk, setRnk] = useState<RnkDraft>({
    mode: 'llm',
    llmModelId: 'env',
    baseUrl: '',
    apiKey: '',
    model: '',
  })
  /* 正在做连通性测试的模型 id（同一时刻只允许一个测试） */
  const [testingId, setTestingId] = useState<string | null>(null)
  /* 正在做连通性测试的 Embedding / Rerank 表单区块 */
  const [testingRag, setTestingRag] = useState<'emb' | 'rnk' | null>(null)
  /* 任一测试进行中（测试按钮互斥，避免同时打多条探针） */
  const testing = testingId !== null || testingRag !== null

  const fail = (e: unknown): void => {
    const loginUrl = loginUrlOf(e)
    if (loginUrl) {
      window.location.assign(loginUrl)
      return
    }
    setToast({ kind: 'error', msg: e instanceof Error ? e.message : String(e) })
  }

  useEffect(() => {
    let alive = true
    getSettings()
      .then((v) => {
        if (!alive) return
        setData(v)
        setEmb(draftFromEmbedding(v.embedding))
        setRnk(draftFromRerank(v.rerank))
        setLoadErr(null)
      })
      .catch((e: unknown) => {
        if (!alive) return
        const loginUrl = loginUrlOf(e)
        if (loginUrl) {
          window.location.assign(loginUrl)
          return
        }
        setLoadErr(e instanceof Error ? e.message : String(e))
      })
    return () => {
      alive = false
    }
  }, [])

  /** 统一提交：成功后以后端返回的最新视图覆盖本地，并提示结果 */
  const run = async (fn: () => Promise<SettingsView>, ok: string): Promise<void> => {
    setBusy(true)
    try {
      setData(await fn())
      setToast({ kind: 'success', msg: ok })
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const submitModel = async (req: ModelRequest) => {
    const editing = modelDlg.editing
    await run(
      () => (editing ? updateModel(editing.id, req) : createModel(req)),
      editing ? '模型已更新' : '模型已添加',
    )
    setModelDlg({ open: false, editing: null })
  }

  const removeModel = (m: ModelView) => {
    if (!window.confirm(`删除模型「${m.name}」？`)) return
    void run(() => deleteModel(m.id), '模型已删除')
  }

  /** 连通性测试：基于基准对话验证该模型接口能否正常返回（不改设置数据，独立于 busy） */
  const testModel = async (m: ModelView) => {
    setTestingId(m.id)
    try {
      const r = await testModelChat(m.id)
      const name = m.name || m.model || '.env'
      if (r.ok) {
        setToast({
          kind: 'success',
          msg: `「${name}」接口正常（${(r.elapsedMs / 1000).toFixed(1)}s）：${r.reply ?? ''}`,
        })
      } else {
        setToast({ kind: 'error', msg: `「${name}」接口异常：${r.error ?? '未知错误'}` })
      }
    } catch (e) {
      fail(e)
    } finally {
      setTestingId(null)
    }
  }

  /** Embedding 连通性测试：按表单当前草稿端点向量化一条探针文本（不改设置数据，独立于 busy） */
  const testEmb = async () => {
    setTestingRag('emb')
    try {
      const custom = emb.mode === 'custom'
      const r = await testEmbedding({
        mode: emb.mode,
        llmModelId: custom ? null : emb.llmModelId,
        baseUrl: custom ? emb.baseUrl.trim() : null,
        apiKey: custom ? emb.apiKey.trim() : null,
        model: emb.model.trim(),
      })
      if (r.ok) {
        setToast({
          kind: 'success',
          msg: `Embedding 接口正常（${(r.elapsedMs / 1000).toFixed(1)}s）：${r.detail ?? ''}`,
        })
      } else {
        setToast({ kind: 'error', msg: `Embedding 接口异常：${r.error ?? '未知错误'}` })
      }
    } catch (e) {
      fail(e)
    } finally {
      setTestingRag(null)
    }
  }

  /** Rerank 连通性测试：按表单当前草稿端点对探针文档打分（不改设置数据，独立于 busy） */
  const testRnk = async () => {
    setTestingRag('rnk')
    try {
      const custom = rnk.mode === 'custom'
      const r = await testRerank({
        mode: rnk.mode,
        llmModelId: custom ? null : rnk.llmModelId,
        baseUrl: custom ? rnk.baseUrl.trim() : null,
        apiKey: custom ? rnk.apiKey.trim() : null,
        model: rnk.model.trim(),
      })
      if (r.ok) {
        setToast({
          kind: 'success',
          msg: `Rerank 接口正常（${(r.elapsedMs / 1000).toFixed(1)}s）：${r.detail ?? ''}`,
        })
      } else {
        setToast({ kind: 'error', msg: `Rerank 接口异常：${r.error ?? '未知错误'}` })
      }
    } catch (e) {
      fail(e)
    } finally {
      setTestingRag(null)
    }
  }

  const setDefaultModel = (id: string) => {
    void run(() => saveDefaults({ modelId: id }), '已设为默认模型')
  }

  const saveEmb = async () => {
    setBusy(true)
    try {
      const custom = emb.mode === 'custom'
      const v = await saveEmbedding({
        mode: emb.mode,
        llmModelId: custom ? null : emb.llmModelId,
        baseUrl: custom ? emb.baseUrl.trim() : null,
        apiKey: custom ? emb.apiKey.trim() : null,
        model: emb.model.trim(),
      })
      setData(v)
      setEmb(draftFromEmbedding(v.embedding))
      setToast({ kind: 'success', msg: 'Embedding 配置已保存' })
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const embInvalid =
    !emb.model.trim() || (emb.mode === 'custom' && (!emb.baseUrl.trim() || !emb.apiKey.trim()))

  const saveRnk = async () => {
    setBusy(true)
    try {
      const custom = rnk.mode === 'custom'
      const v = await saveRerank({
        mode: rnk.mode,
        llmModelId: custom ? null : rnk.llmModelId,
        baseUrl: custom ? rnk.baseUrl.trim() : null,
        apiKey: custom ? rnk.apiKey.trim() : null,
        model: rnk.model.trim(),
      })
      setData(v)
      setRnk(draftFromRerank(v.rerank))
      setToast({
        kind: 'success',
        msg: rnk.mode === 'off' ? '已关闭 Rerank 精排' : 'Rerank 配置已保存',
      })
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const rnkInvalid =
    rnk.mode !== 'off' &&
    (!rnk.model.trim() || (rnk.mode === 'custom' && (!rnk.baseUrl.trim() || !rnk.apiKey.trim())))

  /** 重置知识库：清空全部 chunk、图谱实体与关系，并连带清除整个记忆子系统及相关 embedding（二次确认弹窗内提交） */
  const doReset = async () => {
    setBusy(true)
    try {
      const r = await resetKnowledgeBase()
      setToast({
        kind: 'success',
        msg:
          `知识库已重置：删除 ${r.chunks} 个 chunk、${r.entities} 个实体、${r.relations} 条关系、` +
          `${r.memories} 条记忆（时序 ${r.memoryEvents} + 条目 ${r.memoryItems}）、` +
          `${r.candidates} 条画像候选、${r.shares} 条共享`,
      })
      setResetOpen(false)
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <kit.Page title="设置" en="SETTINGS">
      <Toast
        kind={toast?.kind}
        message={toast?.msg ?? null}
        onClose={() => setToast(null)}
      />

      <div className="flex max-w-3xl flex-col gap-6">
        {loadErr && (
          <kit.Note tone="warn">
            <span className="font-semibold">设置加载失败</span>
            <span className="mx-1.5">·</span>
            {loadErr}
          </kit.Note>
        )}

        {data && (
          <>
            {/* ---------- 大模型 ---------- */}
            <kit.Panel title="大模型">
              <kit.Toolbar
                left={`${data.models.length} 个端点`}
                right={
                  <button
                    type="button"
                    className={btnPrimary()}
                    disabled={busy}
                    onClick={() => setModelDlg({ open: true, editing: null })}
                  >
                    添加模型
                  </button>
                }
              />
              <p className="mt-3 text-sm leading-relaxed text-muted">
                OpenAI Compatible API 类型的模型端点，可配置多个；.env 传入的作为内置项不可修改。
              </p>
              <div className="mt-2 flex flex-col">
                {data.models.map((m) => (
                  <kit.Row key={m.id} className="flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-base font-semibold text-fg">
                          {m.name || m.model || '.env'}
                        </span>
                        {m.builtin && <kit.Badge>内置</kit.Badge>}
                        {ctxLabel(m.contextWindow) && (
                          <kit.Badge>上下文 {ctxLabel(m.contextWindow)}</kit.Badge>
                        )}
                        {data.defaultModelId === m.id && <kit.Badge tone="accent">默认</kit.Badge>}
                      </div>
                      <p className="mt-1 break-all font-mono text-xs text-faint">
                        {m.baseUrl || '（未配置 base-url）'}
                      </p>
                      <p className="mt-0.5 font-mono text-xs text-hint">key {maskKey(m.apiKey)}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1.5">
                      <button
                        type="button"
                        className={miniBtn}
                        disabled={busy || testing}
                        onClick={() => void testModel(m)}
                      >
                        {testingId === m.id ? '测试中 …' : '测试'}
                      </button>
                      {data.defaultModelId !== m.id && (
                        <button
                          type="button"
                          className={miniBtn}
                          disabled={busy}
                          onClick={() => setDefaultModel(m.id)}
                        >
                          设为默认
                        </button>
                      )}
                      {!m.builtin && (
                        <>
                          <button
                            type="button"
                            className={miniBtn}
                            disabled={busy}
                            onClick={() => setModelDlg({ open: true, editing: m })}
                          >
                            编辑
                          </button>
                          <button
                            type="button"
                            className={miniBtn}
                            disabled={busy}
                            onClick={() => removeModel(m)}
                          >
                            删除
                          </button>
                        </>
                      )}
                    </div>
                  </kit.Row>
                ))}
              </div>
            </kit.Panel>

            {/* ---------- Embedding（与模型配置分开） ---------- */}
            <kit.Panel title="Embedding">
              <p className="text-sm leading-relaxed text-muted">
                向量化端点独立于大模型配置：可复用某个已配置模型的端点，或单独填写。
              </p>
              <div className="mt-4 flex flex-wrap gap-2">
                <ModeBtn
                  active={emb.mode === 'llm'}
                  title="复用大模型端点"
                  desc="使用所选模型的 base-url 与 api-key"
                  onClick={() => setEmb((v) => ({ ...v, mode: 'llm' }))}
                />
                <ModeBtn
                  active={emb.mode === 'custom'}
                  title="独立端点"
                  desc="为 Embedding 单独填写 base-url 与 api-key"
                  onClick={() => setEmb((v) => ({ ...v, mode: 'custom' }))}
                />
              </div>

              <div className="mt-4 space-y-3">
                {emb.mode === 'llm' ? (
                  <kit.Field label="复用模型端点" wrap={false}>
                    <Select
                      value={emb.llmModelId}
                      onChange={(v) => setEmb((prev) => ({ ...prev, llmModelId: v }))}
                      options={data.models.map((m) => ({
                        value: m.id,
                        label: m.name || m.model || '.env',
                      }))}
                    />
                  </kit.Field>
                ) : (
                  <>
                    <kit.Field label="Base URL">
                      <input
                        value={emb.baseUrl}
                        onChange={(e) => setEmb((v) => ({ ...v, baseUrl: e.target.value }))}
                        className="font-mono"
                        placeholder="https://api.openai.com/v1"
                        spellCheck={false}
                      />
                    </kit.Field>
                    <kit.Field label="API Key">
                      <input
                        value={emb.apiKey}
                        onChange={(e) => setEmb((v) => ({ ...v, apiKey: e.target.value }))}
                        className="font-mono"
                        placeholder="sk-..."
                        spellCheck={false}
                        autoComplete="off"
                      />
                    </kit.Field>
                  </>
                )}
                <kit.Field label="Embedding 模型名" hint="例如 text-embedding-3-small / bge-m3">
                  <input
                    value={emb.model}
                    onChange={(e) => setEmb((v) => ({ ...v, model: e.target.value }))}
                    className="font-mono"
                    placeholder="text-embedding-3-small"
                    spellCheck={false}
                  />
                </kit.Field>
              </div>

              <div className="mt-4 flex justify-end gap-2">
                <button
                  type="button"
                  className={btnQuiet('rounded-md')}
                  disabled={busy || embInvalid || testing}
                  onClick={() => void testEmb()}
                >
                  {testingRag === 'emb' ? '测试中 …' : '测试'}
                </button>
                <button
                  type="button"
                  className={btnPrimary('rounded-md')}
                  disabled={busy || embInvalid}
                  onClick={() => void saveEmb()}
                >
                  {busy ? '保存中 …' : '保存'}
                </button>
              </div>
            </kit.Panel>

            {/* ---------- Rerank（检索精排，可整体关闭） ---------- */}
            <kit.Panel title="Rerank">
              <p className="text-sm leading-relaxed text-muted">
                检索召回后对「查询 + 文档」重新打分排序（cross-encoder 精排），端点独立于大模型配置。
              </p>
              <div className="mt-4 flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-base font-semibold text-fg">启用精排</p>
                  <p className="mt-1 text-xs leading-relaxed text-faint">
                    关闭后检索结果保持 RRF 融合原序，不再调用精排端点。
                  </p>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={rnk.mode !== 'off'}
                  title="开启 / 关闭 Rerank 精排"
                  onClick={() =>
                    setRnk((v) => ({ ...v, mode: v.mode === 'off' ? 'llm' : 'off' }))
                  }
                  className="mt-0.5 shrink-0 cursor-pointer"
                >
                  <span
                    className={`relative block h-[18px] w-8 rounded-full transition-colors ${
                      rnk.mode !== 'off' ? 'bg-accent' : 'bg-fg/20'
                    }`}
                  >
                    <span
                      className={`absolute top-0.5 h-3.5 w-3.5 rounded-full shadow-sm transition-all ${
                        rnk.mode !== 'off' ? 'left-4 bg-accent-fg' : 'left-0.5 bg-bg'
                      }`}
                    />
                  </span>
                </button>
              </div>

              {rnk.mode !== 'off' && (
                <>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <ModeBtn
                      active={rnk.mode === 'llm'}
                      title="复用大模型端点"
                      desc="使用所选模型的 base-url 与 api-key"
                      onClick={() => setRnk((v) => ({ ...v, mode: 'llm' }))}
                    />
                    <ModeBtn
                      active={rnk.mode === 'custom'}
                      title="独立端点"
                      desc="为 Rerank 单独填写 base-url 与 api-key"
                      onClick={() => setRnk((v) => ({ ...v, mode: 'custom' }))}
                    />
                  </div>

                  <div className="mt-4 space-y-3">
                    {rnk.mode === 'llm' ? (
                      <kit.Field label="复用模型端点" wrap={false}>
                        <Select
                          value={rnk.llmModelId}
                          onChange={(v) => setRnk((prev) => ({ ...prev, llmModelId: v }))}
                          options={data.models.map((m) => ({
                            value: m.id,
                            label: m.name || m.model || '.env',
                          }))}
                        />
                      </kit.Field>
                    ) : (
                      <>
                        <kit.Field label="Base URL">
                          <input
                            value={rnk.baseUrl}
                            onChange={(e) =>
                              setRnk((v) => ({ ...v, baseUrl: e.target.value }))
                            }
                            className="font-mono"
                            placeholder="https://api.openai.com/v1"
                            spellCheck={false}
                          />
                        </kit.Field>
                        <kit.Field label="API Key">
                          <input
                            value={rnk.apiKey}
                            onChange={(e) => setRnk((v) => ({ ...v, apiKey: e.target.value }))}
                            className="font-mono"
                            placeholder="sk-..."
                            spellCheck={false}
                            autoComplete="off"
                          />
                        </kit.Field>
                      </>
                    )}
                    <kit.Field label="Rerank 模型名" hint="例如 bge-reranker-v2-m3 / rerank">
                      <input
                        value={rnk.model}
                        onChange={(e) => setRnk((v) => ({ ...v, model: e.target.value }))}
                        className="font-mono"
                        placeholder="bge-reranker-v2-m3"
                        spellCheck={false}
                      />
                    </kit.Field>
                  </div>
                </>
              )}

              <div className="mt-4 flex justify-end gap-2">
                <button
                  type="button"
                  className={btnQuiet('rounded-md')}
                  disabled={busy || rnkInvalid || testing}
                  onClick={() => void testRnk()}
                >
                  {testingRag === 'rnk' ? '测试中 …' : '测试'}
                </button>
                <button
                  type="button"
                  className={btnPrimary('rounded-md')}
                  disabled={busy || rnkInvalid}
                  onClick={() => void saveRnk()}
                >
                  {busy ? '保存中 …' : '保存'}
                </button>
              </div>
            </kit.Panel>

            {/* ---------- 危险操作（Danger Zone） ---------- */}
            <kit.Panel title="危险操作">
              <p className="text-sm leading-relaxed text-muted">以下操作不可撤销，请谨慎执行。</p>
              <div className="mt-3 rounded-md border border-danger/40 bg-danger/5 p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-base font-semibold text-fg">重置知识库</p>
                    <p className="mt-1 text-xs leading-relaxed text-faint">
                      删除全部 chunk、知识图谱内的实体与关系，并<b>连带清除整个记忆子系统</b>
                      （含时序记忆、记忆条目、画像候选与共享），移除所有相关的 embedding。
                    </p>
                  </div>
                  <button
                    type="button"
                    className={btnDanger('rounded-md')}
                    disabled={busy}
                    onClick={() => setResetOpen(true)}
                  >
                    重置知识库
                  </button>
                </div>
              </div>
            </kit.Panel>
          </>
        )}
      </div>

      {modelDlg.open && (
        <ModelDialog
          initial={modelDlg.editing}
          busy={busy}
          onClose={() => setModelDlg({ open: false, editing: null })}
          onSubmit={(req) => void submitModel(req)}
        />
      )}

      {resetOpen && (
        <ResetKnowledgeDialog
          busy={busy}
          onClose={() => setResetOpen(false)}
          onSubmit={() => void doReset()}
        />
      )}
    </kit.Page>
  )
}
