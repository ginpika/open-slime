# 超级助手 · Agent 运行时架构设计（草案）

> 状态：**草案（持续修订）**。本文只定方向与步骤，不含实现细节。进度见各 STEP 下的「进度」注记。
> 命名约定：步骤统一用 `STEP 1 / STEP 2 / …`，不使用阶段编号。
> 适用范围：`minis` 后端（`backend/`）。**最新一轮（STEP 7）的命名与结构已是当前代码基线的真实状态**。
> 最近一次修订：STEP 7「语义归一：成员即 Agent，潜意识即心跳后台」——见下。

---

## 1. 背景与目标

- 定位：**个人私有化**的超级助手，单用户（M0），自开发自用，无商业化约束。
- 现状：已有前台会话、智能体名册（内置 Agent + 自建成员）、圆桌群聊、心跳后台与常驻职责、匿名子代理、知识库检索、记忆体系。
- 目标：功能面还会持续扩张，**最终内置一个 AI IDE**。
- 硬约束：
  - 自付 API 成本 → 每个新增环节都要算 token 与延迟。
  - 单机部署（SQLite）→ 不走分布式，但需要本地持久化与隔离。
  - 可靠性优先于功能数量 → 不可逆操作（写库 / 改文件 / 执行命令）必须有护栏。

---

## 2. 现状架构

运行时全在 `agent/` 下，按职责分层（`ai/core` 是更底层的工具 / 提示词 / LLM 契约）：

| 包 | 职责 |
|---|---|
| `agent.chat` | 前台会话。`ChatService` 已收为纯门面，执行体是 `ChatTurnRunner`；`ChatExecution` 是可替换点接口（圆桌复用它），`ChatTaskRunner` 管会话任务生命周期 |
| `agent.member` | 智能体名册。`AgentDirectory` / `AgentStore`（`agent` 表，含内置 Agent）、逐成员提示词（`AgentPromptService` / `AgentPromptStore`）、图谱人格锚点（`AgentAnchorService`） |
| `agent.roundtable` | 圆桌。`RoundtableChatService` / `RoundtableTopicService` / `TopicStateMachine` / 发言策略 / 会话与话题 store |
| `agent.heartbeat` | 心跳后台。`HeartbeatService`（心跳主会话调度 + 常驻职责名册）、`HeartbeatInjectors`、观测台 DTO |
| `agent.subagent` | 匿名子代理 worker：`SubagentService` / `SubagentSynthesizer` / `ScratchpadStore` |
| `agent.runtime` | 执行与任务。`AgentRuntime`（唯一的模型往返 + 工具批循环）、`AgentRunner`、`TaskStore` / `ExecStore` / `ChangeSetStore` / `TodoStore` |
| `agent.context` | 上下文平面。`ContextBuilder` + 各路 `ContextProvider`，按 `ContextRequest.Surface`（`CHAT / SUBAGENT / HEARTBEAT`）早退 |

**四条流**（共用同一个执行器 `AgentRuntime` 与同一张 `task`）：

| 流 | 入口 | 说明 |
|---|---|---|
| 前台会话 | `ChatTurnRunner` | 单循环工具调用，SSE 流式，支持停止 |
| 子代理 | `SubagentService` | 匿名 worker，独立上下文、只回结论、对话侧非阻塞 |
| 常驻职责 | `HeartbeatService` | 定时唤醒内置 Agent，按名册派活，可投递消息到会话 |
| 圆桌 | `RoundtableChatService` | 多智能体群聊，实现 `ChatExecution`，把可替换点交给同一个执行器跑 |

**智能体是一等公民**：`agent` 表是所有「具名智能体」的唯一实体。**内置 Agent**（`builtin=1`、固定 id `builtin`、由 `AgentSeeder` 启动播种、不可删除）承载首页对话与会话调度；人在「智能体」页自建的成员与之同表。逐成员提示词存 `agent_prompt_*`，未配的位回退全局提示词——**内置 Agent 的提示词就是全局提示词**（见 STEP 7）。

存储（SQLite）：`hub_session` / `chat_message` / `chat_turn` / `task` / `task_artifact` / `changeset(_item)` / `agent` / `agent_prompt_*` / `heartbeat` / `heartbeat_plan` / `heartbeat_run` / `roundtable_topic(_event)` / `subagent_scratch` / `memory_event` / `rag_recall` …

