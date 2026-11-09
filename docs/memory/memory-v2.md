# 记忆体系 v2 · 全面对齐 ChatGPT 推进文档

> 状态：**草案（评审中）**。本文只定方向、边界、步骤与验收，不含实现细节；进度见各 STEP 下的「进度」注记。
> 命名约定：步骤统一用 `STEP M1 / STEP M2 / …`（M = Memory），与 `agent-runtime-architecture.md` 的 `STEP 1…7` 并列、不占用其编号。
> 适用范围：`minis` 后端（`backend/`）+ 智能体页 / 首页对话前端（`frontend/`）。
> 代码基线：`agent-runtime-architecture.md`（STEP 7）、`ai/memory/**`、`agent/context/**`、`agent/heartbeat/**`。

---

## 实现进度（2026-10-11）

**已完成**：STEP M1–M5、附加需求 §13 均已落地（后端 + 记忆管理前端页）；`mvnw compile` / `test-compile` 通过，
新增回归测试（`MemoryItemStoreTest` / `MemoryConsolidatorTest` / `SessionTitleServiceTest`）与受影响的既有测试
（`ContextInjectionProviderTest` / `MemoryRecallTest`）全绿；前端 `tsc -b` 通过。

**与本文设计的偏差（实现时按真实约束落的）**：

1. **巩固调度自建**：`LayeringTest` 钉死 `ai.memory` 不得依赖 `agent`，故 `MemoryConsolidator` **照抄**
   `HeartbeatService.scan()` 的骨架（单扫描器 + 预筛 + 虚拟线程）而**不 import** 它。
2. **巩固为确定性版**：实现「去重（取代重复，保留最新）+ 情景层裁剪」；**语义级合并**（新旧冲突时
   「新值取代旧值」的 LLM 判断）列为后续增强，`MemoryConsolidator` 已预留位置。
3. **`memory_cursor` 表已建但暂未使用**：提炼走「轮次级 push」（会话侧把本轮对话交进来），
   不扫描 `memory_event`，故无需水位线（表保留供未来扫描式提炼使用）。
4. **提炼提示词内置常量**：`MemoryExtractor` / `SessionTitleService` 的提示词先内置在 Java 里，
   未迁到提示词页（`PromptStore`）——迁入为后续项。
5. **`recall_memory` 默认读条目**，保留 `mode=event` 走事件下钻（`MemoryRecall` 因此仍有用途，非死代码）。
6. **默认开**：`minis.memory.enabled=true` 且 `minis.context.inject-memory=true`——记忆是产品能力，
   默认就该工作；未配置模型时自动空转（不提炼 / 不注入、不报错），需要时可显式关闭。
7. **提炼改为攒批触发**：会话侧 push 进缓冲（`MemoryIngest`），攒够 `batch-turns` 或被停 `idle-ms` 后
   **合并提炼一次**——把逐轮的固定提示词与输出开销摊薄一个数量级；命中「记住」类指令仍立即提炼。

**顺带修复**：`SpeakerPicker.java` 存在一个既有编译错误（lambda 引用非 effectively-final 变量），
阻塞构建，已用最小等价改动修掉。

---

## 0. 目标与非目标

### 一句话目标

把「**事件日志检索**」升级为「**记忆条目 + 后台巩固 + 相关性注入 + 用户可管理**」：让系统在新会话里**默认就知道**用户是谁、在做什么、答应过什么，并能被用户查看、编辑、删除——即 ChatGPT / Gemini 那种「跨会话记得你」的形态。

### 非目标（对齐范围之外）

| 不做 | 原因 |
|---|---|
| 跨用户 / 多租户记忆隔离 | 当前为单用户本地库（`app_user` 单行）；未来需要时再按 `user_id` 分区 |
| 云端跨设备同步、团队共享记忆 | 本地 SQLite 部署形态不支持 |
| 记忆进模型权重（训练侧） | 本系统为推理期注入，不做微调 |
| 重写知识库 RAG（chunk / 图谱三路） | 本次只动「记忆」这一路；世界知识仍走现有检索 |

---

## 1. 现状与差距

### 1.1 现状：只有「事件层」，没有「记忆」

