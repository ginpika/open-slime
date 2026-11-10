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
-- 一张表装两种会话，靠 mode 区分：'ai'=首页对话，'multi-agent'=多智能体圆桌（名册另存 member_ids）。
-- 首页「最近会话」按 mode='ai' 过滤，圆桌页按 mode='multi-agent' 过滤——两边的 id 空间从此是一套。
CREATE TABLE IF NOT EXISTS hub_session (
  id                TEXT PRIMARY KEY,        -- hub 侧 uuid（圆桌会话也是 uuid）
  title             TEXT,
  mode              TEXT NOT NULL DEFAULT 'ai',
  model_id          TEXT,                    -- 本会话所选模型（llm_model.id 或内置 'env'）；NULL=设置页默认
  prompt_id         TEXT,                    -- 本会话所选「对话身份」版本（prompt_version.id）；NULL=提示词页绑定
  agent_id          TEXT,                    -- 本会话跟哪位 Agent 对话（agent.id）；NULL / 'builtin'=内置 Agent（用全局提示词）
  project_id        TEXT,                    -- 本会话所属项目（project.id）；NULL=无项目（记忆归属退化为 Agent 私有，见 memory-v5）
  show_cot          INTEGER NOT NULL DEFAULT 1, -- 本会话是否显示思维链（1=显示）；「…」菜单开关
  context_trace     INTEGER NOT NULL DEFAULT 0, -- 本会话是否把历史思考与工具调用回灌上下文（1=加入）；「…」菜单开关
  max_rounds        INTEGER NOT NULL DEFAULT 0, -- 历史兼容列，不参与工具执行
  show_rounds       INTEGER NOT NULL DEFAULT 0, -- 本会话思维链分段是否显示轮次计数（1=显示，缺省不显示）；「…」菜单开关
  unread            INTEGER NOT NULL DEFAULT 0, -- 子代理投递过新消息且用户未查看（1=未读）；打开会话即清零
  member_ids        TEXT,                    -- 圆桌会话的成员名册（agent.id 的 JSON 数组；NULL=全体名册）；首页会话为 NULL
  memory_off        INTEGER NOT NULL DEFAULT 0, -- 临时会话：1=本次不进记忆（memory_event 写入与提炼均跳过）；「…」菜单开关
  context_scope_revision INTEGER,             -- 单聊续接授权代号，改绑或回退时递增，NULL 等同 0
  context_stage     TEXT,                    -- 单聊阶段与 Epoch 的引用元数据（JSON）
  active_scene_id   INTEGER,                 -- 当前活动任务段（chat_scene.id，R1）；NULL=普通工作段
  context_segment_message_id INTEGER,         -- 普通段边界：message id 大于它者属于当前普通段（R1）
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER
);

-- AI 会话消息（role: user|assistant|system）；reasoning 为 assistant 的思维链纯文本（CoT，非推理模型为 NULL），
-- trace 为其结构化分段（JSON 数组：[{type:"reasoning",text}|{type:"tool",name,args}]，供前端分阶段下拉展示）；
-- tool_rounds 为本轮工具调用的原生消息（JSON 数组：[assistant(含 tool_calls/reasoning_content), tool(含
-- tool_call_id), ...]，assistant 与 tool 按 tool_call_id 成对）；reasoning/trace 仅供前端回放展示，
-- tool_rounds 另供会话开启 context_trace 时按原生协议原样回放（见 agent.context.SessionHistoryProvider）；
-- kind 为消息形态：NULL=普通对话，'subagent'=子代理结论气泡（前端只渲染标题+摘要，点开看全文）；
-- meta 为 kind 对应的元信息（JSON：{title,summary,status,execId}，见 ChatDtos.SubagentBrief）。
-- 末尾几列是圆桌（多智能体会话）独有的语义，首页对话一律为 NULL：
-- topic_id=所属话题（roundtable_topic.id）、peer_id/peer_name=发言成员（冗余存名，抗成员删除）、
-- context=该次发言**当时真正发出去的请求体**（messages + tools 的 JSON）、
-- event_seq=状态机事件补写的水位线（仅 role='event' 行）。
-- 例外：首页对话的 assistant 行也用 context 列，存该 Turn 每一轮 LLM 调用的留痕
-- （JSON 数组：[{round,request,response,withTools}, ...]，供「查看原始上下文」回看请求/响应）。
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
  topic_id      TEXT,
  peer_id       TEXT,
  peer_name     TEXT,
  context       TEXT,
  event_seq     INTEGER,
  event_kind    TEXT,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id, id);

