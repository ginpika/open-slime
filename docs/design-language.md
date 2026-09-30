# minis 项目 · 设计语言与视觉风格总览（可复现规格）

> 适用范围：`frontend/`（React 19 + Tailwind CSS v4）、`desktop/`（Tauri 引导页）、`backend/`（Spring Boot，仅记录与 UI 相关的接口/配置约定）。
> 本文档所有数值均取自源码实际类名与 token，可直接照抄复现。路径均为仓库相对路径。

---

## 0. 总体风格定位（一句话）

**极简、中性、墨色驱动的「可换肤排版系统」**：以「底色 / 墨色 / 线条色 / 点缀色」四角色的三色模型为核心；全局零装饰（无渐变、无彩色插画、正文卡片无阴影），靠 **1px 描边 + 极淡同色叠加（`bg-fg/[0.02~0.08]`）+ 大量描边胶囊** 建立层次；仅在图形与图表处允许出现彩色（分类色板、实体色）。字体为系统字体栈，字号使用**像素硬编码谱系**而非 Tailwind 默认刻度。

---

## 1. 设计系统的技术载体

| 层 | 位置 | 作用 |
|---|---|---|
| 令牌映射 | `frontend/src/index.css` `@theme inline` | 把 `--c-*` 映射为 `bg-bg / text-fg / border-line` 等工具类 |
| 令牌推导 | `frontend/src/theme/apply.ts` | 三色 → 约 28 个语义令牌（含 MD3 角色） |
| 色彩运算 | `frontend/src/theme/color.ts` | hex↔hsl、`mix`、WCAG 对比度、`pickText`、`isDark` |
| 预设主题 | `frontend/src/theme/presets.ts` | 4 套内置主题 |
| 状态 / 持久化 | `frontend/src/theme/store.ts` | `localStorage: minis.theme.v3`，含 v1/v2 迁移 |
| 主题面板 | `frontend/src/theme/ThemePanel.tsx` | header 调色板图标 → 预设 / 自定义两页签 |
| 防闪烁 | `frontend/index.html` | React 挂载前把持久化 `vars` 写入 `:root` |
| 渲染版本注册表 | `frontend/src/theme/overrides.tsx` | 同一路由可切换多个渲染结构版本（当前未启用） |

**不存在** CSS Modules / styled-components / 第三方组件库（shadcn、MUI 等）。全部视觉 = Tailwind 原子类 + 少量 `@utility` 自定义类。

---

## 2. ⭐ 关键结构发现：项目同时存在 **三套并行的设计语言**

这是评估本项目 UI 时最重要的结论。同一应用内并存：

### A. Tailwind 语义令牌轨（主流，约 80% 页面）
关键字：`bg-bg / text-fg / text-muted / text-faint / text-hint / border-line / border-line-strong / bg-accent / text-accent-fg / bg-fg/5 / bg-secondary/10`

使用页：Home、Search、Import、Analyze、Articles、ArticleDetail、Graph、Settings、Stats、About、MultiAgent、AgentDetail、Roundtable、PromptsPanel、Toast、FloatWindow、ChatNotifier 等。

视觉特征：**描边卡片 + 直角或小圆角**。

### B. Material Design 3 角色轨（局部，2 页）
关键字：`bg-surface-container-low/-high/-highest、bg-surface-variant、text-on-surface、text-on-surface-variant、border-outline、border-outline-variant`

使用页：`pages/Subconscious.tsx`、`pages/Workspace.tsx`。

视觉特征：**实底卡片（无描边）+ 8/16dp 圆角**。两轨可互转（MD3 令牌同样由三色现混派生，见 §3.2），但手感不同。

### C. 桌面壳引导页独立 CSS 轨（1 页，完全独立）
文件：`desktop/ui/index.html` 内联 `<style>`，自带一套与前端无关的变量：

```
浅色: --bg #fafafa / --fg #171717 / --muted #6b7280
      --line rgba(23,23,23,.12) / --card #ffffff
      --accent #0f9d58(绿) / --danger #c0392b
深色: --bg #121212 / --fg #e5e5e5 / --muted #9ca3af
      --line rgba(229,229,229,.14) / --card #1b1b1b
      --accent #32f08c / --danger #e8463a
```
圆角 8px（控件）/ 12px（卡片），字体 `"PingFang SC"` 优先，`color-scheme: light dark` 自动跟随系统。**不复用前端主题引擎**。

### D. 图形语言（canvas / SVG，独立于以上三轨）
图谱、数据流、3D 知识球、ECharts 图表各自成体系（见 §10）。

---

## 3. 色彩系统（主题引擎）

### 3.1 三色模型

```ts
ThemeColors = { primary, secondary, accent, dark: boolean, base?: string }
```

角色分工（源码注释语义）：

| 角色 | 令牌 | 用途 |
|---|---|---|
| **底色** base | `--c-bg` | 背景 |
| **主色** primary | `--c-fg` | 正文墨色（灰阶由它派生） |
| **辅色** secondary | `--c-line` | 线条（分割线、描边、focus） |
| **点缀色** accent | `--c-accent` | 按钮实底、链接、placeholder、状态提示、命中高亮 |

双色模式 = `secondary ≡ primary` 的特例；`base` 缺省时自动派生：

```
neutralTint(primary, dark) = hslToHex({ h: 主色色相, s: 0.06, l: dark ? 0.12 : 0.98 })
```

### 3.2 令牌推导公式（`theme/apply.ts`，`mix(a,b,t)` = 线性插值）

```
--c-bg            = base ?? neutralTint(primary, dark)
--c-fg            = primary
--c-faint         = mix(bg, primary, 0.42)
--c-muted         = mix(bg, primary, 0.60)
--c-line          = mix(bg, secondary, 0.40)
--c-line-strong   = mix(bg, secondary, 0.70)
--c-primary       = primary
--c-primary-fg    = pickText(primary)          // 白 / 纯黑中对比度高者
--c-secondary     = secondary
--c-accent        = accent
--c-accent-fg     = pickText(accent)
--c-accent-hover  = mix(accent, bg, 0.15)
--c-hint          = mix(bg, accent, 0.60)      // placeholder / 弱状态
--c-mark          = mix(bg, accent, 0.15)      // 搜索命中高亮
--c-entity-1..4   = entityColor(accent, bg, i)
```

