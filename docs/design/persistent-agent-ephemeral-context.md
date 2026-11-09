# Minis Agent 动态上下文改造与交接

2026-11-09 · P1 至 P4 单聊试点已接入 · P3、P4 待验证

## 给接手会话

这次改造针对 Minis 的 **Java Personal Agent 通用运行时**。长篇角色扮演是验证用例，和长文审阅、资料调研、代码排障一样，用来检验同一个上下文机制。游戏规则、角色数值和地图渲染等属于应用或工具层。

设计阶段完成了仓库只读审计和方案讨论。下文使用 `ContextFragment`、`ContextPlan`、`ContextManifest`、`ContextScope` 等名称描述设计。审计时 `main` 指向 `97511a9`，单聊、圆桌、运行时、子代理和前端均有既有工作树改动。接手时重新检查 `AGENTS.md`、`git status` 和相关差异，以实际代码为准。

下面是 P1 开工时的原始交接指令。P1、P2 的实际用法见各阶段说明。

> 请阅读 `docs/design/persistent-agent-ephemeral-context.md`，继续 Minis 的 Java Personal Agent 通用动态上下文改造。本次只做 P1 的个人单聊试点。先检查当前工作树和真实调用链，保护既有改动，再复用现有 ContextProvider、ContextBuilder 和 ModelLoop，实现完整请求预算、带权限的来源回读及实际出站 Manifest。按文档报告验证结果与剩余风险，完成 P1 后停在可审查的结果。P2 及后续阶段另行确认。

## 问题与目标

长期记忆可能把过期事实、已被纠正的偏好、某次任务里的临时身份或决定带到其他任务。长历史也可能让旧指令与当前任务竞争，或让大量相似的正文、日志和失败重试影响后续输出。不同漂移有不同成因，验证时应把历史长度、内容构成、权威性和检索遗漏分别看待。

目标是 **Persistent Agent, Ephemeral Context**。用户继续使用原有 Conversation 和完整转录，内部按请求选取当前所需材料，在合适的边界自动整理或重建。任务、权限、业务状态和原始证据在模型窗口之外持久存在。重要事实可回源，切换模型后也能用同一批已授权来源重组。

设计遵循以下取舍。

- 先让材料可按引用回读，再减少默认回放。先处理重复、冗长的过程材料，接近预算上限时才考虑摘要或重建。
- 用户可见、自动装载、按需读取、进入长期记忆提炼是四种独立用途。一次输出可以完整呈现给用户、在当前工作范围可见、关闭后只可按权限回读，并跳过通用记忆提炼。
- Runtime 负责必要材料、权限和总预算。Agent 可以按需检索、建议保留片段或结束阶段。检索正文、工具输出和模型摘要以数据身份进入模型，来源和信任等级随片段保留。
- 规则、当前任务契约、权威状态、检索证据和最近历史分别组织。按实际内容和模型窗口计费。System Prompt 的比例由必要指令决定。
- 转录、业务状态、执行恢复和长期记忆各自有生命周期。精确状态来自所属服务，模型推断和派生摘要需要来源及确认状态。当前用户的明确纠正应覆盖适用范围内的旧偏好。

## 通用装配模型

Layer 决定片段怎样进入请求。Scope 决定片段对哪个会话、Agent、项目或任务有效。权限、来源与信任等级独立核验。四层是请求视图，复用现有存储。

| 层 | 内容与装载 |
| --- | --- |
| 指令 | 每次带入运行规则、Agent 身份和适用的约束 |
| 任务 | 保底带入当前输入、已确认目标与约束、恢复所需的已验证状态或引用 |
| 局部工作 | 按作用域装载最近轮次、中间稿与工具往返，保留有界窗口 |
| 证据 | 少量相关线索可预选，正文、旧转录和完整产物由 Agent 按需检索，读取前再次鉴权 |

最小数据契约建议如下，先复用已有 ID、scope 和存储接口。

