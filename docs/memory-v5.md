# Memory v5 · 四层域记忆架构（权威设计）

> **本文档地位**：Memory 体系的**唯一权威设计**。`docs/四层Memory架构.md`（设计理念指导）提供思想源，
> `docs/memory-v4.md`（合并版档案）作为审计事实与既有决议的素材来源；凡冲突以本文为准。
>
> **文档纪律**：文中每条结论标注 **【代码事实】/【设计决议】/【待验证】** 三类之一。
> 【代码事实】= 已在代码库中确认；【设计决议】= 本次设计确定、待实施；【待验证】= 未实测，需在副本上跑通。
>
> **本轮范围**：只产出设计，不改动任何代码与真实库（`data/minis.db` 禁止默认重建，见 §11）。

---

## 目录

- [0. 一句话概述](#0-一句话概述)
- [1. 现状审计（代码事实）](#1-现状审计代码事实)
- [2. 设计理念：三条铁律 + 一个决定律](#2-设计理念三条铁律--一个决定律)
- [3. 概念模型：四层 × 四域（核心）](#3-概念模型四层--四域核心)
- [4. 数据模型](#4-数据模型)
- [5. 写入协议与治理](#5-写入协议与治理)
- [6. 读取与上下文组装](#6-读取与上下文组装)
- [7. 超越主流的四项增强](#7-超越主流的四项增强)
- [8. 生命周期与保留策略](#8-生命周期与保留策略)
- [9. 与 Turn / AgentRuntime / RAG 的集成](#9-与-turn--agentruntime--rag-的集成)
- [10. API 变更](#10-api-变更)
- [11. 数据库迁移](#11-数据库迁移)
- [12. 测试矩阵](#12-测试矩阵)
- [13. 分阶段实施](#13-分阶段实施)
- [14. 风险与未决项](#14-风险与未决项)
- [15. 附录](#15-附录)

---

## 0. 一句话概述

把「记忆」从**一张扁平的作用域标签表**（现有 `memory_item.scope` 四值）升级为**两个正交维度**：

- **Domain（语义域）**——这条信息**描述什么**：用户画像 / 项目 / Agent / 任务事件。决定**归属与写入权限**。
- **Layer（流转层 L1–L4）**——这条信息**谁能读、如何流转**：共享权威 / Agent 私有 / 任务临时 / 发布知识。决定**可见范围与生命周期**。

两者组合 + 受保护的画像域 + 写入协议 + 按需读取，得到一套**对齐并超越主流 Agent Memory**（Letta 三层、Mem0 四级作用域、Zep 双时态图）的设计。

一句话判断标准（继承 v4）：
> **画像不被项目污染、项目记忆不串扰、重要信息不因容量与活跃度丢失、Agent 能共享必要知识又保有独立性、系统能可靠暂停/恢复/迁移/长期维护。**

---

## 1. 现状审计（代码事实）

> 本节只列**已实现**与**缺口**，完整证据索引见 `memory-v4.md` §1 / §8。凡标【代码事实】者均已在源码确认。

### 1.1 已实现的资产（可复用）

| 资产 | 位置 | 角色 |
|---|---|---|
| `memory_event` | [schema.sql](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/resources/schema.sql#L549-L560) | 只追加证据/事件层，`event_key` 幂等 |
| `memory_item` | [schema.sql](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/resources/schema.sql#L585-L602) | 稳定条目，`agent_id`(NULL=全局) + `scope`(directive/profile/project/episode) |
| 向量投影 | `memory_event_embedding` / `memory_item_embedding` | BLOB float32 + 同模型余弦 |
| 提炼 | [MemoryExtractor.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/memory/MemoryExtractor.java) | 一次 LLM 往返产 JSON 数组；失败隔离 + 信息量门槛 |
| 攒批 | [MemoryIngest.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/memory/MemoryIngest.java) | 内存缓冲，攒 N 轮 / 空闲 X 分钟合并提炼 |
| 巩固 | [MemoryConsolidator.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/memory/MemoryConsolidator.java) | 确定性去重 + episode 裁剪（零 LLM） |
| 召回 | [MemoryItemRecall.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/memory/recall/MemoryItemRecall.java) | 候选→向量余弦→scope 加权→门控→pinned 优先 |
| 注入 | [MemoryProvider.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/agent/context/MemoryProvider.java) | `@Order(-5)`，仅 CHAT 面，top-5 硬编码 |
| 管理 | [MemoryController.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/web/controller/MemoryController.java) | 增删改查 + pin + consolidate |
| 分层守门 | [LayeringTest.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/test/java/cc/ginpika/minis/arch/LayeringTest.java) | 钉死 `ai.memory` 不依赖 `agent` / `knowledge` |

### 1.2 已确认的缺口（本次要解决）

| # | 缺口 | 证据（代码事实） | 后果 |
|---|---|---|---|
| G1 | **无 Project 实体** | 全仓无 project 表/列 | 项目间污染无解 |
| G2 | **`agent_id` 写了不读** | `MemoryItemRecall` 只按 scope 过滤 | Agent 私有记忆实际不存在 |
| G3 | **画像污染零防护** | scope 由 LLM 判定；`agent_id=NULL` 即全局 | 小说人物/剧情污染用户画像 |
| G4 | **无并发控制** | 无 version；`supersede/updateContent` 无条件 | 多 Agent 并发静默覆盖 |
| G5 | **无共享/交接** | 无 shared 机制 | 跨 Agent 协作时信息成孤岛 |
| G6 | **注入按条数非 token** | `MemoryProvider.TOP_MEMORIES=5` | 长条目撑爆上下文 / 挤占 |
| G7 | **`ContextRequest` 无作用域** | 只有 `sessionId`+`surface` | 读侧结构上拿不到 agent/project |
| G8 | **生命周期粗糙** | 仅 episode 裁剪；dedupe 全局跑 | 保护等级缺失、跨域误伤 |
| G9 | **role-play 无隔离** | 无来源类型字段 | 虚构设定被当现实事实 |
| G10 | **`memory_cursor` 建而未用** | grep 0 命中 | 水位式提炼缺失 |

### 1.3 三个必须治好的病症（来自 `四层Memory架构.md` §七）

- **记忆污染**：小说人物/价值观/剧情决策被当成用户本人偏好与现实经历。
- **记忆挤占**：连续写小说时，剧情/设定/人物关系占满容量，挤掉其他 Agent 的重要信息。
- **记忆漂移**：小说项目中的临时决策覆盖了用户画像、项目事实或长期偏好。

> **根因**：归属**只由「谁产生」决定**（`insert(agentId, scope)`），且**读侧不按归属过滤**。
> v5 的核心修复就是 §2 的「归属决定律」。

---

## 2. 设计理念：三条铁律 + 一个决定律

### 2.1 三条铁律（来自指导文档 §十一）

1. **隔离写入**——不同域的记忆不能因为同一个用户 / 同一个 Agent / 同一个数据库，就默认拥有相同写入权限。
2. **按需读取**——长期保存可以很多，每次模型调用只读取完成当前任务所需的信息。
3. **独立保留**——是否重要、是否过期、是否保留，由**类型 + 证据 + 保留策略 + 用户意图**决定，而非最近是否活跃。

### 2.2 决定律（v5 的核心创新）

> **记忆的归属不由「谁产生了它」决定，而由「这条信息描述的是什么、适用于什么范围」决定。**

推论：
- 小说家 Agent **可以**产生关于用户的观察，但**不因此**该观察就进入用户画像——要过 §5.4 的证据/审批闸门。
- 后台心跳产生的结论默认落 **Agent 私有（L2）**，不自动升为项目事实。
- 一条记忆同时有 **生产者（producer）** 与 **归属者（owner）** 两个身份，二者解耦：`producer_agent_id` 只做审计，`owner_*` 决定一切权限。

### 2.3 三概念分离（指导文档 §三）

| 概念 | 载体 | 生命周期 | 是否长期记忆 |
|---|---|---|---|
| Conversation History | `chat_message` / `task.context` | 随会话 | 否（用于还原过程） |
| Working State | `task` 状态 + `task.memory_checkpoint` + 黑板 | 任务期间 | 否（可暂停恢复） |
| Long-term Memory | `memory_item` / `memory_candidate` / `memory_edge` | 长期 | 是 |

**硬规则**：三者物理分离、不得互相混淆。禁止「把一段对话中的临时假设直接沉淀为永久记忆」，也禁止「每次恢复执行都重新加载全部历史消息」。

### 2.4 与主流架构的取舍（为什么不照抄）

| 主流 | 它解决什么 | 我们不照抄的原因 | v5 的做法 |
|---|---|---|---|
| Letta/MemGPT 三层（core/recall/archival）+ 可自编辑 memory block | 单 Agent 的上下文虚拟内存 | 以**单个 Agent** 为中心，无跨 Agent 归属/权限/审批 | 保留「分层 + 按需换入」，但层由 **Domain×Layer** 决定，读写受权限谓词约束 |
| Mem0 四级作用域（user/agent/run）+ ADD/UPDATE/DELETE/NOOP | 抽取与去重 | 无画像保护、无审批、无跨 Agent 共享 | 采用其**作用域正交**思想，叠加**候选/审批/撤销**与**证据等级** |
| Zep/Graphiti 双时态知识图谱 | 事实随时间演化、矛盾失效 | 全图建模成本高，非本阶段刚需 | **借鉴双时态**：条目与知识边都带 valid/learned 时间，矛盾**关闭有效期而非删除**（§7.1，P4） |
| Generative Agents 检索（recency×importance×relevance） | 单模拟环境的记忆排序 | 「最新=最相关」在真实多任务下会漂移 | 时序轴**只过滤**、相关轴**只打分**（沿用现有 `MemoryRecall` 的正确取舍），保留强度**永不用于淘汰** |

### 2.5 设计红线（与分层守门一致）【代码事实】

`ai.memory` 与 `ai.knowledge` / `ai.terminal` 平级，**不得依赖 `agent`**（[LayeringTest](file:///d:/Static/worktable/ginpika-page/minis/backend/src/test/java/cc/ginpika/minis/arch/LayeringTest.java#L70-L89)）。
因此：
- **写入是 push**：agent 侧在发生点调用 `ai.memory` 的门面（现有 `MemoryWriter` 模式），memory 侧不回头读 `agent` 的库。
- **读取走 provider/tool**：agent 编排层调用 `ai.memory` 的读接口，作用域由编排层组装好传入。
- **跨层「当前是谁」用 `ai.core.AgentScope`**（已有 ThreadLocal 方案，见 [AgentScope.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/core/AgentScope.java)）；v5 扩展一个 `MemoryScope`（§3.4）。

---

## 3. 概念模型：四层 × 四域（核心）

### 3.1 两个正交维度

```
                     Layer（流转层：谁能读 / 如何流转）
                 L1 共享    L2 Agent    L3 任务    L4 发布
              ┌──────────┬──────────┬──────────┬──────────┐
USER_PROFILE  │ ①受保护画像│    —     │    —     │    —     │
PROJECT       │ ②项目事实 │ ③项目经验 │ ④项目工作 │ ⑤项目知识 │
Domain  AGENT │    —     │ ⑥Agent私有│ ⑦Agent工作│ ⑧Agent发布│
TASK_EPISODIC │    —     │    —     │ ⑨事件/检查点│   —    │
              └──────────┴──────────┴──────────┴──────────┘
```

图例：`①`–`⑨` 是**合法格**；`—` 是**非法格**（写入即拒绝）。合法格集合即 §4.4 的归属组合约束矩阵。

### 3.2 Layer 定义（流转层）

| 层 | 名称 | 定义 | 可见范围 | 生命周期 |
|---|---|---|---|---|
| **L1** | Shared / Authoritative（共享权威） | 跨场景成立的稳定信息：**受保护用户画像** + 项目事实/决策 + 全局规则 | 有权限的 Agent 均可读（画像只读，写入受闸门） | 长期 |
| **L2** | Agent Private（Agent 私有） | 单个 Agent 的经验/判断/方法/失败记录/待解问题 | **仅归属 Agent**，默认不外泄 | 长期 |
| **L3** | Task / Session（任务会话） | 当前讨论、进度、临时结论、工具结果、工作材料、检查点 | 当前 session/task 参与方 | 临时、可归档 |
| **L4** | Handoff / Knowledge（交接与知识） | Agent 主动发布、或经验证沉淀的可复用信息 | 由**发布机制**控制的接收范围（项目/Agent/session/用户） | 由知识价值决定 |

**流转管道**（单向，禁止跳跃）：

```
L3（任务临时）──巩固/提炼──▶ L2（Agent 私有）──发布──▶ L4（交接/知识）──验证/评审──▶ L1（共享权威）
     ▲                                                                              │
     └────────────────────────── 仅人工 / 用户审批可跨域 ────────────────────────────┘
```

- **自动流程永远只在同域内**：巩固只读 L2/L3、只写同域；发布（L4）需显式动作；升 L1 必经证据闸门（§5.4）。
- **跨域覆盖只有两条合法路径**：`memory_share`（显式授权共享）或人工操作（记审计）。

### 3.3 Domain 定义（语义域）

| Domain | 描述什么 | 典型内容 | 保护等级 |
|---|---|---|---|
| **USER_PROFILE** | 跨场景、跨项目仍成立**用户**事实与稳定偏好 | 主要用 Java；偏好低成本可维护方案 | **最高**：普通 Agent 不得直接写，须走候选审批 |
| **PROJECT** | 某项目内的事实/决策/约束/进度 | 小说世界观、人物关系、大纲；软件架构决策 | 项目间默认隔离；不覆盖画像 |
| **AGENT** | 特定 Agent 的工作经验/判断/方法/未解问题 | 叙事节奏总结；并发 Bug 经验 | 默认私有，授权才共享 |
| **TASK_EPISODIC** | 某次会话发生了什么、完整工作材料 | 第三章逐段修改、废弃剧情、讨论细节 | 容量最大、按需检索、可归档 |

> **Domain 与 Layer 的组合即「作用域格」**。Domain 决定**写入权限与归属**，Layer 决定**读取范围与生命周期**。
> 例：`PROJECT × L2` = 项目内某 Agent 的私有经验；`PROJECT × L1` = 项目内共享事实；`AGENT × L4` = Agent 发布的、指定接收者的知识。

### 3.4 归属模型（MemoryScope）—— 落地指导文档 §五的逻辑模型

```java
// 概念模型（非最终 Java 代码）：ownerType + ownerId 是本设计的归属键
record MemoryScope(
    String ownerType,   // USER | PROJECT | AGENT | TASK
    String ownerId      // user.id / project.id / agent.id / session|task id
) {}
```

在归属键之外，每条记忆还携带一组长度的**绑定引用**（可空，按 Domain 约束）：
`projectId`、`agentId`（归属 Agent）、`sessionId`、`taskId`、`producerAgentId`（生产者，仅审计）。
**`ownerType=USER` 时所有绑定必须为空**；`PROJECT` 时 `projectId` 必须非空；`AGENT` 时 `agentId` 必须非空；`TASK` 时 `sessionId` 必须非空。完整矩阵见 §4.4。

### 3.5 运行时作用域（MemoryExec）

现有 `AgentScope` 只带 `agentId`。v5 扩展为执行期上下文 `MemoryExec`【设计决议】：

```java
record MemoryExec(
    String userId,      // 多租户预留：当前 app_user.id（单用户阶段取唯一行）
    String agentId,     // hub_session.agent_id；NULL → 规范化 'builtin'
    String projectId,   // hub_session.project_id（新增列）；可空
    String sessionId,
    Long   taskId,
    Surface surface,    // CHAT / SUBAGENT / HEARTBEAT（复用现有枚举）
    boolean isHuman     // 管理页 / 人工操作
) {}
```

由编排层在**执行入口**组装（对话请求线程 / 虚拟线程 / 心跳扫描线程），实现仍可挂在 `ai.core` 的 ThreadLocal（扩展现有 `AgentScope`），供深层写点读取（例如 `ai.memory` 写入时确定归属）。**这解决了 G7：读侧结构上拿得到作用域。**

### 3.6 role-play 身份隔离（指导文档 §十）—— 解决 G9

系统必须区分同一用户的多种「身份上下文」：

| 身份类型 | `source_kind` | 是否可进用户画像 |
|---|---|---|
| 用户在现实中的陈述 | `real` | ✅（需证据/审批） |
| 用户对虚构角色的设定 | `fiction` | ❌（只落项目域） |
| 用户为推进剧情作出的选择 | `fiction` | ❌ |
| 用户在某个项目中的创作偏好 | `project_pref` | ⚠️ 只落项目域，不自动升画像 |
| 系统由多次行为推断的潜在偏好 | `inferred` | ⚠️ 只能成候选，升画像需 repeated+审批 |

**硬规则**：来自**创作类项目**（`project.kind ∈ {novel, fiction, rpg}`）的记忆，`source_kind` 默认标 `fiction`，**永远不能**直接升级为 `USER_PROFILE × L1`，最多生成 `PROJECT` 域的画像候选。

---

## 4. 数据模型

### 4.1 新增表（P1/P2）

```sql
-- 项目：跨 Session/Task/Agent 的业务工作域（解决 G1）
CREATE TABLE IF NOT EXISTS project (
  id            TEXT PRIMARY KEY,
  user_id       TEXT,                       -- 多租户预留；单用户阶段= app_user 唯一行
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL DEFAULT 'other',   -- novel|software|research|other
  narrative     INTEGER NOT NULL DEFAULT 0,      -- 1=创作/虚构类项目 → 域内记忆禁止升画像（§3.6）
  status        TEXT NOT NULL DEFAULT 'active',  -- active|paused|archived
  note          TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_project_user ON project(user_id, status);

-- 画像候选（唯一权威；解决 G3）
CREATE TABLE IF NOT EXISTS memory_candidate (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  fingerprint    TEXT NOT NULL,
  user_id        TEXT,
  owner          TEXT NOT NULL,              -- user（当前仅画像候选）
  scope          TEXT NOT NULL,              -- profile | directive
  title          TEXT,
  body           TEXT NOT NULL,
  source_kind    TEXT NOT NULL DEFAULT 'inferred', -- real|fiction|project_pref|inferred
  evidence_level TEXT NOT NULL,              -- explicit | repeated | inferred
  evidence_count INTEGER NOT NULL DEFAULT 1,
  evidence_refs  TEXT,                       -- JSON：chat_message.id 数组
  status         TEXT NOT NULL DEFAULT 'pending',  -- pending|approved|rejected
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_candidate_fp
  ON memory_candidate(fingerprint) WHERE status='pending';

-- 共享授权（L4 发布 / 显式授权；解决 G5）
CREATE TABLE IF NOT EXISTS memory_share (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id      INTEGER NOT NULL REFERENCES memory_item(id),
  from_owner   TEXT NOT NULL,
  from_agent   TEXT,
  from_project TEXT,
  to_kind      TEXT NOT NULL,               -- user|project|agent|session
  to_ref       TEXT,
  status       TEXT NOT NULL DEFAULT 'published', -- published|revoked
  note         TEXT,
  created_at   INTEGER NOT NULL,
  revoked_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_share_item ON memory_share(item_id, status);
CREATE INDEX IF NOT EXISTS idx_share_target ON memory_share(to_kind, to_ref, status);

-- 撤销墓碑（防重建；解决 G3 回滚）
CREATE TABLE IF NOT EXISTS memory_revocation (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  target_item_id INTEGER,
  fingerprint   TEXT NOT NULL,
  reason        TEXT,
  allow_relearn INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revocation_fp ON memory_revocation(fingerprint);
```

### 4.2 `memory_item` 增量加列（幂等 `ADD COLUMN`，可空）

```sql
-- 由 SchemaColumnPatches 补（老库就地升级）；schema.sql 同步声明，保证新库结构一致
ALTER TABLE memory_item ADD COLUMN user_id        TEXT;      -- 多租户预留
ALTER TABLE memory_item ADD COLUMN owner          TEXT;      -- USER|PROJECT|AGENT|TASK
ALTER TABLE memory_item ADD COLUMN project_id     TEXT;
ALTER TABLE memory_item ADD COLUMN domain         TEXT;      -- USER_PROFILE|PROJECT|AGENT|TASK_EPISODIC
ALTER TABLE memory_item ADD COLUMN layer          TEXT;      -- L1|L2|L3|L4
ALTER TABLE memory_item ADD COLUMN session_id     TEXT;
ALTER TABLE memory_item ADD COLUMN task_id        INTEGER;
ALTER TABLE memory_item ADD COLUMN producer_agent TEXT;      -- 仅供审计（谁产生）
ALTER TABLE memory_item ADD COLUMN source_kind    TEXT;      -- real|fiction|project_pref|inferred
ALTER TABLE memory_item ADD COLUMN evidence_level TEXT;      -- explicit|repeated|inferred
ALTER TABLE memory_item ADD COLUMN status         TEXT;      -- active|superseded|expired|conflicted|revoked|archived
ALTER TABLE memory_item ADD COLUMN retention      TEXT;      -- pinned|long|normal|ephemeral
ALTER TABLE memory_item ADD COLUMN version        INTEGER;   -- 乐观锁
ALTER TABLE memory_item ADD COLUMN fingerprint    TEXT;      -- 幂等键
ALTER TABLE memory_item ADD COLUMN valid_at       INTEGER;   -- 事实在世界中成立的时间（双时态）
ALTER TABLE memory_item ADD COLUMN invalid_at     INTEGER;   -- 事实失效时间（矛盾时关闭，不删除）
ALTER TABLE memory_item ADD COLUMN learned_at     INTEGER;   -- 系统学到它的时间
ALTER TABLE memory_item ADD COLUMN expired_at     INTEGER;   -- 系统得知其失效的时间

CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_item_fp
  ON memory_item(fingerprint) WHERE status IN ('active','conflicted');
CREATE INDEX IF NOT EXISTS idx_memory_item_owner
  ON memory_item(owner, project_id, agent_id, domain, status);
```

**字段说明（只列新增，避免堆砌）**：

| 字段 | 解决什么 |
|---|---|
| `owner` + 绑定列 | §4.4 组合约束；读侧 `visible` 谓词的判定输入 |
| `domain` + `layer` | 四层×四域落库；检索可按层/域收敛候选 |
| `source_kind` | role-play 隔离（G9） |
| `evidence_level` | 画像升级闸门（§5.4） |
| `status` / `retention` | 状态机与保护级别分离（§8） |
| `version` | 乐观锁，杜绝 last-write-wins（G4） |
| `fingerprint` | 幂等 + 去重 + 撤销墓碑对齐 |
| `valid_at/invalid_at/learned_at/expired_at` | 双时态（§7.1，超越增强） |

> **与现有 `scope` 的兼容**：保留 `scope` 列（值扩展为 `profile|directive|project|episode|fact|decision|method|open_issue|checkpoint|handoff|knowledge`），
> 旧四值映射：`directive/profile → USER_PROFILE×L1`、`project → PROJECT×L1|L2`、`episode → AGENT|TASK×L2|L3`。

### 4.3 超越增强表（P4，可选）

```sql
-- 双时态知识边（借鉴 Zep/Graphiti；仅当需要「事实随时间演化」时启用）
CREATE TABLE IF NOT EXISTS memory_edge (
  id           TEXT PRIMARY KEY,
  owner        TEXT NOT NULL, project_id TEXT, agent_id TEXT, user_id TEXT,
  subject      TEXT NOT NULL, predicate TEXT NOT NULL, object TEXT NOT NULL,
  confidence   REAL NOT NULL DEFAULT 0.5,
  valid_at     INTEGER, invalid_at INTEGER,
  learned_at   INTEGER NOT NULL, expired_at INTEGER,
  source_item  INTEGER REFERENCES memory_item(id),   -- 指向支撑它的条目
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_edge_subject ON memory_edge(subject, predicate, invalid_at);
CREATE INDEX IF NOT EXISTS idx_edge_object  ON memory_edge(object, predicate, invalid_at);
```

### 4.4 归属组合约束矩阵（写入侧与迁移回填侧都校验）

| owner | 允许 domain | 允许 layer | project_id | agent_id | session_id | 合法 |
|---|---|---|---|---|---|---|
| USER | USER_PROFILE | L1 | 必须 NULL | 必须 NULL | 必须 NULL | ✅ |
| PROJECT | PROJECT | L1 / L2 / L3 / L4 | **必须非空** | 可空 | 可空 | ✅ |
| AGENT | AGENT | L2 / L3 / L4 | 可空 | **必须非空** | 可空 | ✅ |
| TASK | TASK_EPISODIC | L3 | 可空 | 必须非空 | **必须非空** | ✅ |
| USER | 其它 domain | 任意 | 任意 | 任意 | 任意 | ❌ |
| PROJECT / AGENT | USER_PROFILE | 任意 | 任意 | 任意 | 任意 | ❌ |
| 任意 | 任意 | USER_PROFILE 且 layer≠L1 | — | — | — | ❌ |
| 任意 | 任意 | PROJECT 且 project_id 为空 | — | — | — | ❌ |
| 任意 | 任意 | AGENT 且 agent_id 为空 | — | — | — | ❌ |

**校验行为**：违反即**拒绝并记日志**（不静默纠正）。违反计数在启动/迁移时汇总上报。

### 4.5 指纹（fingerprint）

```
fingerprint = sha1( owner | project_id | agent_id | domain | scope | normalize(body) )
normalize(body) = 去首尾空白 + 折叠连续空白
```

- `memory_item` 部分唯一：`status IN ('active','conflicted')` 内唯一。
- `memory_candidate` 待审唯一：`status='pending'` 内唯一。
- 用途：幂等写入（`INSERT OR IGNORE` 语义）、去重对齐、撤销墓碑匹配（同指纹 + `allow_relearn=0` → 不再自动重建）。

---

## 5. 写入协议与治理

### 5.1 写入四步协议（指导文档 §四）

任何一条可能成为记忆的信息，都要走完：

```
① 产生      Agent 产出事实/假设/结论/经验/进度
              ↓
② 归类      判定 Domain + Layer + scope（按信息描述的对象，不按生产者）
              ↓
③ 治理      权限校验 + 质量检查（来源/置信度/冲突/时效/访问权限/证据）
              ↓
④ 发布      落到对应作用域（L2/L3 即时；L4 需发布动作；L1 需闸门）；必要时通知订阅者
```

**自动提炼（`MemoryExtractor`）的落点约束**【设计决议】：
- **禁止写 `USER_PROFILE`**（G3 根因修复）。用户明说「记住」且判定为稳定事实/偏好时，只写 `memory_candidate`。
- 默认落 `PROJECT` 或 `AGENT` 域，`layer` 取 `L1`（项目事实）/`L2`（Agent 经验）/`L3`（会话工作）。
- 每次写入带 `fingerprint`，重复即静默合并（`evidence_count++`）而非新建。

### 5.2 权限判定规则（可测试）

**执行上下文** `Exec = MemoryExec`（§3.5）。

**读谓词 `visible(item, exec)`** —— `status='active'`（`isHuman` 可显式请求 `archived`）**且**满足任一：

```
R1  item.owner='USER'    AND item.domain='USER_PROFILE'                    // 全局权威画像（只读）
R2  item.owner='PROJECT' AND item.project_id = exec.projectId              // 当前项目
R3  item.owner='AGENT'   AND item.agent_id  = exec.agentId                 // 我的私有记忆
R4  item.owner='TASK'    AND item.session_id = exec.sessionId              // 当前任务临时记忆
R5  EXISTS(memory_share s WHERE s.item_id=item.id AND s.status='published'
        AND ((s.to_kind='project' AND s.to_ref=exec.projectId)
          OR  s.to_kind='user'
          OR (s.to_kind='agent'   AND s.to_ref=exec.agentId)
          OR (s.to_kind='session' AND s.to_ref=exec.sessionId)))            // 显式授权共享
R6  exec.isHuman = true                                                     // 管理页全量
```

**强制约束**：
- 读入口 SQL **直接编码** `visible`（禁止「先全量召回再在 Java 里过滤」——G2 根因修复）。
- 未授权不因相似度高而返回；共享撤销（`status='revoked'`）后 R5 天然排除。
- `agent_id IS NULL` **不再表示全局画像**（G3/G2 根因修复）：历史 NULL 一律规范化 `'builtin'`，归 `owner=AGENT`。

**写权限**：

| 写入者 | 可写 | 说明 |
|---|---|---|
| 自动提炼 | PROJECT / AGENT / TASK | **禁写 USER_PROFILE** |
| 用户明说「记住」 | → `memory_candidate` | 不直接落画像 |
| 人工（管理页 `isHuman`） | 任意（须过 §4.4 矩阵） | 记审计 |
| 巩固/去重/过期/裁剪 | 同域 | protected 跳过（§8.4） |
| 跨域升级 | 必经 `memory_share` 或候选闸门 | 自动流程一律禁止 |

### 5.3 并发与版本（解决 G4）

- **乐观锁**：`UPDATE memory_item SET ..., version=version+1 WHERE id=? AND version=?`；影响 0 行 → 返回 409。
- **冲突策略**：不覆盖。新值置旧值为 `status='conflicted'`，双方保留，由人工/巩固裁决。
- **去重原子性**：以 `fingerprint` 唯一索引兜底，`INSERT ... ON CONFLICT(fingerprint) DO UPDATE SET evidence_count=evidence_count+1`（限同域），不再用「读全表再改」的非原子路径。
- **禁止 last-write-wins 作为默认**。

### 5.4 用户画像：候选 · 证据 · 审批 · 撤销（指导文档 §九；解决 G3）

画像不是普通共享记忆，而是**受保护数据域**，由专门的 `UserProfileService` 管理，需证据与规则才能更新。

**三类画像信息**：

| 类 | 例子 | 处理 |
|---|---|---|
| A 稳定事实/长期偏好 | 用 Java；偏好低成本方案 | 长期保留，修改需充分证据（`evidence_level ∈ {explicit, repeated}`） |
| B 有时效的用户状态 | 近期在做的事、短期计划 | 带 `valid_until`，过期后降权或重新确认 |
| C 待确认的画像候选 | 小说中多次选某叙事风格 → 推测偏好 | 先记证据，**不直接**升为稳定画像 |

**升级判定 `candidate → active(USER_PROFILE×L1)`** 须**全部**满足：
1. 有**可验证证据**（§5.5）；
2. `explicit` **或**（`repeated` 且 `evidence_count ≥ N` 且人工 approve）；
3. **来源非创作类项目**（`project.narrative=0` 或 `source_kind='real'`）；
4. 通过 §4.4 组合约束。

**可验证证据**（§5.5，`四层Memory架构.md` 的「证据 + 规则」落地）：**LLM 输出的 scope/evidence_level 只是声明，不是证据**。仅三类算证据：
① `chat_message.id`（`role='user'` 的原始消息）；② 用户 approve 事件（记 confirmed_by/at）；③ 人工操作（`source='manual'`）。
`evidence_refs` 至少命中一种，否则最多 `inferred`。

**撤销**：→ `status='revoked'` + 写墓碑 `(fingerprint, allow_relearn=0)`；同指纹不得再自动 approve；用户可 `allow_relearn=1` 解除。

**更新流程**（指导文档 §九）：

```
Agent 观察到一条可能与用户有关的信息
        ↓ 检查来源与适用范围
是否只是当前项目中的选择？
   ├── 是 → 写入项目记忆（PROJECT 域）
   └── 否 → 生成画像候选（memory_candidate）
                   ↓ 检查证据与历史记录
            符合自动更新规则？
              ├── 是 → 受控更新（approve → USER_PROFILE×L1）
              └── 否 → 等待更多证据 / 询问用户
```

**反例（必须挡住）**：连续三天在小说项目要求「增加心理描写」→ 系统只能推断「**小说项目内**当前偏好更细腻的心理描写」，不得升级为「你在所有创作中都有此偏好」。

---

## 6. 读取与上下文组装

### 6.1 检索前先定作用域（指导文档 §二 · 按需读取）

检索前先确定：`User / Project / Agent / Task-Session / 任务类型 / 可访问范围`。**先权限与作用域过滤，再做相关性检索**。
具体：候选集 SQL 先按 `visible(item, exec)` 收窄，再进入打分；未授权数据**根本不进候选池**。

### 6.2 混合检索（不默认全塞向量库）

| 检索手段 | 适用 | 现状 |
|---|---|---|
| 精确命中 | pinned / 指定 id / fingerprint | 复用 `findById`/`byIds` |
| 结构化过滤 | owner/domain/layer/status/project | **新增**（P1） |
| 关键词 | 名称/标题精确 | 可接 FTS5（现有 `chunks_fts_*` 机制） |
| 向量 | 语义相关 | 复用 `MemoryItemRecall` 余弦 |
| 图/关系 | 实体关联、事实演化 | P4（`memory_edge`） |

**融合**：分值 = 向量余弦 × `layerWeight` × `domainWeight`（替代现有单维 `scopeWeight`）；时序**只过滤不排序**（继承 `MemoryRecall` 的正确取舍）。

### 6.3 Token 预算（配置化；解决 G6）

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

规则：
- 作用域过滤 → 混合打分 → 门控（`min-score`）→ 预算内贪心填充。
- 超预算移除顺序：`episode → shared → agent → project → profile`。
- **pinned / 正式画像 / 未完成事项（`open_issue`）永不因超预算移除**。
- 单条超长先截断（正本可回读，`source_refs` 指回 `memory_event`/`chat_message`）。
- 命中 id 写 `rag_recall` 留痕（复用现有 `memory` 通道）。

**这是对现有 `TOP_MEMORIES=5` 硬编码的替换——从「按条数」改为「按 token 预算」。**

### 6.4 Memory Snapshot 与一致性（指导文档 §五）

- **Turn 开始时构建只读快照**：`MemorySnapshot = { exec, 预算内命中的条目视图 }`，本轮注入用它。
- **Turn 内的写入不回改本轮快照**：本轮 Agent 刚记住的东西，下一轮才生效（避免同一轮内自我强化）。
- **一致性策略**（三选一，按需）：
  - 一般记忆：**当前 Turn 用启动时快照**；
  - 一般更新：**下一个 Turn 读最新版本**；
  - 安全/权限类变更：**强制刷新或实时校验**（`exec` 每轮重算，权限不缓存）。
- **Snapshot 不是把记忆永久复制进 Agent**：它是「一次执行所使用的上下文视图」。

### 6.5 注入渲染

复用现有 `MemoryProvider` + `hitsText` 渲染，但：
- 读取 `MemoryExec` 作用域（从 `ContextRequest` 扩展的 `agentId/projectId` 得到）；
- 按 §6.3 预算而非条数；
- 输出分组更清晰：`【用户画像】/【项目】/【我的经验】/【共享知识】/【本会话】`，并显式标注来源层，便于模型区分可信度。

---

## 7. 超越主流的四项增强

### 7.1 双时态记忆（Bi-Temporal）—— 超越 Letta，对齐并扩展 Zep

Zep/Graphiti 的核心洞察：**新事实与旧事实矛盾时，不删除旧事实，而是关闭其有效期**，保留完整审计与「某时刻知道什么」的点查能力（[Zep 双时态](https://www.getzep.com/ai-agents/temporal-knowledge-graph/)、[Zep 论文](https://arxiv.org/html/2501.13956)）。

v5 把双时态**下沉到条目本身**（不必先有全图）：
- `valid_at`（事实世界成立）/ `invalid_at`（事实世界失效）/ `learned_at`（系统学到）/ `expired_at`（系统得知失效）。
- 矛盾处置：新条目写入时，旧条目 `invalid_at` 关闭 + `status='superseded'`（`superseded_by` 成链），**永不物理删除**。
- 收益：直击「记忆漂移」（指导文档 §七）——临时决策不覆盖长期事实，而是各自带时间窗并存，可点查「上周我坚持的方案是什么」。

**超越点**：主流多为「事实图 + 双时态」，v5 让**条目级**记忆也具备双时态，且与**证据/审批**结合（画像的时间线可回溯）。

### 7.2 巩固升级（Dreaming）—— 超越 Mem0 的即时 ADD/UPDATE/DELETE

现有 `MemoryConsolidator` 只有确定性去重 + 裁剪，**语义级合并未实现**（[MemoryConsolidator.java](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/ai/memory/MemoryConsolidator.java#L88-L99)）。
v5 巩固升级为分层：

| 层次 | 动作 | 成本 |
|---|---|---|
| 确定性层（现有） | 指纹去重、episode 裁剪、过期关闭 | 零 LLM |
| 语义层（新增） | 同域内「新值取代旧值」判定、矛盾检测、合并同主题 | 一次 LLM，限同域 |
| 提升层（新增） | L2 经验 → 生成 L4 发布候选；L4 知识 → 生成 L1/画像候选 | 需发布/审批动作 |

**防递归**（指导文档 §四）：巩固**不产事件**；巩固读 `memory_cursor` 水位（**启用现有建而未用的 `memory_cursor`**，G10）；共享不触发提炼。三管齐下防止「无限制通知 / 递归讨论 / 重复提炼」。

### 7.3 交接与订阅发布（L4）—— 主流普遍缺失的跨 Agent 协作层

主流（Letta/Mem0）以单 Agent 为中心，跨 Agent 共享弱。v5 的 L4 是完整机制：

- **发布**：Agent 主动把 L2/L3 的结论/经验发布到指定作用域（`memory_share`，`to_kind ∈ {user, project, agent, session}`）。
- **发现**：接收方通过 `visible` 谓词 R5 的联合查询**按需发现**，**不推送**（避免通知风暴）。
- **权限与来源**：每次共享保留 `from_*`（来源）、`status`（published/revoked）、可撤销。
- **与现有子代理黑板的衔接**【代码事实】：现有 [ScratchpadStore](file:///d:/Static/worktable/ginpika-page/minis/backend/src/main/java/cc/ginpika/minis/agent/subagent/ScratchpadStore.java) 是会话级「只增不改」黑板（L3/L4 的雏形）。v5 把黑板定位为 **L3 会话工作集**的实现之一，L4 则由 `memory_share` 承担跨会话/跨项目发布。

### 7.4 隔离写入 · 按需读取 · 独立保留的工程化

把三条铁律落成可测试的机制：
- **隔离写入** = §4.4 矩阵 + `UserProfileService` 闸门 + 自动流程禁写画像。
- **按需读取** = §6.1 检索前作用域过滤 + §6.3 token 预算。
- **独立保留** = §8 保留策略（**不**由最近活跃决定生存）。

---

## 8. 生命周期与保留策略

### 8.1 状态与保留分离

- `status`：`active | superseded | expired | conflicted | revoked | archived`（**不含 candidate**——候选只在 `memory_candidate`）。
- `retention`：`pinned | long | normal | ephemeral`。

### 8.2 状态转换守卫

| 转换 | 条件 |
|---|---|
| active → superseded | `superseded_by` 非空，且被取代者非 `pinned` |
| active → expired | 到期（`valid_until`）且 `retention ∈ {normal, ephemeral}` |
| active → archived | 非 pinned、非 `open_issue` |
| active → revoked | **仅用户**，写墓碑 |
| conflicted → * | 仅人工/巩固裁决 |

### 8.3 淘汰不由活跃度决定（铁律三）

生存由 `retention + 类型 + 证据 + 保留策略 + 用户意图` 决定。**禁止**以「最近访问时间 / 写入频率 / 相似度 / 条数」作为淘汰依据。
区分：长期保护（pinned/画像/open_issue）/ 定期重评估 / 自动过期（有时效状态）/ 可重建缓存（向量投影）/ 可归档（episode）/ 用户明确删除。

### 8.4 保护规则落到淘汰 / 去重 / 巩固

```
protected = (retention='pinned')
         OR (owner='USER' AND status='active')          // 正式画像
         OR (scope='open_issue' AND status='active')    // 未完成事项
```
`protected` 条目：**淘汰跳过、去重跳过、巩固不取代、episode 裁剪保护**——不只体现在召回排序（修复现有 dedupe 跨域误伤，G8）。

---

## 9. 与 Turn / AgentRuntime / RAG 的集成

### 9.1 关键集成点

| 集成点 | 现状【代码事实】 | v5 改造 |
|---|---|---|
| `ContextRequest` | 只有 `sessionId`+`surface` | 增 `agentId/projectId/taskId`（G7） |
| `hub_session` | 无 `project_id` | 增 `project_id`（可空） |
| `task` | 有 `session_id/agent_id` | 增 `memory_checkpoint`（P1） |
| 会话创建 | 无项目 | 支持 `projectId` |
| Memory 触发 | `ChatTurnRunner:513-514` `tap(...)` | 传 `MemoryExec`，落点受 §5.1 约束 |
| 记忆事件 | `ChatTurnRunner:852,864` / `AgentRunner:141` | 保持 push 门面，补 `source_kind` 判定 |

### 9.2 Turn 生命周期（指导文档 §五）

1. **Turn 开始**：编排层组装 `MemoryExec` → 构建 `MemorySnapshot`（只读）。
2. **Turn 内**：写入不回改本轮快照；本轮新增下一轮生效。
3. **新 Turn**：读取最新有效记忆（受 §6.4 一致性策略约束）。
4. **暂停/恢复**：按 `(agent, project, task)` 还原快照 + `memory_checkpoint`。
5. **切换项目**：`exec.projectId` 变化，旧项目条目因 R2 不再可见（记忆不「清理」，只是不可见）。
6. **重建 Agent / 换模型**：长期记忆与 Agent 身份解耦（`owner=AGENT` 绑定 `agent.id`），身份重建后按 id 复用。
7. **发布后可发现**：接收方经 R5 联合查询发现，不推送。

### 9.3 各流的召回链路

| Surface | 读取范围 |
|---|---|
| CHAT（前台） | 画像 + 当前项目 + 本 Agent + 本会话 + 授权共享 |
| SUBAGENT（子代理） | 本会话黑板（`ScratchpadStore`）+ 派它的 Agent/项目作用域 |
| HEARTBEAT（心跳） | 所属 Agent + 绑定项目（不混入前台会话临时记忆） |

### 9.4 与 RAG / Working Set 的边界

- **RAG（`chunks`/`documents`/`entities`）是资料正本**，与记忆**不同层**：记忆是「关于用户/项目的稳定信息」，RAG 是「原始资料」。
- **Working Set**（长篇小说场景）= 结构化 Project Memory ⊕ RAG chunk（P4 给 RAG 加 `project_id` 过滤）。
- `LayeringTest` 已钉死 `ai.memory` 不依赖 `ai.knowledge`【代码事实】，两者由**编排层组合**，不互相 import。

---

## 10. API 变更

**新增**：
- `/api/projects`（CRUD）——项目工作域
- `/api/memory/candidates` + `/approve` + `/reject`——画像候选审批
- `/api/memory/items/{id}/revoke`——撤销（写墓碑）
- `/api/memory/items/{id}/share`、`/api/memory/shared`、`/api/memory/shared/{id}/revoke`——L4 发布/发现/撤销
- `/api/memory/working-set`——当前工作集视图

**修改**（新增可选参数，向后兼容）：
- `items` 列表支持 `projectId/owner/agentId/domain/layer/status`
- `items` 新增支持 `owner/projectId/agentId/retention`
- `consolidate` 返回冲突数
- 会话创建支持 `projectId`

**管理页**（[MemoryPanel.tsx](file:///d:/Static/worktable/ginpika-page/minis/frontend/src/pages/MemoryPanel.tsx)）：
按 Domain 分组替代按 scope 分组；新增「候选审批」「共享/已发布」「项目」三个视图。

---

## 11. 数据库迁移

### 11.1 执行顺序与职责边界

| 组件 | 职责 | 幂等 |
|---|---|---|
| `schema.sql` | **唯一结构权威**（新表/新列全列） | `IF NOT EXISTS` |
| `SchemaColumnPatches` | 为老库补可空列 | `PRAGMA table_info` 判存在 |
| `SchemaMigrations` | **仅数据级**（回填 / 部分唯一索引 / 必要重建） | `_schema.version` 递增 + 单事务 |

顺序：`schema.sql → Patches → Migrations`（均 `@DependsOnDatabaseInitialization`）；Migrations 启动校验版本一致性，不重复加列。

### 11.2 备份 / 验证 / 恢复（实测流程）

① 迁移前 `VACUUM INTO 'data/backups/minis-<ts>.db'` + 复制 `data/article`；
② 迁移前后记录行数与抽样哈希；
③ 按 `_schema.version` 单事务迁移；
④ 后验证 `integrity_check=ok`、`foreign_key_check` 空、行数一致、`owner/status IS NULL=0`、矩阵违例清单、「待复核」计数；
⑤ 失败中止（不递增版本）；⑥ 重复启动跳过；⑦ 失败恢复 = 备份覆盖 + 回滚旧程序。

> **【待验证】** 本轮未实测（真实库禁止默认重建）。P0 首验收项即在**副本**上完成 ①–⑦。

### 11.3 历史数据回填规则

| 现有数据 | owner | domain×layer | status | evidence_level / source_kind |
|---|---|---|---|---|
| `profile`/`directive` 且 `source=auto` | USER | USER_PROFILE×L1 | **转候选**（写 `memory_candidate`） | inferred |
| `profile`/`directive` 且 `explicit`/`manual` | USER | USER_PROFILE×L1 | active | explicit |
| `scope=project` | PROJECT | PROJECT×L1 | 按现行 → active/expired | inferred（无 project_id → 标「待复核」） |
| `scope=episode` 且 `agent_id` 非空 | AGENT | AGENT×L2|L3 | active/expired | inferred |
| `scope=episode` 且 `agent_id` 空 | AGENT(`'builtin'`) | AGENT×L2 | active/expired | inferred（**不得升为 USER**） |
| 其它/未知 | AGENT（保守） | AGENT×L2 | active | inferred（标「待复核」） |

- `version=0`；`fingerprint` 按 §4.5 计算；`retention`：`pinned=1 → pinned`，否则 `normal`。
- 回填**不静默删除**任何数据；无法分类者一律标「待复核」而非臆造归属。

---

## 12. 测试矩阵

> **测试纪律**：必须验证**真实行为**，而非只验证接口返回成功。全部测试在**副本库**上跑。

| # | 测试 | 阶段 | 真实行为断言 |
|---|---|---|---|
| T1 | 跨项目隔离 | P1 | 小说项目写条目 → 软件项目 `visible` 命中 0 |
| T2 | 画像保护 | P1/P2 | 连写剧情 → 无 `owner=USER,status=active` 新增 |
| T3 | 偏好升级闸门 | P2 | project 不升 user；仅 repeated+approve 才升 |
| T4 | 容量预算 | P3 | 注入 token ≤ budget；超长历史可按需回读 |
| T5 | 重要记忆保留 | P3 | 巩固后 pinned/画像/open_issue 仍 active |
| T6 | 并发写入 | P2 | 并发 update → 一方 409；冲突置 conflicted |
| T7 | 双时态与历史恢复 | P4 | 旧值 `invalid_at` 关闭、可点查；摘要可溯源 |
| T8 | 跨 Agent 共享/撤销 | P2 | 授权命中、未授权 0；撤销后 0 |
| T9 | 暂停恢复 | P5 | 重启恢复工作集、不误带他项目 |
| T10 | 过期修正 | P2 | 现行更新、旧值入历史链 |
| T11 | 候选审批撤销 | P2 | reject/revoke 后同指纹不再 approve |
| T12 | 零噪声 | P1 | 寒暄不产条目 |
| T13 | 迁移幂等 | P0 | 重复启动不重复执行、版本正确 |
| T14 | 备份恢复 | P0 | 副本备份 → 迁移 → 恢复一致 |
| T15 | 无项目默认作用域 | P1 | → `owner=AGENT('builtin')`，不产 `USER active` |
| T16 | MemoryProvider 注入 | P1 | 断言 system 实际内容（作用域正确） |
| T17 | recall_memory 工具 | P1 | 断言工具实际命中（按 `visible`） |
| T18 | 后台任务召回 | P2 | 心跳 Agent 谓词正确 |
| T19 | 项目切换 | P1 | 不返回旧项目条目 |
| T20 | 候选拒绝后重提炼 | P2 | 不再生成 pending |
| T21 | role-play 隔离 | P2 | 创作项目内容不产 `USER_PROFILE` active |
| T22 | 恢复路径 | P5 | 中断后可恢复且作用域正确 |

既有测试保持全绿：`MemoryItemStoreTest` / `MemoryConsolidatorTest` / `MemoryRecallTest` / `MemoryEventStoreTest` / `LayeringTest`。

---

## 13. 分阶段实施

> **纪律**：先测试后实施；复用现有模块；`LayeringTest` 保持；不静默丢弃/错误归类现有数据；DB 变更须有迁移与回滚。

| 阶段 | 交付范围 | 不含 | 验收 |
|---|---|---|---|
| **P0 迁移地基** | `SchemaMigrations` 执行器 + 备份/校验/回滚工具；空业务迁移 | 业务数据变更 | T13/T14 副本实测；真实库不被动 |
| **P1 作用域骨架** | `project` 表 + `hub_session.project_id` + `ContextRequest` 作用域 + `memory_item` 加列回填 + 读侧 `visible` + token 预算骨架 | 候选/共享 | T1/T12/T15/T16/T17/T19 |
| **P2 隔离与画像保护** | 候选权威 + 审批/撤销 + 乐观锁 + Agent 私有隔离 + 共享(L4) + role-play 隔离 | 大项目/归档 | T2/T3/T6/T8/T10/T11/T18/T20/T21 |
| **P3 大项目与预算** | 长度上限 + 配置化预算完整 + Working Set | 双时态 | T4/T5 |
| **P4 双时态与知识沉淀** | `memory_edge` + 双时态 + 语义合并巩固 + 内部文档版本 | 文章 url 约束变更 | T7 |
| **P5 并发/恢复/回归** | 任务检查点恢复 + 监控 + 全量回归 | — | T9/T22 + 全矩阵 |

---

## 14. 风险与未决项

| # | 问题 | 是否阻塞 | 处置 |
|---|---|---|---|
| R1 | 迁移/备份/恢复**未实测** | **阻塞 P0 验收** | P0 首任务：副本实测并记录 §11.2 ①–⑦ |
| R2 | 历史 `project` 条目无 `project_id` | 不阻塞 | 标「待复核」，不臆造 |
| R3 | 历史 auto 画像转候选 | 不阻塞 | 已定；部分旧画像需用户复核 |
| R4 | `agent_id NULL → 'builtin'` 改语义 | 不阻塞但需评审 | 迁移报告计数 |
| R5 | `MemoryIngest` 内存缓冲重启丢 | 不阻塞 | 记录；记忆可重建 |
| R6 | 双时态/知识边成本 | 不阻塞 | 拆 P4，可选启用 |
| R7 | 多用户未启用 | 不阻塞 | 字段预留（`user_id`），`visible` 已含 `userId` |
| R8 | 语义合并需 LLM 调用 | 不阻塞 | 限同域 + 水位（`memory_cursor`）控制频率 |

---

## 15. 附录

### 15.1 与 `memory-v4.md` 的关系

- **继承**：Project 实体、候选唯一权威、可验证证据、status/retention 分离、乐观锁 + fingerprint、配置化 token 预算、迁移分层、测试矩阵思想。
- **升级/改写**：
  1. **新增四层（L1–L4）维度**——v4 只有「作用域 + 状态」，v5 增加「流转层」这一正交轴，明确**跨 Agent 共享与交接**的机制（v4 的 `memory_share` 被纳入 L4 统一解释）。
  2. **归属决定律显性化**——v4 隐含在矩阵里，v5 提炼为第一原则，并据此推出 role-play 隔离（v4 未覆盖）。
  3. **Domain 概念**——v4 用 `scope` 承担语义，v5 拆出 `domain × layer`，`scope` 降为域内细分。
  4. **双时态**——v4 未涉及，v5 引入（P4）。

### 15.2 主流对标速查

| 能力 | Letta/MemGPT | Mem0 | Zep/Graphiti | **v5** |
|---|---|---|---|---|
| 分层记忆 | ✅ core/recall/archival | ⚠️ 四级作用域 | ❌ | ✅ L1–L4 |
| 自编辑 | ✅ memory blocks | 部分 | ❌ | ⚠️ 工具可编辑（在规划） |
| 双时态 | ❌ | ❌ | ✅ | ✅ 条目级 + 边级 |
| 画像保护/审批 | ❌ | ❌ | ❌ | ✅ 候选+证据+撤销 |
| 跨 Agent 共享/交接 | ⚠️ 有限 | ⚠️ 有限 | ⚠️ 图共享 | ✅ L4 发布/订阅 |
| 写入权限治理 | ❌ | ❌ | ❌ | ✅ visible/写权限谓词 |
| 隔离写入/按需读取/独立保留 | 部分 | 部分 | 部分 | ✅ 三铁律工程化 |

### 15.3 参考

- MemGPT: Towards LLMs as Operating Systems — https://arxiv.org/pdf/2310.08560
- Letta — Memory Blocks / 三层记忆 — https://www.letta.com/blog/memory-blocks/ · https://pypi.org/project/letta/
- Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory — https://arxiv.org/html/2504.19413
- Zep: A Temporal Knowledge Graph Architecture for Agent Memory — https://arxiv.org/html/2501.13956
- Zep 双时态模型 — https://www.getzep.com/ai-agents/temporal-knowledge-graph/

---

> **状态（已过期，保留作史料）**：本文写作时「未实施、未改动真实库」。**实际已按此文的地基实施完毕**，
> 并由 `docs/memory-v6.md` 接续演进（v6 已完成 P0–P4：作用域契约、快照与暂存、多面接入、发布/交接/提升、
> 工作集与按层预算、体检、双时态点查、写入审计、显式遗忘、运行指标）。
> **本文的角色**：数据地基与既有决议的史料来源——**凡与 `docs/memory-v6.md` 冲突，以 v6 为准**。
> **当前待验收项**：P0 的副本库迁移（备份 / 校验 / 回滚，见 v6 §17.1）。
