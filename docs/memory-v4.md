# Memory v4 · 完整档案（合并版）

> **合并来源**：`memory-v4-plan.md`（任务目标 + 执行记录）、`memory-v4-audit.md`（架构审计）、`memory-v4-design.md`（目标设计）。
> **权威顺序**：目标设计 > 审计事实；三份原文仍各自保留，本文件为便于通读的合并稿。
> **合并策略**：任务目标与目标设计**完整保留**；审计报告保留全部事实与发现（其「初稿设计」部分已被目标设计取代，收敛为指针）；
> 执行记录完整保留。**本轮未实施任何代码，未改动真实库。**
> 标注约定：**代码事实** / **设计建议** / **待确认** / **未实测**。

---

# 第一部分 · 任务目标

> 原始 v4 任务书（`memory-v4-plan.md` 的任务部分），完整保留。

## 一、现有业务场景与必须解决的问题

系统用 Java 开发，核心执行架构基于 Turn + AgentRuntime，正在迭代 Multi-Agent 协作、群聊、任务调度与长期记忆。
本次目标**不是尽可能复用现有 Memory 实现，而是建立正确、可扩展、可维护的记忆体系**。不预设当前架构正确，也不预设必须多库。

必须解决：
1. **多 Agent 长期协作**——区分：Agent 长期身份与私有记忆；当前 Turn 执行上下文；当前 Task/Session 临时工作状态；
   项目级长期知识；用户级长期画像；Agent 间显式共享信息。不能合并为无明确所有权的单一 Memory 对象。
2. **用户画像污染**——跨 Agent 共享的用户画像，须防止：小说人物属性写入用户画像；虚构事件误认为真实经历；
   单项目临时选择升为全局偏好；少量行为直接改写稳定画像；多 Agent 并发更新静默覆盖。
   画像须有独立写入策略、证据来源、适用范围、更新机制与历史记录。
3. **项目间记忆污染**——项目 A 设定不得污染 B；临时偏好不自动成他项目默认；长期不活跃≠可删除；
   已确认关键决策可持续保留；项目切换后能恢复工作状态。
4. **长篇小说导致记忆膨胀**——大量文本（章节、人物卡、世界观、大纲、废弃方案、伏笔、讨论、当前状态）
   不得无差别写入并每次全量检索/注入。必须区分：原始资料持久保存、结构化知识管理、任务状态管理、模型上下文构建。
   **持久保存可以很多，单次调用只加载当前任务真正需要的；不得为控上下文而删除有价值原始资料。**
5. **记忆容量挤占与遗忘**——不得由数量/最近访问/写入频率决定生存。须区分：是否持久保存、是否长期保留、
   是否参与当前检索、是否进入模型上下文、是否压缩归档或删除。重要画像、项目决策、未完成事项不得因他项目活跃而丢失。
6. **Agent 之间信息共享**——支持：Agent 私有经验与独立判断；当前任务共享工作材料；项目级共享知识；
   有权限访问的用户画像；主动发布的交接信息；验证后沉淀的可复用知识。共享须有明确作用域、权限、来源与生命周期。

## 二、第一阶段：审计（不立即修改）

至少检查：Memory 实体/DTO/表/Repository/索引；MemoryService 全部读写更新删除检索入口；AgentRuntime 如何读取与注入；
Turn/Task/Session/Agent/Project/User 真实关系；Agent 身份是否持久化及其与项目/会话绑定；模型调用前上下文组装/裁剪/摘要/Token 预算；
Memory 写入触发条件；更新/去重/冲突/过期/归档/删除策略；多 Agent 并发的隐患；现有测试/配置/迁移/兼容要求。
追踪至少一条完整调用链，如与示意不同以代码为准。

**审计输出要求**分别列出：A 已实现且有代码证据；B 不存在或明显缺陷；C 存在但无法确认效果；D 隐含未论证假设；
E 可能造成污染/丢失/冲突/膨胀/并发的具体位置。每个结论尽量给文件路径、类名、方法名与调用关系。
不得把「没有检索到实现」等同于「绝对不存在」；证据不足须明确说明。完成后再进入实施。

## 三、第二阶段：设计目标 Memory 架构

评估并可按审计调整：1 User Profile（区分用户陈述/行为推断/项目内偏好/未确认候选/已过期或修正）；
2 Project Memory（小说：世界观/人物卡/时间线/剧情决策/伏笔/章节摘要/废弃方案/原始与历史版本；软件：约束/技术决策/架构/代码事实/未完成事项/已验证方案）；
3 Agent Private Memory（默认仅自身可见，不得自动广播）；4 Task/Session Memory（检查点/待办/临时结论/工具结果/讨论摘要；
支持暂停恢复结束归档清理；不因 Turn 结束销毁有效状态）；5 Archive/Source Materials（可靠定位与重读，不与摘要混同）；
6 Shared Knowledge/Handoff（区分任务内/项目内/跨项目/用户级共享；保留来源/权限/版本）。
这些是逻辑边界，不意味着必须五套库；须比较统一物理存储+逻辑隔离 / 独立存储 / 混合方案并给出选择。

## 四、第三阶段：记忆治理机制

