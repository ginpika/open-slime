# HostID SSO 集成指南 (Rust)

本文档指导如何将 HostID 作为 SSO 服务端，接入 Rust 客户端应用。

## 架构概述

```
┌─────────────────────┐                         ┌─────────────────────┐
│     SSO Server      │                         │     SSO Client      │
│     mail.a.com      │                         │     app.a.com       │
├─────────────────────┤                         ├─────────────────────┤
│ /api/sso/session    │◄──── Cookie ───────────►│   Check Session     │
│ /api/sso/login      │◄──── POST Form ────────►│   Submit Login      │
│ /api/sso/logout     │◄──── Cookie ───────────►│   User Logout       │
│ /login              │◄──── Browser Redirect ─►│   Login Page (GET)  │
└─────────────────────┘                         └─────────────────────┘
```

**重要说明：**
- `/login?redirect=xxx` 是前端页面路由，客户端应重定向浏览器到此页面
- `/api/sso/login` 是后端 API，仅用于前端页面提交登录表单（POST 请求）
- 客户端集成时只需使用 `/login?redirect=xxx` 和 `/api/sso/session`

## 前置条件

1. 所有应用必须在同一主域名下（如 `*.a.com`）
2. SSO 服务端已部署并可访问
3. Rust 1.70+ 环境

## 环境配置

### SSO 服务端配置

```env
# .env
SSO_COOKIE_DOMAIN=.a.com
SSO_COOKIE_NAME=sso_token
SSO_COOKIE_PATH=/
SSO_COOKIE_SECURE=true
SSO_COOKIE_SAMESITE=lax
SESSION_TTL=604800
```

## API 接口

### 1. 验证登录态

```http
GET /api/sso/session HTTP/1.1
Host: mail.a.com
Cookie: sso_token=<token>
```

**响应：**
```json
{
  "authenticated": true,
  "user": {
    "id": "clxxxx",
    "username": "user",
    "email": "user@a.com",
    "role": "user"
  }
}
```

### 2. 登录

```http
POST /api/sso/login HTTP/1.1
Host: mail.a.com
Content-Type: application/json

{
  "username": "user",
  "password": "password"
}
```

### 3. 登出

```http
POST /api/sso/logout HTTP/1.1
Host: mail.a.com
Cookie: sso_token=<token>
```

## Rust 集成示例

### 1. 项目依赖

```toml
# Cargo.toml
[dependencies]
tokio = { version = "1", features = ["full"] }
reqwest = { version = "0.11", features = ["json", "cookies"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
axum = "0.7"
tower = "0.4"
tower-http = { version = "0.5", features = ["cors", "cookies"] }
anyhow = "1"
thiserror = "1"
urlencoding = "2"
```

### 2. 类型定义

```rust
// src/sso/types.rs
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub id: String,
    pub username: String,
    pub email: String,
    pub role: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SessionResponse {
    pub authenticated: bool,
    pub user: Option<User>,
}

#[derive(Debug, Serialize)]
pub struct LoginRequest {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Deserialize)]
pub struct LoginResponse {
    pub success: bool,
    pub user: Option<User>,
}
```

### 3. SSO 客户端

```rust
// src/sso/client.rs
use anyhow::Result;
use reqwest::Client;

use super::types::{SessionResponse, LoginRequest, LoginResponse};

pub struct SsoClient {
    server_url: String,
    http_client: Client,
}

impl SsoClient {
    pub fn new(server_url: String) -> Self {
        Self {
            server_url,
            http_client: Client::builder()
                .cookie_store(true)
                .build()
                .unwrap(),
        }
    }

    pub async fn check_session(&self, token: &str) -> Result<SessionResponse> {
        let response = self.http_client
            .get(format!("{}/api/sso/session", self.server_url))
            .header("Cookie", format!("sso_token={}", token))
            .send()
            .await?;

        let session = response.json::<SessionResponse>().await?;
        Ok(session)
    }

    pub async fn login(&self, username: &str, password: &str) -> Result<LoginResponse> {
        let response = self.http_client
            .post(format!("{}/api/sso/login", self.server_url))
            .json(&LoginRequest {
                username: username.to_string(),
                password: password.to_string(),
            })
            .send()
            .await?;

        let login_response = response.json::<LoginResponse>().await?;
        Ok(login_response)
    }

    pub async fn logout(&self, token: &str) -> Result<()> {
        self.http_client
            .post(format!("{}/api/sso/logout", self.server_url))
            .header("Cookie", format!("sso_token={}", token))
            .send()
            .await?;

        Ok(())
    }

    pub fn get_login_url(&self, redirect_url: &str) -> String {
        format!(
            "{}/sso/login?redirect={}",
            self.server_url,
            urlencoding::encode(redirect_url)
        )
    }
}
```

### 4. 中间件

```rust
// src/sso/middleware.rs
use axum::{
    extract::Request,
    http::StatusCode,
    middleware::Next,
    response::Response,
};
use tower_cookies::Cookies;

use super::client::SsoClient;
use super::types::User;

pub struct SsoUser(pub User);

pub async fn auth_middleware(
    cookies: Cookies,
    sso_client: axum::extract::Extension<SsoClient>,
    request: Request,
    next: Next,
) -> Result<Response, StatusCode> {
    let token = cookies
        .get("sso_token")
        .map(|c| c.value().to_string());

    let token = match token {
        Some(t) => t,
        None => return Err(StatusCode::UNAUTHORIZED),
    };

    let session = sso_client
        .0
        .check_session(&token)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

    if !session.authenticated {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let user = session.user.ok_or(StatusCode::UNAUTHORIZED)?;

    let mut request = request;
    request.extensions_mut().insert(SsoUser(user));

    Ok(next.run(request).await)
}
```