现有记忆模块（`ai/memory/**`）本质是一条**只追加的对话/执行事件流**：

- 写入：`MemoryWriter` 在发生点 push（`chat_message:{id}` / `task:{id}`），幂等、失败隔离；`MemoryEmbeddingWriter` 异步补向量。
- 存储：`memory_event`（时序正本投影）+ `memory_event_embedding`（向量投影）。
- 读取：`MemoryRecall`（时间窗收敛候选 → 窗口内向量余弦打分 → `hitsText` 渲染）；消费方为 `recall_memory` 工具（`KnowledgeToolbox.recallMemoryTool`）与 `MemoryProvider`（STEP 2 上下文源，默认关）。

即：**每一行 `memory_event` 就是一条「记忆」**——这是「日志」，不是「记忆」。

### 1.2 实测：这条链路目前产出的东西不可用

以本地库 `data/minis.db` 实测（2026-10-11）：

| 指标 | 实测值 |
|---|---|
| `memory_event` 事件 | user/chat = 221、assistant/chat = 220、agent/exec = 10 |
| 召回候选池（最近 200 条、排除后台） | assistant = 100、user = 100（对半） |
| `recall_memory` 留痕 115 条 | 66 条含 assistant |
| 内置 Agent 最近一次召回（`limit=6`，query=「最近聊过什么」） | 返回 6 条**全是 user**：`你是？/继续/今天几号？/现在几点？/什么法子` |
| `memory_event.title` 频率 | `[# 世界观 - 时间：2079 年 -]` ×300、`[你是？]` ×100 |

### 1.3 差距清单（驱动本方案）

| # | 现象 | 根因 | 代码位置 |
|---|---|---|---|
| D1 | 返回内容以会话标题（title）刷屏，而非事件正文 | `title` 是**会话级**属性被逐条复制进**事件级**表，渲染时又置于显眼位 | `ChatTurnRunner.recordUserInput/recordReply` 传 `row.title()`；`MemoryRecall.hitsText` |
| D2 | 正文只给前 160 字 | `hitsText` 对 payload 做 `head(...,160)`；入库时已先截 500 字 | `MemoryRecall.hitsText` / `MemoryWriter.PAYLOAD_LIMIT` |
| D3 | 无信息量短句（`你是？/继续`）照返 | 无「记忆价值」门槛，全部消息一律成记忆 | `MemoryWriter.onChatMessage` |
| D4 | 泛查询下 top-k 全是短句、长回复落榜 | 事件粒度不对称（user 一句 = 一条、assistant 一段 = 一条），向量检索偏向短文本 | `MemoryRecall.recall` |
| D5 | 执行结论（`kind=exec`）默认召回不到 | `BACKGROUND_ACTORS` 默认挡掉 `heartbeat/plan/agent` | `MemoryRecall.BACKGROUND_ACTORS` |
| D6 | 记忆不可见、不可改 | 无条目对象、无管理界面 | —— |

**结论**：问题不在「记不记 assistant」，而在**系统里根本没有「记忆」这个对象，只有「事件」**。

---

## 2. 目标形态：全面对齐 ChatGPT

### 2.1 功能全景对照

| ChatGPT 能力 | 说明 | 我方对齐 | 落点 |
|---|---|---|---|
| **Explicit memory**（Saved memories） | 用户说「记住：我下月去新加坡」→ 存条目 | ✅ 对齐 | M2 |
| **Auto memory**（隐式学习） | 从对话中自动提取关于用户的信息 | ✅ 对齐 | M2 |
| **Reference chat history** | 参考历史对话做个性化 | ✅ 对齐（以条目为默认，原始历史作下钻） | M3 |
| **Dreaming**（后台合成） | 后台流程从多轮对话合成记忆状态 | ✅ 对齐 | M4 |
| **Memory summary page** | 记忆可见、可增删改 | ✅ 对齐 | M5 |
| **Memory updated 提示** | 写入记忆时给用户可见反馈 | ✅ 对齐（轻提示） | M5 |
| **引用可见** | 回答里标出用了哪条记忆 | ✅ 对齐（badge） | M5 |
| **用户指令**（提/不提） | 「不要主动提 X」「关于 Y 可以提」 | ✅ 对齐 | M4/M5 |
| **Temporary chat** | 临时会话不计入记忆 | ✅ 对齐（会话级开关） | M2 |
| **时间感知** | 记忆随时间为准、能说清"何时" | ✅ 对齐（`valid_from/until` + 巩固） | M4 |
| 云端跨设备同步 / 共享 | —— | ⛔ 不适用 | §0 |

