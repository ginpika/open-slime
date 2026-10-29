//! minis 桌面壳（Tauri 2）：把「本机后端」与「远程后端」两种模式收在同一个应用里。
//!
//! - 本机模式：壳拉起内置 JRE + fat jar 作为 sidecar，只绑回环、随机端口；壳每次启动生成
//!   一次性令牌，既作为环境变量传给后端（DesktopTokenFilter 校验），也在页面脚本执行前注入
//!   `window.__MINIS_LOCAL_TOKEN__`，前端统一在请求头带回来。固定本地单用户，不走 SSO。
//! - 远程模式：不启动 sidecar，窗口直接加载服务端站点，认证完全交由服务端的 hostid SSO。
//!
//! 两种模式的共同点：实例最终落在一个 **同源 HTTP 地址** 上（本机 `http://127.0.0.1:{端口}`、
//! 远程 `https://{域名}`），所以前端那份「相对 /api + Cookie + SSE」的代码一行都不用改。
//!
//! 一个窗口、一个 webview：引导页与实例页是同一个 webview 的两份 document，靠**导航**互换
//! （一个 webview 只能显示一份 document，导航必然销毁旧文档；想让实例页在后台常驻就得再加一个
//! webview，这里不走那条路）。所以「回到实例」靠**保存 / 恢复状态**：
//!   · 离开实例前，把它的当前地址（含 #hash 路由）记进壳状态（见 open_launcher_home）；
//!   · 引导页据此把对应卡片换成「回到实例」，点它直接导航回那个地址（见 desktop_show_instance）；
//!   · 会话（本机一次性令牌 / hostid cookie）与业务数据都在后端，主题、聊天会话等前端状态已在
//!     localStorage，重新渲染后自然回到离开时的位置与内容。
//! 只有「首次进入 / 登出」铺高斯模糊加载层；引导页与实例页之间的来回是直接导航。
//!
//! 启动一律停在自带的引导页（ui/index.html）：由用户当场选本机还是远程，壳不自动连任何一个。
//! 选本机就拉起 sidecar、就绪后开实例窗口；选远程直接开实例窗口指向服务端站点。选择会写进配置，
//! 下次进来在引导页上做默认高亮。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod config;
mod economy_capture;

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::webview::PageLoadEvent;
use tauri::window::EffectsBuilder;
use tauri::{
    AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, Position, Rect, RunEvent, Size,
    Webview, WebviewBuilder, WebviewUrl, WebviewWindowBuilder, Window,
};
use tauri_plugin_shell::process::CommandChild;

/// 引导页订阅的状态事件名
const STATUS_EVENT: &str = "minis://status";
/// 引导页订阅的用户事件名（当前用户 / 本地用户 / hostid SSO 用户有变即推一份）
const USER_EVENT: &str = "minis://user";
/// 主窗口标签（capabilities 里也按它授权）：单窗口单 webview，
/// 引导页与实例页是它先后加载的两份 document（见文件头说明）
const WINDOW_LABEL: &str = "main";
/// 邮件 webview 标签：主窗口里另挂的一个子 webview，专门承载邮件页（见 desktop_show_mail）
const MAIL_WEBVIEW: &str = "mail";
/// 专用登录窗口标签（登录 hostid 时弹出，成功即关）
const LOGIN_LABEL: &str = "hostid-login";
/// 远程认证探测窗口标签（隐藏，探测完即销毁）
const PROBE_LABEL: &str = "remote-auth-probe";
/// 撤销 hostid 会话的隐藏窗口标签（登出用，完即销毁）
const REVOKE_LABEL: &str = "hostid-logout";
/// 登录窗口回传登录用户用的同源路径（见 desktop_login_hostid）
const SSO_DONE_PATH: &str = "/__minis_sso_done";
/// 撤销会话窗口回传完成信号用的同源路径（见 revoke_hostid_session）
const SSO_REVOKE_PATH: &str = "/__minis_sso_revoke";
/// 登录窗回传「自绘标题栏已注入」（= 这一页真的加载出来了，见 LOGIN_BAR_JS）
const LOGIN_READY_PATH: &str = "/__minis_login_ready";
/// 登录窗回传「这个地址没有 hostid 的会话接口」（= 不是 hostid 实例）
const LOGIN_INVALID_PATH: &str = "/__minis_login_invalid";
/// 远程认证探测回传结果用的同源路径（见 probe_remote_auth）
const PROBE_DONE_PATH: &str = "/__minis_remote_probe";
/// 登出哨兵路径：主应用（远程源）往这里导航，主窗口的 on_navigation 会拦下它并带回引导页。
/// 用导航而非 IPC，是因为远程源调用壳自有命令受 Tauri ACL 限制，导航拦截不依赖 ACL。
const LOGOUT_PATH: &str = "/__minis_desktop_logout";
/// 首页哨兵路径：实例页标题栏的首页按钮往这里导航，主窗口的 on_navigation 会拦下它并带回
/// 引导页的「启动」视图（视图一：选本机 / 远程那一屏）。与登出同走导航拦截，但不撤销会话。
const HOME_PATH: &str = "/__minis_desktop_home";
/// 进入远程实例时的过渡层脚本：实例页文档创建（PageLoadEvent::Started）时铺一层高斯模糊 +
/// 居中转圈，页面就绪后自己渐出（模糊 → 清晰）并移除。与引导页那层同款参数，
/// 于是「引导页模糊 → 跳转 → 实例页模糊 → 清晰」看起来是同一层动画，跳转被藏在里面。
///
/// <p>矩形按外壳常数（12px 圈 + 88px 菜单栏 + 10px 间距 + 48px 标题栏 + 20px 圆角）算成
/// 「主视图内容区」，与 index.html 的首帧骨架、以及挂载后的 {@code .df__view} 完全重合——
/// 所以挂载前后层的边界不变，不会跟着跳。遮罩只遮主容器，菜单栏与标题栏不受影响。
const ENTER_VEIL_JS: &str = r#"(function () {
  var id = '__minis_enter_veil__';
  if (document.getElementById(id)) return;

  // 与窗口外壳同源的结构常数（desktop/ui/index.html、shell/desktopFrame.css、index.html 骨架四处同值）
  var PAD = 12, RAIL = 88, GAP = 10, TITLEBAR = 48, RADIUS = 20;

  var style = document.createElement('style');
  style.textContent = '@keyframes minis-enter-spin{to{transform:rotate(360deg)}}';
  (document.head || document.documentElement).appendChild(style);

  var el = document.createElement('div');
  el.id = id;
  el.style.cssText = [
    'position:fixed',
    'left:' + (PAD + RAIL + GAP) + 'px',
    'top:' + (PAD + TITLEBAR) + 'px',
    'right:' + PAD + 'px',
    'bottom:' + PAD + 'px',
    'z-index:61', 'overflow:hidden',
    // 只有下沿两角是圆的：上沿贴着标题栏，与挂载后 .df__view 的裁切一致
    'border-radius:0 0 ' + RADIUS + 'px ' + RADIUS + 'px',
    'display:flex', 'align-items:center', 'justify-content:center',
    'background:rgba(255,255,255,.35)',
    'backdrop-filter:blur(14px)', '-webkit-backdrop-filter:blur(14px)',
    'opacity:1', 'transition:opacity .6s ease,backdrop-filter .6s ease'
  ].join(';');
  var spinner = document.createElement('span');
  spinner.style.cssText = [
    'width:28px', 'height:28px',
    'border:2px solid rgba(15,15,15,.12)', 'border-top-color:#0f0f0f',
    'border-radius:999px', 'animation:minis-enter-spin .8s linear infinite'
  ].join(';');
  el.appendChild(spinner);
  (document.body || document.documentElement).appendChild(el);

  // 应用挂载后把层交给真正的主容器：矩形一致，只是把定位从 fixed 换成 absolute
  var adopted = false;
  function adopt() {
    if (adopted) return true;
    var host = document.querySelector('.df__view');
    if (!host) return false;
    adopted = true;
    el.style.left = el.style.top = el.style.right = el.style.bottom = '';
    el.style.inset = '0';
    el.style.position = 'absolute';
    /* 圆角不去掉：下沿两角与主容器一致（只靠父级 overflow:hidden 裁切不可靠，
       带 backdrop-filter 的层要自己带圆角）；上沿贴着标题栏，保持直角 */
    host.appendChild(el);
    return true;
  }
  var tries = 0;
  var timer = setInterval(function () {
    if (adopt() || ++tries > 80) clearInterval(timer); // 4s 还没挂上就用整块主视图矩形兜底
  }, 50);

  function fade() {
    adopt(); // 收尾前再试一次，尽量把层落在主容器里
    el.style.opacity = '0';
    el.style.backdropFilter = 'blur(0px)';
    el.style.webkitBackdropFilter = 'blur(0px)';
    el.style.pointerEvents = 'none';
    setTimeout(function () { el.remove(); style.remove(); }, 700);
  }
  function arm() { setTimeout(fade, 400); }
  if (document.readyState === 'complete') arm();
  else window.addEventListener('load', arm, { once: true });
  setTimeout(fade, 6000); // 兜底：load 迟迟不来也不能把人困在模糊层里
})();"#;

