# DeepSeek 网页搜索与正文抓取

设置页的「Deepseek Search」独立配置 DeepSeek API 根地址及 API Key，不受默认聊天模型影响。
默认地址为 `https://api.deepseek.com`；支持以 `/v1` 结尾的地址及兼容网关路径前缀。
密钥只在后端保存，GET 配置仅返回 `hasApiKey`。保存时 `apiKey: null` 保留旧密钥，空字符串清除。

- **在首页 FAST 搜索中使用**：搜索页保留本地文档/实体结果，另外展示网页结果。不开启则不发送该场景的联网请求。
- **在 RAG 中启用**：前台对话组装上下文时自动搜索网页；与 `CONTEXT_INJECT_KNOWLEDGE` 控制的本地召回独立。
  同时开放 `search_web` 工具，按现有工具面和授权规则供聊天、心跳及子代理/协作使用。
- **允许 Fetch 正文抓取**：RAG 首条搜索结果未返回正文时最多补抓取一次，并开放 `fetch_web` 工具。
  关闭后仍可使用 Search 自带的摘要和正文。此开关不控制独立的设置页 Fetch 测试。

Search 发往 `POST {base}/v1/tools/search`，Fetch 发往 `POST {base}/v1/tools/fetch`，均使用 Bearer 鉴权。
配置结果条数为 1–50，自动 RAG 最多 6 条；自动长问题取前 128 个 Unicode 字符并显示截断提示。
Search 保留查询语法，不经过本地关键词解析器改写。Fetch 仅接受不含凭据的 HTTP(S) URL。

网页结果不参与本地 chunk 的 RRF/精排，不伪造 chunk ID，不自动入库或变成浏览器快照。
RAG 文本明确区分摘要与抓取正文，附上原始 URL、可用的发布时间和截断提示；正文有上下文长度上限。
需要浏览器交互、媒体保存或离线快照时继续使用现有 `web_browser` / `save_web_snapshot`。

关闭的工具不进入模型工具列表，执行时再次检查开关。仅开启 FAST 不会开放模型联网工具。
联网失败返回独立的错误/警告，本地检索和对话继续运行。自动注入与主动调用在 RAG 召回页归入「网页」。
前台自动注入留痕工具名为 `web_inject`，主动调用为 `search_web` / `fetch_web`。

Search 成功结果和 Fetch 正文各在内存中保留 60 秒、最多 128 组，配置变更立即隔离旧缓存。
自动补抓取与主动 Fetch 复用相同正文缓存；失败请求不进入缓存。
客户端分别节流 Search/Fetch 到每秒少于 5 次；账号在其他进程中的调用仍可能导致 429。
408、429、5xx 最多重试一次，重试包含在单接口 2–60 秒超时预算中。
响应最多 4 MB；不跟随 API 重定向，不把服务端错误正文或密钥回显到 UI。

Search 和 Fetch 按请求次数收费，**没有**将其虚构为 token 用量加入现有模型费用统计。
测试按钮使用表单草稿发送真实请求，不修改保存配置，也不受场景开关限制。
单次 RAG 可能先 Search 再 Fetch，总耗时上限约为两个单接口超时之和。

接口：`GET/PUT /api/settings/deepseek-web`，`POST /api/settings/deepseek-web/test/search` 和 `/test/fetch`。
测试请求为 `{config: {...配置草稿}, query: "搜索词", url: "https://..."}`。
协议依据：[DeepSeek 官方 Search / Fetch 文档](https://api-docs.deepseek.com/zh-cn/guides/search/)。
