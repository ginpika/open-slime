/**
 * 工作空间页：以 docker 容器 / SSH 服务器为 agent 的工作空间——在此维护环境（增删改、连通性测试、
 * 逐目标启用），并浏览其中的文件（列目录、文本预览、下载）。
 *
 * <p>「工作空间」是命令行终端（{@code ai.terminal}）之上的扩展：环境定义与凭证仍由终端模块的接口
 * 维护（{@code /api/terminal}），本页额外接上工作空间的文件面（{@code /api/workspaces/*}）。
 * 因此这里不再重复一套环境配置，只是把终端能力组织成一个面向「工作空间」的页面。
 *
 * <p>视觉上按 Material Design 3 组织：surface-container 阶梯做卡片层次，outline/on-surface 系列做
 * 描边与文字，按钮分填充 / 色调 / 描边 / 文本四档（均由主题令牌现混，浅色 / 深色 / 自定义主题自适应）。
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react'
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
import FloatWindow from '../components/FloatWindow'
import Toast from '../components/Toast'
import { Field, INPUT, ModeBtn, Select, linesToPatterns } from '../components/formKit'

/* ---------------- MD3 组件样式 ---------------- */

/* 统一按钮基底：微小圆角 + 12px 排版；实底 / 色调 / 描边 / 文本仅差配色与内边距 */
const BTN =
  'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-sm text-[12px] font-medium transition disabled:pointer-events-none disabled:opacity-40'
const FILLED = `${BTN} bg-accent px-3.5 py-1.5 text-accent-fg hover:bg-accent-hover`
const TONAL = `${BTN} bg-surface-container-high px-3.5 py-1.5 text-on-surface hover:bg-surface-container-highest`
const OUTLINED = `${BTN} border border-outline px-3.5 py-1.5 text-on-surface hover:bg-surface-container-high`
const TEXTBTN = `${BTN} px-2.5 py-1 text-accent hover:bg-accent/10`
/* 图标按钮（上一层 / 刷新 / 下载 / 关闭预览）：与文字按钮同圆角、同悬停 */
const ICONBTN =
  'inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-on-surface-variant transition hover:bg-surface-container-high disabled:pointer-events-none disabled:opacity-30'
const CARD = 'rounded-2xl bg-surface-container-low p-5'

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
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M3 7.5A2 2 0 0 1 5 5.5h3.6a2 2 0 0 1 1.6.8l.9 1.2H19a2 2 0 0 1 2 2v6.5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function IconFile() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
      <path d="M14 3v5h5" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
    </svg>
  )
}

function IconLink() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M10 13.5a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-1 1"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
      />
      <path
        d="M14 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l1-1"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
      />
    </svg>
  )
}

function IconDownload() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M12 4v10m0 0 3.5-3.5M12 14l-3.5-3.5M5 18h14"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function IconUp() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M12 19V6m0 0-5 5m5-5 5 5"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function IconRefresh() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-4 w-4">
      <path
        d="M20 11a8 8 0 1 0-2.3 6M20 5v6h-6"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

function IconChevron() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3.5 w-3.5">
      <path d="M9 6l6 6-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

/* ---------------- MD3 基础件 ---------------- */