-- 会话的一次「执行」（Turn）的结构骨架：哪条提问配哪条回答、这次执行挂在哪条任务上。
-- 只存引用、不复制正文（正文仍在 chat_message，前端读取路径不变）；于是「配对」从过去
-- 「扫描消息行、猜哪条回答配哪条提问」的启发式，变成一条显式外键。
-- 旧库无需迁移：没有 Turn 行的历史消息由读侧按老规则兜底配对（见 agent.chat.turn.History）。
CREATE TABLE IF NOT EXISTS chat_turn (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id         TEXT NOT NULL REFERENCES hub_session(id),
  trigger_source     TEXT NOT NULL,           -- USER_INPUT | SUBAGENT_REPORT | HEARTBEAT | PLAN | SYSTEM
  trigger_message_id INTEGER,                 -- 触发那条 chat_message.id（旁路投递时为空）
  reply_message_id   INTEGER,                 -- 这次执行的回答行 id（尚未产出为空）
  task_id            INTEGER,                 -- 归属任务（kind=session）——执行信封的挂点
  scene_id           INTEGER,                 -- 归属任务段（chat_scene.id，R1）；NULL=普通工作段
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_turn_trigger ON chat_turn(trigger_message_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_turn_reply ON chat_turn(reply_message_id);
CREATE INDEX IF NOT EXISTS idx_turn_session ON chat_turn(session_id, id);

-- 分支复制时的来源归属（R2）：copyUpTo 复制进新会话的每一行都带一条映射——
-- 指回原始会话与原始消息行，并记录复制时原始会话的授权代号（context_scope_revision）。
-- 原会话随后改绑 Agent/项目或回退都会推进代号，副本随即退出模型可读范围（用户转录不受影响）；
-- 原始消息被删除或撤回时，连接失败，副本同样失效。原始会话自己的行没有 origin 行。
CREATE TABLE IF NOT EXISTS chat_message_origin (
  message_id          INTEGER PRIMARY KEY REFERENCES chat_message(id) ON DELETE CASCADE,
  origin_session_id   TEXT NOT NULL,            -- 原始会话（hub_session.id）
  origin_message_id   INTEGER NOT NULL,         -- 原始消息行 id
  origin_scope_revision INTEGER NOT NULL,       -- 复制时原始会话的 COALESCE(context_scope_revision, 0)
  created_at          INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_origin_source ON chat_message_origin(origin_session_id, origin_message_id);

-- 派生内容的持久依赖（R3）：一次出站实际用过、且之后可被撤回的材料，按引用记在派生消息名下。
-- 覆盖主答复、子代理报告、批次归纳等；任一必需来源失效（撤回/删除/改绑退出授权），
-- 派生内容退出模型请求与回读，但保留在用户转录中。固定系统规则不记录。
CREATE TABLE IF NOT EXISTS chat_message_dependency (
  message_id INTEGER NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
  ref        TEXT NOT NULL,                     -- message:<id> | message:<id>:tool:<n> | artifact:<id> | subagent:<id>
  created_at INTEGER NOT NULL,
  PRIMARY KEY (message_id, ref)
);
CREATE INDEX IF NOT EXISTS idx_msg_dep_ref ON chat_message_dependency(ref);

-- 任务段（R1 · chat_scene）：同一会话内的一段工作，可覆盖多个 Turn。拥有临时角色（如跑团主持人）、
-- 任务约束、阶段续接（stage，每任务段一份）与自己的 Turn 集合（chat_turn.scene_id）。
-- 会话同一时刻至多一个活动任务段（hub_session.active_scene_id）；结束任务段即开启新的普通工作段
-- （hub_session.context_segment_message_id 记录普通段边界）。切换不改动用户有意设置的全局 Agent 提示词。
CREATE TABLE IF NOT EXISTS chat_scene (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   TEXT NOT NULL REFERENCES hub_session(id) ON DELETE CASCADE,
  name         TEXT,                          -- 展示名（界面显示当前任务）
  role_prompt  TEXT,                          -- 临时角色（限本任务段；切换即退出默认回放）
  constraints  TEXT,                          -- 用户确认的任务约束
  status       TEXT NOT NULL DEFAULT 'active',-- active | ended
  stage        TEXT,                          -- 本任务段的续接与 Epoch（JSON，结构同 hub_session.context_stage）
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_scene_session ON chat_scene(session_id, status, id);

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
-- 网页快照：原始正文证据与生成论点分存；同一 URL 的不同捕获内容保留独立版本。
CREATE TABLE IF NOT EXISTS web_snapshot (
  id TEXT PRIMARY KEY,
  doc_id INTEGER UNIQUE REFERENCES documents(id),
  url TEXT NOT NULL,
  title TEXT NOT NULL,
  author TEXT NOT NULL DEFAULT '',
  published_at TEXT NOT NULL DEFAULT '',
  captured_at INTEGER NOT NULL,
  saved_at INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  source_html TEXT NOT NULL,
  summary TEXT NOT NULL DEFAULT '',
  rationale TEXT NOT NULL,
  claims TEXT NOT NULL DEFAULT '[]',
  model TEXT NOT NULL DEFAULT '',
  warnings TEXT NOT NULL DEFAULT '[]',
  index_status TEXT NOT NULL DEFAULT 'pending',
  enabled INTEGER NOT NULL DEFAULT 1,
  UNIQUE(url, content_hash)
);
CREATE INDEX IF NOT EXISTS idx_web_snapshot_saved ON web_snapshot(saved_at DESC);
CREATE INDEX IF NOT EXISTS idx_documents_created ON documents(created_at DESC, id DESC);

-- Rebuildable text-only evidence: reading never needs to load embedded media after materialization.
CREATE TABLE IF NOT EXISTS web_snapshot_paragraph (
  snapshot_id TEXT NOT NULL REFERENCES web_snapshot(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  paragraph_id TEXT NOT NULL,
  content TEXT NOT NULL,
  PRIMARY KEY(snapshot_id, ordinal),
  UNIQUE(snapshot_id, paragraph_id)
);

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
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(doc_id);

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
CREATE TABLE IF NOT EXISTS llm_model (
  id              TEXT PRIMARY KEY,          -- uuid
  name            TEXT NOT NULL,             -- 展示名
  base_url        TEXT NOT NULL,             -- 带版本前缀，如 https://api.openai.com/v1
  api_key         TEXT NOT NULL,
  model           TEXT NOT NULL,             -- chat/completions 的 model
  context_window  INTEGER,                   -- 上下文窗口（token 数）；NULL=按默认值估算
  max_output_tokens INTEGER,                 -- 单次输出上限；NULL=模型服务默认，与上下文窗口独立
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

-- 提示词版本（用户自己编辑的覆盖版本）：每个「提示词位」（prompt_key）下可挂多份，
-- 由 prompt_binding 选定其一覆盖代码里写死的内置提示词；内置提示词本身不入库
--（代码内置见 ai/core/prompt/PromptLibrary，对话身份的内置来自 .env 的 LLM_SYSTEM_PROMPT）。
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
-- 无行等价于空。
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
-- 后台运行层（heartbeat）：**每个 Agent 各有一套**的常驻编制与调度。
--
--   心跳（heartbeat）——每个 Agent 一行：该 Agent「自己醒过来」时的正文与节拍，
--                           以及它的常驻职责名册。谁醒、隔多久醒、醒不醒，都是它自己的事。
--   常驻职责（heartbeat_plan）——某 Agent 的常驻编制：一份带正文/工具/渠道的岗位定义，
--                           心跳看着自己的名册按需把它派出去（delegate_heartbeat）。
--   子代理（task.origin=agent）——匿名 worker：当场交代即跑，不建档案、跑完即逝。
--
--   素材不再靠「注入器」声明式注入：心跳与职责的正文只写正文，需要什么就自己调工具现取
--   （everything in tools & bash）。heartbeat_run / task 是执行留痕，按 agent_id 归属。
-- ---------------------------------------------------------------------------

-- 逐 Agent 的心跳：每个 Agent 一行（agent_id 主键）。
CREATE TABLE IF NOT EXISTS heartbeat (
  agent_id     TEXT PRIMARY KEY,              -- agent.id（每个 Agent 一行）
  content      TEXT,                          -- 心跳正文（不含运行时数据）
  model_id     TEXT,                          -- 该 Agent 心跳所用模型（llm_model.id / 'env'）；NULL=设置页默认
  enabled      INTEGER NOT NULL DEFAULT 0,    -- 该 Agent 的心跳开关（默认关，由人在页面上开）
  tick_ms      INTEGER NOT NULL DEFAULT 60000, -- 该 Agent 的心跳节拍（毫秒）
  last_beat_at INTEGER,                       -- 上次真的敲过的时间（调度据此判「到点」）
  updated_at   INTEGER NOT NULL
);

-- 常驻职责（某 Agent 的一份编制）：正文（内容）只写正文，需要什么就自己调工具现取。
-- 由大模型自己创建（create_heartbeat）、改写（update_heartbeat）与删除（delete_heartbeat）；
-- 计划不自己跟时钟赛跑：interval_ms 只是给心跳的参考，last_beat_at 是上次真被派出的时间。
-- channels：silent=只留痕；thread=额外可用 open_thread/send_message 投递到会话（见 HeartbeatService）。
CREATE TABLE IF NOT EXISTS heartbeat_plan (
  id            TEXT PRIMARY KEY,             -- uuid
  agent_id      TEXT NOT NULL,                -- 归属 Agent（agent.id）
  name          TEXT NOT NULL,                -- 展示名（派活、改写、删除都靠它指认）
  role          TEXT NOT NULL DEFAULT 'system', -- system | user：正文组装成哪种消息
  content       TEXT NOT NULL,                -- 提示词正文（不含运行时数据）
  tools         TEXT,                         -- JSON：允许调用的工具名数组；空/NULL=不挂工具
  channels      TEXT,                         -- JSON：交付渠道数组，见 HeartbeatService.CHANNELS
  interval_ms   INTEGER,                      -- 节拍参考：NULL=每拍都可以考虑派它
  input         TEXT,                         -- 附加输入（seed，本轮交代）
  model_id      TEXT,                         -- 该计划指定的模型；NULL=设置页默认
  rationale     TEXT,                         -- 立它时给的一句「为什么立」：人隔月回看得靠它认由来
  -- 人工刹车：0=停用。停用只挡「自动派出」，定义与历史留痕都保留；「立即执行」仍可单跑验证。
  enabled       INTEGER NOT NULL DEFAULT 1,
  sort          INTEGER NOT NULL DEFAULT 0,
  last_beat_at  INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_heartbeat_plan_agent ON heartbeat_plan(agent_id, sort, id);

-- 一批执行：某 Agent 的一次心跳（trigger=beat）、页面点名（manual）、或一次对话委托（chat）
CREATE TABLE IF NOT EXISTS heartbeat_run (
  id          TEXT PRIMARY KEY,               -- uuid
  agent_id    TEXT NOT NULL,                  -- 归属 Agent（agent.id）
  beat        INTEGER NOT NULL,               -- 全局心跳序号（自增节拍，仅用于展示排序）
  trigger     TEXT NOT NULL,                  -- beat | manual | chat
  status      TEXT NOT NULL,                  -- running | done | failed
  agent_count INTEGER NOT NULL DEFAULT 0,     -- 本批执行的次数（心跳 / 职责 / 子代理合计）
  note        TEXT,
  started_at  INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_heartbeat_run_started ON heartbeat_run(started_at);
CREATE INDEX IF NOT EXISTS idx_heartbeat_run_agent ON heartbeat_run(agent_id, started_at);

-- 一次执行（独立上下文）并入 task（身份 + 过程同处一行，origin 列记来源：heartbeat | plan | agent）。
-- 老的 sub_exec / sub_exec_edit 已彻底退役，本仓不再有任何表。

-- ---------------------------------------------------------------------------
-- 任务（Task）：编排层的一等公民，也是「一次执行」的**唯一正本**。
--
-- 一次执行 = 一行 task：身份（kind / parent / title / session）、状态、以及过程
-- （context / reasoning / trace / tool_rounds / error）都在这里。
--
-- kind：session（前台会话）| subagent（匿名子代理）| plan（心跳编制与心跳主会话）| code（IDE 任务，预留）
-- status：pending | running | suspended | done | failed | cancelled | interrupted
--   suspended=挂起（预留）、cancelled=主动取消（预留）、interrupted=进程重启打断（可重跑，非任务自身终态）。
-- payload 存重跑所需的原始输入（JSON）；model_id / workspace_id / budget 为后续步骤预留，当前不参与调度。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  parent_id    INTEGER REFERENCES task(id),   -- 父任务（嵌套）；NULL=顶层
  kind         TEXT NOT NULL,                 -- session | subagent | plan | code
  status       TEXT NOT NULL,                 -- pending | running | suspended | done | failed | cancelled | interrupted
  title        TEXT,                          -- 展示名
  session_id   TEXT,                          -- 归属会话（kind=subagent：结论投回的 hub_session.id）
  agent_id     TEXT,                          -- 归属 Agent（agent.id）：这次执行是哪个智能体产生的
  model_id     TEXT,                          -- 本任务所用模型（llm_model.id / 'env'）；NULL=设置页默认
  workspace_id TEXT,                          -- 绑定工作空间（为 IDE 预留；当前未用）
  budget       TEXT,                          -- JSON：token / 轮数 / 时长上限（成本护栏；当前未用）
  payload      TEXT,                          -- JSON：重跑所需输入（子代理：工具调用参数原文）
  memory_checkpoint TEXT,                     -- JSON：任务记忆检查点（暂停/恢复用的工作状态快照，见 memory-v5 §9）
  run_id       TEXT,                          -- 归属批次（heartbeat_run.id）
  plan_id      TEXT,                          -- kind=plan 时的编制 id（heartbeat_plan.id）
  origin       TEXT,                          -- 这次执行的来源：heartbeat | plan | agent（读侧按它分类）
  context      TEXT,                          -- 组装出的完整 LLM 上下文（messages + tools 的 JSON）
  reasoning    TEXT,                          -- 思维链纯文本
  trace        TEXT,                          -- 过程分段 JSON（前台分阶段下拉展示的那份）
  tool_rounds  TEXT,                          -- 工具往返原生消息 JSON
  error        TEXT,                          -- 失败原因（status=failed 时）
  started_at   INTEGER,                       -- 开始时间
  finished_at  INTEGER,                       -- 结束时间（还在跑时为空）
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_status ON task(status, id);
CREATE INDEX IF NOT EXISTS idx_task_session ON task(session_id, id);
CREATE INDEX IF NOT EXISTS idx_task_run ON task(run_id);
CREATE INDEX IF NOT EXISTS idx_task_origin ON task(origin);

-- 任务产物（task_artifact）：一条任务跑出来的东西——结论文本、生成的文件、检索片段引用……
-- 三者分工：task 说「谁在跑、什么状态、过程如何」（过程列已并入 task），这里说「产出了什么」。
--   kind=text —— 结论文本（content 存正文；执行引擎产出结论时自动落一条）
--   kind=file —— 生成的文件（ref 存路径；由后续的文件能力域自己补）
--   kind=ref  —— 外部引用（ref 存 chunk id / 地址；检索片段引用等）
--   kind=tool_result —— 单聊上下文试点保存的裁剪工具结果（content 存原始文本）
-- 为 STEP 5 的工作台铺路：产物面板按任务列出它产出的东西。
CREATE TABLE IF NOT EXISTS task_artifact (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id    INTEGER NOT NULL REFERENCES task(id),
  exec_id    INTEGER,                       -- 由哪次执行产出（task.id；可回看过程）
  kind       TEXT NOT NULL,                 -- text | file | ref | tool_result
  name       TEXT,                          -- 展示名（结论标题 / 文件名 / 片段标题）
  content    TEXT,                          -- 文本产物正文（kind=text）
  ref        TEXT,                          -- 引用（kind=file：路径；kind=ref：id / 地址）
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_artifact ON task_artifact(task_id, id);

-- 变更集（changeset / changeset_item）：把「写操作」从审计留痕升级为有结构、可 diff / 可回滚的一组变更。
--
-- 一次执行（exec）对应一个 changeset，每次写工具调用追加一条 item，回滚时按 changeset 整体撤。
-- after=变更后内容（即工具调用参数）；before 预留给「变更前快照」：当前写工具都不取旧值，故为空，
-- target 亦同（等能力域在写入前补上，见架构文档 STEP 1 / STEP 3）。
-- status：applied（已落库）| reverted（已回滚，预留）。
CREATE TABLE IF NOT EXISTS changeset (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id    INTEGER REFERENCES task(id),      -- 归属任务（更早的执行可能没有任务行，故可空）
  exec_id    INTEGER,                          -- 归属执行（task.id）
  status     TEXT NOT NULL DEFAULT 'applied',  -- applied | reverted
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_changeset_exec ON changeset(exec_id);
CREATE INDEX IF NOT EXISTS idx_changeset_task ON changeset(task_id, id);

CREATE TABLE IF NOT EXISTS changeset_item (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  changeset_id INTEGER NOT NULL REFERENCES changeset(id),
  tool         TEXT NOT NULL,               -- 写库工具名（upsert_node / edit_* / delete_* / create_document…）
  target       TEXT,                        -- 作用对象（实体 / 关系 / 文档；当前工具不单独给，多为空）
  before       TEXT,                        -- 变更前快照（JSON；回滚用，当前为空）
  after        TEXT,                        -- 变更后内容（JSON：工具调用参数）
  summary      TEXT,                        -- 工具返回的可读结果
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_changeset_item ON changeset_item(changeset_id, id);

-- ---------------------------------------------------------------------------
-- 智能体（Agent）：名册成员 + 圆桌。
-- 成员（agent）是系统里「一个具名、常驻的智能体」的唯一实体：内置 Agent（首页对话 + 心跳调度）
-- 与人在成员页自建的成员都在这一张表里。圆桌（roundtable_topic / roundtable_topic_event）
-- 是这些成员轮流发言的场所。见 agent.member 包。
-- ---------------------------------------------------------------------------

-- 智能体名册：一个具名、常驻的智能体（不是匿名 worker，也不按节拍派出）。
-- builtin=1 的是内置 Agent（不可删除、默认存在）——它同时是首页对话的应答者与心跳的调度者，
-- 由 AgentSeeder 启动播种；其余由人在「智能体」页配置，不由大模型自建。
-- 每位智能体自己那套提示词存在 agent_prompt_*（见下），未配的位回退到内置（全局提示词）。
-- 能力面逐成员配：tools 是额外可调的工具，terminals 是它能碰的工作空间（term_target.id）——
-- 两者都是「在默认只读检索之上追加」，空/未配即没有这层额外能力。
CREATE TABLE IF NOT EXISTS agent (
  id          TEXT PRIMARY KEY,               -- uuid（内置 Agent 用固定 id）
  name        TEXT NOT NULL,                  -- 展示名；也是发言时被 @ 的名字
  builtin     INTEGER NOT NULL DEFAULT 0,     -- 1=内置 Agent（不可删除、默认存在）
  tools       TEXT,                           -- JSON：额外允许调用的工具名数组；NULL=默认只读检索
  terminals   TEXT,                           -- JSON：绑定给它的工作空间（term_target.id 数组）；
                                              --    非空即授予 run_command / list_terminals，且只在绑定的目标上可用
  model_id    TEXT,                           -- 该智能体的模型（llm_model.id / 'env'）；NULL=设置页默认
  sort        INTEGER NOT NULL DEFAULT 0,     -- 发言顺序（顺序轮转的依据）
  note        TEXT,                           -- 给人看的备注（不注入模型）
  role        TEXT,                           -- 角色（展示用的一句话岗位，如「产品经理」）；只在执行态卡片右上角的小标签上露面
  role_color  TEXT,                           -- 角色标签的颜色（#RRGGBB）；NULL=用默认灰
  avatar      TEXT,                           -- 头像：data:image/...;base64,... 的小图（前端压到 256px 后存，不落磁盘）
  anchor_id   TEXT,                           -- 图谱人格锚点：entities.id（types 含 ANCHOR 的实体）；NULL=尚未建立
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- 智能体的提示词版本：每个智能体一套「对话」提示词（对话身份 / 对话增强 / 终端 / 子代理 / 子代理回报摘要）。
CREATE TABLE IF NOT EXISTS agent_prompt_version (
  id          TEXT PRIMARY KEY,               -- uuid
  agent_id    TEXT NOT NULL,                  -- agent.id
  prompt_key  TEXT NOT NULL,                  -- 提示词位（PromptLibrary 注册表的 key）
  name        TEXT NOT NULL,                  -- 版本名（展示用）
  content     TEXT NOT NULL,                  -- 提示词正文
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_prompt_version
  ON agent_prompt_version(agent_id, prompt_key, created_at);

-- 智能体的提示词位 → 生效版本（每个智能体每个位至多一行）：version_id 为空 = 回退内置（全局提示词）
CREATE TABLE IF NOT EXISTS agent_prompt_binding (
  agent_id    TEXT NOT NULL,                  -- agent.id
  prompt_key  TEXT NOT NULL,
  version_id  TEXT,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (agent_id, prompt_key)
);

-- 智能体上下文内提示词位的拼装顺序（成员页拖拽调整；无行 = 按 PromptLibrary 注册表顺序）
CREATE TABLE IF NOT EXISTS agent_prompt_order (
  agent_id    TEXT NOT NULL,
  context_key TEXT NOT NULL,
  slot_key    TEXT NOT NULL,
  sort        INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  PRIMARY KEY (agent_id, context_key, slot_key)
);

-- 圆桌的一次话题（对话 loop 的边界）：由某人（用户或成员）的一条发言开启，全体成员都表态结束后关闭。
-- status：open=进行中；closing=已进入「向沉默者征询」阶段；closed=已收束。
-- floor_peer：当前持有发言权的成员（NULL=无人正在发言）——抢答即抢夺这个位。
-- resolve_round：已经重开过几次（沉默者发言 → 全员重新决策即一次重开），上限见 RoundtableProps。
CREATE TABLE IF NOT EXISTS roundtable_topic (
  id            TEXT PRIMARY KEY,             -- uuid
  session_id    TEXT NOT NULL REFERENCES hub_session(id), -- 会话已并入 hub_session（见上）
  origin        TEXT NOT NULL,                -- user | peer：话题由谁的发言开启
  origin_peer   TEXT,                         -- origin=peer 时的发起成员（agent.id）
  status        TEXT NOT NULL,                -- open | closing | closed
  floor_peer    TEXT,                         -- 当前持有发言权的成员；NULL=无人发言
  resolve_round INTEGER NOT NULL DEFAULT 0,   -- 已重开次数
  created_at    INTEGER NOT NULL,
  closed_at     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_roundtable_topic_session ON roundtable_topic(session_id, id);

-- 隐式状态机的事件正本：每次发言、每次状态跃迁、每次征询与打断，都是一条只追加的事件。
-- 成员的当前状态不单独存列，一律由本表按 seq 折叠得出（见 TopicStateMachine）：
-- 每个 actor 取它最后一条带 to_state 的事件，即为它此刻的状态。
-- kind：topic_open（话题开始）/ addressed（被点名）/ floor_granted（授予发言权）/ speech（发言）/
--       floor_released（说完让出）/ interrupt（被用户或同伴打断）/ silent（转入沉默）/
--       propose_end（提议结束）/ prompt（被要求立刻表态）/ reopen（全员重新决策）/ close（话题关闭）。
CREATE TABLE IF NOT EXISTS roundtable_topic_event (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  topic_id    TEXT NOT NULL REFERENCES roundtable_topic(id),
  seq         INTEGER NOT NULL,               -- 话题内序号（1 起，折叠状态的排序依据）
  actor       TEXT NOT NULL,                  -- agent.id 或 'user'
  kind        TEXT NOT NULL,                  -- 见上
  from_state  TEXT,                           -- 跃迁前状态（无跃迁为 NULL）
  to_state    TEXT,                           -- 跃迁后状态（无跃迁为 NULL）
  peer_id     TEXT,                           -- 目标成员（addressed / floor_granted / prompt / interrupt 时）
  content     TEXT,                           -- 发言正文（kind=speech）
  message_id  INTEGER,                        -- 对应的 chat_message.id（kind=speech）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_roundtable_topic_event ON roundtable_topic_event(topic_id, seq);

-- 旧「会话（thread）」体系已退役：子代理的 open_thread / send_message 改为直接往
-- 首页 chat 的 hub_session / chat_message 投递（同名复用、无则新建，见 HeartbeatService）。

-- 记忆事件流（AI Memory 的时序轴正本，见 ai/memory 包）：agent 侧「说过/做过」由发生点
-- 顺手 push 进来（ChatService 落 chat_message 时、AgentRunner 落结论时），事件只追加不改写；
-- 情节正本仍在 chat_message / task，这里存的是投影（截断后的正文 + 指回正本的 ref）。
-- event_key 幂等：同 key 重复 push 静默跳过（INSERT OR IGNORE），正本行 id 即天然键。
-- M0 范围：写入 + 时间窗召回（memory 注入器）；M1 挂上向量轴（memory_event_embedding）；
-- 图轴与固化管道见后续里程碑。
CREATE TABLE IF NOT EXISTS memory_event (
  agent_id TEXT, project_id TEXT, session_id TEXT, task_id INTEGER, identity TEXT,
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  event_key   TEXT NOT NULL UNIQUE,            -- 幂等键（ref_type:ref_id）
  kind        TEXT NOT NULL,                   -- chat | exec
  actor       TEXT NOT NULL,                   -- user | assistant | heartbeat | plan | agent
  title       TEXT,                            -- 展示名（会话名 / 计划名 / 子代理短名）
  payload     TEXT,                            -- 正文投影（原话 / 结论，写入侧已截断）
  ref_type    TEXT NOT NULL,                  -- chat_message | task（正本表名）
  ref_id      INTEGER NOT NULL,               -- 正本行 id（回溯与后续向量/图增强的挂点）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_event_time ON memory_event(created_at, id);

-- 回退撤销的是来源，不是内容；异步提炼返回时据此拒绝写回旧分支。
CREATE TABLE IF NOT EXISTS memory_retracted_source (
  ref TEXT PRIMARY KEY,
  retracted_at INTEGER NOT NULL
);

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

-- ---------------------------------------------------------------------------
-- 记忆条目（graph_claim，记忆体系 v2 · STEP M1）：系统里「记忆」的一等公民。
--
--   与 memory_event（证据层）的分工：memory_event 是「发生过什么的原始投影」（只追加、逐条），
--   本表是「关于用户 / 项目的稳定信息」（会更新、会过期、会被取代）。记忆召回默认读本表，
--   需要原始细节时再按 source_refs 下钻回 memory_event。
--   scope：directive（对记忆行为的指令）| profile（长期画像）| project（进行中的事）| episode（时段摘要）。
--   superseded_by 指向取代它的新条目（NULL=仍有效）；valid_until 到期即失效——两者由巩固（M4）维护。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS graph_claim (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  agent_id      TEXT,                      -- 归属 Agent（agent.id）；NULL 已规范化：历史 NULL 归 'builtin'（见 memory-v5）
  scope         TEXT NOT NULL,             -- profile|directive|project|episode|fact|decision|method|open_issue|checkpoint|handoff|knowledge
  title         TEXT,                      -- 一句话标题（列表 / 注入的极简识别）
  body          TEXT NOT NULL,             -- 记忆正文（关于用户 / 项目的稳定信息，非原文切片）
  confidence    REAL NOT NULL DEFAULT 0.5, -- 提炼置信度 0..1
  source        TEXT NOT NULL DEFAULT 'auto', -- auto(隐式提炼) | explicit(用户明说) | manual(人工编辑)
  pinned        INTEGER NOT NULL DEFAULT 0, -- 1=人工固定：强制注入，不受相关性门控过滤
  valid_from    INTEGER NOT NULL,
  valid_until   INTEGER,                   -- 过期时刻（NULL=不过期）
  superseded_by INTEGER,                   -- 被哪条新条目取代（NULL=仍有效）
  source_refs   TEXT,                      -- 来源证据：memory_event.id 数组（JSON），供下钻
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  ---------------------------------------------------------------- memory-v5 增量（可空；老库由 SchemaColumnPatches 补）
  user_id       TEXT,                      -- 多租户预留：app_user.id
  owner         TEXT,                      -- USER|PROJECT|AGENT|TASK —— 归属者（决定读写权限）
  domain        TEXT,                      -- USER_PROFILE|PROJECT|AGENT|TASK_EPISODIC —— 描述什么
  layer         TEXT,                      -- L1|L2|L3|L4 —— 流转层（决定可见范围与生命周期）
  project_id    TEXT,                      -- owner=PROJECT 时非空
  session_id    TEXT,                      -- owner=TASK 时非空
  task_id       INTEGER,                   -- 归属任务
  producer_agent TEXT,                     -- 生产者 Agent（仅审计；与 owner 解耦）
  source_kind   TEXT,                      -- real|fiction|project_pref|inferred —— role-play 隔离依据
  evidence_level TEXT,                     -- explicit|repeated|inferred —— 画像升级闸门
  status        TEXT,                      -- active|superseded|expired|conflicted|revoked|archived
  retention     TEXT,                      -- pinned|long|normal|ephemeral —— 保护级别
  version       INTEGER,                   -- 乐观锁版本（并发写保护）
  fingerprint   TEXT,                      -- 幂等键：sha1(owner|project_id|agent_id|domain|scope|normalize(body))
  valid_at      INTEGER,                   -- 双时态：事实在世界中成立的时间
  invalid_at    INTEGER,                   -- 双时态：事实失效时间（矛盾时关闭，不删除）
  learned_at    INTEGER,                   -- 双时态：系统学到它的时间
  expired_at    INTEGER,                   -- 双时态：系统得知其失效的时间
  ---------------------------------------------------------------- memory-v6 增量（identity / 提升来源 / 软过期）
  identity      TEXT,                      -- REAL|FICTION|PROJECT_PREF|INFERRED —— 身份上下文（role-play 隔离）
  promoted_from INTEGER,                   -- 提升来源：memory_promotion.id（L4/L1 的可追溯凭据）
  expires_soft  INTEGER,                   -- 软过期时刻：到期只「不再注入」，不删除（与 hard 过期分开）
  temporal_json TEXT                       -- 来源锚定的事件/状态时间区间、精度、时区；未知保持空
);
CREATE INDEX IF NOT EXISTS idx_memory_item_scope ON graph_claim(scope, superseded_by, valid_until);
CREATE INDEX IF NOT EXISTS idx_memory_item_agent ON graph_claim(agent_id, scope);
-- 注意：memory-v5 新增列（owner/project_id/domain/status/fingerprint…）上的索引**不在这里**建：
-- 老库执行 schema.sql 时这些列尚未由 SchemaColumnPatches 补上，建索引会当场报 no such column。
-- 它们统一由 SchemaIndexes 在加列之后创建（见 config/SchemaIndexes.java）。

-- 记忆条目向量（memory_item_embedding，M1）：graph_claim 的 embedding 投影，列结构对齐
-- memory_event_embedding（BLOB = little-endian float32，同模型余弦）。
CREATE TABLE IF NOT EXISTS memory_item_embedding (
  item_version INTEGER,
  item_id    INTEGER PRIMARY KEY REFERENCES graph_claim(id),
  model      TEXT NOT NULL,                  -- embedding 模型名（同 memory_event_embedding 的语义）
  dim        INTEGER NOT NULL,
  vec        BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);

-- 项目（project，memory-v5 §4.1）：跨 Session/Task/Agent 的业务工作域。项目的存在让「项目间记忆隔离」
-- 成为可能——记忆按 owner=PROJECT + project_id 归属，读侧 visible 谓词只放行当前项目。
--   kind：novel|software|research|other；narrative=1 表示创作/虚构类项目 → 域内记忆禁止升级为用户画像。
CREATE TABLE IF NOT EXISTS project (
  id          TEXT PRIMARY KEY,
  user_id     TEXT,                          -- 多租户预留；单用户阶段= app_user 唯一行
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'other',
  narrative   INTEGER NOT NULL DEFAULT 0,    -- 1=创作/虚构类 → 记忆不得升画像（memory-v5 §3.6）
  status      TEXT NOT NULL DEFAULT 'active',-- active|paused|archived
  note        TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_project_user ON project(user_id, status);

-- 画像候选（memory_candidate，memory-v5 §5.4）：用户画像的唯一权威入口。普通 Agent 不得直接写
-- graph_claim 的 USER_PROFILE 域——只能生成候选，经证据/审批闸门方可由 UserProfileService 落为正式画像。
--   evidence_level：explicit（用户明说）| repeated（多次出现）| inferred（推断，不足以上升）
--   fingerprint 在 pending 内唯一：同指纹追证只累加 evidence_count，不重复建候选。
CREATE TABLE IF NOT EXISTS memory_candidate (
  valid_until INTEGER,
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  fingerprint    TEXT NOT NULL,
  user_id        TEXT,
  owner          TEXT NOT NULL DEFAULT 'USER',
  scope          TEXT NOT NULL,              -- profile | directive
  title          TEXT,
  body           TEXT NOT NULL,
  source_kind    TEXT NOT NULL DEFAULT 'inferred', -- real|fiction|project_pref|inferred
  evidence_level TEXT NOT NULL,              -- explicit|repeated|inferred
  evidence_count INTEGER NOT NULL DEFAULT 1,
  evidence_refs  TEXT,                       -- JSON：chat_message.id 数组
  status         TEXT NOT NULL DEFAULT 'pending', -- pending|approved|rejected
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_candidate_fp
  ON memory_candidate(fingerprint) WHERE status='pending';

-- 共享授权（memory_share，memory-v5 §7.3 · L4 交接/知识）：Agent 主动把条目发布到指定作用域。
-- 读侧 R5 经此表联合查询发现（不推送）；revoked 后天然不可见。
CREATE TABLE IF NOT EXISTS memory_share (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id      INTEGER NOT NULL REFERENCES graph_claim(id),
  from_owner   TEXT NOT NULL,
  from_agent   TEXT,
  from_project TEXT,
  to_kind      TEXT NOT NULL,                -- user|project|agent|session
  to_ref       TEXT,
  status       TEXT NOT NULL DEFAULT 'published', -- published|revoked
  note         TEXT,
  created_at   INTEGER NOT NULL,
  revoked_at   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_share_item ON memory_share(item_id, status);
CREATE INDEX IF NOT EXISTS idx_share_target ON memory_share(to_kind, to_ref, status);

-- 撤销墓碑（memory_revocation，memory-v5 §5.4）：画像撤销/拒绝后写墓碑，防同指纹被自动重建。
--   allow_relearn=1 表示用户解除了封锁，允许重新学习。
CREATE TABLE IF NOT EXISTS memory_revocation (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  target_item_id INTEGER,
  fingerprint    TEXT NOT NULL,
  reason         TEXT,
  allow_relearn  INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revocation_fp ON memory_revocation(fingerprint);

-- 双时态知识边（memory_edge，memory-v5 §7.1 · P4）：记忆的「关系轴」，借鉴 Zep/Graphiti。
-- 每条边是 (subject, predicate, object) 三元组，带双时态：valid_at（事实世界成立）/ invalid_at（失效）/
-- learned_at（系统学到）/ expired_at（系统得知失效）。新事实与旧事实矛盾时，**关闭旧边有效期**（写 invalid_at）
-- 而非删除——保留完整审计与「某时刻知道什么」的点查能力。归属同条目（owner × project × agent）。
CREATE TABLE IF NOT EXISTS memory_edge (
  id           TEXT PRIMARY KEY,
  user_id      TEXT,
  owner        TEXT NOT NULL,
  project_id   TEXT,
  agent_id     TEXT,
  subject      TEXT NOT NULL,
  predicate    TEXT NOT NULL,
  object       TEXT NOT NULL,
  confidence   REAL NOT NULL DEFAULT 0.5,
  valid_at     INTEGER,
  invalid_at   INTEGER,
  learned_at   INTEGER NOT NULL,
  expired_at   INTEGER,
  source_item  INTEGER REFERENCES graph_claim(id),
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_edge_subject ON memory_edge(subject, predicate, invalid_at);
CREATE INDEX IF NOT EXISTS idx_edge_object  ON memory_edge(object, predicate, invalid_at);
CREATE INDEX IF NOT EXISTS idx_edge_owner   ON memory_edge(owner, project_id, agent_id, invalid_at);

-- 提炼水位（memory_cursor，M2）：每个会话已提炼到哪条 memory_event，避免重复提炼。
CREATE TABLE IF NOT EXISTS memory_cursor (
  session_id    TEXT PRIMARY KEY,
  last_event_id INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- memory-v6 · 记忆运行时（见 docs/memory/memory-v6.md）
-- ---------------------------------------------------------------------------

-- 写入审计（memory_revision，memory-v6 §6.1）：每次提交留一条，可回放、可追责、可 purge 溯源。
-- 与 memory_event 的区别：event 是「说过/做过」的正本投影，revision 是「记忆条目被谁改成了什么」。
CREATE TABLE IF NOT EXISTS memory_revision (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id      INTEGER,                     -- 关联条目（新建时可空，落库后回填）
  op           TEXT NOT NULL,               -- create|update|supersede|status|promote|publish|revoke|purge
  actor_kind   TEXT NOT NULL,               -- AGENT|USER|SYSTEM
  actor_id     TEXT,                        -- agent.id / 'user' / 'migrator'
  turn_id      TEXT,                        -- 回放键（MemoryExec.turnId）
  before_json  TEXT,                        -- 变更前（JSON，审计用）
  after_json   TEXT,                        -- 变更后
  reason       TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rev_item ON memory_revision(item_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rev_turn ON memory_revision(turn_id);

-- 工作状态（memory_work，memory-v6 §13 · L3 的工程实现）：会话 / 任务级临时物，
-- 与长期记忆物理分离（三概念分离）。会话/任务结束时由 MemoryDistiller 蒸馏进 L2/L4 后归档。
CREATE TABLE IF NOT EXISTS memory_work (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  task_id     INTEGER,
  agent_id    TEXT,
  project_id  TEXT,
  kind        TEXT NOT NULL,                -- progress|temp_conclusion|tool_result|decision_in_flight|todo_snapshot
  title       TEXT,
  body        TEXT NOT NULL,
  ref_type    TEXT,                         -- chat_message|task|tool_exec（指回正本）
  ref_id      INTEGER,
  status      TEXT NOT NULL DEFAULT 'open', -- open|closed|distilled|archived
  expires_at  INTEGER,                      -- TTL：到期不再注入
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_work_session ON memory_work(session_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_work_task ON memory_work(task_id, status);

-- 提升记录（memory_promotion，memory-v6 §9.3）：L2→L4 / L4→L1 的来源凭据。
-- 不变量 I6：layer=L4 的条目必须有可追溯发布来源（memory_share 或本表）。
CREATE TABLE IF NOT EXISTS memory_promotion (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id        INTEGER NOT NULL,
  from_layer     TEXT NOT NULL,
  to_layer       TEXT NOT NULL,
  policy         TEXT NOT NULL,             -- auto|manual|approved
  evidence_count INTEGER NOT NULL DEFAULT 1,
  state          TEXT NOT NULL DEFAULT 'candidate', -- candidate|applied|rejected
  note           TEXT,
  created_at     INTEGER NOT NULL,
  applied_at     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_promotion_item ON memory_promotion(item_id, state);

-- 交接状态（memory_handoff，memory-v6 §9.2）：L4 的「交到谁手上、做到哪一步」。
CREATE TABLE IF NOT EXISTS memory_handoff (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  item_id    INTEGER NOT NULL REFERENCES graph_claim(id),
  to_kind    TEXT NOT NULL,                 -- agent|project|session|user
  to_ref     TEXT,
  state      TEXT NOT NULL DEFAULT 'offered', -- offered|accepted|done|rejected
  note       TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_target ON memory_handoff(to_kind, to_ref, state);

-- 快照回放（memory_snapshot，memory-v6 §4.3）：只存引用（turnId → 命中条目与水位），不存正文。
-- 回答「这一轮模型到底看见了什么」，也是「本轮写入不回改本轮」的取证依据。
CREATE TABLE IF NOT EXISTS memory_snapshot (
  content_json TEXT,
  turn_id    TEXT PRIMARY KEY,
  session_id TEXT,
  agent_id   TEXT,
  project_id TEXT,
  watermark  INTEGER NOT NULL,
  item_ids   TEXT,                          -- JSON 数组（命中条目的 id）
  tokens     INTEGER NOT NULL DEFAULT 0,
  built_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_snapshot_session ON memory_snapshot(session_id, built_at);

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
  agent_id     TEXT,                      -- 归属 Agent（agent.id）：这次召回是哪个智能体发起的
  query        TEXT,                      -- 调用参数原文（JSON 字符串）
  result       TEXT,                      -- 回给模型的 tool 消息原文（大模型实际拿到的原始数据）
  result_chars INTEGER NOT NULL DEFAULT 0,-- result 的原始字符数（超限截断前，供页面标注）
  created_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rag_recall_time ON rag_recall(created_at);
CREATE INDEX IF NOT EXISTS idx_rag_recall_channel ON rag_recall(channel, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_recall_agent ON rag_recall(agent_id, created_at);

-- 一次 LLM 调用的 token 用量（统计页数据源）：每次 chat/embedding/rerank 调用结束后一行，
-- 真实值来自供应商回传的 usage（estimated=0），未回传时为本地估算兜底（estimated=1）。
-- source 取值见 TokenUsageService 的来源常量。
CREATE TABLE IF NOT EXISTS token_usage (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  source            TEXT NOT NULL,              -- chat | analyze | commit | doc-summary | chunk-questions | chunk-summary | chunker | heartbeat | embedding | rerank | model-test
  model             TEXT NOT NULL DEFAULT '',   -- 模型名（未配置时为空串）
  prompt_tokens     INTEGER NOT NULL DEFAULT 0, -- 输入 token
  completion_tokens INTEGER NOT NULL DEFAULT 0, -- 输出 token
  total_tokens      INTEGER NOT NULL DEFAULT 0,
  estimated         INTEGER NOT NULL DEFAULT 0, -- 1=本地估算（供应商未回传 usage）
  provider          TEXT,                      -- 无凭证的模型端点，历史未知时为 NULL
  quality           TEXT,                      -- reported | estimated | partial
  status            TEXT,                      -- success | interrupted | failed
  cached_tokens     INTEGER,                   -- 已包含在输入中
  reasoning_tokens  INTEGER,                   -- 已包含在输出中
  created_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_token_usage_created ON token_usage(created_at);
CREATE INDEX IF NOT EXISTS idx_token_usage_source ON token_usage(source);

-- 手工价格版本：按调用时间匹配最近生效版本；更正版本会重新估算历史，金额非实付账单。
CREATE TABLE IF NOT EXISTS token_price (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  effective_from INTEGER NOT NULL,
  currency TEXT NOT NULL,
  input_per_million TEXT NOT NULL,
  output_per_million TEXT NOT NULL,
  cached_per_million TEXT,
  UNIQUE(provider, model, effective_from)
);

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
  host_workdir    TEXT,                         -- 可选：Docker 宿主机目录，绑定到 workdir（容器内路径）
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

-- ---------------------------------------------------------------------------
-- 待办清单（plan-of-record，STEP 4 编排层进阶）：一次「任务分解」的正本。
--
-- 与 heartbeat_plan 的区别（二者语义不同，别混用）：heartbeat_plan 是常驻编制（有名字、有节拍、可反复派），
-- 这里是「这一轮活要分几步做」的临时清单——由大模型用 write_todos 工具写，随会话存档。
-- 全量覆盖式写入：每次 write_todos 把该会话的清单整体替换（模型给出当前完整视图），
-- 于是「进度怎么变的」由同一批 seq 行稳定复现，前端与上下文源都只读最近一份。
CREATE TABLE IF NOT EXISTS chat_todo (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,                  -- 归属会话（hub_session.id）
  owner       TEXT,                           -- 归属 Agent（成员 id / 内置 Agent）：同会话里各 Agent 的个人清单互不串写
  seq         INTEGER NOT NULL,               -- 清单内顺序（0 起，越小越靠前）
  content     TEXT NOT NULL,                  -- 这一步要做什么（一句祈使句）
  status      TEXT NOT NULL,                  -- pending | in_progress | done | failed
  note        TEXT,                           -- 结果 / 失败原因（可选，收尾时写上更可读）
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
-- 索引只按 (session_id, seq) 建：owner 是后加的列（老库靠 SchemaColumnPatches 补），
-- 若在这里引用它，老库执行建索引语句会因「表在但列不在」直接报错、拖垮整个 schema 初始化
CREATE INDEX IF NOT EXISTS idx_chat_todo_session ON chat_todo(session_id, seq);

-- 单聊待办审批的安全点和回执。operation_id 沿用被暂停的 task.id。
CREATE TABLE IF NOT EXISTS chat_todo_approval (
  operation_id       INTEGER PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
  session_id         TEXT NOT NULL REFERENCES hub_session(id) ON DELETE CASCADE,
  turn_id            INTEGER NOT NULL UNIQUE,
  trigger_message_id INTEGER NOT NULL,
  reply_message_id   INTEGER NOT NULL,
  actor_id           TEXT NOT NULL,
  agent_id           TEXT NOT NULL,
  project_id         TEXT,
  scope_revision     INTEGER NOT NULL,
  todo_id            INTEGER NOT NULL,
  todo_updated_at    INTEGER NOT NULL,
  content            TEXT NOT NULL,
  note               TEXT,
  status             TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'stale')),
  decision           TEXT CHECK (decision IN ('approve', 'reject')),
  created_at         INTEGER NOT NULL,
  decided_at         INTEGER
);
CREATE INDEX IF NOT EXISTS idx_chat_todo_approval_session ON chat_todo_approval(session_id, status, scope_revision);

-- ---------------------------------------------------------------------------
-- 子代理共享黑板（scratchpad，STEP 4）：本会话历次子代理的发现，按时间累积。
--
-- 用途：新派出的子代理开工前先看它（注入 system），于是「前面的人查过什么」不必重查——
-- 一批并行子代理之间看不到彼此，但同一会话里先派后派能接力。结论逐条追加，只增不改写。
-- 由 SubagentService 在子代理收尾时自动落一条（author=短名，text=结论，exec_id 可回看过程）。
CREATE TABLE IF NOT EXISTS subagent_scratch (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,                  -- 归属会话（结论投回的 hub_session.id）
  author      TEXT,                           -- 写下这条的子代理短名
  exec_id     INTEGER,                        -- 对应执行（task.id；可回看检索过程）
  content     TEXT NOT NULL,                  -- 发现正文（写入侧已按上限截断）
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_subagent_scratch_session ON subagent_scratch(session_id, id);

-- ---------------------------------------------------------------------------
-- 运行时事件正本（agent/runtime/event，设计一）：系统推进的基本单位。
--
--   「Agent 负责思考和提出行动，Runtime 负责调度和控制执行；Turn 是一次推理，Event 才是系统
--   推进的基本单位。」任何用户输入、成员发言、工具结果、任务状态变化、打断请求，都先成为一条
--   事件再进入运行时；调度器据事件判断影响谁、哪些任务要暂停 / 取消 / 恢复 / 重新调度。
--
--   与 roundtable_topic_event（话题内的状态跃迁正本）互补：
--   本表记「系统层面发生了什么（用户说了话、工具跑了、任务完成了、有人请求打断）」，
--   后者记「圆桌状态机怎么跃迁的」；两者靠 correlation_id / task_id 关联。
--   event_id 上有唯一约束，重复投递不产生第二条（幂等）。
CREATE TABLE IF NOT EXISTS runtime_event (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id       TEXT NOT NULL,               -- 全局幂等键（UUID）
  session_id     TEXT NOT NULL,               -- 归属会话（hub_session.id）
  type           TEXT NOT NULL,               -- RuntimeEventType 名
  source         TEXT,                        -- user | <agent.id> | tool | runtime
  target         TEXT,                        -- 定向成员（agent.id）；NULL=广播
  task_id        TEXT,                        -- 关联任务（可空）
  priority       INTEGER NOT NULL DEFAULT 0,  -- 排序 / 抢占依据（越大越优先）
  correlation_id TEXT,                        -- 关联 id：runId / topicId
  payload        TEXT NOT NULL DEFAULT '{}',  -- JSON（camelCase）
  created_at     INTEGER NOT NULL,
  UNIQUE (event_id)
);
CREATE INDEX IF NOT EXISTS idx_runtime_event_session ON runtime_event(session_id, id);
CREATE INDEX IF NOT EXISTS idx_runtime_event_task ON runtime_event(task_id, id);

-- ---------------------------------------------------------------------------
-- 工具执行生命周期（设计一 §五-2）：工具调用要有独立的执行记录与生命周期。
--
--   「取消生成，不等于取消工具副作用」：模型停止生成可以尽力中断，但若它已经发起了一次有副作用的
--   调用（发邮件、删文件、部署），仅靠取消模型请求保证不了操作停止。于是每一次工具调用都落一行：
--   status = running（尚未开始 / 正在执行）→ done | failed | cancelled | uncertain（结果不确定）。
--   对结果不确定的调用，应据本记录先查询执行状态，不能盲目重试；幂等键 idem_key 供重试判重。
--
--   注意：M0 不实现所有外部副作用的自动补偿（非前提），本表只保证「调过什么、到哪一步、成没成」
--   可查、可审计。
CREATE TABLE IF NOT EXISTS tool_execution (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT,                           -- 归属会话
  task_id     TEXT,                           -- 归属任务（同一次发言执行）
  tool        TEXT NOT NULL,                  -- 工具名
  args        TEXT,                           -- 调用参数（原文，可能已截断）
  idem_key    TEXT,                           -- 幂等键（重试判重）
  status      TEXT NOT NULL,                  -- running | done | failed | cancelled | uncertain
  cancellable INTEGER NOT NULL DEFAULT 0,     -- 是否支持安全取消（1=是）
  result      TEXT,                           -- 结果摘要（截断）
  error       TEXT,                           -- 失败原因
  started_at  INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tool_execution_session ON tool_execution(session_id, id);
CREATE INDEX IF NOT EXISTS idx_tool_execution_idem ON tool_execution(idem_key);

-- ---------------------------------------------------------------------------
-- 圆桌共享任务区（roundtable_todo）：讨论态与执行态之间的「任务板」。
--
--   与 chat_todo（单会话、全量覆盖、写者自用）的区别：这是<b>会话级共享板</b>——同一张桌子上的
--   所有成员都能看到、都能增删改；每一项可指定负责人（owner_peer），未指定即公共。
--
--   闭环：讨论中成员依据彼此的信息往任务板加条目 → 讨论结论「该开工了」→ 各成员的核心线程
--   按序完成自己名下的条目（每一项 = 一次 Agent 执行 / Turn）→ 途中遇阻则发起讨论，
--   全桌据此动态增删改任务板 → 再开工。任务板是这条全链路 loop 的共享状态。
CREATE TABLE IF NOT EXISTS roundtable_todo (
  id          TEXT PRIMARY KEY,             -- uuid
  session_id  TEXT NOT NULL,                -- 归属会话（hub_session.id）
  topic_id    TEXT,                         -- 由哪次讨论产生（可空）
  owner_peer  TEXT,                         -- 负责的成员（agent.id）；NULL=公共 / 未分配
  title       TEXT NOT NULL,                -- 这一步要做什么（一句祈使句）
  detail      TEXT,                         -- 补充说明 / 验收标准
  depends_on  TEXT,                         -- 前置依赖：TODO id 的 JSON 数组（全部 done/dropped 后本条才可领）
  priority    INTEGER NOT NULL DEFAULT 0,   -- 调度优先级（越大越先领）：人的「暂时跳过」压最低、「立刻检查」拉最高
  status      TEXT NOT NULL,                -- pending | doing | blocked | done | dropped
  created_by  TEXT,                         -- 谁开的这条（agent.id / user）
  result      TEXT,                         -- 完成结论（status=done）
  block_reason TEXT,                        -- 受阻原因（status=blocked）
  block_with  TEXT,                         -- 受阻时想定向找谁沟通（agent.id）；NULL=全桌一起议
  seq         INTEGER NOT NULL,             -- 会话内顺序
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_roundtable_todo_session ON roundtable_todo(session_id, seq);
CREATE INDEX IF NOT EXISTS idx_roundtable_todo_owner ON roundtable_todo(session_id, owner_peer, status);

-- ---------------------------------------------------------------------------
-- 执行态留痕（roundtable_work_run）：每做一条 TODO 就是一次执行，留一条它的过程。
--
--   为什么要它：执行态的思考链此前根本没落库（讨论发言有 chat_message 兜着，执行没有），
--   于是「点击一条任务看不到 Agent 在想什么」——它卡在死循环里也无人知晓，代价是真金白银。
--   本表把每次执行的过程（思考链 / 过程分段 / 工具往返 / 当次请求体）完整留痕，
--   供执行态页面点开某条任务时回放「它到底在干嘛」。
--
--   status：running（正在跑）→ done | failed | blocked。
CREATE TABLE IF NOT EXISTS roundtable_work_run (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  todo_id     TEXT NOT NULL,                -- 哪一条待办
  session_id  TEXT NOT NULL,                -- 归属会话
  peer_id     TEXT,                         -- 执行者（agent.id）
  peer_name   TEXT,                         -- 执行者展示名（成员被删后历史仍可读）
  status      TEXT NOT NULL,                -- running | done | failed | blocked
  output      TEXT,                          -- 最终回答 / 结论
  reasoning   TEXT,                          -- 思考链纯文本
  trace       TEXT,                          -- 过程分段 JSON（前端分阶段展示）
  tool_rounds TEXT,                          -- 工具往返原生消息 JSON
  context     TEXT,                          -- 当次请求体快照（它当时看到了什么）
  error       TEXT,                          -- 失败 / 受阻原因
  started_at  INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_work_run_todo ON roundtable_work_run(todo_id, id);
CREATE INDEX IF NOT EXISTS idx_work_run_session ON roundtable_work_run(session_id, id);

-- Explicit session continuation grants; ordinary memory visibility remains unchanged.
CREATE TABLE IF NOT EXISTS session_continuation (
    target_session_id TEXT NOT NULL,
    source_session_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'granted',
    PRIMARY KEY (target_session_id, source_session_id, agent_id)
);

-- Collaboration issues reuse multi-agent sessions for their isolated message history.
CREATE TABLE IF NOT EXISTS collaboration_issue (
  id TEXT PRIMARY KEY,
  parent_session_id TEXT NOT NULL REFERENCES hub_session(id),
  session_id TEXT NOT NULL UNIQUE REFERENCES hub_session(id),
  title TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','resolved')),
  conclusion TEXT,
  message_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  resolved_at INTEGER,
  CHECK(parent_session_id <> session_id)
);
CREATE INDEX IF NOT EXISTS idx_collaboration_issue_parent ON collaboration_issue(parent_session_id);
CREATE TABLE IF NOT EXISTS collaboration_project_member (
  project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  agent_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY(project_id,agent_id)
);

CREATE TABLE IF NOT EXISTS project_proposal (
    session_id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    args_json TEXT NOT NULL,
    user_message_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
-- TTS profiles and per-agent selection (empty selection inherits the default).
CREATE TABLE IF NOT EXISTS deepseek_web_config (
    id INTEGER PRIMARY KEY CHECK(id=1), base_url TEXT NOT NULL, api_key TEXT NOT NULL,
    fast_enabled INTEGER NOT NULL DEFAULT 0, rag_enabled INTEGER NOT NULL DEFAULT 0,
    fetch_enabled INTEGER NOT NULL DEFAULT 1, result_count INTEGER NOT NULL DEFAULT 5,
    timeout_seconds INTEGER NOT NULL DEFAULT 15
);

CREATE TABLE IF NOT EXISTS tts_profile (id TEXT PRIMARY KEY, config TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tts_endpoint (id TEXT PRIMARY KEY, config TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tts_selection (agent_id TEXT PRIMARY KEY, profile_id TEXT);

-- ComfyUI：可复用端点、模型工作流档案与持久化生成图片。
CREATE TABLE IF NOT EXISTS comfyui_endpoint (id TEXT PRIMARY KEY, config TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS comfyui_profile (id TEXT PRIMARY KEY, config TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS comfyui_setting (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS comfyui_generation (
    id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, prompt TEXT NOT NULL, prompt_id TEXT,
    status TEXT NOT NULL, error TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS comfyui_image (
    id TEXT PRIMARY KEY, generation_id TEXT NOT NULL REFERENCES comfyui_generation(id),
    media_type TEXT NOT NULL, content BLOB NOT NULL
);

-- Bounded autonomous collaboration and durable links from issues back to work.
CREATE TABLE IF NOT EXISTS roundtable_progress (
    session_id TEXT PRIMARY KEY REFERENCES hub_session(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'active', steps INTEGER NOT NULL DEFAULT 0, reason TEXT
);
CREATE TABLE IF NOT EXISTS roundtable_attempt (
    todo_id TEXT PRIMARY KEY REFERENCES roundtable_todo(id) ON DELETE CASCADE,
    attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS collaboration_issue_work (
    issue_id TEXT PRIMARY KEY REFERENCES collaboration_issue(id) ON DELETE CASCADE,
    todo_id TEXT NOT NULL REFERENCES roundtable_todo(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS collaboration_plan (
    session_id TEXT PRIMARY KEY REFERENCES hub_session(id) ON DELETE CASCADE,
    coordinator_id TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    phase TEXT NOT NULL DEFAULT 'planning',
    criteria TEXT,
    decision TEXT,
    evidence TEXT,
    todo_id TEXT,
    executor_id TEXT,
    reviewer_id TEXT
);
CREATE TABLE IF NOT EXISTS project_workspace (
    project_id TEXT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    repository_id TEXT NOT NULL REFERENCES coding_repository(id) ON DELETE RESTRICT
);
-- Explicit controller-owned wave. One Agent profile can execute several isolated TODOs.
CREATE TABLE IF NOT EXISTS collaboration_assignment (
    session_id TEXT NOT NULL REFERENCES collaboration_plan(session_id) ON DELETE CASCADE,
    todo_id TEXT NOT NULL REFERENCES roundtable_todo(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    executor_id TEXT NOT NULL, reviewer_id TEXT NOT NULL,
    phase TEXT NOT NULL, decision TEXT NOT NULL,
    PRIMARY KEY(session_id,todo_id)
);
-- Preserve an in-flight single assignment when upgrading.
INSERT OR IGNORE INTO collaboration_assignment(session_id,todo_id,version,executor_id,reviewer_id,phase,decision)
SELECT session_id,todo_id,version,executor_id,reviewer_id,phase,COALESCE(decision,'') FROM collaboration_plan
WHERE phase IN ('executing','checking') AND todo_id IS NOT NULL AND executor_id IS NOT NULL AND reviewer_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS collaboration_scope (
    session_id TEXT PRIMARY KEY REFERENCES hub_session(id) ON DELETE CASCADE,
    deliverables TEXT NOT NULL, exclusions TEXT NOT NULL, baseline TEXT NOT NULL, roadmap TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS collaboration_task_archive (
    session_id TEXT PRIMARY KEY REFERENCES hub_session(id) ON DELETE CASCADE,
    archived_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS collaboration_scope_review (
    session_id TEXT NOT NULL REFERENCES hub_session(id) ON DELETE CASCADE,
    digest TEXT NOT NULL, allowed INTEGER NOT NULL, reason TEXT NOT NULL,
    PRIMARY KEY(session_id,digest)
);
CREATE TABLE IF NOT EXISTS collaboration_coding_run (
    -- Coding owns run lifetime; keep session cleanup independent of the optional coding schema.
    run_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES hub_session(id) ON DELETE CASCADE,
    todo_id TEXT NOT NULL REFERENCES roundtable_todo(id) ON DELETE CASCADE,
    executor_id TEXT NOT NULL, assignment_version INTEGER NOT NULL,
    reviewer_id TEXT, reviewed_hash TEXT, review_evidence TEXT, review_version INTEGER
);
CREATE TABLE IF NOT EXISTS collaboration_task_quality (
 todo_id TEXT PRIMARY KEY REFERENCES roundtable_todo(id) ON DELETE CASCADE,
 contract TEXT, findings TEXT, diagnosis TEXT, updated_at INTEGER NOT NULL
);

-- Project documents outlive their source sessions. Every update appends an immutable revision.
CREATE TABLE IF NOT EXISTS project_document (
 project_id TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
 doc_key TEXT NOT NULL, version INTEGER NOT NULL,
 kind TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL,
 graph_claim_id INTEGER REFERENCES graph_claim(id),
 retired INTEGER NOT NULL DEFAULT 0,
 source_session_id TEXT, source_title TEXT, author TEXT NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(project_id,doc_key,version)
);
CREATE INDEX IF NOT EXISTS idx_project_document_updated ON project_document(project_id,updated_at DESC);

-- Preserve existing confirmed deliveries without inventing a project summary or changing edited documents.
INSERT OR IGNORE INTO project_document(project_id,doc_key,version,kind,title,content,source_session_id,source_title,author,updated_at)
SELECT s.project_id,'delivery-'||s.id||'-'||c.version,1,'delivery','交付 · '||COALESCE(s.title,'未命名会话'),
 '# '||COALESCE(s.title,'未命名会话')||char(10)||char(10)||'## 交付与验证'||char(10)||c.evidence
 ||char(10)||char(10)||'## 本次成果'||char(10)||COALESCE(sc.deliverables,'历史记录未填写')
 ||char(10)||char(10)||'## 范围外事项与限制'||char(10)||COALESCE(sc.exclusions,'历史记录未填写')
 ||char(10)||char(10)||'## 后续建议（尚未执行）'||char(10)||COALESCE(sc.roadmap,'历史记录未填写')
 ||char(10)||char(10)||'## 工作结果'||char(10)||COALESCE((SELECT group_concat(t.title||' ['||t.status||'] '||COALESCE(t.result,'未记录结果'),char(10)||char(10)) FROM roundtable_todo t WHERE t.session_id=s.id),'未记录工作结果'),
 s.id,s.title,c.coordinator_id,s.updated_at
FROM collaboration_plan c JOIN hub_session s ON s.id=c.session_id LEFT JOIN collaboration_scope sc ON sc.session_id=c.session_id
WHERE s.project_id IS NOT NULL AND c.phase='completed' AND c.evidence IS NOT NULL;
CREATE TABLE IF NOT EXISTS economy_report (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL, kind TEXT NOT NULL,
    created_at INTEGER NOT NULL, body TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_economy_report_owner ON economy_report(owner,kind,created_at DESC);

-- Unified knowledge graph: graph_claim is the sole statement body and governance record.
-- Legacy entities/relations are retained for migration only; runtime reads these graph tables.
CREATE TABLE IF NOT EXISTS graph_entity (
  id TEXT PRIMARY KEY, namespace TEXT NOT NULL, identity_key TEXT NOT NULL, type TEXT NOT NULL, types TEXT,
  name TEXT NOT NULL, aliases TEXT, summary TEXT, meta TEXT,
  metadata_claim_id INTEGER REFERENCES graph_claim(id) ON DELETE SET NULL,
  retired INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER,
  UNIQUE(namespace, identity_key)
);
CREATE INDEX IF NOT EXISTS idx_graph_entity_namespace ON graph_entity(namespace,name);
CREATE TABLE IF NOT EXISTS graph_relation (
  id TEXT PRIMARY KEY, claim_id INTEGER NOT NULL REFERENCES graph_claim(id) ON DELETE CASCADE,
  subject_id TEXT NOT NULL REFERENCES graph_entity(id), predicate TEXT NOT NULL,
  object_id TEXT NOT NULL REFERENCES graph_entity(id), props TEXT,
  source_chunk INTEGER, source_claim INTEGER REFERENCES graph_claim(id) ON DELETE SET NULL,
  UNIQUE(claim_id,subject_id,predicate,object_id)
);
CREATE INDEX IF NOT EXISTS idx_graph_relation_subject ON graph_relation(subject_id,claim_id);
CREATE INDEX IF NOT EXISTS idx_graph_relation_object ON graph_relation(object_id,claim_id);
CREATE INDEX IF NOT EXISTS idx_graph_relation_claim ON graph_relation(claim_id);
CREATE TABLE IF NOT EXISTS graph_chunk_entity (
  chunk_id INTEGER NOT NULL REFERENCES chunks(id), entity_id TEXT NOT NULL REFERENCES graph_entity(id),
  PRIMARY KEY(chunk_id,entity_id)
);
CREATE TABLE IF NOT EXISTS graph_entity_embedding (
  id TEXT PRIMARY KEY REFERENCES graph_entity(id), model TEXT NOT NULL, dim INTEGER NOT NULL,
  vec BLOB NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS graph_episode (
  id TEXT PRIMARY KEY, source_ref TEXT NOT NULL, content_hash TEXT NOT NULL,
  payload TEXT NOT NULL, exec_json TEXT, observed_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'active', UNIQUE(source_ref,content_hash)
);
CREATE INDEX IF NOT EXISTS idx_graph_episode_source ON graph_episode(source_ref,status);
CREATE TABLE IF NOT EXISTS graph_support (
  claim_id INTEGER NOT NULL REFERENCES graph_claim(id) ON DELETE CASCADE,
  episode_id TEXT NOT NULL REFERENCES graph_episode(id), stance TEXT NOT NULL DEFAULT 'supports',
  PRIMARY KEY(claim_id,episode_id)
);
CREATE INDEX IF NOT EXISTS idx_graph_support_episode ON graph_support(episode_id,claim_id);
CREATE TABLE IF NOT EXISTS graph_ingest_job (
  id TEXT PRIMARY KEY, group_key TEXT NOT NULL, exec_json TEXT NOT NULL,
  episode_id TEXT NOT NULL REFERENCES graph_episode(id), source_refs TEXT,
  explicit INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
  next_at INTEGER NOT NULL, lease_until INTEGER, lease_token TEXT, created_at INTEGER NOT NULL,
  last_error TEXT
);
CREATE INDEX IF NOT EXISTS idx_graph_ingest_due ON graph_ingest_job(state,next_at,group_key);
CREATE VIRTUAL TABLE IF NOT EXISTS graph_claim_fts USING fts5(title,body,tokenize='trigram');