1 **数据模型**（评估记忆字段：memoryId/domain-scope/ownerId/projectId/agentId/taskId/memoryKind/content/sourceId/sourceType/
appliesTo/status/confidence或evidenceLevel/version/createdAt-updatedAt/expiresAt/retentionPolicy/pinned/关联引用；
说明每字段解决什么问题，哪些复用、哪些确需新增，不为完善而堆砌抽象）。
2 **写入治理**（区分任务事件/项目事实/项目决策/Agent 私有经验/用户画像候选/已确认用户级信息/需发布知识；
防跨作用域隐式写入；对画像与关键决策评估版本控制、冲突检测、来源追踪与确认机制；并发明确事务/乐观锁/版本校验；
不让 last-write-wins 成为默认）。
3 **读取与上下文组装**（检索前确定 User/Project/Agent/Task-Session/任务类型/可访问范围；先权限与作用域过滤再相关性检索；
评估精确/结构化/关键词/向量/混合/按实体关系/历史版本检索，不默认全塞向量库；定义 Token 预算、优先级、截断策略；
超限优先移除低相关历史与可重建摘要，不得静默丢弃受保护记忆）。
4 **压缩与长期保留**（区分原始资料/结构化知识/派生摘要/当前工作集/可清理缓存；摘要保留到原文可追溯引用；
更新摘要不删仍需历史事实；新旧冲突时更新现行状态并保留必要历史与原因）。
5 **生命周期与淘汰**（按类型定义独立保留策略；不只凭访问时间/写入频率/相似度/条数；区分长期保护/定期重评估/自动过期/
可重建缓存/可归档/用户明确删除）。

## 五、第四阶段：与 Turn + AgentRuntime 正确集成

Memory 不应绑定单个 Turn 生命周期。须设计：1 Agent 长期身份与长期记忆关联；2 Task/Session 状态持久化与恢复；
3 Turn 开始时构建 Memory Snapshot；4 Turn 内记忆更新的一致性；5 新 Turn 读取最新有效记忆；6 暂停恢复还原上下文；
7 切换项目清理不再适用上下文；8 重建 Agent/切换底层模型可恢复的状态；9 记忆发布后他人如何发现更新；
10 避免无限制通知/递归讨论/重复提炼。区分持久化状态/运行时快照/模型上下文/共享事件；
不让 TurnScheduler 承担全部 Memory 业务，也不让 AgentRuntime 绕过统一治理。

## 六、第五阶段：可执行验收测试

测试 A 跨项目隔离；B 用户画像保护；C 偏好升级；D 记忆容量控制；E 重要记忆保留；F 并发写入；G 历史恢复；
H 跨 Agent 共享；I 任务暂停与恢复；J 过期和修正。**必须验证实际行为，而非只验证接口返回成功。**

## 七、第六阶段：分阶段实施

顺序：阶段1 补齐作用域/权限/来源基础治理；阶段2 项目隔离/画像保护/Agent 私有记忆；阶段3 上下文检索/Token 预算/大型项目知识组织；
阶段4 提炼/交接/冲突/长期归档；阶段5 并发/恢复/监控/回归。顺序可按代码调整但须解释。
要求：尽量复用可靠基础设施；避免无必要大规模重构；不以兼容旧架构为由保留明确缺陷；DB 变更须有迁移与回滚；
对已有数据制定兼容/分类/迁移/异常策略；不得静默删除重要记忆；先写/更新测试再改动；每阶段跑测试并报告；
关键决策依赖未确认业务约束时先说明不擅自假定。环境允许则分阶段实施而非只输出理论；无法完成须列明阻塞与下一步。

## 八、最终交付标准

1 审计报告；2 目标架构及取舍；3 数据模型与作用域定义；4 读写权限与生命周期规则；5 AgentRuntime/Turn 集成方案；
6 数据迁移与兼容方案；7 已实施代码修改清单；8 测试结果及未覆盖风险；9 后续迭代计划。
所有结论区分代码事实、设计建议、待验证假设。
**最终判断标准不是模块更复杂，而是：画像不被项目污染、项目记忆不串扰、重要信息不因容量与活跃度丢失、
Agent 能共享必要知识又保有独立性、系统能可靠暂停/恢复/迁移/长期维护。**

---

# 第二部分 · 架构审计报告

> 来源 `memory-v4-audit.md`。本文为**事实来源**；其中「初稿设计」已被第三部分取代，下文以指针标注。

## 0. 结论摘要

现有实现是 v2 的成果：**从纯事件日志升级为「记忆条目（memory_item）+ 证据事件（memory_event）+ 后台巩固」**，
默认开启、异步、失败隔离，能跨会话注入「关于用户/项目的稳定信息」。这层可靠、值得复用。

但它只解决 ChatGPT 式单用户偏好记忆，**未解决 v4 的多 Agent/多项目/长文本项目/治理**四个维度：

| v4 关注点 | 现状 | 结论 |
|---|---|---|
| 多 Agent 长期协作 | `memory_item.agent_id` **写了但读侧从不按它过滤** | ❌ Agent 私有记忆实际不存在 |
| 用户画像污染 | profile 由 LLM 一次性判定；builtin 会话 `agent_id=NULL` 即全局 | ❌ 无防护（测试 B 必失败） |
| 项目间污染 | **全仓无 Project 实体**，`scope=project` 只是标签 | ❌ 无从谈起（测试 A 必失败） |
| 长篇小说膨胀 | 条目无长度上限、无归档层、注入按条数非 token | ❌ 会撑爆上下文 |
| 容量挤占与遗忘 | 仅 `episode` 保留最新 50 条 | ⚠️ 保护等级缺失（E 有风险） |
| Agent 间共享 | **无共享/交接机制** | ❌ 不存在（测试 H 必失败） |
| 并发一致性 | 无 version/乐观锁；去重为非原子读-改-写 | ❌ 静默覆盖（测试 F 必失败） |
| 任务暂停恢复 | `TaskRecovery` 只把在途置 interrupted | ⚠️ 部分（I 有风险） |

**一句话**：地基可复用；**「作用域/所有权/隔离/治理」四件事等于从零开始**，且必须先回答「项目（Project）指什么」。

## 1. 现有实现地图（代码事实）

### 1.1 存储（`resources/schema.sql`）

