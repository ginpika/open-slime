//! minis 桌面壳（Tauri 2）：把「本机后端」与「远程后端」两种模式收在同一个窗口里。
//!
//! - 本机模式：壳拉起内置 JRE + fat jar 作为 sidecar，只绑回环、随机端口；壳每次启动生成
//!   一次性令牌，既作为环境变量传给后端（DesktopTokenFilter 校验），也在页面脚本执行前注入
//!   `window.__MINIS_LOCAL_TOKEN__`，前端统一在请求头带回来。固定本地单用户，不走 SSO。
//! - 远程模式：不启动 sidecar，窗口直接加载服务端站点，认证完全交由服务端的 hostid SSO。
//!
//! 两种模式的共同点：窗口最终都落在一个 **同源 HTTP 地址** 上（本机 `http://127.0.0.1:{端口}`、
//! 远程 `https://{域名}`），所以前端那份「相对 /api + Cookie + SSE」的代码一行都不用改。
//!
//! 启动一律停在自带的引导页（ui/index.html）：由用户当场选本机还是远程，壳不自动连任何一个。
//! 选本机就拉起 sidecar、就绪后跳到本机地址；选远程直接把窗口开到服务端站点。选择会写进配置，
//! 下次进来在引导页上做默认高亮。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod config;

use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_shell::process::CommandChild;

/// 引导页订阅的状态事件名
const STATUS_EVENT: &str = "minis://status";
/// 引导页订阅的用户事件名（当前用户 / 本地用户 / hostid SSO 用户有变即推一份）
const USER_EVENT: &str = "minis://user";
/// 引导页订阅的会话事件名（启动校验 hostid 会话的结果）
const SESSION_EVENT: &str = "minis://session";
/// 主窗口标签（capabilities 里也按它授权）
const WINDOW_LABEL: &str = "main";
/// 专用登录窗口标签（登录 hostid 时弹出，成功即关）
const LOGIN_LABEL: &str = "hostid-login";
/// 后台会话校验窗口标签（隐藏，校验完即销毁）
const SESSION_LABEL: &str = "hostid-session-check";
/// 登录窗口回传登录用户用的同源路径（见 desktop_login_hostid）
const SSO_DONE_PATH: &str = "/__minis_sso_done";
/// 会话校验窗口回传结果用的同源路径（见 run_session_check）
const SESSION_DONE_PATH: &str = "/__minis_session_check";

/// 壳的当前状态（引导页据此渲染）
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    mode: config::Mode,
    remote_base_url: String,
    /// idle=待选择 / boot=正在启动本机后端 / ready=已跳转本机后端 / remote=已跳转远程 / error=失败
    phase: String,
    detail: String,
}

/// 本地用户（引导页左下角用户菜单编辑）：进入本机模式后顶替后端的 local-dev
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalUser {
    name: String,
    /// data:image/...;base64,... 的小图；空 = 用首字母兜底
    avatar: String,
}

/// hostid SSO 用户（登录窗口回传后展示在引导页用户菜单里）
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SsoUserView {
    name: String,
    /// 头像地址（hostid avatarUrl，可能为空 → 前端用首字母兜底）
    avatar: String,
    email: String,
}

/// 引导页用户菜单的全量身份视图：当前选中的身份 + 本地用户 + hostid SSO 用户（未登录为 null）
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Users {
    /// 当前选中的身份："local" | "sso"
    current: String,
    local: LocalUser,
    sso: Option<SsoUserView>,
}

/// 从配置组装用户视图
fn users_snapshot(app: &AppHandle) -> Users {
    let cfg = config::load(app);
    Users {
        current: cfg.current_user,
        local: LocalUser {
            name: cfg.local_user_name,
            avatar: cfg.local_user_avatar,
        },
        sso: if cfg.sso_user_name.is_empty() {
            None
        } else {
            Some(SsoUserView {
                name: cfg.sso_user_name,
                avatar: cfg.sso_user_avatar,
                email: cfg.sso_user_email,
            })
        },
    }
}

/// 推送用户视图给引导页（页面不在引导页时无人接收，无副作用）
fn emit_users(app: &AppHandle) {
    let _ = app.emit(USER_EVENT, users_snapshot(app));
}

/// 启动时的 hostid 会话校验结果：applicable=当前用户是可校验的 hostid 会话；valid=会话是否仍有效
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionStatus {
    applicable: bool,
    valid: bool,
}

