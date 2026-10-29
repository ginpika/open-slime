# 长期记忆：统一、可追溯的知识正本

日期：2026-10-20。本文描述已接入的后端实现。它更新 memory-v6 中长期记忆的物理存储、图谱归属和注入预算约定；原有画像审批、会话隔离、共享授权、工作集和快照契约继续适用。

## 事实与原文的权威边界

| 对象 | 持久表 | 职责 |
| --- | --- | --- |
| 事实声明 | `graph_claim` | 唯一的事实正文、归属、身份、有效期、状态、版本与来源引用；沿用原条目 ID |
| 实体 | `graph_entity` | UUID 身份、作用域、身份键、名称及元数据声明引用；同域同名对象可以有不同身份键 |
| 关系 | `graph_relation` | 引用声明和两端实体；关系的可见性由声明决定 |
| 原始经历 | `graph_episode` | 不可变的原文版本及来源标识、执行作用域、观察时间、撤回状态 |
| 支持证据 | `graph_support` | 声明与经历的多对多连接；一项事实可以获得独立来源支持 |
| 提炼作业 | `graph_ingest_job` | 持久待处理材料、租约、重试及提交资格 |
| 检索投影 | `graph_claim_fts`、现有条目向量表 | 可重建的检索索引，不拥有第二份事实状态 |

`MemoryItemStore` 保留为旧 API 的类型适配器，实际读写 `graph_claim`。`GraphStore` 的实体、关系、属性和图遍历也使用这一组正本。`MemoryEdgeStore` 的旧时态关系 API 已改为图谱查询适配器，不再写独立的 `memory_edge` 事实。

实体名称不再生成持久身份 ID。普通写入在明确的作用域内定位；同名不同对象可由 `upsert_node.identity_key` 区分。有多个同名候选时，调用方必须使用实体 ID 消歧，禁止默默选第一项。

实体摘要和属性有自己的事实声明。撤回一轮对话时，这些元数据也会失效，避免关系已撤销而节点属性继续充当另一份记忆。

项目文档保留版本目录、标题、作者与来源会话；`graph_claim_id` 指向图谱正文。新版本取代旧版本的检索状态，但旧版本正文仍可读取。文档修改须经过项目知识的版本检查，普通记忆改写不能覆盖不可变的文档版本。普通知识/派生记忆重置保留项目文档正文，防止重置检索能力时破坏项目成果。

## 写入与撤销

1. 记录完整经历，再把待提炼材料写入持久作业；不在确认提炼成功前删除材料。
2. 长材料拆为有限长度的重叠窗口；原文全文独立保留。助手回复的结尾不再因原来的 600 字缓冲截断而失去提炼机会。
3. 后台领取作业时获取租约。模型失败后指数退避重试；进程重启后可领取过期租约。
4. 模型结果提交前，在写事务内同时校验来源撤回墓碑与作业租约。旧工作线程不能提交或确认已被重新领取的作业。
5. 相同声明重复出现时累积证据，不重复建立正文。图谱关系引用这一份声明。
6. 原始事件正文可能晚于关系建立，此时把来源占位引用补到完整原文，保持事实正文不变。

用户画像继续通过候选和审批。内置聊天提示词已取消静默写用户锚点的要求；图谱写入也禁止 Agent 修改用户锚点或用关系绕过审批。执行结论的即时入队不代表用户明确确认。

关系不再把不同宾语一概解释为矛盾。“喜欢 Java”和“喜欢 Python”可以共存。事实失效通过显式撤销、确认更新或既有取代流程表达。模型置信度不等于事实已被验证。

聊天撤回会失效相关声明、节点元数据、支持经历和待处理作业。多来源文档事实删除一个来源后，如果仍有独立有效来源，可以继续使用。私有会话提炼出的混合摘要仍采取保守撤回，避免保留已丢弃的结论。

## 读取与容量

读侧先应用 `MemoryPolicy`，再执行检索和图遍历。项目、Agent、会话、共享授权、状态与虚构身份约束共用同一来源。没有执行作用域的知识读取只开放公共知识及正式画像；人工图谱管理使用显式管理视图。

普通记忆不再先裁取最新 200 条：

- 全文索引在授权历史中检索，再限制候选数量；没有向量服务时也能找回关键词命中的旧事。
- 向量检索流式扫描授权范围内的有效版本，使用有限大小的候选堆；不会先按时间丢掉旧向量。
- 从命中事实的语义实体扩展相关声明，再排序并组织上下文。归属节点与声明节点不参与语义联想，避免同一项目的全部记忆因管理关系而混在一起。
- 原始事件检索同样按页扫描授权历史、保留有限的最佳结果。

长期保留和本轮注入分别控制。画像、指令和固定记忆优先，但同样受本轮条目总预算约束；它们仍保留在库中，可主动检索。工作集使用同一条目总预算，项目续接有独立额度，少量协议头不计入条目预算。工具轮次中的实时重验也执行预算限制。

聊天、心跳、子代理、圆桌继续使用现有记忆接入点。编码运行新增 `CodingMemory` 适配器，每轮按绑定项目或仓库隔离域重新建立记忆快照；撤销不会停留在初始提示词里。可审阅的编码结论按“待验收、尚未应用”采集，不直接成为项目已确认事实。

向量检索目前仍是 SQLite 上的精确流式扫描，不是近似向量索引。选择先复用现有 SQLite 与向量格式，避免引入另一套数据库、同步协议和运维依赖；后续应依据真实维度、数量与并发测量替换索引实现。

## 来源读取

