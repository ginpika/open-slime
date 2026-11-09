进行总结式审查，看看这些改动是否真的可以解决文档中提到的问题。(Deepseek-v4.1-flash)

## 报告 B · 动态上下文管理架构审查
结论先行 ：架构骨架已经落地，P1/P2 的实现有可复现测试，方向正确；但原始问题 尚未闭环 。按默认配置，行为与改造前完全一致；即使打开开关，P1 也只在请求接近窗口预算时裁历史，P3 的场景/阶段需要模型主动调用工具建立，且只有超过约 85% 输入预算时才真正生效；角色与场景指令始终共用同一个 system prompt。因此“跑团几十轮后问代码仍用角色口吻”在当前代码路径上仍会发生。

我按实际代码行为独立审查，重点追踪了`ChatTurnRunner → ChatContextService → ContextBuilder.plan → ContextPlan.fit → ModelLoop → OpenAiChatClient` 的出站链路，以及来源回读、P2 子代理、P3 续接、P4 审批。工作树还混有 Agent 消息、骰子、前端 UI 等旁路改动，本报告只对动态上下文链路负责。

### 一、原问题是否被解决
原问题环节 当前实现 判定 旧轮次带入长期对话 P1 仅在`used > allowance` 时按预算裁历史；85% 才尝试推进 Epoch 部分解决 角色口吻跨任务延续 `system_base` 每轮强制注入同一个 Agent 提示词；P3 续接只保存摘录，不替换场景指令 未解决 跑团结束 → 代码问题 没有自动话题/场景边界检测；`save_context_continuation` 由模型主动调用，默认关闭 未闭环 旧工具结果、子代理全文退出默认上下文 live tool 归档 +`read_context_source` 、P2 报告摘要 + 引用 已改善 默认用户体验 `ephemeral-chat` 、`stage-continuation` 、`todo-approvals` 默认均为 false 未生效

三处直接依据：