### 2.2 三条「好记忆」验收基准（官方口径）

1. **Carry forward context**：说一次，后续会话自动带上。
2. **Follow preferences & constraints**：记住偏好并持续遵守。
3. **Stay current over time**：记忆随时间为准（过期即失效）。

> 关键认知：用户描述的「几天前聊过 AI 架构设计 → 聊新技术时主动联系起」，**靠的不是找回那句原话，而是先有一条稳定条目「用户在做 AI 架构设计」，再在新会话按当前话题检索并注入**。

---

## 3. 四层记忆模型

引入「记忆条目」作为一等公民，与现有事件流分层共存：

| 层 | 名称 | 内容 | 生命周期 | 载体 |
|---|---|---|---|---|
| **L1** | 画像层 · profile | 长期稳定的偏好/身份/约束（素食、做 AI 架构、中文优先） | 长期，人工可改 | `memory_item(scope=profile)` |
| **L2** | 项目层 · project | 进行中的事/主题，可过期（"下周上线 X"） | 中期，带 `valid_until` | `memory_item(scope=project)` |
| **L3** | 情景层 · episode | 会话/时段摘要（"10-10 讨论了记忆架构"） | 短期 | `memory_item(scope=episode)` |
| **L4** | 证据层 · event | 原始对话/执行事件（现有 `memory_event`） | 只追加 | **保留不变**，降级为下钻/审计 |
| **L0** | 指令层 · directive | 用户对记忆行为的元指令（提/不提、何时提） | 长期 | `memory_item(scope=directive)` |

**职责划分**：

- **默认注入 / `recall_memory` 的返回对象 = L0–L3 条目**（§5）。
- **L4 事件流**保留为「证据」：从条目 `source_refs` 下钻到原始事件；`rag_recall` 留痕不变。
- 结构性消除 D1/D2/D3/D4：记忆召回不再直接吐事件行。

---

## 4. 三段流程

```
对话发生 ─► ① 写入(提炼) ─► 条目(L0–L3) ─► ④ 读取(检索+门控) ─► 注入 system / 工具返回
              ▲                                  ▲
         (证据) memory_event(L4) ─────────────────┘(下钻)
              ▲
      ② 后台巩固(Dreaming)：合并 / 去重 / 更新 / 过期 / 指令生效
              ▲
      ③ 管理(人)：看 / 改 / 删 / 给指令
```

### ① 写入（Capture · 提炼）

- 触发：会话空闲阈值到达 / 会话结束 / 后台节拍（非主链路，**异步**）。
- 输入：该会话自**水位线**（`memory_cursor`）以来新增的 `memory_event`。
- 处理：一次 LLM 调用（`OpenAiChatClient.chat(target, messages, source)`），产出**结构化候选条目**（scope/title/body/confidence/valid_until/source_refs）。
- **显式记忆**：用户消息命中「记住…」句式时，走**同步优先**路径（当场落条目并回一条「已记住」提示），对齐 ChatGPT 的显式保存体验。
- 门槛：跳过无信息量输入（长度阈值、纯寒暄/问句、与已有条目等价）→ 直接解决 D3。
- 落库：`memory_item` + 异步向量（复用 `MemoryEmbeddingWriter` 模式）。
- 硬规则：**失败隔离**（写不进绝不拖垮聊天主流程）、**幂等**（按来源 ref 去重）、**临时会话不提炼**（会话级开关）。

### ② 后台巩固（Consolidate · Dreaming）

- 宿主：独立调度器（**复用** `HeartbeatService` 的「单扫描器 + 预筛 + 虚拟线程」骨架，见 `scan()`）。
- 动作：合并/去重、冲突 `supersede`、`valid_until` 失效、L3→L2 提升、`episode-max` 裁剪、指令（L0）生效。
- 输出：可读「记忆摘要」文本（供管理页展示）。