**分类色板** `entityColor()`：从点缀色色相出发，第 i 档旋转 `i * 90°`；饱和度固定 `0.55`；亮度 `l = isDark(bg) ? 0.62 : 0.45`。若点缀色饱和度 `< 0.12`（黑白主题），色相基准取 `215°`，保证无彩主题下实体类型仍可区分。

**MD3 角色**（全部由主色 / 辅色往底色掺，升阶即"浅底变深、深底变亮"）：

```
surface-container-lowest  = mix(bg, primary, 0.015)
surface-container-low     = mix(bg, primary, 0.04)
surface-container         = mix(bg, primary, 0.075)
surface-container-high    = mix(bg, primary, 0.11)
surface-container-highest = mix(bg, primary, 0.15)
surface-variant           = mix(bg, secondary, 0.16)
on-surface                = primary
on-surface-variant        = mix(bg, primary, 0.60)
outline                   = mix(bg, secondary, 0.60)
outline-variant           = mix(bg, secondary, 0.28)
```

### 3.3 内置预设（`theme/presets.ts`）

| id | 名称 | primary | secondary | accent | base | 深浅 |
|---|---|---|---|---|---|---|
| `mono` | **Mono（默认）** | `#0f0f0f` | `#8b8f96` | `#0f0f0f` | `#ffffff` | 浅 |
| `trio-ink` | 靛墨（三色） | `#1f3a5f` | `#8b8f96` | `#b45309` | 自动派生 | 浅 |
| `night` | 夜幕 | `#ededed` | `#8b8f96` | `#ededed` | `#101010` | 深 |
| `neon` | 荧光绿 | `#ffffff` | `#8b8f96` | `#32f08c` | `#1a1b1d` | 深 |

默认浅色 **Mono** 的实际观感：`bg #ffffff`、正文 `#0f0f0f`（近纯黑）、线条约 40% 灰、按钮 = 纯黑底白字。

### 3.4 外观模式与槽位

`appearance: 'light' | 'dark' | 'system'`；分别记住 `lastLightId / lastDarkId`（各档上次用过的主题）。`system` 档监听 `prefers-color-scheme` 实时切换槽位。

### 3.5 自定义主题

「自定义」页签支持：双色 / 三色切换、取色器 + hex 输入实时生效、底色可覆写或选「深底 / 浅底」自动派生。双色↔三色切换时辅色迁移规则：→ 双色时 `secondary = primary`；→ 三色且 `secondary === primary` 时解绑为默认 `#8b8f96`。

### 3.6 透明度叠加惯例（统一用「前景色 alpha」而非灰阶色板）

| 类 | 用途 |
|---|---|
| `bg-fg/[0.02]` | 只读文本块底、思维链折叠块 |
| `bg-fg/[0.03]` | 表头、FloatWindow 标题栏、pre |
| `bg-fg/[0.04]` | 实体 / 关系 pill |
| `bg-fg/[0.06]` `/[0.07]` `/[0.08]` | 头像兜底、理由 badge、选中 tab |
| `bg-fg/5` | code、th、按钮 hover |
| `bg-fg/10` | Badge、开关 hover |
| `bg-fg/20` | 开关关闭态轨道 |
| `bg-primary-fg/5 ~ /25` | 用户深色气泡内的叠加层 |
| `bg-secondary/10` | 选中态（卡片 / 会话行 / ModeBtn） |
| `bg-accent/10` | 选中菜单项、Tab |
| `bg-black/40` / `bg-black/45` | 模态遮罩 / 头像 hover 遮罩 |

---

## 4. 排版系统

- **字体族**（`index.css`）：`system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif`，无自定义字体文件。
- **等宽 `font-mono`** 用于：ID、时间戳、得分、token 数、文件路径、端口、命令、提示词正文、JSON、文章 code/pre、About hero 整块文案、品牌小标签。
- **字号谱系为硬编码像素值**（非 `text-sm` 等默认刻度），全项目实际出现：

| px | 典型用途 |
|---|---|
| `10` / `10.5` | Badge、菜单分组标签、Contents、`+N`、类型胶囊 |
| `11` / `11.5` | Field 标签、hint、日期、次数、说明 |
| `12` / `12.5` | 正文、按钮（BTN_BASE）、菜单项、Tab、统计 |
| `13` / `13.5` | 表单输入、Toast、卡片标题、文章列表标题 |
| `14` | Markdown 正文 p、Toast 文字、首页提示 |
| `14.5` | Markdown h3 |
| `15` | Section 标题、Metric 值、搜索框 |
| `16` / `18` | Markdown h2 / h1、文章标题 |
| `20` / `21` / `22` | StatCard 数值 / ResultCard 标题 / Workspace h1 |
| `30` → `40`(sm) | About hero |
| `34` | Stats TOTAL TOKENS |

- **字重**：正文 `400`；`font-medium 500`（标签 / 表头）；`font-semibold 600`（标题、按钮、数值）；`font-bold 700`（Markdown 标题、ResultCard h3、图谱强调标签）。
- **大写小标签（signature 手法）**：`uppercase` + `tracking-[0.08em / 0.12em / 0.18em / 0.2em / 0.24em / 0.25em]` + `text-faint`。用于 `Field` 标签、`Props`/`ID`/`Name`/`Type`/`Appearance`/`Presets`/`Contents`/`TOTAL TOKENS`。
- **行高**：正文 `leading-relaxed`(1.625)；Markdown 正文 `leading-7`(1.75)；About hero `leading-[1.9]`；紧凑信息行 `leading-none / leading-snug`。
- **数字对齐**：以 `font-mono` 为主，局部 `tabular-nums`（图谱统计胶囊定宽 `min-w-[110px]` 防抖动）。
- **文案风格**：标题常用逗号分隔的中文短句（如「欢迎回来, 我的朋友!」）。