/// 登录窗的自绘标题栏（窗口无边框，见 open_login_window）：左段是拖拽区（框架内置脚本接管
/// {@code data-tauri-drag-region}），右段是圆形窗口按钮——样式与桌面壳主容器一致
/// （实底 #1c1b1d、圆形、落影）。
///
/// <p>登录窗加载的是用户填的 hostid 站点，页面本身不可控，所以整条标题栏用内联样式注入，
/// 只铺顶部 48px、不参与页面布局（否则 100vh 的登录页会被挤出滚动条）。
const LOGIN_BAR_JS: &str = r#"(function () {
  var ID = 'minis-login-bar';
  var STROKE = 'rgba(255,255,255,.72)';
  // 「本页已加载」回传路径：open_login_window 会把占位符替换成 LOGIN_READY_PATH，
  // 免得同一条路径在 Rust 与 JS 里各写一份
  var READY = '__MINIS_LOGIN_READY__';
  /**
   * 图标：样式必须落在图形元素自身。hostid 页面常有 svg/path 的全局规则
   * （例如 path { fill: currentColor }），只在 <svg> 上写、靠继承是不行的——
   * 描边型图标（横线、×）会被覆盖成「填充零面积」而整条看不见，只有 <rect> 这类
   * 闭合图形还显示。内联样式优先级高于页面规则，写在自己身上才稳。
   */
  function icon(glyph) {
    var s = 'fill:none;stroke:' + STROKE + ';stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round';
    return (
      '<svg viewBox="0 0 12 12" style="display:block;width:12px;height:12px" aria-hidden="true">' +
      '<' + glyph + ' style="' + s + '"/></svg>'
    );
  }
  function act(name) {
    var api = window.__TAURI__ && window.__TAURI__.window;
    if (!api) return;
    var w = api.getCurrentWindow();
    if (name === 'minimize') w.minimize();
    else if (name === 'maximize') w.toggleMaximize();
    else w.close();
  }
  function paint() {
    if (document.getElementById(ID) || !document.body) return;
    var bar = document.createElement('div');
    bar.id = ID;
    bar.style.cssText = [
      'position:fixed', 'left:0', 'top:0', 'right:0', 'height:48px', 'z-index:2147483647',
      'display:flex', 'align-items:center', 'gap:8px', 'padding:0 12px 0 8px',
      'user-select:none', '-webkit-user-select:none'
    ].join(';');

    // 左段：拖拽区（单击拖拽、双击最大化）。文字给窗口一个标题——系统标题栏已去掉
    var drag = document.createElement('div');
    drag.setAttribute('data-tauri-drag-region', '');
    drag.style.cssText = [
      'flex:1', 'align-self:stretch', 'display:flex', 'align-items:center', 'padding-left:10px',
      'font:600 13px/1 -apple-system,"Segoe UI",system-ui,sans-serif', 'color:#8a8a90'
    ].join(';');
    drag.textContent = '登录 hostid';
    bar.appendChild(drag);

    // 右段：圆形窗口按钮
    var defs = [
      ['minimize', '最小化', 'path d="M2 6h8"'],
      ['maximize', '最大化', 'rect x="2.5" y="2.5" width="7" height="7" rx="1"'],
      ['close', '关闭', 'path d="M3 3l6 6M9 3l-6 6"']
    ];
    defs.forEach(function (d) {
      var b = document.createElement('button');
      b.type = 'button';
      b.title = d[1];
      b.setAttribute('aria-label', d[1]);
      b.style.cssText = [
        'flex:none', 'display:flex', 'align-items:center', 'justify-content:center',
        'width:30px', 'height:30px', 'padding:0', 'border:0', 'border-radius:50%',
        'background:#1c1b1d', 'cursor:pointer',
        'box-shadow:0 1px 2px rgba(0,0,0,.1),0 4px 12px rgba(0,0,0,.1)'
      ].join(';');
      b.innerHTML = icon(d[2]);
      // hover 直接改图形自身的描边色：不依赖 currentColor（同样可能被页面规则改掉）
      var glyph = b.firstChild && b.firstChild.firstChild;
      b.addEventListener('mouseenter', function () {
        if (glyph) glyph.style.stroke = '#fff';
      });
      b.addEventListener('mouseleave', function () {
        if (glyph) glyph.style.stroke = STROKE;
      });
      b.addEventListener('click', function () { act(d[0]); });
      bar.appendChild(b);
    });
    document.body.appendChild(bar);
    // 标题栏注入成功 = 这一页真的加载出来了：壳据此判断地址是否可达
    // （地址不通时 WebView 的错误页上注入脚本不执行，就报不出这条）
    try { location.href = READY; } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', paint);
  else paint();
  setTimeout(paint, 1200); // SPA 整块换 body 时补一次（已存在则直接返回）
})();"#;

/// 离开实例时的过渡层脚本：在当前页主视图内容区上渐入高斯模糊 + 居中转圈（清晰 → 模糊），
/// 停留不动——随后壳在模糊层下把窗口带回引导页（见 return_to_launcher）。
/// 参数与进入时同款、矩形与主容器一致，所以来回看起来是同一层。
const EXIT_VEIL_JS: &str = r#"(function () {
  var id = '__minis_exit_veil__';
  if (document.getElementById(id)) return;
  var RADIUS = 20; // 与主容器同圆角（上沿贴标题栏，只圆下两角）

  var style = document.createElement('style');
  style.textContent = '@keyframes minis-enter-spin{to{transform:rotate(360deg)}}';
  (document.head || document.documentElement).appendChild(style);

  var el = document.createElement('div');
  el.id = id;
  el.style.cssText = [
    'position:absolute', 'inset:0', 'z-index:2147483646', 'overflow:hidden',
    'border-radius:0 0 ' + RADIUS + 'px ' + RADIUS + 'px',
    'display:flex', 'align-items:center', 'justify-content:center',
    'background:rgba(255,255,255,.35)',
    'backdrop-filter:blur(0px)', '-webkit-backdrop-filter:blur(0px)',
    'opacity:0', 'transition:opacity .6s ease,backdrop-filter .6s ease'
  ].join(';');
  var spinner = document.createElement('span');
  spinner.style.cssText = [
    'width:28px', 'height:28px',
    'border:2px solid rgba(15,15,15,.12)', 'border-top-color:#0f0f0f',
    'border-radius:999px', 'animation:minis-enter-spin .8s linear infinite'
  ].join(';');
  el.appendChild(spinner);

  var host = document.querySelector('.df__view');
  if (host) {
    host.appendChild(el);
  } else {
    // 兜底：不在桌面框架里（理论上不该发生）就盖整屏
    el.style.position = 'fixed';
    el.style.borderRadius = '0';
    (document.body || document.documentElement).appendChild(el);
  }
  // 下一帧再上模糊：让「清晰 → 模糊」有过渡起点
  requestAnimationFrame(function () {
    el.style.opacity = '1';
    el.style.backdropFilter = 'blur(14px)';
    el.style.webkitBackdropFilter = 'blur(14px)';
  });
  // 兜底：万一没走成，别把页面锁在模糊里
  setTimeout(function () {
    el.style.opacity = '0';
    el.style.backdropFilter = 'blur(0px)';
    el.style.webkitBackdropFilter = 'blur(0px)';
    setTimeout(function () { el.remove(); style.remove(); }, 700);
  }, 8000);
})();"#;

/// 存活的实例会话：进入实例后实例窗口常驻后台，引导页据此把对应模式的卡片换成「回到实例」
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceSession {
    /// 进入时用的目标地址（远程为归一化后的站点地址；本机为 127.0.0.1 的就绪地址）
    url: String,
    /// "local" | "remote"
    mode: String,
}