### ③ 管理（人）

- 新增「记忆」页：查看 L0–L3 条目、手动增删改、写指令、清空。
- 落点：智能体页「系统」tab、与现有「RAG 召回」子页并列（`MultiAgent.tsx`）。
- 对齐 ChatGPT：记忆**用户可见、可控**，不是黑箱。

### ④ 读取（Recall & Inject）

- 对象：`memory_item`（不再直接检索 `memory_event`）。
- 打分：向量余弦（`memory_item_embedding`）+ 关键词命中，按 scope 加权（profile > project > episode）。
- **门控**：低于 `min-score` 或无命中 → **不注入**（对齐 Gemini「先判断这次历史信息有没有用」）。
- 注入：`MemoryProvider`（STEP 2，`@Order(-5)`）注入条目；`recall_memory` 工具查条目并支持下钻 L4。
- 可见性：注入命中时，回答侧可标「引用记忆」badge（对齐 ChatGPT 的引用可见）。

---

## 5. 数据模型（草案）

> 约定：`schema.sql` 幂等（`IF NOT EXISTS`）、`_schema.version` 记版本；STEP 7 已定「DB 可破坏」，**新增表直接改 schema 并重建库，不写迁移器**。

```sql
-- 记忆条目（L0 指令 / L1 画像 / L2 项目 / L3 情景）：系统里「记忆」的一等公民
CREATE TABLE IF NOT EXISTS memory_item (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id      TEXT,                      -- 归属 Agent（agent.id）；NULL=全局（跨 Agent 的用户画像）
  scope         TEXT NOT NULL,             -- directive | profile | project | episode
  title         TEXT,                      -- 一句话标题（列表/注入的极简识别）
  body          TEXT NOT NULL,             -- 记忆正文（关于用户/项目的稳定信息，非原文切片）
  confidence    REAL NOT NULL DEFAULT 0.5, -- 提炼置信度 0..1
  source        TEXT NOT NULL DEFAULT 'auto', -- auto(隐式提炼) | explicit(用户明说) | manual(人工编辑)
  valid_from    INTEGER NOT NULL,
  valid_until   INTEGER,                   -- 过期时刻（NULL=不过期）；巩固据此失效
  superseded_by INTEGER,                   -- 被哪条新条目取代（NULL=仍有效）
  source_refs   TEXT,                      -- 来源证据：memory_event.id 数组（JSON），供下钻
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_item_scope ON memory_item(scope, superseded_by, valid_until);
CREATE INDEX IF NOT EXISTS idx_memory_item_agent ON memory_item(agent_id, scope);

-- 条目向量（对齐 memory_event_embedding 列结构）
CREATE TABLE IF NOT EXISTS memory_item_embedding (
  item_id    INTEGER PRIMARY KEY REFERENCES memory_item(id),
  model      TEXT NOT NULL,
  dim        INTEGER NOT NULL,
  vec        BLOB NOT NULL,                -- little-endian float32，同模型余弦
  updated_at INTEGER NOT NULL
);

-- 提炼水位：每个会话已提炼到哪条事件，避免重复提炼
CREATE TABLE IF NOT EXISTS memory_cursor (
  session_id    TEXT PRIMARY KEY,
  last_event_id INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
```

会话级新增（`hub_session` 增列，用于临时会话）：

```sql
-- 追加到 hub_session：临时会话不进记忆（对齐 ChatGPT Temporary chat）
ALTER TABLE hub_session ADD COLUMN memory_off INTEGER NOT NULL DEFAULT 0;
```

---

## 6. 与现有代码的衔接

