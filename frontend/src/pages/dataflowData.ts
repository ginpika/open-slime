/**
 * 「数据流向」图的数据源（关于页 · 数据流向 Tab 消费）。
 *
 * 图形态：自顶向下逐层生长的树状 DAG——严格说系统是多入口汇聚的有向无环图
 * （多个 Controller 共享同一 Store），但按「数据产生的层次」分层后，视觉上即一棵向下生长的树。
 *
 * 节点 = 一个明确的源码文件（类），hover 圆点可见：
 *   why  —— 为什么把这些代码抽象成这个类（设计含义）
 *   flow —— 数据从这里流向哪里（业务流向）
 * 边 = 调用/数据流动方向（自上而下）；store 类到表节点的边即「落库」。
 *
 * 后端图覆盖 cc.ginpika.minis 全部源文件；前端图覆盖 frontend/src 全部源文件。
 * 说明：DocumentStore 内部调用 Chunker、ChunkStore/GraphStore 内部使用 VecMath 等
 * 少量「向上回指」的协作不画边（会破坏自顶向下的流势），在节点 why/flow 文本中说明。
 */

/** 节点在架构中的角色，决定圆点配色与徽章文案 */
export type FlowKind =
  | 'root'
  | 'config'
  | 'sso'
  | 'controller'
  | 'service'
  | 'hub'
  | 'llm'
  | 'store'
  | 'table'
  | 'dto'
  | 'page'
  | 'component'
  | 'api'
  | 'theme'

/** 图节点：一个类文件 */
export type GraphNode = {
  id: string
  /** 类名 / 文件名（圆点下方标签） */
  name: string
  kind: FlowKind
  /** 所在层（0 = 最上层入口，越大越靠近存储） */
  layer: number
  /** 设计含义：为什么把这些代码、这些功能抽象成这个类 */
  why: string
  /** 数据流向：数据从哪里来、经它做什么、流向哪里 */
  flow?: string
  /** 落点说明（表/文件/外部 API 节点或直接落库的类） */
  sink?: string
}

/** 图边：数据/调用流向，from → to */
export type GraphEdge = { from: string; to: string }

/** 一张完整的流向图 */
export type FlowGraph = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** 各层的语义标签（渲染在图左侧） */
  layerLabels: Record<number, string>
}

/* ------------------------------------------------------------------ */
/* 后端：请求 → 门禁 → Controller → 编排 → 算法/模型 → Store → 表     */
/* ------------------------------------------------------------------ */

