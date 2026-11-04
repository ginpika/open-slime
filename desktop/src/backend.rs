//! 本机后端的生命周期：拉起内置 JRE + fat jar，盯住 stdout 等 READY 握手，再让窗口跳过去。
//!
//! 运行时（jlink 裁剪出的 jre/ + 后端 jar）由 `desktop/assemble-runtime.ps1`（Windows）
//! 或 `desktop/assemble-runtime.sh`（macOS / Linux）组装到
//! `desktop/runtime/`，打包时作为 Tauri resources 随应用分发，运行期从 resource_dir 取。
//! 开发时可用环境变量 `MINIS_DESKTOP_RUNTIME` 直接指向该目录，省去每次重新打包。

use crate::{config, emit_status, enter_instance, Shell};
use std::path::{Path, PathBuf};
use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

/// 后端启动握手行的前缀（见后端 DesktopReadyReporter）
const READY_MARKER: &str = "MINIS_READY port=";

/// 运行时里那个可执行 jar 的文件名（assemble 脚本按它落盘；名称随 branding.json 的 slug 变）
const JAR_NAME: &str = concat!(env!("BRAND_SLUG"), "-backend.jar");

/// Windows 路径前缀常量（verbatim / UNC verbatim）
const VERBATIM_UNC: &str = r"\\?\UNC\";
const VERBATIM: &str = r"\\?\";

/// 剥掉 Windows 的 verbatim 前缀。
///
/// <p>Rust 的 `std::env::current_exe()`（以及 Tauri 由它派生的 `resource_dir()`）在 Windows 上
/// 可能返回 `\\?\D:\...` 这种 lengthened 形式。用它做存在性判断没问题，**但当成命令行参数交给
/// 子进程会出事**：`java -jar \\?\D:\...\x.jar` 能读到 manifest 里的 Main-Class，却把 classpath
/// 拼成非法 URL，最终报
/// `ClassNotFoundException: org.springframework.boot.loader.launch.JarLauncher`。
/// 所以凡是递给子进程的路径，先用它归一化。
fn plain_path(path: PathBuf) -> PathBuf {
    let text = path.to_string_lossy();
    if let Some(rest) = text.strip_prefix(VERBATIM_UNC) {
        return PathBuf::from(format!(r"\\{rest}"));
    }
    if let Some(rest) = text.strip_prefix(VERBATIM) {
        return PathBuf::from(rest);
    }
    path
}

/// 运行时目录：按优先级挑一个**真实存在**的。
///
/// <p>dev 与打包后的路径来源不同（resource_dir 在 dev 指向 target/debug，打包后指向安装目录），
/// 逐个探测比依赖某一种行为更稳；最后一个候选是源码树里的 desktop/runtime（编译期常量），
/// 让 `tauri dev` 不依赖资源拷贝也能直接跑。
fn runtime_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(dir) = std::env::var("MINIS_DESKTOP_RUNTIME") {
        candidates.push(plain_path(PathBuf::from(dir)));
    }
    if let Ok(dir) = app.path().resolve("runtime", BaseDirectory::Resource) {
        candidates.push(plain_path(dir));
    }
    if let Some(exe_dir) = std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf)) {
        candidates.push(plain_path(exe_dir).join("runtime"));
    }
    candidates.push(plain_path(PathBuf::from(env!("CARGO_MANIFEST_DIR"))).join("runtime"));

    for dir in &candidates {
        if java_launcher(dir).exists() && dir.join(JAR_NAME).exists() {
            eprintln!("[minis-desktop] 使用运行时目录 {}", dir.display());
            return Ok(dir.clone());
        }
    }
    let assemble_command = if cfg!(windows) {
        "powershell -ExecutionPolicy Bypass -File assemble-runtime.ps1"
    } else {
        "bash assemble-runtime.sh"
    };
    Err(format!(
        "未找到内置后端运行时；已尝试：{}。请先在 desktop/ 目录执行 {assemble_command} 组装",
        candidates
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join(" | ")
    ))
}

/// jlink 运行时里启动器的位置（Windows 用 javaw 避免弹控制台窗口，仍可读管道）
fn java_launcher(runtime: &Path) -> PathBuf {
    if cfg!(windows) {
        runtime.join("jre").join("bin").join("javaw.exe")
    } else {
        runtime.join("jre").join("bin").join("java")
    }
}

/// 启动时把「本机模式能不能起」打一行日志：路径不对时不用点按钮就能看出来
pub fn log_probe(app: &AppHandle) {
    match runtime_dir(app) {
        Ok(dir) => {
            let jar = dir.join(JAR_NAME);
            eprintln!(
                "[minis-desktop] 本地服务器运行时 {}（java 存在={}，jar {} 字节）",
                dir.display(),
                java_launcher(&dir).exists(),
                std::fs::metadata(&jar).map(|m| m.len()).unwrap_or(0)
            );
        }
        Err(err) => eprintln!("[minis-desktop] {err}"),
    }
}

