# Computer-use 首版

首版提供两个 function tools：`window_capture` 和 `chrome`。不实现系统鼠标键盘控制。终端能力已迁入 `cc.ginpika.minis.ai.computer.terminal`；既有终端 API、目标、凭证、数据库表与配置不变。

## 部署与权限

| 使用方式 | 隔离 Chrome | 个人 Chrome 登录态 | 原生窗口截图 |
| --- | --- | --- | --- |
| Web 访问服务端 | 支持，按用户、Agent、会话隔离 | 不开放 | 不开放，包括服务器桌面 |
| Desktop 本机模式 | 支持 | 用户配对扩展后支持 | Windows / macOS，本机令牌与原生 helper 都就绪时开放 |
| Desktop 远程模式 | 沿用 Web 端权限 | 不开放 | 不开放 |

这些边界在服务端执行。`MINIS_DESKTOP` 开启且存在壳注入的本机令牌，才能生成 Chrome 配对地址、连接扩展或开放窗口工具。单独配置 helper 路径不授予服务端桌面权限。Chrome WebSocket 还检查回环来源、扩展 Origin 与随机配对凭证；不根据 User-Agent 或前端显示模式授予能力。

普通 Web 部署不需要安装 Chrome 扩展。若使用隔离浏览器，运行后端的环境需要安装 Google Chrome；也可用 `MINIS_COMPUTER_CHROME_EXECUTABLE` 明确指定独立浏览器的可执行文件。该路径是部署配置，模型不能修改。

## Desktop 使用入口

1. 更新并重新组装桌面端运行时，重启后选择「本机」。原生截图 helper 是更新后的桌面可执行程序自身，需要同时更新 Rust 壳和后端 JAR。
2. 打开「工作空间 → Chrome」。界面展示当前模式、扩展连接状态与窗口截图能力。
3. 首次使用个人 Chrome 时，下载并解压扩展，在 Chrome 的扩展管理页开启开发者模式，然后选择「加载已解压的扩展程序」。开发时也可以直接加载仓库的 `desktop/chrome-extension`。
4. 在工作空间页生成连接地址，粘贴到扩展弹窗中并连接。扩展图标显示 `ON` 后，刷新工作空间页状态即可确认。配对地址在十分钟内首次使用；已配对连接的凭证持续到应用重启或主动撤销。重启应用后需要重新配对。
5. 窗口截图首次使用可能需要系统屏幕录制授权。授权对象取决于系统对应用与其可执行程序的识别方式。拒绝授权时工具返回错误，不尝试抢焦点或绕过权限。

扩展仅请求 `tabs`、`scripting`、`storage` 和网页访问权限，不请求 `cookies`、`debugger`、原生消息或系统输入权限。登录请求由 Chrome 自身处理，不读取、复制或导出 Cookie；也不提供读取密码、执行任意 JavaScript 的工具。连接地址只允许回环 WebSocket，保存在扩展的会话存储中。

扩展创建普通 Chrome 窗口中的独立后台标签页（`active:false`），与用户正常网页共享同一浏览器配置中的登录态。不会新建或激活浏览器窗口，也不会操作用户原有标签页。如果用户主动切到工具标签页，该标签页就永久退出工具归属，后续工具不会再修改或关闭它。需要继续时，新建后台标签页即可沿用已经完成的登录状态。

## Function calling

### 指定窗口截图

`window_capture` 仅在本机截图 helper 可用时进入模型工具面。先列窗口：

```json
{"action":"list"}
```

从返回结果选择任务需要的 `id`、`pid`，再调用：

```json
{"action":"capture","window_id":123,"pid":456}
```

每次截取前都重新验证窗口 ID 与进程 ID，防止窗口已经关闭或句柄被复用。使用现有的 xcap 原生窗口捕获路径，不截整屏，不恢复最小化窗口，不改变焦点。最小化、受保护内容、空白画面、权限不足等情况明确返回失败。后台窗口能否提供有效图像仍取决于系统及目标应用。