主要问题（与改造直接相关，多数已由后续 STEP 解决）：

1. **「一次执行」不是实体** → 已解：`task` 成为一等公民（STEP 1 / STEP 6-②）。
2. **上下文组装混在业务里** → 已解：抽成 `agent.context` 上下文平面（STEP 2 / STEP 6-③）。
3. **能力是一张平铺的工具表** → 部分解：`CapabilityRouter` + `CapabilityGuard` 分层路由与鉴权（STEP 3）。
4. **产物不可回滚** → 部分解：写操作收进 `changeset(_item)`（STEP 1）；diff / 回滚 UI 待 STEP 5。

---

## 3. 目标方向

**从"三个流"收敛成"三个平面 + 一个一等公民"。**

```
┌──────────────────────────────────────────────────────────┐
│ 交互层   会话 · 工作台 · IDE 面板（编辑器 / diff / 看板）  │
├──────────────────────────────────────────────────────────┤
│ 编排层   Task 一等公民：状态机 · 角色/编制 · 预算 · 嵌套    │  ← 地基
├──────────────────────────────────────────────────────────┤
│ 能力平面 工具注册 · 分层路由 · 权限与沙箱 · MCP            │
├──────────────────────────────────────────────────────────┤
│ 上下文平面 多源上下文 · 压缩 · 注入 · 预算                 │
├──────────────────────────────────────────────────────────┤
│ 执行平面 AgentRunner · 沙箱执行 · 变更集（可 diff/回滚）   │
├──────────────────────────────────────────────────────────┤
│ 底座     SQLite · 事件流 · trace / 评测 / 回放             │
└──────────────────────────────────────────────────────────┘
```

一句话概括：**把 agent 的动作对象从"知识库"扩展到"代码库 + 运行时"，把"执行"从函数调用提升为可观测、可恢复的任务。**

设计原则：

1. **骨架先行，但每步都能跑**：不做 big-bang 重写，用绞杀者模式逐步替换，旧行为零变化是硬指标。
2. **抽象要服务于真实需求**：只有能解决已遇到的具体问题才加（例如"要不要 ToT/LATS"→否）。
3. **不可逆操作必须有护栏**：写入与执行走变更集 / 沙箱，只读能力优先开放。
4. **成本可见**：每个任务带 token 预算，超限自动压缩或收尾，而不是无限重试。

---

## 4. 默认决策（可推翻）

| 决策点 | 默认选择 | 理由 |
|---|---|---|
| 落地路径 | 先立骨架 | 目标含 AI IDE，返工代价远大于前期投入 |
| 执行模型 | 持久化任务队列 + 常驻 worker | IDE 长任务必须可恢复，进程内线程做不到 |
| 沙箱边界 | 分层：读本机 / 写与执行进容器 | 复用已有 Docker 执行基础，风险可控 |
| 首战目标 | Task 地基 + 上下文平面并行 | 骨架要立，但纯骨架没体感，配一条上下文线才跑得动 |
| 圆桌定位 | 运行时不合并，等 Task 稳定后再归一 | 发言 / 状态机语义与子代理不同，硬合并互相拖累；STEP 7 只做了概念与页面归一 |

---

## STEP 7 · 语义归一：成员即 Agent，潜意识即心跳后台

> **进度：已落地。** `mvn test` 112 项全绿；前端 `tsc + vite build` 通过。
> 取舍：**DB 可破坏**——旧库直接弃用，历史迁移器全部删除，启动即按 `schema.sql` 建全新结构。

**动机**：最早是「单 Agent 架构」——首页对话用一份可切换的人格提示词模拟出不同助手，而「潜意识」是它的后台心跳。如今已明确采用**多 Agent 架构**：具名智能体才是一等公民。故把那条单 Agent 落成一个**内置 Agent**，并把「多智能体成员」「潜意识后台」两块语义归一，前端两个页面合体。