| 表 | 位置 | 角色 | 关键列 |
|---|---|---|---|
| `memory_event` | L549-560 | L4 证据层，只追加 | `event_key`(UNIQUE 幂等)、`kind(chat/exec)`、`actor`、`title`、`payload`(≤500 字投影)、`ref_type/ref_id` |
| `memory_event_embedding` | L568-574 | 事件向量 | `event_id` PK、`model/dim/vec` |
| `memory_item` | L585-602 | L0–L3 条目 | `agent_id`(NULL=全局)、`scope(directive/profile/project/episode)`、`title/body/confidence/source/pinned/valid_from/valid_until/superseded_by/source_refs` |
| `memory_item_embedding` | L606-612 | 条目向量 | `item_id` PK、`model/dim/vec` |
| `memory_cursor` | L615-619 | 提炼水位 | **建而未用（grep 0 命中）** |
| `hub_session.memory_off` | L40 | 临时会话开关 | 1=本轮不进记忆 |
| `hub_session.agent_id` | L33 | 会话归属 Agent | NULL/`builtin`=内置 |

**证据**：`grep "memory_cursor"`（`src/main/java`）0 命中；`MemoryProps.getTopK()` 定义后无消费点。

### 1.2 写入链路

- 事件层：`MemoryWriter.onChatMessage/onExecDone`——发生点 push、`event_key` 幂等、失败隔离、异步补向量。
  调用点：`ChatTurnRunner:852,864`、`AgentRunner:141`。
- 条目层（提炼）：`MemoryExtractor.extractAsync/extractConversationAsync`——一次 LLM 往返产出 JSON 数组
  `{scope,title,body,confidence,valid_until}`，逐条 `items.insert(agentId,...)` + 异步补向量。
  硬规则：失败隔离 + 信息量门槛（`noise-min-chars` + 「记住」触发词）+ 零噪声提示词。
- 攒批：`MemoryIngest.tap`——**内存**缓冲，`batch-turns` / `idle-ms` 合并提炼；「记住」立即 flush。
- 触发：`ChatTurnRunner:513-514`（`turnTracked && !memoryOff` → `tap(row.id(), row.agentId(), input, reply)`）。

### 1.3 读取链路

- 条目召回 `MemoryItemRecall.recall`：候选 = `items.active(200)`/`activeByScope(scope,200)`（**只过滤 scope，不过滤 agent/project**）
  → 向量余弦 × scope 权重（directive1.15/profile1.10/project1.00/episode0.90）→ `min-score` 门控 → pinned 优先。
- 事件下钻 `MemoryRecall.recall`（时间窗 + 向量 + `BACKGROUND_ACTORS` 排除后台）。
- 注入 `MemoryProvider`：`@Order(-5)`、仅 `Surface.CHAT`、`recall(query, null, 5)`、无命中不注入、异常吞掉。
- 工具 `recall_memory`（默认条目、`mode=event` 下钻）+ `backfill_memory_item_vectors`；留痕走 `rag_recall` 的 `memory` 通道。

### 1.4 治理链路

- 巩固 `MemoryConsolidator`（确定性、零 LLM）：`dedupe()` 对 `(scope,body)` 相同保留 id 最大、其余 supersede；
  `trimEpisodes()` 超 `episode-max` 最旧 delete。调度照抄 Heartbeat，默认 1h。**语义级合并未实现**。
- 管理 API `MemoryController`（summary/items/新增/编辑/删除/pin/consolidate/clear）。

### 1.5 运行时关系（代码事实）

```
app_user(单行)  ──  无 user_id 分区
agent(agent.id) ── hub_session.agent_id ── chat_message
                      └─ task(session_id, agent_id, kind, origin, status...)
```
- **不存在 project 表/列**；`grep -i project` 仅命中 memory scope 常量。
- `task.workspace_id` 注释「为 IDE 预留；当前未用」；真正的工作空间是 `term_target`（docker/ssh）。
- `ContextRequest` **只有 sessionId 与 surface，无 agentId/projectId** → `MemoryProvider` 结构上拿不到作用域。

## 2. 审计输出 A–E

### A. 已实现且有代码证据（可复用）
双层存储 + 向量 + 幂等事件；异步提炼 + 门槛 + 显式记忆 + 失败隔离 + 临时会话跳过；攒批缓冲；
条目召回（向量 + scope 加权 + 门控 + pinned）；注入与工具双入口；后台巩固骨架 + 去重 + episode 裁剪 + 手动触发；
管理 API；会话级临时开关；`LayeringTest` 钉死 `ai.memory` 不依赖 `agent`；4 个既有回归测试。

### B. 不存在 / 明显缺陷

| # | 缺陷 | 证据 | 影响 |
|---|---|---|---|
| B1 | **无 Project 实体** | 全仓无 project 表/列 | A、E |
| B2 | **`agent_id` 写了不读** | 召回/查询均无 agent 条件 | H、B |
| B3 | **画像污染零防护** | scope 由 LLM 判定；builtin 会话 agent_id=NULL→全局 | B、C |
| B4 | 无偏好升级门槛 | 无升级逻辑 | C |
| B5 | 无容量/预算分离 | body 无长度上限；注入按条数（硬编码 5）；`getTopK` 无人消费 | D |
| B6 | 生命周期粗糙 | 仅 episode 裁剪；dedupe 在全局 active 上跑 | E、跨域误伤 |
| B7 | 无并发控制 | 无 version；`supersede/updateContent` 无条件；去重非原子 | F |
| B8 | 任务/会话无记忆检查点 | `TaskRecovery` 只置 interrupted | I |
| B9 | 无共享/交接 | 无 shared scope/发布/发现/权限 | H |
| B10 | 无历史版本检索 | 无 version 列；superseded 链无 API 查历史 | J |
| B11 | `memory_cursor` 建而未用 | grep 0 命中 | 扫描式提炼缺失 |

