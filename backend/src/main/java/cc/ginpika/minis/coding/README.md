# Coding 模块（自研 Java 首版）

**当前入口基于既有工作空间：在起始页新建/打开 Docker 或 SSH 工作空间中的目录，进入项目编程界面。** 工作空间项目使用 WORKSPACE Runner，复用现有连接、命令护栏与环境；后端可信快照负责补丁和交付。无需源目录已有 Git。下面的宿主 Git/Docker 配置仍适用于旧本地仓库路线；最新流程、运行环境要求和核查结果见 `docs/AI-Coding工作空间与项目入口-2026-10-14.md`。

该包管理个人助手的自主编程任务。业务代码、API、上下文、仓库、Runner 与持久化都位于 `cc.ginpika.minis.coding`；共享模型协议循环位于 `ai.runtime.ModelLoop`。不依赖 Claude/Codex SDK。首版是可构建、可审阅的基础实现，真实模型与 Docker 链路仍需部署环境验收。

工作空间项目沿用现有工作空间接口的登录权限，普通已认证用户可以新建/打开项目，项目所有者来自登录会话。SSH 的 `root` 与 SSO 的 `admin` 是不同身份，不要求用户修改 SSO 角色；旧宿主路径登记 API 仍要求管理员。最新页面与权限回归见 `docs/AI-Coding页面检查与修复-2026-10-14.md`。

**编程已融合到默认 Agent**：工作台沿用 `builtin` 的名称、头像、模型与身份，名册不再单列编码 Agent。编程规程保留在 `ai/skills/coding.md`，作为独立的 `coding_skill` 状态化提示词，仅由 IDE 项目执行上下文加载；普通聊天不加载。可在默认 Agent 的「提示词 & 上下文工程 → Skills · 状态化提示词」中维护版本。任务保存身份与 Skill 快照，恢复仍沿用原配置。v6 数据迁移先备份再归并任务、会话和记忆归属，原编程提示词迁为 Skill 版本；旧名册和配置行保留作归档。

**组合 Agent 与持续会话已实现第一阶段**：主 Agent 按需委派 Explore、Planner、Reviewer 三种只读专家。每个子任务有独立模型循环、上下文、检查点和操作记录；共享当前任务副本与累计预算。角色配置在默认 Agent 页的「编程项目与任务」中维护，不额外创建三个永久智能体。已应用的任务可以在同一会话开启下一轮，规划结果可以在原任务批准后切换模式继续实施。实现边界与验收见 `docs/AI-Coding持续会话与组合Agent-2026-10-15.md`。

## 边界与职责

协作主控可通过 `start_work(assignments=[{id,reviewer},...],note=...)` 一批派发 1–8 项独立 TODO；相同默认 Agent 配置的各项执行使用独立副本。运行及独立复跑受 `MINIS_CODING_RUNS` 控制（默认 2），额外工作有界排队。检查前整合最新项目基准并重新验证；同路径并发修改拒绝覆盖。子代理评估、调度协议与完整边界见 `docs/collaboration-parallel-coding.md`。

工作台输入区的运行设置使用模态弹窗；“权限”菜单选择自动代码审查或手动审阅。新工作台任务默认开启自动审查，`StartRun` / `ResumeRun` 的 `autoReview` 字段保存为任务审计事件，`GET /runs/{id}/permissions` 读取当前策略。旧 API 未传该字段时沿用手动审阅；继续任务未传则保留原策略。EDIT / EXECUTE 产生非空变更后，运行时按具体产物哈希调用只读 Reviewer，并记录 `review.started` / `review.finished`。审查失败会明确展示，产物仍可手动审阅；审查从不自动应用代码。

项目终端由 `runner/CodingTerminals` 管理，`/projects/{project}/terminals` 提供列表与创建，`/{id}` 提供删除，`/{id}/connect` 接收输出游标并签发 30 秒有效的一次性 WebSocket 票据。该 REST 请求沿用 SSO / 桌面令牌与项目归属校验；`/ws/coding/terminals/{ticket}` 在升级前消费票据，再核对工作空间、终端归属及票据绑定的精确浏览器 Origin。长期桌面令牌不进入 WebSocket URL。Vite 与 nginx 已配置同源 WebSocket 代理。