**① 内置 Agent（核心）**——`agent` 表新增 `builtin` 标志（`1`=内置、默认存在、不可删）。`AgentSeeder` 启动播种固定 id `builtin` 的内置 Agent；`AgentDirectory.delete` 拒绝删除它。它同时承担**首页对话应答**与**心跳调度**：首页对话读的就是全局提示词，而 `AgentPromptService` 对内置 Agent 的读写一律路由到全局——于是**「改内置 Agent 的人设」= 改首页对话**，二者不再分叉。

**② 实体与表归一**——
- `ma_peer` → `agent`（加 `builtin`，去遗留列 `prompt_id`）；
- `ma_peer_prompt_version` / `_binding` / `_order` → `agent_prompt_*`（列 `peer_id`→`agent_id`）；
- `sub_heartbeat` → `heartbeat`、`sub_plan` → `heartbeat_plan`、`sub_run` → `heartbeat_run`、`sub_scratch` → `subagent_scratch`；
- `ma_topic` / `ma_topic_event` → `roundtable_topic` / `roundtable_topic_event`；
- `task` 去掉退役列 `exec_id`（`execId == taskId`）。

**③ 包重组**——`agent.multiagent` 拆为 `agent.member`（名册 / 提示词 / 锚点）与 `agent.roundtable`（会话 / 话题 / 状态机 / 发言）；`agent.subconscious` → `agent.heartbeat`。类名同步：`Peer*`→`Agent*`、`Ma*`→`Roundtable*`、`Subconscious*`→`Heartbeat*`；`SubconsciousTool`→`HeartbeatTool`；工具包 `ai.core.tool.subconscious`→`ai.core.tool.heartbeat`；枚举 `ContextRequest.Surface.SUBCONSCIOUS`→`HEARTBEAT`。

**④ 接口与配置归一**——`/api/multi-agent/**`→`/api/agents/**`（`AgentController`）、`/api/subconscious/**`→`/api/heartbeat/**`（`HeartbeatController`）；配置前缀 `minis.subconscious`→`minis.heartbeat`、`MultiAgentProps`→`RoundtableProps`（前缀 `minis.roundtable`）；token 用量来源 `subconscious`→`heartbeat`；记忆事件正本表名 `sub_exec`→`task`。

**⑤ 删除历史迁移器（DB 可破坏）**——移除 `HubSessionMigrator` / `ChatMessageMigrator` / `MultiAgentMigrator` / `MultiAgentMergeMigrator` / `SubconsciousMigrator` / `ExecMergeMigrator` / `PromptMigrator` / `LlmModelMigrator` / `ChunkDocMigrator` 及对应测试，并清理相关 `@DependsOn` 与遗留列（如 `ma_peer.prompt_id`）。今后结构变更若有破坏性，优先直接改 `schema.sql`。

**⑥ 前端合体**——去掉 `/subconscious` 导航项，归一为**一个「智能体」页**（`/multi-agent`），内部三个 tab：**成员 / 圆桌 / 后台**（「后台」即原潜意识页的七个子页）。旧 `/subconscious` 入口落到「后台」tab；首页「群聊」仍走 `/roundtable`；成员卡片与详情页对内置 Agent 标注「内置」且无删除入口。

> 与 §4「圆桌暂不合并」的关系：本次仍**不做**圆桌与子代理的**运行时**合并（各走各的 `ChatExecution` 落点、各落各的消息表），只做**概念与页面**的归一。

---

## STEP 6 · 归一：一次会话、一次执行各自只有一个落点