/// 壳的当前状态（引导页据此渲染）
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    mode: config::Mode,
    remote_base_url: String,
    /// idle=待选择 / boot=正在启动本机后端 / ready=已跳转本机后端 / remote=已跳转远程 / error=失败
    phase: String,
    detail: String,
    /// 会话是否已失效（登出 / 被 hostid 打回）：引导页据此显示「重新登录」横幅。
    /// 放状态里而不是靠事件推送，是因为带窗口回引导页时页面还没加载，事件会丢。
    session_expired: bool,
    /// 存活的实例会话（Some = 实例窗口还在后台，对应卡片显示「回到实例」）
    instance: Option<InstanceSession>,
    /// 壳要求引导页切换到的视图（如 "about"）：引导页应用后回执清除（见 desktop_view_applied）
    open_view: Option<String>,
    /// 邮件服务地址（引导页「用户」视图里配置的真实地址；空 = 退回用 hostid 地址）
    mail_url: String,
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

/// 记「会话已失效」到壳状态：引导页读取 desktop_state 时据此显示「重新登录」横幅。
///
/// <p>只由确定性信号置真（用户登出、被 hostid 打回登录页），登录成功即置假——不做
/// 「后台探测 hostid 猜一把」：探测失败（网络/代理不通、hostid 地址不可解析）与「确实失效」
/// 无法区分，会误报；而且探测回来得晚，登录之后还能把横幅画回去。
fn set_session_expired(app: &AppHandle, expired: bool) {
    let shell = app.state::<Shell>();
    shell.status.lock().unwrap().session_expired = expired;
}

/// 记下 hostid 服务端地址（登录时、或被 hostid 打回时从跳转地址推断）
fn remember_hostid_base(app: &AppHandle, base: &str) {
    if base.is_empty() {
        return;
    }
    let mut cfg = config::load(app);
    if cfg.hostid_base_url != base {
        cfg.hostid_base_url = base.to_string();
        let _ = config::save(app, &cfg);
    }
}