| 概念 | 最小职责 |
| --- | --- |
| `ContextFragment` | 标记片段的层、作用域、来源引用、信任与默认用途。需要追溯时引用稳定 ID、版本或片段范围 |
| `ContextPlan` | 在当前执行作用域下选择和排序片段，分配预算，再交给模型协议渲染。可选来源按需取材 |
| `ContextManifest` | 记录最终出站请求的选取结果，包括执行 ID、调用序号、尝试序号、来源、版本、预算和裁剪原因。Turn ID 用作关联 |
| `ContinuationSnapshot` | 保存仍有效的约束、约定、未决问题与证据引用。恢复时重新读取权威状态和现行权限 |
| `RunCheckpoint` | 保存待审批、待结果、已执行操作回执等恢复原运行需要的信息 |

Manifest 默认使用元数据和受控引用。出站后的供应商端编辑只记录可观测的事件和用量，应用侧 Manifest 的观察边界是实际提交给供应商的请求。上下文预算计入动态片段、工具 schema、协议和媒体开销，并为输出与推理预留空间。优先裁可选材料，保持原生工具调用与响应配对。当前输入或必要约束装不下时，返回可解释的预算错误。

## Conversation、Scene、SubAgent 和 Epoch

| 概念 | 边界 |
| --- | --- |
| Conversation | 稳定的用户可见入口，沿用 `hub_session` 和现有转录 |
| Turn | 一次用户输入及其执行与回复，沿用现有聊天和 SSE |
| Scene | 可选的阶段性 `ContextScope`。同一个 Agent 可跨多个 Turn 在其中执行。收尾显式带回有用结果、决定、未决项或证据 |
| SubAgent | 独立执行者。接受明确目标、必要引用、受限工具、预算与父任务 ID，通过相同分层装配自己的请求 |
| Context Epoch | 记录上下文重建的代号、水位、原因和续接引用。可在同一个长 Scene 内更新，多段短 Scene 也可以共用一代 |

短问答继续使用普通 Turn。Scene 适合用户需要看到完整过程、下一阶段却只需精选结果的工作。子代理用于可独立探索、并行或需隔离工具轨迹的工作。默认由主 Agent 负责答复。与另一个具名 Agent 交换消息或将答复责任交给它，沿用独立的身份和权限边界。

Scene 的通用结果包含三类材料。

1. **已验证结果或状态变更引用。** 业务工具拥有权威状态。需要修改时，提交有类型的操作，校验权限、规则和预期版本，再返回回执。普通写作或只读调研可没有状态变更。
2. **Continuity Memory。** 保存对后续决定有影响的约定、因果、未决项和证据引用，区分已确认事实与假设。它首先服务于任务续接，长期记忆仍通过现有治理和发布。
3. **Archive。** 引用现有用户转录、工具记录和任务产物。用户能回看原文，模型能在获授权时取回具体片段。摘要及派生引用沿用来源的访问和撤回约束。

Scene 用例包括长篇角色互动之后回到普通对话，长文审阅之后转入定稿，以及排障结束后带着根因和证据继续修改。共同的验收点是 **长过程留在转录或产物里，后续请求只自动携带继续任务所需的内容**。

## 现有代码与改造切入点