> **进度：三刀已落地。** `mvnw test` 114 项全绿；启动已验证（构造环用 `@Lazy` 打破）；
> 未在真实旧库上跑「去插槽」迁移（改完要重启后端）。
>
> **命名注记（历史）**：本节的类名 / 表名是当时的命名，已在 STEP 7 归一——
> `Ma*` → `Roundtable*`、`Subconscious*` → `Heartbeat*`、`Peer*` → `Agent*`、
> `sub_heartbeat / sub_plan / sub_run` → `heartbeat / heartbeat_plan / heartbeat_run`、
> `ma_topic*` → `roundtable_topic*`、枚举 `Surface.SUBCONSCIOUS` → `Surface.HEARTBEAT`；
> 旧库回填用的一次性迁移器已随 STEP 7 一并删除。
>
> **① 会话与消息归一**——`ma_session` → `hub_session`（`mode='multi-agent'` + `member_ids` 名册）、
> `ma_message` → `chat_message`（新增 `topic_id / peer_id / peer_name / context / event_seq`）、
> `ma_topic.session_id` 外键改指 `hub_session`。圆桌那两个 store（`MaSessionStore` / `MaMessageStore`）
> **只换内部 SQL、API 不变**，所以 `MaChatService` / `MaTopicService` / 控制器一行未动；首页列表按
> `mode='ai'` 过滤，圆桌查询钉住 `mode='multi-agent'`。（当时用迁移器回填旧库；STEP 7 起 DB 可破坏，回填逻辑已删除。）
>
> **② 执行归一**——`sub_exec` 的过程列（`run_id / plan_id / context / reasoning / trace / tool_rounds /
> error / started_at / finished_at`）并入 `task`；另加 `task.origin`（heartbeat | plan | agent）——
> 资源页的三个用量桶靠它分类，只靠 `task.kind` 会把心跳与计划并成一类。写侧随之合一：
> `AgentRunner.openExec` **一步建出那行 task**（`TaskStore.beginExec`），调用方不再 `tasks.begin` /
> `tasks.finish`；`execId` 即 `taskId`，故 `taskIdOfExec` / `attachExec` 删除，变更集与产物按同一个 id 记。
> 写库清单改读 `changeset_item`（旧表 `sub_exec_edit` 的 `exec_id` 外键不再成立）。
> 批次表 `sub_run` 保留（STEP 7 更名 `heartbeat_run`）；`sub_exec` / `sub_exec_edit` 及搬迁用迁移器已删除。
>
> **③ 上下文强合（已落地）**——三条流都从 `ContextBuilder` + `ContextProvider` 组装，
> 心跳 / 计划的 `{{插槽}}` 设计已移除。
> 1. `ContextRequest` 加 `surface`（`CHAT | SUBAGENT | SUBCONSCIOUS`）并补「不写 surface 按 CHAT」的
>    便捷构造——现有调用点与测试**一行未改**；`ContextBuilder` 补 `build(req, extras)` 重载（面专属、
>    带本次数据的 provider 走这里），并在 system 全空时不落 system 消息（role=user 的计划不再凭空多
>    一条空 system）。子代理面另有全局 `SubagentContextProvider`。
> 2. 五路现有源按 surface 早退：`History / KnowledgeProvider / MemoryProvider / ActiveSubagentProvider /
>    TodoProvider` 只服务 `CHAT`，行为与今天逐字一致；圆桌那条流仍走自己的 `PeerContextBuilder`（不并入）。
> 3. 子代理流：`SubagentService.messages()` 的手拼换成 `ContextBuilder.build(...)`；黑板与「当前时间」
>    收进 `SubagentContextProvider`（`SUBAGENT` 面，按 `sessionId` 取黑板）。`SubagentService` 注入
>    `ContextBuilder` 与 `ActiveSubagentProvider → SubagentService` 构成构造环，用 `@Lazy` 打破。
> 4. 心跳 / 计划流：素材改由 `SubconsciousMaterialsProvider`（`SUBCONSCIOUS` 面）现取、按绑定顺序追加
>    （每段以注入器名作小标题），`SubconsciousContextBuilder` 只负责组装次序与 role 分流（role=user 时
>    正文+素材+交代并进同一条 user 消息）。绑定从 `{slot, injector, args}` 降为 `{injector, args}`；
>    `create_plan` / `update_plan` 新增 `injectors` 参数（模型显式声明素材，取代原先「按正文里的
>    `{{插槽}}` 名自动绑定」）；`SubconsciousInjectors.load` 改收 `(injector, args, planContent, planInput)`。
> 5. 数据迁移：`SubconsciousSeeder` 加一步一次性「去插槽」——`sub_heartbeat.content` /
>    `sub_plan.content` / `input` 去掉 `{{...}}` 标记，`injectors` JSON 降级为 `{injector, args}`
>    （按注入器 + 参数去重），幂等。
> 6. 前端：`InjectorBinding` 去掉 `slot`；heartbeat 页绑定行改「注入器 + 参数」；「插槽」子页更名「注入器」。
> 回归见 `SubagentContextProviderTest` / `SubconsciousMaterialsProviderTest` / `SubconsciousSeederTest`，
> 以及 `ContextInjectionProviderTest` 新增的「非 CHAT 面早退」用例。