### C. 存在但无法确认实际效果
dedupe 跨 agent/project 归一是否有意；`min-score` 门控实际效果依赖 embedding（未配置时只返回 pinned）；
`MemoryIngest` 内存缓冲重启即丢；默认开 + 全局召回很可能已在跨项目注入（未被测试覆盖）。

### D. 隐含未经论证的假设
单用户单库；scope 由 LLM 判定足够可靠；`agent_id=NULL` 即全局画像；会话是天然边界；条目正文天然短小；
记忆只服务前台会话（但 `recall_memory` 在 HEARTBEAT 面也是全局召回）。

### E. 具体风险位置
污染：`MemoryExtractor.extract → insert(agentId)`（无校验）→ `MemoryItemRecall.recall`（不隔离）→ `MemoryProvider`（注入所有会话）。
丢失：`trimEpisodes` 直接 delete；`dedupe` 软覆盖；`clearAll` 全删。
冲突：`updateContent/supersede/setPinned` 无版本校验。
膨胀：`memory_item` 除 episode 无上限；注入按条数非 token。
并发：`MemoryIngest` 并发 extract；`consolidator` 与 `extract` 并发读写 active；`insert` 不在事务内。

## 8. 证据索引（文件:行）

- 存储：`schema.sql` L549-619、L27-43、L362-390。
- 写入：`MemoryWriter`、`MemoryExtractor`、`MemoryIngest`；`ChatTurnRunner:513-514,852,864`；`AgentRunner:141`。
- 读取：`MemoryItemRecall`、`MemoryRecall`、`MemoryProvider`、`KnowledgeToolbox:548-578`。
- 治理：`MemoryConsolidator`、`MemoryItemStore`、`MemoryController`。
- 集成缺口：`ContextRequest`（无 agentId/projectId）。
- 分层约束：`LayeringTest:70-89`。
- 配置：`MemoryProps`、`application.yaml` L61-75。

> **（原审计报告 §3 目标架构、§5 分阶段实施 已被第三部分取代，此处不再重复。）**

---

# 第三部分 · 目标设计（权威）

> 来源 `memory-v4-design.md`（评审修订版 v2）。**本文为决策唯一权威。**

## 0. 决策记录

### 0.1 既有四项决策
1. **Project 方案 (a)**：独立 Project 实体与表，跨 Session/Task/Agent 的业务工作域。
2. **User Profile 全局权威**：严格区分正式画像 / 画像候选 / Agent 私有；普通 Agent 不得直接覆盖。
3. **版本化迁移**：不破坏式重建；先确认兼容性，提供迁移/验证/备份/恢复；仅可弃的一次性数据才重建。
4. **混合资料方案**：Archive/RAG + 结构化 Project Memory + 动态 Working Set。

### 0.2 本轮评审 6 组问题的决议

| 组 | 决议 |
|---|---|
| I 历史数据与画像保护 | §6.5 回填规则 + §6.2 可验证证据定义 |
| II 统一数据模型与状态机 | 候选唯一权威=`memory_candidate`（§6.1）；`status`/`retention` 分离（§5.1）；归属组合约束（§2.3）；内置 Agent 归属（§2.2） |
| III 权限 | 可测试判定规则（§4） |
| IV 迁移 | 迁移器与业务迁移分层（§11.1）；P0 仅基础设施（§13）；实测在副本（§11.4）；资料版本拆 P4（§11.6） |
| V Token 预算与测试 | 配置化预算（§8）；保护规则落到淘汰/去重/巩固（§5.4）；测试矩阵含阶段/执行/结果（§12） |
| VI 交付纪律 | 逐阶段范围（§13）；不重写可复用模块；不静默丢弃/错误归类 |

## 1. 现有能力审计

### 1.1 RAG / 资料 / 图谱（可直接复用，代码事实）
`chunks`（原文块）；`documents`（统一文档模型）；中英双路 FTS5（`chunks_fts_meta`/`chunks_fts_cn`）；
`chunk_embedding` + `VectorRecaller`；图谱 `entities`/`relations`/`chunk_entity`/`chunk_extraction`/`entity_embedding`
+ `GraphExplorer`/`GraphPathFinder`/`EntityEnricher`；`FastSearchService`（关键词→向量→图谱 → RRF → 精排）；
`data/article/{url}.md` 文件正本；内部文档（url=NULL）全文由 `fullText` 用 chunk 拼接；`Chunker`。

### 1.2 `documents.url` 依赖审计（代码事实）

| 依赖点 | 位置 | 性质 |
|---|---|---|
| 路由键 | 前端 `#/article/{url}` → `GET /api/articles/{url}` → `ArticleController.detail(url)` | 用户可见 |
| 文件名 | `DocumentStore.writeMarkdown/markdown` → `data/article/{url}.md` | 存储定位 |
| 存储判据 | `DocumentStore.fullText`：`url!=null` 读文件，否则 chunk 拼接 | 语义分支 |
| 文章过滤 | `articlePage`/`articleCount`：`WHERE url IS NOT NULL` | 列表逻辑 |
| 分块来源 | `rechunk(sourceRef = url!=null ? url : "doc:"+id)` → `chunks.source_ref` | 溯源 |
| 检索链接 | `FastSearchService.docHits` → `Hit.url` → 前端 `ResultCard` | 用户可见 |
| Upsert 依赖 | `DocumentStore.saveArticle` 用 `ON CONFLICT(url) DO UPDATE` | 依赖 `url` 唯一约束 |

**关键不一致（必须实测）**：`schema.sql` 中 `documents.url` **无任何 UNIQUE 约束/索引**，但 `saveArticle` 用 `ON CONFLICT(url)`。
→ 真实库可能另有唯一索引，也可能该路径未被触发。**不得凭假设推进。**