export const BACKEND_GRAPH: FlowGraph = {
  layerLabels: {
    0: '输入与组合根',
    1: '接入层',
    2: '编排层',
    3: '算法与模型',
    4: '存储层',
    5: '数据落点',
  },
  nodes: [
    /* ---- L0 输入与组合根 ---- */
    {
      id: 'http',
      name: 'HTTP /api/*',
      kind: 'root',
      layer: 0,
      why: '系统唯一的输入边界：一切数据都以浏览器的 fetch / SSE 请求的形式从这里进入，后端不开放任何其他入口。',
      flow: '前端 api/client.ts 发出的每一个请求，先经过门禁，再分发到对应 Controller。',
    },
    {
      id: 'hostid',
      name: 'hostid SSO',
      kind: 'root',
      layer: 0,
      why: '外部单点登录服务（独立部署）：身份不归本系统管，本系统只认它签发的 cookie 会话。',
      flow: '向 SsoService 提供 /api/sso/session 校验与 /api/sso/logout 撤销两个远端点。',
    },
    {
      id: 'app',
      name: 'MinisApplication',
      kind: 'config',
      layer: 0,
      why: '组合根：一行 main() 把配置绑定、组件扫描、拦截器装配成一个可运行的整体——它自己不包含任何业务逻辑，只负责「让合适的类在启动时相遇」。',
      flow: '启动时触发属性绑定（各 Props）与库结构迁移（各 Migrator），此后退居幕后。',
    },

    /* ---- L1 接入层：Controller 门禁 + 配置 ---- */
    {
      id: 'ssoIc',
      name: 'SsoInterceptor',
      kind: 'sso',
      layer: 1,
      why: '门卫模式的统一实现：与其让十个 Controller 各自判断「你是谁、能不能进来」，不如在 MVC 之前拦一道，把认证从业务中彻底剥离。',
      flow: '每个请求先经 preHandle：校验 cookie → 注入 SsoUser；失败时 API 回 401{loginUrl}、页面 302 跳登录。放行的请求继续流向 Controller。',
    },
    { id: 'webCfg', name: 'WebConfig', kind: 'config', layer: 1, why: 'MVC 的接线板：唯一职责是把 SsoInterceptor 注册到拦截链并圈出免登录白名单（健康检查、登出、静态资源）。', flow: '不处理数据，只决定「请求走哪条门」。' },
    { id: 'propFast', name: 'FastProps', kind: 'config', layer: 1, why: '检索调参的纯配置载体：召回条数、图谱跳数、RRF 常量随环境可调，算法类因此保持零配置、可单测。', flow: '被 FastSearchService 与 GraphController 读取。' },
    { id: 'propLlm', name: 'LlmProps', kind: 'config', layer: 1, why: '模型接入的兜底配置：settings 页未配置任何模型时，.env 里这套值让系统「开箱即用」，是运行时解析链的最后一环。', flow: '被 SettingsService 合成内置项，喂给两个 LLM 客户端。' },
    { id: 'propSso', name: 'SsoProps', kind: 'config', layer: 1, why: '认证参数的单一来源：服务地址、cookie 名、缓存 TTL、管理员白名单——改配置不改代码。', flow: '被拦截器链与各管理端门禁读取。' },
    { id: 'modelTarget', name: 'ModelTarget', kind: 'config', layer: 1, why: '一次模型调用的「车票」：把 baseUrl/apiKey/model 三个值封装成不可变值对象，让「用哪个端点」成为可传递的数据而非散落的字段。', flow: 'SettingsService 解析产出，OpenAiChatClient / EmbeddingClient 消费。' },

    { id: 'icImport', name: 'ImportController', kind: 'controller', layer: 1, why: '把「粘贴一段 Markdown」这个最轻的入库动作独立成端点——导入者不需要理解分块、图谱或向量化，复杂性被挡在门后。', flow: 'POST /api/import：原文 + 标题直达 DocumentStore.saveArticle，一步落盘落库。' },
    { id: 'icAdmin', name: 'AdminController', kind: 'controller', layer: 1, why: '管理端的聚合门面：seed 灌入、图谱维护、文章发布这些「低频高危」操作收拢到一处，统一 requireAdmin 门禁。', flow: '分发到 Chunker / GraphStore / DocumentStore / EmbeddingClient 完成批量灌入。' },
    { id: 'icAnalyze', name: 'AnalyzeController', kind: 'controller', layer: 1, why: '分析工作流的 HTTP 翻译层：把三步工作流（提取→写图→增强）的每一步映射为细粒度端点，前端得以逐步推进、随时回显。', flow: '读端点直达 Store 回显；写端点转 AnalyzeService / DocSummaryService 编排。' },
    { id: 'icSearch', name: 'SearchController', kind: 'controller', layer: 1, why: '搜索栏的极简门面：一个 POST 端点背后是多路召回与融合——调用方永远只拿到「一次搜索」的心智模型。', flow: 'POST /api/search → FastSearchService.search（rag=true 时走 RAG 全量召回暗门）。' },
    { id: 'icChat', name: 'ChatController', kind: 'controller', layer: 1, why: '会话的传输层适配器：把同步问答、SSE 流式、会话 CRUD 的协议差异（超时、虚拟线程、断连检测）全部消化在这里，ChatService 只面对纯粹的领域调用。', flow: 'POST /api/chat[/stream|/stop] 与 /api/sessions* → ChatService。' },
    { id: 'icGraph', name: 'GraphController', kind: 'controller', layer: 1, why: '图谱浏览与编辑的只读优先入口：搜索实体、展开邻域、改属性——所有操作直达 GraphStore，不经过业务编排层，因为图本身就是存储的直接投影。', flow: 'GET 邻域/实体搜索、POST 实体/关系、PUT 属性 → GraphStore。' },
    { id: 'icSettings', name: 'SettingsController', kind: 'controller', layer: 1, why: '设置页的薄壳：全量视图 + 各域 CRUD 端点一一转调 SettingsService，保证「返回值永远是最新全量」的一致契约。', flow: '模型/人格/默认项/Embedding 的读写 → SettingsService → SettingsStore。' },
    { id: 'icArticle', name: 'ArticleController', kind: 'controller', layer: 1, why: '已发布文章的只读出口：与导入/分析完全隔离——读者永远不该感知到一篇文档曾经被切块和向量化过。', flow: 'GET /api/articles 分页与详情 → DocumentStore（详情回读 md 文件原文）。' },
    { id: 'icAuth', name: 'AuthController', kind: 'controller', layer: 1, why: '身份的两个最小问题：我是谁（me）、我走了（logout）——不 more，不 less。', flow: 'me 读拦截器注入的用户；logout → SsoService.revoke 撤销远端会话。' },
    { id: 'icHealth', name: 'HealthController', kind: 'controller', layer: 1, why: '进程的脉搏：给 Docker 与运维一个免登录的探针，回答「活着吗」。', flow: 'GET /api/health 返回 status/service/time，不触任何存储。' },

    /* ---- L2 编排层：业务服务 + DTO 契约 ---- */
    { id: 'ssoSvc', name: 'SsoService', kind: 'sso', layer: 2, why: '认证的防腐层：把 hostid 的 HTTP 协议、缓存 TTL、用户镜像落库封装在一个类里，系统其余部分只认「给我一个 SsoUser」。', flow: 'session(token)：缓存 miss 时请求 hostid → 命中后 UserStore.upsert 懒同步；revoke 时通知远端并清缓存。' },
    { id: 'ssoUser', name: 'SsoUser', kind: 'sso', layer: 3, why: '用户即值：用不可变模型取代散落的 JSON 字段，dev() 同时解决了本地开发的身份问题。', flow: '由 SsoService 解析产出，随 request attribute 流经所有门禁判断。' },
    { id: 'analyzeSvc', name: 'AnalyzeService', kind: 'service', layer: 2, why: '知识入库的心脏：把「LLM 抽取 → 消歧 → 写图 → 增强 → 向量化」这条最长的业务链编排成显式步骤，每一步可独立失败与重试。', flow: 'chunk 原文经 AiContext 组上下文 → OpenAiChatClient 抽三元组 → GraphStore 写图（新实体经 EmbeddingClient 向量化）→ ChunkStore 留档并最终启用。' },
    { id: 'chatSvc', name: 'ChatService', kind: 'service', layer: 2, why: '会话的编排中枢：消息落库、历史组装、可配轮数的工具循环（默认 30，「…」菜单可改）、流式回调——把「和大模型多轮博弈」这个状态机收敛到一个类，工具执行则分发出去。', flow: '用户输入 → HubSessionStore 定位会话 → ChatMessageStore 落 user 消息 → OpenAiChatClient 流式补全（工具轮调用 hub 算法与 GraphStore）→ assistant 消息（含 CoT/trace）落库。' },
    { id: 'docSum', name: 'DocSummaryService', kind: 'service', layer: 2, why: '「一篇文档是什么」的一次性回答：全文概述 + 类型判定只需一次 LLM 调用，独立成类让摘要可被工作流与重试复用。', flow: 'fullText → OpenAiChatClient → Summary(概述, docType) → documents 行。' },
    { id: 'fastSearch', name: 'FastSearchService', kind: 'hub', layer: 2, why: '搜索的指挥家：关键词、图谱（、向量）三路召回各自独立实现，在这里被 RRF 融合成一个排序——「多路召回、统一裁决」的架构缩影。', flow: 'QueryTerms 解析 → Keyword/Vector/Entity 三路 → ChunkStore/GraphStore 取证据 → RRF 合并 → 文档命中 + 实体卡片 + 图谱面板。' },
    { id: 'settingsSvc', name: 'SettingsService', kind: 'config', layer: 2, why: '配置的运行时仲裁者：SQLite 里用户配的模型与 .env 内置项在这里合成统一的解析链（指定 → 默认 → env 兜底），两个 LLM 客户端永远拿到确定的 ModelTarget。', flow: '各 CRUD → SettingsStore；resolveChatModel/resolveEmbedding 被聊天、分析、检索全链路消费。' },
    { id: 'chunker', name: 'Chunker', kind: 'service', layer: 2, why: '分块是检索质量的根：三级降级（标题节 → 段落 → LLM 语义切分）让 99% 的文档走确定性规则，只把最难的 1% 交给模型，且拼接保真校验兜底。', flow: 'markdown 全文 → 块列表（500~800 字符）→ 经 DocumentStore.rechunk 落 chunks 表与双 FTS。' },
    { id: 'aiCtx', name: 'AiContext', kind: 'service', layer: 2, why: 'AI 上下文的宪法（ai.core）：提示词由 PromptLibrary 定义并按顺序组合，工具由 ToolManager 按工具面汇总，本组件只负责上下文文本的渲染与组装——LLM 的「世界观」只有一个定义点。', flow: '被 AnalyzeService / ChatService / 潜意识调用：取提示词与 tools、把图谱/召回结果渲染回模型。' },

    { id: 'dtoImport', name: 'ImportDtos', kind: 'dto', layer: 2, why: '传输契约：请求体与响应体是 API 的类型化承诺，独立于领域逻辑演进。', flow: 'ImportRequest(content,title) → ImportResult(url,title)。' },
    { id: 'dtoAdmin', name: 'AdminDtos', kind: 'dto', layer: 2, why: '管理端契约：灌入、实体、关系、文章发布四组请求/响应的聚合定义。', flow: 'Ingest/Entity/Relation/ArticlePublish 的 Request 与 Result 族。' },
    { id: 'dtoAnalyze', name: 'AnalyzeDtos', kind: 'dto', layer: 2, why: '工作流契约：三步流程中最大的 DTO 族——三元组、消歧评估、提取回显、预案提交，每一步的形状都被显式定义。', flow: 'Triple/TripleEval/Disambiguate*/ChunkCommit*/ChunkSubmit* 等往返于前端与 AnalyzeService。' },
    { id: 'dtoSearch', name: 'SearchDtos', kind: 'dto', layer: 2, why: '搜索契约：Hit/Graph/Node/Edge 同时服务搜索页与图谱页——两个页面共享同一套图结构语言。', flow: 'SearchRequest → SearchResponse{hits, graph}。' },
    { id: 'dtoChat', name: 'ChatDtos', kind: 'dto', layer: 2, why: '会话契约：消息、分段追踪（TraceSegment）、会话配置的形状定义。', flow: 'ChatRequest/Reply、SessionItem/Config、Rewind/Stop 族。' },
    { id: 'dtoSettings', name: 'SettingsDtos', kind: 'dto', layer: 2, why: '设置契约：模型/Embedding/默认项的视图与请求体，SettingsView 全量覆盖式返回。', flow: 'ModelView/EmbeddingView/RerankView/SettingsView 族；提示词见 PromptDtos（场景/提示词位/版本）。' },
    { id: 'dtoArticle', name: 'ArticleDtos', kind: 'dto', layer: 2, why: '文章契约：读者视角的最小形状——列表 Item、分页 Page、详情 Detail。', flow: 'GET /api/articles 的响应体。' },

    /* ---- L3 算法与模型 ---- */
    { id: 'qTerms', name: 'QueryTerms', kind: 'hub', layer: 3, why: '查询理解的最低成本实现：不引分词器，用「英文 token + 中文连续串 + 短语窗口」三规则覆盖 90% 场景——简单先于聪明。', flow: '原始查询串 → Parsed(enTokens, cnRuns, cnPhrases)，供关键词召回与实体富化共用。' },
    { id: 'kwRecall', name: 'KeywordRecaller', kind: 'hub', layer: 3, why: '确定性召回：英文走 unicode61、中文走 trigram 的两张 FTS5 表，BM25 排序——不依赖任何模型即可命中。', flow: 'Parsed → chunks_fts_meta（OR）/ chunks_fts_cn（AND）→ chunk id 列表。' },
    { id: 'vecRecall', name: 'VectorRecaller', kind: 'hub', layer: 3, why: '语义召回（M0 暴力版）：同模型全量余弦 top-k，先让语义这条路通，再谈索引优化。', flow: '查询向量 → ChunkStore.topByCosine 扫 chunk_embedding → chunk id 列表。' },
    { id: 'entEnrich', name: 'EntityEnricher', kind: 'hub', layer: 3, why: '图谱路的召回器：别名命中锚点实体后 BFS 展开，把「知识结构」作为检索信号——这是关键词与向量之外的第三条路。', flow: 'Parsed → GraphStore.lookup 命中 → 邻域展开 → 实体/边/关联 chunk 证据。' },
    { id: 'graphExp', name: 'GraphExplorer', kind: 'hub', layer: 3, why: '子图探索（HippoRAG 2 廉价迁移）：Personalized PageRank 给「和种子实体最相关」一个数学定义，LLM 只需读懂结果。', flow: 'seed 实体 → 局部子图 → PPR 幂迭代 → 高分周边实体/边/关联 chunk。' },
    { id: 'pathFind', name: 'GraphPathFinder', kind: 'hub', layer: 3, why: '两点间的解释器：Yen 算法求前 k 条最短路径，回答「A 和 B 是怎么联系起来的」——可解释性是问答的信任基础。', flow: '两实体 id → 逐步带谓词/方向的 Path 列表 → 注入对话上下文。' },
    { id: 'vecMath', name: 'VecMath', kind: 'hub', layer: 3, why: '向量即字节：float[]↔BLOB 编码与余弦相似度收敛为无状态工具——chunk 向量与实体向量共用同一套几何语言（ChunkStore/GraphStore 内部亦调用）。', flow: '被 VectorRecaller 与两个 Store 的向量读写使用。' },
    { id: 'openai', name: 'OpenAiChatClient', kind: 'llm', layer: 3, why: '唯一的聊天模型出口：强制流式 SSE 再聚合为等价 message，所有调用方（摘要/分块/抽取/对话/测试）拿到统一抽象，换供应商只改这里。', flow: 'messages + tools → POST {base}/chat/completions → Completion（含 tool_calls 合并）→ 外部 LLM API。' },
    { id: 'embed', name: 'EmbeddingClient', kind: 'llm', layer: 3, why: '唯一的向量化出口：与聊天客户端对称的设计——「未配置则静默跳过」让向量能力成为系统的可选器官而非硬依赖。', flow: '文本 → POST {base}/embeddings → float[] → 写 chunk_embedding / entity_embedding。' },
    { id: 'modelMeta', name: 'ModelMetaClient', kind: 'llm', layer: 3, why: '模型元信息的探测器：GET /models 后多字段名兜底解析上下文窗口，让设置页的「256K」标签自动出现而非手填。', flow: '端点三元组 → context_window 数值（取不到为 null）→ 设置页。' },

    /* ---- L4 存储层 ---- */
    { id: 'docStore', name: 'DocumentStore', kind: 'store', layer: 4, why: '文档的唯一事实源出口：无论来自导入、管理端还是分析工作流，「一篇文档如何创建、重切、落盘」只有一份代码；重切时旧块连带关系/向量一并清理，不留悬空数据。', flow: 'saveArticle：md 文件 + documents 行 → rechunk（内部调 Chunker 分块后经 ChunkStore 落库）。' },
    { id: 'chunkStore', name: 'ChunkStore', kind: 'store', layer: 4, why: '知识块的全生命周期管理：内容、双 FTS 索引、向量、状态、预案留档——「按 uuid 整块替换、删除时五表连清」保证块数据的原子一致。', flow: 'replace/saveEmbedding/updatePlan/updateStatus 写入；metaMatch/cnMatch/topByCosine 供召回。' },
    { id: 'graphStore', name: 'GraphStore', kind: 'store', layer: 4, why: '图谱的读写全集：SHA-256 稳定 id 让「同一实体」天然幂等，source_chunk 让每条关系可溯源到产出它的原文块——知识图谱因而是可审计的。', flow: 'upsert 实体/关系、linkEntityChunk、留档提取记录；lookup/hubs/neighborsOf 供检索与图谱页。' },
    { id: 'chatMsgStore', name: 'ChatMessageStore', kind: 'store', layer: 4, why: '问答的正本：content 之外连 reasoning/trace/tool_rounds 一并落库——对话不只是答案，更是可回放的过程。', flow: 'insert 落 user/assistant 消息；listBySession 组装历史；deleteFrom 支撑回退。' },
    { id: 'hubStore', name: 'HubSessionStore', kind: 'store', layer: 4, why: '会话的最小档案：标题、时间与「…」菜单的会话级配置（模型/人格/CoT/回灌/工具轮数上限），touch 让最近使用自然浮顶。', flow: 'insert/touch/updateConfig/listAll/delete。' },
    { id: 'settingsStore', name: 'SettingsStore', kind: 'store', layer: 4, why: '用户偏好的持久化：模型、人格、默认项、Embedding 四张表，默认项与 Embedding 用单行 id=1 upsert——「配置也是一种数据」。', flow: '模型/人格 CRUD 与单行读写；内置 env 项不入库，由 SettingsService 运行时合成。' },
    { id: 'userStore', name: 'UserStore', kind: 'store', layer: 4, why: '用户镜像：SSO 是身份的真相源，本地只留一份懒同步副本（保留旧头像），供审计与展示。', flow: 'SsoService 校验命中 → upsert app_user。' },
    { id: 'migChunkDoc', name: 'ChunkDocMigrator', kind: 'store', layer: 4, why: '库结构演进的执行者之一：chunks/documents/entities 补列，旧 articles 表整体合并进 documents 后 DROP——迁移即代码，幂等可重跑。', flow: '启动期执行：ALTER TABLE + 数据搬迁。' },
    { id: 'migChat', name: 'ChatMessageMigrator', kind: 'store', layer: 4, why: '为 chat_message 补 reasoning/trace/tool_rounds 列——当「过程回放」成为需求，迁移让旧数据无痛升级。', flow: '启动期执行，幂等。' },
    { id: 'migHub', name: 'HubSessionMigrator', kind: 'store', layer: 4, why: '会话表的结构重建：旧结构（含外部 yuni_session_id）事务性迁移为新结构，保 id/标题/时间。', flow: '启动期执行，幂等。' },
    { id: 'migLlm', name: 'LlmModelMigrator', kind: 'store', layer: 4, why: '为 llm_model 补 context_window 列——设置页的上下文窗口标签由此落地。', flow: '启动期执行，幂等。' },

    /* ---- L5 数据落点 ---- */
    { id: 'tDocuments', name: 'documents', kind: 'table', layer: 5, why: '统一文档模型：博客文章与分析文档同表（source 区分），url/summary/概述/类型直接挂行上。', sink: 'title, source(blog|doc), url, summary, contextualizing, doc_type' },
    { id: 'tMdFile', name: 'data/article/*.md', kind: 'table', layer: 5, why: '文件系统即原文冷存储：markdown 原文以文件落盘，重分块与文章详情都回读它——库存结构，文件存内容。', sink: 'data/article/{url}.md' },
    { id: 'tChunks', name: 'chunks', kind: 'table', layer: 5, why: '知识块正表：一切分析生命周期的载体。', sink: 'uuid, title, content, tags, meta, header, questions, doc_id, status' },
    { id: 'tFtsMeta', name: 'chunks_fts_meta', kind: 'table', layer: 5, why: 'FTS5 虚表（unicode61）：英文/代码/标签的 BM25 命中。', sink: 'rowid, title, tags, content' },
    { id: 'tFtsCn', name: 'chunks_fts_cn', kind: 'table', layer: 5, why: 'FTS5 虚表（trigram）：中文短语的 BM25 命中。', sink: 'rowid, content' },
    { id: 'tChunkEmb', name: 'chunk_embedding', kind: 'table', layer: 5, why: '块的语义坐标：增强提交时的预案全文向量。', sink: 'chunk_id, model, dim, vec(BLOB)' },
    { id: 'tEntities', name: 'entities', kind: 'table', layer: 5, why: '图谱节点：SHA-256 id + 唯一名 + 多类型/别名/KV 属性。', sink: 'id, type, types, name, aliases, summary, meta' },
    { id: 'tRelations', name: 'relations', kind: 'table', layer: 5, why: '图谱边：source_chunk 溯源列让每条知识都能回答「你从哪来」。', sink: 'id, subject_id, predicate, object_id, props, source_chunk' },
    { id: 'tChunkEntity', name: 'chunk_entity', kind: 'table', layer: 5, why: '块与实体的多对多桥：图谱命中可反查原文。', sink: 'chunk_id, entity_id' },
    { id: 'tChunkExtraction', name: 'chunk_extraction', kind: 'table', layer: 5, why: '提取留档：重复提交覆盖写，二次进入自动回显。', sink: 'chunk_id, triples(JSON)' },
    { id: 'tEntityEmb', name: 'entity_embedding', kind: 'table', layer: 5, why: '实体的语义坐标：名称+摘要向量化，支撑语义回退的实体召回。', sink: 'id, model, dim, vec(BLOB)' },
    { id: 'tChatMsg', name: 'chat_message', kind: 'table', layer: 5, why: '问答正本：答案与思维链、工具轮同存。', sink: 'session_id, role, content, reasoning, trace, tool_rounds' },
    { id: 'tHubSession', name: 'hub_session', kind: 'table', layer: 5, why: '会话档案与「…」菜单配置。', sink: 'title, model_id, prompt_id, show_cot, context_trace' },
    { id: 'tLlmModel', name: 'llm_model', kind: 'table', layer: 5, why: '用户配置的模型端点（env 内置项不入库）。', sink: 'name, base_url, api_key, model, context_window' },
    { id: 'tPrompt', name: 'system_prompt', kind: 'table', layer: 5, why: '用户配置的聊天人格。', sink: 'name, content' },
    { id: 'tAppSetting', name: 'app_setting', kind: 'table', layer: 5, why: '单行默认项：默认模型与人格的选择。', sink: 'default_model_id, default_prompt_id' },
    { id: 'tEmbCfg', name: 'embedding_config', kind: 'table', layer: 5, why: '单行 Embedding 独立配置（复用模型端点或独立端点）。', sink: 'mode, llm_model_id, base_url, api_key, model' },
    { id: 'tAppUser', name: 'app_user', kind: 'table', layer: 5, why: 'SSO 用户的本地镜像。', sink: 'id, username, nickname, email, role, avatar_url' },
    { id: 'llmOut', name: '外部 LLM API', kind: 'root', layer: 5, why: '系统的最终输出口之一：一切生成与向量化都以 OpenAI 兼容协议流向外部端点——智能是租来的，数据是自己的。', flow: 'chat/completions 与 embeddings 的出站请求。' },
  ],
  edges: [
    /* 入口 → 接入层 */
    { from: 'http', to: 'ssoIc' },
    { from: 'http', to: 'icImport' },
    { from: 'http', to: 'icAdmin' },
    { from: 'http', to: 'icAnalyze' },
    { from: 'http', to: 'icSearch' },
    { from: 'http', to: 'icChat' },
    { from: 'http', to: 'icGraph' },
    { from: 'http', to: 'icSettings' },
    { from: 'http', to: 'icArticle' },
    { from: 'http', to: 'icAuth' },
    { from: 'http', to: 'icHealth' },
    { from: 'app', to: 'webCfg' },
    { from: 'app', to: 'propFast' },
    { from: 'app', to: 'propLlm' },
    { from: 'app', to: 'propSso' },
    { from: 'app', to: 'modelTarget' },

    /* 接入层 → 编排层 */
    { from: 'ssoIc', to: 'ssoSvc' },
    { from: 'hostid', to: 'ssoSvc' },
    { from: 'icAuth', to: 'ssoSvc' },
    { from: 'ssoSvc', to: 'ssoUser' },
    { from: 'icAnalyze', to: 'analyzeSvc' },
    { from: 'icAnalyze', to: 'docSum' },
    { from: 'icChat', to: 'chatSvc' },
    { from: 'icSearch', to: 'fastSearch' },
    { from: 'icSettings', to: 'settingsSvc' },
    { from: 'icAdmin', to: 'chunker' },

    /* Controller → DTO 契约 */
    { from: 'icImport', to: 'dtoImport' },
    { from: 'icAdmin', to: 'dtoAdmin' },
    { from: 'icAnalyze', to: 'dtoAnalyze' },
    { from: 'icSearch', to: 'dtoSearch' },
    { from: 'icGraph', to: 'dtoSearch' },
    { from: 'icChat', to: 'dtoChat' },
    { from: 'icSettings', to: 'dtoSettings' },
    { from: 'icArticle', to: 'dtoArticle' },

    /* 编排层横向协作与下沉 */
    { from: 'analyzeSvc', to: 'aiCtx' },
    { from: 'chatSvc', to: 'aiCtx' },
    { from: 'analyzeSvc', to: 'docSum' },
    { from: 'chatSvc', to: 'fastSearch' },
    { from: 'chatSvc', to: 'settingsSvc' },
    { from: 'fastSearch', to: 'qTerms' },
    { from: 'fastSearch', to: 'kwRecall' },
    { from: 'fastSearch', to: 'vecRecall' },
    { from: 'fastSearch', to: 'entEnrich' },
    { from: 'chatSvc', to: 'graphExp' },
    { from: 'chatSvc', to: 'pathFind' },
    { from: 'chunker', to: 'openai' },
    { from: 'docSum', to: 'openai' },
    { from: 'analyzeSvc', to: 'openai' },
    { from: 'chatSvc', to: 'openai' },
    { from: 'settingsSvc', to: 'openai' },
    { from: 'analyzeSvc', to: 'embed' },
    { from: 'chatSvc', to: 'embed' },
    { from: 'fastSearch', to: 'embed' },
    { from: 'icAdmin', to: 'embed' },
    { from: 'settingsSvc', to: 'modelMeta' },

    /* 编排 → 存储 */
    { from: 'icImport', to: 'docStore' },
    { from: 'icAdmin', to: 'docStore' },
    { from: 'icAdmin', to: 'graphStore' },
    { from: 'icAnalyze', to: 'docStore' },
    { from: 'icAnalyze', to: 'chunkStore' },
    { from: 'icAnalyze', to: 'graphStore' },
    { from: 'icGraph', to: 'graphStore' },
    { from: 'icArticle', to: 'docStore' },
    { from: 'ssoSvc', to: 'userStore' },
    { from: 'analyzeSvc', to: 'graphStore' },
    { from: 'analyzeSvc', to: 'chunkStore' },
    { from: 'analyzeSvc', to: 'docStore' },
    { from: 'chatSvc', to: 'graphStore' },
    { from: 'chatSvc', to: 'chunkStore' },
    { from: 'chatSvc', to: 'chatMsgStore' },
    { from: 'chatSvc', to: 'hubStore' },
    { from: 'fastSearch', to: 'chunkStore' },
    { from: 'fastSearch', to: 'graphStore' },
    { from: 'fastSearch', to: 'docStore' },
    { from: 'settingsSvc', to: 'settingsStore' },

    /* 存储 → 数据落点 */
    { from: 'docStore', to: 'tDocuments' },
    { from: 'docStore', to: 'tMdFile' },
    { from: 'chunkStore', to: 'tChunks' },
    { from: 'chunkStore', to: 'tFtsMeta' },
    { from: 'chunkStore', to: 'tFtsCn' },
    { from: 'chunkStore', to: 'tChunkEmb' },
    { from: 'graphStore', to: 'tEntities' },
    { from: 'graphStore', to: 'tRelations' },
    { from: 'graphStore', to: 'tChunkEntity' },
    { from: 'graphStore', to: 'tChunkExtraction' },
    { from: 'graphStore', to: 'tEntityEmb' },
    { from: 'chatMsgStore', to: 'tChatMsg' },
    { from: 'hubStore', to: 'tHubSession' },
    { from: 'settingsStore', to: 'tLlmModel' },
    { from: 'settingsStore', to: 'tPrompt' },
    { from: 'settingsStore', to: 'tAppSetting' },
    { from: 'settingsStore', to: 'tEmbCfg' },
    { from: 'userStore', to: 'tAppUser' },
    { from: 'openai', to: 'llmOut' },
    { from: 'embed', to: 'llmOut' },
    { from: 'modelMeta', to: 'llmOut' },
  ],
}