| 部位 | 审计发现与复用点 |
| --- | --- |
| 会话与轮次 | `hub_session` 是稳定会话 ID，`chat_turn` 配对触发、回复和任务。`chat_message` 同时承载转录与部分执行记录。旧会话和分支可能依赖 `History` 的回退配对。 [Schema](../../backend/src/main/resources/schema.sql) · [History](../../backend/src/main/java/cc/ginpika/minis/agent/chat/turn/History.java) |
| 装配与预算 | `ContextProvider` 直接产出 `systemSuffix` 或 `messages`。预算先扣 system 基底和工具 schema，动态 suffix 随后才拼入。零历史预算也会走全量回放。先在 `ContextBuilder` 附近接入少量结构化片段和整份请求的预算。 [ContextProvider](../../backend/src/main/java/cc/ginpika/minis/agent/context/ContextProvider.java) · [ChatContextService](../../backend/src/main/java/cc/ginpika/minis/agent/chat/ChatContextService.java) |
| 执行与观测 | `ChatTurnRunner` 和 `AgentRunner` 共享 `AgentRuntime` 与 `ModelLoop`。工具往返会使请求继续增长。保留现有循环，在实际发送边界复核最终预算和采集 Manifest。当前 raw-context 预览是重新组装的，不能代替实际请求记录。圆桌虽复用执行链，却有自己的装配路径。 [ModelLoop](../../backend/src/main/java/cc/ginpika/minis/ai/runtime/ModelLoop.java) · [ChatContextService](../../backend/src/main/java/cc/ginpika/minis/agent/chat/ChatContextService.java) |
| 记忆 | 复用 `MemoryPolicy`、时态和来源过滤、`recall_memory`、工作集及现有记忆治理。初始 `MemorySnapshot` 会随模型步骤重验和渲染，按 Turn 覆盖的键不足以记录父子调用。关闭回放的正文也要按用途检查是否进入记忆采集和提炼。现有 `memory_off` 语义接手时从代码核实。 [MemoryProvider](../../backend/src/main/java/cc/ginpika/minis/agent/context/MemoryProvider.java) · [MemoryLiveContext](../../backend/src/main/java/cc/ginpika/minis/ai/memory/MemoryLiveContext.java) |
| 子代理 | 已有独立请求、任务与产物、同步和后台执行。后台报告虽显示为摘要卡片，全文仍写进聊天历史，批次总结还会读取会话。`read_subagent_run` 的读取路径应核对会话和 Agent 归属。复用现有 runner 和产物，以结构化结果及引用替换默认全文回流。 [SubagentService](../../backend/src/main/java/cc/ginpika/minis/agent/subagent/SubagentService.java) · [SubagentDeliveryService](../../backend/src/main/java/cc/ginpika/minis/agent/chat/SubagentDeliveryService.java) |
| 状态与恢复 | 现有事件表可去重，事件总线有提交后广播路径。工具执行键主要用于追踪，启动恢复主要把在途执行标为中断并恢复工作集摘要。需要业务事务和精确续跑时，基于现有机制补齐保证。 [RuntimeEventStore](../../backend/src/main/java/cc/ginpika/minis/agent/runtime/event/RuntimeEventStore.java) · [ToolExecutionRecorder](../../backend/src/main/java/cc/ginpika/minis/agent/runtime/ToolExecutionRecorder.java) · [TaskRecovery](../../backend/src/main/java/cc/ginpika/minis/agent/runtime/TaskRecovery.java) |

## 分阶段实施与验收

### P1 · 个人单聊上下文选取、回读和观测

1. 重新追踪同步、SSE、重连、取消、用户补充输入和后续工具轮的真实调用链。先建立小规模对照用例和基线数据。
2. 让少数现有 Provider 提供结构化片段，旧 Provider 可走兼容路径。装配器预算覆盖最终请求和工具循环。必要时先用同一批材料计算新旧方案的差异，再以单聊开关灰度。影子计算避免重复召回、额外模型调用和记忆写入。
3. 在现有消息和任务产物上提供最小、可授权的来源回读，优先复用已有分页与证据读取。P1 的回读范围限当前获授权的单聊材料。诊断或模型工具接入方式按实际调用点选择。
4. 从实际出站边界记录 Manifest，沿用现有诊断入口或存储。为模型首轮、后续工具轮和重试保留独立记录，预览单独标明。

P1 验收要求如下。

- 零历史预算不会回放全量。当前输入和必带内容优先。工具配对有效，较长的动态片段与工具结果都计入预算。估算不确定时有安全余量及明确失败路径。
- 内容可从源引用精确找回。跨 Agent、跨未授权会话、来源被撤回或删除后的读取被拒绝。
- Manifest 与实际请求匹配，能区分发送尝试、历史水位、装配策略和裁剪原因。若试点产生子执行，关联父 Turn 时仍使用独立执行键。
- 转录、流式输出、重连和现有会话 ID 继续可用。旧数据仍可读，圆桌及其他入口继续走自己的装配路径。P1 灰度关闭后可回到原选取路径。
- 在两个异质用例中验证同一机制，长叙事去掉旧过程后仍承接约定，长工具输出去掉后仍能找回根因证据。具体 Scene 生命周期留到 P3。

#### P1 试点使用与验证