/// 推送会话校验结果给引导页
fn emit_session(app: &AppHandle, applicable: bool, valid: bool) {
    let _ = app.emit(SESSION_EVENT, SessionStatus { applicable, valid });
}

/// 壳的进程内状态：本机后端子进程句柄 + 一次性令牌 + 已就绪端口 + 对外状态
pub struct Shell {
    /// 本机模式的一次性令牌（每次启动重新生成）
    token: String,
    child: Mutex<Option<CommandChild>>,
    /// 本机后端本次会话已监听的端口（null = 还没起来）
    port: Mutex<Option<u16>>,
    status: Mutex<Status>,
}

/// 更新状态并推给引导页（页面不在引导页时无人接收，无副作用）
fn emit_status(app: &AppHandle, phase: &str, detail: &str) {
    let shell = app.state::<Shell>();
    let snapshot = {
        let mut status = shell.status.lock().unwrap();
        status.phase = phase.to_string();
        status.detail = detail.to_string();
        status.clone()
    };
    let _ = app.emit(STATUS_EVENT, snapshot);
}

/// 记下用户这次的选择（引导页下次打开时据此高亮）
fn set_choice(app: &AppHandle, mode: config::Mode, remote_base_url: &str) {
    let shell = app.state::<Shell>();
    let mut status = shell.status.lock().unwrap();
    status.mode = mode;
    status.remote_base_url = remote_base_url.to_string();
}

/// 让主窗口跳到某个地址（同源 HTTP 地址，见文件头说明）
fn navigate(app: &AppHandle, url: &str) {
    let Some(window) = app.get_webview_window(WINDOW_LABEL) else {
        return;
    };
    match url.parse::<tauri::Url>() {
        Ok(parsed) => {
            if let Err(err) = window.navigate(parsed) {
                emit_status(app, "error", &format!("打开 {url} 失败：{err}"));
            }
        }
        Err(err) => emit_status(app, "error", &format!("地址无效（{url}）：{err}")),
    }
}

/// 引导页读取当前状态
#[tauri::command]
fn desktop_state(shell: tauri::State<'_, Shell>) -> Status {
    shell.status.lock().unwrap().clone()
}

/// 读取用户菜单全量身份（当前用户 + 本地用户 + hostid SSO 用户）
#[tauri::command]
fn desktop_users(app: AppHandle) -> Users {
    users_snapshot(&app)
}

/// 切换当前用户（"local" | "sso"）；未登录 hostid 时不允许选 sso
#[tauri::command]
fn desktop_switch_user(app: AppHandle, kind: String) -> Result<Users, String> {
    let mut cfg = config::load(&app);
    match kind.as_str() {
        "local" => {}
        "sso" if !cfg.sso_user_name.is_empty() => {}
        "sso" => return Err("尚未登录 hostid".to_string()),
        other => return Err(format!("未知的用户类型：{other}")),
    }
    cfg.current_user = kind;
    config::save(&app, &cfg)?;
    let snapshot = users_snapshot(&app);
    let _ = app.emit(USER_EVENT, snapshot.clone());
    Ok(snapshot)
}

/// 保存本地用户（引导页用户菜单提交）；下次启动本机后端时随环境变量注入
#[tauri::command]
fn desktop_save_local_user(app: AppHandle, name: String, avatar: String) -> Result<(), String> {
    let mut cfg = config::load(&app);
    cfg.local_user_name = name.trim().to_string();
    cfg.local_user_avatar = avatar;
    config::save(&app, &cfg)?;
    emit_users(&app);
    Ok(())
}

/// 已记住的 hostid 服务端地址（引导页预填登录输入框）
#[tauri::command]
fn desktop_hostid_base(app: AppHandle) -> String {
    config::load(&app).hostid_base_url
}

/// 启动时校验当前 hostid 会话是否仍可用；结果经 minis://session 推给引导页。
/// 当前用户不是 hostid 身份（或从未登录）时直接回 applicable=false。
#[tauri::command]
fn desktop_check_session(app: AppHandle) {
    let cfg = config::load(&app);
    let applicable = cfg.current_user == "sso"
        && !cfg.sso_user_name.is_empty()
        && !cfg.hostid_base_url.is_empty();
    if !applicable {
        emit_session(&app, false, true);
        return;
    }
    std::thread::spawn(move || run_session_check(&app));
}