| 构件 | 处置 | 说明 |
|---|---|---|
| `MemoryWriter` / `MemoryEventStore` / `memory_event` | **保留** | 继续作为 L4 证据层写入，接口不变 |
| `MemoryEmbeddingWriter` / `MemoryEventEmbeddingStore` | **保留 + 复用模式** | 条目向量照抄「异步补齐 + backfill」两条入口 |
| `MemoryRecall` | **改造** | 增加「按条目检索」读侧；新渲染不再有 title 刷屏 / 160 截断 |
| `recall_memory`（`RecallMemoryTool` + `KnowledgeToolbox.recallMemoryTool`） | **改造** | 默认查条目；新增下钻参数查 L4 |
| `MemoryProvider`（STEP 2） | **改造** | 注入对象换条目 + 门控；默认仍关（`minis.context.inject-memory`） |
| `RagRecallRecorder` / `rag_recall` | **复用** | `recall_memory` 埋点不变，仍走 `memory` channel |
| `HeartbeatService.scan()` 骨架 | **复用** | 巩固调度照抄「单扫描器 + 预筛 + 虚拟线程」 |
| `OpenAiChatClient.chat(target, msgs, source)` | **复用** | 提炼/巩固 LLM 调用；`source` 走 token 统计 |
| `SettingsService.chatModel()` / `resolveChatModel(id)` | **复用** | 提炼/巩固默认沿用聊天模型，可另配 |
| `ContextProvider`（`@Order`） | **复用** | 记忆注入仍是上下文源之一 |
| 智能体页「系统」tab | **扩展** | 新增「记忆」子页 |

**新增组件（建议命名）**：`MemoryItemStore`、`MemoryItemEmbeddingStore`、`MemoryItemEmbeddingWriter`、`MemoryExtractor`（写入提炼）、`MemoryConsolidator`（后台巩固）、`MemoryItemRecall`（读侧）、`MemoryController`（API）。

---

## 7. 接口与前端

### 7.1 后端接口（新增 `MemoryController`）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/memory/summary` | 记忆摘要（按 scope 分组的可读文本，对齐 memory summary page） |
| GET | `/api/memory/items` | 条目列表（可按 scope/agent 过滤，分页） |
| POST | `/api/memory/items` | 人工新增条目（source=manual） |
| PUT | `/api/memory/items/{id}` | 编辑条目 |
| DELETE | `/api/memory/items/{id}` | 删除条目（删除后不再注入） |
| POST | `/api/memory/items/{id}/pin` | 置顶/固定（强制注入，不参与门控） |
| POST | `/api/memory/consolidate` | 手动触发一次巩固（对齐页面「立即整理」） |
| DELETE | `/api/memory` | 清空全部记忆（对齐「重置知识库」的既有做法） |

### 7.2 前端

- **记忆页**：智能体页「系统」tab 新增「记忆」子页——按 scope 分组的条目卡片、行内编辑、删除、写指令、清空、手动巩固。
- **对话内**：`Memory updated` 轻提示（本轮新增/更新了记忆时）；命中记忆时回答侧「引用记忆」badge（点击可查看条目）。
- **会话开关**：会话「…」菜单加「本次不记入记忆」（写入 `hub_session.memory_off`）。

---

## 8. 提示词设计要点

### 8.1 提炼（Extractor）

- **角色**：只产出「关于用户的稳定信息」，不产出世界知识、不复述对话。
- **输出**：JSON 数组，每项 `{scope, title, body, confidence, valid_until?, refs}`。
- **scope 判定**：偏好/身份/长期约束 → `profile`；进行中的事 → `project`；一次性时段摘要 → `episode`；对记忆行为的元指令 → `directive`。
- **拒绝规则**：寒暄、纯提问、无主语句、与已有条目等价的，一律不产出（对齐 D3）。
- **可回退**：模型输出非 JSON / 空数组时，本轮不落条目（失败静默）。

### 8.2 巩固（Consolidator）

- **输入**：同一 scope/主题下的条目集合 + 现有摘要。
- **输出**：合并后的条目集（含 `supersede` 关系与 `valid_until` 调整）。
- **原则**：新值取代旧值；不确定则不合并；绝不凭空编造未出现过的信息。

> 提示词正文沿用现有 `PromptStore` 体系存放（如新增 `memory.extract` / `memory.consolidate` 两个提示词位），不硬编码在 Java 里。

---

## 9. 配置（草案）