- 默认开关： application.yaml
- P1 只在预算压力下裁剪： ContextPlan.java
- P3 续接必须由模型工具写入： ChatContextContinuation.java
- system prompt 始终来自 Agent 身份： ChatContextService.java 、 ContextBuilder.java
### 二、实现中值得肯定的部分
- 持久会话与请求视图解耦 ：工作转录保留完整历史，`ContextPlan.fit` 每个模型轮次独立装配出站请求，工具循环不会再把整段历史重复塞回。
- 来源与权限可回读 ：`ContextFragment.Ref` 、`ChatContextSources` 对消息、工具结果、任务产物做统一引用读取，并在每次模型尝试前复核作用域；跨会话、撤回、改绑后的读取会被拒绝。
- Manifest 来自真实出站边界 ：通过 OkHttp`requestBodyEnd` 记录实际提交的正文，而不是重新组装的预览；`raw-context` 与`context-manifests` 也有 preview/outbound 区分。
- P2 子执行隔离 ：子代理只拿到任务目标、授权引用和受限只读工具；报告默认压缩为摘要 + 引用，完整正文保留在转录，记忆提炼也被隔离。
- P4 事务化审批 ：提案、原任务挂起、回复、待办版本校验和回执在同一事务内提交，重复决定可按回执幂等读回。
### 三、缺陷与风险
1. 高 · 场景/角色指令没有切换机制。`ContextPlan` 的 INSTRUCTION 层始终是同一个`system_base` ；P3 续接是普通 user 消息，无法覆盖或降权角色设定。即使旧轮次被裁掉，模型仍会按角色提示词回答。若产品目标是“同一会话内自动从跑团切到代码”，当前实现缺少 Scene 指令或用户可见的场景切换入口。
2. 高 · P1 不是“聊久了就自动收敛”，而是“快满了才收敛”。 只有请求超过可用输入预算时才丢历史；几十轮普通对话若远低于窗口，旧跑团内容仍会完整回放。P3 又要求先有模型主动保存的续接，且`canAdvance` 需要超过 85% 水位。因此用户症状的典型触发条件并未被覆盖。
3. 中高 · P2 同步委派的完整报告不会进入用户转录。`background=false` 时走`finishScoped(..., false)` ，只把摘要作为工具结果返回；只有后台路径才发布`SubagentReportEvent` 并写入完整报告卡片。这与设计文档“用户的转录保留完整报告”不一致，也导致同步批次无法参与后续批次归纳。见 SubagentService.java 、 SubagentService.java 、 设计文档 。
4. 中 · 兼容路径把不可信资料标成了“必须指令”。`ContextProvider.fragments` 的默认实现把任意`systemSuffix` 包装为`TASK + INSTRUCTION` 。`BrowserContextProvider` 明确说明页面标题/URL 不可信，却没有覆写`fragments` ，因此浏览器 JSON 会被当作不可裁剪的指令片段；`MemoryProvider` 同样默认成为 required。结果是预算紧张时先裁真正可裁的历史，反而保留不可信动态材料；Manifest 的 trust 也失真。见 ContextProvider.java 、 BrowserContextProvider.java 、 MemoryProvider.java 。
5. 中 · 历史工具证据被裁后缺少可发现引用。 live tool 超预算时会归档为 artifact 并插入“本轮工具记录索引”；但历史工具交换在`stripHistoricalTools(true)` 中直接移除，不归档、不提示。模型理论上可以用`read_context_source list` 逐页找回，但缺少自动引用，不能算强保证“长工具输出去掉后仍能找回根因证据”。见 ContextPlan.java 。
6. 中 · P3/P4 没有自动化覆盖，全量套件也未绿。 新增测试集中在 P1/P2：`ChatEphemeralContextTest` 5 个、`ChatContextSourcesTest` 4 个、`ContextPlanTest` 3 个、`ChatContextManifestsTest` 1 个、`ScopedSubagentRunTest` 1 个、`SubagentDeliveryServiceTest` 3 个，均通过。但`ChatContextStages` 、`ChatTodoApprovals` 没有专门测试。全量`mvnw test` 为 731 个用例、1 failure + 34 errors，集中在 memory/config 测试夹具（`temporal_json` 、`memory_cursor` 等建表漂移），其中 MemoryRecallTest.java 因测试夹具没有`memory_event` 行而失败。不能据此宣称整体回归通过。
7. 低 · P4 的`stale` 状态在列表里不可见。`VISIBLE_APPROVALS` 强制要求`a.scope_revision = 当前 revision` ，而回退/改绑时`retireTodoApprovals` 只把状态改成`stale` ，不修改审批行里的 revision。结果是这些审批从列表消失，前端定义的“审批已失效”状态实际不可达。见 ChatTodoApprovals.java 、 HubSessionStore.java 、 ChatTodoApprovalCard.tsx 。
8. 低 · 可维护性风险。 ContextPlan.java 同时承担预算、裁剪、Epoch、图片窗口、归档提示和协议渲染，且大量依赖`IdentityHashMap` 以 JSON 节点身份关联引用；一旦任一层深拷贝消息，来源会静默丢失。当前测试只覆盖了 3 个预算场景，后续演化风险较高。
### 四、验证结果
- `mvnw -DskipTests compile` ：通过。
- 新增/改动范围内用例：17/17 通过。
- 全量`mvnw test` ：731 个用例，1 failure、34 errors，全部落在 memory/config 测试夹具，非 P1/P2 新增路径，但说明当前提交区不能作为“全绿”基线。
- P3/P4：无自动化用例，只有手工验收步骤；与设计文档“P3、P4 待验证”的状态一致。
### 五、建议
1. 先定义场景边界 ：给用户或 Agent 一个明确的“开始新阶段/切换任务”动作；新场景应替换或降权角色指令，而不只是带回几段摘录。
2. 不要把 P3 完全交给模型自觉 ：至少在用户明显切换话题时建议建立新 Scene；并用真实模型做对照，验证“跑团→代码”场景下默认路径是否还会串味。
3. 修复三个实现缺陷 ：同步委派报告进入转录、按资料类型修正`ContextProvider.fragments` 的 Layer/Trust、为历史工具裁剪补可发现的 artifact 引用。
4. 补 P3/P4 测试并修复记忆测试夹具 ；在开关打开、重启恢复、回退/改绑、撤回后晚到报告等路径上做闭环回归。
总体判定：这是一次合格的架构骨架施工，但不是原问题的完成态。 用一句话概括报告 B 的结论： “持久会话 / 临时上下文”的机制已经建立；但“同一个 Agent 聊久后被旧内容带偏”的用户症状，在当前默认路径和 P3 触发条件下仍未真正解决。

