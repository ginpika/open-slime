/**
 * 工作空间页：以 docker 容器 / SSH 服务器为 agent 的工作空间——在此维护环境（增删改、连通性测试、
 * 逐目标启用），并浏览其中的文件（列目录、文本预览、下载）。
 *
 * <p>「工作空间」是命令行终端（{@code ai.terminal}）之上的扩展：环境定义与凭证仍由终端模块的接口
 * 维护（{@code /api/terminal}），本页额外接上工作空间的文件面（{@code /api/workspaces/*}）。
 * 因此这里不再重复一套环境配置，只是把终端能力组织成一个面向「工作空间」的页面。
 *
 * <p>视觉交给 PageKit 与主题原语：标题区用 kit.Page，面板用 Card（描边环 + 面板底），
 * 按钮用 ui-btn 变体（primary / outline / quiet / icon），标签用 kit.Badge，层次随主题令牌自适应。
 */
import { AppIcon } from '../components/AppIcon'
import { useCallback, useEffect, useState } from 'react'
import WorkspaceDialog from './WorkspaceDialog'
import WorkspaceShell from './WorkspaceShell'
import {
  ApiError,
  createTerminalTarget,
  deleteTerminalTarget,
  downloadWorkspaceFile,
  getTerminal,
  listTerminalCommands,
  listWorkspaceFiles,
  loginUrlOf,
  probeTerminalTarget,
  readWorkspaceFile,
  updateTerminalTarget,
  type TerminalCommand,
  type TerminalTarget,
  type TerminalTargetRequest,
  type TerminalView,
  type WorkspaceDirView,
  type WorkspaceFileView,
} from '../api/client'
import { Card, CardHead } from '../components/Card'
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { btnIcon, btnOutline, btnPrimary, btnQuiet, miniBtn } from '../components/ui'
import { usePageKit } from '../kit'

/* ---------------- 小工具 ---------------- */