/* ------------------------------------------------------------------ */
/* 前端：用户 → main → App → 页面 → 组件/子流程 → client.ts → /api/*   */
/* ------------------------------------------------------------------ */

export const FRONTEND_GRAPH: FlowGraph = {
  layerLabels: {
    0: '用户与入口',
    1: '应用壳',
    2: '页面',
    3: '子流程 · 组件 · API 出口',
    4: '基础设施与落点',
  },
  nodes: [
    /* ---- L0 ---- */
    { id: 'user', name: '用户操作', kind: 'root', layer: 0, why: '一切的源头：键入、点击、滚动——前端的数据流由人的意图驱动，而非请求驱动。', flow: '操作经 hash 路由进入 App，触发页面事件，最终化为对 client.ts 的调用。' },
    { id: 'main', name: 'main.tsx', kind: 'root', layer: 0, why: '最小入口：仅做两件事——先把持久化的主题变量写进 :root（防闪烁），再把 <App/> 挂到根节点。', flow: 'initTheme()（同步，先于首帧）→ createRoot().render(<App/>)。' },

    /* ---- L1 ---- */
    { id: 'app', name: 'App.tsx', kind: 'page', layer: 1, why: '应用壳：自研 hash 路由（不引路由库）+ 全局唯一的数据获取（me()）——壳只负责「你在哪个页面、你是谁」。', flow: 'hashchange → 条件渲染页面；me() 拉用户（401 整页跳 SSO）；每页经 PageSlot 包裹（渲染版本扩展点）。' },
    { id: 'userMenu', name: 'UserMenu', kind: 'component', layer: 2, why: 'App 的内部组件：身份的出口——头像、昵称、登出，让认证结果可见。', flow: '读 App 的 user 状态；登出 → client.logout() → reload。' },

    /* ---- L2 页面 ---- */
    { id: 'pgHome', name: 'Home', kind: 'page', layer: 2, why: '系统的正门：检索/会话双模式共享一个搜索框——「找知识」和「问知识」本是一件事的两种问法。', flow: '会话：chatStream SSE 流式灌入（rAF 攒批渲染）；检索：跳转 #/search。' },
    { id: 'pgSearch', name: 'Search', kind: 'page', layer: 2, why: '结果页：只做一件事——把一次搜索的三类命中（文档/实体/块）诚实呈现，并给出到图谱/文章的去路。', flow: 'search(query, rag) → hits → ResultCard 逐条渲染。' },
    { id: 'pgGraph', name: 'Graph', kind: 'page', layer: 2, why: '图谱的驾驶舱：双渲染引擎（自研 SVG / ECharts）可切换、增量展开保持视角——探索感优先于完备感。', flow: 'graphEntities 取入口 → graphNeighbors 逐跳展开/合并；编辑直达后端。' },
    { id: 'pgArticles', name: 'Articles', kind: 'page', layer: 2, why: '读者视角的文档列表：分页算法独立成纯函数，首末页+窗口+省略号的经典权衡。', flow: 'articles(page) → ArticlePage。' },
    { id: 'pgArticleDetail', name: 'ArticleDetail', kind: 'page', layer: 2, why: '原文的忠实呈现：剥 front-matter、markdown 渲染走主题令牌、悬浮目录——阅读体验即产品。', flow: 'articleDetail(url) → ReactMarkdown 渲染 → DOM 提取目录。' },
    { id: 'pgImport', name: 'Import', kind: 'page', layer: 2, why: '入库的第一公里：自动识别 front-matter 标题，把「粘贴」变成「入库」的一步之遥。', flow: 'importArticle(content, title) → POST /api/import → 回显存储路径。' },
    { id: 'pgAnalyze', name: 'Analyze', kind: 'page', layer: 2, why: '三步工作流的容器与状态机：步骤条 + 文档折叠表 + 跨步骤 ctx 传递——「工作流只能逐步推进」的约束在这里成形。', flow: 'listDocs/listDocChunks 维护视图；携带 chunk+概述 ctx 进入子流程页。' },
    { id: 'pgSettings', name: 'Settings', kind: 'page', layer: 2, why: '配置的仪表盘：三区块（模型/人格/Embedding）+ 统一提交模式——成功后永远用后端返回的最新全量覆盖本地，杜绝双源不一致。', flow: 'getSettings + 各 CRUD → SettingsView 覆盖。' },
    { id: 'pgAbout', name: 'About（本页）', kind: 'page', layer: 2, why: '系统的自我讲述：业务流程给使用者，数据流向图给建设者——文档与代码同源演进。', flow: '纯静态，不请求后端。' },
    { id: 'themePanel', name: 'ThemePanel', kind: 'theme', layer: 2, why: '主题的 UI 入口：预设网格 + 自定义取色实时生效——所见即所得的调色台。', flow: '读写 theme store，消费 presets。' },

    /* ---- L3 子流程 · 组件 · API 出口 ---- */
    { id: 'exStep', name: 'ExtractStep', kind: 'page', layer: 3, why: '步骤二「知识提取」：三元组卡片、消歧标注、邻域弹窗——把 LLM 的不确定性包装成可勾选、可修正的确定操作。', flow: 'analyzeDocument 抽取 → disambiguateTriples 评估 → commitChunkTriples 写图。' },
    { id: 'enStep', name: 'EnhanceStep', kind: 'page', layer: 3, why: '步骤三「Chunk 增强」：前端组装 RAG 语义定位头（章节路径/位置/摘要/实体/时间），预案分段可编辑——检索质量在这一步被设计出来。', flow: 'genChunkSummary/Questions + 前端组装 → saveChunkPlan 留档 → submitChunkPlan 向量化启用。' },
    { id: 'client', name: 'api/client.ts', kind: 'api', layer: 3, why: '前端唯一的 HTTP 出口：所有请求、SSE 解析、401 跳转策略集中一处——网络层没有第二份实现，页面只面对类型安全的函数。', flow: 'requestJson/chatStream 封装 fetch → 全部 /api/* 端点 → 响应/流式增量回传页面 setState。' },
    { id: 'cpFloat', name: 'FloatWindow', kind: 'component', layer: 3, why: '可拖动悬浮窗：分析页、提取、增强、设置四处弹窗共用——「无遮罩可并行操作」是工作流页的交互基石。', flow: '纯 UI 容器，不触网。' },
    { id: 'cpCanvas', name: 'GraphCanvas', kind: 'component', layer: 3, why: '自研 SVG 力布局画布：pan/zoom/拖节点/双击展开，rAF 物理模拟——不依赖图表库的图谱底座。', flow: '读 Graph 页的 nodes/edges 状态渲染，事件回传选中/展开。' },
    { id: 'cpECharts', name: 'GraphECharts', kind: 'component', layer: 3, why: 'ECharts 渲染引擎（lazy 懒加载）：force 动态与确定性布局双模式，颜色从主题 store 取实际 hex——重型依赖被隔离在按需加载的分包里。', flow: '同 GraphCanvas 的输入输出契约，可无缝切换。' },
    { id: 'cpMini', name: 'GraphMini', kind: 'component', layer: 3, why: '迷你图谱预览：邻域弹窗里的一跳子图——轻量到不需要交互，只回答「它长什么样」。', flow: 'ExtractStep 实体弹窗的只读渲染。' },
    { id: 'cpProps', name: 'PropsEditor', kind: 'component', layer: 3, why: 'KV 属性编辑器：只读态按类型渲染、编辑态增删改——实体与关系的属性编辑共用一套心智。', flow: 'onSave(next) 由 Graph 页接住 → graphUpdate*Props。' },
    { id: 'cpResult', name: 'ResultCard', kind: 'component', layer: 3, why: '命中卡片：Entity/Chunk/Document 三类命中各有去路（图谱/文章/外链），高亮由 Mark 完成——搜索结果的原子单位。', flow: 'Search 页 map 渲染 hit。' },
    { id: 'cpSegment', name: 'SegmentEditor', kind: 'component', layer: 3, why: '分段文本编辑器：预览态点击分段就地变输入框——为「预案可修改」而生。', flow: 'EnhanceStep 的 segments 状态双向绑定。' },
    { id: 'cpToast', name: 'Toast', kind: 'component', layer: 3, why: '全局轻提示：错误 6s/成功 4.5s 自动消失——反馈的最高频载体，几乎每页消费。', flow: '各页面 setMessage 驱动。' },
    { id: 'cpLayout', name: 'graphLayout.ts', kind: 'component', layer: 3, why: '确定性力导向布局的纯函数库：斥力+弹簧+向心，静态布局=快进 300 轮——三个图谱组件共享同一套几何。', flow: '被 GraphCanvas（实时物理）、GraphMini/GraphECharts（静态排布）调用。' },
    { id: 'cpUi', name: 'ui.tsx', kind: 'component', layer: 3, why: 'UI 原语：五枚按钮样式函数 + 图标——设计系统的最小内核，样式即函数。', flow: '被半数页面与 ThemePanel 引用。' },

    /* ---- L4 基础设施与落点 ---- */
    { id: 'thStore', name: 'theme/store.ts', kind: 'theme', layer: 4, why: '全局唯一的前端 store：useSyncExternalStore + localStorage 持久化，无 Redux——主题的状态机（选择/草稿/外观档位）收敛于此。', flow: 'commit 时 resolveTokens → 写 :root → persist → 通知订阅者；监听系统深浅色。' },
    { id: 'thApply', name: 'theme/apply.ts', kind: 'theme', layer: 4, why: '令牌推导器：主/辅/点缀三色 → 18 个语义变量（含对比度选字、实体色相旋转）——主题引擎的纯函数核心。', flow: 'resolveTokens(ThemeDef) → applyVars 写 documentElement。' },
    { id: 'thColor', name: 'theme/color.ts', kind: 'theme', layer: 4, why: '零依赖色彩运算：hex/rgb/hsl 互转、mix、WCAG contrast——让「颜色也有一致的数学」。', flow: '被 apply/presets/GraphCanvas/ThemePanel 复用。' },
    { id: 'thPresets', name: 'theme/presets.ts', kind: 'theme', layer: 4, why: '内置预设 ×4：浅色 Mono/靛墨、深色 夜幕/荧光绿——开箱即用的审美下限。', flow: '供 ThemePanel 网格与默认值。' },
    { id: 'thTypes', name: 'theme/types.ts', kind: 'theme', layer: 4, why: '主题的类型契约：模式/颜色/状态/外观的形状定义——类型即文档。', flow: '被主题全家与 overrides 引用（纯类型，无运行时）。' },
    { id: 'thOverrides', name: 'theme/overrides.tsx', kind: 'theme', layer: 4, why: '页面渲染版本注册表：PageSlot 为每个路由预留可替换的实现位——当前为空，但扩展点已在。', flow: 'App 每个路由经 PageSlot 包裹，恒渲染 fallback。' },
    { id: 'sinkApi', name: '/api/* 后端', kind: 'root', layer: 4, why: '前端的最终输出口：所有数据跨越进程边界的地方——过了这条线，就进入后端的门禁与编排。', flow: 'client.ts 的全部 fetch 目的地。' },
    { id: 'sinkLs', name: 'localStorage', kind: 'table', layer: 4, why: '前端的持久化落点：minis.theme.v3 一键即全部本地状态——这个应用没有让浏览器存任何业务数据。', sink: 'minis.theme.v3' },
    { id: 'sinkRoot', name: ':root CSS 变量', kind: 'table', layer: 4, why: '主题的实时落点：18 个 --c-* 变量写入文档根，Tailwind @theme 映射为全站工具类——一次写入，处处生效。', sink: '--c-bg/--c-fg/--c-accent/…' },
  ],
  edges: [
    { from: 'user', to: 'main' },
    { from: 'main', to: 'app' },
    { from: 'main', to: 'thStore' },
    { from: 'app', to: 'userMenu' },
    { from: 'app', to: 'pgHome' },
    { from: 'app', to: 'pgSearch' },
    { from: 'app', to: 'pgGraph' },
    { from: 'app', to: 'pgArticles' },
    { from: 'app', to: 'pgArticleDetail' },
    { from: 'app', to: 'pgImport' },
    { from: 'app', to: 'pgAnalyze' },
    { from: 'app', to: 'pgSettings' },
    { from: 'app', to: 'pgAbout' },
    { from: 'app', to: 'themePanel' },
    { from: 'app', to: 'client' },
    { from: 'app', to: 'thOverrides' },

    /* 页面 → API 出口 */
    { from: 'pgHome', to: 'client' },
    { from: 'pgSearch', to: 'client' },
    { from: 'pgGraph', to: 'client' },
    { from: 'pgArticles', to: 'client' },
    { from: 'pgArticleDetail', to: 'client' },
    { from: 'pgImport', to: 'client' },
    { from: 'pgAnalyze', to: 'client' },
    { from: 'exStep', to: 'client' },
    { from: 'enStep', to: 'client' },
    { from: 'pgSettings', to: 'client' },
    { from: 'userMenu', to: 'client' },

    /* 工作流容器 → 子流程页 */
    { from: 'pgAnalyze', to: 'exStep' },
    { from: 'pgAnalyze', to: 'enStep' },

    /* 页面 → 组件 */
    { from: 'pgHome', to: 'cpToast' },
    { from: 'pgSearch', to: 'cpResult' },
    { from: 'pgSearch', to: 'cpToast' },
    { from: 'pgGraph', to: 'cpCanvas' },
    { from: 'pgGraph', to: 'cpECharts' },
    { from: 'pgGraph', to: 'cpProps' },
    { from: 'pgGraph', to: 'cpToast' },
    { from: 'pgGraph', to: 'cpUi' },
    { from: 'pgArticles', to: 'cpToast' },
    { from: 'pgArticleDetail', to: 'cpToast' },
    { from: 'pgImport', to: 'cpToast' },
    { from: 'pgImport', to: 'cpUi' },
    { from: 'pgAnalyze', to: 'cpFloat' },
    { from: 'pgAnalyze', to: 'cpToast' },
    { from: 'pgAnalyze', to: 'cpUi' },
    { from: 'exStep', to: 'cpFloat' },
    { from: 'exStep', to: 'cpMini' },
    { from: 'exStep', to: 'cpToast' },
    { from: 'enStep', to: 'cpFloat' },
    { from: 'enStep', to: 'cpSegment' },
    { from: 'enStep', to: 'cpToast' },
    { from: 'pgSettings', to: 'cpFloat' },
    { from: 'pgSettings', to: 'cpToast' },
    { from: 'pgSettings', to: 'cpUi' },

    /* 组件 → 基础设施 */
    { from: 'cpCanvas', to: 'cpLayout' },
    { from: 'cpCanvas', to: 'thColor' },
    { from: 'cpECharts', to: 'cpLayout' },
    { from: 'cpECharts', to: 'thStore' },
    { from: 'cpMini', to: 'cpLayout' },
    { from: 'themePanel', to: 'thStore' },
    { from: 'themePanel', to: 'thPresets' },
    { from: 'themePanel', to: 'cpUi' },
    { from: 'thStore', to: 'thApply' },
    { from: 'thApply', to: 'thColor' },
    { from: 'thPresets', to: 'thColor' },

    /* 落点 */
    { from: 'client', to: 'sinkApi' },
    { from: 'thStore', to: 'sinkLs' },
    { from: 'thApply', to: 'sinkRoot' },
  ],
}