---

## 5. 形状系统

### 5.1 圆角谱系（用途严格分工）

| 类 | px | 用途 |
|---|---|---|
| `rounded-full` | ∞ | 胶囊：搜索框、Tab、分页钮、状态 pill、步骤圆点、开关轨道、头像、**按钮默认** |
| `rounded-2xl` | 16 | 大面板卡：MultiAgent 卡、Roundtable 主容器 / 气泡、Subconscious / Workspace 卡、图谱侧卡、PageBoundary 错误框 |
| `rounded-xl` | 12 | 提示词便签长条、`PickCard`、下拉面板（图谱 / 圆桌 / 提示词）、GraphMini 图框、Workspace 目录行 |
| `rounded-lg` | 8 | Toast、思维链折叠块、`btnOutline('rounded-lg')`、AgentDetail 破坏性按钮 |
| `rounded-md` | 6 | **表单输入框**、FloatWindow、Select 面板、Tab 页签、`btnPrimary('rounded-md')`、Graph 输入框 |
| `rounded-sm` | 2 | `miniBtn`、`btnPrimary('rounded-sm')`、主题面板控件、FloatWindow 关闭钮、属性按钮、`SegmentBlock` |
| `rounded-[4px]` / `rounded-xs` | 3–4 | `PickCard` 勾选框、主题色块 |

**关键反差手法**：页级大卡片在 **Stats / Settings / About 业务流程** 使用**完全无圆角**（`border border-line p-5`，直角）；而在 MultiAgent / Roundtable / Subconscious / Workspace 使用 `rounded-2xl` 实底卡。这是两轨设计语言的分界线。

### 5.2 边框

- 一律 `1px`；颜色 `border-line`，hover → `border-line-strong`，focus → `border-secondary`。
- 虚线：**空态 / 占位**统一 `border border-dashed border-line`；MD3 轨用 `border-dashed border-outline-variant`。
- `border-l-4`：Toast / ChatNotifier 左侧状态条（`border-l-hint` / `border-l-emerald-500` / `border-l-accent`）。
- 分割：`border-b` / `border-t border-line`；列表用 `divide-y divide-line`。

### 5.3 阴影（克制）

| 类 | 用途 |
|---|---|
| 无阴影 | **所有正文卡片**（仅描边）——全站最显著的策略 |
| `shadow-sm` | 开关滑块、提示词便签长条、首页模式滑块 |
| `shadow-md` | ExtractStep 属性气泡 |
| `shadow-lg` | Select 下拉面板、Toast、图谱悬浮栏（统计胶囊 / 工具栏 / 锚点入口 / 图例） |
| `shadow-xl` | **FloatWindow（唯一主容器阴影）**、所有省略号菜单、图谱详情卡片、会话抽屉 |

### 5.4 玻璃拟态（仅浮层）

`bg-bg/85 ~ /95` + `backdrop-blur-md`（DataFlow 用 `backdrop-blur-sm`）。用于 Toast、ChatNotifier、图谱全部悬浮控件。

---

## 6. 布局与间距系统

- **页壳**（`App.tsx`）：`flex h-screen flex-col overflow-hidden`；
  - header：`border-b border-line`，内层 `mx-auto max-w-6xl px-6 py-4`；
  - main：`mx-auto max-w-6xl px-6 pb-16`，内部滚动；
  - footer：`border-t border-line` + `text-[12px] text-faint py-6`。
- **满幅路由**：`/graph`、`/roundtable`、`/workspace`、`/search` 等 main 不滚动、无内边距，由页面自管 `h-full / min-h-0 / flex-1`。
- **导航**：`text-[12px]`；激活项 `font-semibold text-fg underline decoration-fg underline-offset-8`；非激活 `font-medium text-faint hover:text-fg`。
- **内容宽度**：全局 `max-w-6xl`；内容块 `max-w-2xl`（首页 / 搜索 / 对话）、`max-w-3xl`（设置）、`max-w-[900px]`（提示词）、`max-w-[760px]`（Subconscious 文档页）。
- **间距**：页面纵向节奏 `gap-5 / gap-6`；区块 `gap-2 … gap-4`；Section `mt-12`（首个 `first:mt-0`）；卡片内边距 `p-3.5 / p-4 / p-5`；`pt-6` 顶部、`pb-4 ~ pb-16` 底部。
- **滚动条（3 种自定义 utility）**：
  - `scrollbar-hide`：首页两页模式、对话列表、Workspace 面包屑；
  - `scrollbar-slim`：默认细条，8px、圆角 9999px、`color-mix(fg 18%, transparent)`，hover 32%；
  - `scrollbar-accent`：点缀色滑块，`color-mix(accent 50%)`，hover 75%（图谱关系列表、文章详情）。
- **`::selection` 反色**：`background: var(--c-fg); color: var(--c-bg)`。
- **`button:not(:disabled) { cursor: pointer }`**（覆盖 Tailwind v4 preflight）。

---

## 7. 组件原语库（组件级可复现规格）

### 7.1 按钮五式（`components/ui.tsx`）

基底：
```
inline-flex items-center justify-center gap-1.5 whitespace-nowrap text-[12px] font-semibold transition
```
默认圆角 `rounded-full`，面板内传 `'rounded-md'` / `'rounded-sm'` / `'rounded-lg'` 覆盖。

