-- minis M0 schema（幂等：全部 IF NOT EXISTS；增量用 _schema.version）
-- 权威：minis/M0-骨架设计.md §5
PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=5000;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS _schema (
  version INTEGER NOT NULL
);
INSERT INTO _schema(version) SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM _schema);

-- 用户（hostid SSO 同步的最小结构；单用户足够，为多用户预留）
CREATE TABLE IF NOT EXISTS app_user (
  id          TEXT PRIMARY KEY,             -- hostid user.id
  username    TEXT NOT NULL,
  nickname    TEXT,
  email       TEXT,
  role        TEXT,
  avatar_url  TEXT,
  prefs       TEXT,                          -- JSON
  created_at  INTEGER NOT NULL
);

-- AI 会话（消息正文本地落库，见 chat_message）
-- 注意：旧版表含 yuni_session_id 列（Yuni BFF 时代），启动时由 HubSessionMigrator 自动迁移重建；
-- 旧库缺下方的配置列（model_id / prompt_id / show_cot / context_trace / max_rounds / show_rounds / unread）时由同一迁移器按缺补列
CREATE TABLE IF NOT EXISTS hub_session (
  id                TEXT PRIMARY KEY,        -- hub 侧 uuid
  title             TEXT,
  mode              TEXT NOT NULL DEFAULT 'ai',
  model_id          TEXT,                    -- 本会话所选模型（llm_model.id 或内置 'env'）；NULL=设置页默认
  prompt_id         TEXT,                    -- 本会话所选「对话身份」版本（prompt_version.id）；NULL=提示词页绑定
  show_cot          INTEGER NOT NULL DEFAULT 1, -- 本会话是否显示思维链（1=显示）；「…」菜单开关
  context_trace     INTEGER NOT NULL DEFAULT 0, -- 本会话是否把历史思考与工具调用回灌上下文（1=加入）；「…」菜单开关
  max_rounds        INTEGER NOT NULL DEFAULT 30, -- 本会话单次提问的工具调用轮数上限（超限后撤掉 tools 强制出最终回答）；「…」菜单配置
  show_rounds       INTEGER NOT NULL DEFAULT 0, -- 本会话思维链分段是否显示轮次计数（1=显示，缺省不显示）；「…」菜单开关
  unread            INTEGER NOT NULL DEFAULT 0, -- 潜意识子代理投递过新消息且用户未查看（1=未读）；打开会话即清零
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER
);

-- AI 会话消息（role: user|assistant|system）；reasoning 为 assistant 的思维链纯文本（CoT，非推理模型为 NULL），
-- trace 为其结构化分段（JSON 数组：[{type:"reasoning",text}|{type:"tool",name,args}]，供前端分阶段下拉展示）；
-- tool_rounds 为本轮工具调用的原生消息（JSON 数组：[assistant(含 tool_calls/reasoning_content), tool(含
-- tool_call_id), ...]，assistant 与 tool 按 tool_call_id 成对）；reasoning/trace 仅供前端回放展示，
-- tool_rounds 另供会话开启 context_trace 时按原生协议原样回放（见 ChatService#replayAssistant）；
-- kind 为消息形态：NULL=普通对话，'subagent'=子代理结论气泡（前端只渲染标题+摘要，点开看全文）；
-- meta 为 kind 对应的元信息（JSON：{title,summary,status,execId}，见 ChatDtos.SubagentBrief）；
-- 旧库由 ChatMessageMigrator 补列
CREATE TABLE IF NOT EXISTS chat_message (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id    TEXT NOT NULL REFERENCES hub_session(id),
  role          TEXT NOT NULL,
  content       TEXT NOT NULL,
  reasoning     TEXT,
  trace         TEXT,
  tool_rounds   TEXT,
  kind          TEXT,
  meta          TEXT,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id, id);

-- 事件回放流（SSE 游标 + 抽取源；M1 启用，M0 建表）
CREATE TABLE IF NOT EXISTS session_events (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,   -- SSE Last-Event-ID
  hub_session_id TEXT NOT NULL,
  seq           INTEGER NOT NULL,
  kind          TEXT NOT NULL,
  event_id      TEXT,                                -- 幂等键（live 与补扫同键去重）
  payload       TEXT NOT NULL,                       -- JSON（camelCase）
  ts            INTEGER NOT NULL,
  UNIQUE (hub_session_id, event_id)
);
CREATE INDEX IF NOT EXISTS idx_ses_scan ON session_events(hub_session_id, id);

-- 文档（统一模型：文档分析工作流的父级 + 已发布文章的索引）。
-- url 非空的行是已发布文章（markdown 原文存 data/article/{url}.md）；内部文档 url 为 NULL。
-- 旧库的 articles 索引表由 ChunkDocMigrator 启动时合并进来后移除。
-- 注意：idx_documents_url 唯一索引不由此处创建——旧库 documents 缺 url 列时
-- CREATE INDEX 会先于迁移器补列而失败，故统一由迁移器建（同 idx_chunks_doc）。
CREATE TABLE IF NOT EXISTS documents (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  title       TEXT NOT NULL,
  source      TEXT,                   -- 来源：blog|doc|…
  url         TEXT,                   -- 文章 slug（/article/{url} 访问）；内部文档为 NULL
  summary     TEXT,                   -- 摘要（文章导入时自动生成，文章列表展示用）
  contextualizing TEXT,               -- 全文概述（文档分析页「摘要」按钮：LLM 总结全文，100~200 字）
  doc_type    TEXT,                   -- 文档类型（同上一次 LLM 调用判定：TECHNICAL|NARRATIVE|CONVERSATIONAL）
  created_at  INTEGER NOT NULL
);