**结论**：① 不用 `url=NULL` 表达文章历史版本；② 版本化落「内部文档」路径；③ 不改 `documents.url` 约束与文章发布语义；
④ 资料版本化拆 P4，不进 P0。

### 1.3 现有库与迁移现状（代码事实）
真实库 `data/minis.db` 存在（gitignore），含真实数据 → **禁止默认重建**。`schema.sql` 全 `IF NOT EXISTS`、启动即执行。
`SchemaColumnPatches` 仅做「可空、无默认值」的 `ADD COLUMN`（`PRAGMA table_info` 判存在，幂等）。
`_schema.version` 恒为 1、无任何代码读/递增 → 无真正迁移执行器。

## 2. 领域与所有权模型

### 2.1 关系
```
User(app_user) ──owns──▶ Project(project) ◀──serves── Agent(agent)
                              │ has                      │ 长期身份 + 私有记忆
                              ▼                          │
                     Session(hub_session.project_id) ◀──┘ (agent_id)
                              │ has
                              ▼
                        Task(task) ── Turn(chat_turn)
```
- **Project**：`id,name,kind(novel|software|research|other),owner_user_id,status(active|paused|archived),note,created_at,updated_at`。
- **Session**：新增 `project_id`（可空）。**Task/Turn 不新增项目/Agent 列**——经 Session 传递。

### 2.2 无 Project 归属的默认作用域

| 情形 | owner | scope | agent_id | 说明 |
|---|---|---|---|---|
| 会话绑 Project | `project` | `project`/`episode` | 记录产出者（可空） | 项目内共享 |
| 无 Project，成员 Agent | `agent` | `episode`/`fact` | = 该 Agent | Agent 私有 |
| 无 Project，内置 Agent | `agent` | `episode`/`fact` | **`'builtin'`（规范化，不再 NULL）** | Agent 私有；绝不默认提升为 user |
| 用户明说「记住」且稳定事实/偏好 | `user` | `profile`/`directive` | NULL | 全局，但默认落候选 |
| 项目内假设/虚构剧情 | `project`/`agent` | `project` | — | 永不自动升级为 user |

**硬规则**：`agent_id=NULL` **不再表示全局画像**；历史 NULL 一律归 `owner=agent`（规范化 `'builtin'`）。

### 2.3 归属组合约束矩阵

| owner | 允许 scope | project_id | agent_id | session_id | 合法 |
|---|---|---|---|---|---|
| `user` | profile, directive | 必须 NULL | 必须 NULL | 必须 NULL | ✅ |
| `project` | project, episode, fact, decision, open_issue | **必须非空** | 可空 | 可空 | ✅ |
| `agent` | episode, fact, method | 可空 | **必须非空** | 可空 | ✅ |
| `task` | checkpoint | 可空 | 必须非空 | **必须非空** | ✅ |
| `user` | 其它 scope | 任意 | 任意 | 任意 | ❌ |
| `project`/`agent` | profile/directive | 任意 | 任意 | 任意 | ❌ |
| 任意 | 任意 | `owner=user` 且 project_id 非空 | — | — | ❌ |
| 任意 | 任意 | `owner=project` 且 project_id 为空 | — | — | ❌ |

写入侧与迁移回填侧**都**校验；违反即拒绝并记日志（不静默纠正）。

## 3. 数据模型

### 3.1 新增表
```sql
CREATE TABLE IF NOT EXISTS project (
  id TEXT PRIMARY KEY, name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'other', owner_user_id TEXT,
  status TEXT NOT NULL DEFAULT 'active', note TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);

CREATE TABLE IF NOT EXISTS memory_candidate (
  id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL,
  owner TEXT NOT NULL, project_id TEXT, agent_id TEXT,
  scope TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
  evidence_level TEXT NOT NULL, evidence_count INTEGER NOT NULL DEFAULT 1,
  evidence_refs TEXT, status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS uq_candidate_fp ON memory_candidate(fingerprint) WHERE status='pending';

CREATE TABLE IF NOT EXISTS memory_share (
  id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL REFERENCES memory_item(id),
  from_owner TEXT NOT NULL, from_project TEXT, from_agent TEXT,
  to_kind TEXT NOT NULL, to_ref TEXT, status TEXT NOT NULL DEFAULT 'published',
  note TEXT, created_at INTEGER NOT NULL, revoked_at INTEGER);
CREATE INDEX IF NOT EXISTS idx_share_item ON memory_share(item_id, status);
CREATE INDEX IF NOT EXISTS idx_share_target ON memory_share(to_kind, to_ref, status);

CREATE TABLE IF NOT EXISTS memory_revocation (
  id INTEGER PRIMARY KEY AUTOINCREMENT, target_item_id INTEGER,
  fingerprint TEXT NOT NULL, reason TEXT,
  allow_relearn INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS idx_revocation_fp ON memory_revocation(fingerprint);
```

### 3.2 `memory_item` 增量加列（可空、无默认值）
`project_id` TEXT；`owner` TEXT；`status` TEXT（**不含 candidate**）；`version` INTEGER；
`evidence_level` TEXT；`session_id` TEXT；`fingerprint` TEXT；`retention` TEXT（**不含 archived**）。
唯一索引：`CREATE UNIQUE INDEX uq_memory_item_fp ON memory_item(fingerprint) WHERE status IN ('active','conflicted');`

### 3.3 `documents`（P4 独立阶段）
新增 `project_id`/`version`/`supersedes_id`/`doc_status`；版本化仅作用于**内部文档（url=NULL）**，**不改 url 约束**。

## 4. 权限判定规则（可测试）