| 名称 | 视觉 | 类 |
|---|---|---|
| `btnOutline` | 细描边 + 悬停加深 / 微填充 | `border border-line bg-bg px-3.5 py-1.5 text-muted hover:border-line-strong hover:bg-fg/5 hover:text-fg` |
| `btnPrimary` | 点缀色实底 + 自动黑 / 白字 | `bg-accent px-3.5 py-1.5 text-accent-fg hover:bg-accent-hover` |
| `btnQuiet` | 描边、无填充 | `border border-line px-3.5 py-1.5 text-muted hover:border-line-strong` |
| `btnIcon` | 无描边圆形图标钮 | `h-[30px] w-[30px] text-muted hover:bg-fg/10 hover:text-fg` |
| `miniBtn` | 表格操作微钮 | `rounded-sm border border-line px-2.5 py-1 text-xs font-medium text-muted hover:border-line-strong hover:bg-fg/5 hover:text-fg disabled:pointer-events-none disabled:opacity-40` |

另有局部常量：
- `btnDanger`（Settings）：`rounded-md bg-red-600 text-white hover:bg-red-700`
- Workspace 四档 MD3 按钮（filled / tonal / outlined / text）
- PropsEditor 的 `btnSolid` / `btnGhost`

### 7.2 表单（`components/formKit.tsx`）

```
INPUT = w-full rounded-md border border-line bg-bg px-3 py-2 text-[13px] text-fg outline-none
        transition placeholder:text-hint hover:border-line-strong
        focus:border-secondary focus:ring-2 focus:ring-secondary/30
```

- `Field`：`<label class="block">` + 标签 `mb-1 block text-[11px] font-medium uppercase tracking-[0.12em] text-faint` + hint `mt-1 text-[11px] text-hint`。
- `Select`：**自绘下拉**（按钮 + 绝对定位面板 + 点外 / Esc 关闭）。面板 `rounded-md border border-line bg-bg p-1 shadow-lg`；选项 `text-[13px]`，选中 `bg-accent/10 font-medium text-fg`，未选 `text-muted hover:bg-fg/5`；箭头 `rotate-180` 过渡 200ms。
- `ModeBtn`：`rounded-md border px-3.5 py-3 text-left`；选中 `border-secondary bg-secondary/10`，未选 `hover:border-line-strong`；前置圆点 `h-3 w-3 rounded-full`。

### 7.3 开关（两套尺寸）

- **Tailwind 轨**：轨道 `h-[18px] w-8 rounded-full`（开 `bg-accent` / 关 `bg-fg/20`）；滑块 `h-3.5 w-3.5 rounded-full shadow-sm`（开 `left-4 bg-accent-fg` / 关 `left-0.5 bg-bg`）。
- **MD3 轨**（Subconscious）：轨道 `h-6 w-11 rounded-full border`（开 `border-accent bg-accent` / 关 `border-outline bg-surface-container-highest`）；滑块 `h-5 w-5`（开 `left-[21px] bg-accent-fg` / 关 `left-[1px] bg-outline`）。

### 7.4 Chip / Badge / Pill

- 类型胶囊：`rounded-full border px-1.5 py-0.5 text-[10px] font-medium leading-none` + 分类色 `border-entity-N/40 bg-entity-N/10 text-entity-N`。
- Badge：`rounded-sm px-1.5 py-0.5 text-[10px] font-semibold`；muted `bg-fg/10 text-muted` / accent `bg-accent text-accent-fg`。
- 状态 pill（结果卡片）：`rounded-full border px-2 py-0.5 text-[10px] font-medium`；enabled `border-secondary/40 bg-secondary/10 text-secondary`。
- MD3 chip：`rounded-full px-2 py-0.5 text-[11px] font-medium`；neutral `bg-surface-container-highest text-on-surface-variant` / on `bg-accent text-accent-fg`。

### 7.5 `FloatWindow`（唯一浮窗范式，`components/FloatWindow.tsx`）

```
fixed z-[60] flex max-h-[70vh] max-w-[90vw] flex-col overflow-hidden
rounded-md border border-line bg-bg shadow-xl
```
- 默认 `width = 520`；**可拖动（Pointer Events + setPointerCapture）、无遮罩、Esc 关闭**。
- 标题栏：`flex h-9 shrink-0 cursor-grab touch-none select-none items-center justify-between gap-2 border-b border-line bg-fg/[0.03] pl-3.5 pr-2 active:cursor-grabbing`；标题 `min-w-0 truncate text-xs font-medium text-fg`；关闭钮 `flex h-6 w-6 rounded-sm text-muted hover:bg-fg/[0.06] hover:text-fg`。
- 内容区：`scrollbar-slim min-h-0 flex-1 select-none overflow-y-auto p-3.5`。
- 初始位置：水平居中、垂直 15% 视口高；拖动 clamp 保证不全出视口。
- 全项目所有弹窗（模型编辑、提示词查看、chunk 检查、子代理结论、原始上下文、工作空间对话框）均复用它。

### 7.6 `Toast`（`components/Toast.tsx`）

- 外层：`pointer-events-none fixed inset-x-0 top-5 z-[70] flex justify-center px-4`。
- 卡片：`pointer-events-auto flex max-w-xl cursor-pointer items-start gap-2.5 rounded-lg border border-line border-l-4 bg-bg/95 px-4 py-3 shadow-lg backdrop-blur-md transition-all duration-300`。
  - error 左条 `border-l-hint`；success 左条 `border-l-emerald-500`。
- 入场 `translate-y-0 opacity-100` / 退场 `-translate-y-3 opacity-0`。
- 图标 `mt-0.5 h-4 w-4 shrink-0`（error `text-hint`，success `text-emerald-500`）。
- **停留：error 6000ms / success 4500ms，再 300ms 后关闭**。
- **全站用 Toast 取代页内错误文案**。

### 7.7 `ChatNotifier`（页级通知气泡）

- 左上角 `fixed left-4 top-[72px] z-[65] flex flex-col gap-2`，卡片 `w-72 rounded-xl border border-line border-l-4 border-l-accent bg-bg/95 py-3 pl-3.5 pr-8 shadow-lg backdrop-blur-md`。
- 左滑淡入：`-translate-x-3 opacity-0` → `translate-x-0 opacity-100`（300ms）。
- 文案层级：10px uppercase 标签 + 13px 标题 + 11px accent 引导语。