-- 原文块（事实源）；doc_id 指向所属文档（chunk→doc），status 为分析生命周期
CREATE TABLE IF NOT EXISTS chunks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,     -- FTS 同步键
  uuid        TEXT NOT NULL UNIQUE,
  source      TEXT NOT NULL,          -- blog|note|chat|manual|doc
  source_ref  TEXT,                   -- URL / 文件名 / yuni session:msgId
  title       TEXT,
  content     TEXT NOT NULL,
  tags        TEXT,                   -- JSON array（M0 可空）
  meta        TEXT,                   -- JSON
  header      TEXT,                   -- 最终提交预案 · Chunk Header 留档（生成时落库，二次进入自动回显）
  questions   TEXT,                   -- 最终提交预案 · 假设性问题留档（生成时落库，二次进入自动回显）
  doc_id      INTEGER REFERENCES documents(id),  -- 所属文档（双向父子关系的 chunk→doc 侧）
  status      TEXT NOT NULL DEFAULT 'unanalyzed', -- unanalyzed|enabled|disabled
  created_at  INTEGER NOT NULL
);
-- 注意：idx_chunks_doc 索引不由此处创建——旧库的 chunks 表缺 doc_id 列时，
-- CREATE INDEX 会先于 ChunkDocMigrator 补列而失败，故统一由迁移器建（含新库）。

-- FTS5 #1：英文/代码/标签/标题（unicode61，contentless 只存索引取 rowid）
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts_meta USING fts5(
  title, tags, content, content='', contentless_delete=1, tokenize='unicode61'
);
-- FTS5 #2：中文正文（trigram，≥3 字子串）
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts_cn USING fts5(
  content, tokenize='trigram'
);