/// 用已记住的 hostid 地址重开登录窗口（会话失效时的一键重登）
#[tauri::command]
async fn desktop_relogin_hostid(app: AppHandle) -> Result<(), String> {
    let base = config::load(&app).hostid_base_url;
    if base.is_empty() {
        return Err("尚未配置 hostid 地址".to_string());
    }
    desktop_login_hostid(app, base).await
}

/// 隐藏窗口里跑一次会话校验：窗口加载 hostid 根，注入脚本调 /api/sso/session，
/// 结果经同源路径回传（on_navigation 拦截），随后销毁窗口并推送结果。
fn run_session_check(app: &AppHandle) {
    let Ok(base_url) = config::load(app).hostid_base_url.parse::<tauri::Url>() else {
        emit_session(app, true, false);
        return;
    };

    if let Some(old) = app.get_webview_window(SESSION_LABEL) {
        let _ = old.close();
    }

    let done_lit =
        serde_json::to_string(SESSION_DONE_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let script = format!(
        r#"(function () {{
  var done = {done};
  var tries = 0;
  function report(s) {{
    var payload = s || {{ authenticated: false }};
    location.href = done + '?data=' + encodeURIComponent(JSON.stringify(payload));
  }}
  function probe() {{
    tries++;
    fetch('/api/sso/session', {{ credentials: 'include' }})
      .then(function (r) {{ return r.ok ? r.json() : null; }})
      .then(function (s) {{ report(s); }})
      .catch(function () {{ if (tries < 3) setTimeout(probe, 400); else report(null); }});
  }}
  probe();
}})();"#,
        done = done_lit,
    );

    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let built = WebviewWindowBuilder::new(app, SESSION_LABEL, WebviewUrl::External(base_url))
        .title("session-check")
        .visible(false)
        .skip_taskbar(true)
        .inner_size(420.0, 320.0)
        .initialization_script(script)
        .on_navigation(move |nav| {
            if nav.path() == SESSION_DONE_PATH {
                let data = nav
                    .query_pairs()
                    .find(|(k, _)| &**k == "data")
                    .map(|(_, v)| v.to_string());
                let _ = tx.send(data.unwrap_or_default());
                return false;
            }
            true
        })
        .build();

    let payload = if built.is_err() {
        None
    } else {
        let payload = rx.recv_timeout(Duration::from_secs(10)).ok();
        if let Some(window) = app.get_webview_window(SESSION_LABEL) {
            let _ = window.close();
        }
        payload
    };
    // 只有确实拿到 authenticated 结论才下判断；取不到（网络不通 / 超时）不下结论，避免误报
    match payload
        .as_deref()
        .and_then(|p| serde_json::from_str::<SsoSession>(p).ok())
        .map(|s| s.authenticated)
    {
        Some(valid) => emit_session(app, true, valid),
        None => emit_session(app, false, true),
    }
}

/// 选本机模式：拉起后端（或复用本次会话已起来的那个），就绪后由 READY 分支把窗口跳过去
#[tauri::command]
fn desktop_choose_local(app: AppHandle) -> Result<(), String> {
    let mut cfg = config::load(&app);
    cfg.mode = config::Mode::Local;
    config::save(&app, &cfg)?;
    set_choice(&app, config::Mode::Local, "");

    let token = app.state::<Shell>().token.clone();
    if let Some(port) = backend::known_port(&app) {
        emit_status(&app, "ready", &format!("本地服务器已就绪（端口 {port}）"));
        navigate(&app, &format!("http://127.0.0.1:{port}/"));
        return Ok(());
    }
    backend::start(&app, &token)
}

/// 选远程模式：地址须为 http(s)，直接把窗口开过去（选了远程就不让本机后端继续跑着）
#[tauri::command]
fn desktop_choose_remote(app: AppHandle, url: String) -> Result<(), String> {
    let url = url.trim().trim_end_matches('/').to_string();
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("地址需以 http:// 或 https:// 开头".to_string());
    }
    let mut cfg = config::load(&app);
    cfg.mode = config::Mode::Remote;
    cfg.remote_base_url = url.clone();
    config::save(&app, &cfg)?;
    set_choice(&app, config::Mode::Remote, &url);

    backend::stop(&app);
    emit_status(&app, "remote", "正在打开远程服务…");
    navigate(&app, &url);
    Ok(())
}

