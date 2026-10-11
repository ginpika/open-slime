use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::{AppHandle, Manager};

/// 后端来源：本机（壳拉起 sidecar）/ 远程（连服务端部署）
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Local,
    Remote,
}

impl Default for Mode {
    fn default() -> Self {
        Mode::Local
    }
}

/// 壳的持久化配置（{AppConfigDir}/config.json）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// 上次使用的模式（引导页据此默认高亮，不自动连）
    #[serde(default)]
    pub mode: Mode,
    /// 远程模式的服务端地址（形如 https://minis.example.com，不带结尾斜杠）
    #[serde(default, rename = "remoteBaseUrl")]
    pub remote_base_url: String,
    /// 本地用户：进入本机模式后顶替后端的 local-dev 用户名（空 = 用后端默认）
    #[serde(default, rename = "localUserName")]
    pub local_user_name: String,
    /// 本地用户头像：data:image/...;base64,... 的小图（引导页压到 128px）；空 = 用首字母兜底
    #[serde(default, rename = "localUserAvatar")]
    pub local_user_avatar: String,
    /// 当前用户在菜单里选中的身份："local" | "sso"
    #[serde(default = "default_current_user", rename = "currentUser")]
    pub current_user: String,
    /// hostid 服务端地址（登录时记下；启动时据此校验会话、失效时可一键重登）
    #[serde(default, rename = "hostidBaseUrl")]
    pub hostid_base_url: String,
    /// 邮件服务地址（hostid 自带邮件，但特殊场景下它的前端与后端不同域，这里填真实地址；
    /// 空 = 退回用 hostid 地址，见 desktop_hostid_base）
    #[serde(default, rename = "mailUrl")]
    pub mail_url: String,
    /// hostid SSO 用户（登录成功后由登录窗口回传写入；name 空 = 未登录）
    #[serde(default, rename = "ssoUserName")]
    pub sso_user_name: String,
    /// hostid SSO 用户头像（avatarUrl，可能为空）
    #[serde(default, rename = "ssoUserAvatar")]
    pub sso_user_avatar: String,
    /// hostid SSO 用户邮箱
    #[serde(default, rename = "ssoUserEmail")]
    pub sso_user_email: String,
}

/// 缺省当前用户：本地用户
fn default_current_user() -> String {
    "local".to_string()
}

impl Default for Config {
    fn default() -> Self {
        Config {
            mode: Mode::default(),
            remote_base_url: String::new(),
            local_user_name: String::new(),
            local_user_avatar: String::new(),
            current_user: default_current_user(),
            hostid_base_url: String::new(),
            mail_url: String::new(),
            sso_user_name: String::new(),
            sso_user_avatar: String::new(),
            sso_user_email: String::new(),
        }
    }
}

fn path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_config_dir().map_err(|e| e.to_string())?;
    Ok(dir.join("config.json"))
}

/// 读取配置；文件缺失或损坏时回落到默认（本机模式）
pub fn load(app: &AppHandle) -> Config {
    let Ok(p) = path(app) else {
        return Config::default();
    };
    std::fs::read_to_string(p)
        .ok()
        .and_then(|s| serde_json::from_str::<Config>(&s).ok())
        .unwrap_or_default()
}

/// 写回配置（目录不存在时创建）
pub fn save(app: &AppHandle, cfg: &Config) -> Result<(), String> {
    let p = path(app)?;
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let json = serde_json::to_string_pretty(cfg).map_err(|e| e.to_string())?;
    std::fs::write(p, json).map_err(|e| e.to_string())
}
