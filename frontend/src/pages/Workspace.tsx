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
import { BRAND } from '../branding'
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
  runTerminalCommand,
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
import { Field, INPUT, ModeBtn, Select, linesToPatterns } from '../components/formKit'
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

/** 添加 / 编辑一个工作空间（docker 容器 或 SSH 服务器） */
function WorkspaceDialog({
  initial,
  busy,
  onClose,
  onSubmit,
}: {
  initial: TerminalTarget | null
  busy: boolean
  onClose: () => void
  onSubmit: (req: TerminalTargetRequest) => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [kind, setKind] = useState<'docker' | 'ssh'>(initial?.kind ?? 'docker')
  const [enabled, setEnabled] = useState(initial?.enabled ?? true)
  const [workdir, setWorkdir] = useState(initial?.workdir ?? '')
  const [timeoutMs, setTimeoutMs] = useState(
    initial?.timeoutMs != null ? String(initial.timeoutMs) : '',
  )
  const [maxOutput, setMaxOutput] = useState(
    initial?.maxOutput != null ? String(initial.maxOutput) : '',
  )
  const [allow, setAllow] = useState((initial?.allowPatterns ?? []).join('\n'))
  const [deny, setDeny] = useState((initial?.denyPatterns ?? []).join('\n'))
  const [note, setNote] = useState(initial?.note ?? '')
  const [dockerHost, setDockerHost] = useState(initial?.dockerHost ?? '')
  const [image, setImage] = useState(initial?.image ?? '')
  const [container, setContainer] = useState(initial?.container ?? '')
  const [sshHost, setSshHost] = useState(initial?.sshHost ?? '')
  const [sshPort, setSshPort] = useState(initial?.sshPort != null ? String(initial.sshPort) : '')
  const [sshUser, setSshUser] = useState(initial?.sshUser ?? '')
  const [sshAuth, setSshAuth] = useState<'password' | 'key'>(initial?.sshAuth ?? 'password')
  const [sshPassword, setSshPassword] = useState(initial?.sshPassword ?? '')
  const [sshPrivateKey, setSshPrivateKey] = useState(initial?.sshPrivateKey ?? '')
  const [sshPassphrase, setSshPassphrase] = useState(initial?.sshPassphrase ?? '')
  const [hostKeyCheck, setHostKeyCheck] = useState<'known_hosts' | 'accept-all'>(
    initial?.hostKeyCheck ?? 'known_hosts',
  )

  const positive = (s: string): number | null => {
    const n = Number(s.trim())
    return s.trim() && Number.isFinite(n) && n > 0 ? n : null
  }
  const dockerOk = !!(container.trim() || image.trim())
  const sshOk = !!sshHost.trim() && (sshAuth === 'key' ? !!sshPrivateKey.trim() : !!sshPassword.trim())
  const valid = !!name.trim() && (kind === 'docker' ? dockerOk : sshOk)

  const submit = () => {
    if (!valid || busy) return
    const docker = kind === 'docker'
    onSubmit({
      name: name.trim(),
      kind,
      enabled,
      workdir: workdir.trim() || null,
      timeoutMs: positive(timeoutMs),
      maxOutput: positive(maxOutput),
      allowPatterns: linesToPatterns(allow),
      denyPatterns: linesToPatterns(deny),
      dockerHost: docker ? dockerHost.trim() || null : null,
      image: docker ? image.trim() || null : null,
      container: docker ? container.trim() || null : null,
      sshHost: docker ? null : sshHost.trim(),
      sshPort: docker ? null : positive(sshPort),
      sshUser: docker ? null : sshUser.trim() || null,
      sshAuth,
      sshPassword: docker ? null : sshPassword || null,
      sshPrivateKey: docker ? null : sshPrivateKey || null,
      sshPassphrase: docker ? null : sshPassphrase || null,
      hostKeyCheck,
      note: note.trim() || null,
    })
  }

  return (
    <FloatWindow
      title={initial ? '编辑工作空间' : '新建工作空间'}
      onClose={onClose}
      width={560}
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          submit()
        }}
        className="space-y-3"
      >
        <div className="flex flex-wrap gap-2">
          <ModeBtn
            active={kind === 'docker'}
            title="Docker 容器"
            desc="本机 docker 环境：指定既有容器，或给镜像自动起一个会话容器"
            onClick={() => setKind('docker')}
          />
          <ModeBtn
            active={kind === 'ssh'}
            title="SSH 服务器"
            desc="远程服务器：密码或私钥认证，直接操作其物理环境"
            onClick={() => setKind('ssh')}
          />
        </div>

        <Field label="工作空间名" hint="模型用这个名字指认环境，须唯一且好记，例如 web-01 / builder">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className={INPUT}
            placeholder="web-01"
            autoFocus
          />
        </Field>

        {kind === 'docker' ? (
          <>
            <Field label="容器名 / ID" hint="直接 exec 进这个既有容器（与下面的镜像二选一）">
              <input
                value={container}
                onChange={(e) => setContainer(e.target.value)}
                className={`${INPUT} font-mono`}
                placeholder={BRAND.backendContainer}
                spellCheck={false}
              />
            </Field>
            <Field
              label="镜像"
              hint={`未指定容器时按此镜像惰性起一个常驻会话容器（${BRAND.termPrefix}<工作空间名>）并复用`}
            >
              <input
                value={image}
                onChange={(e) => setImage(e.target.value)}
                className={`${INPUT} font-mono`}
                placeholder="ubuntu:24.04"
                spellCheck={false}
              />
            </Field>
            <Field
              label="Docker Host"
              hint="留空用环境默认；Linux/mac 通常 unix:///var/run/docker.sock，Windows 可填 npipe:////./pipe/docker_engine 或 tcp://localhost:2375"
            >
              <input
                value={dockerHost}
                onChange={(e) => setDockerHost(e.target.value)}
                className={`${INPUT} font-mono`}
                placeholder="（留空 = 环境默认）"
                spellCheck={false}
              />
            </Field>
          </>
        ) : (
          <>
            <div className="flex gap-3">
              <div className="flex-1">
                <Field label="主机">
                  <input
                    value={sshHost}
                    onChange={(e) => setSshHost(e.target.value)}
                    className={`${INPUT} font-mono`}
                    placeholder="10.0.0.11 或 example.com"
                    spellCheck={false}
                  />
                </Field>
              </div>
              <div className="w-24">
                <Field label="端口">
                  <input
                    value={sshPort}
                    onChange={(e) => setSshPort(e.target.value.replace(/[^\d]/g, ''))}
                    className={`${INPUT} font-mono`}
                    placeholder="22"
                    inputMode="numeric"
                    spellCheck={false}
                  />
                </Field>
              </div>
            </div>
            <Field label="用户名">
              <input
                value={sshUser}
                onChange={(e) => setSshUser(e.target.value)}
                className={`${INPUT} font-mono`}
                placeholder="root"
                spellCheck={false}
              />
            </Field>
            <Field label="认证方式">
              <Select
                value={sshAuth}
                onChange={(v) => setSshAuth(v as 'password' | 'key')}
                options={[
                  { value: 'password', label: '密码' },
                  { value: 'key', label: '私钥' },
                ]}
              />
            </Field>
            {sshAuth === 'password' ? (
              <Field label="密码">
                <input
                  value={sshPassword}
                  onChange={(e) => setSshPassword(e.target.value)}
                  className={`${INPUT} font-mono`}
                  type="password"
                  autoComplete="off"
                />
              </Field>
            ) : (
              <>
                <Field label="私钥" hint="直接粘贴 PEM 内容，或填服务器/本机上私钥文件的路径">
                  <textarea
                    value={sshPrivateKey}
                    onChange={(e) => setSshPrivateKey(e.target.value)}
                    className={`${INPUT} font-mono h-24`}
                    placeholder="-----BEGIN OPENSSH PRIVATE KEY----- …"
                    spellCheck={false}
                  />
                </Field>
                <Field label="私钥口令" hint="私钥未加密时留空">
                  <input
                    value={sshPassphrase}
                    onChange={(e) => setSshPassphrase(e.target.value)}
                    className={`${INPUT} font-mono`}
                    type="password"
                    autoComplete="off"
                  />
                </Field>
              </>
            )}
            <Field
              label="Host Key 校验"
              hint="known_hosts：首次连接自动记录该主机指纹并放行，之后逐次校验，指纹变化即拒绝；accept-all 完全不校验，仅限自建内网"
            >
              <Select
                value={hostKeyCheck}
                onChange={(v) => setHostKeyCheck(v as 'known_hosts' | 'accept-all')}
                options={[
                  { value: 'known_hosts', label: 'known_hosts（首次信任，推荐）' },
                  { value: 'accept-all', label: 'accept-all（不校验）' },
                ]}
              />
            </Field>
          </>
        )}

        <Field label="工作目录" hint="工作空间的文件面以此目录为起点；每条命令也先 cd 进来。留空则用远端默认目录">
          <input
            value={workdir}
            onChange={(e) => setWorkdir(e.target.value)}
            className={`${INPUT} font-mono`}
            placeholder="/srv/app"
            spellCheck={false}
          />
        </Field>

        <div className="flex gap-3">
          <div className="flex-1">
            <Field label="命令超时（毫秒）" hint="留空用全局默认；超时会被真的终止">
              <input
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(e.target.value.replace(/[^\d]/g, ''))}
                className={`${INPUT} font-mono`}
                placeholder="60000"
                inputMode="numeric"
                spellCheck={false}
              />
            </Field>
          </div>
          <div className="flex-1">
            <Field label="输出上限（字符）" hint="留空用全局默认；超出部分截断并标注">
              <input
                value={maxOutput}
                onChange={(e) => setMaxOutput(e.target.value.replace(/[^\d]/g, ''))}
                className={`${INPUT} font-mono`}
                placeholder="20000"
                inputMode="numeric"
                spellCheck={false}
              />
            </Field>
          </div>
        </div>

        <Field label="命令白名单" hint="一行一条正则；非空时模型命令必须命中其一才放行">
          <textarea
            value={allow}
            onChange={(e) => setAllow(e.target.value)}
            className={`${INPUT} font-mono h-20`}
            placeholder={'^\\s*(ls|cat|grep|df|docker ps)\\b'}
            spellCheck={false}
          />
        </Field>
        <Field label="命令黑名单" hint="一行一条正则；命中即拒（先于白名单判定）">
          <textarea
            value={deny}
            onChange={(e) => setDeny(e.target.value)}
            className={`${INPUT} font-mono h-20`}
            placeholder={'rm\\s+-rf\\s+/'}
            spellCheck={false}
          />
        </Field>
        <Field label="说明" hint="给模型看的一句话：这台环境是什么、什么时候该用它">
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            className={INPUT}
            placeholder="生产 Web 服务器，只读排查用"
          />
        </Field>

        <label className="flex cursor-pointer items-center gap-2 pt-1">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
            className="h-3.5 w-3.5 accent-[color:var(--c-accent)]"
          />
          <span className="text-sm text-muted">
            启用（模型可见可用；不启用则模型完全看不到这台环境）
          </span>
        </label>

        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className={btnOutline('rounded-sm')} onClick={onClose}>
            取消
          </button>
          <button type="submit" className={btnPrimary('rounded-sm')} disabled={!valid || busy}>
            {busy ? '保存中 …' : '保存'}
          </button>
        </div>
      </form>
    </FloatWindow>
  )
}

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

  const [runTargetId, setRunTargetId] = useState('')
  const [runCmd, setRunCmd] = useState('')
  const [running, setRunning] = useState(false)
  const [lastRun, setLastRun] = useState<TerminalCommand | null>(null)
  const [runErr, setRunErr] = useState<string | null>(null)
  /* 试跑 / 最近执行收进浮窗，由页头按钮唤起 */
  const [runDlg, setRunDlg] = useState(false)
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
  const runTarget = targets.find((t) => t.id === runTargetId) ?? null

  /** 统一提交：成功后以后端返回的最新视图覆盖本地，并提示结果 */
  const runView = async (fn: () => Promise<TerminalView>, ok: string): Promise<void> => {
    setBusy(true)
    try {
      setView(await fn())
      setToast({ kind: 'success', msg: ok })
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  const submitTarget = async (req: TerminalTargetRequest) => {
    const editing = dlg?.editing ?? null
    await runView(
      () => (editing ? updateTerminalTarget(editing.id, req) : createTerminalTarget(req)),
      editing ? '工作空间已更新' : '工作空间已创建',
    )
    setDlg(null)
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

  const runNow = async () => {
    if (!runTarget || !runCmd.trim()) return
    setRunning(true)
    setRunErr(null)
    try {
      const r = await runTerminalCommand(runTarget.id, runCmd.trim())
      setLastRun(r)
      refreshCommands()
    } catch (e) {
      setLastRun(null)
      setRunErr(errMsg(e))
    } finally {
      setRunning(false)
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
        {/* 页头动作条：左侧定位说明，右侧收进浮窗的试跑与执行记录（把版面让给文件浏览器） */}
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
              <button type="button" className={btnQuiet('rounded-sm')} onClick={() => setRunDlg(true)}>
                人工试跑
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
                        disabled={busy}
                        onClick={() => {
                          setRunTargetId(t.id)
                          setSelectedId(t.id)
                          setLastRun(null)
                          setRunErr(null)
                          setRunDlg(true)
                        }}
                      >
                        试跑
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

        {/* 人工试跑：页头按钮唤起的浮窗 */}
        {runDlg && (
          <FloatWindow title="人工试跑" onClose={() => setRunDlg(false)} width={640}>
            <p className="text-xs leading-relaxed text-hint">
              与模型走同一条执行路径：同样上白名单 / 黑名单与超时，同样记入「最近执行」。
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <div className="w-44 shrink-0">
                <Select
                  value={runTargetId}
                  onChange={setRunTargetId}
                  options={[
                    { value: '', label: '选择工作空间 …' },
                    ...targets.map((t) => ({ value: t.id, label: t.name })),
                  ]}
                />
              </div>
              <input
                value={runCmd}
                onChange={(e) => setRunCmd(e.target.value)}
                className={`${INPUT} min-w-[180px] flex-1 font-mono`}
                placeholder="uname -a"
                spellCheck={false}
              />
              <button
                type="button"
                className={btnPrimary('rounded-sm')}
                disabled={running || !runTarget || !runCmd.trim()}
                onClick={() => void runNow()}
              >
                {running ? '执行中 …' : '执行'}
              </button>
            </div>
            {runErr && (
              <p className="mt-3 rounded-md border border-danger bg-fg/[0.03] px-3 py-3 text-xs leading-relaxed text-muted">
                {runErr}
              </p>
            )}
            {lastRun && (
              <div className="mt-3">
                <div className="flex flex-wrap items-center gap-2">
                  <kit.Badge tone={lastRun.ok ? 'ok' : 'danger'}>
                    {lastRun.exitCode == null ? 'exit ?' : `exit ${lastRun.exitCode}`}
                  </kit.Badge>
                  <span className="mono text-2xs text-dim">{lastRun.durationMs} ms</span>
                  {lastRun.timedOut && <kit.Badge tone="warn">已超时终止</kit.Badge>}
                  {lastRun.truncated && <kit.Badge tone="warn">输出已截断</kit.Badge>}
                </div>
                <pre className="scrollbar-slim mt-2 max-h-[45vh] select-text overflow-auto whitespace-pre-wrap break-words rounded-md border border-line bg-fg/[0.03] p-3 font-mono text-xs leading-5 text-muted">
                  {lastRun.stdout?.trim() || '（无输出）'}
                  {lastRun.stderr?.trim() ? `\n--- stderr ---\n${lastRun.stderr.trim()}` : ''}
                </pre>
              </div>
            )}
          </FloatWindow>
        )}

        {/* 最近执行：页头按钮唤起的浮窗 */}
        {cmdDlg && (
          <FloatWindow title="最近执行" onClose={() => setCmdDlg(false)} width={560}>
            <div className="flex items-center justify-between gap-2">
              <p className="text-xs leading-relaxed text-hint">
                最近 20 条命令记录，人工试跑与模型执行都在此。
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
