# Memory v6 · 记忆运行时标准（权威设计）

> **本文档地位**：Memory 体系的**唯一权威标准**。`docs/四层Memory架构.md` 是思想源（设计大纲），
> `docs/memory-v5.md` 是数据地基，本文在其之上定义**运行标准**。凡冲突以本文为准。
>
> **文档纪律**：每条结论标 **【代码事实】/【设计决议】/【待验证】**。
> 【代码事实】= 已在源码确认；【设计决议】= 本次确定、待实施；【待验证】= 需在副本库实测。
>
> **本轮范围**：只产出标准，不改动任何代码与真实库（`data/minis.db` 禁止默认重建）。
> 实施按 §19 分阶段，先测试后实施。

---

## 目录

- [0. 一句话概述](#0-一句话概述)
- [1. 缺陷总结（v5 落地审计）](#1-缺陷总结v5-落地审计)
- [2. 设计目标与标准条款](#2-设计目标与标准条款)
- [3. 概念模型（四层 × 四域 · 运行化）](#3-概念模型四层--四域--运行化)
- [4. 运行时契约（v6 核心）](#4-运行时契约v6-核心)
- [5. 模块与职责（对齐大纲 §五）](#5-模块与职责对齐大纲-五)
- [6. 数据模型 v6](#6-数据模型-v6)
- [7. 写入协议 v6](#7-写入协议-v6)
- [8. 检索与注入 v6](#8-检索与注入-v6)
- [9. L2 / L4 运行化](#9-l2--l4-运行化)
- [10. 受保护域 v6（画像 + 全局规则）](#10-受保护域-v6画像--全局规则)
- [11. role-play 隔离 v6](#11-role-play-隔离-v6)
- [12. 生命周期与保留 v6](#12-生命周期与保留-v6)
- [13. 三概念分离（表与 API 对照）](#13-三概念分离表与-api-对照)
- [14. 后端 API 变更](#14-后端-api-变更)
- [15. 前端「记忆中心」](#15-前端记忆中心)
- [16. 配置](#16-配置)
- [17. 迁移与运维](#17-迁移与运维)
- [18. 测试矩阵](#18-测试矩阵)
- [19. 分阶段实施](#19-分阶段实施)
- [20. 风险与未决项](#20-风险与未决项)
- [21. 附录](#21-附录)

---

## 0. 一句话概述

v5 把「记忆」从**扁平作用域标签**升级为 `owner × domain × layer` 的**存储模型**；
v6 把它升级为**记忆运行时**：

1. **作用域成为执行契约**——任何读写都在显式 `MemoryExec` 内发生，缺失即失败（§4）。
2. **四层各自跑起来**——L1/L2/L3/L4 每一层都有明确的生产者、消费者、生命周期与归档策略（§3.1）。
3. **三个概念三重分离**——对话历史 / 工作状态 / 长期记忆在存储、API、UI 上物理分开（§13）。
4. **一切流转可审计、可回放**——写入留 revision，注入留 snapshot，能回答「这一轮模型到底看见了什么」（§4.3、§12）。

一句话判据（继承 v5 并加强）：
> 画像不被项目污染、项目不串扰、重要信息不因容量与活跃度丢失、跨 Agent 共享可控可撤销、
> 系统能可靠暂停/恢复/迁移，且**每一轮模型看见了什么都能回放**。

---

## 1. 缺陷总结（v5 落地审计）

> 本节为 `memory-v5.md` 实施后的**实测审计**（源码逐行核对）。结论：v5 完成的是「读侧谓词的存储地基」，
> 但**没有运行时把作用域、生命周期、跨层流转真正跑起来**；写入侧只有「对话提炼」一条生产线。

| # | 缺陷 | 证据【代码事实】 | 后果 | v6 对策 |
|---|---|---|---|---|
| **D1** | **项目维度在真实运行中恒空** | 会话创建不接受 `projectId`（`ChatSessionService.create()` 传 null）；`HubSessionStore.setProject` 无调用者 | `exec.projectId` 恒空 → 提炼永远落 `AGENT(builtin)×L2`，**PROJECT×L1 项目事实永不产生**；项目间隔离、画像防污染都失去发生条件 | §4.1、§7.1、§15.2 |
| **D2** | **L3 任务/会话记忆没有生产者** | `MemoryExtractor.ownershipFor` 只产出 PROJECT 或 AGENT，从不产出 `OWNER_TASK` | L3 层在契约里存在、在运行时空白 | §3.1、§9.1、§6.1 |
| **D3** | **L3 会话隔离未生效** | 读谓词 R3 只按 `agent_id` 过滤；`AGENT×L3` 条目的 `session_id` 写了却不参与过滤 | 同一 Agent 在不同会话的临时工作记忆互相串（今天的临时结论注入到明天的会话） | §2.3 I5、§8.1 |
| **D4** | **L4 只有人工入口** | `memory_share` 仅由 `MemoryController` 的 `/share` 端点写入；工具目录无「发布/写记忆」工具 | 大纲要求的「Agent 主动发布结论、交接摘要」未落地；跨 Agent 协作仍是孤岛 | §9.2、§14 |
| **D5** | **自动提升层缺失** | v5 §7.2 的「L2→L4→L1 提升」无实现 | 经验永远停在私有层，无法沉淀为可复用知识 | §9.3 |
| **D6** | **只有 CHAT 面接入记忆** | `HeartbeatContextBuilder` 注释明说「该面没有额外 provider」；`SubagentContextProvider` 只有共享黑板；圆桌 `turnTracked()=false` 且不走 `ContextBuilder` | 心跳/子代理/圆桌 Agent **读不到自己的私有记忆**（L2 形同不存在） | §8.5、§4.2 |
| **D7** | **exec 事件不提炼** | `AgentRunner.onExecDone` 只写 `memory_event`，无提炼入口 | 子代理/心跳的结论不进 `memory_item`，长期记忆只见前台对话 | §7.3、§9.1 |
| **D8** | **工具召回绕过权限谓词** | `recall_memory` 走无作用域重载（`memoryItems.recall(query, scope, limit)`），直接查全量 active | 模型主动召回可读到**别的 Agent/项目**的条目——v5 要修的 G2 类漏洞残留 | §8.6 |
| **D9** | **`MemoryScopeCtx` 定义了但零调用** | 全仓无 `set/run/clear` 调用点；`MemoryExec.human/system/chat` 工厂亦无调用者 | 深层写读点拿不到作用域，D8 无法在局部修好 | §4.2 |
| **D10** | **无快照、无一致性策略** | 全仓无 `MemorySnapshot`；`MemoryProvider` 现取现算 | 无法回答「这轮模型看到了什么」；无法保证「本轮写入不回改本轮」 | §4.3、§4.4 |
| **D11** | **Working State 未接** | `task.memory_checkpoint` 列、`TaskStore.setCheckpoint`、`TaskCheckpointService.save/load` 全在，**无任何调用者** | 「暂停/恢复」链路空转；工作状态只能靠对话历史硬撑，违反大纲 §三 | §4.4、§13 |
| **D12** | **打分与预算细节偏差** | 打分仍是单维 `scopeWeight`；`budget.shared` 桶未参与分桶（共享条目落进 agent 桶）；`/api/memory/working-set` 未实现 | 层与域两个正交维度没有体现在召回取舍上 | §8.2、§8.3、§14 |
| **D13** | **验收未闭环** | `visible` 无直接单测；v5 §12 的 T1/T2/T15/T16/T17/T19 无对应断言；P0 迁移备份/恢复仍标【待验证】 | 关键不变量没有回归网，改动即回归风险 | §18、§17 |
| **D14** | **文档状态过期** | `memory-v5.md` 文末仍写「设计已成型，未实施、未改动真实库」，实际代码已实施大半（工作区有未提交改动） | 评估基准失真 | §21.3 |

**根因**：v5 的读写谓词与字段是「静态地基」，缺三样运行时物——
① 作用域契约的强制圈定（D9）；
② 各层真正干活的生产者与消费者（D1–D7）；
③ 可回放的一致性与工作状态（D10、D11）。

---

## 2. 设计目标与标准条款

### 2.1 三铁律 + 决定律（继承，不重复论证）

1. **隔离写入**、2. **按需读取**、3. **独立保留**（`四层Memory架构.md` §十一）。
4. **决定律**：记忆归属不由「谁产生」决定，而由「这条信息描述什么、适用于什么范围」决定
   —— `producer_agent` 只做审计，`owner_*` 决定一切权限。

### 2.2 v6 四法则【设计决议】

| 法则 | 含义 | 落地 |
|---|---|---|
| **法一 · 作用域即契约** | 任何读写都必须在显式 `MemoryExec` 内发生；缺失即失败 | `ScopeGuard`（§4.2） |
| **法二 · 层权分离** | domain 定归属与写权限，layer 定可见范围与生命周期；打分与预算按 `layer × domain` 双轴 | §8.2、§8.3 |
| **法三 · 三概念物理分离** | 对话历史 / 工作状态 / 长期记忆分表、分 API、分 UI；禁止「顺手」互写 | §13 |
| **法四 · 流转可审计可回放** | 每次写入留 revision；每轮注入留 snapshot watermark，可回放 | §6.1、§12.4 |

### 2.3 不变量（标准条款，全部可测试）

> 违反即**拒绝并记审计**，不静默纠正。§18 的测试矩阵逐条覆盖。

| # | 不变量 |
|---|---|
| **I1** | 无 `MemoryExec` 不得写库（`ScopeGuard` 抛错；生产环境降级为「不写 + 审计告警」） |
| **I2** | `USER_PROFILE × L1` 的 `active` 条目**只能**由 `UserProfileService.approve` 产生 |
| **I3** | `owner=USER` ⇒ `project_id / agent_id / session_id` 必须全为 NULL |
| **I4** | `owner=PROJECT` ⇒ `project_id` 非空；`owner=AGENT` ⇒ `agent_id` 非空；`owner=TASK` ⇒ `session_id` 或 `task_id` 非空 |
| **I5** | `layer=L3` ⇒ 必须绑定 `session_id` 或 `task_id`，且**读侧按绑定隔离**（不因同 Agent 而跨会话可见） |
| **I6** | `layer=L4` ⇒ 必须有可追溯发布来源（`memory_share` 或 `memory_promotion` 记录） |
| **I7** | 跨域/跨层升级必经 `memory_share` 或候选闸门；**自动流程一律不得跨域** |
| **I8** | `fingerprint` 在 `status ∈ {active, conflicted}` 内唯一 |
| **I9** | 更新一律乐观锁；影响 0 行 → `409`；**禁止 last-write-wins** |
| **I10** | `protected`（`pinned` / 正式画像 / `open_issue` / `handoff-pending`）不被淘汰、去重、取代 |
| **I11** | 写入落库前必须带 `source_kind` 与 `evidence_level`（缺省即拒绝） |
| **I12** | 每轮注入必须留 snapshot（`turnId + watermark`），可回放 |

---

## 3. 概念模型（四层 × 四域 · 运行化）

### 3.1 层定义与运行角色【设计决议】

| 层 | 名称 | **谁产生** | **谁消费** | 生命周期 | 归档/清理 |
|---|---|---|---|---|---|
| **L1** | Shared 共享权威 | ① 用户（显式/审批）② `UserProfileService.approve`（画像）③ 项目事实经提炼 + 项目绑定 | 有权限的所有 Agent（画像只读） | 长期 | 归档（不删除）；撤销写墓碑 |
| **L2** | Agent Private | ① 对话提炼（无项目时）② 会话收尾的 `MemoryDistiller`（L3→L2）③ 心跳/子代理的 exec 结论提炼 | **仅归属 Agent 自己**（注入 + `read_my_memory` 工具） | 长期 | 归档；语义巩固可取代 |
| **L3** | Task / Session | ① 会话工作集写入（进度/临时结论/工具结果/待办快照）② `memory_checkpoint` 工作状态 | 当前 session/task 参与方（**按 sessionId/taskId 隔离**） | 临时 | 会话/任务结束 → 蒸馏进 L2/L4 → 归档 |
| **L4** | Handoff / Knowledge | ① Agent 主动 `publish` ② `MemoryPromoter` 生成的发布候选 ③ 人工发布 | 由发布机制限定的接收方（拉取发现 + 收件箱） | 由知识价值决定 | 撤销 / 归档；交接项有状态机 |

### 3.2 流转管道（单向，禁止跳跃）【设计决议】

```
L3 任务/工作状态 ──收尾蒸馏──▶ L2 Agent 私有 ──提升──▶ L4 交接/知识 ──评审──▶ L1 共享权威
      ▲                          ▲                        ▲                    │
      │ 会话内即时写入            │ 对话提炼                │ 人工/自动发布候选    │
      │                          │                        │                    │
      └────────── 自动流程永远只在同域内；跨域只有两条合法路径 ────────────────┘
                    ① memory_share（显式授权）  ② 人工操作（记审计）
```

- **自动**：同域内的提炼（对话→条目）、巩固、去重、过期、L3 收尾蒸馏。
- **半自动**：`MemoryPromoter` 生成**候选**（发布候选 / 画像候选），需一次显式动作或政策放行。
- **人工**：升 L1、跨项目共享、显式遗忘。

### 3.3 域 × 层合法格 + 绑定矩阵（继承 v5 §4.4，v6 补两行）

```
                     Layer（谁能读 / 如何流转）
                 L1 共享    L2 Agent    L3 任务    L4 发布
USER_PROFILE  │ ①受保护画像│    —     │    —     │    —     │
PROJECT       │ ②项目事实 │ ③项目经验 │ ④项目工作 │ ⑤项目知识 │
AGENT         │    —      │ ⑥Agent私有│ ⑦Agent工作│ ⑧Agent发布│
TASK_EPISODIC │    —      │    —     │ ⑨事件/检查点│   —    │
```

| owner | 允许 domain | 允许 layer | 绑定要求 | 合法 |
|---|---|---|---|---|
| USER | USER_PROFILE | L1 | `project_id/agent_id/session_id` 全 NULL | ✅ |
| PROJECT | PROJECT | L1/L2/L3/L4 | `project_id` 非空 | ✅ |
| AGENT | AGENT | L2/L3/L4 | `agent_id` 非空；**L3 还须 `session_id` 或 `task_id` 非空** | ✅ |
| TASK | TASK_EPISODIC | L3 | `session_id` 或 `task_id` 非空 | ✅ |
| 任意 | 任意 | USER_PROFILE 且 layer≠L1 | — | ❌ |
| 任意 | 任意 | PROJECT 且 `project_id` 为空 | — | ❌ |
| 任意 | 任意 | AGENT 且 `agent_id` 为空 | — | ❌ |
| 任意 | 任意 | L3 且 `session_id`/`task_id` 均为空 | — | ❌ |
| 任意 | 任意 | L4 且无发布来源 | — | ❌ |

### 3.4 身份上下文（role-play）与作用域栈【设计决议】

`MemoryExec` 携带 `identity ∈ {REAL, FICTION, PROJECT_PREF, INFERRED}`，来自会话/项目标记：

```
执行入口 → 组装 MemoryExec（含 identity）
           ├── identity=FICTION   → 写侧强制 source_kind=fiction，永不进画像；读侧注入时标注「虚构设定」
           ├── identity=PROJECT_PREF → 只落项目域
           └── identity=REAL      → 可进入画像候选（仍需证据）
```

---

## 4. 运行时契约（v6 核心）

### 4.1 `MemoryExec` v6（在现有基础上扩展）

```java
// 现有（【代码事实】ai/core/MemoryExec.java）：
record MemoryExec(String userId, String agentId, String projectId, String sessionId,
                  Long taskId, String surface, boolean human, boolean narrative)

// v6 扩展【设计决议】：
record MemoryExec(String userId, String agentId, String projectId, String sessionId, Long taskId,
                  Surface surface,      // 枚举化：CHAT | SUBAGENT | HEARTBEAT | ROUNDTABLE | PLAN | SYSTEM
                  boolean human,
                  boolean narrative,
                  String  turnId,       // 本轮唯一 id：snapshot 与 revision 的回放键
                  Identity identity)    // REAL | FICTION | PROJECT_PREF | INFERRED
```

**变更点**：`surface` 由字符串改枚举（编译期收口）；新增 `turnId`（回放）与 `identity`（role-play 显式化）。
`narrative` 保留为兼容位，由 `identity` 派生（`identity=FICTION → narrative=true`）。

### 4.2 `MemoryScopeCtx` 强制化 + `ScopeGuard`【设计决议】

1. **圈定点**（每一条执行入口，无遗漏）：

| 入口 | 圈定内容 | 现状 |
|---|---|---|
| 前台对话 `ChatTurnRunner` | `CHAT` + agent/project/session/task/turnId/identity | 已有 `new MemoryExec`，**未进 `MemoryScopeCtx`** |
| 子代理 `SubagentService` | `SUBAGENT` + 派发方 agent + project + session | 无 |
| 心跳 `HeartbeatService` | `HEARTBEAT` + agent + 绑定 project | 无 |
| 圆桌 `RoundtableChatService` | `ROUNDTABLE` + 发言成员 + session | 无 |
| 职责/计划 `AgentRunner` | `PLAN` + agent + project | 无 |
| 管理页 `MemoryController` | `SYSTEM` + `human=true` | 读全量，未走谓词 |

2. **`ScopeGuard`**：写侧统一入口先取 `MemoryScopeCtx.get()`。
   - 为空 → **dev/测试抛 `IllegalStateException`**；生产 → 拒绝写入 + `memory_audit` 记一条 `scope_missing` 告警。
   - 不为空 → 校验 §2.3 的 I3–I6，违例即拒。
3. **读侧**：`MemoryRetriever` / 工具 **必须**用 `exec`；没有作用域时退化为「只读正式画像」，绝不退化为「读全量」（修 D8）。

### 4.3 `MemorySnapshot`（每轮只读视图）【设计决议】

```java
record MemorySnapshot(
    String  turnId,        // 回放键
    MemoryExec exec,       // 本轮作用域
    long    watermark,     // 记忆条目的全局水位（max(id) 或 commit_seq）
    List<SnapshotEntry> entries,  // 预算内选中的条目视图（含 layer/domain/来源）
    long    builtAt,
    long    ttlMs          // 快照有效期；超期下一轮重建
) {}
```

- **构建时机**：Turn 开始时由 `MemorySnapshotBuilder` 构建（作用域过滤 → 混合检索 → 预算填充）。
- **注入用它**：本轮 system 只渲染快照内条目，并在末尾标注来源层。
- **落库可回放**：`memory_snapshot` 表存 `turnId → watermark + 命中的 itemId 列表`（只存引用，不存正文），
  供「快照回放」页回答「这一轮模型看见了什么、为什么是这些」。

### 4.4 Turn 生命周期与一致性【设计决议】

```
Turn 开始 ──▶ 组装 MemoryExec（含 turnId/identity）→ 圈定 MemoryScopeCtx
           ──▶ 构建 MemorySnapshot（只读，记 watermark）
Turn 内   ──▶ 写侧先入 StagedWrite 缓冲（不落 memory_item）
           ──▶ 本轮注入仍用 Turn 开始时的快照（写入不回改本轮）
Turn 结束 ──▶ 提交 StagedWrite：ScopeGuard → Policy → fingerprint 幂等 → 乐观锁 → revision
           ──▶ 写 memory_snapshot 回放记录；清理 MemoryScopeCtx
下一轮    ──▶ 读最新有效记忆（新快照）
```

| 记忆类别 | 一致性策略 |
|---|---|
| 一般记忆 | 本轮用启动快照；**下一轮**才可见本轮新增 |
| 一般更新 | 下一轮读最新版本 |
| 安全 / 权限 / 全局规则类 | **强制刷新或实时校验**（`exec` 每轮重算，权限不缓存） |

---

## 5. 模块与职责（对齐大纲 §五）

> 大纲的模块清单（记忆大纲 §五）：`MemoryService / MemoryPolicy / MemoryRetriever / MemoryConsolidator /
> MemoryPublisher / AgentRuntime / TurnScheduler`。v5 未按此命名落地，v6 补齐并给出与现状的映射。

| 模块 | 职责 | v6 落点 | 依赖方向 |
|---|---|---|---|
| `MemoryService` | **统一门面**：读（recall/snapshot）、写（stage/commit）、提升、发布、撤销 | **新建**（聚合下列组件） | `ai.memory` |
| `MemoryPolicy` | **唯一判定**：`canRead/canWrite/canPromote/canRevoke(exec, entry)`；把策略编译为 SQL 谓词片段 + Java 判定两份等价实现（单测互证） | **新建**（现有 `visible` SQL 收敛进来） | `ai.memory` |
| `MemoryRetriever` | 作用域过滤 → FTS5/结构化 → 向量 → 图 → 融合打分 | **改造**（现 `MemoryItemRecall` + 新增融合与图轴） | `ai.memory` |
| `MemorySnapshotBuilder` | 构建本轮只读快照 + 落回放记录 | **新建** | `ai.memory` |
| `MemoryWriter` / `StagedWriter` | push 门面 + 轮内写缓冲 | **改造**（现 `MemoryWriter` 已有事件 push；新增 staged） | `ai.memory` |
| `MemoryConsolidator` | 确定性去重 / 裁剪 / 语义取代（已有） | **已存在**（`MemoryConsolidator` + `MemorySemanticConsolidator`） | `ai.memory` |
| `MemoryDistiller` | L3 收尾蒸馏：会话/任务结束 → 工作集提炼为 L2/L4 候选 | **新建** | `ai.memory` |
| `MemoryPromoter` | 提升层：L2→L4、L4→L1 候选（受政策与阈值约束） | **新建** | `ai.memory` |
| `MemoryPublisher` | L4 发布/撤销/收件箱（现 `MemoryShareStore` 收敛进来） | **改造** | `ai.memory` |
| `UserProfileService` | 受保护画像域（候选/证据/审批/撤销） | **已存在**（补：规则域、复检到期） | `ai.memory` |
| `WorkingSetService` | 工作状态：会话工作集 + 检查点 + 归档 | **新建** | `ai.memory` |
| `MemoryHealthService` | 三病体检（污染 / 挤占 / 漂移） | **新建** | `ai.memory` |
| `MemoryAuditService` | revision 写入、审计查询、purge 影响面预览 | **新建** | `ai.memory` |
| `AgentRuntime` / `TurnScheduler` | 圈定作用域、构建快照、提交 staged（不拥有长期记忆） | **改造**（`ChatTurnRunner` / `AgentRunner` 接入） | `agent.*` |

**分层红线不变**【代码事实】：`ai.memory` 与 `ai.knowledge` 平级，**不得依赖 `agent.*`**
（`LayeringTest`）；写入是 push，读取走 provider/tool，跨层「当前是谁」用 `ai.core`。

---

## 6. 数据模型 v6

### 6.1 新增表（V6）【设计决议】

```sql
-- 写入审计：每次提交留一条（可回放、可追责、可 purge 溯源）
CREATE TABLE IF NOT EXISTS memory_revision (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id       INTEGER,                    -- 关联条目（新建时可为 NULL，后补）
  op            TEXT NOT NULL,              -- create|update|supersede|status|promote|publish|revoke|purge
  actor_kind    TEXT NOT NULL,              -- AGENT|USER|SYSTEM
  actor_id      TEXT,                       -- agent.id / 'user' / 'migrator'
  turn_id       TEXT,                       -- 回放键（MemoryExec.turnId）
  before_json   TEXT,                       -- 变更前快照（JSON，审计用）
  after_json    TEXT,                       -- 变更后
  reason        TEXT,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rev_item ON memory_revision(item_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rev_turn ON memory_revision(turn_id);

-- 工作状态（L3 的工程实现）：会话/任务级临时物，与长期记忆物理分离
CREATE TABLE IF NOT EXISTS memory_work (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  task_id     INTEGER,
  agent_id    TEXT,
  project_id  TEXT,
  kind        TEXT NOT NULL,               -- progress|temp_conclusion|tool_result|decision_in_flight|todo_snapshot
  title       TEXT,
  body        TEXT NOT NULL,
  ref_type    TEXT,                        -- chat_message|task|tool_exec（指回正本）
  ref_id      INTEGER,
  status      TEXT NOT NULL DEFAULT 'open',-- open|closed|distilled|archived
  expires_at  INTEGER,                     -- TTL：到期不再注入
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_work_session ON memory_work(session_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_work_task ON memory_work(task_id, status);

-- 提升记录（L2→L4 / L4→L1 的来源凭据；L4 必填，满足 I6）
CREATE TABLE IF NOT EXISTS memory_promotion (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id       INTEGER NOT NULL,
  from_layer    TEXT NOT NULL,
  to_layer      TEXT NOT NULL,
  policy        TEXT NOT NULL,             -- auto|manual|approved
  evidence_count INTEGER NOT NULL DEFAULT 1,
  state         TEXT NOT NULL DEFAULT 'candidate', -- candidate|applied|rejected
  note          TEXT,
  created_at    INTEGER NOT NULL,
  applied_at    INTEGER
);
CREATE INDEX IF NOT EXISTS idx_promotion_item ON memory_promotion(item_id, state);

-- 交接条目状态（L4 的「交到谁手上、做到哪一步」）
CREATE TABLE IF NOT EXISTS memory_handoff (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id      INTEGER NOT NULL REFERENCES memory_item(id),
  to_kind      TEXT NOT NULL,              -- agent|project|session|user
  to_ref       TEXT,
  state        TEXT NOT NULL DEFAULT 'offered', -- offered|accepted|done|rejected
  note         TEXT,
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_target ON memory_handoff(to_kind, to_ref, state);

-- 快照回放：只存引用（turnId → 命中条目与水位），不存正文
CREATE TABLE IF NOT EXISTS memory_snapshot (
  turn_id     TEXT PRIMARY KEY,
  session_id  TEXT,
  agent_id    TEXT,
  project_id  TEXT,
  watermark   INTEGER NOT NULL,
  item_ids    TEXT,                        -- JSON 数组
  tokens      INTEGER NOT NULL DEFAULT 0,
  built_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_snapshot_session ON memory_snapshot(session_id, built_at);
```

### 6.2 `memory_item` 增量列（幂等 ADD COLUMN，可空）

```sql
ALTER TABLE memory_item ADD COLUMN identity       TEXT;  -- REAL|FICTION|PROJECT_PREF|INFERRED
ALTER TABLE memory_item ADD COLUMN promoted_from  INTEGER; -- 指向 memory_promotion.id（提升来源）
ALTER TABLE memory_item ADD COLUMN expires_soft   INTEGER; -- 软过期（TTL 到期，不删除、只不注入）
```

### 6.3 索引与唯一约束

- 沿用 v5：`uq_memory_item_fp`（`status ∈ {active,conflicted}` 内按 `fingerprint` 唯一）、`idx_memory_item_owner`。
- **新增**：`idx_memory_item_layer ON memory_item(owner, layer, status)`（按层检索）；
  `idx_memory_item_session ON memory_item(session_id, status)`（L3 隔离读，修 D3）。

### 6.4 迁移 V6（在 v5 迁移之后追加一版）

| 步骤 | 动作 | 校验 |
|---|---|---|
| 1 | **回填会话↔项目**：为已有 `hub_session` 提供默认项目（可为空项目「默认工作域」）；不改历史语义 | 计数上报 |
| 2 | **L3 归属修正**：`layer='L3' AND session_id IS NULL AND task_id IS NULL` 的历史条目 → 保守降为 L2（并计入「待复核」） | 违例数 = 0 或全部落「待复核」 |
| 3 | `identity` 回填：`source_kind='fiction' → FICTION`，其余 → `INFERRED`/`REAL`（两者分明） | 无 NULL |
| 4 | 新建 5 张表 + 新列 + 新索引 | `integrity_check=ok`、`foreign_key_check` 空 |
| 5 | 建立 `watermark`（当前 `max(id)`） | 幂等重复启动不重复执行 |

> **【待验证】** 备份 / 迁移 / 恢复全流程必须在**副本库**实测（见 §17），本版不接受「未实测即上线」。

---

## 7. 写入协议 v6

### 7.1 六步协议【设计决议】

```
① 产生     Agent / 用户 / 系统 产出事实 / 假设 / 结论 / 经验 / 进度 / 工作状态
             ↓
② 归类     判定 domain × layer × scope × identity（按「描述什么」，不按「谁产生」）
             ↓
③ 判定     ScopeGuard（I1/I3–I6）+ MemoryPolicy（权限）+ 质量检查（来源 / 置信度 / 冲突 / 时效 / 证据）
             ↓
④ 暂存     写入 StagedWrite（本轮缓冲；本轮注入不受它影响）
             ↓
⑤ 提交     fingerprint 幂等 → 乐观锁 → 落 memory_item + memory_revision（审计）
             ↓
⑥ 传播     同域即时可见；L4 需发布动作；L1 需闸门；收件箱登记（不推送）
```

### 7.2 写入通道分级（对齐大纲 §六.2 的「不要所有 Agent 都能自由改公共记忆」）

| 通道 | 允许对象 | 条件 |
|---|---|---|
| **append**（追加） | 任意 Agent | 落 L2 / L3（自己的域）；`fingerprint` 幂等 |
| **controlled-update**（受控更新） | 归属者 | 域内更新 + 乐观锁 + revision |
| **conflict-resolution**（冲突裁决） | 人工 / 巩固 | 双方保留，置 `conflicted`，不覆盖 |
| **high-risk**（高风险：画像、全局规则、权限类） | **仅** `UserProfileService` / 人工 | 候选 → 证据 → 审批 → revision |

### 7.3 各入口的落点（修 D1–D7）

| 入口 | 写什么 | 落点 | 现状 |
|---|---|---|---|
| 前台对话 | 提炼条目 | 有项目 → `PROJECT×L1`；无项目 → `AGENT×L2` | 已有，但项目恒空（D1） |
| 前台对话 | 工作集 | `memory_work`（L3，绑 session） | **新增** |
| 前台对话 | 画像候选 | `memory_candidate` | 已有 |
| 子代理 / 心跳 | 事件 + **结论提炼** | `AGENT×L2`（带 project 则 `PROJECT×L2`） | 事件有、提炼缺（D7） |
| 圆桌 | 纪要 + 结论提炼 | 同上 | **新增** |
| 会话/任务结束 | L3 蒸馏 | `memory_work` → `AGENT×L2` / L4 候选 | **新增** |
| Agent 主动 | 发布 | `memory_share` + `memory_promotion` + `memory_handoff` | **新增**（D4/D5） |

---

## 8. 检索与注入 v6

### 8.1 作用域先行（继承 v5，补 L3 隔离）

读谓词 `visible(item, exec)`：`status='active'`（`isHuman` 可请求 `archived`）且满足任一：

```
R1  owner='USER'    AND domain='USER_PROFILE'                      // 正式画像（只读）
R2  owner='PROJECT' AND project_id = exec.projectId                // 当前项目
R3  owner='AGENT'   AND agent_id = exec.agentId
      AND (layer <> 'L3' OR session_id = exec.sessionId
                            OR (task_id IS NOT NULL AND task_id = exec.taskId))   // ← 新增：L3 隔离（修 D3）
R4  owner='TASK'    AND (session_id = exec.sessionId OR task_id = exec.taskId)
R5  EXISTS(memory_share s ... status='published' AND 目标命中 exec)   // 显式授权共享
R6  exec.human = true                                                // 管理页全量
```

- SQL 直接编码（禁止全量召回后 Java 过滤）；未授权不进候选池。
- `owner=AGENT` 且 `layer=L4` 的条目**必须**经 R5 命中（满足 I6），不得靠 R3 蒙混。

### 8.2 融合打分（修 D12）

```
score = cosine(query, item) × W_layer(layer) × W_domain(domain) × W_evidence(evidence_level)
```

| 权重 | 取值（可配置） |
|---|---|
| `W_layer` | `L1 1.20 · L2 1.00 · L3 0.85 · L4 1.05` |
| `W_domain` | `USER_PROFILE 1.15 · PROJECT 1.05 · AGENT 1.00 · TASK_EPISODIC 0.80` |
| `W_evidence` | `explicit 1.10 · repeated 1.00 · inferred 0.90` |

**时序轴只过滤、不排序**（继承 v5 的正确取舍）；强度永不用于淘汰。

### 8.3 Token 预算按层（修 `budget.shared` 未生效）

```yaml
minis:
  memory:
    budget:
      total: 1600
      shared: 400      # L1：画像 + 项目事实 + 全局规则
      agent: 300       # L2
      task: 300        # L3
      publish: 200     # L4
      episode: 200
    never_evict: "pinned,profile,open_issue,handoff_pending"   # 永不因超预算移除
```

分桶按 **layer**（不是 scope）；超预算移除顺序：`episode → publish → task → agent → shared`。

### 8.4 注入渲染（按层标注可信度）

```
【用户画像】…（仅正式画像，L1）
【项目事实】…（当前项目，L1）
【我的经验】…（本 Agent 私有，L2）
【本会话】…（临时，L3）
【共享知识】…（他人发布，L4 · 来源：Agent X / 项目 Y）
```

### 8.5 各面召回链路（修 D6）

| Surface | 读取范围 | 落点 |
|---|---|---|
| CHAT | 画像 + 当前项目 + 本 Agent + 本会话 + 授权共享 + 工作集（L3） | `MemoryProvider`（已有）+ L3 桶 |
| SUBAGENT | 派发方的 **Agent/项目** 作用域 + 本会话黑板（L3） | `SubagentContextProvider` 扩展 |
| HEARTBEAT | 该 Agent **自己的 L2** + 绑定项目 + 未完成手交（L4） | `HeartbeatContextBuilder` 扩展 |
| ROUNDTABLE | 同名册：成员各自的公开面（L1 共享 + L4 已发布）+ 本会话纪要 | 圆桌新增 provider |
| PLAN | 归属 Agent + 项目 | `AgentRunner` 接入 |

### 8.6 工具面统一（修 D8）

- `recall_memory` 改为 `MemoryService.recall(exec, query, scope, limit)`——**必须**带作用域；
- 新增 `read_my_memory`（只读自己 L2）、`read_working_set`（读当前会话工作集）；
- `scope` 位图扩为合法 scope 全集，`layer` 可作为过滤参数（`layer=L2` 查我的经验）。

---

## 9. L2 / L4 运行化

### 9.1 Agent 私有记忆（L2）

- **读**：注入（该 Agent 自己的面）+ `read_my_memory` 工具。
- **写**：只能 append 到 `owner=AGENT` 且 `agent_id=自己`；改/删需策略放行（高风险通道）。
- **`agent_id NULL → 'builtin'`** 语义不变（v5 已规范化）。

### 9.2 发布 / 交接 / 收件箱（L4，修 D4）

- **发布动作**：新工具 `publish_knowledge(itemId | draft, to_kind, to_ref, note)`；
  落 `memory_share`（可见性）+ `memory_promotion`（来源凭据）+ 可选 `memory_handoff`（交接状态）。
- **发现方式**：**拉取**（R5 联合查询）+ 收件箱列表；**不推送**（避免通知风暴）。
- **交接状态机**：`offered → accepted → done`（可 `rejected`）；`handoff-pending` 属 `protected`（I10）。
- **撤销**：`status='revoked'` → R5 天然排除。

### 9.3 自动提升（修 D5）

| 路径 | 触发 | 政策 |
|---|---|---|
| L3 → L2 | 会话/任务结束，`MemoryDistiller` | 自动（同域，只读 L3、只写 L2） |
| L2 → L4 | `evidence_count ≥ N` 且被复用 ≥ M 次 | 生成**发布候选**；自动放行需 `promote.auto=true` |
| L4 → L1 | 评审通过 + 非创作来源 + 证据等级达标 | **必经闸门**（人工或 `UserProfileService`） |

**防递归三闸**（继承 v5 §7.2）：巩固不产事件；巩固读 `memory_cursor` 水位；共享不触发提炼。

---

## 10. 受保护域 v6（画像 + 全局规则）

### 10.1 画像三类（继承大纲 §九）

| 类 | 例子 | 处理 |
|---|---|---|
| A 稳定事实 / 长期偏好 | 用 Java；偏好低成本方案 | 长期；`evidence_level ∈ {explicit, repeated}` 才可改 |
| B 有时效的状态 | 近期在做的事、短期计划 | 带 `valid_until`；到期降权或**复检到期队列** |
| C 待确认候选 | 小说里多次选某叙事风格 | 只记证据，不自动升级 |

### 10.2 全局规则（`directive`）升为 L1 一等公民【设计决议】

v5 把 `directive` 与 `profile` 同归「画像类」进候选闸门；v6 区分：

| 类型 | 生效范围 | 注入策略 | 更新通道 |
|---|---|---|---|
| 用户画像（`profile`） | 全局 | 恒定注入（预算内） | 高风险管理（候选+审批） |
| **全局规则（`rule`）** | `global / project / agent` 三档 | **恒定注入且豁免预算**（安全类） | 高风险管理；变更**实时生效**（§4.4 强制刷新） |
| 记忆行为指令（`directive`） | 全局 | 恒定注入 | 高风险管理 |

- 规则带 `scope_ref`（作用范围）与 `version`，每次变更留 revision，可回滚。

### 10.3 升级闸门（`candidate → active(USER_PROFILE×L1)`）须全部满足

1. 有**可验证证据**（`chat_message.id` 原件 / 用户 approve 事件 / 人工操作）；
2. `explicit` 或（`repeated` 且 `evidence_count ≥ N` 且人工 approve）；
3. 来源非创作类（`project.narrative=0` 或 `identity=REAL`）；
4. 通过 §3.3 组合约束。

---

## 11. role-play 隔离 v6

- 会话级 `identity`：新建/编辑会话可标 `REAL | FICTION`；创作类项目默认 `FICTION`。
- **写侧**：`FICTION` ⇒ `source_kind='fiction'` 且 `owner` 最高只到 `PROJECT`；画像候选直接拒收（已实现，v6 扩到所有面）。
- **读侧**：`FICTION` 会话仍可注入真实画像，但**必须加标注**：「真实用户信息，勿混入剧情设定」。
- **跨会话不串**：`FICTION` 会话的 L3 工作集带 identity，蒸馏到 L2 时保留 `identity=FICTION`；永不进入画像。
- **反例必须挡住**：连续三天在小说项目要求「增加心理描写」→ 只能推断「**小说项目内**当前偏好更细腻的心理描写」。

---

## 12. 生命周期与保留 v6

### 12.1 状态机

`staged → active → { superseded | expired | archived | conflicted | revoked | purged }`
（`staged` 为本轮缓冲态，仅存在于 StagedWrite，不落库）

### 12.2 保留与 TTL

- `retention`：`pinned | long | normal | ephemeral`（继承）。
- **TTL**：`memory_work.expires_at`（工作状态到期不注入）；`memory_item.expires_soft`（软过期）。
- **禁则**：淘汰不得以「最近访问 / 写入频率 / 相似度 / 条数」为依据（铁律三）。

### 12.3 归档与清理

| 对象 | 结束时机 | 处理 |
|---|---|---|
| L3 工作集 | 会话/任务结束 | 蒸馏 → 归档（`status='archived'`，不物理删） |
| L2 经验 | 语义巩固取代 | `superseded` + `invalid_at` 关闭（保留历史） |
| L4 交接 | done / rejected | 归档；共享撤销走 `revoked` |
| episode | 超 `episodeMax` | 归档（继承 v5） |

### 12.4 双时态点查与回放

- `as-of` 查询：`GET /api/memory/as-of?at=<ts>&scope...` → 返回该时刻有效的记忆视图（`valid_at ≤ at < invalid_at`）。
- 快照回放：`GET /api/memory/snapshot/{turnId}` → 该轮命中的条目 + 水位 + 预算消耗。

### 12.5 显式遗忘（purge）【设计决议】

- 入口：按 `fingerprint` / 来源 / 时间范围撤回。
- 必须**先给影响面预览**（将影响多少条、多少 Agent 的 L4、是否命中画像），确认后：
  `revoked` + 墓碑（`allow_relearn=0`）+ revision 记 `purge`。

---

## 13. 三概念分离（表与 API 对照）

| 概念 | 载体（存储） | 读 API | 写入口 | UI 分区 |
|---|---|---|---|---|
| **Conversation History** | `chat_message` / `hub_session` / `turn` | 会话消息接口 | 轮次落库 | 对话页（原样） |
| **Working State** | `task` + `task.memory_checkpoint` + `memory_work` + 子代理黑板 `ScratchpadStore` | `/api/memory/working-set`、子代理页 | `WorkingSetService.save/checkpoint` | 「工作集」页签 |
| **Long-term Memory** | `memory_item` / `memory_event` / `memory_candidate` / `memory_edge` / `memory_share` | `/api/memory/*` | `MemoryService.write` / 提炼 / 审批 | 「记忆中心」 |

**硬规则**：三者物理分离，禁止「把一段对话中的临时假设直接沉淀为永久记忆」，
也禁止「每次恢复执行都重新加载全部历史消息」（恢复只读工作集 + 检查点）。

---

## 14. 后端 API 变更

### 14.1 新增

| 端点 | 说明 |
|---|---|
| `POST /api/sessions`（增参 `projectId`） | 会话绑定项目（**修 D1 的关键**） |
| `PUT /api/sessions/{id}/project` | 切换/解绑项目 |
| `GET /api/projects`（CRUD 已有） | 项目工作域 |
| `GET /api/memory/working-set` | 当前会话/任务工作集视图 |
| `POST /api/memory/working-set`、`DELETE .../{id}` | 工作集增删（人工） |
| `GET /api/memory/as-of?at=` | 双时态点查 |
| `GET /api/memory/snapshot/{turnId}` | 快照回放 |
| `GET /api/memory/inbox` | L4 收件箱（拉取发现） |
| `POST /api/memory/inbox/{id}/accept \| reject` | 交接状态机 |
| `POST /api/memory/items/{id}/promote` | 提升（L2→L4 候选 / 应用） |
| `GET /api/memory/promotions` | 提升候选列表 |
| `GET /api/memory/revisions?itemId=` | 写入审计 |
| `POST /api/memory/purge/preview`、`POST /api/memory/purge` | 显式遗忘（先预览） |
| `GET /api/memory/health` | 三病体检报告 |

### 14.2 修改（向后兼容）

- `GET /api/memory/items` 增 `layer / identity / sessionId` 过滤。
- `POST /api/memory/consolidate` 返回 `{deduped, trimmed, merged, promoted}`。
- `GET /api/memory/summary` 增按 **layer** 分组与候选/共享/交接计数。

---

## 15. 前端「记忆中心」

> 现状【代码事实】：`MemoryPanel.tsx` 已有 `items | candidates | shared` 三页签、按 domain 分组、layer 标签。

### 15.1 页面结构（六区）

| 区 | 内容 | 依赖 API |
|---|---|---|
| **四层泳道** | 按 L1/L2/L3/L4 分列，列内按 domain 分组；每条显示来源（identity/source_kind）徽标 | `items?layer=` |
| **项目工作域** | 顶部项目选择器；展示该项目的事实/决策/经验/工作集/关联 RAG | `projects`、`working-set` |
| **候选审批台** | 画像候选 + **证据溯源**（点击跳转 `chat_message` 原文）+ 批量批准/拒绝 | `candidates`、`approve/reject` |
| **发布与收件箱** | 已发布（发件）+ 收件箱（拉取发现）+ 交接状态机操作 | `shared`、`inbox` |
| **时间轴 / 点查** | 时间滑杆 → `as-of` 视图；展示 `superseded` 链（谁取代谁、何时） | `as-of`、`edges` |
| **记忆体检** | 三病报告卡（见 15.4）+ 一键「立即整理/巩固」 | `health`、`consolidate` |
| 附加 | **快照回放**：输入/选择 turn → 看该轮注入清单 | `snapshot/{turnId}` |

### 15.2 会话与项目（修 D1 的前端半边）

- 新建会话 / 会话设置里提供**项目选择器**（默认「无项目 = 个人工作域」）。
- 侧边栏会话条目显示项目标签；切项目即切记忆作用域（旧项目条目不再注入，但**不删除**）。

### 15.3 对话内操作

- 每条消息右侧「入库」菜单：**存为记忆**（→ 候选/条目，产生 `explicit` 证据）、
  **发布为知识**（→ L4，选接收方）、**标记虚构**（→ `identity=FICTION`，防污染）。
- 这三项把大纲 §5.5「可验证证据」的来源交到用户手上，补上证据链缺口。

### 15.4 记忆体检（新特性，直击大纲 §七 三病）

| 报告项 | 指标 | 阈值预警 |
|---|---|---|
| **污染** | 被拒收的 fiction→画像候选数 / 越权写入尝试数 | > 0 即列出明细 |
| **挤占** | 各 domain×layer 的预算占用与挤出条数；单域占比 | 单域 > 60% 预算 → 告警 |
| **漂移** | 跨 scope 的 `superseded` 链数量；临时决策覆盖长期事实的事件 | 出现即列出链条 |
| **健康度** | 快照回放覆盖率、指纹重复率、`protected` 命中率 | 供趋势观察 |

### 15.5 组件与状态

- 新增页面/组件建议：`MemoryCenter`（父）、`LayerBoard`、`ProjectWorkbench`、`CandidateDesk`、
  `PublishInbox`、`MemoryTimeline`、`HealthReport`、`SnapshotReplay`、`MemoryItemCard`（含 identity 徽标）。
- API 客户端扩展：`api/client.ts` 增 §14 的端点封装；沿用现有 `shareMemoryItem` 风格。

---

## 16. 配置

```yaml
minis:
  memory:
    enabled: true
    scope:
      enforce: true              # ScopeGuard：缺作用域即拒绝写入（I1）
    snapshot:
      ttl-ms: 120000             # 快照有效期
      replay: true               # 落 memory_snapshot 回放记录
    staging:
      enabled: true              # 轮内写缓冲（本轮写入不回改本轮）
    budget:
      total: 1600
      shared: 400
      agent: 300
      task: 300
      publish: 200
      episode: 200
    weights:
      layer:   { L1: 1.20, L2: 1.00, L3: 0.85, L4: 1.05 }
      domain:  { USER_PROFILE: 1.15, PROJECT: 1.05, AGENT: 1.00, TASK_EPISODIC: 0.80 }
      evidence:{ explicit: 1.10, repeated: 1.00, inferred: 0.90 }
    promote:
      auto: false                # L2→L4 自动放行（默认候选）
      min-evidence: 3
      min-reuse: 2
    work:
      ttl-ms: 604800000          # L3 工作集默认 TTL（7 天）
      distill-on-close: true     # 会话/任务结束自动蒸馏
    health:
      scan-interval-ms: 86400000
```

---

## 17. 迁移与运维

### 17.1 备份 / 校验 / 恢复（P0 首验收项，必须在副本实测）

① 迁移前 `VACUUM INTO 'data/backups/minis-<ts>.db'` + 复制 `data/article`；
② 记录行数与抽样哈希；③ 单事务按 `_schema.version` 迁移；④ 后验证 `integrity_check=ok`、
`foreign_key_check` 空、行数一致、`owner/status/identity IS NULL = 0`、§3.3 矩阵违例清单 = 0（或全落「待复核」）；
⑤ 失败中止不递增版本；⑥ 重复启动跳过；⑦ 失败恢复 = 备份覆盖 + 回滚旧程序。

### 17.2 监控指标（新增）

| 指标 | 含义 |
|---|---|
| `memory.scope_missing` | 无作用域写入被拒次数（I1） |
| `memory.invariant_violation` | I2–I6 违例计数 |
| `memory.write_conflict` | 乐观锁 409 次数（I9） |
| `memory.budget_evicted` | 各层被预算挤出的条数 |
| `memory.snapshot_coverage` | 有回放记录的轮次占比 |
| `memory.cross_domain_blocked` | 自动流程跨域被拦次数（I7） |

---

## 18. 测试矩阵

> 纪律：验证**真实行为**，非「接口返回成功」；全部在**副本库**跑。`T5/T6/T7/T8/T10/T11/T12/T20/T21` 继承 v5，
> 下表为 v6 全量（★ 为 v6 新增项）。

| # | 测试 | 断言 | 阶段 |
|---|---|---|---|
| T1 | 跨项目隔离 | 小说项目写条目 → 软件项目 `visible` 命中 0 | P1 |
| T2 | 画像保护 | 连写剧情 → 无 `owner=USER,status=active` 新增 | P1 |
| T3 | 偏好升级闸门 | project 不升 user；仅 repeated+approve 才升 | P2 |
| T4 | 容量预算 | 注入 token ≤ budget；超长可回读 | P3 |
| T5 | 重要记忆保留 | 巩固后 pinned/画像/open_issue/handoff-pending 仍 active | P3 |
| T6 | 并发写入 | 并发 update → 一方 409；冲突置 conflicted | P2 |
| T7 | 双时态与点查 | 旧值 `invalid_at` 关闭、`as-of` 可点查 | P4 |
| T8 | 共享/撤销 | 授权命中、未授权 0；撤销后 0 | P2 |
| **T9★** | **作用域契约** | 无 `MemoryExec` 写入 → 抛错/被拒 + 审计 1 条（I1） | P1 |
| **T10★** | **项目绑定闭环** | 会话绑项目后：提炼落 `PROJECT×L1`；解绑后不再注入 | P1 |
| **T11★** | **L3 会话隔离** | A 会话工作记忆在 B 会话注入 0；同会话命中 | P1 |
| **T12★** | **L3 收尾蒸馏** | 会话结束 → 工作集归档 + 蒸馏出 L2 条目 | P3 |
| **T13★** | **子代理/心跳记忆** | 子代理面注入派发方 Agent/项目记忆；心跳面注入自身 L2（断言 system 实际内容） | P2 |
| **T14★** | **exec 结论提炼** | 子代理/心跳产出 → 生成 `AGENT×L2` 条目 | P2 |
| **T15★** | **工具作用域** | `recall_memory` 在项目 A 命中不到项目 B 的条目（修 D8） | P1 |
| **T16★** | **快照隔离** | 本轮写入不影响本轮注入；下一轮可见（I12） | P2 |
| **T17★** | **快照回放** | `snapshot/{turnId}` 命中清单 = 本轮实际注入 | P2 |
| **T18★** | **发布/收件箱** | 发布后收件方 R5 命中；`offered→accepted→done`；撤销后 0 | P2 |
| **T19★** | **提升层** | L2 达阈值生成 L4 候选；L4→L1 必经闸门（I7） | P4 |
| **T20** | 候选拒绝后重提炼 | 不再生成同指纹 pending | P2 |
| **T21** | role-play 隔离 | 创作会话内容不产 `USER_PROFILE` active；读侧带标注 | P2 |
| T22 | 暂停恢复 | 重启后按 `(agent, project, task)` 恢复工作集 + 检查点 | P5 |
| **T23★** | **检查点写入** | 中断前 `TaskCheckpointService.save` 有调用；恢复读到 | P5 |
| **T24★** | **全局规则实时生效** | 规则变更后**当轮**生效（强制刷新路径） | P2 |
| **T25★** | **不变量矩阵** | I3–I6 逐条构造违例 → 全部被拒并计数 | P1 |
| **T26★** | **政策双实现一致** | SQL 谓词与 Java `MemoryPolicy` 在同一数据集上判定一致 | P1 |
| T27 | 零噪声 | 寒暄不产条目 | P1 |
| **T28★** | **迁移幂等与回滚** | 重复启动不重复执行；失败回滚版本不前进 | P0 |
| T29 | 备份恢复 | 副本备份 → 迁移 → 恢复一致 | P0 |
| **T30★** | **三病体检** | 构造污染/挤占/漂移样本 → 报告指标非零且明细正确 | P3 |
| **T31★** | **purge 影响面** | 预览计数 = 实际影响条数；墓碑阻止自动重建 | P4 |
| **T32★** | **LayeringTest** | `ai.memory` 不依赖 `agent` / `knowledge`（保持全绿） | 全程 |

既有测试保持全绿：`MemoryItemStoreTest` / `MemoryConsolidatorTest` / `MemoryRecallTest` / `MemoryEventStoreTest` / `LayeringTest`。

---

## 19. 分阶段实施

> 纪律：先测试后实施；复用现有模块；`LayeringTest` 保持；不静默丢弃/错误归类数据；DB 变更须有迁移与回滚。

| 阶段 | 交付范围 | 不含 | 验收 |
|---|---|---|---|
| **P0 · 迁移地基** | `SchemaMigrations` V6 执行器 + 备份/校验/回滚；新表空壳 | 业务逻辑 | T28/T29 副本实测 |
| **P1 · 作用域契约 + 项目闭环** | `MemoryExec` 扩展 + `MemoryScopeCtx` 全入口圈定 + `ScopeGuard` + 会话↔项目打通 + L3 隔离读 + `recall_memory` 走作用域 + `MemoryPolicy`（SQL/Java 双实现） | 快照/发布 | T1/T2/T9–T11/T15/T25/T26/T27 |
| **P2 · 快照 + 多面接入 + 发布** | `MemorySnapshot` + staged write + 四面 provider + exec 结论提炼 + 发布/收件箱/交接 + 规则实时生效 + role-play 全链路 | 提升层 | T3/T6/T8/T13/T14/T16–T18/T20/T21/T24 |
| **P3 · 工作集 + 预算 + 体检** | `WorkingSetService`（含 `memory_checkpoint` 接线）+ 按层预算与权重 + `MemoryHealthService` + 前端记忆中心（泳道/审批台/体检） | 双时态点查 | T4/T5/T12/T30 |
| **P4 · 双时态 + 提升层 + 遗忘** | `as-of` 点查 + `MemoryPromoter` + 知识边运行化 + purge（先预览）+ 前端时间轴/发布区 | — | T7/T19/T31 |
| **P5 · 恢复与回归** | 检查点 save/load 真接线 + 恢复路径 + 监控指标 + 全矩阵回归 | — | T22/T23 + 全矩阵 |
| **P6 · 文档与验收** | 更新 `memory-v5.md` 状态行、补架构文档、指标看板 | — | 文档与代码一致 |

> **建议起步**：P0 → P1。P1 完成后，D1/D3/D8/D9 同时消除，是解锁「四层真正跑起来」的最小闭环。

---

## 20. 风险与未决项

| # | 问题 | 阻塞 | 处置 |
|---|---|---|---|
| R1 | 迁移/备份/恢复未实测 | **阻塞 P0** | P0 首任务：副本实测 §17.1 ①–⑦ |
| R2 | 历史 L3/project 条目归属需回填 | 否 | 保守降级 + 标「待复核」，不臆造 |
| R3 | 会话↔项目回填需要默认工作域 | 否 | 允许 `project_id=NULL` = 「个人工作域」，不强制造数据 |
| R4 | staged write 与「显式记住立即生效」体验冲突 | 否 | 显式指令走同步提交（豁免 staged），其余走 staged |
| R5 | 快照回放存储增长 | 否 | 只存引用 + 定期清理（保留 N 天） |
| R6 | 语义提升（L2→L4）需 LLM 成本 | 否 | 默认 `auto=false`，只产候选；水位控频 |
| R7 | 多租户未启用 | 否 | `user_id` 预留，`visible` 已含 `userId` |
| R8 | 前端工作量大（六个新区） | 否 | 按 P1→P4 分阶段交付，先「项目选择器 + 泳道 + 审批台」 |

---

## 21. 附录

### 21.1 v5 → v6 差异清单

| 维度 | v5 | v6 |
|---|---|---|
| 定位 | 存储模型（owner × domain × layer） | **记忆运行时**（作用域契约 + 生命周期 + 流转） |
| 作用域 | 显式传参；`MemoryScopeCtx` 未用 | **强制圈定 + ScopeGuard**，无作用域不写库 |
| 一致性 | 无快照 | **每轮只读快照 + staged write + 回放** |
| L3 | 有谓词无生产者 | **工作集（`memory_work`）+ 会话隔离 + 收尾蒸馏** |
| L4 | 只有人工 API | **Agent 发布工具 + 交接状态机 + 收件箱 + 提升层** |
| 打分 | 单维 `scopeWeight` | **`layer × domain × evidence` 三轴** |
| 预算 | 桶含 shared 但未生效 | **按层预算，`shared/publish` 真正生效** |
| 工作状态 | `memory_checkpoint` 未接线 | **WorkingState 服务 + 检查点真接线** |
| 可审计 | 无 | **revision + snapshot + 体检 + purge 预览** |
| 前端 | 三页签 | **记忆中心六区 + 对话内入库/发布** |

### 21.2 主流对标速查

| 能力 | Letta/MemGPT | Mem0 | Zep/Graphiti | v5 | **v6** |
|---|---|---|---|---|---|
| 分层记忆 | ✅ | ⚠️ | ❌ | ✅ | ✅ + **每层有生产者/消费者** |
| 双时态 | ❌ | ❌ | ✅ | ✅ | ✅ + **as-of 点查/回放** |
| 画像保护/审批 | ❌ | ❌ | ❌ | ✅ | ✅ + **证据溯源/复检到期** |
| 跨 Agent 共享/交接 | ⚠️ | ⚠️ | ⚠️ | ⚠️（仅人工） | ✅ **发布/交接/收件箱** |
| 写入治理 | ❌ | ❌ | ❌ | ✅ 谓词 | ✅ + **通道分级/审计/revision** |
| 工作状态分离 | ⚠️ | ❌ | ❌ | ❌ | ✅ **三概念物理分离** |
| 可回放 | ❌ | ❌ | ⚠️ | ❌ | ✅ **snapshot 回放** |

### 21.3 文档关系

- `docs/四层Memory架构.md`：思想源（大纲），v6 逐条对齐其 §一–§十一。
- `docs/memory-v5.md`：数据地基与既有决议；**文末状态行已过期**（写「未实施」，实际已实施大半），
  P6 更新为「已实施，由 v6 接管演进」。
- `docs/memory-v6.md`（本文）：**唯一权威标准**。

### 21.4 术语

| 术语 | 含义 |
|---|---|
| 作用域契约 | 任何读写必须在显式 `MemoryExec` 内发生（v6 法一） |
| 快照 | 一轮执行使用的只读记忆视图 + 水位，可回放 |
| 暂存提交 | 轮内写缓冲，本轮注入不受其影响 |
| 蒸馏 | L3 工作集在会话/任务结束后提炼为 L2/L4 |
| 提升 | L2→L4、L4→L1 的受控升级（须凭据） |
| 三病 | 记忆污染 / 记忆挤占 / 记忆漂移（大纲 §七） |

---

> **状态**：标准已成型。实施按 §19 推进，本文件末尾随迭代更新进度。

### 实施进度（随迭代更新）

| 阶段 | 状态 | 交付 |
|---|---|---|
| **P0 · 迁移地基** | ✅ 已实施 | `schema.sql` 新增 5 表（`memory_revision` / `memory_work` / `memory_promotion` / `memory_handoff` / `memory_snapshot`）+ `memory_item` 三列（`identity` / `promoted_from` / `expires_soft`）；`SchemaColumnPatches` / `SchemaIndexes` 同步；`SchemaMigrations` v3（identity 回填、L3 无绑定降级、TASK 孤儿归 AGENT）+ 迁移后 I5/I6 抽查 |
| **P1 · 作用域契约 + 项目闭环** | 🟡 后端完成，前端完成「项目选择器」 | `MemoryExec` v6（surface 枚举 + `turnId` + `identity`，`narrative` 由身份派生）；`MemoryPolicy`（读谓词唯一来源 R1–R6 + 写侧矩阵 I3–I6）；`ScopeGuard`（I1）；`visible` 收敛到 policy（新增 L3 会话隔离、L4 需发布）；写入口校验（`MemoryItemStore.insert`）；`ChatTurnRunner` 圈定 `MemoryScopeCtx`；`recall_memory` 走作用域；会话↔项目（`POST /api/sessions` 带 `projectId`、`PUT /api/sessions/{id}/project`、分支继承项目）；前端「…」菜单项目选择器；单测 `MemoryPolicyTest` / `MemoryScopeVisibilityTest` |
| **P1 剩余** | ⏳ | 多执行面（子代理 / 心跳 / 圆桌）圈定与注入已随 P2 落地；`MemoryPolicy` 的 SQL/Java 一致性断言（T26）待补 |
| **P2 · 快照 + 多面接入 + 发布** | ✅ 已实施 | ① **快照**：`MemorySnapshot` / `MemorySnapshotBuilder` / `MemorySnapshotStore`（回放表只存引用），`MemoryProvider` 不再自算预算；② **暂存**：`MemoryStage`（轮内写缓冲，轮末提交）+ `ChatTurnRunner` 接线；③ **多面**：`MemoryProvider` 服务 CHAT/SUBAGENT/HEARTBEAT，圆桌在 `requestBody` 注入（自建 messages 不经过 provider），子代理/心跳/职责入口圈定 `MemoryScopeCtx`；④ **exec 提炼**：`AgentRunner` → `MemoryIngest#tapExec`（修 D7）；⑤ **发布/交接/提升**：`MemoryPublisher` + `memory_handoff` / `memory_promotion` 两表 + `publish_knowledge` 工具 + `/api/memory/inbox`、`/handoff`、`/promote`、`/promotions/*`；⑥ **恒定注入**：`MemoryVocabulary.isProtected`（pinned/画像/open_issue/全局规则）统一供巩固与预算使用（T24 机制） |
| **P3 · 工作集 + 按层预算 + 体检 + 前端** | ✅ 已实施 | ① **工作集**：`MemoryWorkStore`（`memory_work`）+ `WorkingSetService`（记录 / 读取 / 关闭 / **收尾蒸馏** / 检查点摘要）+ `note_working` 工具（临时结论进 L3 而非长期记忆）；快照同时呈现「记得什么」与「做到哪一步」；② **检查点接线**（修 D11）：轮末把工作集摘要写进 `task.memory_checkpoint`，`GET /api/memory/working-set/checkpoint` 可查；③ **按层预算与权重**：预算改 `shared/agent/task/publish/episode` 分桶，召回分值改 `余弦 × W_layer × W_domain × W_evidence`（修 D12）；④ **体检**：`MemoryHealthService` + `GET /api/memory/health`（污染 / 挤占 / 漂移 + 总评级）；⑤ **API**：`/working-set`（增删查关）、`items` 增 `layer/identity/sessionId` 过滤、`summary` 增 `workingOpen`；⑥ **前端**：记忆中心新增「泳道 / 工作集 / 体检」三页签 + identity 徽标 |
| **P4 · 双时态 + 审计 + 遗忘 + 提升 + 前端** | ✅ 已实施 | ① **双时态点查**（T7）：`MemoryItemStore.asOf` / `history`（取代链旧→新）+ `MemoryEdgeStore.asOf` + `MemoryTemporalService` + `GET /api/memory/as-of`、`/items/{id}/history`；② **写入审计**（法四）：`MemoryRevisionStore` + `MemoryItemStore` 在 create/update/supersede/status/promote/delete 处落 revision（actor/turnId 取执行作用域）+ `GET /api/memory/revisions`；③ **显式遗忘**（§12.5）：`MemoryPurgeService`「先预览后执行」+ 墓碑防重建 + `/purge/preview`、`/purge`；④ **提升扫描**（§9.3）：`MemoryPromoter` 生成 L2→L4 候选（受 `promote.*` 配置，默认只产候选）+ `/promote/scan`；⑤ **顺手修一个真实缺陷**：`MemoryEdgeStore.nzEq` 用 NUL 当哨兵导致含该串的 SQL 无法解析（"加知识边"整条路走不通），改为空串哨兵；⑥ **前端**：记忆中心新增「时间轴（as-of + 取代链）」与「发布与收件箱（待接收 / 已发布 / 提升候选）」两页签 |
| **P5 · 恢复 + 监控** | ✅ 已实施 | ① **工作状态恢复**（T9/T22 · 修 D11 的读取侧）：`WorkingSetRestorer` 把 `task.memory_checkpoint` 还原成工作集条目（幂等：同任务已有恢复项则跳过；空检查点给可读提示），`TaskRecovery` 启动时自动恢复 + `POST /api/memory/restore?taskId=` 手动恢复；② **运行指标**（§17.2）：`MemoryMetrics` 六个计数器（无作用域被拒 / 归属违例 / 乐观锁冲突 / 预算挤出 / 跨域被拦 / 快照覆盖率）+ `GET /api/memory/metrics`，并在体检页签展示；③ **T26 一致性断言**：`MemoryPolicyConsistencyTest` 用同一份数据比对 SQL 谓词与 Java 镜像的命中集合；④ **前端**：工作集页签加「恢复工作状态」按钮，体检页签加「运行指标」卡片 |
| **P6 · 文档收尾** | ✅ 已实施 | ① `docs/memory-v5.md` 状态行更正（原文写「未实施」已过期，改为指向 v6 并标记为史料）；② 本文档补全 P0–P6 实施进度与验收口径 |
| **补充 · Agent 侧记忆写工具** | ✅ 已实施 | 除 `note_working`（L3）/ `publish_knowledge`（L4）外，新增 **`update_memory` / `forget_memory`**（§9.1）：只能改/忘**自己的 L2、当前项目、当前会话**的条目（`MemoryEditService.canEdit`），**画像与已发布 L4 一律拒**（I2）；按 `match` 定位命中多条**不猜**、回候选让模型重挑；更新走乐观锁（不覆盖）；遗忘 = 撤销 + 墓碑（不物删）。同时补上两个写工具漏声明的 `writes()+validate()`（此前被当成只读，会混进子代理默认工具集）。`pin_memory` 与提升仍留人工 |
| **遗留（已登记）** | ⏳ | ① **P0 副本库迁移实测**（唯一未验收项）；② 心跳「绑定项目」——需要产品先定「心跳会话归属哪里」（当前心跳没有独立会话行，故未强行发明绑定）；③ `MemorySemanticConsolidator` 的 LLM 语义取代仍默认关（需真实模型往返，仅单测未覆盖） |

> 已验收：后端**全量测试 204 项全绿**（含 P5 的 `WorkingSetRestorerTest` 4 / `MemoryMetricsTest` 2 / `MemoryPolicyConsistencyTest` 2，P4 的 `MemoryTemporalTest` 3 / `MemoryGovernanceTest` 7（含 Agent 侧改写/遗忘），P3 的 `WorkingSetServiceTest` 4 / `MemoryHealthServiceTest` 5，P2 的 `MemorySnapshotBuilderTest` 6 / `MemoryPublisherTest` 4 / `MemoryStageTest` 3，P1 的 `MemoryPolicyTest` 8 / `MemoryScopeVisibilityTest` 6）；`LayeringTest` 7 项保持通过（分层红线未破）；后端 `compile` 与前端 `tsc -b` 通过。
> **未验收**【待验证】：真实库迁移（§17.1 备份 / 校验 / 回滚）须在**副本库**实测（P0 验收项，仍是唯一硬缺口）。
>
> **实施状态**：P0–P6 已全部落地（本文件 §19 的表格逐行对应），代码在工作区未提交；下一步只等副本库迁移实测与真机体验验收。