前端使用 xterm.js，输入、输出与缩放通过 WebSocket 双向传输。服务端先发送 `output` JSON 元数据（offset、reset），再发送原始二进制 PTY 字节；浏览器在 xterm 渲染完成后确认 `ack` 游标，服务端才继续发送下一块输出，避免慢客户端积压。PTY 输出唤醒等待线程，无 HTTP 输出轮询。网络断开保留 PTY，可按已渲染游标重连；正常 EOF 发送 `exit` 并清理后端会话，前端立即关闭对应 Tab，选中相邻终端；最后一个终端退出时展示新建入口。旧的 HTTP 游标读取、输入和缩放接口保留用于兼容与诊断，工作台不再调用它们。

SSH 使用独立 PTY 连接，Docker 使用附带 stdin 的 TTY exec。用户终端进入项目目录的 **bash**，创建前检查 bash 是否可用，缺失时明确报错；保留 shell 状态，与 Agent 的任务副本及执行日志分开。终端背景、文字、光标、选区和 ANSI 色板跟随当前主题，切换主题不重建会话。每个用户最多 8 个终端，输出使用 1 MiB 环形缓存，离开页面 30 分钟后回收；删除终端关闭通道并清理该 shell 的子进程。连接配置失效时停止会话。

验收入口：`npm --prefix frontend run test:coding`、`test:coding:browser`（通过 `MINIS_PLAYWRIGHT_DIR` 指定已安装的 Playwright 包目录）；后端 `Coding*Test`。设置 `MINIS_TEST_DOCKER=true` 可运行真实 Docker PTY 测试，默认仅使用本地包含 bash 的 `maven:3.9-eclipse-temurin-21` 镜像，支持 `MINIS_TEST_DOCKER_IMAGE` 覆盖；测试只操作临时容器并自动删除。

| 组件 | 职责 |
| --- | --- |
| `CodingFacade` | 用户用例、并发与运行预算、状态机、取消、继续、接受修改 |
| `api` | 认证、仓库管理员登记、所有者隔离、REST/SSE |
| `context` | 项目约定、检查点修复、工具协议配对、上下文压缩 |
| `engine` | 按模式授权工具、串行修改、验证证据 |
| `engine/CodingRoles`、`CodingSubagents` | 用户角色配置、独立只读子循环、回报、停止与恢复 |
| `repository` | 快照、路径校验、哈希补丁、Diff、原仓库冲突检查 |
| `runner` | 可信 Git/Docker 命令、受控任务进程与日志 |
| `store` | SQLite 任务、事件、操作结果与用户介入记录 |
| `integration` | 聊天中的 `coding_task` 业务工具 |

普通聊天通过旧 `AgentRuntime` 适配共享循环，聊天记忆与 DTO 留在适配层。Coding 不继承聊天记忆生命周期或通用终端工具权限。`ModelLoop` 不依赖 Coding、Agent、Memory 或 Web 业务。

## 工作流

1. 在已有 Docker/SSH 工作空间中打开或新建项目目录；旧宿主路径路线由管理员登记 Git 根目录，可设置验证命令。
2. 提交任务，服务持久化任务与幂等键后异步执行。快照包含允许范围的已跟踪文件与未忽略的新增文件，保留未提交修改。
3. `PLAN` 只读；`EDIT` 可编辑副本；`EXECUTE` 才能运行命令。工作期间不写原仓库。
4. 用户补充输入先持久化，在下一轮模型调用前应用；取消同时终止模型 HTTP 请求并清理任务进程。
5. 超过时间或显式配置的累计 token 额度进入 `PAUSED`，保留检查点。累计额度默认关闭；模型回答本身不代表验证通过。
6. 正常收尾生成版本哈希与 Diff，状态为 `REVIEWABLE`。只有明确的接受请求才能应用到原仓库；重新核对已审阅产物、源 HEAD 和基准文件内容，冲突拒绝覆盖。
7. PLAN 结果可在同一任务批准后进入 EDIT/EXECUTE；完成应用后通过 `sessionId` 在同一会话发起下一轮。历史轮次保留且只读，每一轮重新取项目快照，历史文件内容、哈希与验证结果不当作当前证据。

