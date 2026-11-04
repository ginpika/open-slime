# 品牌配置说明（Branding）

本项目所有**面向用户显示的正式名称**与**构建产物名**都收敛到根目录一个文件：`branding.json`。
改正式名称时只改这一个文件，前端、后端、桌面端统一生效——不需要在三个技术栈里逐个搜索替换。

---

## 1. 唯一配置源：`branding.json`

```json
{
  "displayName": "minis",
  "slug": "minis",
  "tagline": "尝试像人类一样思考和学习",
  "webTitleSuffix": "hub",
  "identifier": "cc.ginpika.minis"
}
```

| 字段 | 含义 | 字符约束 | 用在哪 |
|---|---|---|---|
| `displayName` | 面向用户显示的正式名称 | 无限制（可中文、大写、空格） | 网页标题、页脚、关于页、桌面窗口标题、桌面引导页 `h1`、桌面应用名（productName） |
| `slug` | 技术标识（"机器名"） | 仅小写 ASCII：`a-z 0-9 . _ -` | 容器名、镜像名、jar 名、服务标识、Spring 应用名 |
| `tagline` | 一句话标语 | 无限制 | 页面页脚、桌面引导页副标题 |
| `webTitleSuffix` | 网页标题后缀 | 无限制 | 拼接为 `<displayName> · <webTitleSuffix>` |
| `identifier` | 应用唯一标识（反向域名） | 反向域名格式 | Tauri `bundle.identifier`、应用数据目录名 |

### 派生规则（不用手写，由各端代码计算）

| 产物 | 规则 | 示例 |
|---|---|---|
| 网页标题 | `<displayName> · <webTitleSuffix>` | `minis · hub` |
| 页脚 | `<displayName> · <tagline>` | `minis · 尝试像人类一样思考和学习` |
| 关于页小标签 | `displayName` 大写 | `MINIS` |
| 健康检查服务标识 | `<slug>-backend` | `minis-backend` |
| 桌面运行时 jar | `<slug>-backend.jar` | `minis-backend.jar` |
| 默认后端容器名 | `<slug>-backend` | `minis-backend` |
| 终端会话容器前缀 | `<slug>-term-` | `minis-term-` |
| 远程地址占位符 | `https://<slug>.example.com` | `https://minis.example.com` |

### 常见误区

- **`displayName` 可以中文，`slug` 不能。** 若正式名是「米尼斯」，`displayName` 写中文，`slug` 仍保持 `minis`——容器名、jar 文件名、Spring 应用名都不接受中文。
- **`identifier` 通常跟着 `slug` 走**（最后一段等于 `slug`）。但改 `identifier` 会让应用在系统里"变成另一个应用"：旧版数据目录不再被读取（Tauri 的数据目录是 `{系统数据目录}/{identifier}`），且可与旧版并存安装。首次公开改名时没问题，已发布后再改需谨慎。

---

## 2. 三端如何读取（构建期注入）

前端、后端、桌面端都在**编译 / 打包那一刻**读 `branding.json`，把值烧进各自产物，运行时零开销、零额外请求。

| 端 | 读取位置 | 注入方式 |
|---|---|---|
| 前端 | `frontend/vite.config.ts`（Vite 配置即 Node 程序） | `transformIndexHtml` 钩子把 `index.html` 的 `__BRAND_TITLE__` 占位符替换为标题 |
| 前端（TS 代码） | `frontend/src/branding.ts` | `import` JSON，打包器内联为常量，供 `App.tsx` / `About.tsx` / `Workspace.tsx` 使用 |
| 后端 | `backend/pom.xml` 的 `<resources>` 把根 JSON 拷进 classpath | `config/Branding.java` 用 Jackson 解析 |
| 后端（应用名） | `MinisApplication` 在 Spring 启动前 | `setDefaultProperties("spring.application.name", ...)` |
| 桌面壳（Rust） | `desktop/build.rs` | `cargo:rustc-env` 注入环境变量，代码里用 `env!()` 宏在**编译期**取值 |
| 桌面壳（引导页 HTML） | `desktop/src/main.rs` | `initialization_script` 注入 `window.__BRAND__`，页面脚本据此设置 `title` / `h1` / 占位符 |

三种注入手法：
1. **占位符替换**——HTML 里的 `__BRAND_TITLE__`；
2. **编译期常量**——TS 的 JSON import、Rust 的 `env!`；
3. **启动前属性**——Spring 的 `setDefaultProperties`。

---

## 3. 同步脚本（打包前写回静态文件）

有三份文件**读不了 JSON**，因为它们的消费者在很早的阶段解析且不支持变量：

| 文件 | 消费者 | 原因 |
|---|---|---|
| `desktop/tauri.conf.json` | Tauri CLI | 纯静态 JSON，`productName` 无变量能力 |
| `backend/pom.xml` | Maven 模型 | `<artifactId>` 在插件运行前就已定型 |
| `docker-compose.yml` | Docker Compose | 只读环境变量，不读 JSON |

因此由 `scripts/brand-sync.mjs` 在打包前把它们写回统一值（结构化改写、幂等，重复执行无副作用）。

该脚本已挂到下列构建入口，通常**无需手动执行**：

- `desktop/package.json` 的 `predev` / `prebuild`
- `desktop/assemble-runtime.ps1` / `desktop/assemble-runtime.sh`（起始处）

手动执行：

```bash
node scripts/brand-sync.mjs
```

---

## 4. 改正式名称的标准流程

1. 编辑根目录 `branding.json`（改 `displayName`；如需同时改技术标识，改 `slug`，并同步 `identifier`）。
2. 运行一次同步脚本（或直接走任一构建入口，会自动触发）：
   ```bash
   node scripts/brand-sync.mjs
   ```
3. 重新构建需要出包的端（前端 `npm run build`、后端 `./mvnw package`、桌面 `npm run build`）。

> 说明：前端 / 后端 / 桌面壳的构建期读取是自动的；第 2 步只负责那三份静态配置文件。

---

## 5. 有意保持不变的技术标识

以下字符串**不在**本配置的管理范围内，改名会破坏已有用户数据或前后端 / 桌面协议兼容性，故保持原样：

| 类别 | 例子 | 原因 |
|---|---|---|
| 浏览器本地存储 key | `minis.theme.v3`、`minis.chat.lastSession`、`minis.roundtable.*` | 改了会丢失用户本地偏好 |
| 桌面壳请求头 | `X-Minis-Token` | 前端与后端约定的鉴权头 |
| 桌面壳事件名 | `minis://status`、`minis://user`、`minis://session` | 壳与引导页之间的协议 |
| Java 包名 | `cc.ginpika.minis` | 源码结构，与展示无关 |
| Cargo / npm 包名 | `minis-desktop` | 内部构建标识 |
| 数据文件名 | `minis.db`、`minis.log` | 落盘文件名 |

如确需一并修改，请单独评估兼容性影响。

---

## 6. 验证

```bash
# 前端：类型检查 + 构建（同时验证 JSON import 与标题注入）
cd frontend && npm run build
# 构建产物标题应为 "<displayName> · <webTitleSuffix>"
grep "<title>" dist/index.html

# 后端：编译（resources 阶段会把 branding.json 拷进 classpath）
cd backend && ./mvnw -B -DskipTests clean compile
# 分层守门测试
./mvnw -B -Dtest=LayeringTest test

# 桌面壳：编译（build.rs 读取并注入环境变量）
cd desktop && cargo check
```