### 4.1 执行上下文
`Exec = { userId, agentId, projectId, sessionId, taskId, surface, isHuman }`。
前台：agentId 来自 `hub_session.agent_id`（NULL→'builtin'）、projectId 来自 `hub_session.project_id`；
子代理/心跳：所属 Agent + 绑定项目；任务恢复：task→session；管理页：isHuman=true。

### 4.2 读谓词 `visible(item, exec)`
`status='active'`（或 isHuman 且显式请求 archived）**且**满足任一：
```
R1 item.owner='user'    AND scope IN ('profile','directive')                  // 全局权威画像
R2 item.owner='project' AND project_id = exec.projectId                        // 当前项目
R3 item.owner='agent'   AND agent_id = exec.agentId AND reusable=1             // Agent 私有(可跨项目复用)
R4 item.owner='agent'   AND agent_id = exec.agentId AND project_id IS NULL     // Agent 私有(非项目绑定)
R5 item.owner='task'    AND session_id = exec.sessionId                        // 当前任务临时记忆
R6 EXISTS(memory_share s WHERE s.item_id=item.id AND s.status='published'
          AND ((s.to_kind='project' AND s.to_ref=exec.projectId) OR s.to_kind='user'
            OR (s.to_kind='agent' AND s.to_ref=exec.agentId)
            OR (s.to_kind='session' AND s.to_ref=exec.sessionId)))             // 显式授权共享
R7 exec.isHuman = true                                                         // 管理页全量
```
**强制约束**：读入口 SQL 直接编码 `visible`（禁「先全量召回再 Java 过滤」）；未授权不因相似度高而返回；共享撤销后 `s.status='published'` 天然排除。

### 4.3 各读入口落地
`MemoryProvider`（ContextRequest 作用域）、`recall_memory` item/event、后台任务（所属 Agent+项目）、任务恢复（task→session）、
管理页（isHuman）——全部走 `visible`。

### 4.4 写权限
自动提炼→project/agent（**禁写 user**）；用户明说→user（候选）；人工→任意（校验矩阵）；巩固→同域；跨域升级→必经候选。

## 5. 生命周期与保留策略（分离）

### 5.1 status（含 archived） vs retention（保护级别）
`status`：active / superseded / expired / conflicted / revoked / **archived**。
`retention`：pinned / long / normal / ephemeral。`candidate` **不在 status**（只在 `memory_candidate`）。

### 5.2 转换守卫
`active→superseded`（superseded_by 非空且被取代者非 pinned）；`active→expired`（到期且 retention∈{normal,ephemeral}）；
`active→archived`（非 pinned 且非未完成事项）；`→revoked`（仅用户，写墓碑）；`conflicted→` 仅人工/巩固裁决。

### 5.3 动作权限与转换
去重/巩固合并/过期/裁剪/归档**限同域**且 protected 跳过；撤销由用户发起；人工编辑可跨越但记审计。
**跨项目/跨 Agent 覆盖仅 `memory_share` 或人工；自动流程一律禁止跨域。**

### 5.4 保护规则落到淘汰/去重/巩固
`protected = (retention='pinned') OR (owner='user' AND status='active') OR (kind='open_issue' AND status='active')`：
淘汰跳过、去重跳过、巩固不取代、episode 裁剪保护——不只体现在召回排序。

## 6. 用户画像：候选权威 · 证据 · 审批 · 撤销

### 6.1 候选唯一权威
候选只在 `memory_candidate`；`memory_item.status` 无 candidate。approve→**单事务** INSERT memory_item(active) + candidate=approved；
reject→rejected + 写墓碑。防重建：同 fingerprint 有 pending/active/墓碑（allow_relearn=0）→ 不再生成。
追证：同 fingerprint repeated → evidence_count++（不新建）。

### 6.2 可验证证据
**LLM 输出的 scope/evidence_level 只是声明，不是证据。** 仅三类证据：① `chat_message.id`（role='user'）；
② 用户 approve 事件（记 confirmed_by/at）；③ 人工操作（source=manual）。`evidence_refs` 至少命中一种，否则最多 `inferred`。

### 6.3 升级规则
`candidate→active(user)` 须**全部**满足：有可验证证据；`explicit` 或（`repeated` 且 count≥N 且人工 approve）；
**来源非创作类项目**；通过组合约束。**`repeated` 只提置信度，不突破来源/作用域。**

### 6.4 撤销
撤销→revoked + 墓碑 `(fingerprint, allow_relearn=0)`；同指纹不得再自动 approve；用户可选 allow_relearn=1 解除。

### 6.5 历史数据回填
| 现有数据 | owner | status | evidence_level |
|---|---|---|---|
| profile/directive 且 `source=auto` | user | **转候选**（写 memory_candidate） | inferred |
| profile/directive 且 explicit/manual | user | active | explicit |
| `scope=project` | project | 按现行→active/expired | inferred（无 project_id → 标「待复核」） |
| `scope=episode` 且 agent_id 非空 | agent | active/expired | inferred |
| `scope=episode` 且 agent_id 空 | agent(`'builtin'`) | active/expired | inferred（**不得提升为 user**） |
| 其它/未知 | agent（保守） | active | inferred（标「待复核」） |
`version=0`；fingerprint 按 `(owner,project_id,agent_id,scope,normalize(body))`；retention：pinned=1→pinned 否则 normal。

## 7. 并发 · 版本冲突 · 去重 · 恢复

- 指纹：`sha1(owner|project_id|agent_id|scope|normalize(body))`；item 部分唯一（active/conflicted），candidate pending 唯一。
- 乐观锁：`UPDATE ... version=version+1 WHERE id=? AND version=?`；0 行→409；冲突置 `conflicted` 保留双方；**禁 last-write-wins**。
- 去重/取代：同域；protected 跳过；superseded_by 成链保留历史。
- 恢复：写幂等（指纹）+ 巩固可重入；任务按 `(agent,project,task)` 还原快照 + 检查点。