- 后端启动时设置 `CONTEXT_EPHEMERAL_CHAT=true`，在个人单聊启用新选取策略。默认值为 `false`。预算使用该模型配置的窗口、`CONTEXT_RESERVE_OUTPUT_TOKENS` 和估算安全余量。窗口不足时，当前输入与必带内容优先。
- `read_context_source` 在当前会话内分页列出或读取文本。消息使用 `message:<id>`，原生工具记录使用 `message:<id>:tool:<索引>`，任务产物使用 `artifact:<id>`。超长工具结果在裁剪时可保存为任务产物，当前轮就能用引用回读。自动装载和回读共用消息归属校验，早期无任务关联的消息沿用会话绑定。每次提交模型请求前复核会话、Agent、项目和当前 Turn。
- 使用 `GET /api/sessions/{id}/context-manifests?turnId=<id>&limit=30` 查看出站元数据，两个查询参数均可省略。调用序号和模型重试序号从零开始，同一尝试中的 HTTP 正文发送次数从一开始。诊断记录保存在进程内，保留至多 500 条、24 小时。`raw-context` 的响应头 `X-Minis-Context-Kind: preview` 标记预览。
- 自动化用例以模拟模型对照长叙事的旧过程裁剪和近期约定保留，以及长日志裁剪后按引用精确读回根因。真实模型的行为、总 token、缓存命中和延迟仍需对照采样。

### P2 · 收敛 SubAgent 回流和记忆入口

统一子执行输入与输出。输入包含任务目标、必要引用、工具授权和预算。输出包含完成或部分完成状态、短结论、证据及产物引用。子任务先建立自身执行身份，再组装上下文。复用现有同步与后台路径，分别处理同轮有时限的等待和后续 Turn 消费。收紧按 ID 回读的作用域校验，并把用户可见报告与自动回放区分。回流时检查权限和来源失效，保留完整报告的显式回读路径。检查归档正文是否会经通用记忆采集或提炼绕回。

验收覆盖父子执行隔离、报告全文退出默认上下文、可精确回读、撤回后晚到结果无法进入上下文，以及无委派与委派版本的质量、总 token 和延迟。

#### P2 试点使用与验证

- 个人单聊沿用 `CONTEXT_EPHEMERAL_CHAT=true`。每次委派先登记独立执行 ID，持久化父任务、Turn、会话、Agent、项目、授权引用和预算。子代理按自身任务目标和引用组装请求，至多接收 6 个明确授权的原文引用。工具授权限可用的只读资料工具，运行时逐次校验。
- `delegate_subagent` 接受 `source_refs`，例如 `message:12` 或 `artifact:34`。同步等待上限为 20 秒，超时后继续在后台运行并回报。模型收到执行 ID、状态和短结论，可以用 `read_subagent_run` 按 ID、`offset`、`length` 回读报告。回执区分执行中、完成和失败，部分成果和未决项由报告正文说明。子执行按独立 ID 记录实际出站 Manifest，可按父 Turn 查询。
- 用户的转录保留完整报告。后续模型请求将报告摘要作为可裁剪证据，按消息引用分页回读全文。批次归纳只取同批获授权的摘要和引用。报告、归纳及模型回读复核来源，父轮次回退、项目或 Agent 改绑后失效的来源退出装配。
- 自动记忆采集跳过试点子执行正文和使用受控证据的本轮助手答复。会话回退撤回关联子任务的记忆来源并使在途提炼失效。历史库中缺少可追溯来源的记忆条目仍按原有治理处理。
- 关闭开关后，新委派回到原入口。已有试点报告留在转录中，可通过卡片查看。
- SQLite 与模拟模型用例覆盖子执行隔离、预算裁剪、原文回读、撤回后拒绝投递和记忆提炼隔离。HTTP 回环用例验证实际出站记录，前端回归覆盖子代理批次先于主回复到达时的重连和收尾。真实模型的质量、总 token、缓存命中率与延迟仍待对照。

### P3 · 按证据加入 Scope、续接与轻量 Epoch

选择一个真实多阶段流程试点。在当前任务或 Turn 上先挂可选 Scope，只有需要跨轮生命周期时才持久化独立 Scene。阶段结束时生成短续接和引用，继续保留完整用户转录。先做局部整理，高水位仍不足时再重建至较低水位。通过 Manifest 记录重建代号、触发原因和选取范围，以安全点为边界，测试重建后连续数轮。供应商原生压缩产物若启用，仅作绑定模型、作用域和版本的可丢弃缓存。