-- 向量（M0：BLOB + Java 暴力 top-k；vec1 仅 spike 评估，不承诺启用）
CREATE TABLE IF NOT EXISTS chunk_embedding (
  chunk_id    INTEGER PRIMARY KEY REFERENCES chunks(id),
  model       TEXT NOT NULL,          -- 固定供应商+模型（见配置）
  dim         INTEGER NOT NULL,
  vec         BLOB NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- 图：实体/节点
CREATE TABLE IF NOT EXISTS entities (
  id          TEXT PRIMARY KEY,
  type        TEXT NOT NULL,          -- 主类型（首写定不覆盖）：PERSON|PROJECT|ARTICLE|CONCEPT|HOBBY|...
  types       TEXT,                   -- 全量类型 JSON array（含主类型，去重合并，兼容多类型）
  name        TEXT NOT NULL UNIQUE,
  aliases     TEXT,                   -- JSON array（对齐用）
  summary     TEXT,
  meta        TEXT,
  created_at  INTEGER NOT NULL,        -- 首建时间（upsert 冲突不覆盖）
  updated_at  INTEGER                  -- 实体行最近一次变动时间（含触及它的关系写入）
);
CREATE TABLE IF NOT EXISTS relations (
  id          TEXT PRIMARY KEY,
  subject_id  TEXT NOT NULL REFERENCES entities(id),
  predicate   TEXT NOT NULL,
  object_id   TEXT NOT NULL REFERENCES entities(id),
  props       TEXT,                   -- JSON（来源 chunk_id、时间…）
  source_chunk INTEGER REFERENCES chunks(id)
);
CREATE INDEX IF NOT EXISTS idx_rel_sub ON relations(subject_id);
CREATE INDEX IF NOT EXISTS idx_rel_obj ON relations(object_id);

-- 关联 chunk ↔ 实体
CREATE TABLE IF NOT EXISTS chunk_entity (
  chunk_id    INTEGER NOT NULL REFERENCES chunks(id),
  entity_id   TEXT NOT NULL REFERENCES entities(id),
  PRIMARY KEY (chunk_id, entity_id)
);

-- chunk 知识提取记录：commitChunk 提交时落库，存储通过该 chunk 更新过的实体/关系三元组清单
--（重复提交覆盖为最近一次）；用户再次进入步骤 2「知识提取」时回显。
CREATE TABLE IF NOT EXISTS chunk_extraction (
  chunk_id    INTEGER PRIMARY KEY REFERENCES chunks(id),
  triples     TEXT NOT NULL,               -- JSON 数组（Triple：subject/predicate/object + 两端类型与 KV 属性）
  created_at  INTEGER NOT NULL
);

-- 实体向量（文档导入时对节点 name+summary 向量化；BLOB + Java 暴力余弦，同 chunk_embedding 语义）
CREATE TABLE IF NOT EXISTS entity_embedding (
  id          TEXT PRIMARY KEY REFERENCES entities(id),
  model       TEXT NOT NULL,
  dim         INTEGER NOT NULL,
  vec         BLOB NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- 运行时配置（设置页读写；.env 传入的 LLM/EMBEDDING 作为内置项 "env"，不可编辑删除）
-- ---------------------------------------------------------------------------

-- 用户配置的大模型（OpenAI Compatible /chat/completions 端点）；内置 env 项不入库
-- 注意：旧库缺 context_window 列时由 LlmModelMigrator 按缺补列
CREATE TABLE IF NOT EXISTS llm_model (
  id              TEXT PRIMARY KEY,          -- uuid
  name            TEXT NOT NULL,             -- 展示名
  base_url        TEXT NOT NULL,             -- 带版本前缀，如 https://api.openai.com/v1
  api_key         TEXT NOT NULL,
  model           TEXT NOT NULL,             -- chat/completions 的 model
  context_window  INTEGER,                   -- 上下文窗口（token 数）；NULL=按默认值估算
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

-- 提示词版本（用户自己编辑的覆盖版本）：每个「提示词位」（prompt_key）下可挂多份，
-- 由 prompt_binding 选定其一覆盖代码里写死的内置提示词；内置提示词本身不入库
--（代码内置见 ai/core/prompt/PromptLibrary，对话身份的内置来自 .env 的 LLM_SYSTEM_PROMPT）。
-- 旧库的 system_prompt（用户人格列表）由 PromptMigrator 迁入 prompt_key='identity'。
CREATE TABLE IF NOT EXISTS prompt_version (
  id          TEXT PRIMARY KEY,              -- uuid
  prompt_key  TEXT NOT NULL,                 -- 提示词位（PromptLibrary 场景注册表的 key）
  name        TEXT NOT NULL,                 -- 版本名（展示用）
  content     TEXT NOT NULL,                 -- 提示词正文
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_prompt_version_key ON prompt_version(prompt_key, created_at);

-- 提示词位 → 生效版本（每个提示词位至多一行）：version_id 为空 = 用内置（代码写死 / .env）；
-- 无行等价于空。旧库 app_setting.default_prompt_id（默认人格）由 PromptMigrator 迁入 identity。
CREATE TABLE IF NOT EXISTS prompt_binding (
  prompt_key  TEXT PRIMARY KEY,
  version_id  TEXT,
  updated_at  INTEGER NOT NULL
);

-- 上下文内提示词位的拼装顺序（用户在提示词页拖拽调整）：sort 越小越靠前；
-- 无行 = 按 PromptLibrary 注册表顺序。只对「组合型上下文」（如对话 = 身份 + 增强）有意义。
CREATE TABLE IF NOT EXISTS prompt_order (
  context_key TEXT NOT NULL,
  slot_key    TEXT NOT NULL,
  sort        INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (context_key, slot_key)
);

-- 默认模型选择（单行，id=1）：空值回退内置 env 项
CREATE TABLE IF NOT EXISTS app_setting (
  id                INTEGER PRIMARY KEY CHECK (id = 1),
  default_model_id  TEXT,
  updated_at        INTEGER NOT NULL
);

-- Embedding 独立配置（单行，id=1）：mode=llm 复用某个 llm_model 的 base-url/api-key（llm_model_id 可为内置 "env"）；
-- mode=custom 用自己的 base_url/api_key；model 均为 embedding 模型名。无行时回退 .env（LLM_BASE_URL + EMBEDDING_MODEL）。
CREATE TABLE IF NOT EXISTS embedding_config (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  mode          TEXT NOT NULL DEFAULT 'llm', -- llm | custom
  llm_model_id  TEXT,
  base_url      TEXT,
  api_key       TEXT,
  model         TEXT,
  updated_at    INTEGER NOT NULL
);

-- Rerank 精排配置（单行，id=1）：mode=llm 复用某个 llm_model 的 base-url/api-key（llm_model_id 可为内置 "env"）；
-- mode=custom 用自己的 base_url/api_key；mode=off 关闭精排，检索保持 RRF 原序。
-- 无行时回退 .env（LLM_BASE_URL + RERANK_MODEL，RERANK_MODEL 为空则不启用）。
CREATE TABLE IF NOT EXISTS rerank_config (
  id            INTEGER PRIMARY KEY CHECK (id = 1),
  mode          TEXT NOT NULL DEFAULT 'llm', -- llm | custom | off
  llm_model_id  TEXT,
  base_url      TEXT,
  api_key       TEXT,
  model         TEXT,
  updated_at    INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- 潜意识（subconscious）：两层概念，共用「一次执行」的留痕。
--
--   任务计划（sub_plan）——常驻编制：一份带正文/工具/渠道的岗位定义，带节拍与上次派出时间，
--                          心跳主会话看着名册按需把它派出去（delegate_plan）。
--   心跳主会话（sub_heartbeat）——总线本身的那份上下文：每拍先由它出场决定派谁、怎么派。
--   子代理（sub_exec.kind=agent）——匿名 worker：主对话或心跳当场交代即跑，不建档案、跑完即逝；
--                          独立上下文、窄工具集（默认只读检索），只把结论回流给派它的人。
--
-- sub_run / sub_exec / sub_exec_edit 是三者共用的执行留痕：谁跑一次就落一行 exec，kind 标明是谁。
-- 三者的共同点是「一次独立上下文的 LLM 执行」——引擎相同（AgentRunner），概念不同。
-- LLM 调用本身无状态（每次都重新组装 messages），组装结果原样落在 sub_exec.context 供回溯。
-- ---------------------------------------------------------------------------

-- 心跳主会话（总线的上下文）：心跳本身有一份自己的上下文，它是唯一入口——每拍先由它出场，
-- 看着任务计划名册决定这一拍派谁、怎么派（delegate_plan）；缺人手时它自己也能攒计划。
-- 单行表（id 固定 'heartbeat'）：正文（含 {{插槽}}）+ 插槽绑定 + 模型，与任务计划同一套玩法。
CREATE TABLE IF NOT EXISTS sub_heartbeat (
  id          TEXT PRIMARY KEY,               -- 固定 'heartbeat'（单行）
  content     TEXT,                           -- 主会话正文，含 {{插槽}}
  injectors   TEXT,                           -- JSON：[{slot, injector, args}] 插槽 → 注入器
  model_id    TEXT,                           -- 主会话模型（llm_model.id / 'env'）；NULL=设置页默认
  updated_at  INTEGER NOT NULL
);

-- 任务计划（一个 plan 的完整定义）：正文（内容）内以 {{占位名}} 标记插槽，
-- injectors 声明每个插槽由哪个注入器（见 SubconsciousInjectors 注册表）从存储层现取内容填充，
-- 运行时组装成一条完整的 LLM 上下文（system / user）；正文不改不猜。
--
-- 计划自带本事，不另设「模板」层：提示词、角色、工具权限就是它自己的，直接内联在这一行上。
-- 计划由大模型自己创建（create_plan）、改写（update_plan）与删除（delete_plan），
-- 不由人在页面上新建：它不是配置项，而是大模型手上常驻、按需唤起的编制。
-- 计划不自己跟时钟赛跑：interval_ms 只是给主会话的参考（它据此判断这一拍该不该派），
-- last_beat_at 则是上次真被派出去的时间。
-- channels 只有一个维度：silent=只留痕（写 exec 供回溯）；thread=额外可用 open_thread/send_message
-- 工具自建会话并投递消息（见 SubconsciousService.CHANNELS）。
-- 计划每被派出一次，就是起一个匿名实例跑一遍——所以执行留痕与子代理同表（sub_exec）。
-- 旧库的 sub_task / sub_agent 由 SubconsciousMigrator 启动时改名为本表。
CREATE TABLE IF NOT EXISTS sub_plan (
  id            TEXT PRIMARY KEY,             -- uuid
  name          TEXT NOT NULL,                -- 展示名（主会话派活、改写、删除都靠它指认）
  role          TEXT NOT NULL DEFAULT 'system', -- system | user：正文组装成哪种消息
  content       TEXT NOT NULL,                -- 提示词正文，含 {{插槽}} 占位
  tools         TEXT,                         -- JSON：允许调用的工具名数组；空/NULL=不挂工具
  injectors     TEXT,                         -- JSON：[{slot, injector, args}] 插槽 → 注入器
  channels      TEXT,                         -- JSON：交付渠道数组，见 SubconsciousService.CHANNELS
  interval_ms   INTEGER,                      -- 节拍倍数：NULL=每拍都可以考虑派它
  input         TEXT,                         -- 附加输入（seed），同样支持 {{插槽}}
  model_id      TEXT,                         -- 该计划指定的模型（llm_model.id / 'env'）；NULL=设置页默认
  sort          INTEGER NOT NULL DEFAULT 0,
  last_beat_at  INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

-- 一批执行：一次心跳（trigger=beat）、页面点名（manual）、或一次对话委托里的若干次执行（chat）
CREATE TABLE IF NOT EXISTS sub_run (
  id          TEXT PRIMARY KEY,               -- uuid
  beat        INTEGER NOT NULL,               -- 全局心跳序号（自增节拍）
  trigger     TEXT NOT NULL,                  -- beat | manual | chat
  status      TEXT NOT NULL,                  -- running | done | failed
  agent_count INTEGER NOT NULL DEFAULT 0,     -- 本批执行的次数（心跳主会话 / 计划 / 子代理合计）
  note        TEXT,
  started_at  INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sub_run_started ON sub_run(started_at);

-- 一次执行（独立上下文）：组装出的 messages、结论、思维链、过程分段、工具往返原生消息全部留痕，
-- 供前端逐步回放与主模型 read_subagent_run 按需查看。
-- kind 决定 plan_id 与 title 的含义：
--   heartbeat = 心跳主会话登场这一拍（plan_id 固定 'heartbeat'）
--   plan      = 某个任务计划被派出去跑了一次（plan_id = sub_plan.id）
--   agent     = 一个匿名子代理被当场交代跑了一次（plan_id 为 NULL，title 是它的短名）
CREATE TABLE IF NOT EXISTS sub_exec (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id      TEXT NOT NULL REFERENCES sub_run(id),
  kind        TEXT NOT NULL,                  -- heartbeat | plan | agent
  plan_id     TEXT,                           -- 来自哪个任务计划（kind=plan）；其余为 NULL
  title       TEXT,                           -- 展示名：计划名 / 子代理短名 / 心跳主会话
  status      TEXT NOT NULL,                  -- running | done | failed
  context     TEXT,                           -- 组装出的完整 LLM 上下文（messages JSON）
  output      TEXT,                           -- 结论文本（回流给调用方 / 交付给各渠道的正文）
  reasoning   TEXT,                           -- 思维链纯文本（与 chat_message.reasoning 同构）
  trace       TEXT,                           -- 过程分段 JSON：[{type:reasoning|tool,text,name,args}]
  tool_rounds TEXT,                           -- 工具往返原生消息 JSON（同 chat_message.tool_rounds）
  error       TEXT,
  started_at  INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_sub_exec_run ON sub_exec(run_id, id);
-- idx_sub_exec_plan(plan_id, id) 与 idx_sub_exec_kind(kind, id) 不在本文件建：旧库此时还没有
-- plan_id / kind（仍是 task_id / agent_id），由 SubconsciousMigrator 改完列名后再补建，
-- 避免旧库启动时 schema 初始化直接失败。

-- 对知识库的写入清单（写库工具每次调用的审计留痕，挂在某一次执行上）
CREATE TABLE IF NOT EXISTS sub_exec_edit (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  exec_id     INTEGER NOT NULL REFERENCES sub_exec(id),
  tool        TEXT NOT NULL,                  -- upsert_node / upsert_edge / create_document / edit_* / delete_*
  args        TEXT,                           -- 调用参数（JSON 字符串）
  summary     TEXT,                           -- 工具返回的可读结果
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sub_exec_edit ON sub_exec_edit(exec_id, id);

-- ---------------------------------------------------------------------------
-- 多智能体（圆桌）：一群具名成员在同一个多智能体会话里轮流发言
-- 与其他体系（hub_session / chat_message / sub_*）完全隔离：自己的会话、自己的消息、
-- 自己的话题与事件，互不影响。见 agent/multiagent 包（MaChatService / MaTopicService）。
-- ---------------------------------------------------------------------------

-- 多智能体成员名册：一个具名、常驻的发言者（不是匿名 worker，也不按节拍派出）。
-- 每位成员自己那套「对话」提示词存在 ma_peer_prompt_*（见下），与首页对话的全局提示词隔离。
-- prompt_id 是新表之前的遗留字段（曾指向全局 identity 版本），已不参与解析，仅保留兼容旧库。
-- 由人在「多智能体 · 成员」页配置，不由大模型自建。
-- 能力面逐成员配：tools 是额外可调的工具，terminals 是它能碰的工作空间（term_target.id）——
-- 两者都是「在默认只读检索之上追加」，空/未配即没有这层额外能力。
CREATE TABLE IF NOT EXISTS ma_peer (
  id          TEXT PRIMARY KEY,               -- uuid
  name        TEXT NOT NULL,                  -- 展示名；也是发言时被 @ 的名字
  prompt_id   TEXT,                           -- identity 提示词版本 id；NULL=跟随提示词页绑定
  tools       TEXT,                           -- JSON：额外允许调用的工具名数组；NULL=默认只读检索
  terminals   TEXT,                           -- JSON：绑定给该成员的工作空间（term_target.id 数组）；
                                              --    非空即授予 run_command / list_terminals，且只在绑定的目标上可用
  model_id    TEXT,                           -- 该成员的模型（llm_model.id / 'env'）；NULL=设置页默认
  sort        INTEGER NOT NULL DEFAULT 0,     -- 发言顺序（顺序轮转的依据）
  note        TEXT,                           -- 给人看的备注（不注入模型）
  avatar      TEXT,                           -- 头像：data:image/...;base64,... 的小图（前端压到 256px 后存，不落磁盘）
  anchor_id   TEXT,                           -- 图谱人格锚点：entities.id（types 含 ANCHOR 的实体）；NULL=尚未建立
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- 成员的提示词版本：每个成员一套「对话」提示词（对话身份 / 对话增强 / 终端 / 子代理 / 子代理回报摘要），
-- 与全局 prompt_version 完全隔离——首页对话用全局那一份，成员各用各的，改这边不影响那边。
CREATE TABLE IF NOT EXISTS ma_peer_prompt_version (
  id          TEXT PRIMARY KEY,               -- uuid
  peer_id     TEXT NOT NULL,                  -- ma_peer.id
  prompt_key  TEXT NOT NULL,                  -- 提示词位（PromptLibrary 注册表的 key）
  name        TEXT NOT NULL,                  -- 版本名（展示用）
  content     TEXT NOT NULL,                  -- 提示词正文
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ma_peer_prompt_version
  ON ma_peer_prompt_version(peer_id, prompt_key, created_at);

-- 成员的提示词位 → 生效版本（每个成员每个位至多一行）：version_id 为空 = 回退全局（提示词页绑定 → 内置）
CREATE TABLE IF NOT EXISTS ma_peer_prompt_binding (
  peer_id     TEXT NOT NULL,                  -- ma_peer.id
  prompt_key  TEXT NOT NULL,
  version_id  TEXT,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (peer_id, prompt_key)
);

-- 成员上下文内提示词位的拼装顺序（成员页拖拽调整；无行 = 按 PromptLibrary 注册表顺序）
CREATE TABLE IF NOT EXISTS ma_peer_prompt_order (
  peer_id     TEXT NOT NULL,
  context_key TEXT NOT NULL,
  slot_key    TEXT NOT NULL,
  sort        INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (peer_id, context_key, slot_key)
);

-- 多智能体会话：与 hub_session 隔离的一套会话（多智能体对话页专用），不参与首页「最近会话」。
CREATE TABLE IF NOT EXISTS ma_session (
  id          TEXT PRIMARY KEY,               -- uuid
  title       TEXT,                           -- 首条发言截断而来
  model_id    TEXT,                           -- 兜底模型（成员未指定时用）；NULL=设置页默认
  member_ids  TEXT,                           -- 这张圆桌的成员：ma_peer.id 的 JSON 数组；NULL=全体名册
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER
);

-- 多智能体消息：role=user（人说的话）| peer（某个成员说的话）；peer_id / peer_name 冗余存一份，
-- 成员被删后历史仍可读。reasoning / trace / tool_rounds 与 chat_message 同构（思维链与工具往返留痕）。
-- topic_id 指向该消息所属的这次话题（话题关闭后新开一个，同会话由此串起多轮）。
-- context 是这位成员这次发言**当时真正发出去的请求体**（messages + tools 的 JSON），供页面上
-- 点开一条发言回看「它当时看到的到底是什么」——这是调试多智能体最要紧的一件事（谁在座、状态如何、
-- 历史怎么拼出来的，只有这一份能回答）。旧库由 MultiAgentMigrator 补列。
CREATE TABLE IF NOT EXISTS ma_message (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL REFERENCES ma_session(id),
  topic_id    TEXT,                           -- 所属话题（ma_topic.id）
  role        TEXT NOT NULL,                  -- user | peer | event（event=状态机的一条动作）
  peer_id     TEXT,                           -- role=peer/event 时的成员
  peer_name   TEXT,                           -- role=peer/event 时的展示名（冗余，抗成员删除）
  content     TEXT NOT NULL,
  reasoning   TEXT,
  trace       TEXT,
  tool_rounds TEXT,
  context     TEXT,                           -- 该次发言组装出的请求体（messages + tools）
  event_seq   INTEGER,                        -- role=event 时：对应的话题事件序号（补写水位线）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ma_msg_session ON ma_message(session_id, id);

-- 一次话题（对话 loop 的边界）：由某人（用户或成员）的一条发言开启，全体成员都表态结束后关闭。
-- status：open=进行中；closing=已进入「向沉默者征询」阶段；closed=已收束。
-- floor_peer：当前持有发言权的成员（NULL=无人正在发言）——抢答即抢夺这个位。
-- resolve_round：已经重开过几次（沉默者发言 → 全员重新决策即一次重开），上限见 MultiAgentProps。
CREATE TABLE IF NOT EXISTS ma_topic (
  id            TEXT PRIMARY KEY,             -- uuid
  session_id    TEXT NOT NULL REFERENCES ma_session(id),
  origin        TEXT NOT NULL,                -- user | peer：话题由谁的发言开启
  origin_peer   TEXT,                         -- origin=peer 时的发起成员（ma_peer.id）
  status        TEXT NOT NULL,                -- open | closing | closed
  floor_peer    TEXT,                         -- 当前持有发言权的成员；NULL=无人发言
  resolve_round INTEGER NOT NULL DEFAULT 0,   -- 已重开次数
  created_at    INTEGER NOT NULL,
  closed_at     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_ma_topic_session ON ma_topic(session_id, id);

-- 隐式状态机的事件正本：每次发言、每次状态跃迁、每次征询与打断，都是一条只追加的事件。
-- 成员的当前状态不单独存列，一律由本表按 seq 折叠得出（见 TopicStateMachine）：
-- 每个 actor 取它最后一条带 to_state 的事件，即为它此刻的状态。
-- kind：topic_open（话题开始）/ addressed（被点名）/ floor_granted（授予发言权）/ speech（发言）/
--       floor_released（说完让出）/ interrupt（被用户或同伴打断）/ silent（转入沉默）/
--       propose_end（提议结束）/ prompt（被要求立刻表态）/ reopen（全员重新决策）/ close（话题关闭）。
CREATE TABLE IF NOT EXISTS ma_topic_event (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  topic_id    TEXT NOT NULL REFERENCES ma_topic(id),
  seq         INTEGER NOT NULL,               -- 话题内序号（1 起，折叠状态的排序依据）
  actor       TEXT NOT NULL,                  -- ma_peer.id 或 'user'
  kind        TEXT NOT NULL,                  -- 见上
  from_state  TEXT,                           -- 跃迁前状态（无跃迁为 NULL）
  to_state    TEXT,                           -- 跃迁后状态（无跃迁为 NULL）
  peer_id     TEXT,                           -- 目标成员（addressed / floor_granted / prompt / interrupt 时）
  content     TEXT,                           -- 发言正文（kind=speech）
  message_id  INTEGER,                        -- 对应的 ma_message.id（kind=speech）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ma_topic_event ON ma_topic_event(topic_id, seq);

-- 旧「会话（thread）」体系已退役：子代理的 open_thread / send_message 改为直接往
-- 首页 chat 的 hub_session / chat_message 投递（同名复用、无则新建，见 SubconsciousService）。
-- 旧库中的 sub_thread / sub_thread_message 两表由 SubconsciousMigrator 启动时 DROP。

-- 记忆事件流（AI Memory 的时序轴正本，见 ai/memory 包）：agent 侧「说过/做过」由发生点
-- 顺手 push 进来（ChatService 落 chat_message 时、AgentRunner 落结论时），事件只追加不改写；
-- 情节正本仍在 chat_message / sub_exec，这里存的是投影（截断后的正文 + 指回正本的 ref）。
-- event_key 幂等：同 key 重复 push 静默跳过（INSERT OR IGNORE），正本行 id 即天然键。
-- M0 范围：写入 + 时间窗召回（memory 注入器）；M1 挂上向量轴（memory_event_embedding）；
-- 图轴与固化管道见后续里程碑。
CREATE TABLE IF NOT EXISTS memory_event (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  event_key   TEXT NOT NULL UNIQUE,            -- 幂等键（ref_type:ref_id）
  kind        TEXT NOT NULL,                   -- chat | exec
  actor       TEXT NOT NULL,                   -- user | assistant | heartbeat | plan | agent
  title       TEXT,                            -- 展示名（会话名 / 计划名 / 子代理短名）
  payload     TEXT,                            -- 正文投影（原话 / 结论，写入侧已截断）
  ref_type    TEXT NOT NULL,                  -- chat_message | sub_exec（正本表名）
  ref_id      INTEGER NOT NULL,               -- 正本行 id（回溯与后续向量/图增强的挂点）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_event_time ON memory_event(created_at, id);

-- 记忆事件向量（AI Memory 的向量轴，M1）：memory_event 的 embedding 投影，列结构对齐
-- chunk_embedding（BLOB = little-endian float32，同模型余弦）。用法上的差别：记忆侧的余弦只在
-- 时间窗收敛后的候选内打分，不做全表暴力扫（见 ai/memory/recall/MemoryRecall）。
-- 写入两路：push 后异步补齐（embedding 未配置/失败跳过，不重试不阻塞主流程）+
-- backfill_memory_vectors 工具按需扫描补齐（供记忆维护的任务计划调用）。
-- event_id 为主键：换 embedding 模型后重算会整行覆盖——重建投影，事件本身仍是只追加不改写。
CREATE TABLE IF NOT EXISTS memory_event_embedding (
  event_id    INTEGER PRIMARY KEY REFERENCES memory_event(id),
  model       TEXT NOT NULL,                  -- embedding 模型名（同 chunk_embedding 的语义）
  dim         INTEGER NOT NULL,
  vec         BLOB NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- RAG 召回留痕（潜意识页「RAG 召回」子页的数据源）：每次「三路召回」工具调用记一行，
-- 让用户能回看「模型当初问了什么、拿到了什么原始数据」。三路指：
--   vector —— search_vector_chunks（关键词 + 向量 + 图谱 RRF 融合的 chunk 命中，含图谱结构证据）
--   graph  —— search_graph_entities / query_entity_relations / query_entity_path / explore_subgraph
--   memory —— recall_memory（记忆事件流的时序轴召回）
-- 与 memory_event 的区别：那是「说过/做过」的正本投影，这是「模型检索动作」的审计；
-- 写入点在 KnowledgeToolbox 的工具分发处（所有工具面共用的唯一默认分发点），失败隔离、
-- 只追加不改写。source 标明这次召回发生在哪条流（chat / multiagent / heartbeat / plan / agent）。
CREATE TABLE IF NOT EXISTS rag_recall (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  channel      TEXT NOT NULL,             -- vector | graph | memory（三路之一）
  tool         TEXT NOT NULL,             -- 工具名（search_vector_chunks / recall_memory / …）
  source       TEXT NOT NULL,             -- 来自哪条流：chat | multiagent | heartbeat | plan | agent
  query        TEXT,                      -- 调用参数原文（JSON 字符串）
  result       TEXT,                      -- 回给模型的 tool 消息原文（大模型实际拿到的原始数据）
  result_chars INTEGER NOT NULL DEFAULT 0,-- result 的原始字符数（超限截断前，供页面标注）
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rag_recall_time ON rag_recall(created_at);
CREATE INDEX IF NOT EXISTS idx_rag_recall_channel ON rag_recall(channel, created_at);

-- 一次 LLM 调用的 token 用量（统计页数据源）：每次 chat/embedding/rerank 调用结束后一行，
-- 真实值来自供应商回传的 usage（estimated=0），未回传时为本地估算兜底（estimated=1）。
-- source 取值见 TokenUsageService 的来源常量。
CREATE TABLE IF NOT EXISTS token_usage (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  source            TEXT NOT NULL,              -- chat | analyze | commit | doc-summary | chunk-questions | chunk-summary | chunker | subconscious | embedding | rerank | model-test
  model             TEXT NOT NULL DEFAULT '',   -- 模型名（未配置时为空串）
  prompt_tokens     INTEGER NOT NULL DEFAULT 0, -- 输入 token
  completion_tokens INTEGER NOT NULL DEFAULT 0, -- 输出 token
  total_tokens      INTEGER NOT NULL DEFAULT 0,
  estimated         INTEGER NOT NULL DEFAULT 0, -- 1=本地估算（供应商未回传 usage）
  created_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_token_usage_created ON token_usage(created_at);
CREATE INDEX IF NOT EXISTS idx_token_usage_source ON token_usage(source);

-- ---------------------------------------------------------------------------
-- 命令行终端（ai/terminal 包）：让 Agent 直接控制所在环境的能力面。
--
--   一个 term_target = 一台「可执行环境」/ 一个工作空间：本机 docker 的一个容器（Engine API + exec），
--   或一台远程服务器（SSH）。由人在工作空间页维护，模型只读（list_terminals）并按 name 选用；
--   工作空间页（agent/workspace）在此之上再提供目录浏览与文件下载。
--
--   两套实现共用一个执行契约（ExecTarget）：命令进去、退出码与两路输出出来，
--   于是模型侧只有一个工具（run_command），换环境只是换 target。
--
--   没有全局总闸：enabled 是逐目标开关——开哪台模型才能用哪台（默认关）。
--   凭证（SSH 密码 / 私钥）就存在这一行上：单用户私域部署，与 llm_model.api_key 同一个取舍。
-- ---------------------------------------------------------------------------

-- 终端目标（一台可执行环境的完整定义）
CREATE TABLE IF NOT EXISTS term_target (
  id              TEXT PRIMARY KEY,             -- uuid
  name            TEXT NOT NULL UNIQUE,         -- 目标名（模型按名指认，故唯一）
  kind            TEXT NOT NULL,                -- docker | ssh
  enabled         INTEGER NOT NULL DEFAULT 0,   -- 逐目标开关（1=模型可见可用）
  workdir         TEXT,                         -- 默认工作目录（每条命令执行前 cd 进去）
  timeout_ms      INTEGER,                      -- 单条命令超时；NULL=用全局默认
  max_output      INTEGER,                      -- 回给模型的输出上限（字符）；NULL=用全局默认
  allow_patterns  TEXT,                         -- JSON：命令白名单正则数组；非空时命令须命中其一
  deny_patterns   TEXT,                         -- JSON：命令黑名单正则数组；命中即拒（先于白名单判定）
  -- docker：container 与 image 二选一。给了 container 就直接 exec 进去（容器由外部管理）；
  -- 只给 image 时惰性起一个常驻会话容器（minis-term-<name>）并复用，于是文件与环境改动跨调用保留。
  docker_host     TEXT,                         -- 如 unix:///var/run/docker.sock、tcp://localhost:2375；NULL=环境默认
  image           TEXT,
  container       TEXT,
  -- ssh
  ssh_host        TEXT,
  ssh_port        INTEGER,
  ssh_user        TEXT,
  ssh_auth        TEXT,                         -- password | key
  ssh_password    TEXT,
  ssh_private_key TEXT,                         -- 私钥内容（PEM）或私钥文件路径
  ssh_passphrase  TEXT,                         -- 私钥口令（私钥加密时）
  host_key_check  TEXT,                         -- known_hosts（默认，首次信任：未知主机记入 ~/.ssh/known_hosts 后放行，指纹变化则拒）| accept-all（完全不校验）
  note            TEXT,                         -- 给模型看的一句话说明（什么时候该用它）
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

-- 命令执行审计（每次 run_command 一行）：既是页面的回溯凭证，也是模型自查的依据。
-- stdout/stderr 已按上限截断后才落库，不另存全文（与 chat_message 的留痕粒度一致）。
CREATE TABLE IF NOT EXISTS term_command (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  target_id   TEXT,
  target_name TEXT NOT NULL,
  command     TEXT NOT NULL,
  workdir     TEXT,
  exit_code   INTEGER,                          -- NULL=超时未拿到退出码
  duration_ms INTEGER NOT NULL,
  stdout      TEXT,
  stderr      TEXT,
  truncated   INTEGER NOT NULL DEFAULT 0,
  timed_out   INTEGER NOT NULL DEFAULT 0,
  ok          INTEGER NOT NULL DEFAULT 0,       -- 1=退出码为 0
  source      TEXT NOT NULL DEFAULT 'chat',     -- 谁发起的：chat | heartbeat | manual（设置页试跑）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_term_command_time ON term_command(created_at);