## 8. Token 预算（配置化，无设置页）

```yaml
minis:
  memory:
    budget:
      total: 1600
      profile: 400
      project: 700
      agent: 300
      shared: 200
      episode: 200
    priority: "profile > project > agent > shared > episode"
```
规则：作用域过滤 → 混合打分 → 门控 → 预算内贪心填充；超预算移除顺序 episode→shared→agent→project→profile；
**pinned/正式画像/未完成事项永不因超预算移除**；单条超长先截断（正本可回读）；命中 id 写 `rag_recall`。
Working Set = 结构化 Project Memory ⊕ RAG chunk（P4 加 project 过滤）。

## 9. 集成（Turn / AgentRuntime / Session / 上下文 / RAG）
`ContextRequest` 增 `agentId/projectId/taskId`；Turn 构建只读快照、Turn 内写入不改本轮；`hub_session.project_id`、
`task.memory_checkpoint`；暂停恢复按 `(agent,project,task)`；发布发现走 grant 联合查询、不推送；
防递归（memory_cursor 水位、巩固不产事件、共享不触发提炼）；Working Set 调 `FastSearchService`。

## 10. API 变更
新增：`/api/projects`(CRUD)；`/api/memory/candidates` + `approve|reject`；`items/{id}/revoke`；`items/{id}/share`、`shared`、`shared/{id}/revoke`；`working-set`。
修改（新增可选参数）：items 列表支持 `projectId/owner/agentId/status`；items 新增支持 `owner/projectId/agentId/retention`；consolidate 返回冲突数；会话创建支持 `projectId`。

## 11. 数据库迁移

### 11.1 执行顺序与职责边界
| 组件 | 职责 | 幂等 |
|---|---|---|
| `schema.sql` | **唯一结构权威**（新表/新列全列） | IF NOT EXISTS |
| `SchemaColumnPatches` | 为老库补可空列 | PRAGMA 判存在 |
| `SchemaMigrations` | **仅数据级**（回填/部分唯一索引/必要表重建） | `_schema.version` 递增 + 单事务 |
顺序：schema.sql → Patches → Migrations（均 `@DependsOnDatabaseInitialization`）。Migrations 启动校验版本一致性，不重复加列。

### 11.2 迁移拆分
V2 `project` 表（P0a）；V3 memory_item 加列回填（P1）；V4 候选/共享/撤销表（P2）；V5 `hub_session.project_id`+`task.memory_checkpoint`（P1）；V6 documents 版本列（P4）。
**P0 只做迁移器基础设施 + 备份/校验/回滚工具，不塞业务迁移。**

### 11.3 备份/验证/恢复（实测流程）
① 迁移前 `VACUUM INTO 'data/backups/minis-<ts>.db'` + 复制 `data/article`；
② 迁移前后记录行数与抽样哈希；③ 按 `_schema.version` 单事务迁移；
④ 后验证 `integrity_check`=ok、`foreign_key_check` 空、行数一致、`owner/status IS NULL=0`、矩阵违例清单、「待复核」计数；
⑤ 失败中止（不递增版本）；⑥ 重复启动跳过；⑦ 失败恢复=备份覆盖 + 回滚旧程序。
> **本轮未实测**（受门禁约束）；P0 首验收项即在副本上完成并记录 ①–⑦。

### 11.4 一致性验证待办
`PRAGMA index_list(documents)` 确认 url 唯一约束；复核 url 是否有重复。

### 11.6 资料历史版本
拆 **P4**；仅内部文档（url=NULL）；**不改 url 约束**；若要版本化已发布文章另立专项并先修不一致。

## 12. 测试矩阵（阶段 / 执行方式 / 结果）

| # | 测试 | 阶段 | 真实行为断言 | 结果 |
|---|---|---|---|---|
| T1 | 跨项目隔离 | P1 | 小说项目写条目→软件项目 `visible` 命中 0 | 未执行 |
| T2 | 画像保护 | P1/P2 | 连写剧情→无 `owner=user,active` 新增 | 未执行 |
| T3 | 偏好升级 | P2 | project 不升 user；repeated+approve 才升 | 未执行 |
| T4 | 容量预算 | P3 | 注入 token ≤ budget；历史可按需回读 | 未执行 |
| T5 | 重要记忆保留 | P3 | 巩固后 pinned/画像/open_issue 仍 active | 未执行 |
| T6 | 并发写入 | P2 | 并发 update→一方 409 | 未执行 |
| T7 | 历史恢复 | P4 | 内部文档版本可定位、摘要溯源 | 未执行 |
| T8/T8b | 跨 Agent 共享/撤销 | P2 | 授权命中、未授权 0；撤销后 0 | 未执行 |
| T9 | 暂停恢复 | P5 | 重启恢复工作集、不误带他项目 | 未执行 |
| T10 | 过期修正 | P2 | 现行更新、旧值入历史链 | 未执行 |
| T11 | 候选审批撤销 | P2 | reject/revoke 后同指纹不再 approve | 未执行 |
| T12 | 零噪声 | P1 | 寒暄不产条目 | 未执行 |
| T13 | 迁移幂等 | P0 | 重复启动不重复执行、版本正确 | 未执行 |
| T14 | 备份恢复 | P0 | 副本备份→迁移→恢复一致 | 未执行 |
| T15 | 无项目默认作用域 | P1 | →owner=agent('builtin')，不产 user active | 未执行 |
| T16 | MemoryProvider 注入 | P1 | 断言 system 实际内容 | 未执行 |
| T17 | recall_memory 工具 | P1 | 断言工具实际命中 | 未执行 |
| T18 | 后台任务召回 | P2 | 心跳 Agent 谓词正确 | 未执行 |
| T19 | 项目切换 | P1 | 不返回旧项目条目 | 未执行 |
| T20 | 候选拒绝后重提炼 | P2 | 不再生成 pending | 未执行 |
| T21 | 恢复路径 | P5 | 中断后可恢复且作用域正确 | 未执行 |