/// 壳的进程内状态：本机后端子进程句柄 + 一次性令牌 + 已就绪端口 + 对外状态
pub struct Shell {
    /// 本机模式的一次性令牌（每次启动重新生成）
    token: String,
    child: Mutex<Option<CommandChild>>,
    /// 本机后端本次会话已监听的端口（null = 还没起来）
    port: Mutex<Option<u16>>,
    status: Mutex<Status>,
    /// 引导页（壳页面）地址：启动时记下，从实例返回时据此把窗口带回引导页
    launcher_url: Mutex<String>,
    /// 正在执行「带回引导页」：拦截到重复导航（会话失效时页面会反复跳登录）只处理一次
    returning: AtomicBool,
    /// 正在「进入实例」：置真后主窗口下一次页面加载（Started）会铺过渡层，取一次即消。
    /// 只有进入（连接 / 登录）铺；「回到实例」「回引导页」是直接导航，不铺。
    entering: AtomicBool,
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

/// 记下 / 清除存活的实例会话并推一份状态：引导页据此把对应卡片在「连接」与「回到实例」间切换
fn set_instance_session(app: &AppHandle, session: Option<InstanceSession>) {
    let Some(shell) = app.try_state::<Shell>() else {
        return;
    };
    let snapshot = {
        let mut status = shell.status.lock().unwrap();
        status.instance = session;
        status.clone()
    };
    let _ = app.emit(STATUS_EVENT, snapshot);
}

/// 当前存活的实例会话（None = 实例窗口不在后台）
fn current_instance_session(app: &AppHandle) -> Option<InstanceSession> {
    app.try_state::<Shell>()
        .and_then(|shell| shell.status.lock().unwrap().instance.clone())
}

/// 页面脚本执行前注入的初始化脚本：品牌信息 + 本机模式的一次性令牌。
/// 引导页与实例窗口都要注入——本机模式下实例窗口跑在 127.0.0.1 上，同样要带 X-Minis-Token。
fn desktop_injection(app: &AppHandle) -> String {
    let brand = serde_json::json!({
        "name": env!("BRAND_NAME"),
        "slug": env!("BRAND_SLUG"),
        "tagline": env!("BRAND_TAGLINE"),
    });
    let token = app
        .try_state::<Shell>()
        .map(|shell| shell.token.clone())
        .unwrap_or_default();
    format!(
        "window.__BRAND__={brand};\nif (location.hostname === \"127.0.0.1\") {{ window.__MINIS_LOCAL_TOKEN__={}; }}",
        serde_json::to_string(&token).unwrap_or_else(|_| "\"\"".to_string())
    )
}

/// 主窗口的主 webview（引导页与实例页都加载在它上面）。
///
/// <p>注意别用 `get_webview_window(WINDOW_LABEL)`：那是「单 webview 窗口」的取法——
/// `Manager::get_webview_window` 只在窗口里所有 webview 的 label 都等于窗口 label 时才返回
/// `Some`。主窗口挂了邮件的子 webview 之后就不满足了，取到 None 会让导航静默失败。
fn main_webview(app: &AppHandle) -> Option<Webview> {
    app.get_webview(WINDOW_LABEL)
}

/// 让主 webview 跳到某个地址（引导页 ↔ 实例页都是它上面的 document）；返回是否发起了导航
fn navigate(app: &AppHandle, url: &str) -> bool {
    let Some(webview) = main_webview(app) else {
        return false;
    };
    match url.parse::<tauri::Url>() {
        Ok(parsed) => match webview.navigate(parsed) {
            Ok(()) => true,
            Err(err) => {
                emit_status(app, "error", &format!("打开 {url} 失败：{err}"));
                false
            }
        },
        Err(err) => {
            emit_status(app, "error", &format!("地址无效（{url}）：{err}"));
            false
        }
    }
}

/// 记下引导页（壳页面）地址：主窗口第一次真正的页面加载就是引导页（见 setup 的窗口创建），
/// 从 on_page_load 的 payload 里取——比创建后立刻 `window.url()` 可靠（那会儿 webview 还没
/// 提交 URL，取不到就会让「从实例返回」无处可去）。只记第一次，之后加载的就可能是实例页了。
fn remember_launcher_if_first(app: &AppHandle, url: &tauri::Url) {
    // 只认真正的页面地址：about:blank 这类空白文档（部分平台会先来一发）不算
    if !matches!(url.scheme(), "http" | "https" | "tauri") {
        return;
    }
    let shell = app.state::<Shell>();
    let mut slot = shell.launcher_url.lock().unwrap();
    if slot.is_empty() {
        *slot = url.to_string();
    }
}

/// 把窗口带回引导页；返回是否发起了导航
fn navigate_launcher(app: &AppHandle) -> bool {
    let url = app.state::<Shell>().launcher_url.lock().unwrap().clone();
    if url.is_empty() {
        eprintln!("[minis-desktop] 未知引导页地址，无法把窗口带出后端站点");
        return false;
    }
    navigate(app, &url)
}

/// 引导页 webview 与实例页共用的导航闸门：三类哨兵导航由壳接管，其余放行。
///
/// <p>用导航而非 IPC：远程源调用壳自有命令受 Tauri ACL 限制，导航拦截不依赖 ACL。
fn intercept_navigation(app: &AppHandle, nav: &tauri::Url) -> bool {
    let path = nav.path();
    if path == LOGOUT_PATH {
        // 前端登出：通知 + 清身份 + 销毁实例窗口 + 带回引导页
        let h = app.clone();
        std::thread::spawn(move || return_to_launcher(&h, true, Some("已退出登录，正在返回…")));
        return false;
    }
    if path == HOME_PATH {
        // 实例页的首页入口：不登出、不销毁会话，切回引导页的「启动」视图
        let h = app.clone();
        std::thread::spawn(move || open_launcher_home(&h));
        return false;
    }
    if path.starts_with("/sso/login") {
        // 会话失效被 hostid 打回：顺手记下 hostid 地址（下次「重新登录」直接用），
        // 再带回引导页提示重登——不让窗口停在 hostid 登录页
        let base = origin_of(nav.as_str()).unwrap_or_default();
        let h = app.clone();
        std::thread::spawn(move || {
            remember_hostid_base(&h, &base);
            return_to_launcher(&h, true, Some("会话已失效，请重新登录"));
        });
        return false;
    }
    true
}

/// 实例页的首页入口：把实例当前地址（含 #hash 路由）记进壳状态，再直接导航回引导页、
/// 停在「启动」视图（视图一）。
///
/// <p>这次切换不铺过渡层（只有首次进入 / 登出才铺），引导页一加载就是清晰的。记下的地址
/// 让引导页的对应卡片变成「回到实例」，点它即回到离开时的那个页面。
fn open_launcher_home(app: &AppHandle) {
    // 读实例页当前地址：别丢 #hash 路由（如 #/graph、#/articles/xxx）；
    // 哨兵地址本身不算（万一拦截时机有偏差，别把哨兵当成实例地址记下来）
    let current = main_webview(app)
        .and_then(|webview| webview.url().ok())
        .filter(|url| url.path() != HOME_PATH && url.path() != LOGOUT_PATH)
        .map(|url| url.to_string());

    let shell = app.state::<Shell>();
    let snapshot = {
        let mut status = shell.status.lock().unwrap();
        let mode = match status.mode {
            config::Mode::Local => "local",
            config::Mode::Remote => "remote",
        }
        .to_string();
        status.instance = current.map(|url| InstanceSession { url, mode });
        status.open_view = Some("launch".to_string());
        status.phase = "idle".to_string();
        status.detail = String::new();
        status.clone()
    };
    let _ = app.emit(STATUS_EVENT, snapshot);
    navigate_launcher(app);
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

/// 已记住的 hostid 服务端地址（引导页预填登录输入框；也是邮件地址的兜底，见 desktop_save_mail_url）
#[tauri::command]
fn desktop_hostid_base(app: AppHandle) -> String {
    config::load(&app).hostid_base_url
}

/// 保存邮件服务地址（引导页「用户」视图的邮件卡片提交）。
///
/// <p>hostid 自带邮件，但它前端与后端可能配在不同域，所以让用户填真实地址；空串 = 清掉、
/// 退回用 hostid 地址。存完推一份状态，并让已开过的邮件 webview 跟着换地址。
#[tauri::command]
fn desktop_save_mail_url(app: AppHandle, url: String) -> Result<String, String> {
    let url = url.trim().trim_end_matches('/').to_string();
    if !url.is_empty() && !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("地址需以 http:// 或 https:// 开头".to_string());
    }
    let mut cfg = config::load(&app);
    cfg.mail_url = url.clone();
    config::save(&app, &cfg)?;
    let shell = app.state::<Shell>();
    let snapshot = {
        let mut status = shell.status.lock().unwrap();
        status.mail_url = url.clone();
        status.clone()
    };
    let _ = app.emit(STATUS_EVENT, snapshot);

    // 邮件页开过就跟着换到新地址（两个地址都空则收起它）
    if let Some(webview) = app.get_webview(MAIL_WEBVIEW) {
        let effective = mail_address(&app);
        if effective.is_empty() {
            let _ = webview.hide();
        } else if let Ok(parsed) = effective.parse::<tauri::Url>() {
            let _ = webview.navigate(parsed);
        }
    }
    Ok(url)
}

/* ------------------------------------------------ 邮件页（主窗口里的子 webview） */

/// 邮件地址：优先用「用户」视图里配的真实地址（hostid 前后端不同域时就靠它），没配则退回 hostid 地址
fn mail_address(app: &AppHandle) -> String {
    let cfg = config::load(app);
    if cfg.mail_url.is_empty() {
        cfg.hostid_base_url
    } else {
        cfg.mail_url
    }
}

/// 邮件 webview 的摆放：**铺满**引导页的「主视图」容器（.stage），逻辑像素、相对窗口客户区。
///
/// <p>外壳常数与 desktop/ui/index.html 同源：12 圈 + 88 菜单栏 + 10 间距 = 左 110、12 + 48 标题栏 = 上 60。
/// 页面里的品牌区（品牌名 + 标语）在这一档由 CSS 让位，见 index.html 的 body[data-view='mail']。
fn mail_bounds(window: &Window) -> Rect {
    const LEFT: f64 = 12.0 + 88.0 + 10.0;
    const TOP: f64 = 12.0 + 48.0;
    const EDGE: f64 = 12.0;
    let scale = window.scale_factor().unwrap_or(1.0);
    let (w, h) = window
        .inner_size()
        .map(|size| (size.width as f64 / scale, size.height as f64 / scale))
        .unwrap_or((1280.0, 720.0));
    Rect {
        position: Position::Logical(LogicalPosition::new(LEFT, TOP)),
        size: Size::Logical(LogicalSize::new(
            (w - LEFT - EDGE).max(1.0),
            (h - TOP - EDGE).max(1.0),
        )),
    }
}

/// 收起邮件 webview：它是原生视图，页面盖不住它，所以离开引导页（进实例）时必须收起来
fn hide_mail_webview(app: &AppHandle) {
    if let Some(webview) = app.get_webview(MAIL_WEBVIEW) {
        let _ = webview.hide();
    }
}

/// 让邮件页「长进」主容器的注入脚本。
///
/// <p>邮件页是原生子窗口，两件事只能靠它自己的页面来做：
/// 1. **去掉根层的底**：`html` / `body` 置透明（源站的元素都挂在 `body > #root`，那层底色照旧保留），
///    容器底色便能从根层透出来。注意 CSS 的**背景传播**：根元素没有背景时，`body` 的底会被提升去
///    铺满整个画布，所以两层必须一起清，只清 `html` 等于没清。
/// 2. **圆角遮罩**：铺满视口、与主容器同色的圆角矩形，用 `box-shadow` 盖住四角之外——原生子窗口
///    吃不到页面 CSS 的 `border-radius`，也不能用 Win32 的 1 位窗口区域（锯齿明显），
///    只能让浏览器来画这段圆弧；那块颜色和主容器一模一样，接缝看不出来。
///
/// <p>半径与底色不写死：引导页切视图时现读 `.stage` 的 `border-radius` / `background-color`
/// 传进来，随主题变化自动跟着走。同一个脚本可重复执行：变量每次都更新，节点只建一次。
fn mail_fit_script(radius: &str, color: &str) -> String {
    let radius = serde_json::to_string(radius).unwrap_or_else(|_| "\"\"".into());
    let color = serde_json::to_string(color).unwrap_or_else(|_| "\"\"".into());
    r#"(function () {
  var root = document.documentElement
  if (!root) return
  root.style.setProperty('--minis-mail-radius', __RADIUS__)
  root.style.setProperty('--minis-mail-corner', __COLOR__)
  if (document.getElementById('minis-mail-style')) return
  var style = document.createElement('style')
  style.id = 'minis-mail-style'
  style.textContent = [
    'html, body { background-color: transparent !important; background-image: none !important; }',
    'html::after { content: ""; position: fixed; inset: 0; border-radius: var(--minis-mail-radius, 0px); box-shadow: 0 0 0 100vmax var(--minis-mail-corner, transparent); pointer-events: none; z-index: 2147483647; }'
  ].join('\n')
  ;(document.head || root).appendChild(style)
})()"#
        .replace("__RADIUS__", &radius)
        .replace("__COLOR__", &color)
}