旧会话可惰性建立水位，保留现有配对兼容逻辑。回退、分支、项目或 Agent 改绑以及删除来源时，重新核验派生引用和在途结果。会话作用域版本与业务状态版本分别管理。正常 Epoch 更新可继续接收仍有效、获授权的后台结果。

#### P3 单聊排障试点

- 同时设置 `CONTEXT_EPHEMERAL_CHAT=true` 和 `CONTEXT_STAGE_CONTINUATION=true`。P3 默认关闭。试点流程为同一单聊中的日志排查、阶段收尾与后续复核，每个会话保存一份续接。由主 Agent 在阶段收尾时调用 `save_context_continuation`。新阶段的有效续接替换旧记录，并重新从待整理状态开始。
- 工具接收 `items`，每项含 `kind`、`source`、`offset`、`length`。`kind` 可为 `confirmed`、`constraint`、`open` 或 `evidence`，由 Agent 对历史材料分类。来源沿用 `read_context_source`，字符范围以 Unicode 码点计。最多保存四段，每段最多 240 字符。只有本轮成功完成才保存范围和校验值，装配时重读原文，并以带来源的历史资料进入请求。Agent 可从旧日志工具记录、当前任务已归档的日志、会话中已确认的约定等提取短摘录。
- 下一轮开始后，如局部工具轨迹整理后仍超过可用输入预算的 85%，可按完整用户 Turn 收拢阶段边界以内的最早历史，尽量降至 65%。收拢会优先保留最近至少两轮，必要时继续按完整请求的硬预算处理。旧转录仍可回看和回读。新 Epoch 只有在采用该水位的主请求实际出站且 Turn 成功完成后持久化。
- `/api/sessions/{id}/context-manifests` 的 `selection.epoch` 记录 `id`、`stageTurnId`、`cutoff` 和 `reason`。`cutoff` 是最后一个退出默认回放的用户 Turn 的触发消息 ID，`history_high_watermark` 表示本轮触发，`persisted` 表示沿用已保存的水位。`raw-context` 可预览已保存水位。Scope 版本用于撤销会话改绑或回退前的续接，分支以自己的消息 ID 建立状态。来源不可读或摘录变化时，后续装配退回 P1 选取。已获授权的 P2 后台报告继续按证据预算进入请求。

#### P3 待用户验证

1. 在隔离的单聊测试会话中积累数轮带长正文的排障过程。在阶段收尾时让 Agent 用 `read_context_source` 选取根因证据和复核约束，再调用 `save_context_continuation`。用足以达到高水位的历史和模型窗口，让下一轮根据续接验证根因。
2. 查询实际出站 Manifest，核对 `context_continuation` 及 Epoch 水位，确认水位前的用户 Turn 整体退出默认请求。继续复核两轮，并按引用读取一段被省略的日志，核对原文和用户转录。比较启用 P3 前后的模型质量、总 token、缓存命中与延迟。
3. 分别取消收尾或重建轮次、撤回摘录来源、回退、改绑后再改回、创建分支，确认续接按预期失效或独立建立。检查正常 Epoch 更新之后仍可接收有效的 P2 后台结果。关闭 P3 后用同一用例核对 P1/P2 路径。

### P4 · 按需加入业务提交和持久恢复，再扩面

只在试点确实需要时增加阶段结算事务。领域服务校验类型、权限和版本，在短事务内更新权威状态、追加持久事件并保存幂等回执。相同操作键和载荷重试时返回原回执。同键不同载荷视为冲突。模型与外部调用在事务外，提交后投递通知。外部操作结果不明时，先核对实际结果，再决定如何续跑。回退聊天历史和补偿已发生的外部副作用分别处理。

暂停恢复优先支持明确安全点，保存待审批项、待完成操作与回执，恢复原执行并重新校验。单聊获得可复现收益后，再接入圆桌、心跳或 Coding 的现有执行路径，优先复用装配和观测接口。

#### P4 单聊待办确认试点