主 Agent 增加 `delegate_agent`、`read_agent`、`resume_agent`、`stop_agent`。默认委派等待结果，也可后台执行；交付前等待并收集后台报告。子 Agent 没有修改、命令或再委派权限，角色提示词修改不会改变服务端权限。共享副本的文件操作串行化，子任务结束时对比版本哈希；版本变动标为 `STALE`。取消主任务会停止其子任务。重启后子任务标为 `INTERRUPTED`，恢复沿用原角色配置快照。

新增 API：`GET /sessions`、`GET /sessions/{id}/runs`、`GET /runs/{id}/session`、`GET /roles`、`PUT /roles/{id}`、`GET /runs/{id}/executions`，以及子执行的 `stop`、`resume`。都沿用编码模块的登录与任务所有者检查。角色覆盖按用户保存；子任务恢复要求主任务正在执行。

主要 API 为 `/api/coding/repositories`、`/runs`、`/runs/{id}`，以及 `resume`、`messages`、`cancel`、`accept`、`diff`、`operations`、`events`、`events/stream`。提交支持 `Idempotency-Key`；事件支持游标补读与 SSE `Last-Event-ID`。桌面令牌走现有认证链。

状态包括 `QUEUED → PREPARING → RUNNING → VERIFYING → REVIEWABLE → ACCEPTING → COMPLETED`，以及 `WAITING_AGENTS / PAUSED / FAILED / CANCELLED / INTERRUPTED`。等待子任务仍属工作中。PLAN 的可审查分析会同步将通用任务标为完成；修改任务在用户接受前保持待处理。

## 配置与执行环境

模型复用设置页的 OpenAI-compatible Chat Completions 配置，要求支持原生多轮 function tools。连接本机/内网推理服务时仍需核验工具协议、上下文长度和模型能力；安装 Agent 程序不等于下载闭源模型。无鉴权端点可按现有配置约束填写占位 API key。

| 环境变量 | 默认/含义 |
| --- | --- |
| `MINIS_CODING_ENABLED` | `true` |
| `MINIS_CODING_DIR` | 空：SQLite 数据文件旁的 `coding` 目录 |
| `MINIS_CODING_HOST_DIR` | 后端容器内数据目录对应的 Docker 宿主绝对路径 |
| `MINIS_CODING_IMAGE` | `maven:3.9-eclipse-temurin-21`；管理员预先准备 |
| `MINIS_CODING_LOCAL_EXECUTION` | `false`；显式启用才允许受信任本机执行 |
| `MINIS_CODING_MAX_MINUTES` | `30`，每次运行 |
| `MINIS_CODING_MAX_TOKENS` | `0`，默认不限制累计 token；正数限制每次运行的累计请求与响应，用供应商 usage，缺失时估算文本，非账单上限 |
| `MINIS_CODING_RESPONSE_TOKENS` | `0`，单次输出默认跟随模型服务；模型设置中的单次输出上限优先 |
| `MINIS_CODING_RESPONSE_RESERVE` | `32768`，未知输出能力时的上下文预留，不是发送给模型的硬输出上限 |
| `MINIS_CODING_OUTPUT_CONTINUATIONS` | `3`，连续截断时最多自动续接次数，仍受时间与任务预算控制 |
| `MINIS_CODING_MODEL_RETRIES` | `3`，单次模型请求失败后的额外重试次数，0 关闭；初次请求加重试最多 4 次 |
| `MINIS_CODING_SUBAGENTS` | `2`，全局同时执行的只读子 Agent 数，与主任务并发配额分开 |

其他 `minis.coding` 属性见 `config/CodingProps.java`，包括快照、文件、输出与并发设置。默认并发 2，快照 256 MiB，最多 20000 文件，单文件读取/补丁 2 MiB，单进程保留 40000 字符输出。

PLAN/EDIT 需要 Java 21、Git 和可写数据目录，不需要 Docker。EXECUTE 默认要求后端具有可用 Docker CLI、可信 daemon 连接和预载镜像。任务容器无网络、只挂副本、只读根文件系统、去除 capabilities、限制 CPU/内存/PID；模型容器不挂 Docker socket 或宿主凭证。私有 Git 管理区在 checkout 外，状态/Diff 使用内置工具。

当前 Runner 由可信后端直接调用 Docker CLI；未实现远程 Runner RPC。现有后端 Dockerfile 增加了 Git，但没有安装 Docker CLI 或挂载 daemon。容器部署必须由管理员明确配置可信 Docker 连接和路径映射。LOCAL 具有宿主权限，不能称为沙箱。