/// 拉起本机后端。失败（运行时未组装等）只报状态，不 panic —— 引导页会把原因显示出来。
pub fn start(app: &AppHandle, token: &str) -> Result<(), String> {
    let runtime = runtime_dir(app)?;
    let java = java_launcher(&runtime);
    let jar = runtime.join(JAR_NAME);

    // 排查「起不来」的第一手证据：实际用的路径 + jar 大小（可执行 fat jar 约 60MB，瘦包只有几 MB）
    eprintln!(
        "[minis-desktop] java={} jar={} ({} 字节)",
        java.display(),
        jar.display(),
        std::fs::metadata(&jar).map(|m| m.len()).unwrap_or(0)
    );

    // 数据落各平台的应用数据目录：Windows %APPDATA%\minis、macOS ~/Library/Application Support/minis
    // （同样归一化，避免 verbatim 前缀混进 JDBC URL 与日志路径）
    let data = plain_path(app.path().app_data_dir().map_err(|e| e.to_string())?);
    for sub in ["article", "logs"] {
        std::fs::create_dir_all(data.join(sub)).map_err(|e| e.to_string())?;
    }

    emit_status(app, "boot", "正在启动本地服务器…");

    // 引导页当前选中的用户（用户名 / 头像）随环境变量注入：后端以它顶替 local-dev。
    // 当前选的是 hostid 用户就注入该身份，否则注入本地用户；都为空则走后端默认。
    let cfg = config::load(app);
    let (user_name, user_avatar) = if cfg.current_user == "sso" && !cfg.sso_user_name.is_empty() {
        (cfg.sso_user_name, cfg.sso_user_avatar)
    } else {
        (cfg.local_user_name, cfg.local_user_avatar)
    };

    let (mut rx, child) = app
        .shell()
        .command(java.to_string_lossy().to_string())
        .args([
            "-jar".to_string(),
            jar.to_string_lossy().to_string(),
            "--server.address=127.0.0.1".to_string(),
            "--server.port=0".to_string(),
        ])
        .env("MINIS_DESKTOP", "1")
        .env(
            "MINIS_COMPUTER_WINDOW_HELPER",
            std::env::current_exe()
                .map_err(|e| format!("无法定位窗口截图程序：{e}"))?
                .to_string_lossy()
                .to_string(),
        )
        .env("MINIS_LOCAL_TOKEN", token)
        .env("MINIS_LOCAL_USER_NAME", user_name)
        .env("MINIS_LOCAL_USER_AVATAR", user_avatar)
        .env(
            "MINIS_DB_PATH",
            data.join("minis.db").to_string_lossy().to_string(),
        )
        .env(
            "MINIS_ARTICLE_DIR",
            data.join("article").to_string_lossy().to_string(),
        )
        .env(
            "MINIS_LOG_FILE",
            data.join("logs").join("minis.log").to_string_lossy().to_string(),
        )
        .spawn()
        .map_err(|e| format!("启动本地服务器失败：{e}"))?;

    remember_child(app, child);

    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(bytes) => {
                    let line = String::from_utf8_lossy(&bytes);
                    // 原样转发：后端起不来时，失败现场就在这儿（Spring Boot 把启动异常也写 stdout），
                    // 只挑 READY 行等于把最关键的诊断信息丢掉
                    print!("[minis-backend] {line}");
                    if let Some(port) = parse_ready(&line) {
                        if let Some(shell) = handle.try_state::<Shell>() {
                            *shell.port.lock().unwrap() = Some(port);
                        }
                        emit_status(&handle, "ready", &format!("本地服务器已就绪（端口 {port}）"));
                        enter_instance(&handle, &format!("http://127.0.0.1:{port}/"), "local");
                    }
                }
                CommandEvent::Stderr(bytes) => {
                    eprint!("[minis-backend] {}", String::from_utf8_lossy(&bytes));
                }
                CommandEvent::Terminated(payload) => {
                    emit_status(
                        &handle,
                        "error",
                        &format!("本地服务器已退出（code={:?}），请重开应用重试", payload.code),
                    );
                    break;
                }
                _ => {}
            }
        }
    });
    Ok(())
}

/// 从一行日志里取端口；不是握手行返回 None
fn parse_ready(line: &str) -> Option<u16> {
    let idx = line.find(READY_MARKER)?;
    line[idx + READY_MARKER.len()..]
        .trim()
        .split(|c: char| !c.is_ascii_digit())
        .next()
        .and_then(|s| s.parse::<u16>().ok())
}

fn remember_child(app: &AppHandle, child: CommandChild) {
    let shell = app.state::<Shell>();
    *shell.child.lock().unwrap() = Some(child);
}

/// 本次会话已就绪的端口（引导页再点一次「本机」时据此直接跳转，不重复拉起）
pub fn known_port(app: &AppHandle) -> Option<u16> {
    app.try_state::<Shell>()
        .and_then(|shell| *shell.port.lock().unwrap())
}

/// 退出前收尾：先请后端正常退出（关掉它自己），拿不到就强杀
pub fn stop(app: &AppHandle) {
    let shell = app.state::<Shell>();
    let child = shell.child.lock().unwrap().take();
    if let Some(child) = child {
        let _ = child.kill();
    }
}