## STEP 1 · 地基：让 Task 成为一等公民

> **进度：已验收。** 最小切片已落地并回归通过——`task` / `task_artifact` / `changeset` / `changeset_item` 建表；`TaskRunner` 接口抽出、`AgentRunner` 降为其实现；会话 / 子代理 / 心跳三条旧流照常（子代理那条真正落在 Task 上）；启动时残留的 `running` 置为 `interrupted`（可见、可重跑）；写操作同时收进变更集（`changeset`；旧表 `sub_exec_edit` 已退役）。回归见 `TaskStoreTest` / `ChangeSetStoreTest`。
> 与下方「交付」的差异：`ChatTaskRunner` 只收会话任务的生命周期（身份 / 状态 / 恢复），**循环不在它那里**——循环已收进唯一的 `AgentRuntime`（四条流共用），会话这条流的落点在 `ChatTurnRunner`（见 STEP 5）。

**目标**：把"一次执行"抽象成有身份、有状态、有预算、可嵌套、可恢复的实体。

**交付**

- 新表 `task`：`id / parent_id / kind / status / workspace_id / model_id / budget / created_at / updated_at`
  - `kind` 取值：`session`（前台会话）、`subagent`、`plan`（心跳编制）、`code`（IDE 任务）——四者统一。
  - `budget`：token / 轮数 / 时长上限，随任务携带。
- 状态机：`pending → running → (suspended) → done / failed / cancelled`。
- 启动恢复：进程启动时把残留的 `running` 置为 `interrupted`，支持重跑或续跑。
- `TaskRunner` 接口：`AgentRunner` 降级为其中一种实现；`ChatService.runChat` 变成 `ChatTaskRunner.execute`（**循环逻辑原样搬运，不重写**）。
- 新表 `task_artifact`：任务产物（结论文本、生成的文件、检索片段引用等）。
- 新表 `changeset` + `changeset_item`：所有写操作（文件 / 图谱 / 文档）进变更集，为 diff / 回滚铺路。

**验收**

- 三条旧流（会话 / 子代理 / 心跳）行为零变化，全部走适配层。
- 杀掉进程重启后，在途任务显示为"已中断、可重跑"，而不是消失或永久 `running`。
- 一条任务可以挂子任务，父子关系可查。

**依赖**：无（这是第一块）。

---

## STEP 2 · 上下文平面

> **进度：部分完成（等效抽取已落地；知识 / 记忆两路 provider 已接入但默认关）。** 已落地——`ContextBuilder` + `ContextProvider` 接口，把 `ChatService.requestBody` 原样搬进上下文平面，`rawContext` 预览与实际发送共用同一条路径，行为零变化（回归见 `ContextBuilderTest`）；已接入五路源：`History`（会话历史回放，含 `context_trace` 原生协议回放、microcompaction 截断与逼近窗口才裁历史）、`ActiveSubagentProvider`（在跑子代理进度）、`TodoProvider`（待办清单）、`KnowledgeProvider`（RAG-first：按本次输入预召回 chunk + 图谱证据）与 `MemoryProvider`（按本次输入预召回相关记忆）。后两者是行为变更，由 `minis.context.inject-knowledge` / `inject-memory` 控制、**默认关**（关时零行为变化，回归见 `ContextInjectionProviderTest`）。
> **未完成**——① 代码库 / 文件树 provider（为 IDE 预留，待 STEP 5）；② 上下文压缩改为**逼近窗口才裁历史**（不做摘要：先从最早的轮次整条丢、仍超再截断长思考与工具结果），**已落地**（见 `agent.chat.turn.History`，回归见 `HistoryTest`）；③ Task 预算字段已建但**未参与**上下文截断。
> 因此本节验收项「长会话（超阈值）自动摘要生效」**已按「逼近窗口自动裁历史」实现**（不做有损摘要）；「抽出前后 raw-context 逐字一致」已满足。

**目标**：把"组装 messages"从业务代码里抽出来，成为独立子系统。

**交付**