### 7.8 图标规范（全站自绘，无图标库）

- 统一 `viewBox="0 0 24 24"`、`fill="none"`、`stroke="currentColor"`、`strokeLinecap/Linejoin="round"`。
- `strokeWidth` 约定：
  - `1.7 / 1.8`：文件类细线（Workspace）
  - `2`：通用 / 箭头 / 关闭 / 发送
  - `2.2`：放大镜手柄、思维链 chevron
  - `2.5`：步骤打勾
  - `3`：Spinner 圆环
- 实心图标（三点菜单、停止方块）用 `fill="currentColor"`。
- 无 `ring` 类使用（唯一例外：提示词拖拽落点 `ring-2 ring-accent ring-offset-2 ring-offset-bg`）。

---

## 8. 动效系统

| 场景 | 规格 |
|---|---|
| 默认过渡 | Tailwind `transition` = **150ms ease**（约 90% 交互态） |
| 弹窗 / 通知 | `transition-all duration-300 ease-out`（Toast、ChatNotifier、会话抽屉） |
| 渐隐交叉 | `duration-150`（Roundtable `MemberTile` 头像↔文字交叉渐隐） |
| 旋转 / 位移 | `transition-transform`（chevron `rotate-90/180`）、`transition-colors`（开关）、`transition-[stroke-dasharray] duration-300`（上下文占比环） |
| Spinner | `animate-spin`，圆环 r9 strokeWidth 3 + 弧 path |
| 呼吸点 | `animate-pulse`（心跳、等待中、未读、"生成中"小点、ExtractStep 属性蓝点） |
| 打字点 | `animate-bounce` ×3，`animationDelay` 0 / 150 / 300ms |
| 面板卸载延迟 | 图谱详情卡 fadeOut `duration-200` + `setTimeout(240ms)` 卸载 |
| **持续循环** | 仅图谱物理模拟（`requestAnimationFrame`，`alpha *= 0.98`，`alpha ≤ 0.02` 停帧）与 About 3D 知识球 |

---

## 9. 页面风格清单（逐页）

### 9.1 首页 `/`（`pages/Home.tsx`）

- Google 式：`min-h-[calc(100dvh-8.5rem)]` 撑满一屏并垂直居中。
- hero：`text-4xl font-semibold tracking-tight text-center`（「欢迎回来, 我的朋友!」）；副提示 `text-[12px] text-faint`。
- **搜索胶囊**：`max-w-2xl rounded-full border border-line py-1.5 pl-5 pr-1.5 hover:border-line-strong focus-within:border-secondary`；左内侧模式切换图标、输入 `text-[15px]`、右侧点缀色实底按钮 `rounded-full bg-accent px-6 py-2.5 text-sm`。
- **模式滑块**：`rounded-full border p-1 shadow-sm`，激活项 `bg-accent text-accent-fg`。
- **两页滚动模式**：搜索页与会话页各撑满一屏（`h-[calc(100dvh-8.5rem)]`），滚轮拦截使两页互不外溢。
- **聊天气泡**：
  - 用户：`max-w-[85%] rounded-2xl rounded-br-sm bg-fg px-4 py-2.5 text-[14px] text-primary-fg`（右对齐、非对称圆角）
  - AI：`border-l border-line pl-4 text-[14px] leading-relaxed`（左竖线、无气泡底）
- **思维链折叠块**：`rounded-lg border border-line bg-fg/[0.02]`；标题 `text-[11px] uppercase tracking-[0.12em] text-faint`；正文 `max-h-64` 滚动。
- **会话抽屉**：`fixed left-0 top-1/2 w-64 rounded-r-xl border-y border-r bg-bg shadow-xl`；L 形拉手 `h-12 w-6 rounded-r-md bg-accent`。
- **上下文占比环**：SVG r=15.915 strokeWidth=4，accent 弧 + `text-line` 底环。

### 9.2 搜索 `/search`（`pages/Search.tsx`）

- 结果区顶栏 `-mx-6 border-b border-line bg-bg` 吸顶 + `scrollbar-slim` 独立滚动。
- 结果条目（`components/ResultCard.tsx`）：`py-6` + `divide-y divide-line`；
  - 元信息行 `text-[11px] text-faint`：`uppercase tracking-[0.18em]` 类型标签 + `font-mono` chunkId + 右对齐 `font-mono` 得分（`toFixed(3)`）；
  - 标题 `text-[21px] font-bold leading-snug tracking-tight`；
  - 摘要 `text-[14px] leading-relaxed text-muted max-w-2xl`；
  - 命中高亮 `bg-mark`。

### 9.3 文档导入 `/import`（`pages/Import.tsx`）

`min-h-[calc(100dvh-8.5rem)]`；标题输入 + 大 textarea `min-h-[320px] rounded-md`；底部 `mt-auto` 贴底说明；路径 `font-mono text-[11px]`。

### 9.4 文档分析 `/analyze`（`pages/Analyze.tsx` + `ExtractStep.tsx` + `EnhanceStep.tsx`）

- **步骤条**：圆点 `h-7 w-7 rounded-full`（当前 `bg-accent text-accent-fg`、已完成 `border-secondary`、待办 `border-line text-faint`）+ 连接线 `h-px w-8 bg-line`。
- **文档表**：`rounded-md border` 容器；表头 `bg-fg/[0.03] text-xs text-faint`；行 `hover:bg-fg/[0.03]`；ID / 时间 `font-mono`；操作列用 `miniBtn`；删除键 `text-hint hover:border-hint/40`。
- **知识提取卡**（ExtractStep）：三分栏 SVG 卡片 `viewBox 0 0 320 106`，「点—边—点」结构；实体圆 r=26 用 12 色哈希调色板；状态色硬编码 `未评估 #94a3b8 / 已存在 #10b981 / 疑似 #f59e0b / 新实体 #3b82f6`；待添加属性蓝点 `animate-pulse bg-blue-500` + hover 紫色气泡 `#8b5cf6`。
- **分段编辑器**（`components/SegmentEditor.tsx`）：`max-h-133.5`(=534px) 容器；段块 `rounded-sm`；预览态 hover 浮出 `{label} · 点击编辑` 提示。

