# 桌面端开发

`npm run dev` 会编译并启动 Tauri 桌面壳；本机模式还需要提前组装后端运行时。

## macOS / Linux

需要 Node/npm、Rust/Cargo 和完整的 JDK 21+，以及当前平台的 Tauri 构建依赖。
首次使用时，在仓库根目录执行：

```bash
npm --prefix frontend ci
npm --prefix desktop ci
bash desktop/assemble-runtime.sh
cd desktop
npm run dev
```

脚本会同步品牌配置、构建前端、通过 Maven Wrapper 的 `desktop` profile
将前端打进后端 JAR，再用 `jlink` 生成当前平台的 JRE。首次运行 Maven Wrapper
可能需要下载 Maven 和依赖。打包过程跳过测试。

macOS 未设置 `JAVA_HOME` 时，脚本先查找已注册的 JDK 21，再查找 Homebrew 的
`openjdk@21`；也可以显式选择已安装的 JDK，例如：

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
```

产物位于 `desktop/runtime/<slug>-backend.jar` 和 `desktop/runtime/jre/bin/java`。
JRE 与操作系统、CPU 架构绑定，需要在目标平台分别组装。

日常更新可以在 `desktop/` 目录执行：

```bash
# 仅后端变化：复用前端产物和 JRE
bash assemble-runtime.sh --skip-frontend --skip-jre

# 前端、后端变化：重新构建，复用 JRE
bash assemble-runtime.sh --skip-jre
```

切换 JDK 或调整模块集后，去掉 `--skip-jre` 重新生成运行时。
仅修改 Rust 壳或 `desktop/ui/` 时，直接 `npm run dev`。

## Windows

在 `desktop/` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File assemble-runtime.ps1
npm run dev
```

Windows 脚本支持 `-SkipFrontend` 和 `-SkipJre`，参数含义与 `.sh` 相同。