```yaml
minis:
  memory:
    enabled: true                  # 总开关：关时提炼 / 巩固 / 条目召回短路（默认开）
    extract-model: ""              # 提炼/巩固用模型；空 = 沿用 chatModel()
    top-k: 6                       # 默认注入条目上限
    min-score: 0.35                # 相关性门控阈值（低于不注入）
    noise-min-chars: 6             # 信息量门槛：正文短于此不作为提炼素材
    batch-turns: 4                 # 攒批：同一会话攒够这么多轮就合并提炼一次（降调用次数）
    idle-ms: 300000                # 空闲阈值：会话静默这么久后合并提炼一次
    consolidate-interval-ms: 3600000  # 巩固节拍（默认 1h）
    episode-max: 50                # 情景层保留条数上限（超出由巩固裁剪）
```

`minis.context.inject-memory` 语义升级为「注入条目」，默认 **true**（记忆是产品能力，默认就该工作）。

---

## 10. 分步推进

每步独立可验证、默认关或行为零变化；格式对齐 `agent-runtime-architecture.md`（目标 / 交付 / 验收 / 依赖）。

### STEP M1 · 地基：记忆条目成为一等公民

> **进度：已落地。**

**目标**：建 L0–L3 存储与向量投影，记忆第一次有「条目」这个对象。

**交付**
- `schema.sql` 增 `memory_item` / `memory_item_embedding` / `memory_cursor`；`hub_session` 增 `memory_off`。
- `MemoryItemStore`（CRUD + 按 scope/agent 查询）、`MemoryItemEmbeddingStore`、`MemoryItemEmbeddingWriter`（异步补齐 + `backfill_memory_item_vectors` 工具）。

**验收**
- 条目可落库/读取/补向量；重建库不报错。
- 现有 `memory_event` 链路行为逐字不变。

**依赖**：无。

---

### STEP M2 · 写入：从「逐条消息」到「提炼条目」

> **进度：已落地。**

**目标**：会话新增内容被**提炼**成结构化条目，过滤无信息量输入；支持显式记忆与临时会话。

**交付**
- `MemoryExtractor`：按 `memory_cursor` 水位取增量事件 → LLM 提炼 → 结构化候选。
- 信息量门槛、幂等去重、**显式「记住…」优先路径**、临时会话跳过（`memory_off`）。
- 提示词位 `memory.extract`；触发 = 空闲阈值 / 会话结束 / 后台节拍；全程异步、失败隔离。

**验收**
- 含「你是？/继续」的对话跑完：噪声**不产出条目**；出现至少一条可读的 profile/project 条目（消除 D3）。
- 显式「记住 X」当场产出条目并有可见提示；临时会话不产出任何条目。
- 提炼失败不影响对话主流程；`source_refs` 能指回原始 `memory_event`。

**依赖**：M1；`OpenAiChatClient`、`SettingsService.chatModel()`、`PromptStore`。

---

### STEP M3 · 读取：检索条目 + 门控注入

> **进度：已落地。**

**目标**：`recall_memory` 与 `MemoryProvider` 改为面向条目，并加相关性门控。

**交付**
- `MemoryItemRecall`：向量 + 关键词打分，按 scope 加权；`min-score` 门控。
- `recall_memory`：默认返回条目；新增「下钻」参数按 `source_refs` 查 L4。
- `MemoryProvider`：注入条目 + 门控（无命中不注入）；默认关。
- 渲染：`时间 · {scope 标签} · title/body`，**不含会话标题**、不再 160 字截断（消除 D1/D2）。

**验收**
- 问「最近聊过什么」返回**相关且可读**的条目，而非 `你是？/继续`（消除 D4）。
- 未命中/低分时 `system` 不新增内容；关闭开关时请求体逐字不变。

**依赖**：M2。

---

### STEP M4 · 巩固：后台合成（Dreaming）

> **进度：已落地（确定性版）。**

**目标**：零散条目被持续合并、更新、过期，形成「记忆状态」。

**交付**
- `MemoryConsolidator` + 调度（复用 `HeartbeatService.scan()` 骨架）。
- 合并/去重、冲突 `supersede`、`valid_until` 失效、L3→L2 提升、`episode-max` 裁剪、指令（L0）生效。
- 提示词位 `memory.consolidate`；产出可读「记忆摘要」。

**验收**
- 同一事实重复条目归一；"以前用 X → 现在用 Y" 收敛为单条最新值。
- 过期条目不再被注入（对齐 "Stay current"）。
- 空转有预筛跳过计数（对齐心跳 `idleSkips` 观测口径）。