### 9.5 文档列表 / 详情 `/articles`、`/article/*`

- 列表：`min-h-[calc(100dvh-8.5rem)]`；行标题 `text-[13.5px] font-medium text-accent` + `group-hover:underline underline-offset-4`；分页为**圆形按钮** `h-[30px] w-[30px] rounded-full`（当前 `bg-accent text-accent-fg`）。
- 详情：**右侧悬浮目录** `fixed right-[calc(50%_+_35.5rem)] 2xl:block`（仅 2xl 可见）；`text-[10px] uppercase tracking-[0.2em]` 标题；条目左竖线，active `border-accent text-accent`。
- 摘要 `border-l border-line pl-3`。
- Markdown 映射（signature）：
  - **h1 / h2 / h3 全部 `font-bold text-accent` + `underline decoration-accent/40 decoration-1`**（h1 18px / offset 6px、h2 16px / offset 6px、h3 14.5px / offset 4px），均 `scroll-mt-6`；
  - p `text-[14px] leading-7`；
  - pre `bg-fg/[0.03] p-4 text-[12.5px] leading-6`；
  - 表格 `border-line` + th `bg-fg/5`。

### 9.6 知识图谱 `/graph`

见 §10.1。

### 9.7 多智能体 `/multi-agent`、`/multi-agent/*`

- 卡片墙 `grid gap-4 sm:grid-cols-2 lg:grid-cols-3`；卡 `rounded-2xl border p-4 hover:border-line-strong hover:bg-fg/[0.02]`；**新建卡虚线** `border-dashed`；备注 `line-clamp-2`。
- 成员详情：左栏 320px（`h-fit rounded-2xl border p-5`）+ 右栏自适应；头像 `h-28 w-28`，hover 遮罩 `bg-black/45` 显示「换头像」；`PickCard` 选中 `border-secondary bg-secondary/10`；勾选框 `h-3.5 w-3.5 rounded-[4px]`。

### 9.8 圆桌 `/roundtable`（`pages/Roundtable.tsx`）

- 主容器 `rounded-2xl border`；左栏 `w-[164px] lg:w-[220px] border-r`。
- 气泡外层 `max-w-[86%]`；成员气泡二级上限 `max-w-[560px]`；**统一 `rounded-2xl`（非非对称）**；成员气泡 `bg-surface-container-low/50`（唯一用容器令牌的气泡）；成员头像 `h-14 w-14`（左对齐动作用同尺寸透明占位对齐正文）。
- 时间戳 `font-mono text-[10.5px] text-faint`。
- `TypingDots`：三点 `animate-bounce`。
- `MemberTile`：`h-28 w-28`，头像↔文字交叉渐隐（`duration-150`）。
- 等待条 `bg-accent/10` + `animate-pulse` 点。
- 输入区 `rounded-2xl border focus-within:border-secondary`；发送钮 `h-11 w-11 rounded-full bg-accent`。

### 9.9 潜意识 `/subconscious`（**MD3 轨**）

- 顶部滑块 Tab（激活 `bg-fg text-bg` 反色）；七个子视图。
- 卡片 `rounded-2xl bg-surface-container-low px-4 py-4 shadow-sm`（实底、无描边）。
- `DASH_INPUT`：`rounded-lg border border-outline font-mono focus:ring-2 focus:ring-accent/30`。
- `StatCard` 数值 `text-[20px] font-semibold`（accent 或 on-surface）。
- 硬编码语义色：`emerald-500`（完成）/ `red-500`（失败）/ `sky-500` + `violet-500` + `amber-500`（RAG 三路）。
- 交互 `hover:bg-fg/8`、`active:bg-fg/12`。

### 9.10 工作空间 `/workspace`（**MD3 轨**）

- h1 `text-[22px] font-semibold tracking-tight text-on-surface`（全站最大标题）。
- 四档按钮 filled / tonal / outlined / text + 图标钮 `h-8 w-8`。
- `CARD = rounded-2xl bg-surface-container-low p-5`。
- 左列表 320px + 右文件浏览器；文件行 `rounded-xl px-2.5 py-2 hover:bg-surface-container-high`；文件名 `hover:text-accent hover:underline`；时间 / 大小 `font-mono text-[11px]`。
- 文件类图标描边 1.7 / 1.8。

### 9.11 设置 `/settings`（`pages/Settings.tsx`）

- Section：`mt-12` 间距 + 标题行 `border-b border-line pb-3`（`text-[15px] font-semibold` + `text-[12px] text-faint`）。
- 模型列表 `divide-y divide-line rounded-md border`（描边容器 + 分割线）。
- **危险区**：`rounded-md border border-red-500/40 bg-red-500/[0.04]`（全站唯一红底）；`btnDanger` 硬编码 `red-600/700`；重置需输入「确认」。

### 9.12 统计 `/stats`（`pages/Stats.tsx`）

**无圆角**策略最明显的一页：
- 总量卡 `border border-line p-5`；`TOTAL TOKENS` `text-[11px] tracking-[0.2em] text-faint` + 数值 `font-mono text-[34px] leading-none font-semibold`。
- 范围切换胶囊。
- 分布卡 `border border-line p-5`；条目占比条 `h-1 bg-fg/5` + 填充 `bg-hint`（最小 2% 宽）。
- 趋势图容器 `h-[260px]`。

### 9.13 关于 `/about`（`pages/About.tsx`）

- 主 Tab 胶囊。
- **Hero**：`min-h-[440px] sm:540 lg:600`；右侧越界 3D 知识球（canvas）；整块 `font-mono`；装饰线 `h-px w-8 bg-line-strong`；标题 `text-[30px] sm:text-[40px] font-semibold tracking-tight`（含 `text-accent` 强调词）；正文 `max-w-[52ch] text-[12.5px] leading-[1.9]`；角落标注 `text-[10px] tracking-[0.2em]`。
- **业务流程**时间线：`h-8 w-8 rounded-full bg-accent text-accent-fg` 序号 + `w-px flex-1 bg-line` 连线 + `RouteLink` 胶囊「前往X →」。
- **数据流向**：见 §10.2。