截图以有大小上限的图像返回模型，需要配置支持图像输入的模型。工具元信息和图像分别组成完整工具结果与后续图像观察；不把 Base64 当作普通文本塞给模型。长任务仅保留最近两张图像在模型输入中，历史裁剪保留工具调用与结果配对。

### Chrome

先查看环境：

```json
{"action":"status"}
```

需要用户登录态时显式选择 `user`：

```json
{"action":"open","environment":"user","url":"https://example.com"}
```

独立后台实例选择 `isolated`：

```json
{"action":"open","environment":"isolated","url":"https://example.com"}
```

省略环境时，已连接扩展则选择 `user`，否则选择 `isolated`。返回的 `tab_id` 带环境前缀，后续调用由该前缀保持路由；扩展断开后不会把原有用户标签页操作改投到隔离浏览器。

常用操作：

```json
{"action":"list","environment":"user"}
{"action":"read","tab_id":"user:123"}
{"action":"fill","tab_id":"user:123","ref":"最近观察中的元素引用","text":"查询内容"}
{"action":"click","tab_id":"user:123","ref":"最近观察中的按钮引用"}
{"action":"select","tab_id":"user:123","ref":"最近观察中的下拉框引用","value":"选项值"}
{"action":"close","tab_id":"user:123"}
```

通过普通按钮点击提交表单。元素引用在重新观察后更新，旧引用不能复用。首版只操作主文档，暂不处理 iframe、复杂富文本编辑器和任意脚本执行。需要登录时由用户在自己的 Chrome 中完成，然后重新打开工具后台标签页。

隔离实例额外支持 `action=screenshot`；用户 Chrome 模式不使用需要切换标签页的可见页截图接口。需要观察某个原生窗口时，单独使用 `window_capture`。

隔离实例使用专用 Playwright 线程与独立的 headless Chrome 进程，不连接个人 Chrome、默认配置目录或 CDP。浏览器上下文按已认证用户、Agent、会话隔离，跨对话轮保留，闲置会话在后续操作时清理；重启后不保留登录态。服务端浏览器复用公开网页地址校验，不允许直接访问本机、内网及保留地址。下载关闭，弹窗与对话框不进入用户桌面。

Chrome 操作不自动重试。超时或连接中断时，已提交的操作可能已经生效，先 `read` / `list` 核对后再继续，避免重复提交。

## 代码落点

- `ai/computer/terminal`：迁入的终端能力；继续复用原有目标与凭证。
- `ai/computer/window/WindowCapture`：带边界检查的原生 helper 调用。
- `desktop/src/computer.rs`：截图专用 CLI，在 Tauri 初始化和单实例聚焦逻辑之前处理请求。
- `ai/computer/browser/ChromeBrowser`：隔离 headless 实例。
- `ai/computer/browser/ChromeBridge`：本机扩展配对与调用通道。
- `desktop/chrome-extension`：用户 Chrome 中的后台标签页操作。
- `ai/computer/integration`：function schema、参数校验与分发。
- `ai/core/tool/ToolOutput`：图像工具结果；共享 `ModelLoop` 保持原工具循环。
- `web/computer`、`web/controller/ComputerController`：WebSocket 握手与用户连接入口。

未增加另一套电脑任务、凭证仓库或 Agent 循环。终端的远程环境与本机窗口是不同的执行位置，本版不会把远程后端当作用户电脑。

## 参考

- [Playwright 的 Chrome 支持](https://playwright.dev/java/docs/browsers)
- [Chrome 扩展中的脚本执行](https://developer.chrome.com/docs/extensions/reference/api/scripting)
- [Chrome 扩展 Service Worker 的 WebSocket](https://developer.chrome.com/docs/extensions/how-to/web-platform/websockets)
- [Chat Completions 图像输入](https://developers.openai.com/api/docs/guides/images-vision?api-mode=chat)

按用户要求，停止自动测试，最终功能由用户手动验收。Windows / macOS 真机、系统授权、已登录网站与真实模型的组合仍需用户验证。