默认镜像只提供 Maven/Java 工具。由于任务容器禁网且不复制依赖目录，真实项目必须预备对应工具链和依赖缓存；Node 项目需另配含 Node 的镜像。镜像中的缓存应在命令开始时复制到可写目录，或使用适合只读缓存的构建方式；不得假定容器可以在线安装依赖。

工具循环不设轮数上限，不因历史 maxRounds 字段停止或撤掉工具。上下文压缩仅依据模型 token 窗口与输出预留，不再使用固定字符预算。聊天入口 read 在任务运行中等待完成（最多 30 秒后返回当前状态），避免高频模型轮询。

## 恢复与证据

协作主会话的有效单项派工可授权执行者使用 EXECUTE，仅限项目绑定仓库。执行、独立检查复用同一副本；执行者验证通过后提交，另一位检查者用 verify 实际复跑命令并读取 diff，再提交 review_todo。后端校验派工版本、检查者、命令及树哈希，应用成功才把待办标为完成；代码变化和应用冲突使旧检查失效。普通聊天不因此获得 EXECUTE 或应用权限。

项目固定验证命令优先。未配置固定命令时，有效协作执行任务可将本次构建测试命令登记到 coding_validation_command；普通任务不能创建该授权记录。计划撤销、成员移除、用户暂停或项目换绑会撤销执行权限。详细协议见 docs/design/collaboration-autonomy.md。

操作执行前记录调用 ID、attempt 和 round，结果另行记录。重启将执行中任务标为 `INTERRUPTED`，只读未返回结果标为 `NOT_RETURNED`，未确认副作用标为 `UNKNOWN`。不自动重放副作用；用户核查后才能继续。继续/取消时依据可信记录清理残留 Docker 容器，清理失败显示中断，不宣称停止成功。

验证报告含命令、退出码、日志与树哈希。代码在验证期间变化为 `STALE`，环境失败为 `BLOCKED`；相同版本已有验证结果时不重复执行。补丁或新命令使已有验证失效。

接受修改有日志与异常回滚，但不具备跨文件系统断电原子性。存在 `accept-journal.json` 时必须人工核对原仓库与日志，自动继续会被拒绝。

## 当前限制

- 根目录 AGENTS.md/CLAUDE.md 可加载；嵌套作用域规则、LSP、MCP、Skills、并行 Coding Worker、PTY/stdin、项目知识注入与自动模型路由尚未实现。
- 当前组合角色均只读；并行修改 Worker、Verifier、产品帮助 Guide 不在此次实现范围。PLAN/EDIT/EXECUTE 是本系统已有权限模式，没有实现 Claude Code 的 Auto 权限分类器。
- 文件排除防止常见凭证进入副本，不能替代密钥扫描；路径/链接检查仍须真实平台故障验收。Windows 硬链接缺少原生链接数核验。
- 快照体积与扫描有上限，容器对 bind mount 的磁盘写入尚无硬配额；LOCAL 不能保证清理所有脱离进程树的后台进程。
- 事件、任务和副本暂不自动删除；需要后续保留策略。多实例调度、严格跨用户的通用任务列表隔离不在本包首版范围。
- 首版 SQL 使用幂等建表；后续已发布表结构变更必须补迁移。此次没有更新用户的运行数据库。

实现与核查证据见仓库 `docs/AI-Coding首版实现与验收-2026-10-14.md`。真实任务完成率尚未测量，不能据此声称达到 Claude Code 的成熟度。

单次输出不再硬编码为 8192。截断响应在工具派发前保存并安全续接，要求模型输出较小的完整工具调用；不会执行半截补丁或命令。输出限制与上下文、任务预算分开配置，修复和验收见 `docs/AI-Coding输出截断修复-2026-10-14.md`。

执行态通过中立的 `ai.runtime.AgentActivitySource` 接入编码任务，按已认证用户过滤，任务视角只计算绑定当前会话的任务。会话时间线保存供应商返回的 `reasoning_content` 增量与每轮完整快照；模型不提供推理内容时不会生成虚假的思考文本。工具开始即显示文件路径、请求行段、目录或搜索关键词与 glob，结束更新同一操作。详细修复和检查见 `docs/AI-Coding活动状态与任务过程修复-2026-10-15.md`。