/// hostid /api/sso/session 的响应（字段可能缺省）
#[derive(Deserialize)]
struct SsoSession {
    #[serde(default)]
    authenticated: bool,
    #[serde(default)]
    user: Option<SsoSessionUser>,
}

/// hostid 返回的用户（字段对齐 hostid 实码；nickname / avatarUrl 可能没有）
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SsoSessionUser {
    #[serde(default)]
    username: String,
    #[serde(default)]
    nickname: String,
    #[serde(default)]
    email: String,
    #[serde(default)]
    avatar_url: String,
}

/// 登录 hostid：弹出一个专用窗口，只用来在 hostid 上完成登录（拿到 SSO 会话 cookie）。
///
/// <p>按 docs/hositd-sso-integration-rust.md：客户端把浏览器重定向到 hostid 的登录页
/// `{hostid}/sso/login?redirect=xxx`；这里 redirect 指回 hostid 根，登录成功后 hostid 会把
/// 窗口送回该地址。窗口里注入一段脚本，届时调 `/api/sso/session` 取回用户，
/// 经同源路径 {@value #SSO_DONE_PATH} 回传给壳（on_navigation 拦截），随即将用户写入配置并关窗。
///
/// <p>关键：hostid 的一切跳转都只发生在登录窗口内，**主窗口（我们的 SSO 消费方客户端）
/// 始终不动** —— 不导航、不切模式、不停本机后端；登录窗口与主窗口共用 cookie 存储，
/// 会话随之生效，之后由用户在引导页上进入 minis 实例。
///
/// <p>必须是 async 命令：Windows 上在同步命令里创建窗口会死锁（wry#583），
/// 表现为新窗口白屏且整个应用卡死无法操作。
#[tauri::command]
async fn desktop_login_hostid(app: AppHandle, url: String) -> Result<(), String> {
    let base = url.trim().trim_end_matches('/').to_string();
    if !(base.starts_with("http://") || base.starts_with("https://")) {
        return Err("地址需以 http:// 或 https:// 开头".to_string());
    }
    let base_url = base
        .parse::<tauri::Url>()
        .map_err(|e| format!("地址无效（{base}）：{e}"))?;

    // 记下 hostid 地址：启动时据此校验会话、失效时可一键重登
    let mut cfg = config::load(&app);
    if cfg.hostid_base_url != base {
        cfg.hostid_base_url = base.clone();
        config::save(&app, &cfg)?;
    }

    // 登录后 hostid 送回的目标（hostid 根，保留其路径前缀）
    let redirect = {
        let mut u = base_url.clone();
        let path = format!("{}/", u.path().trim_end_matches('/'));
        u.set_path(&path);
        u.set_query(None);
        u.set_fragment(None);
        u
    };

    // hostid 登录页
    let mut login_url = base_url.clone();
    login_url.set_path("/sso/login");
    login_url.query_pairs_mut().append_pair("redirect", redirect.as_str());

    // 多开时先收掉旧的登录窗
    if let Some(old) = app.get_webview_window(LOGIN_LABEL) {
        let _ = old.close();
    }

    // 登录窗里注入脚本：后台轮询 hostid 的 /api/sso/session，一旦 authenticated 就取回用户，
    // 经同源路径回传（on_navigation 拦截）。不依赖登录后停在哪个页面 —— 登录页 / 成功页 /
    // 任意 hostid 页面都行，只要会话建立即可。
    let done_lit = serde_json::to_string(SSO_DONE_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let script = format!(
        r#"(function () {{
  var done = {done};
  var sent = false;
  function probe() {{
    if (sent) return;
    fetch('/api/sso/session', {{ credentials: 'include' }})
      .then(function (r) {{ return r.ok ? r.json() : null; }})
      .then(function (s) {{
        if (s && s.authenticated) {{
          sent = true;
          location.href = done + '?data=' + encodeURIComponent(JSON.stringify(s));
        }}
      }})
      .catch(function () {{ /* 尚未就绪，下一拍再试 */ }});
  }}
  probe();
  setInterval(probe, 800);
}})();"#,
        done = done_lit,
    );

    // 登录窗回传的用户 JSON
    let captured: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let captured_nav = captured.clone();

    WebviewWindowBuilder::new(&app, LOGIN_LABEL, WebviewUrl::External(login_url))
        .title("登录 hostid")
        .inner_size(520.0, 700.0)
        .min_inner_size(420.0, 560.0)
        .center()
        .initialization_script(script)
        .on_navigation(move |nav| {
            if nav.path() == SSO_DONE_PATH {
                // 脚本回传通道：收下用户 JSON 并拦掉这条导航
                let data = nav
                    .query_pairs()
                    .find(|(k, _)| &**k == "data")
                    .map(|(_, v)| v.to_string());
                *captured_nav.lock().unwrap() = Some(data.unwrap_or_default());
                return false;
            }
            true
        })
        .build()
        .map_err(|e| format!("打开登录窗口失败：{e}"))?;

    // 收到回传即写入配置并关窗；用户手动关窗则什么都不做（主窗口始终不动）。
    let handle = app.clone();
    let captured_poll = captured.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_millis(400));
        let Some(window) = handle.get_webview_window(LOGIN_LABEL) else {
            return;
        };
        if let Some(payload) = captured_poll.lock().unwrap().take() {
            persist_sso_user(&handle, &payload);
            let _ = window.close();
            return;
        }
    });
    Ok(())
}

