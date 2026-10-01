/**
 * 多智能体 · 成员页：圆桌上坐着谁。
 *
 * <p>每位成员一张卡片（头像 + 名字 + 模型 / 备注），点进去是它自己的内部页
 * （{@code /multi-agent/{id}}）——资料与它自己那套「对话」提示词都在那里改，页面独立、空间充足。
 *
 * <p>与圆桌页（{@code /roundtable}）分开成两个页面：这一页只管「人」，那一页只管「说」。
 *
 * <p>呈现全部来自 PageKit：同一份成员数据，在 flat 下是描边卡网格、
 * 在 Endfield 下是带发光角标的切角仪表块、在 BlueArchive 下是糖果卡窄栏流。
 */

import { useCallback, useEffect, useState } from 'react'
import { ApiError, getSettings, listMaPeers, type MaPeer } from '../api/client'
import { btnOutline } from '../components/ui'
import PageBoundary from '../components/PageBoundary'
import PeerAvatar from '../components/PeerAvatar'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'

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
  const kit = usePageKit()
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
    <kit.Page
      title="多智能体"
      en="AGENTS"
      meta={<span className="text-sm text-muted">{peers.length} 位成员</span>}
    >
      <kit.Toolbar
        left="圆桌上坐着的人：名字、头像、模型与各自的提示词。点开一张卡片进它的内部页；想和他们说话，用首页的「群聊」。"
        right={
          <a href="#/roundtable" className={btnOutline('rounded-lg')}>
            前往圆桌
          </a>
        }
      />

      <PageBoundary>
        <div className={kit.panelGrid}>
          <kit.Tile href={agentHref('new')} dashed>
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
            新建成员
          </kit.Tile>

          {peers.map((p) => (
            <kit.Tile key={p.id} href={agentHref(p.id)}>
              <div className="flex items-start gap-3.5">
                <PeerAvatar
                  name={p.name}
                  avatar={p.avatar}
                  size="h-12 w-12"
                  textClass="text-md"
                />
                <div className="min-w-0 flex-1">
                  <p className="ui-heading truncate text-md">{p.name}</p>
                  <p className="mt-1 truncate text-2xs text-hint">提示词 · 每位成员独立一套</p>
                  <p className="mt-0.5 truncate text-2xs text-hint">
                    模型 · {labelOf(modelOptions, p.modelId ?? '')}
                  </p>
                  {p.note && (
                    <p className="mt-1.5 line-clamp-2 text-sm leading-relaxed text-muted">
                      {p.note}
                    </p>
                  )}
                </div>
              </div>
            </kit.Tile>
          ))}
        </div>
      </PageBoundary>

      <Toast message={toast} kind="error" onClose={() => setToast(null)} />
    </kit.Page>
  )
}