- `ContextBuilder` + 多源 provider 接口：
  - 会话历史 provider
  - 知识检索 provider（RAG-first：进模型前默认注入 top-k，而不是纯靠模型主动调工具）
  - 记忆 provider（默认注入相关记忆，而不是等模型主动 recall）
  - 代码库 / 文件树 provider（为 IDE 预留）
- 压缩策略：
  - 阈值触发历史摘要（保留最近 K 轮 + 滚动摘要）
  - 升级现有 microcompaction（`clipRound`）为通用工具结果压缩
- 预算控制：token 预算由 Task 携带，超限自动压缩或截断（不再只是前端展示 `contextPct`）。
- 等价抽取：先把 `ChatService.requestBody()` 的实现原样搬进 `ContextBuilder`，**保证行为零变化**，再逐步插 provider。

**验收**

- 抽出前后，同一会话的 `raw-context` 预览逐字一致（可用 `GET /api/sessions/{id}/raw-context` 对比）。
- 长会话（超阈值）自动摘要生效，且历史可读性不崩。

**依赖**：STEP 1 的 Task 预算字段。

---

## STEP 3 · 能力平面

> **进度：首版已落地（核心交付的两条验收项已满足，沙箱 / MCP / IDE 工具后置）。**
> 已落地——① **能力注册**：`AiTool` 补 `risk()`（只读 / 写入 / 执行，见 `ToolRisk`；默认由 `writes()` 推得，`run_command` 显式声明为执行），`ToolDomain` 补「终端」域并预留 IDE 域（fs / editor / lsp / git / test）；② **分层路由**：`CapabilityRouter` 按一句话选域、`ToolManager#toolsOfDomains` 按域取定义，首页对话的工具面由「CHAT 面全量常驻」改为「按域注入」（闲聊只带控制类工具）；③ **权限模型**：`CapabilityGuard` 在执行前按「域 + 只读」复核每次工具调用，越权返回可读理由（不落执行）。
> 未落地（与下方「交付」的差异）——④ MCP 接入（后端暂无 MCP 客户端，外部能力仍各写各的，待单起一步）；⑤ IDE 工具本体（fs / editor / lsp / git / test 仅预留域，无工具落上去）；⑥ 容器写入沙箱（按 §7-4「沙箱别一步到位」后置到真正改代码时）；⑦ `workspace` 维度的越权**未由能力平面新做**——仍由 `TerminalService` 的逐目标开关与授权名单（`allowedTargetIds`）执行并返回可读原因，与域鉴权叠加即「Capability + workspace」。圆桌 / 子代理 / 心跳三条旧流**不接**能力平面（`capabilityTracked=false`），授权走各自既有链路，保证旧行为零变化。
> 验收对照：**「闲聊不再携带全套工具 schema」已满足**（闲聊请求从 24 件工具降到仅控制类 6 件，规则见 `CapabilityRouter`，回归见 `CapabilityRouterTest`）；**「越权调用被拒且理由可读」中「未授权 domain」已满足**（回归见 `CapabilityGuardTest`），「越出工作空间的写」沿用既有终端授权（已返回可读原因）。

**目标**：把工具从"平铺清单"升级为"可路由、可授权、可隔离"的能力体系。

**交付**

- Capability 注册：在现有 `AiTool` 上补 `domain`（如 `knowledge / graph / terminal / memory / fs / editor / git / test`）与风险等级 `read / write / exec`。
- 分层路由：先选 domain（规则或轻量路由），再注入该 domain 的 schema，替代 `CHAT` 面全量常驻。
- 权限模型：沿用并推广现有"逐目标开关 / 显式授权"的思路，统一到 Capability + workspace 上。
- 沙箱与工作区：`workspace` 绑定；分层执行（本机只读 / 容器写与执行），复用现有 Docker 执行链路。
- MCP 接入：外部能力统一走 MCP client，不再各写各的。
- 新增 IDE 能力域：`fs / editor / lsp / git / test`。

**验收**

- 一个"闲聊"请求不再携带全套工具 schema（token 明显下降，请求体可对比）。
- 越权调用（未授权的 domain / 越出工作区的写）被拒绝，且返回可读原因。

**依赖**：STEP 1（Task 携带 workspace / 权限上下文）。

---

## STEP 4 · 编排层进阶