- 设置 `CONTEXT_EPHEMERAL_CHAT=true` 和 `CONTEXT_TODO_APPROVALS=true` 启用试点，后者默认关闭。首个操作是将当前 Agent 清单里一项未开始的待办标为完成。Agent 用 `request_todo_completion` 提交从 1 开始的序号及可选验收备注。工具必须单独调用。关闭开关后，已有待确认项仍可处理。
- 提案只有在成为本轮最终回复时才保存。原任务在同一事务中进入 `suspended`，确认卡附在该轮回复下。待确认状态可以跨后端重启，用户可在原会话明确确认或拒绝。处理决定时继续原任务的这一步待办操作，后端直接提交业务状态与回执。
- 审批使用原任务 ID 作为操作键，并复核发起人、会话、Agent、项目、作用域版本、原轮次和待办版本。确认、拒绝及待办版本冲突都产生持久状态。在当前获授权的作用域内，相同决定重试读回原回执，改用另一决定返回冲突。回退、改绑和删除会使相应的待确认操作失效，分支建立独立状态。
- 会话内的确认卡从 `GET /api/sessions/{id}/todo-approvals` 读取状态，通过 `POST /api/sessions/{id}/todo-approvals/{operationId}` 提交 `{"decision":"approve"}` 或 `{"decision":"reject"}`。待办状态、原任务终态、决定回执和运行时事件在同一短事务中提交，界面在提交后刷新。回退转录与补偿已经提交的待办状态是两个操作。

#### P4 待用户验证

1. 在启用 P4 的测试单聊中，让 Agent 用 `write_todos` 建立一条 `pending` 待办。再请它调用 `request_todo_completion` 提议完成该项，并附上一条验收备注。确认卡出现时检查待办仍为未开始，原任务为 `suspended`。
2. 重启后端并打开原会话，确认卡应可继续操作。批准后检查对应待办、原任务终态、备注和审批回执。对同一操作键重复批准应读回同一回执，再提交拒绝应返回冲突。
3. 用另一条提案覆盖待办清单后再审批，确认旧提案失效。分别验证拒绝、回退、改绑后再改回以及分支，检查提案和原任务状态。关闭 P4 后核对普通清单更新和 `request_user_input`。

## 行为漂移评测

固定模型、工具、任务输入和可用证据，依次比较现状、仅做预算与历史筛选、再加子代理回流或续接重建。Scene 在同一流程中单独做对照，避免把它的收益和其他改动混在一起。

| 场景 | 观察和断言 |
| --- | --- |
| 长叙事切换到普通对话，再回到原任务 | 旧措辞与结构是否串入，新输出是否接住已确认约定，能否按引用逐字回读 |
| 大量日志和工具结果之后继续排障 | 旧日志退出默认请求，关键根因、反例和未完成步骤仍可恢复 |
| 事实、偏好、身份和决策冲突 | 分别测事实准确性、当前偏好覆盖、身份约束保持和遇到新证据时修正决定 |
| 回退、分支、取消、撤回和重启 | 无越权、无重复业务提交、无撤回材料复活。测试实际状态和回执 |
| 成本与性能 | 记录每个成功任务的总 token、缓存 token、调用数、回读次数、首字延迟与 P95，并检查多次运行的稳定性 |

## 仍需在试点决定

- 预算阈值、输出预留、保留窗口与缓存策略，依据实际模型和任务调参。
- 来源引用是否需要新 API 或工具、Manifest 留存期限与正文访问规则，以最小可用切片决定。
- Scene 的首个正式流程、自动结束信号和是否需要独立持久实体，用对照结果决定。
- 待办确认试点以外的恢复范围和业务操作，由实际流程决定。

公开对照包括同一 Codex thread 上的压缩事件、Anthropic 持久会话与按需回读，以及 OpenAI 对委派与控制权转移的区分。这些资料用于类比设计。Minis 的效果以自己的对照评测为准。 [Codex App Server](https://learn.chatgpt.com/docs/app-server#trigger-thread-compaction) · [Anthropic Managed Agents](https://www.anthropic.com/research/managed-agents) · [OpenAI Orchestration](https://developers.openai.com/api/docs/guides/agents/orchestration) · [Anthropic Agent Memory](https://www.anthropic.com/research/components-of-agent-memory) · [OpenAI Agent Evals](https://developers.openai.com/api/docs/guides/agent-evals)