**依赖**：M2/M3。

---

### STEP M5 · 管理与观测

> **进度：已落地。**

**目标**：记忆对用户**可见、可控**（对齐 memory summary page）。

**交付**
- `MemoryController`（§7.1 接口）+ 智能体页「系统」tab 新增「记忆」子页（列表/编辑/删除/指令/清空/手动巩固）。
- 对话内 `Memory updated` 轻提示；命中时的「引用记忆」badge。
- 观测：`rag_recall` 的 `memory` 通道留痕继续可用；补充「本次注入命中哪些条目」可回看。
- 设置页暴露 `minis.memory.*` 关键开关。

**验收**
- 用户能看见"系统记得我什么"，并能改/删；删除后不再注入。
- 记忆注入与留痕可在页面回看。

**依赖**：M1–M4。

---

### STEP M6 · 收尾：旧链路退场与回归

> **进度：部分落地。**

**目标**：事件层彻底降为证据层，默认行为切换到新记忆。

**交付**
- `MemoryProvider` / `recall_memory` 默认走条目；事件直返路径仅保留在「下钻」。
- 增加回归用例（§11）。
- 文档更新：`agent-runtime-architecture.md` 补记记忆 v2。

**验收**
- 三条「好记忆」基准全绿；本仓 5 个回归项（D1–D5）全绿。

**依赖**：M2–M5。

---

## 11. 成本、评估与测试

### 11.1 成本

- 提炼/巩固是额外 LLM 调用：**异步 + 节流 + 水位线只提炼增量**，默认关。
- 调用 `source` 标签分别记 `memory-extract` / `memory-consolidate`，进 `token_usage`，统计页可见（复用现有 `TokenUsageService`）。

### 11.2 单元测试（对齐现有测试目录）

| 用例 | 断言 |
|---|---|
| `MemoryExtractorTest` | 噪声输入不产出条目；显式「记住」产出条目；JSON 解析失败静默 |
| `MemoryConsolidatorTest` | 冲突条目 `supersede` 正确；过期条目被排除 |
| `MemoryItemRecallTest` | 门控生效（低于 `min-score` 不返回）；scope 加权顺序 |
| `MemoryItemStoreTest` | CRUD、幂等去重、`source_refs` 往返 |

### 11.3 离线验收用例（对齐三目标）

1. 会话 A 说「我在做 AI 架构设计」→ 会话 B 问「这个新技术怎么样」→ 回答应引用该画像条目。
2. 会话 A 说「我吃素」→ 会话 B 推荐餐厅 → 应遵守约束。
3. 条目带 `valid_until`（"下周的生日派对"）→ 到期后不再注入，且被新条目取代。

### 11.4 在线指标

- 注入命中率 / 平均注入条数 / 单条含 token 量。
- 召回结果中「无信息量条目」占比（目标 0）。

---

## 12. 风险与权衡

| 风险 | 说明 | 对策 |
|---|---|---|
| 提炼成本 | 每次提炼/巩固一次 LLM 调用 | 异步 + 节流 + 水位线只提炼增量；默认关 |
| 幻觉/错误记忆 | 提炼可能记错、记偏 | `confidence` + `source_refs` 可追溯 + 人工可编辑可删 |
| 记忆污染 | 错误条目长期影响注入 | 巩固可 `supersede`；管理页可删；门控降低影响面 |
| 冲突与时序 | 新旧信息矛盾 | "新值取代旧值" + `valid_until` |
| 复杂度上升 | 多三张表 + 多个组件 | 严格分步、每步默认可关、行为零变化 |
| 与 RAG 混淆 | 记忆（用户相关）与知识库（世界知识）边界 | `memory_item` 只装"关于用户/项目"的信息，世界知识仍走 chunk/图谱 |
| 提示词不稳定 | 提炼质量受模型影响 | 提示词走 `PromptStore` 可调；产出走结构化 JSON；失败静默 |

---

## 13. 附加需求 · 会话自动命名（对齐 ChatGPT）

> 与记忆体系**解耦**，可独立实现（建议排在 STEP M1 之前或与之并行）。
> 一句话：新建会话保持「未命名」，用户**首次输入后**额外请求一次 LLM API，为该会话生成一句概括标题——就像 ChatGPT / Gemini 那样。