/// 显示 / 隐藏邮件页（引导页切到「邮件」视图时调用）。
///
/// <p>邮件页是主窗口里的一个**子 webview**：顶层浏览上下文，不是 iframe，所以跨站 cookie
/// （SameSite=Lax 的 sso_token）与浏览器的第三方 cookie 限制都不影响它；它铺满引导页「主视图」
/// 区域，左侧菜单栏与自绘标题栏仍是引导页画的，照旧在。
///
/// <p>`corner_radius` / `corner_color` 是引导页现读的主容器圆角与底色，用来让邮件页的根层透明、
/// 并在四角盖同色挡板（见 mail_fit_script）——原生子窗口吃不到页面圆角，只能让它自己长出来。
///
/// <p>首次显示才创建；隐藏只收起、不销毁，来回切换是瞬时的。
#[tauri::command]
fn desktop_show_mail(
    app: AppHandle,
    visible: bool,
    corner_radius: Option<String>,
    corner_color: Option<String>,
) {
    // 新建 webview 不能在命令线程里做（Windows 上会死锁），统一放后台线程
    std::thread::spawn(move || {
        let Some(window) = app.get_window(WINDOW_LABEL) else {
            return;
        };
        if !visible {
            hide_mail_webview(&app);
            return;
        }
        let fit = mail_fit_script(
            corner_radius.as_deref().unwrap_or_default(),
            corner_color.as_deref().unwrap_or_default(),
        );
        let address = mail_address(&app);
        if address.is_empty() {
            eprintln!("[minis-desktop] 还没配邮件地址：到引导页「用户」视图填，或用 hostid 地址兜底");
            return;
        }
        let Ok(target) = address.parse::<tauri::Url>() else {
            emit_status(&app, "error", &format!("邮件地址无效（{address}）"));
            return;
        };

        if let Some(webview) = app.get_webview(MAIL_WEBVIEW) {
            let _ = webview.set_bounds(mail_bounds(&window));
            // 圆角/底色可能换了（主题变了）：脚本事后重复执行一遍即可
            let _ = webview.eval(&fit);
            let _ = webview.show();
            let _ = webview.set_focus();
            return;
        }

        let bounds = mail_bounds(&window);
        match window.add_child(
            WebviewBuilder::new(MAIL_WEBVIEW, WebviewUrl::External(target))
                // 透明底：WebView2 默认铺一层白底，加载中/页面透明处会露出来；
                // 置空后透出下层（引导页的主容器），和主窗口一样是透的
                .transparent(true)
                .initialization_script(&fit),
            bounds.position,
            bounds.size,
        ) {
            Ok(webview) => {
                let _ = webview.show();
                let _ = webview.set_focus();
            }
            Err(err) => emit_status(&app, "error", &format!("打开邮件页失败：{err}")),
        }

        // 邮件 webview 是原生视图，不跟随页面布局：窗口尺寸变化时自己重算摆放
        let resize_app = app.clone();
        window.on_window_event(move |event| {
            if matches!(event, tauri::WindowEvent::Resized(_)) {
                if let (Some(window), Some(webview)) = (
                    resize_app.get_window(WINDOW_LABEL),
                    resize_app.get_webview(MAIL_WEBVIEW),
                ) {
                    let _ = webview.set_bounds(mail_bounds(&window));
                }
            }
        });
    });
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

/// 引导页确认「返回动画已放完」：把相位从 returning 收回 idle。
/// 否则状态行会一直停在「正在返回…」（相位是壳状态，页面自己改不了）。
#[tauri::command]
fn desktop_return_done(app: AppHandle) {
    emit_status(&app, "idle", "");
}

/// 引导页撤下「会话已失效」提示：连壳状态里的标记一起清掉。
/// 只清引导页本地变量的话，之后任何一次重读 desktop_state（如切身份）都会把横幅又带回来。
#[tauri::command]
fn desktop_dismiss_session_notice(app: AppHandle) {
    set_session_expired(&app, false);
}

/// 选本机模式：拉起后端（或复用本次会话已起来的那个），就绪后进入实例窗口
#[tauri::command]
fn desktop_choose_local(app: AppHandle) -> Result<(), String> {
    let mut cfg = config::load(&app);
    cfg.mode = config::Mode::Local;
    config::save(&app, &cfg)?;
    set_choice(&app, config::Mode::Local, "");

    // 进本机模式不涉及 hostid 会话：清掉「会话已失效」标记
    set_session_expired(&app, false);

    let token = app.state::<Shell>().token.clone();
    if let Some(port) = backend::known_port(&app) {
        emit_status(&app, "ready", &format!("本地服务器已就绪（端口 {port}）"));
        enter_instance(&app, &format!("http://127.0.0.1:{port}/"), "local");
        return Ok(());
    }
    backend::start(&app, &token)
}

/// 选远程模式：记住地址，然后由壳在后台探测会话、必要时先登录，再进站点。
/// （选了远程就不让本机后端继续跑着）
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
    // 重新发起连接：清掉上次的「会话已失效」标记
    set_session_expired(&app, false);
    emit_status(&app, "boot", "正在连接远程服务…");

    // 连接流程要新建 webview（探测窗 / 登录窗 / 实例窗），不能在命令线程里建（会死锁），放后台线程
    let handle = app.clone();
    std::thread::spawn(move || connect_remote(handle, url));
    Ok(())
}

/// 引导页的「回到实例」：直接导航回离开时记下的实例地址（含 #hash 路由），不铺过渡层。
/// 会话与业务数据都在后端、前端状态都在 localStorage，重新渲染后即回到离开时的页面。
#[tauri::command]
fn desktop_show_instance(app: AppHandle) {
    let Some(session) = current_instance_session(&app) else {
        return;
    };
    std::thread::spawn(move || {
        if !navigate(&app, &session.url) {
            // 地址已不可用（比如后端换了端口）：清掉会话，卡片回到「连接 / 启动」
            set_instance_session(&app, None);
        }
    });
}

/// 引导页已应用 openView：清掉它，避免之后的状态推送又把视图掰回去
#[tauri::command]
fn desktop_view_applied(app: AppHandle) {
    app.state::<Shell>().status.lock().unwrap().open_view = None;
}

/// 远程后端对当前 cookie 会话的判定
enum RemoteAuth {
    /// 后端认可会话（/api/auth/me 正常返回用户）
    Authed,
    /// 后端不认、需要登录；hostid_base 为要登录的 hostid 源（后端 401 里给的，可能为空）
    NeedLogin { hostid_base: String },
}

/// 连接远程后端：先探测会话，已认直接进；不认则弹壳内 hostid 登录窗，登录成功后自动进站点。
/// 探测不出结论（网络不通等）时不阻断，退回原行为直接打开远程地址。
fn connect_remote(app: AppHandle, remote_base: String) {
    match probe_remote_auth(&app, &remote_base) {
        Some(RemoteAuth::Authed) => {
            // 校验通过：弹一条「登录成功」，再走统一的「进入实例」过渡
            notify_main(&app, "登录成功，正在进入远程实例…");
            enter_instance(&app, &remote_base, "remote");
        }
        Some(RemoteAuth::NeedLogin { hostid_base }) => {
            // 桌面端自己的会话不被远程实例接受：提示会话不可用，转去壳内 hostid 登录
            let base = if hostid_base.is_empty() {
                config::load(&app).hostid_base_url
            } else {
                hostid_base
            };
            if base.is_empty() {
                notify_main(&app, "会话不可用，且未取到 hostid 地址，请到「用户」页登录");
                emit_status(
                    &app,
                    "error",
                    "远程实例不接受当前会话，且未取到 hostid 地址；请到「用户」页登录 hostid 后重试",
                );
                return;
            }
            notify_main(&app, "当前会话不可用，需要重新登录 hostid");
            emit_status(&app, "boot", "需要登录 hostid，已打开登录窗口");
            if let Err(err) = open_login_window(&app, &base, Some(remote_base.clone())) {
                emit_status(&app, "error", &err);
            }
        }
        None => {
            // 探测不出结论（网络不通等）：不阻断，直接按目标地址开实例窗口
            enter_instance(&app, &remote_base, "remote");
        }
    }
}

/// 进入实例的统一入口：先铺过渡相位（引导页主视图渐入高斯模糊），稍后在模糊层下导航过去。
///
/// <p>只有「进入」（连接 / 登录）铺过渡层；引导页 ↔ 实例页的来回是直接导航，见
/// open_launcher_home / desktop_show_instance。两条路都走它：连接远程（探测已认证 /
/// 直接打开 / 登录窗登录成功）与本机后端就绪。
fn enter_instance(app: &AppHandle, url: &str, mode: &str) {
    // 邮件页是原生子 webview，页面盖不住它：离开引导页前先收起
    hide_mail_webview(app);
    // 进入时先按目标地址记一份会话：离开实例前（首页入口）会覆盖成带 #hash 的精确地址
    set_instance_session(
        app,
        Some(InstanceSession {
            url: url.to_string(),
            mode: mode.to_string(),
        }),
    );
    emit_status(app, "entering", "正在进入实例…");
    let handle = app.clone();
    let url = url.to_string();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(1600)); // 渐入 0.6s + 停留
        // 导航藏在模糊层下：置标记后立刻导航，实例页文档创建时会铺上同款过渡层
        handle.state::<Shell>().entering.store(true, Ordering::SeqCst);
        if !navigate(&handle, &url) {
            // 导航没发起：撤掉标记，别把引导页困在模糊层里
            handle.state::<Shell>().entering.store(false, Ordering::SeqCst);
            set_instance_session(&handle, None);
        }
    });
}

