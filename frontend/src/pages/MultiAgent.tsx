/**
 * 多智能体 · 成员页：圆桌上坐着谁。
 *
 * <p>每位成员一张卡片（头像 + 名字 + 模型 / 备注），点进去是它自己的内部页
 * （{@code /multi-agent/{id}}）——资料与它自己那套「对话」提示词都在那里改，页面独立、空间充足。
 *
 * <p>与圆桌页（{@code /roundtable}）分开成两个页面：这一页只管「人」，那一页只管「说」。
 * 标题下方有一行按钮，其中的「前往圆桌」直接跳过去；首页的「群聊」也进同一页。
 */

import { useCallback, useEffect, useState } from 'react'
import { ApiError, getSettings, listMaPeers, type MaPeer } from '../api/client'
import { btnOutline } from '../components/ui'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import Toast from '../components/Toast'

type Option = { value: string; label: string }

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

/** 选项表里取显示名（找不到就原样回显，别显示成空白） */
function labelOf(options: Option[], value: string): string {
  return options.find((o) => o.value === value)?.label ?? value
}

/** 成员内部页地址（新建走同一个页面，末段用 new） */
function agentHref(id: string): string {
  return `#/multi-agent/${encodeURIComponent(id)}`
}

export default function MultiAgent() {
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [modelOptions, setModelOptions] = useState<Option[]>([])
  const [toast, setToast] = useState<string | null>(null)

  const reloadPeers = useCallback(async () => {
    try {
      setPeers(await listMaPeers())
    } catch (e) {
      setToast(errMsg(e))
    }
  }, [])

  const loadModelOptions = useCallback(async () => {
    try {
      const settings = await getSettings()
      setModelOptions([
        { value: '', label: '跟随设置页默认' },
        ...settings.models.map((m) => ({ value: m.id, label: m.name })),
      ])
    } catch (e) {
      setToast(errMsg(e))
    }
  }, [])

  useEffect(() => {
    void reloadPeers()
    void loadModelOptions()
  }, [reloadPeers, loadModelOptions])

  return (
    <section className="flex flex-col gap-6 pt-6 pb-4">
      <header className="flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
        <div>
          <h1 className="text-[17px] font-semibold text-fg">多智能体</h1>
          <p className="mt-1 text-[12px] leading-relaxed text-muted">
            圆桌上坐着的人：名字、头像、模型与各自的提示词。点开一张卡片进它的内部页。
            想和他们说话，用首页的「群聊」。
          </p>
        </div>
        <p className="text-[12px] text-muted">{peers.length} 位成员</p>
      </header>

      {/* 标题下的按钮行：这一页只管「人」，想和他们说话得去圆桌页，这里给一条直接的回路
          （原先只有首页的「群聊」能进圆桌） */}
      <div className="flex flex-wrap items-center gap-2">
        <a href="#/roundtable" className={btnOutline('rounded-lg')}>
          前往圆桌
        </a>
      </div>

      <PageBoundary>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <a
            href={agentHref('new')}
            className="flex min-h-[104px] flex-col items-center justify-center gap-1.5 rounded-2xl border border-dashed border-line text-[12px] text-muted transition hover:border-line-strong hover:bg-fg/[0.02] hover:text-fg"
          >
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
            新建成员
          </a>

          {peers.map((p) => (
            <a
              key={p.id}
              href={agentHref(p.id)}
              className="flex items-start gap-3.5 rounded-2xl border border-line p-4 text-left transition hover:border-line-strong hover:bg-fg/[0.02]"
            >
              <PeerAvatar name={p.name} avatar={p.avatar} size="h-12 w-12" textClass="text-[17px]" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[14px] font-semibold text-fg">{p.name}</p>
                <p className="mt-1 truncate text-[11px] text-hint">
                  提示词 · 每位成员独立一套
                </p>
                <p className="mt-0.5 truncate text-[11px] text-hint">
                  模型 · {labelOf(modelOptions, p.modelId ?? '')}
                </p>
                {p.note && (
                  <p className="mt-1.5 line-clamp-2 text-[12px] leading-relaxed text-muted">
                    {p.note}
                  </p>
                )}
              </div>
            </a>
          ))}
        </div>
      </PageBoundary>

      <Toast message={toast} kind="error" onClose={() => setToast(null)} />
    </section>
  )
}