### 5. 处理器

```rust
// src/handlers.rs
use axum::{
    extract::Extension,
    http::StatusCode,
    response::{IntoResponse, Redirect},
    Json,
};
use tower_cookies::{Cookie, Cookies};

use crate::sso::{client::SsoClient, middleware::SsoUser, types::User};

pub async fn get_current_user(sso_user: Option<SsoUser>) -> impl IntoResponse {
    match sso_user {
        Some(user) => Json(user.0).into_response(),
        None => StatusCode::UNAUTHORIZED.into_response(),
    }
}

pub async fn logout(
    cookies: Cookies,
    sso_client: Extension<SsoClient>,
) -> impl IntoResponse {
    if let Some(token) = cookies.get("sso_token") {
        let _ = sso_client.0.logout(token.value()).await;
    }

    let mut cookie = Cookie::build(("sso_token", ""))
        .path("/")
        .max_age(time::Duration::seconds(0))
        .http_only(true)
        .build();
    
    cookies.remove(cookie);

    let login_url = sso_client.0.get_login_url("/");
    Redirect::temporary(&login_url)
}

pub async fn sso_login_redirect(
    sso_client: Extension<SsoClient>,
) -> impl IntoResponse {
    let login_url = sso_client.0.get_login_url("/");
    Redirect::temporary(&login_url)
}
```

### 6. 主程序

```rust
// src/main.rs
mod sso;
mod handlers;

use std::env;
use axum::{
    routing::{get, post},
    Extension, Router,
};
use tower_cookies::CookieManagerLayer;
use tower_http::cors::CorsLayer;

use sso::client::SsoClient;
use sso::middleware::auth_middleware;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let sso_server_url = env::var("SSO_SERVER_URL")
        .unwrap_or_else(|_| "https://mail.a.com".to_string());
    
    let sso_client = SsoClient::new(sso_server_url);

    let protected_routes = Router::new()
        .route("/api/me", get(handlers::get_current_user))
        .route("/api/logout", post(handlers::logout))
        .layer(axum::middleware::from_fn(auth_middleware));

    let app = Router::new()
        .route("/login", get(handlers::sso_login_redirect))
        .merge(protected_routes)
        .layer(Extension(sso_client))
        .layer(CookieManagerLayer::new())
        .layer(CorsLayer::permissive());

    let addr = "0.0.0.0:8080";
    println!("Server running on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
```

## Actix-web 示例

```rust
// main.rs (Actix-web version)
use actix_web::{web, App, HttpServer, HttpResponse, Responder, HttpRequest};
use actix_web::middleware::Logger;
use actix_cors::Cors;
use reqwest::Client;

struct SsoClient {
    server_url: String,
    http_client: Client,
}

impl SsoClient {
    fn new(server_url: String) -> Self {
        Self {
            server_url,
            http_client: Client::new(),
        }
    }

    async fn check_session(&self, token: &str) -> Result<SessionResponse, reqwest::Error> {
        self.http_client
            .get(format!("{}/api/sso/session", self.server_url))
            .header("Cookie", format!("sso_token={}", token))
            .send()
            .await?
            .json()
            .await
    }
}

async fn get_me(req: HttpRequest) -> impl Responder {
    let user = req.extensions().get::<User>().cloned();
    
    match user {
        Some(u) => HttpResponse::Ok().json(u),
        None => HttpResponse::Unauthorized().finish(),
    }
}

async fn index() -> impl Responder {
    HttpResponse::Ok().body("Hello, authenticated user!")
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    let sso_client = web::Data::new(SsoClient::new(
        "https://mail.a.com".to_string()
    ));

    HttpServer::new(move || {
        App::new()
            .app_data(sso_client.clone())
            .wrap(Logger::default())
            .wrap(Cors::permissive())
            .route("/", web::get().to(index))
            .route("/api/me", web::get().to(get_me))
    })
    .bind("0.0.0.0:8080")?
    .run()
    .await
}
```

## 注意事项

### 1. Cookie 跨域

确保所有应用在同一主域名下，Cookie 的 `domain` 属性设置为 `.a.com`。

### 2. HTTPS 要求

生产环境必须使用 HTTPS。

### 3. 会话同步

```rust
use std::time::Duration;
use tokio::time::interval;

async fn session_sync_task(sso_client: SsoClient, token_store: TokenStore) {
    let mut ticker = interval(Duration::from_secs(300)); // 5 minutes

    loop {
        ticker.tick().await;
        
        let tokens = token_store.get_all().await;
        for token in tokens {
            if let Ok(session) = sso_client.check_session(&token).await {
                if !session.authenticated {
                    token_store.remove(&token).await;
                }
            }
        }
    }
}
```

## 故障排查

| 问题 | 解决方案 |
|------|----------|
| Cookie 未设置 | 检查 `SSO_COOKIE_DOMAIN` 配置 |
| 跨域请求失败 | 确认 CORS 配置正确 |
| 会话丢失 | 检查会话是否过期 |

## 安全建议

1. 生产环境必须使用 HTTPS
2. 设置合理的会话过期时间
3. 敏感操作需要二次验证
4. 记录登录日志用于审计