### 9.14 提示词面板（`pages/PromptsPanel.tsx`）

- **便签式长条（signature）**：`note-tone-accent / secondary / primary` 现混底色 + `rounded-xl border border-line py-2 pl-2 pr-2.5 shadow-sm`。
- 运行时素材：`note-tone-neutral` + `border-dashed`。
- 拖拽手柄六点、类型胶囊、`miniBtn` 查看、省略号菜单 `w-[268px] rounded-xl shadow-xl`。
- 大类标签**反色实底** `border-fg bg-fg text-bg`。
- 拖拽落点 `ring-2 ring-accent ring-offset-2 ring-offset-bg`。
- 便签色调混比：accent 11% / secondary 13% / primary 7% / neutral 4%（`@utility note-tone-*`）。

---

## 10. 图形与图表语言（第四套视觉体系）

### 10.1 知识图谱（双引擎）

| 维度 | 自研 SVG（`components/GraphCanvas.tsx`） | ECharts（`components/GraphECharts.tsx`） |
|---|---|---|
| 节点半径 | `min(16, (em ? 11 : 8) + degree * 2)` | `symbolSize = min(24, (em ? 14 : 12) + degree * 2)` |
| 节点描边 | `stroke var(--c-bg)`，粗细 `em ? 1.5 : 1` | `borderColor em ? fg : bg`，`borderWidth em ? 2.5 : 1.5` |
| 标签 | `fontSize 13`，`paintOrder:stroke` + `stroke=bg` + `strokeWidth 4`（文字光晕遮线） | `position:bottom`，`textBorderColor bg`，`textBorderWidth 3` |
| 边 | 二次贝塞尔，`hi ? 1.6 : 1`，`stroke hi ? fg : line`；多重边垂线错开 `±(slot-(n-1)/2) * d * 0.14` | `autoCurveness`，`hi ? 1.8 : 1` |
| 箭头 marker | 双档 `gc-arr-weak/strong`（6.5 / 7px） | `edgeSymbol:['none','arrow']`，size 6 |
| 标签显示阈值 | 边数 ≤ 18 | 同 |
| 居中 / 选中环 | 中心 `r+5` `stroke-opacity .45`；选中 `r+3` `strokeWidth 1.2` | `emphasis.focus:'adjacency'` `scale:1.15` |
| 力参数 | `spacing 130 / repulsion .012 / spring .1 / springScale .9 / center .012 / anchorBoost 10 / decay .86 / maxSpeed .12`；冷却 `alpha *= 0.98`，阈值 `0.02` | `repulsion 280 / edgeLength [50,120] / gravity .12 / friction .6 / initLayout circular` |
| 分类色 | `typeColorMap`：类型排序后黄金角 `137.508°`，`s 0.68 l 0.55` → **hex**（必须 hex，否则 zrender 解析失败） | 同源同一 Map |

**悬浮控件**统一 `rounded-full border border-line bg-bg/85 backdrop-blur-md shadow-lg`（顶部工具栏 z-30、统计胶囊 z-10、锚点入口 z-30、图例面板 `w-60 rounded-2xl z-20`、详情卡 `w-72 rounded-2xl z-20`）；新建实体弹窗不压暗画布；空态卡 `w-[380px] rounded-2xl bg-bg/90 z-10`。

### 10.2 数据流向（`pages/DataFlow.tsx`）

- 分层树图：`COL 132 / LVL 128 / PAD_L 132 / PAD_T 30 / PAD_R 36 / PAD_B 54`，6 次 barycenter 降交叉。
- 节点半径：`root 15 / table 8 / 其它 12.5`。
- 14 种 `kind` 的颜色**全部走语义令牌**（`--c-accent/muted/hint/entity-1..4/fg`）。
- 层背景带 `rect rx=12 fill=fg opacity=0.03`；层标签 `fontSize 11 fill faint`。
- 边：`hi ? accent : line-strong`，`opacity dim ? 0.08 : lit ? 1 : 0.5`，`transition opacity .15s, stroke-width .15s`。
- 节点 hover 环 `r+6 stroke accent 1.5 opacity .55`；非邻接 `opacity 0.18`。
- 画布 `h-[64vh] min-h-[460px] rounded-md border cursor-grab`；三层图例 + `± / ⤢` 缩放钮 `h-7 w-7 rounded`。
- Tooltip `fixed z-[80] w-80 rounded-md bg-bg p-3.5 shadow-xl`。

### 10.3 图表（`components/StatsTrendChart.tsx`）

- 折线 `smooth:true symbol:none width:2`，`areaStyle.opacity 0.08`。
- **输入 = `--c-hint`，输出 = `--c-accent`**。
- `grid {left:8, right:12, top:30, bottom:4, containLabel:true}`。
- 图例 `icon:'roundRect' itemWidth:10 itemHeight:3 textStyle muted 11px`。
- x / y 轴标签 `faint 11px`；分割线 `line opacity .6`；tooltip 背景 `bg`、边框 `line`、文字 `fg`。
- y 轴 `≥ 10000 → X万`。
- **canvas 不能读 CSS 变量**，故通过 `useThemeState().vars` 取 hex 兜底（`#4b74ff` / `#8aa0ff` / …），`vars` 变化即重建 option。

### 10.4 3D 知识球（About · Orb）

- 132 点斐波那契球面 + 11 纬度带环 + 最近邻连线。
- `FOCAL 3.1 / TILT 0.42 / R = min(w,h) * 0.46`；枢纽点（前 10%）用 accent 且缓慢呼吸（`sin(t*1.1+i)*0.16`）。
- 球面轮廓 `line alpha 0.12`；关系线 `edge`，`alpha = a*(0.26+k*0.4)`，`lineWidth 0.8+k*0.5`。
- `dpr = min(devicePixelRatio, 2)`。
- 配色用 `getComputedStyle` 读 `--c-*` 并挂 `MutationObserver` 随主题重读。