### 13.1 现状（截断式标题，不调 LLM）

| 环节 | 现状 | 代码位置 |
|---|---|---|
| 新建会话 | `sessions.insert(hubId, null)` → `title` 为空（未命名） | `ChatSessionService.create()` |
| 首次回填 | 首次 Turn **完成（有回答）后**调 `exec.touchSession(row, abbreviate(input))` | `ChatTurnRunner` |
| 截断规则 | `abbreviate(input)` 取首条输入**前 20 字**（`TITLE_MAX = 20`） | `ChatTurnRunner.abbreviate` |
| 只填一次 | `UPDATE ... SET title = COALESCE(title, ?)`，仅在 title 为空时写入 | `HubSessionStore.touch` |

**后果**：标题 = 首句截断，出现「你是？」「今天几号？」「宝贝，帮我清空两台服务器上，你访问的工作」这类**无概括性**的标题（实测见 §1.2：`[你是？] ×100`）。

### 13.2 目标

新建会话仍旧**未命名**；用户首次输入（首个 Turn 完成）后，**额外一次 LLM 调用**生成一句概括标题（默认 ≤ 16 字、单行、无引号/句号），写入会话标题。生成失败/未配置时**降级为现有截断**，行为不劣化。

### 13.3 设计

| 环节 | 设计 |
|---|---|
| **触发时机** | 首个 Turn 完成、且 `title` 仍为空时触发**一次**；异步执行，**不阻塞回答、不增加首字延迟** |
| **输入** | 首条用户输入（可选附首个回答的首段，帮助概括） |
| **输出** | 单行标题，≤16 字，无引号、无结尾标点、无「标题：」前缀；解析失败视为失败 |
| **调用** | 复用 `OpenAiChatClient.chat(target, messages, source="session-title")`；模型取 `SettingsService.chatModel()`（可另配 `minis.title.model`） |
| **落库** | `HubSessionStore` 新增 `setTitleIfAbsent(id, title)`，沿用 `COALESCE` 语义——**不覆盖**用户手动命名或已生成标题 |
| **前端刷新** | 经 `SessionEventBus` 推一次标题更新（复用/新增事件类型），会话列表即时改名，无需刷新 |
| **降级** | 未配置 / 调用失败 / 超时 / 空输出 → 回落到 `abbreviate(input)`（与现状一致） |
| **成本** | `source="session-title"` 计入 `token_usage`，统计页可见 |

### 13.4 边界

- 用户**手动命名**过的会话不被覆盖（`COALESCE`/`setTitleIfAbsent` 天然保证）。
- 仅对**首页会话**（`mode='ai'`）生效；圆桌会话（`mode='multi-agent'`）不处理。
- 子代理投递 / 系统消息不算「首条输入」，不触发命名。
- 首条输入很短（如「你好」）也要生成（对齐 ChatGPT 行为）。

### 13.5 验收

- 新建会话首条输入后，列表标题是**概括短语**，而非首句截断。
- 生成失败时仍可用（降级截断），且**不阻塞回答、不产生首字延迟**。
- 用户手动命名的会话不被自动标题覆盖。

**依赖**：`OpenAiChatClient`、`SettingsService.chatModel()`、`HubSessionStore`、`SessionEventBus`。

---

## 附录 A · 参考

- OpenAI《Dreaming: Better memory for a more helpful ChatGPT》——saved memories(2024) → dreaming(2025) → Dreaming V3(2026)；三条评估目标。
- OpenAI《Memory and new controls for ChatGPT》（2025-04）——saved memories + chat history 两种记忆。
- Google《Gemini app personalizes responses based on past chats》——记住"关键细节与偏好"，由推理模型判断历史信息是否有用后再用。

## 附录 B · 术语

- **事件（event）**：`memory_event` 一行，对话/执行的原始投影（L4）。
- **条目（item）**：`memory_item` 一行，关于用户/项目的稳定信息（L0–L3）。
- **提炼（extract）**：从事件增量产出条目的过程（写入）。
- **巩固（consolidate）**：对条目做合并/更新/过期，形成记忆状态（后台，对标 dreaming）。