/** 后排错误 → 可读文案（Spring 错误体是 JSON，取出 message 后展示） */
function errMsg(e: unknown): string {
  if (e instanceof ApiError) {
    const raw = e.body?.trim()
    if (raw) {
      try {
        const j = JSON.parse(raw) as { message?: string }
        if (j.message) return j.message
      } catch {
        /* 非 JSON：原样展示 */
      }
      return raw
    }
    return `请求失败（${e.status}）`
  }
  return e instanceof Error ? e.message : String(e)
}

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  const kb = n / 1024
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1) : Math.round(kb)} KB`
  const mb = kb / 1024
  return `${mb < 10 ? mb.toFixed(1) : Math.round(mb)} MB`
}

/** 一行目标的位置摘要（与后端 describe 同义，便于一眼认出这台环境） */
function targetWhere(t: TerminalTarget): string {
  if (t.kind === 'docker') {
    return `${t.container ? `容器 ${t.container}` : `镜像 ${t.image}`}${t.dockerHost ? ` @ ${t.dockerHost}` : ''}`
  }
  return `${t.sshUser || 'root'}@${t.sshHost}:${t.sshPort ?? 22}`
}

/* ---------------- 图标 ---------------- */

function IconFolder() {
  return (
    <AppIcon name="folder" className="h-4 w-4" />
  )
}

function IconFile() {
  return (
    <AppIcon name="file" className="h-4 w-4" />
  )
}

function IconLink() {
  return (
    <AppIcon name="link" className="h-4 w-4" />
  )
}

function IconDownload() {
  return (
    <AppIcon name="download" className="h-4 w-4" />
  )
}

function IconUp() {
  return (
    <AppIcon name="arrow-up" className="h-4 w-4" />
  )
}

function IconRefresh() {
  return (
    <AppIcon name="refresh" className="h-4 w-4" />
  )
}

function IconChevron() {
  return (
    <AppIcon name="chevron-right" className="h-3.5 w-3.5" />
  )
}

/* ---------------- 新建 / 编辑工作空间 ---------------- */

/* ---------------- 文件浏览 ---------------- */

/**
 * 文件浏览：面包屑（工作空间名 → 各级目录）+ 目录项列表 + 文本预览 / 下载。
 *
 * <p>路径状态用「段数组」而不是拼串：既天然把浏览限制在工作空间起点之下（不会出现 {@code ..}），
 * 也让面包屑与后端沙箱路径一一对应。切换工作空间时由外层用 key 重挂载本组件来复位。
 */
function FileBrowser({
  workspace,
  onToast,
}: {
  workspace: TerminalTarget
  onToast: (t: { kind: 'success' | 'error'; msg: string }) => void
}) {
  const kit = usePageKit()
  const [segments, setSegments] = useState<string[]>([])
  const [dir, setDir] = useState<WorkspaceDirView | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [preview, setPreview] = useState<WorkspaceFileView | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewErr, setPreviewErr] = useState<string | null>(null)
  const [downloading, setDownloading] = useState<string | null>(null)

  const path = segments.length ? segments.join('/') : '.'
  const entryPath = useCallback((name: string) => [...segments, name].join('/'), [segments])

  const load = useCallback(async () => {
    setLoading(true)
    setErr(null)
    try {
      setDir(await listWorkspaceFiles(workspace.id, path))
    } catch (e) {
      setDir(null)
      setErr(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [workspace.id, path])

  useEffect(() => {
    void load()
  }, [load])

  /* 换目录即收起预览：上一层的文件内容不该留在这一层 */
  useEffect(() => {
    setPreview(null)
    setPreviewErr(null)
  }, [path])

  const openFile = async (name: string) => {
    setPreviewLoading(true)
    setPreviewErr(null)
    setPreview(null)
    try {
      setPreview(await readWorkspaceFile(workspace.id, entryPath(name)))
    } catch (e) {
      setPreviewErr(errMsg(e))
    } finally {
      setPreviewLoading(false)
    }
  }

  const download = async (name: string) => {
    const p = entryPath(name)
    setDownloading(p)
    try {
      const { filename, blob } = await downloadWorkspaceFile(workspace.id, p)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      onToast({ kind: 'error', msg: `下载失败：${errMsg(e)}` })
    } finally {
      setDownloading(null)
    }
  }

  return (
    <Card pad="lg" className="flex min-h-0 flex-col">
      {/* 面包屑 + 刷新 */}
      <div className="flex shrink-0 items-center gap-2">
        <button
          type="button"
          title="上一层"
          disabled={segments.length === 0}
          onClick={() => setSegments((s) => s.slice(0, -1))}
          className={btnIcon('rounded-sm')}
        >
          <IconUp />
        </button>
        <div className="scrollbar-hide flex min-w-0 flex-1 items-center gap-1 overflow-x-auto text-xs">
          <button
            type="button"
            onClick={() => setSegments([])}
            className={`shrink-0 px-2 py-1 font-medium transition ${
              segments.length === 0
                ? 'bg-accent-soft text-fg'
                : 'text-muted hover:bg-fg/5 hover:text-fg'
            }`}
          >
            {workspace.name}
          </button>
          {segments.map((seg, i) => (
            <span key={`${seg}-${i}`} className="flex shrink-0 items-center gap-1">
              <span className="text-faint">
                <IconChevron />
              </span>
              <button
                type="button"
                onClick={() => setSegments((s) => s.slice(0, i + 1))}
                className={`mono px-2 py-1 transition ${
                  i === segments.length - 1
                    ? 'bg-accent-soft text-fg'
                    : 'text-muted hover:bg-fg/5 hover:text-fg'
                }`}
              >
                {seg}
              </button>
            </span>
          ))}
        </div>
        <button
          type="button"
          title="刷新"
          onClick={() => void load()}
          className={btnIcon('rounded-sm')}
        >
          <IconRefresh />
        </button>
        <a className="ui-btn ui-btn--quiet text-xs" href={`#/coding?workspace=${encodeURIComponent(workspace.id)}&directory=${encodeURIComponent(path)}`}>编程 ↗</a>
      </div>

      {/* 目录项 */}
      <div className="mt-2 min-h-0 flex-1 overflow-y-auto scrollbar-slim">
        {loading && <p className="px-3 py-6 text-xs text-muted">加载中 …</p>}
        {!loading && err && (
          <p className="rounded-md border border-line bg-fg/[0.03] px-3 py-4 text-xs leading-relaxed text-muted">
            {err}
          </p>
        )}
        {!loading && !err && dir?.entries.length === 0 && (
          <p className="px-3 py-6 text-xs text-muted">（空目录）</p>
        )}
        {!loading &&
          !err &&
          dir?.entries.map((e) => {
            const p = entryPath(e.name)
            return (
              <kit.Row key={e.name} className="gap-3 py-2">
                <span className="shrink-0 text-muted">
                  {e.dir ? <IconFolder /> : e.link ? <IconLink /> : <IconFile />}
                </span>
                <button
                  type="button"
                  onClick={() => (e.dir ? setSegments((s) => [...s, e.name]) : void openFile(e.name))}
                  title={e.name}
                  className="min-w-0 flex-1 truncate text-left text-base text-fg transition hover:underline"
                >
                  {e.name}
                </button>
                <span className="mono hidden w-32 shrink-0 truncate text-right text-2xs text-dim sm:block">
                  {e.modified}
                </span>
                <span className="mono w-20 shrink-0 text-right text-2xs text-dim">
                  {e.dir ? '—' : fmtSize(e.size)}
                </span>
                <button
                  type="button"
                  title="下载"
                  disabled={downloading === p}
                  onClick={() => void download(e.name)}
                  className={btnIcon('rounded-sm')}
                >
                  <IconDownload />
                </button>
              </kit.Row>
            )
          })}
      </div>

      {/* 预览 */}
      {(previewLoading || preview || previewErr) && (
        <div className="mt-3 shrink-0 rounded-md border border-line">
          <div className="flex items-center gap-2 px-3.5 py-2">
            <span className="mono min-w-0 flex-1 truncate text-xs font-bold text-fg">
              {previewLoading ? '加载中 …' : preview?.name ?? '预览'}
            </span>
            {preview && <kit.Badge>{fmtSize(preview.size)}</kit.Badge>}
            {preview?.truncated && <kit.Badge tone="warn">已截断</kit.Badge>}
            {preview?.binary && <kit.Badge>二进制</kit.Badge>}
            {preview && (
              <button
                type="button"
                className={miniBtn}
                disabled={downloading === preview.path}
                onClick={() => void download(preview.name)}
              >
                下载
              </button>
            )}
            <button
              type="button"
              title="关闭预览"
              onClick={() => {
                setPreview(null)
                setPreviewErr(null)
              }}
              className={btnIcon('rounded-sm')}
            >
              <AppIcon name="close" className="h-3.5 w-3.5" />
            </button>
          </div>
          <div className="border-t border-line px-3.5 py-3">
            {previewLoading && (
              <p className="text-xs text-muted">读取中 …</p>
            )}
            {!previewLoading && previewErr && (
              <p className="text-xs leading-relaxed text-muted">{previewErr}</p>
            )}
            {!previewLoading && preview?.binary && (
              <p className="text-xs leading-relaxed text-muted">
                二进制文件（{fmtSize(preview.size)}），不支持文本预览，可下载后查看。
              </p>
            )}
            {!previewLoading && preview && !preview.binary && (
              <pre className="scrollbar-slim max-h-[360px] overflow-auto whitespace-pre-wrap break-words rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-xs leading-5 text-muted">
                {preview.content?.length ? preview.content : '（空文件）'}
              </pre>
            )}
          </div>
        </div>
      )}
    </Card>
  )
}