/// 把登录窗口回传的会话写入配置，并把当前用户切到 hostid SSO 用户
fn persist_sso_user(app: &AppHandle, payload: &str) {
    let Ok(session) = serde_json::from_str::<SsoSession>(payload) else {
        return;
    };
    if !session.authenticated {
        return;
    }
    let Some(user) = session.user else {
        return;
    };
    let name = if user.nickname.trim().is_empty() {
        user.username
    } else {
        user.nickname
    };
    let name = name.trim();
    if name.is_empty() {
        return;
    }
    let mut cfg = config::load(app);
    cfg.sso_user_name = name.to_string();
    cfg.sso_user_avatar = user.avatar_url;
    cfg.sso_user_email = user.email;
    cfg.current_user = "sso".to_string();
    if config::save(app, &cfg).is_ok() {
        emit_users(app);
    }
}

fn main() {
    tauri::Builder::default()
        // 单实例必须最先注册：本机后端含 SQLite(WAL) 与进程内心跳/圆桌状态，不能双开
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window(WINDOW_LABEL) {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![
            desktop_state,
            desktop_users,
            desktop_switch_user,
            desktop_save_local_user,
            desktop_hostid_base,
            desktop_check_session,
            desktop_relogin_hostid,
            desktop_choose_local,
            desktop_choose_remote,
            desktop_login_hostid
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let cfg = config::load(&handle);

            // 令牌对非回环来源不生效：远程站点拿不到本机令牌，本机页面照常带上
            let token = uuid::Uuid::new_v4().simple().to_string();
            // 品牌名（来自根 branding.json，经 build.rs 注入）也随脚本注入，供引导页渲染标题 / 标语
            let brand = serde_json::json!({
                "name": env!("BRAND_NAME"),
                "slug": env!("BRAND_SLUG"),
                "tagline": env!("BRAND_TAGLINE"),
            });
            let injection = format!(
                "window.__BRAND__={brand};\nif (location.hostname === \"127.0.0.1\") {{ window.__MINIS_LOCAL_TOKEN__={}; }}",
                serde_json::to_string(&token).unwrap_or_else(|_| "\"\"".to_string())
            );

            app.manage(Shell {
                token,
                child: Mutex::new(None),
                port: Mutex::new(None),
                status: Mutex::new(Status {
                    mode: cfg.mode,
                    remote_base_url: cfg.remote_base_url.clone(),
                    phase: "idle".to_string(),
                    detail: String::new(),
                }),
            });

            // 一律先落在引导页：本机 / 远程由用户当场选，壳不自动连任何一个
            WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App("index.html".into()))
                .title(env!("BRAND_NAME"))
                .inner_size(1600.0, 900.0)
                .min_inner_size(960.0, 640.0)
                // 每次启动都居中；再兜一下溢出——1600×900 在小屏上放不下时，
                // 会被夹到「屏幕 - 任务栏」的尺寸，免得居中之后反而被裁掉一块
                .center()
                .prevent_overflow()
                .initialization_script(&injection)
                .build()?;
            backend::log_probe(&handle);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("minis desktop 启动失败")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                backend::stop(app);
            }
        });
}
