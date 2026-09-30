/**
 * 页面内的错误边界：页面还在迭代时，任何一处渲染抛错都不该把整个应用打成白屏
 * （React 没有边界时会卸载整棵树，页面上只剩空白，只能刷新）。兜住之后就地在原位置给一行
 * 可读的错误，并提供「重试」把出错的子树重新挂一次。
 */
import { Component, type ReactNode } from 'react'
import { btnOutline } from './ui'

export default class PageBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error) {
    console.error('页面渲染出错：', error)
  }

  render() {
    if (this.state.error) {
      return (
        <div className="rounded-2xl border border-line px-4 py-6">
          <p className="text-[13px] font-medium text-fg">这一页渲染出错了</p>
          <p className="mt-2 text-[12px] text-muted">{this.state.error.message}</p>
          <button
            type="button"
            className={`${btnOutline('rounded-lg')} mt-4`}
            onClick={() => this.setState({ error: null })}
          >
            重试
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