> **进度：首版已落地（两条验收项均已满足）。**
> 已落地——① **任务分解**：新增 `write_todos` 工具（plan-of-record）+ `chat_todo` 表 + `TodoStore` / `TodoService`，清单随会话存档（覆盖式写入），并由 `TodoProvider` 每轮回灌 system——模型据此推进、不必在正文复述进度；与 `create_plan`（常驻编制）语义分明、互不替代。② **反思自检**：`AiTool` 补 `validate(args)`，新增 `ToolValidator` 在写入 / 执行前过一道<b>零成本</b>参数自检（只读放行），接入会话侧 `ChatService.runTool` 与执行侧 `AgentRunner.dispatch`；`run_command` / `create_document` / `delete_graph_item` / `write_todos` 已带实检。③ **子代理协作**：`ScratchpadStore`（会话级共享黑板，`subagent_scratch` 表）在子代理收尾时沉淀结论、注入新子代理的 system（同会话内先派后派接力、不重复查）；`SubagentSynthesizer` 把整批结论收口成一份整体交代，并在有失败时点名失败件 + 给补救建议（`SubagentBatchDoneEvent` 补 `runId`，据此取回各次成败）。④ **回流改推送**：`session_events` 表 + `SessionEventStore` + `SessionEventBus` + `GET /api/events/stream`（SSE，带 `Last-Event-ID` 断线补推），子代理回报与「会话答完」即时推给前端；前端新增单例订阅 `sessionEvents.ts`，`ChatNotifier` 的 5s 与 `Home` 的 4s 轮询已删除。⑤ **在途锁 + 幂等**：`ChatService` 加同会话在途锁（重复触发返回 409、不落库），不再单靠前端 `sendingRef`；`SubagentService.rerunTask` 对在途任务拒绝重跑。
> 未落地（与下方「交付」的差异）——「共享黑板」只做「结论沉淀、后人可读」，不做并发内的实时共识（那要靠分布式锁与消息，与单用户 M0 的取舍不符）；`write_todos` 归属 GENERAL 域，故「闲聊携带的控制类工具数」由 STEP 3 的 6 变 7。
> 验收对照：**「一个多步子任务能被拆解、跟踪、并在其中一个失败时给出可读的收尾」已满足**（拆解与跟踪由 `write_todos` + `TodoProvider` 承担，失败可读收尾由 `SubagentSynthesizer` 承担）；**「子代理结论到达时前端即时更新（无轮询延迟）」已满足**（SSE 推送取代轮询）。
> 回归见 `TodoStoreTest` / `ToolValidatorTest` / `ScratchpadStoreTest` / `SessionEventBusTest`；全量 `mvn test` 80 项通过。

**目标**：把编排能力补到"能稳定干长活"。

**交付**

- 任务分解：新增 plan-of-record（待办列表）工具。
  - **注意**：这是"任务分解"，与现有 `plan`（常驻角色编制）语义不同，二者不要混用或互相替代。
- 反思自检：在**写入 / 执行前**插校验节点（validator），不给所有回答统一加自检（否则每轮多一次模型调用）。
- 子代理协作：共享黑板（scratchpad）+ synthesizer（把多个子代理结论汇总成一份）。
- 回流改推送：用事件总线 / SSE 替代前端 4s 轮询。
- 后端在途锁 + 幂等：同一 Task 重复触发不再靠前端 `sendingRef` 挡。

**验收**

- 一个多步子任务能被拆解、跟踪、并在其中一个失败时给出可读的收尾（而不是整轮丢弃）。
- 子代理结论到达时前端即时更新（无轮询延迟）。

**依赖**：STEP 1（Task 树是任务分解的载体）。

---

## STEP 5 · IDE 落地

**目标**：打通"编辑器 ↔ agent ↔ 运行时"的最小闭环，并逐步扩面。

**交付**

- 工作台视图：任务看板 + 执行轨迹 + diff 审阅。
- 变更集 UI：所有写操作可视化 diff、可回滚、可按任务撤销。
- 编辑器内联 agent：选中代码 → 生成 Task（解释 / 重构 / 测试）。
- 长任务（索引 / 构建 / 测试）以后台 Task 形式运行，可挂起、可续看。

**验收**

