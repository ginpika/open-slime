import { useState } from 'react'
import { BRAND } from '../branding'
import type { TerminalTarget, TerminalTargetRequest } from '../api/client'
import FloatWindow from '../components/FloatWindow'
import { Field, INPUT, ModeBtn, Select, linesToPatterns } from '../components/formKit'
import { btnOutline, btnPrimary } from '../components/ui'
import './workspaceDialog.css'

const DOCKER_IMAGES = [
  { name: 'Ubuntu', image: 'ubuntu:24.04', desc: '通用开发 · apt' },
  { name: 'Debian', image: 'debian:bookworm-slim', desc: '精简稳定 · apt' },
  { name: 'Alpine', image: 'alpine:latest', desc: '轻量环境 · apk' },
  { name: 'Arch Linux', image: 'archlinux:latest', desc: '滚动更新 · pacman' },
] as const
/** 添加 / 编辑一个工作空间（docker 容器 或 SSH 服务器） */
export default function WorkspaceDialog({
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
  const [workdir, setWorkdir] = useState(initial?.workdir ?? (initial ? '' : '/workspace'))
  const [hostWorkdir, setHostWorkdir] = useState(initial?.hostWorkdir ?? '')
  const [dockerMode, setDockerMode] = useState<'image' | 'container'>(initial?.container ? 'container' : 'image')
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
  const [image, setImage] = useState(initial?.image ?? DOCKER_IMAGES[0].image)
  const [customImage, setCustomImage] = useState(!!initial?.image && !DOCKER_IMAGES.some(p => p.image === initial.image))
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
  const dockerOk = dockerMode === 'container' ? !!container.trim() : !!image.trim()
  const sshOk = !!sshHost.trim() && (sshAuth === 'key' ? !!sshPrivateKey.trim() : !!sshPassword.trim())
  const valid = kind === 'docker' ? dockerOk : !!name.trim() && sshOk

  const submit = () => {
    if (!valid || busy) return
    const docker = kind === 'docker'
    onSubmit({
      name: name.trim() || initial?.name || '',
      kind,
      enabled,
      workdir: workdir.trim() || null,
      hostWorkdir: docker && dockerMode === 'image' ? hostWorkdir.trim() || null : null,
      timeoutMs: positive(timeoutMs),
      maxOutput: positive(maxOutput),
      allowPatterns: linesToPatterns(allow),
      denyPatterns: linesToPatterns(deny),
      dockerHost: docker ? dockerHost.trim() || null : null,
      image: docker && dockerMode === 'image' ? image.trim() || null : null,
      container: docker && dockerMode === 'container' ? container.trim() || null : null,
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
        className="workspace-dialog space-y-4"
      >
        <div className="flex flex-wrap gap-2">
          <ModeBtn
            active={kind === 'docker'}
            title="Docker 容器"
            desc="选好镜像即可使用，自动创建并复用容器"
            onClick={() => { setKind('docker'); if (!initial && kind !== 'docker') setWorkdir('/workspace') }}
          />
          <ModeBtn
            active={kind === 'ssh'}
            title="SSH 服务器"
            desc="远程服务器：密码或私钥认证，直接操作其物理环境"
            onClick={() => { setKind('ssh'); if (!initial && kind !== 'ssh') setWorkdir('') }}
          />
        </div>

        {kind === 'ssh' && <Field label="工作空间名" hint="模型用这个名字指认环境，须唯一且好记，例如 web-01 / builder">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className={INPUT}
            placeholder="web-01"
            autoFocus
          />
        </Field>}

        {kind === 'docker' ? (
          <>
            <div className="flex flex-wrap gap-2" aria-label="Docker 来源">
              <button type="button" className={dockerMode === 'image' ? btnPrimary('rounded-sm') : btnOutline('rounded-sm')} aria-pressed={dockerMode === 'image'} onClick={() => { setDockerMode('image'); if (!initial && dockerMode !== 'image') setWorkdir('/workspace') }}>从镜像新建</button>
              <button type="button" className={dockerMode === 'container' ? btnPrimary('rounded-sm') : btnOutline('rounded-sm')} aria-pressed={dockerMode === 'container'} onClick={() => { setDockerMode('container'); if (!initial && dockerMode !== 'container') setWorkdir('') }}>接入已有容器</button>
            </div>
            {dockerMode === 'container' ? <Field label="容器名 / ID" hint="使用容器已有的目录挂载；容器停止时会自动启动。">
              <input
                value={container}
                onChange={(e) => setContainer(e.target.value)}
                className={`${INPUT} font-mono`}
                placeholder={BRAND.backendContainer}
                spellCheck={false}
              />
            </Field> : <>
              <fieldset>
                <legend className="mb-2 text-sm font-medium text-fg">选择镜像</legend>
                <div className="grid grid-cols-2 gap-2">
                  {DOCKER_IMAGES.map(preset => (
                    <label key={preset.image} className={`cursor-pointer rounded-md border p-3 transition ${!customImage && image === preset.image ? 'border-accent bg-accent-soft' : 'border-line hover:border-line-strong'}`}>
                      <span className="flex items-center gap-2 text-base font-semibold text-fg">
                        <input type="radio" name="docker-image" checked={!customImage && image === preset.image} onChange={() => { setCustomImage(false); setImage(preset.image) }} className="accent-[color:var(--c-accent)]" />
                        {preset.name}
                      </span>
                      <span className="mt-1 block text-xs text-muted">{preset.desc}</span>
                      <span className="mt-1 block break-all font-mono text-xs text-muted">{preset.image}</span>
                    </label>
                  ))}
                </div>
                <label className="mt-3 flex cursor-pointer items-center gap-2 text-sm text-fg">
                  <input type="radio" name="docker-image" checked={customImage} onChange={() => { setCustomImage(true); setImage('') }} className="accent-[color:var(--c-accent)]" />自定义镜像
                </label>
              </fieldset>
              {customImage && <Field label="镜像地址" hint="可填写本地镜像或镜像仓库地址与标签。">
                <input
                  value={image}
                  onChange={(e) => setImage(e.target.value)}
                  className={`${INPUT} font-mono`}
                  placeholder="ubuntu:24.04"
                  spellCheck={false}
                />
              </Field>}
              <p className="text-sm leading-relaxed text-muted">无需填写其他信息。首次打开 Shell 或执行命令时，自动拉取缺失的镜像并创建容器，之后持续复用。</p>
              <details open={!!initial?.hostWorkdir} className="rounded-md border border-line p-3">
                <summary className="cursor-pointer text-base font-medium text-fg">绑定工作目录（可选）</summary>
                <div className="mt-3 space-y-3">
                  <Field label="宿主机目录" hint="填写 Docker 所在机器上的已有目录；容器内的文件改动会同步到此目录。留空则不绑定。">
                    <input value={hostWorkdir} onChange={e => setHostWorkdir(e.target.value)} className={`${INPUT} font-mono`} placeholder="例如 D:\Projects\app 或 /home/user/app" spellCheck={false} />
                  </Field>
                  <Field label="容器内路径" hint="默认 /workspace，同时作为文件浏览与 Shell 的起始目录。">
                    <input value={workdir} onChange={e => setWorkdir(e.target.value)} className={`${INPUT} font-mono`} placeholder="/workspace" spellCheck={false} />
                  </Field>
                </div>
              </details>
              {initial && <p className="text-xs leading-relaxed text-muted">若会话容器已创建，更换镜像或目录绑定需新建工作空间，原容器和文件会保留。</p>}
            </>}
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

        {(kind === 'ssh' || dockerMode === 'container') && <Field label="工作目录" hint="文件浏览和 Shell 从此目录开始。留空则用环境默认目录。">
          <input
            value={workdir}
            onChange={(e) => setWorkdir(e.target.value)}
            className={`${INPUT} font-mono`}
            placeholder="/srv/app"
            spellCheck={false}
          />
        </Field>}

        <details className="rounded-md border border-line p-3">
          <summary className="cursor-pointer text-base font-medium text-fg">高级设置</summary>
          <div className="mt-3 space-y-3">
            {kind === 'docker' && <>
              <Field label="工作空间名" hint={initial ? '留空保留当前名称。' : '留空自动生成唯一名称。'}>
                <input value={name} onChange={e => setName(e.target.value)} className={INPUT} placeholder="自动生成" />
              </Field>
              <Field label="Docker Host" hint="留空使用环境默认连接。远程 Docker 的目录绑定使用远程机器上的路径。">
                <input value={dockerHost} onChange={e => setDockerHost(e.target.value)} className={`${INPUT} font-mono`} placeholder="环境默认" spellCheck={false} />
              </Field>
            </>}
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
          </div>
        </details>

        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className={btnOutline('rounded-sm')} onClick={onClose}>
            取消
          </button>
          <button type="submit" className={btnPrimary('rounded-sm')} disabled={!valid || busy}>
            {busy ? '保存中 …' : initial ? '保存' : '创建工作空间'}
          </button>
        </div>
      </form>
    </FloatWindow>
  )
}