/// 隐藏窗口里探测远程后端是否认可当前 hostid 会话。
///
/// <p>窗口加载 remote_base（同源，自动带上共享 cookie 域里的 sso_token），注入脚本请求
/// `/api/auth/me`：200 → 已认证；401 → 未认（顺带取回 body 里的 loginUrl，即后端使用的
/// hostid 地址）。若后端直接把窗口 302 到 hostid 登录页，脚本改从当前地址取 hostid 源。
/// 结论经同源路径 {@value #PROBE_DONE_PATH} 回传（on_navigation 拦截），随后销毁窗口。
/// 取不到结论（网络不通 / 超时）返回 None。
fn probe_remote_auth(app: &AppHandle, remote_base: &str) -> Option<RemoteAuth> {
    let url = remote_base.parse::<tauri::Url>().ok()?;
    let expected = url.origin().ascii_serialization();
    let done_lit = serde_json::to_string(PROBE_DONE_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let expected_lit = serde_json::to_string(&expected).unwrap_or_else(|_| "\"\"".to_string());
    let script = format!(
        r#"(function () {{
  var done = {done};
  var expected = {expected};
  var sent = false;
  function report(o) {{
    if (sent) return;
    sent = true;
    location.href = done + '?data=' + encodeURIComponent(JSON.stringify(o));
  }}
  if (location.origin !== expected) {{
    // 已被后端跳到 hostid（登录页）：从当前地址取它的源
    report({{ ok: false, hostid: location.origin }});
    return;
  }}
  fetch('/api/auth/me', {{ credentials: 'include' }})
    .then(function (r) {{
      if (r.ok) return r.json().then(function (u) {{ report({{ ok: true, user: u }}); }});
      return r.text().then(function (t) {{
        var loginUrl = null;
        try {{ loginUrl = JSON.parse(t).loginUrl || null; }} catch (e) {{}}
        report({{ ok: false, loginUrl: loginUrl }});
      }});
    }})
    .catch(function () {{ report({{ ok: false, loginUrl: null }}); }});
}})();"#,
        done = done_lit,
        expected = expected_lit,
    );

    if let Some(old) = app.get_webview_window(PROBE_LABEL) {
        let _ = old.close();
    }

    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let built = WebviewWindowBuilder::new(app, PROBE_LABEL, WebviewUrl::External(url))
        .title("remote-probe")
        .visible(false)
        .skip_taskbar(true)
        .inner_size(420.0, 320.0)
        .initialization_script(script)
        .on_navigation(move |nav| {
            if nav.path() == PROBE_DONE_PATH {
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
    if built.is_err() {
        return None;
    }
    let payload = rx.recv_timeout(Duration::from_secs(12)).ok();
    if let Some(window) = app.get_webview_window(PROBE_LABEL) {
        let _ = window.close();
    }
    let value: serde_json::Value = serde_json::from_str(payload.as_deref()?).ok()?;
    if value.get("ok").and_then(|b| b.as_bool()).unwrap_or(false) {
        return Some(RemoteAuth::Authed);
    }
    // 未认证：hostid 源优先取 401 body 里的 loginUrl，其次取被跳转到的当前源
    let hostid_base = value
        .get("loginUrl")
        .and_then(|v| v.as_str())
        .and_then(origin_of)
        .or_else(|| {
            value
                .get("hostid")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
        })
        .unwrap_or_default();
    Some(RemoteAuth::NeedLogin { hostid_base })
}

/// 从 URL 字串取源（scheme://host[:port]）；解析失败返回 None
fn origin_of(raw: &str) -> Option<String> {
    let url = raw.parse::<tauri::Url>().ok()?;
    Some(url.origin().ascii_serialization())
}

/// 往主窗口当前页面弹一条自绘通知（注入一个置顶胶囊；页面跳走后自然消失）。
///
/// <p>主窗口可能在壳页面（tauri 源）也可能在远程实例上，两边的样式/框架都不同，
/// 所以这里自带最小样式，不依赖任何一方的组件。
fn notify_main(app: &AppHandle, text: &str) {
    let Some(webview) = main_webview(app) else {
        return;
    };
    let text_lit = serde_json::to_string(text).unwrap_or_else(|_| "\"\"".to_string());
    let js = format!(
        r#"(function () {{
  var id = '__minis_desktop_toast__';
  var el = document.getElementById(id);
  if (!el) {{
    el = document.createElement('div');
    el.id = id;
    el.style.cssText = [
      'position:fixed', 'left:50%', 'top:26px', 'transform:translateX(-50%)',
      'z-index:2147483647', 'max-width:70vw',
      'padding:10px 18px', 'border-radius:999px',
      'background:rgba(24,24,27,.94)', 'color:#fff',
      'font:600 14px/1.5 system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif',
      'box-shadow:0 10px 30px rgba(0,0,0,.3)', 'pointer-events:none',
      'opacity:0', 'transition:opacity .2s ease', 'white-space:nowrap'
    ].join(';');
    (document.body || document.documentElement).appendChild(el);
    requestAnimationFrame(function () {{ el.style.opacity = '1'; }});
  }}
  el.textContent = {text};
  if (el.__minisTimer) clearTimeout(el.__minisTimer);
  el.__minisTimer = setTimeout(function () {{ el.style.opacity = '0'; }}, 8000);
}})();"#,
        text = text_lit,
    );
    let _ = webview.eval(&js);
}

/// 从实例页回到引导页（登出 / 被 hostid 打回）：渐入模糊层后导航回引导页。
///
/// <p>会话撤销由服务端完成（前端已调后端的登出接口）。若把窗口留在后端站点，之后任何一次
/// 请求发现会话失效，前端都会把窗口送到 hostid 登录页——那不是桌面端的预期落点。这里由壳
/// 把窗口带回引导页；session_expired=true 表示这次是「会话没了」（用户登出 / 被 hostid 打回），
/// 除记标记外还要把壳里那份 hostid 身份一并清掉，否则引导页的「当前身份」仍会显示那个账户。
///
/// <p>与进入实例对称：先在实例页渐入模糊层（清晰 → 模糊），停留片刻再导航——引导页读到
/// returning 相位后接着做「模糊 → 清晰」的渐出。notice 非空时同时弹一条通知。
fn return_to_launcher(app: &AppHandle, session_expired: bool, notice: Option<&str>) {
    // 会话失效时页面可能连续跳登录：只处理第一次，避免反复弹通知 / 反复延迟
    if app.state::<Shell>().returning.swap(true, Ordering::SeqCst) {
        return;
    }
    if session_expired {
        // 记到壳状态里：引导页读 desktop_state 即显示「重新登录」横幅
        set_session_expired(app, true);
        forget_sso_identity(app);
    }

    // 当前页（实例页）先渐入模糊层（清晰 → 模糊）
    if let Some(webview) = main_webview(app) {
        let _ = webview.eval(EXIT_VEIL_JS);
    }
    if let Some(text) = notice {
        notify_main(app, text);
    }
    std::thread::sleep(Duration::from_millis(1600)); // 渐入 0.6s + 停留

    // 相位先落到 returning、并清掉实例会话，再导航：引导页一加载就是模糊态，随后自己渐出；
    // 会话没了，卡片回到「连接 / 启动」
    let shell = app.state::<Shell>();
    let snapshot = {
        let mut status = shell.status.lock().unwrap();
        status.instance = None;
        status.open_view = None;
        status.phase = "returning".to_string();
        status.detail = String::new();
        status.clone()
    };
    let _ = app.emit(STATUS_EVENT, snapshot);
    navigate_launcher(app);
    app.state::<Shell>().returning.store(false, Ordering::SeqCst);
}

/// 退出 hostid 登录：清掉壳里记住的 SSO 身份，并把当前身份切回本地用户
/// （hostid 地址保留，供「重新登录」直接使用；引导页据此刷新「当前身份」）
fn forget_sso_identity(app: &AppHandle) {
    let mut cfg = config::load(app);
    if cfg.sso_user_name.is_empty() && cfg.current_user != "sso" {
        return;
    }
    cfg.sso_user_name.clear();
    cfg.sso_user_avatar.clear();
    cfg.sso_user_email.clear();
    if cfg.current_user == "sso" {
        cfg.current_user = "local".to_string();
    }
    if config::save(app, &cfg).is_ok() {
        emit_users(app);
    }
}