/* ---------------- 页面 ---------------- */

export default function Workspace() {
  const kit = usePageKit()
  const [view, setView] = useState<TerminalView | null>(null)
  const [commands, setCommands] = useState<TerminalCommand[]>([])
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)
  const [dlg, setDlg] = useState<{ editing: TerminalTarget | null } | null>(null)
  const [probing, setProbing] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState(() => new URLSearchParams(window.location.hash.split('?')[1] || '').get('target') || '')

  const [shell, setShell] = useState<{ target: string } | null>(null)
  const [cmdDlg, setCmdDlg] = useState(false)

  const fail = useCallback((e: unknown): void => {
    const loginUrl = loginUrlOf(e)
    if (loginUrl) {
      window.location.assign(loginUrl)
      return
    }
    setToast({ kind: 'error', msg: errMsg(e) })
  }, [])

  const refreshCommands = useCallback(() => {
    listTerminalCommands(20)
      .then(setCommands)
      .catch(() => {})
  }, [])

  useEffect(() => {
    let alive = true
    getTerminal()
      .then((v) => {
        if (alive) setView(v)
      })
      .catch((e: unknown) => {
        if (alive) setLoadErr(errMsg(e))
      })
    refreshCommands()
    return () => {
      alive = false
    }
  }, [refreshCommands])

  const targets = view?.targets ?? []

  /* 选中的工作空间：只有显式点选才激活（选中的被删掉即回到未选中）——渲染期直接推导，
     避免用「同步 state 的 effect」再触发一轮渲染 */
  const selected = targets.find((t) => t.id === selectedId) ?? null

  /** 统一提交：成功后以后端返回的最新视图覆盖本地，并提示结果 */
  const runView = async (fn: () => Promise<TerminalView>, ok: string): Promise<boolean> => {
    setBusy(true)
    try {
      setView(await fn())
      setToast({ kind: 'success', msg: ok })
      return true
    } catch (e) {
      fail(e)
      return false
    } finally {
      setBusy(false)
    }
  }

  const submitTarget = async (req: TerminalTargetRequest) => {
    const editing = dlg?.editing ?? null
    const saved = await runView(
      () => (editing ? updateTerminalTarget(editing.id, req) : createTerminalTarget(req)),
      editing ? '工作空间已更新' : '工作空间已创建',
    )
    if (saved) setDlg(null)
  }

  const removeTarget = (t: TerminalTarget) => {
    if (!window.confirm(`删除工作空间「${t.name}」？`)) return
    void runView(() => deleteTerminalTarget(t.id), '工作空间已删除')
  }

  const probeTarget = async (t: TerminalTarget) => {
    setProbing(t.id)
    try {
      const r = await probeTerminalTarget(t.id)
      if (r.ok) {
        setToast({
          kind: 'success',
          msg: `「${t.name}」连接正常（${(r.elapsedMs / 1000).toFixed(1)}s）：${r.detail ?? ''}`,
        })
      } else {
        setToast({ kind: 'error', msg: `「${t.name}」连接失败：${r.error ?? '未知错误'}` })
      }
    } catch (e) {
      fail(e)
    } finally {
      setProbing(null)
    }
  }

  return (
    /* 满幅页：lg 档把 kit.Page 的 section 拉伸到视口高（App 路由已给 h-full），
       两栏列表与文件浏览器各自内部滚动 */
    <div className="flex min-h-0 flex-col lg:h-full lg:[&>section]:min-h-0 lg:[&>section]:flex-1">
      <kit.Page
        title="工作空间"
        en="WORKSPACES"
        meta={
          <button
            type="button"
            className={btnPrimary('rounded-sm')}
            disabled={busy}
            onClick={() => setDlg({ editing: null })}
          >
            新建工作空间
          </button>
        }
      >
        {/* 页头动作条：左侧定位说明，右侧收进浮窗的 Shell 与执行记录（把版面让给文件浏览器） */}
        <kit.Toolbar
          left={
            <p className="max-w-2xl leading-relaxed">
              以 docker 容器或 SSH 服务器为 agent 的工作空间：模型通过命令行直接在其中作业，
              你也可以在这里浏览、预览与下载其中的文件。没有全局总开关——启用哪个工作空间，
              模型就能在哪里执行命令（run_command / list_terminals）。
            </p>
          }
          right={
            <>
              <button type="button" className={btnQuiet('rounded-sm')} onClick={() => setShell({ target: selected?.id || targets.find(t => t.enabled)?.id || '' })}>
                <AppIcon name="terminal" />Shell
              </button>
              <button
                type="button"
                className={btnOutline('rounded-sm')}
                onClick={() => {
                  refreshCommands()
                  setCmdDlg(true)
                }}
              >
                最近执行
              </button>
            </>
          }
        />

        {loadErr && <kit.Note tone="warn">加载失败：{loadErr}</kit.Note>}

        {/* 工作空间列表 + 文件编辑器：占据页头之下的全部剩余高度 */}
        <div className="grid min-h-0 gap-5 lg:flex-1 lg:grid-cols-[320px_1fr]">
          <Card pad="none" className="flex min-h-0 flex-col">
            <CardHead
              title="全部工作空间"
              meta={
                <span className="mono text-2xs text-dim">
                  {targets.length > 0 ? `${targets.length} 台` : '—'}
                </span>
              }
            />
            <div className="scrollbar-slim flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-3">
              {targets.length === 0 && (
                <kit.Empty>
                  还没有工作空间。新建一个 docker 容器或 SSH 服务器后，模型才知道能在哪里执行命令。
                </kit.Empty>
              )}
              {targets.map((t) => {
                const active = t.id === selected?.id
                return (
                  <div
                    key={t.id}
                    onClick={() => setSelectedId(t.id)}
                    className={`cursor-pointer rounded-md border p-3.5 transition ${
                      active
                        ? 'border-accent bg-accent-soft'
                        : 'border-line hover:border-line-strong hover:bg-fg/[0.03]'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() => setSelectedId(t.id)}
                      className="block w-full text-left"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-base font-semibold text-fg">{t.name}</span>
                        <kit.Badge>{t.kind}</kit.Badge>
                        {t.enabled ? (
                          <kit.Badge tone="ok">已启用</kit.Badge>
                        ) : (
                          <kit.Badge tone="warn">未启用</kit.Badge>
                        )}
                      </div>
                      <p className="mono mt-1 break-all text-2xs text-dim">{targetWhere(t)}</p>
                      {t.note && <p className="mt-0.5 text-2xs text-faint">{t.note}</p>}
                    </button>
                    {/* 动作行独立于卡片点击：只响应各自按钮，不把点击冒泡成「选中」 */}
                    <div className="mt-2 flex cursor-default items-center gap-1" onClick={(e) => e.stopPropagation()}>
                      <button
                        type="button"
                        className={miniBtn}
                        disabled={busy || probing !== null}
                        onClick={() => void probeTarget(t)}
                      >
                        {probing === t.id ? '测试中 …' : '测试'}
                      </button>
                      <button type="button" className={miniBtn} disabled={busy} onClick={() => setDlg({ editing: t })}>
                        编辑
                      </button>
                      <button
                        type="button"
                        className={miniBtn}
                        disabled={busy || !t.enabled}
                        onClick={() => {
                          setSelectedId(t.id)
                          setShell({ target: t.id })
                        }}
                      >
                        Shell
                      </button>
                      <button type="button" className={miniBtn} disabled={busy} onClick={() => removeTarget(t)}>
                        删除
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </Card>

          {selected ? (
            <FileBrowser
              key={selected.id}
              workspace={selected}
              onToast={(t) => setToast(t)}
            />
          ) : (
            <div className="flex min-h-[320px] items-center justify-center">
              <kit.Empty>选择左侧的工作空间，即可浏览其中的文件。</kit.Empty>
            </div>
          )}
        </div>

        {shell && <WorkspaceShell targets={targets} initialTarget={shell.target} onClose={() => setShell(null)} />}

        {/* 最近执行：页头按钮唤起的浮窗 */}
        {cmdDlg && (
          <FloatWindow title="最近执行" onClose={() => setCmdDlg(false)} width={560}>
            <div className="flex items-center justify-between gap-2">
              <p className="text-xs leading-relaxed text-hint">
                最近 20 条单次命令记录；交互式 Shell 命令不在此列表中。
              </p>
              <button type="button" className={`${miniBtn} shrink-0`} onClick={refreshCommands}>
                刷新
              </button>
            </div>
            <div className="mt-3 flex flex-col gap-1.5">
              {commands.length === 0 && <kit.Empty>还没有执行记录。</kit.Empty>}
              {commands.map((c) => (
                <div
                  key={c.id}
                  className="flex select-text items-start justify-between gap-3 rounded-md border border-line px-3 py-2.5"
                >
                  <div className="min-w-0 flex-1">
                    <p className="mono truncate text-xs text-fg">{c.command}</p>
                    <p className="mono mt-0.5 text-2xs text-dim">
                      {c.targetName} · {c.source} · {new Date(c.createdAt).toLocaleString()}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-1.5">
                    {c.timedOut && <kit.Badge tone="warn">超时</kit.Badge>}
                    {c.truncated && <kit.Badge tone="warn">截断</kit.Badge>}
                    <kit.Badge tone={c.ok ? 'ok' : 'danger'}>
                      {c.exitCode == null ? 'exit ?' : `exit ${c.exitCode}`}
                    </kit.Badge>
                  </div>
                </div>
              ))}
            </div>
          </FloatWindow>
        )}

        {dlg && (
          <WorkspaceDialog
            initial={dlg.editing}
            busy={busy}
            onClose={() => setDlg(null)}
            onSubmit={(req) => void submitTarget(req)}
          />
        )}

        <Toast kind={toast?.kind} message={toast?.msg ?? null} onClose={() => setToast(null)} />
      </kit.Page>
    </div>
  )
}