---

## 11. 品牌与桌面壳

- 品牌单一真源 `branding.json`：
  ```
  displayName "Slime"
  slug        "slime"
  tagline     "尝试像人类一样思考和学习"
  identifier  "cc.ginpika.minis"
  ```
- 派生到：网页标题（Vite 构建期注入 `__BRAND_TITLE__`）、footer、Tauri 窗口、Docker 容器名、终端名前缀。
- 桌面引导页（§2C）为**独立第三套语言**：460px 居中单列、卡片 12px 圆角、`--accent` 为绿色（浅 `#0f9d58` / 深 `#32f08c`）、状态点 8px 圆点 + `pulse` 动画、左下角胶囊用户按钮 + 320px 用户菜单、顶部失效横幅。**不复用前端主题令牌**。

---

## 12. 后端侧的设计约定（与 UI 的接口）

后端（Spring Boot）本身无视觉层，但有三点直接约束前端观感：

1. **主题无关**：后端不存主题；主题完全前端本地（`localStorage`），后端仅提供数据。
2. **SSE 流式**：`/api/chat`、潜意识观测台等走长连接流式（nginx `proxy_buffering off`、`proxy_read_timeout 300s`），决定了前端 `thinking → trace 分段 → 逐段折叠` 的**渐进式渲染**（流式期间纯文本 + 结束后 markdown 重解析）。
3. **配置即样式开关**：`application.yaml` 的 `minis.*` 参数影响 UI 分支——`desktop.enabled` 决定是否校验 `X-Minis-Token`；`subconscious.enabled / tick-ms / max-agents-per-beat` 决定潜意识页心跳卡文案与节拍；`fast.web.enabled`、`fast.*-top-k`、`rerank-model` 决定搜索页 / Rerank 开关展示；`sso.admin-usernames` 决定管理入口。
4. **API 风格**：REST + `/api/{domain}/...`；DTO 用 Java record；统一 `SsoInterceptor` 鉴权（`/api/health`、静态资源、logout 放行）。

---

## 13. AI 视角的设计评估（问题与改进点）

### 优点

- 令牌驱动彻底：28 个语义令牌 + `mix()` 现混派生，**任意三色输入自动得到协调的浅 / 深主题**，无需手工配板。这是本项目最强的架构性设计决策。
- 层次表达克制且一致：靠「1px 线 + 同色 2–8% 叠加 + 描边胶囊」而非阴影 / 渐变，高密度信息下依然清爽。
- 交互范式统一：所有弹窗走 `FloatWindow`、所有提示走 `Toast`、所有下拉自绘且「点外 / Esc 关闭」一致，学习成本低。

### 问题（按严重度）

1. 🔴 **三套设计语言并存**：Tailwind 轨 vs MD3 轨（Subconscious / Workspace）vs 桌面壳独立 CSS。圆角（`rounded-sm` vs `rounded-2xl` vs `8/12px`）、卡片范式（描边 vs 实底）、开关尺寸（`h-[18px] w-8` vs `h-6 w-11`）都不统一。同产品内切换页面会有明显"换了套 UI"的割裂感。
   **建议**：把 MD3 令牌收敛为一组 Tailwind 语义别名，或反向统一到单轨。
2. 🟠 **字号谱系过于离散**：出现 10 / 10.5 / 11 / 11.5 / 12 / 12.5 / 13 / 13.5 / 14 / 14.5 / 15 / 16 / 17 / 18 / 20 / 21 / 22 / 30 / 34 / 40 共 20 档、0.5px 步进，缺少 scale 约束，长期维护易漂移。
   **建议**：定义 6–8 档 type scale 令牌。
3. 🟠 **硬编码语义色未纳入主题**：`red-500/600/700`（危险区）、`emerald-500`（Toast 成功）、`sky/violet/amber-500`（RAG 三路）、`blue-500`（属性点）、`#8b5cf6`（属性气泡）、ExtractStep 的 12 色实体板——这些在深色 / 自定义主题下不随之调整，可能出现对比度不足。
   **建议**：为 success / warning / danger / info 建立令牌。
4. 🟡 **`bg-fg/[0.02]` 等 8 档透明叠加**缺乏命名，靠记忆取值；`--c-accent` 与 `--color-accent` 两种变量写法混用（PropsEditor vs Workspace）。
5. 🟡 **直角 vs 大圆角两派**并存（Stats / Settings 无圆角 vs MultiAgent / Roundtable `rounded-2xl`），需明确"何时用哪一派"的规则文档。
6. 🟢 **无障碍**：`aria-label / aria-expanded / aria-pressed / aria-current` 覆盖较好；但 Focus Visible 主要依赖浏览器默认（除输入框 `ring-2`），键盘导航时轮廓在深色主题下可能不可见。

---

## 14. 复现速查（最小可行清单）

若要让另一个 AI 复刻本项目 UI，只需给定：

1. **三色 + 底色 + 深浅**（默认 `#0f0f0f / #8b8f96 / #0f0f0f / #ffffff / light`）；
2. **§3.2 的 18 条推导公式**（生成全部令牌）；
3. **§4 字号谱系 + §5 圆角谱系 + §5.3 阴影策略（正文卡无阴影）**；
4. **§7.1 五式按钮 + §7.2 INPUT 字符串 + §7.5 FloatWindow + §7.6 Toast + §7.3 开关尺寸**；
5. **§6 页壳（`h-screen` + `max-w-6xl px-6`）与三种滚动条 utility**；
6. **§7.8 图标规范（24×24 / currentColor / strokeWidth 2）**；
7. 明确标注：**Subconscious、Workspace 使用 MD3 轨，桌面引导页自成一套**。