/// 退出当前 hostid 登录（引导页「用户」页的退出入口）：本地立刻清掉 SSO 身份，
/// 随后在后台请 hostid 撤销会话（隐藏窗口里 POST /api/sso/logout，带上共享 cookie）。
/// 撤销只是把服务端会话和 cookie 清干净——不撤销的话，下次连远程仍会拿这个账户自动登录。
#[tauri::command]
fn desktop_logout_hostid(app: AppHandle) {
    let base = config::load(&app).hostid_base_url;
    forget_sso_identity(&app); // 内部会推一份新的身份视图
    if base.is_empty() {
        return;
    }
    // 新建 webview 不能在命令线程里做（会死锁），放后台线程
    std::thread::spawn(move || revoke_hostid_session(&app, &base));
}

/// 隐藏窗口里撤销 hostid 会话：窗口加载 hostid 根（带共享 cookie），注入脚本 POST
/// /api/sso/logout，结果经同源路径 {@value #SSO_REVOKE_PATH} 回传（on_navigation 拦截）后销毁窗口。
fn revoke_hostid_session(app: &AppHandle, base: &str) {
    let Ok(url) = base.parse::<tauri::Url>() else {
        return;
    };
    if let Some(old) = app.get_webview_window(REVOKE_LABEL) {
        let _ = old.close();
    }
    let done_lit = serde_json::to_string(SSO_REVOKE_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let script = format!(
        r#"(function () {{
  var done = {done};
  var sent = false;
  function report() {{
    if (sent) return;
    sent = true;
    location.href = done;
  }}
  fetch('/api/sso/logout', {{ method: 'POST', credentials: 'include' }})
    .then(function () {{ report(); }})
    .catch(function () {{ report(); }});
}})();"#,
        done = done_lit,
    );

    let (tx, rx) = std::sync::mpsc::channel::<()>();
    let built = WebviewWindowBuilder::new(app, REVOKE_LABEL, WebviewUrl::External(url))
        .title("hostid-logout")
        .visible(false)
        .skip_taskbar(true)
        .inner_size(420.0, 320.0)
        .initialization_script(script)
        .on_navigation(move |nav| {
            if nav.path() == SSO_REVOKE_PATH {
                let _ = tx.send(());
                return false;
            }
            true
        })
        .build();
    if built.is_ok() {
        let _ = rx.recv_timeout(Duration::from_secs(6));
        if let Some(window) = app.get_webview_window(REVOKE_LABEL) {
            let _ = window.close();
        }
    }
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
/// 表现为新窗口白屏且整个应用卡死无法操作；窗口本体在 {@link open_login_window}。
#[tauri::command]
async fn desktop_login_hostid(app: AppHandle, url: String) -> Result<(), String> {
    open_login_window(&app, &url, None)
}