- 一次"改代码"的任务，全程可 diff、可一键回滚；进程重启不丢任务。
- 不做任何破坏性操作（默认只读 + 显式确认）。

**依赖**：STEP 1–4。

---

## 6. 与现有代码的衔接（绞杀者模式）

原则：**新结构包住旧结构，读时双写，稳定后切读**；不删旧代码直到新路径验证通过。
（STEP 1–7 已落地，下表为**结果**。`sub_*` / `ma_*` 旧表与旧命名，在 STEP 7 随「DB 可破坏」一并清除。）

| 原（改造前） | 现（当前基线） | 手法 |
|---|---|---|
| `hub_session` / `sub_exec` | `task`（一次执行一行，`origin` 记来源） | 先双写、稳定后切读，最终删除 `sub_exec` |
| `AgentRunner.run()` 直接调用 | `TaskRunner` / `ChatExecution` 接口 | 抽接口，`AgentRunner` / `ChatTurnRunner` 为实现 |
| 前台会话循环散在业务里 | `AgentRuntime`（唯一循环）+ `ChatTurnRunner` | 循环收进内核，各流只备素材 |
| `ToolSurface.CHAT` 全量常驻 | `CapabilityRouter` + `CapabilityGuard` 分层 | 按域路由 + 执行前鉴权 |
| `ChatService.requestBody()` | `agent.context` 上下文平面 | 等价抽取后插 provider |
| `execs.insertEdit` | `changeset` / `changeset_item` | 写操作结构化、为 diff / 回滚铺路 |
| `ma_peer` 等旧命名 | `agent` / `agent_prompt_*` / `heartbeat_*` / `roundtable_topic*` | STEP 7 语义归一（DB 可破坏） |

**不做**：不一次性重写，不合并圆桌与子代理的**运行时**，不引入分布式基础设施（Postgres / 消息队列）。

---

## 7. 关键取舍与风险

1. **最大风险是"半途重写"**：因此每个 STEP 的验收都必须包含"旧行为零变化"，否则会掉进长期不可用状态。
2. **SQLite 够不够**：单机单用户继续用 SQLite 没问题；"持久化队列 + worker"若跨进程，需要本地文件锁或 IPC，不要过早换数据库。
3. **不为架构而架构**：`Tree-of-Thoughts / LATS`、多模型聚合（MoA）、通用 DAG 引擎——**不做**。注意 `Task` 树 ≠ 画布式 DAG。
4. **沙箱别一步到位**：STEP 3 先"工作区目录隔离 + 只读优先"，容器写入等 IDE 真正开始改代码时再上。
5. **成本护栏要早**：预算与熔断在 STEP 1 就进 Task 模型，避免后期到处补。

---

## 8. 首个里程碑（STEP 1 的最小切片）

**目标**：一次子代理派活，从"进程内虚拟线程"变成"一条可恢复的 Task"。

**交付**

- `task` 表 + 最小状态机。
- `TaskRunner` 接口，`AgentRunner` 实现它（行为等价）。
- 进程重启后，`running` 的任务标记为 `interrupted`，前端可见并可重跑。
- 旧的三条流全部照常工作（回归验证）。

**为什么从这个切片开始**：小、可验证、且一旦落地，STEP 2–5 都是往上挂，不用再动骨架。

---

## 9. 待决问题（需要拍板）

1. **执行模型**：持久化任务队列 + 常驻 worker，还是先用进程内线程把接口立起来、队列后置？（**现状：进程内虚拟线程**，队列未上。）
2. **沙箱首版**：只做"工作区目录隔离 + 只读"，还是同步把 Docker 写入通道接上？（未决。）
3. **上下文压缩的激进程度**：只做"阈值触发摘要"，还是连"主动 RAG-first 注入"一起做？（**已定：逼近窗口才裁历史、不做有损摘要**；知识 / 记忆注入默认关。）
4. **圆桌是否纳入统一 Task**：现在就归一，还是等 STEP 4 之后再说？（**仍未纳入运行时**；STEP 7 只做了概念与页面归一。）
5. **调度载体**：常驻 worker 用独立线程组，还是独立进程 / daemon（隔离更强但工程量更大）？（未决。）

---

（本文为草案，收到审阅意见后按结论修订。）
