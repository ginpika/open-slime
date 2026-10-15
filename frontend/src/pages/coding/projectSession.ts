import type { CodingRun } from '../../api/coding'

const COOKIE = 'minis.coding.project'

export function readCodingProject(): string {
  try {
    const entry = document.cookie.split(';').map(item => item.trim()).find(item => item.startsWith(`${COOKIE}=`))
    return entry ? decodeURIComponent(entry.slice(COOKIE.length + 1)) : ''
  } catch { return '' }
}

/** 不设置 Expires / Max-Age，项目选择只保留在浏览器会话中。 */
export function rememberCodingProject(id: string): void {
  try {
    document.cookie = `${COOKIE}=${encodeURIComponent(id)}; Path=/; SameSite=Lax${location.protocol === 'https:' ? '; Secure' : ''}${id ? '' : '; Max-Age=0'}`
  } catch { /* 禁用 cookie 时仍可通过普通项目链接进入 IDE。 */ }
}

export function resolveCodingProject(params: URLSearchParams, runs: Pick<CodingRun, 'id' | 'repositoryId'>[], remembered: string): string {
  const requested = params.get('project') || runs.find(run => run.id === params.get('run'))?.repositoryId || ''
  // 项目、任务、目录快捷入口及主动返回起始页，均优先于自动恢复。
  return ['project', 'run', 'workspace', 'directory', 'start'].some(key => params.has(key)) ? requested : remembered
}
