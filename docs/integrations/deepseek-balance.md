# DeepSeek 官方账户余额

设置页按已保存的 Base URL 识别官方端点：HTTPS、主机 `api.deepseek.com`、默认端口或 443，路径为空或 `/v1`，允许结尾斜线；拒绝用户信息、查询参数、片段及其他路径。第三方中转地址即使模型名包含 DeepSeek，也不查询官方余额。

模型列表（含 `.env` 内置项）与独立 Search / Fetch 配置均展示账户余额。添加模型时可点击「使用 DeepSeek 官方端点」填入根地址；名称、模型名为空时补入 DeepSeek / deepseek-flash，已有值保留。API Key 输入框遮蔽显示。

后端使用已保存密钥请求官方 `GET https://api.deepseek.com/user/balance`，展示 `is_available`、各币种总余额、赠送余额和充值余额。金额保持字符串精度，CNY / USD 分别展示，不做汇率换算。

- `GET /api/settings/models/{id}/balance?refresh=false`：按模型 ID 精确解析已保存端点，不存在的 ID 返回 404，禁止回退至默认账户。
- `GET /api/settings/deepseek-web/balance?refresh=false`：使用独立联网检索配置，密钥不出现在请求参数或余额响应中。
- 响应状态为 `ready` / `error` / `unsupported` / `unconfigured`；`checkedAt` 为最近成功查询时间，`attemptedAt` 为最近查询完成时间。接口响应禁止浏览器缓存。

设置页首次打开立即查询；可见时每 30 秒刷新，回到页面时补查，离开设置页后停止轮询。手动「刷新余额」使用 `refresh=true`。同一密钥通过 SHA-256 指纹复用后端缓存（正常 25 秒、失败 10 秒，手动刷新最短间隔 3 秒），并发请求合并。缓存仅保留指纹与结果，最多 128 个账户。

官方请求总超时 8 秒，不自动重试、不跟随重定向。错误仅返回本地固定说明及 HTTP 状态码，不回显上游正文、鉴权头或密钥。失败保留同一密钥的最近成功结果并标注「上次余额 / 上次状态」，更换密钥不复用旧账户数据。没有有效结果时不显示零余额。

这是官方账户级余额的定期查询快照；同一账户的不同模型与联网检索共享余额，不代表某个 Agent 的独立预算，也不保证扣费后的即时推送。

验证：`DeepSeekBalanceServiceTest` 覆盖端点识别、协议、精度、多币种、缓存、并发、密钥隔离、错误与恢复；`DeepSeekBalanceControllerTest` 覆盖凭据来源、精确 ID 与缓存响应头。`frontend/tests/deepseek-balance-browser-check.mjs` 使用模拟 API 验证交互、轮询生命周期、四主题、移动端和 200% 字体。浏览器脚本依赖 Playwright，沿用 `MINIS_PLAYWRIGHT_DIR` 指定依赖位置；构建前端后执行。

官方接口文档：https://api-docs.deepseek.com/api/get-user-balance/
