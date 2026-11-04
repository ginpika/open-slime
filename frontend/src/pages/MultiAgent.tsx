/**
 * 智能体页（{@code /multi-agent}）：成员 / 系统两块合一，顶部 tab 切换。
 *
 * <p>「成员」（{@link MembersPanel}）：圆桌上坐着谁——每位成员一张卡片（头像 + 名字 + 模型 / 备注），
 * 点进去是它自己的内部页（{@code /multi-agent/{id}}），资料、它自己那套「对话」提示词以及它的
 * 后台（心跳 / 常驻职责 / 子代理 / RAG 召回 / 任务）都在那里。
 * 「系统」（{@link SystemPanel}）：全局东西——观测台（全部 Agent 的心跳与跨 Agent 的最近执行）/
 * 工具一览 / 开发者选项提示词。每位 Agent 自己的后台不在这里，而在成员内部页的「后台」tab。
 *
 * <p>圆桌不在此页：它有自己的独立页（{@code /roundtable}）。
 *
 * <p>原来的「潜意识」独立页（{@code /subconscious}）现在作为本页的「系统」tab 打开；
 * 原来的「多智能体」页作为「成员」tab。
 *
 * <p>呈现全部来自 PageKit：同一份成员数据，在 flat 下是描边卡网格、
 * 在 Endfield 下是带发光角标的切角仪表块、在 BlueArchive 下是糖果卡窄栏流。
 */

import { AppIcon } from '../components/AppIcon'
import { useCallback, useEffect, useState } from 'react'
import { ApiError, getSettings, listMaPeers, type MaPeer } from '../api/client'
import PageBoundary from '../components/PageBoundary'
import { AgentAvatar, AgentRole } from '../components/AgentPresence'
import { presenceLabel, useAgentOverview } from '../api/agentPresence'
import Toast from '../components/Toast'
import { usePageKit } from '../kit'
import SystemPanel from './SystemPanel'
import { CORE_AGENT_ID, sortAgentPeers } from '../api/agentRuntime'

type Option = { value: string; label: string }

/** 顶部一级 tab：成员 / 系统 */
type HubTab = 'members' | 'system'

const HUB_TABS: { key: HubTab; label: string }[] = [
  { key: 'members', label: '成员' },
  { key: 'system', label: '系统' },
]

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

/**
 * 成员名册：卡片墙 + 新建入口。页头由外层 hub 提供，这里只出正文。
 */
function MembersPanel({ peers, modelOptions }: { peers: MaPeer[]; modelOptions: Option[] }) {
  const kit = usePageKit()
  const { data: overview, stale } = useAgentOverview(true)
  const activity = new Map(overview?.agents.map(a => [a.id, a]))
  return (
    <>
      <kit.Toolbar left="统一管理智能体的模型、提示词与能力。默认 Agent 在编程 IDE 中自动启用编程 Skill。" />

      <PageBoundary>
        {/*
          成员名册固定三列（lg 及以上）：这是刻意选的密集排布——成员往往有十几位，
          三列能一屏放下更多卡片。请勿「优化」成双列：kit.panelGrid 在 flat / endfield
          主题下就是双列（lg:grid-cols-2，bluearchive 则是单列），这里刻意用
          lg:grid-cols-3 覆盖它，统一三列是产品决定，不是遗漏。
        */}
        <div className={`${kit.panelGrid} lg:grid-cols-3`}>
          {/* 跨行、跨主题统一高度；文字放大或调整间距时仍可滚动访问内容。 */}
          <kit.Tile href={agentHref('new')} dashed className="h-48 overflow-y-auto">
            <AppIcon name="plus" className="h-5 w-5" />
            <span className="text-[1rem] sm:text-[0.875rem]">新建成员</span>
          </kit.Tile>

          {peers.map((p) => (
            <kit.Tile key={p.id} href={agentHref(p.id)} className="h-48 overflow-y-auto">
              <div className="flex items-start gap-3.5">
                <AgentAvatar
                  name={p.name}
                  avatar={p.avatar}
                  size="h-12 w-12"
                  textClass="text-md"
                  working={activity.get(p.id)?.working}
                  stale={stale}
                />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <p className="ui-heading min-w-0 truncate text-md" title={p.name}>{p.name}</p>
                    {p.builtin && <span className="shrink-0"><kit.Badge>内置</kit.Badge></span>}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1">
                    <AgentRole role={p.role} color={p.roleColor} />
                    <span className="text-xs leading-4 text-muted">{presenceLabel(activity.get(p.id)?.working, stale)}</span>
                  </div>
                  <p className="mt-1 truncate text-[0.75rem] leading-[1.125rem] text-hint">{p.id === CORE_AGENT_ID ? '对话与编程共用 · IDE 启用编程 Skill' : '提示词 · 每位成员独立一套'}</p>
                  <p className="mt-0.5 truncate text-[0.75rem] leading-[1.125rem] text-hint" title={labelOf(modelOptions, p.modelId ?? '')}>
                    模型 · {labelOf(modelOptions, p.modelId ?? '')}
                  </p>
                  {p.note && (
                    <p className="mt-1.5 line-clamp-2 text-[1rem] leading-[1.375rem] text-muted sm:text-[0.875rem]" title={p.note}>
                      {p.note}
                    </p>
                  )}
                </div>
              </div>
            </kit.Tile>
          ))}
        </div>
      </PageBoundary>
    </>
  )
}

/**
 * 智能体统一页：成员 / 系统两块用顶部 tab 切换。
 * initialTab 决定默认打开哪一块（{@code /subconscious} 路由以 system 打开本页）。
 */
export default function MultiAgent({
  initialTab = 'members',
}: {
  initialTab?: HubTab
} = {}) {
  const kit = usePageKit()
  const [tab, setTab] = useState<HubTab>(initialTab)
  const [peers, setPeers] = useState<MaPeer[]>([])
  const [modelOptions, setModelOptions] = useState<Option[]>([])
  const [toast, setToast] = useState<string | null>(null)

  const reloadPeers = useCallback(async () => {
    try {
      setPeers(sortAgentPeers(await listMaPeers()))
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
      title="智能体"
      en="AGENTS"
      meta={<span className="text-sm text-muted">{peers.length} 位成员</span>}
    >
      {/* 顶部一级 tab：成员 / 系统（选中 = 主行动钮，未选中走安静钮） */}
      <div className="flex w-fit shrink-0 flex-wrap items-center gap-1.5">
        {HUB_TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setTab(t.key)}
            className={`ui-btn ui-btn--sm ${tab === t.key ? 'ui-btn--primary' : 'ui-btn--quiet'}`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'members' && <MembersPanel peers={peers} modelOptions={modelOptions} />}
      {tab === 'system' && <SystemPanel />}

      <Toast message={toast} kind="error" onClose={() => setToast(null)} />
    </kit.Page>
  )
}
