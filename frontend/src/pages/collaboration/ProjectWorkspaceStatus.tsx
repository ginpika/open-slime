import type { ProjectWorkspaceBinding } from '../../api/collaboration'
import { AppIcon } from '../../components/AppIcon'

export default function ProjectWorkspaceStatus({ binding, busy = false, onConfigure }: { binding?: ProjectWorkspaceBinding; busy?: boolean; onConfigure: () => void }) {
  return <section className="collab-workspace-status" aria-label="项目工作目录"><div><strong><AppIcon name="folder-open" />项目工作目录</strong>
    <p>{binding ? `${binding.workspaceName} / ${binding.directory || '目录不可用'}` : '尚未设置，可先讨论方案；执行代码前需要绑定项目目录。'}</p>
    {binding && !binding.enabled && <p role="alert">绑定已失效或工作空间未启用，请重新核对。</p>}</div>
    <button className="ui-btn ui-btn--outline" disabled={busy} onClick={onConfigure}>{binding ? '调整工作目录' : '设置工作目录'}</button>
  </section>
}