编程页新增普通/思考/详细三档过程展示、持续会话历史、可展开的子 Agent 面板。父子正文与推理事件按执行 ID 隔离，避免串流混在一起；详细档展开工具参数与结果。新增表采用幂等建表，旧任务首次打开时补建会话关联；此次没有修改运行数据库。

## 默认角色提示词

默认文案独立保存在 `src/main/resources/coding/prompts/`：`main.md` 为主任务运行契约，`explore.md`、`planner.md`、`reviewer.md` 为三个专家的默认提示词。子任务使用独立的只读运行契约，不再注入主协调者文案。项目元数据及实际加载的项目约定由后端追加。

本轮按社区收录的 [Explore](https://github.com/asgeirtj/system_prompts_leaks/blob/main/Anthropic/claude-code/agents/Explore.md)、[Plan](https://github.com/asgeirtj/system_prompts_leaks/blob/main/Anthropic/claude-code/agents/Plan.md) 与 [general-purpose](https://github.com/asgeirtj/system_prompts_leaks/blob/main/Anthropic/claude-code/agents/general-purpose.md) 的职责设计改写；Reviewer 按本项目能力独立编写。保留范围调查、基于证据规划、按目标完成及简洁回报；移除其品牌身份、内部 frontmatter、FleetView 完成标记、Bash/正则搜索/绝对路径等不适用约定，以及未实现的工具。我们的搜索是字面文本匹配，工具使用项目相对路径。

专家默认配置版本升为 2，首次保存用户覆盖为版本 3；已有用户覆盖不被重写，已创建子任务仍使用冻结的配置与检查点。主任务的新运行上下文会加载新的运行契约。此次更新默认资源，不直接修改运行数据库；重建并重启后端后生效。

## 思考模式协议与模型重试

模型的工具消息、普通回答、后台回报后的续轮、子任务结果与截断续接均保留原始 `reasoning_content`。截断只丢弃未完成的工具调用，不删除已返回的推理字段；上下文摘要及旧检查点缺字段时补空串表示无留存内容，不生成推理文本。此前已经丢失的内容无法凭空还原。带工具的请求需要回传全部 assistant 轮次推理，而不限于 tool_calls 轮次，参见 [模型协议](https://api-docs.deepseek.com/guides/thinking_mode/#tool-calls)。

主 Agent 和子 Agent 默认对临时模型故障自动重试 3 次，等待 0.5 / 1 / 2 秒；支持网络 I/O、HTTP 408、429 和 5xx，400 参数错误、401 鉴权错误及应用异常不做相同请求的盲重试。重试只包围当前模型 HTTP 请求，不重新执行之前完成的工具，仍受任务时间与取消控制。失败流中没有完成标记时拒绝将半截工具参数作为有效结果；重试前清除当前请求的正文及推理展示片段，保留其他轮次，并记录 `model.retry` 事件。

2026-10-15 本轮协议、循环、编码与聊天适配的定向检查共 70 项：66 项通过，4 项本地 HTTP 回环用例因运行环境不支持回环连接而跳过。回环用例可在允许本地端口的环境中设置 `MINIS_TEST_LOOPBACK=true` 后执行。前端纯逻辑检查覆盖主子任务重试流隔离与去重。


## 协作续跑回执与重复失败诊断

`coding_task` 在校验与执行入口使用同一个参数解析器。续跑/补充要求使用 `input`，旧 `prompt` 别名会规范化并报告；冲突字段和无效参数在修改状态前拒绝。返回的 receipt 包含实际接收内容、可信来源、attempt 和 ACCEPTED/APPLIED 状态，read 返回最新 instruction。续跑请求通过状态/快照/应用日志/未知副作用预检后才重新绑定派工，同请求键不重复开工。

`attempt.completed` 保存产物、已应用要求和失败证据指纹；相同产物、相同要求和相同失败证据重复时要求主控诊断，有进展则继续，不设机械返工次数上限。结构化检查问题和应用冲突由协作层记录，见 `docs/design/collaboration-autonomy.md`。SSH/LOCAL 命令拥有配置账号的系统权限，移除协作通用终端入口不能替代 OS 沙箱。
