# Slime Logo 基底与底形候选

日期：2026-10-30

本轮确认的两种基底：**graphite 黑色**（首轮 A）与 **yellow 黄色**（首轮 C，用户随后澄清选择黄色）。青绿色不属于本次交付。

## 文件

- `bases/`：两张获选基底 PNG，保留生成原图尺寸与透明通道。
- `generated/`：黑色、黄色各三种底形的生成原图：圆形 circle、浅冰蓝圆角方形 rounded-square、macOS 白底圆角 macos-white。
- `png/`：六种候选的 1024 × 1024 PNG 导出。
- `macos/`：两种白底 macOS 候选的 .icns 文件及 .iconset 多尺寸源图。
- `prompts.md`：完整生成提示词。图像使用内置 image_gen 生成；sips / iconutil 仅用于尺寸导出和格式打包。
- `export-icons.mjs`：在 macOS 执行 `node export-icons.mjs` 可由本目录 generated 原图重新导出 PNG、iconset 与 icns。

桌面客户端已选用 `png/slime-graphite-macos-white-1024.png`，通过 Tauri CLI 生成并接入 `desktop/icons/` 的 PNG、macOS ICNS 和 Windows ICO / Appx 图标，用于 macOS Dock 与 Windows 任务栏及安装包。其余文件保留为候选；`frontend/public` 的图标尚未调整。

## macOS 说明

本项目 desktop/tauri.conf.json 当前通过 desktop/icons/icon.icns 打包 Mac 图标，因此附带可供该流程使用的传统 ICNS：16、32、128、256、512 pt 的 1× / 2×，最大像素图为 1024 × 1024。白色圆角底已绘制，画布外保留透明。

Apple 的新图标流程提供 Icon Composer 分层 .icon 格式；本目录的 ICNS 不应误称为分层 .icon 文件。后续如迁移 Icon Composer，应使用完整方形背景层和独立前景层重新组合，避免重复圆角遮罩。

参考：[Apple App icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)、[Icon Composer](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer)。