/// 打开 hostid 登录窗口（独立窗口，主窗口不动）。登录成功后写入配置；
/// then_navigate 非空时，再让主窗口导航到该地址（远程连接用：登录完直接进站点）。
fn open_login_window(app: &AppHandle, url: &str, then_navigate: Option<String>) -> Result<(), String> {
    let base = url.trim().trim_end_matches('/').to_string();
    if !(base.starts_with("http://") || base.starts_with("https://")) {
        return Err("地址需以 http:// 或 https:// 开头".to_string());
    }
    let base_url = base
        .parse::<tauri::Url>()
        .map_err(|e| format!("地址无效（{base}）：{e}"))?;

    // 记下 hostid 地址：启动时据此校验会话、失效时可一键重登
    let mut cfg = config::load(app);
    if cfg.hostid_base_url != base {
        cfg.hostid_base_url = base.clone();
        config::save(app, &cfg)?;
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

    // 登录窗里注入脚本，两个职责：
    // 1) 轮询 hostid 的 /api/sso/session，一旦 authenticated 就取回用户，经同源路径回传
    //    （on_navigation 拦截）。不依赖登录后停在哪个页面——登录页 / 成功页 / 任意 hostid
    //    页面都行，地址是直达还是跳转过来的都不影响。
    // 2) 连着几次都拿不到 hostid 形状的会话接口，就判「这个地址不是 hostid 实例」并回传，
    //    由壳关窗说明原因（见下面的收尾线程）。
    let done_lit = serde_json::to_string(SSO_DONE_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let invalid_lit =
        serde_json::to_string(LOGIN_INVALID_PATH).unwrap_or_else(|_| "\"\"".to_string());
    let script = format!(
        r#"(function () {{
  var done = {done};
  var invalid = {invalid};
  var sent = false;
  var strikes = 0;

  // 判「这个地址不是 hostid」必须保守，否则会把好地址的登录窗关掉——未登录时接口回
  // 401 / 302 都是正常的。逐条定：
  //   401 / 403                     → 接口在、只是要鉴权：认它是 hostid
  //   发生过重定向                  → 说不清（可能是跳登录页）：不判
  //   其它非 2xx（如 404）          → 接口不存在：记一次
  //   200 但正文不是 {{authenticated: bool}} 形状 → 不是 hostid 的接口：记一次
  //   网络错误                      → 不判（地址不通由壳那边的看门狗负责）
  // 连记 3 次（约 2.4s）才定案：这个地址用不了，登录流程也走不通。
  function strike() {{
    if (sent) return;
    if (++strikes >= 3) location.href = invalid;
  }}
  function probe() {{
    if (sent) return;
    fetch('/api/sso/session', {{ credentials: 'include' }})
      .then(function (r) {{
        if (r.status === 401 || r.status === 403) {{ strikes = 0; return; }}
        if (r.redirected) return;
        if (!r.ok) {{ strike(); return; }}
        return r.text().then(function (t) {{
          var s = null;
          try {{ s = JSON.parse(t); }} catch (e) {{ s = null; }}
          if (s && typeof s.authenticated === 'boolean') {{
            strikes = 0;
            if (s.authenticated) {{
              sent = true;
              location.href = done + '?data=' + encodeURIComponent(t);
            }}
            return;
          }}
          strike();
        }});
      }})
      .catch(function () {{}});
  }}
  probe();
  setInterval(probe, 800);
}})();"#,
        done = done_lit,
        invalid = invalid_lit,
    );

    // 登录窗回传的信号：页面加载出来了（标题栏注入成功）/ 不是 hostid 实例 / 登录用户 JSON
    let captured: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let captured_nav = captured.clone();
    let ready = Arc::new(AtomicBool::new(false));
    let ready_nav = ready.clone();
    let invalid = Arc::new(AtomicBool::new(false));
    let invalid_nav = invalid.clone();
    // 标题栏脚本里的「已加载」回传路径占位符换成真路径
    let bar = LOGIN_BAR_JS.replace("__MINIS_LOGIN_READY__", LOGIN_READY_PATH);

    WebviewWindowBuilder::new(app, LOGIN_LABEL, WebviewUrl::External(login_url))
        .title("登录 hostid")
        .decorations(false) // 无边框：标题栏改由 LOGIN_BAR_JS 自绘（拖拽区 + 圆形窗口按钮）
        .inner_size(520.0, 806.0)
        // 高度不低于 806：hostid 登录页矮于此会挤掉表单
        .min_inner_size(420.0, 806.0)
        .center()
        .initialization_script(format!("{script}\n{bar}"))
        .on_navigation(move |nav| {
            if nav.path() == LOGIN_READY_PATH {
                ready_nav.store(true, Ordering::SeqCst);
                return false;
            }
            if nav.path() == LOGIN_INVALID_PATH {
                invalid_nav.store(true, Ordering::SeqCst);
                return false;
            }
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

    // 收尾线程：登录成功 → 写配置关窗（远程流程再顺势进实例）；用户关窗 → 当取消；
    // 页面压根没加载出来 / 不像 hostid → 关窗说明原因。
    // 登录窗是无边框的，关闭按钮靠注入，页面加载失败时注入不进去，窗口既没关闭入口也没法
    // 登录，所以这两种情况必须由壳主动收掉。
    let handle = app.clone();
    let captured_poll = captured.clone();
    let ready_poll = ready.clone();
    let invalid_poll = invalid.clone();
    std::thread::spawn(move || {
        let opened = std::time::Instant::now();
        loop {
            std::thread::sleep(Duration::from_millis(400));
            // 先取回传：即便用户登录后立刻关窗，也不会把这次成功误判成「取消」
            let payload = captured_poll.lock().unwrap().take();
            let window = handle.get_webview_window(LOGIN_LABEL);
            match (payload, window) {
                (Some(payload), window) => {
                    if let Some(window) = window {
                        let _ = window.close();
                    }
                    persist_sso_user(&handle, &payload);
                    if let Some(dest) = then_navigate {
                        // 登录成功也走统一的「进入实例」过渡（引导页加载层 → 实例页渐出），
                        // 而不是直接跳过去
                        notify_main(&handle, "登录成功，正在进入远程实例…");
                        enter_instance(&handle, &dest, "remote");
                    }
                    return;
                }
                (None, Some(window)) => {
                    if invalid_poll.load(Ordering::SeqCst) {
                        let _ = window.destroy();
                        notify_main(&handle, "该地址不是可用的 hostid 实例：找不到 hostid 的 SSO 接口");
                        emit_status(&handle, "idle", "该地址不是 hostid 实例");
                        return;
                    }
                    // 5 秒还没报「已加载」：多半是地址不可达（DNS / 连接 / 证书失败）。
                    // WebView 的错误页上注入不进标题栏，这个窗口留着也没法用。
                    if !ready_poll.load(Ordering::SeqCst)
                        && opened.elapsed() > Duration::from_secs(5)
                    {
                        let _ = window.destroy();
                        notify_main(&handle, "无法打开该地址：页面没有加载出来，请检查地址是否可达");
                        emit_status(&handle, "idle", "地址不可达");
                        return;
                    }
                }
                // 登录窗被关掉（用户取消）：把状态从「启动中」放出来，别让引导页一直禁用按钮
                (None, None) => {
                    emit_status(&handle, "idle", "已取消登录");
                    return;
                }
            }
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
        // 登录成功：清掉「会话已失效」标记（引导页收到 minis://user 会重读状态，横幅随之撤下）
        set_session_expired(app, false);
        emit_users(app);
    }
}

/* ------------------------------------------------------------------ *
 * 窗口控制
 *
 * 主窗口已去掉系统装饰（见 setup 里的 .decorations(false)），系统不再提供标题栏、
 * 拖拽区与边缘缩放。引导页（tauri 源）与实例页（HTTP 源）是同一个 webview 先后加载的两份
 * document，各自用页面自绘的标题栏，统一经 Tauri 核心 window 插件的命令回落系统行为：
 *
 *   拖拽   → data-tauri-drag-region 属性（框架内置脚本，含双击最大化）
 *   最小化 → plugin:window|minimize
 *   最大化 → plugin:window|toggle_maximize（图标状态读 is_maximized）
 *   关闭   → plugin:window|close
 *   缩放   → plugin:window|start_resize_dragging（窗缘热区，页面自绘）
 *
 * 这些命令由 capabilities/default.json 授权（windows 列 main），其中 remote 列出 HTTP 源，
 * 否则实例页没有 IPC，标题栏会变成死钮。
 * ------------------------------------------------------------------ */

fn main() {
    tauri::Builder::default()
        // 单实例必须最先注册：本机后端含 SQLite(WAL) 与进程内心跳/圆桌状态，不能双开
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // 单窗口：唤醒即显示并聚焦主窗口（里面当前显示的是引导页或实例页）。
            // 取 Window 而不是「webview 窗口」——主窗口里还挂着邮件的子 webview
            if let Some(window) = app.get_window(WINDOW_LABEL) {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![
            economy_capture::economy_windows,
            economy_capture::economy_capture_window,
            desktop_state,
            desktop_users,
            desktop_switch_user,
            desktop_save_local_user,
            desktop_hostid_base,
            desktop_save_mail_url,
            desktop_show_mail,
            desktop_relogin_hostid,
            desktop_return_done,
            desktop_dismiss_session_notice,
            desktop_logout_hostid,
            desktop_choose_local,
            desktop_choose_remote,
            desktop_show_instance,
            desktop_view_applied,
            desktop_login_hostid
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let cfg = config::load(&handle);

            // 令牌对非回环来源不生效：远程站点拿不到本机令牌，本机页面照常带上
            let token = uuid::Uuid::new_v4().simple().to_string();

            app.manage(Shell {
                token,
                child: Mutex::new(None),
                port: Mutex::new(None),
                status: Mutex::new(Status {
                    mode: cfg.mode,
                    remote_base_url: cfg.remote_base_url.clone(),
                    phase: "idle".to_string(),
                    detail: String::new(),
                    session_expired: false,
                    instance: None,
                    open_view: None,
                    mail_url: cfg.mail_url.clone(),
                }),
                launcher_url: Mutex::new(String::new()),
                returning: AtomicBool::new(false),
                entering: AtomicBool::new(false),
            });

            // 一律先落在引导页：本机 / 远程由用户当场选，壳不自动连任何一个。
            // 引导页与实例页共用这一个 webview，靠导航互换（见文件头说明）。
            WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App("index.html".into()))
                .title(env!("BRAND_NAME"))
                .inner_size(1280.0, 720.0)
                .min_inner_size(1280.0, 720.0)
                // 全面自定义标题栏：不要系统装饰。标题栏 / 拖拽区 / 窗口按钮 / 边缘缩放
                // 全部由页面自绘（引导页见 desktop/ui/index.html，主应用见 frontend 的桌面框架），
                // 两侧都经下面的 desktop_window_* 命令回落到系统行为。
                .decorations(false)
                // 亚克力：窗口本身透明，桌面从外壳的圆角与那一圈亚克力里透出来。
                // 模糊由系统做（blur/acrylic 是窗口级效果，CSS 的 backdrop-filter 只能糊
                // 页面自己的内容、糊不到桌面），页面只负责压一层白色当磨砂。
                .transparent(true)
                // 空的效果配置：先把调用点占住，将来开亚克力往列表里加一项即可。
                // effect 列表为空时，系统侧 apply_effects 找不到可应用的条项，直接返回，
                // 所以现在这个写法是纯 no-op。加效果时记得把 Effect / EffectState / Color
                // 补回上面的 use。
                .effects(EffectsBuilder::new().build())
                // 关掉系统阴影：无边框窗口在 Windows 上仍留着 WS_THICKFRAME，
                // DWM 会按「窗口矩形」而不是页面的圆角外壳去投影子（实测窗口矩形
                // 比页面视口整整大一圈），于是圆角外会多出一块方角影子。
                // 关掉之后，窗口的轮廓完全由页面的圆角外壳决定。
                .shadow(false)
                // 每次启动都居中；再兜一下溢出——1600×900 在小屏上放不下时，
                // 会被夹到「屏幕 - 任务栏」的尺寸，免得居中之后反而被裁掉一块
                .center()
                .prevent_overflow()
                .initialization_script(&desktop_injection(&handle))
                // 导航闸门：三类哨兵导航由壳接管（见 intercept_navigation）。
                // 用导航而非 IPC：远程源调用壳自有命令受 Tauri ACL 限制，导航拦截不依赖 ACL。
                .on_navigation({
                    let nav_handle = handle.clone();
                    move |nav| intercept_navigation(&nav_handle, nav)
                })
                // 主窗口每次页面加载：① 第一次（引导页）把它的地址记下，从实例返回时据此导航回来；
                // ② 若正在「进入实例」，在文档创建时就铺过渡层，由实例页接着做「模糊 → 清晰」的渐出
                .on_page_load({
                    let page_handle = handle.clone();
                    move |window, payload| {
                        if !matches!(payload.event(), PageLoadEvent::Started) {
                            return;
                        }
                        remember_launcher_if_first(&page_handle, payload.url());
                        if page_handle
                            .state::<Shell>()
                            .entering
                            .swap(false, Ordering::SeqCst)
                        {
                            let _ = window.eval(ENTER_VEIL_JS);
                        }
                    }
                })
                .build()?;
            backend::log_probe(&handle);
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("minis desktop 启动失败")
        .run(|app, event| match event {
            RunEvent::Exit => backend::stop(app),
            // 主窗口一关就把附属窗口一起收掉：它们不是独立窗口，主窗口没了还留在屏幕上
            // 会变成孤儿，而且进程会因为有窗口而赖着不退（本机后端也就一直跑着）。
            RunEvent::WindowEvent { label, event, .. } if label == WINDOW_LABEL => {
                if matches!(event, tauri::WindowEvent::CloseRequested { .. }) {
                    close_aux_windows(app);
                }
            }
            _ => {}
        });
}

/// 强制关掉壳开出来的附属窗口（登录 / 认证探测 / 会话撤销）。
/// 用 destroy 而不是 close：这几个窗口不参与关闭协商，主窗口一走就必须消失。
fn close_aux_windows(app: &AppHandle) {
    for label in [LOGIN_LABEL, PROBE_LABEL, REVOKE_LABEL] {
        if let Some(window) = app.get_webview_window(label) {
            let _ = window.destroy();
        }
    }
}