既有测试保持全绿：`MemoryItemStoreTest`/`MemoryConsolidatorTest`/`MemoryRecallTest`/`MemoryEventStoreTest`/`LayeringTest`。

## 13. 阶段实施范围与验收

| 阶段 | 交付范围 | 不含 | 验收 |
|---|---|---|---|
| P0a 迁移地基 | SchemaMigrations 执行器 + 备份/校验/回滚工具；**空业务迁移** | 业务数据变更 | T13/T14 副本实测；真实库不被动 |
| P1 作用域骨架 | project 表 + hub_session.project_id + ContextRequest 作用域 + memory_item 加列回填 + 读侧 visible | 候选/共享/预算 | T1/T12/T15/T16/T17/T19 |
| P2 隔离与画像保护 | 候选权威 + 审批/撤销 + 乐观锁 + Agent 私有隔离 + 共享 | 大项目/归档 | T2/T3/T6/T8/T8b/T10/T11/T18/T20 |
| P3 大项目与预算 | 长度上限 + 配置化预算 + Working Set | 资料版本 | T4/T5 |
| P4 共享归档与资料版本 | 长期归档 + 语义合并 + documents 内部文档版本 | 文章 url 约束变更 | T7 |
| P5 并发/恢复/回归 | 任务检查点恢复 + 监控 + 全量回归 | — | T9/T21 + 全矩阵 |

纪律：先测试后实施；复用现有模块；`LayeringTest` 保持；不静默丢弃/错误归类现有数据。

## 14. 剩余风险与未解决项

| # | 问题 | 是否阻塞 | 处置 |
|---|---|---|---|
| R1 | `documents.url` 唯一约束与 `ON CONFLICT(url)` 不一致 | 阻塞 P4，不阻塞 P0/P1 | P0 副本 `PRAGMA index_list` 实测 |
| R2 | 迁移/备份/恢复**未实测** | **阻塞 P0 验收** | P0 首任务：副本实测并记录 |
| R3 | 历史 project 条目无 project_id | 不阻塞 | 标「待复核」，不臆造 |
| R4 | evidence_level 历史回填为 inferred | 不阻塞 | 已定；部分旧画像转候选待审 |
| R5 | 资料历史版本本轮不做 | 不阻塞 | 已拆 P4 |
| R6 | 多用户未启用 | 不阻塞 | 保留字段 |
| R7 | agent_id NULL→'builtin' 改语义 | 不阻塞但需评审 | 迁移报告计数 |
| R8 | MemoryIngest 内存缓冲重启丢 | 不阻塞 | 记录；记忆可重建 |

## 15. 交付对照
目标模型 §2；权限 §4；状态机 §5；画像候选 §6；并发 §7；Token 预算 §8；集成 §9；DB/API §3/§10；
迁移 §11；测试 §12；阶段 §13；风险 §14；RAG/URL 审计 §1.1/§1.2。

---

# 第四部分 · 执行记录

> 来源 `memory-v4-plan.md`（评审修订第二轮追加）。

## 明确决策

| 决策 | 结论 |
|---|---|
| Project | 独立 `project` 表；跨 Session/Task/Agent 业务工作域（方案 a） |
| 用户画像 | 全局权威；候选唯一权威 = `memory_candidate`（`memory_item.status` 无 candidate） |
| 可验证证据 | 仅「role=user 的原始消息 / 用户确认 / 人工操作」三类；LLM 声明不算证据 |
| 状态 vs 保留 | `status`（含 archived）与 `retention`（pinned/long/normal/ephemeral）职责分离 |
| 内置 Agent | `agent_id=NULL` 不再表示全局画像 → 规范化 `'builtin'`，归 `owner=agent` |
| 历史 `auto` 画像 | 回填 `evidence_level=inferred` 并转**候选**（不保持 active） |
| 去重/巩固 | 限定同作用域；`protected`（pinned/画像/open_issue）跳过 |
| 并发 | `fingerprint` 幂等键 + `version` 乐观锁，冲突 409，禁止 last-write-wins |
| Token 预算 | 配置化（total/profile/project/agent/shared/episode），无设置页 |
| 迁移 | `schema.sql`(结构) → `SchemaColumnPatches`(加列) → `SchemaMigrations`(数据)；P0 仅基础设施 |
| 资料版本 | 仅内部文档（url=NULL）；**不改 `documents.url` 约束**；拆 P4 |

## 剩余风险
见目标设计 §14（R1–R8）。**阻塞项**：R2 阻塞 P0 验收；R1 阻塞 P4。

## 阶段验收条件
| 阶段 | 验收 |
|---|---|
| P0a 迁移地基 | 副本上完成备份→迁移→校验→恢复演练并记录；真实库未被动 |
| P1 作用域骨架 | T1/T12/T15/T16/T17/T19 通过；现有测试全绿 |
| P2 隔离与画像保护 | T2/T3/T6/T8/T8b/T10/T11/T18/T20 通过 |
| P3 大项目与预算 | T4/T5 通过 |
| P4 共享归档与资料版本 | T7 通过；R1 实测确认 |
| P5 并发恢复回归 | T9/T21 + 全矩阵通过 |

## 实际测试结果
**全部 T1–T21：未执行**（本轮受「不批准不实施、不改真实库」门禁约束，未写代码、未跑测试、未触碰数据库）。

---

> **状态**：设计已按评审意见修订；**未实施、未改动真实库**。待批准后按 P0a → P1 先测试后实施。
