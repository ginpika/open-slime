# ComfyUI 绘图接入

设置页的「ComfyUI 绘图」位于 TTS 后。端点保存自托管 ComfyUI 的服务根地址和可选的 Bearer Key（可由认证网关验证）；多个模型工作流档案可以引用同一端点。地址必须能从 **应用后端** 访问，不要求用户浏览器能直连 GPU 服务器。

## 配置档案

1. 在 ComfyUI 中先确认文生图工作流能正常运行，使用 `SaveImage` 等节点输出图片。
2. 导出 **API 格式** JSON，而非包含 `nodes` / `links` 的普通画布 JSON。
3. 添加端点并测试连接（读取 `/system_stats`，不会生成图片）。
4. 新建工作流档案，导入 JSON，填写提示词节点 ID 及输入字段。例如正向 `CLIPTextEncode` 的节点 `6`、输入 `text`。模型、负向词、分辨率、步数等保持工作流中设置的值。
5. 可选填写随机种子映射，例如 `KSampler` 节点 `3`、输入 `seed`。配置后每次生成写入随机整数；留空则保留工作流中的种子。不同模型可以用不同的节点和字段。
6. 可选指定图片输出节点。留空时只收集 `output` 类型图片，避免返回中间预览；指定后读取该节点的图片（包括 `temp` 类型）。单次最多 8 张，每张最多 20 MB。
7. 设置默认档案，或让工具通过 `profile_id` 明确选择档案。可用「试生成」检查完整链路。

不会自动安装节点、下载模型或转换画布 JSON。依赖与模型由远程环境维护。配置保存检查 JSON 形状及输入映射，运行时由远端检查模型和节点依赖。

## 可复用能力与工具入口

- `ai.comfyui.ComfyUiService.generate(profileId, prompt)`：同步执行，返回 generationId、profileId、远端 promptId 及应用图片 URL。profileId 为空时使用默认档案。该包不依赖聊天、Agent 或 HTTP 控制器。
- `ai.comfyui.ComfyUiSettings` / `ComfyUiStore`：配置、生成记录与图片存储。
- `ai.comfyui.ComfyUiClient`：`POST /prompt` → 轮询 `GET /history/{prompt_id}` → `GET /view`，等待期限包括排队、执行与图片获取。
- `ai.comfyui.integration.GenerateImageTool`：`generate_image` 工具，仅适配参数与结果；schema 提供可用档案 ID、名称、说明和默认项，不暴露地址、Key 或工作流内容。绘图有远端资源消耗，标为 WRITE 风险，但不标记为写知识库。
- `GeneratedImageReply`：会话收尾时补齐模型遗漏的图片 Markdown，确保结果进入消息正本。已有图片不重复追加。

工具描述明确区分计划、实际调用和成功结果：决定执行新的绘图请求时须在本轮发起调用，收到真实 `images` 后才能声称完成；历史图片不能冒充新图，禁止编造或输出 placeholder 地址。失败、无法满足请求或需要澄清时应如实说明。该约束通过工具 schema 随每次请求提供给模型，不修改 Agent 的角色提示词。提示词约束只能降低遗漏调用和虚构产物的概率，不能作为真实执行的校验依据；真实调用仍以 `tool_calls` 与服务记录为准。

HTTP 入口沿用应用的 SSO 和桌面令牌验证：

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/settings/comfyui` | 读取端点、档案、默认档案 |
| `POST /api/settings/comfyui/endpoints` | 创建端点 |
| `PUT /api/settings/comfyui/endpoints/{id}` | 更新端点 |
| `DELETE /api/settings/comfyui/endpoints/{id}` | 删除未被引用的端点 |
| `POST /api/settings/comfyui/endpoints/test` | 测试表单中的端点 |
| `POST /api/settings/comfyui/endpoints/{id}/test` | 测试已保存端点 |
| `POST /api/settings/comfyui/profiles` | 创建工作流档案 |
| `PUT /api/settings/comfyui/profiles/{id}` | 更新档案 |
| `DELETE /api/settings/comfyui/profiles/{id}` | 删除档案，保留已生成图片 |
| `PUT /api/settings/comfyui/default` | 选择默认档案，body 为 `{ "profileId": "..." }` |
| `POST /api/comfyui/generate` | 独立于聊天的生成入口，body 为 `{ "profileId": "...", "prompt": "..." }` |
| `GET /api/comfyui/images/{id}` | 读取已保存图片 |

## 结果与失败语义

图片保存在 SQLite 的 `comfyui_image` 表，生成记录保存在 `comfyui_generation` 表（包含远端任务 ID）。远端删除文件、档案改名或端点删除不会破坏已保存的图片。前端通过带认证头的请求读取图片，再用临时 blob URL 显示，消息中保留稳定的 `/api/comfyui/images/{id}` 地址。

点击图片会在应用内打开 Yet Another React Lightbox 预览，支持放大、缩小、滚轮缩放、拖动和触屏手势；Esc、关闭按钮或点击背景可退出。预览复用已认证的 blob URL，不重复请求图片或打开浏览器标签页。通用 `ImagePreview` 入口与认证加载组件 `ComfyImage` 分离，查看器及 Zoom 插件仅在首次打开时加载。工具栏使用 AppIcon 图标和中文辅助标签，并适配主题颜色。

不会对提交请求自动重试。超时、网络中断或应用关闭不意味着远程 GPU 任务已取消；不调用 ComfyUI 的全局 `/interrupt`，避免影响共享服务上的其他任务。当前没有断点恢复或自动重提交流程；重启前未结束的记录只用于排查，可根据已保存的 prompt_id 到远端查询。

默认等待 600 秒，可配置 10–3600 秒。应用聊天流和随附 nginx 的读取时限为 3900 秒，为工作流等待和模型收尾留出余量；外层网关也应允许相应的等待时间。聊天流断开后可重新打开会话查看在途状态，最终回复仍落库。

协议参考：[ComfyUI 自托管 API](https://docs.comfy.org/development/comfyui-server/comms_routes)、[官方 API 示例](https://docs.comfy.org/development/comfyui-server/api-examples)。