- `recall_memory` 的 `item` 模式返回事实编号。
- `recall_memory(mode="evidence", id=...)` 返回完整声明和证据页；`offset` 控制证据分页，`text_offset`、`max_chars` 控制输出文本分页，按返回的 `next_offset` 继续读取。
- 图谱工具输出关系对应的事实编号，可以继续下钻同一证据入口。
- 人工检查 API：`GET /api/memory/graph/claims/{id}/evidence`。
- 迁移知识人工复核：`POST /api/memory/graph/claims/{id}/review`，提交 `expectedVersion`、`accept`、`reason`；限 `KNOWLEDGE × conflicted`，不能绕过用户画像审批。确认和拒绝都保留人工证据与审计。
- 容量与提炼状态：`GET /api/memory/graph/status`。

共享一个事实不等于共享它的全部原始对话。接收方可以看到声明和来源标识；不具备原会话/执行读取范围时，私有证据正文会被隐藏。公共文档和已授权项目文档的来源可随对应声明读取。

## 数据迁移与回滚

本次 schema 数据版本为 **5**。启动路径为：

1. 初始化脚本执行前检测旧 `memory_item`，文件数据库先用 `VACUUM INTO` 保存 `backups/before-graph-*.db`。
2. 在事务内把旧表重命名为 `graph_claim`；保留 ID，并由 SQLite 更新相关外键。若新旧正本表同时存在，停止启动，不猜测哪份正确。
3. 执行幂等结构脚本及缺列补丁，再进入已有的带备份数据迁移事务。
4. 尽可能从原消息和执行产物恢复经历全文，导入旧实体、关系、时态边和项目文档，构建检索投影。
5. 无来源的旧关系与旧实体元数据进入 `conflicted` 待复核，不自动成为确认画像或正式共享事实。旧表数据全部有新正本表示后清空旧图谱表，避免继续存在第二份可写知识。
6. 验证成功后推进版本；失败回滚数据与版本，保留备份。重复启动不会重复导入。

不要将旧版本程序直接连接迁移后的库。回滚程序版本时，应停止后端，使用迁移前备份恢复数据库，再启动匹配的旧程序。本轮开发验证使用内存或临时数据库，没有运行用户真实数据库的迁移。

## 验证范围

新增回归覆盖十万条干扰数据后的旧事实语义/关键词召回、图谱与记忆共用状态、作用域与虚构隔离、同名消歧、多来源支持、画像旁路拦截、共享时原文隔离、长材料结尾保留、租约与重启重试、节点元数据撤回、项目正文保留、启动备份和迁移回滚，以及编码记忆的每轮重验。

十万条用例使用二维桩向量和本地 SQLite，验证的是候选覆盖性、边界行为和有限内存处理方式，不代表真实供应商的提炼正确率、真实高维向量检索延迟或浏览器端到端验收。最终测试结果以本次交付报告为准。

最终后端完整回归：683 项，666 项通过、17 项跳过、零失败、零错误。使用现有 Mockito javaagent，并在允许本机回环服务及临时 Git 文件操作的环境完成。包含 15 项统一图谱回归、2 项启动/回滚迁移回归、1 项编码记忆回归，以及既有记忆、项目、图谱、终端和协作测试。真实模型质量、真实数据库迁移和浏览器交互未在本轮验收。

新增测试文件为 `UnifiedMemoryGraphTest.java`、`GraphMigrationTest.java`、`CodingMemoryTest.java`；既有测试的 SQL 表名及必要的真实 schema 初始化、编码终端测试夹具依赖也已同步。与本功能无关的用户工作区修改保持原状。

## 生产文件与依赖

新增生产文件：

- `backend/src/main/java/cc/ginpika/minis/ai/memory/graph/MemoryGraphStore.java`
- `backend/src/main/java/cc/ginpika/minis/ai/memory/store/MemoryIngestStore.java`
- `backend/src/main/java/cc/ginpika/minis/config/GraphDatabaseInitialization.java`
- `backend/src/main/java/cc/ginpika/minis/coding/integration/CodingMemory.java`
- `backend/src/main/java/cc/ginpika/minis/web/controller/MemoryGraphController.java`

修改的生产链路：

- `ai/memory`：`MemoryExtractor`、`MemoryIngest`、`MemoryPolicy`、`MemoryWriter`、`MemorySnapshotBuilder`、`MemoryLiveContext`、`MemoryEditService`、`MemoryMaintenanceService`。
- `ai/memory/store` 与 `recall`：`MemoryItemStore`、`MemoryItemEmbeddingStore`、`MemoryEventStore`、`MemoryEdgeStore`、`MemoryRetractionStore`、`MemoryItemRecall`、`MemoryRecall`。
- `ai/knowledge`：`GraphStore`、`ChunkStore`、`AnalyzeService`、`KnowledgeToolbox`、`ResultTexts`、`GraphExplorer`、`WebSnapshotStore`。
- `ai/core`：`ChatGuidanceBlock`、`RecallMemoryTool`、`UpsertNodeTool`、`UpsertEdgeTool`。
- 接入与目录：`ContextBuilder`、`CodingFacade`、`ProjectKnowledgeStore`、`GraphController`。
- 结构：`schema.sql`、`SchemaColumnPatches`、`SchemaIndexes`、`SchemaMigrations`。
- 其他正本名称和预算注释同步：`WorkingSetService`、`MemoryVocabulary`、`MemoryProps`、`MemoryCandidateStore`、`MemoryWorkStore`、`SettingsDtos`、`SettingsService`。

以上 Java 路径均位于 `backend/src/main/java/cc/ginpika/minis/`；包含工作区尚未跟踪的生产文件，提交时不能只收录测试。

没有新增 Maven 或 npm 依赖；复用 SQLite FTS5、现有 JDBC、Jackson 和向量格式。测试配置在命令行使用现有 Mockito javaagent，未修改用户的 Maven 依赖配置。