/** MD3 芯片 */
function Chip({
  children,
  tone = 'neutral',
}: {
  children: ReactNode
  tone?: 'neutral' | 'on' | 'off'
}) {
  const tones = {
    neutral: 'bg-surface-container-highest text-on-surface-variant',
    on: 'bg-accent text-accent-fg',
    off: 'bg-surface-variant text-on-surface-variant',
  }
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${tones[tone]}`}>
      {children}
    </span>
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
            className="h-3.5 w-3.5 accent-[var(--color-accent)]"
          />
          <span className="text-[12px] text-on-surface-variant">
            启用（模型可见可用；不启用则模型完全看不到这台环境）
          </span>
        </label>

        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className={OUTLINED} onClick={onClose}>
            取消
          </button>
          <button type="submit" className={FILLED} disabled={!valid || busy}>
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
    <div className={`${CARD} flex min-h-0 flex-col`}>
      {/* 面包屑 + 刷新 */}
      <div className="flex shrink-0 items-center gap-2">
        <button
          type="button"
          title="上一层"
          disabled={segments.length === 0}
          onClick={() => setSegments((s) => s.slice(0, -1))}
          className={ICONBTN}
        >
          <IconUp />
        </button>
        <div className="scrollbar-hide flex min-w-0 flex-1 items-center gap-1 overflow-x-auto text-[12px]">
          <button
            type="button"
            onClick={() => setSegments([])}
            className={`shrink-0 rounded-sm px-2 py-1 font-medium transition ${
              segments.length === 0
                ? 'bg-surface-container-high text-on-surface'
                : 'text-on-surface-variant hover:bg-surface-container-high'
            }`}
          >
            {workspace.name}
          </button>
          {segments.map((seg, i) => (
            <span key={`${seg}-${i}`} className="flex shrink-0 items-center gap-1">
              <span className="text-outline">
                <IconChevron />
              </span>
              <button
                type="button"
                onClick={() => setSegments((s) => s.slice(0, i + 1))}
                className={`rounded-sm px-2 py-1 font-mono transition ${
                  i === segments.length - 1
                    ? 'bg-surface-container-high text-on-surface'
                    : 'text-on-surface-variant hover:bg-surface-container-high'
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
          className={ICONBTN}
        >
          <IconRefresh />
        </button>
      </div>

      {/* 目录项 */}
      <div className="mt-3 min-h-0 flex-1 overflow-y-auto scrollbar-slim">
        {loading && <p className="px-3 py-6 text-[12px] text-on-surface-variant">加载中 …</p>}
        {!loading && err && (
          <p className="rounded-xl bg-surface-container-high px-3 py-4 text-[12px] leading-relaxed text-on-surface-variant">
            {err}
          </p>
        )}
        {!loading && !err && dir?.entries.length === 0 && (
          <p className="px-3 py-6 text-[12px] text-on-surface-variant">（空目录）</p>
        )}
        {!loading &&
          !err &&
          dir?.entries.map((e) => {
            const p = entryPath(e.name)
            return (
              <div
                key={e.name}
                className="flex items-center gap-3 rounded-xl px-2.5 py-2 transition hover:bg-surface-container-high"
              >
                <span className="shrink-0 text-on-surface-variant">
                  {e.dir ? <IconFolder /> : e.link ? <IconLink /> : <IconFile />}
                </span>
                <button
                  type="button"
                  onClick={() => (e.dir ? setSegments((s) => [...s, e.name]) : void openFile(e.name))}
                  title={e.name}
                  className="min-w-0 flex-1 truncate text-left text-[13px] text-on-surface transition hover:text-accent hover:underline"
                >
                  {e.name}
                </button>
                <span className="hidden w-32 shrink-0 truncate text-right font-mono text-[11px] text-on-surface-variant sm:block">
                  {e.modified}
                </span>
                <span className="w-20 shrink-0 text-right font-mono text-[11px] text-on-surface-variant">
                  {e.dir ? '—' : fmtSize(e.size)}
                </span>
                <button
                  type="button"
                  title="下载"
                  disabled={downloading === p}
                  onClick={() => void download(e.name)}
                  className={ICONBTN}
                >
                  <IconDownload />
                </button>
              </div>
            )
          })}
      </div>

      {/* 预览 */}
      {(previewLoading || preview || previewErr) && (
        <div className="mt-4 shrink-0 rounded-xl bg-surface-container">
          <div className="flex items-center gap-2 px-4 py-2.5">
            <span className="min-w-0 flex-1 truncate text-[12px] font-medium text-on-surface">
              {previewLoading ? '加载中 …' : preview?.name ?? '预览'}
            </span>
            {preview && <Chip>{fmtSize(preview.size)}</Chip>}
            {preview?.truncated && <Chip>已截断</Chip>}
            {preview?.binary && <Chip>二进制</Chip>}
            {preview && (
              <button
                type="button"
                className={TEXTBTN}
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
              className={ICONBTN}
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true" className="h-3.5 w-3.5">
                <path
                  d="M6 6l12 12M18 6L6 18"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                />
              </svg>
            </button>
          </div>
          <div className="border-t border-outline-variant px-4 py-3">
            {previewLoading && (
              <p className="text-[12px] text-on-surface-variant">读取中 …</p>
            )}
            {!previewLoading && previewErr && (
              <p className="text-[12px] leading-relaxed text-on-surface-variant">{previewErr}</p>
            )}
            {!previewLoading && preview?.binary && (
              <p className="text-[12px] leading-relaxed text-on-surface-variant">
                二进制文件（{fmtSize(preview.size)}），不支持文本预览，可下载后查看。
              </p>
            )}
            {!previewLoading && preview && !preview.binary && (
              <pre className="scrollbar-slim max-h-[360px] overflow-auto whitespace-pre-wrap break-words font-mono text-[12px] leading-relaxed text-on-surface">
                {preview.content?.length ? preview.content : '（空文件）'}
              </pre>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

/* ---------------- 页面 ---------------- */

export default function Workspace() {
  const [view, setView] = useState<TerminalView | null>(null)
  const [commands, setCommands] = useState<TerminalCommand[]>([])
  const [loadErr, setLoadErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; msg: string } | null>(null)
  const [dlg, setDlg] = useState<{ editing: TerminalTarget | null } | null>(null)
  const [probing, setProbing] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState('')

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
    <section className="flex flex-col py-8 lg:h-full">
      <Toast kind={toast?.kind} message={toast?.msg ?? null} onClose={() => setToast(null)} />

      {/* 页头 */}
      <header className="flex shrink-0 flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-[22px] font-semibold tracking-tight text-on-surface">工作空间</h1>
          <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-on-surface-variant">
            以 docker 容器或 SSH 服务器为 agent 的工作空间：模型通过命令行直接在其中作业，
            你也可以在这里浏览、预览与下载其中的文件。没有全局总开关——启用哪个工作空间，
            模型就能在哪里执行命令（run_command / list_terminals）。
          </p>
          {/* 试跑与执行记录收进浮窗，把页头之下的版面让给文件编辑器 */}
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className={TONAL} onClick={() => setRunDlg(true)}>
              人工试跑
            </button>
            <button
              type="button"
              className={OUTLINED}
              onClick={() => {
                refreshCommands()
                setCmdDlg(true)
              }}
            >
              最近执行
            </button>
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-4">
          <button type="button" className={FILLED} disabled={busy} onClick={() => setDlg({ editing: null })}>
            新建工作空间
          </button>
        </div>
      </header>

      {loadErr && (
        <p className="mt-6 shrink-0 rounded-2xl bg-surface-container-low px-5 py-4 text-[13px] leading-relaxed text-on-surface-variant">
          加载失败：{loadErr}
        </p>
      )}

      {/* 工作空间列表 + 文件编辑器：占据页头之下的全部剩余高度 */}
      <div className="mt-6 grid min-h-0 gap-6 lg:flex-1 lg:grid-cols-[320px_1fr]">
        <div className={`${CARD} flex min-h-0 flex-col`}>
          <h2 className="shrink-0 px-1 text-[13px] font-semibold text-on-surface">全部工作空间</h2>
          <div className="mt-3 min-h-0 flex-1 space-y-2 overflow-y-auto scrollbar-slim">
            {targets.length === 0 && (
              <p className="px-1 py-4 text-[12px] leading-relaxed text-on-surface-variant">
                还没有工作空间。新建一个 docker 容器或 SSH 服务器后，模型才知道能在哪里执行命令。
              </p>
            )}
            {targets.map((t) => {
              const active = t.id === selected?.id
              return (
                <div
                  key={t.id}
                  onClick={() => setSelectedId(t.id)}
                  className={`cursor-pointer rounded-2xl border p-3.5 transition ${
                    active
                      ? 'border-outline bg-surface-container-high'
                      : 'border-transparent bg-surface-container hover:bg-surface-container-high'
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => setSelectedId(t.id)}
                    className="block w-full text-left"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-[13px] font-semibold text-on-surface">{t.name}</span>
                      <Chip>{t.kind}</Chip>
                      {t.enabled ? <Chip tone="on">已启用</Chip> : <Chip tone="off">未启用</Chip>}
                    </div>
                    <p className="mt-1 break-all font-mono text-[11px] text-on-surface-variant">
                      {targetWhere(t)}
                    </p>
                    {t.note && (
                      <p className="mt-0.5 text-[11px] text-on-surface-variant">{t.note}</p>
                    )}
                  </button>
                  {/* 动作行独立于卡片点击：只响应各自按钮，不把点击冒泡成「选中」 */}
                  <div className="mt-2 flex cursor-default items-center gap-1" onClick={(e) => e.stopPropagation()}>
                    <button
                      type="button"
                      className={TEXTBTN}
                      disabled={busy || probing !== null}
                      onClick={() => void probeTarget(t)}
                    >
                      {probing === t.id ? '测试中 …' : '测试'}
                    </button>
                    <button type="button" className={TEXTBTN} disabled={busy} onClick={() => setDlg({ editing: t })}>
                      编辑
                    </button>
                    <button
                      type="button"
                      className={TEXTBTN}
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
                    <button type="button" className={TEXTBTN} disabled={busy} onClick={() => removeTarget(t)}>
                      删除
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {selected ? (
          <FileBrowser
            key={selected.id}
            workspace={selected}
            onToast={(t) => setToast(t)}
          />
        ) : (
          <div className={`${CARD} flex min-h-[320px] items-center justify-center`}>
            <p className="text-[12px] text-on-surface-variant">
              选择左侧的工作空间，即可浏览其中的文件。
            </p>
          </div>
        )}
      </div>

      {/* 人工试跑：页头按钮唤起的浮窗 */}
      {runDlg && (
        <FloatWindow title="人工试跑" onClose={() => setRunDlg(false)} width={640}>
          <p className="text-[11px] leading-relaxed text-on-surface-variant">
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
              className={TONAL}
              disabled={running || !runTarget || !runCmd.trim()}
              onClick={() => void runNow()}
            >
              {running ? '执行中 …' : '执行'}
            </button>
          </div>
          {runErr && (
            <p className="mt-3 rounded-xl bg-surface-container-high px-3 py-3 text-[12px] leading-relaxed text-on-surface-variant">
              {runErr}
            </p>
          )}
          {lastRun && (
            <div className="mt-3">
              <div className="flex flex-wrap items-center gap-2">
                <Chip tone={lastRun.ok ? 'on' : 'neutral'}>
                  {lastRun.exitCode == null ? 'exit ?' : `exit ${lastRun.exitCode}`}
                </Chip>
                <span className="text-[11px] text-on-surface-variant">{lastRun.durationMs} ms</span>
                {lastRun.timedOut && <Chip>已超时终止</Chip>}
                {lastRun.truncated && <Chip>输出已截断</Chip>}
              </div>
              <pre className="scrollbar-slim mt-2 max-h-[45vh] select-text overflow-auto whitespace-pre-wrap break-words rounded-xl bg-surface-container px-3 py-3 font-mono text-[11px] leading-relaxed text-on-surface">
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
            <p className="text-[11px] leading-relaxed text-on-surface-variant">
              最近 20 条命令记录，人工试跑与模型执行都在此。
            </p>
            <button type="button" className={`${TEXTBTN} shrink-0`} onClick={refreshCommands}>
              刷新
            </button>
          </div>
          <div className="mt-3 space-y-1.5">
            {commands.length === 0 && (
              <p className="px-1 py-4 text-[12px] text-on-surface-variant">还没有执行记录。</p>
            )}
            {commands.map((c) => (
              <div
                key={c.id}
                className="flex select-text items-start justify-between gap-3 rounded-xl bg-surface-container px-3 py-2.5"
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate font-mono text-[12px] text-on-surface">{c.command}</p>
                  <p className="mt-0.5 text-[11px] text-on-surface-variant">
                    {c.targetName} · {c.source} · {new Date(c.createdAt).toLocaleString()}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  {c.timedOut && <Chip>超时</Chip>}
                  {c.truncated && <Chip>截断</Chip>}
                  <Chip tone={c.ok ? 'on' : 'neutral'}>
                    {c.exitCode == null ? 'exit ?' : `exit ${c.exitCode}`}
                  </Chip>
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
    </section>
  )
}
