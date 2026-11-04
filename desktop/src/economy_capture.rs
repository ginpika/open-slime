//! Explicit window selection only; capture stays local until the user requests analysis.
use serde::Serialize;
use tauri::{Manager, Webview};

#[derive(Serialize)]
pub struct WindowInfo {
    id: u32,
    pid: u32,
    title: String,
    app: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Frame {
    image: String,
    title: String,
    captured_at: u64,
}

fn authorize(webview: &Webview) -> Result<(), String> {
    if webview.label() != crate::WINDOW_LABEL {
        return Err("仅经济研究页可以观察窗口".into());
    }
    let url = webview.url().map_err(|e| e.to_string())?;
    if !matches!(
        url.fragment().unwrap_or("").split('?').next(),
        Some("/economy/research" | "/economy/market" | "/economy/stock")
    ) {
        return Err("请从经济研究页选择窗口".into());
    }
    let shell = webview.state::<crate::Shell>();
    let status = shell.status.lock().map_err(|_| "桌面状态不可用")?;
    let instance = status.instance.as_ref().ok_or("请先进入实例")?;
    let expected = tauri::Url::parse(&instance.url).map_err(|e| e.to_string())?;
    if url.origin() != expected.origin() {
        return Err("窗口观察来源不匹配".into());
    }
    Ok(())
}

#[tauri::command]
pub async fn economy_windows(webview: Webview) -> Result<Vec<WindowInfo>, String> {
    authorize(&webview)?;
    tauri::async_runtime::spawn_blocking(list)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn economy_capture_window(webview: Webview, id: u32, pid: u32) -> Result<Frame, String> {
    authorize(&webview)?;
    tauri::async_runtime::spawn_blocking(move || capture(id, pid))
        .await
        .map_err(|e| e.to_string())?
}

#[cfg(any(target_os = "windows", target_os = "macos"))]
pub(crate) fn list() -> Result<Vec<WindowInfo>, String> {
    let windows =
        xcap::Window::all().map_err(|e| format!("无法列出窗口，请检查系统屏幕录制权限：{e}"))?;
    Ok(windows
        .into_iter()
        .filter_map(|w| {
            if w.is_minimized().unwrap_or(true) {
                return None;
            }
            let title = w.title().ok()?;
            if title.trim().is_empty() {
                return None;
            }
            Some(WindowInfo {
                id: w.id().ok()?,
                pid: w.pid().ok()?,
                title,
                app: w.app_name().unwrap_or_default(),
            })
        })
        .collect())
}

#[cfg(any(target_os = "windows", target_os = "macos"))]
pub(crate) fn capture(id: u32, pid: u32) -> Result<Frame, String> {
    let window = xcap::Window::all()
        .map_err(|e| e.to_string())?
        .into_iter()
        .find(|w| w.id().ok() == Some(id) && w.pid().ok() == Some(pid))
        .ok_or("窗口已关闭，请重新选择")?;
    if window.is_minimized().map_err(|e| e.to_string())? {
        return Err("窗口已最小化，请恢复后重试".into());
    }
    let title = window.title().map_err(|e| e.to_string())?;
    let pixels = window
        .capture_image()
        .map_err(|e| format!("截图失败，请检查系统屏幕录制权限：{e}"))?;
    let image = encode_frame(pixels)?;
    Ok(Frame {
        image,
        title,
        captured_at: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|e| e.to_string())?
            .as_millis() as u64,
    })
}

#[cfg(any(target_os = "windows", target_os = "macos"))]
fn encode_frame(pixels: image::RgbaImage) -> Result<String, String> {
    use base64::Engine;
    if pixels.pixels().all(|p| p[0] == 0 && p[1] == 0 && p[2] == 0) {
        return Err("窗口没有可读取画面，请将窗口移到屏幕内并恢复显示后重试".into());
    }
    // Screen capture is opaque. Drop platform-specific alpha before resampling.
    let rgb = image::DynamicImage::ImageRgba8(pixels).to_rgb8();
    let image = image::DynamicImage::ImageRgb8(rgb)
        .resize(1600, 1000, image::imageops::FilterType::Triangle)
        .to_rgb8();
    let mut bytes = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut bytes, 80)
        .encode_image(&image)
        .map_err(|e| e.to_string())?;
    Ok(format!(
        "data:image/jpeg;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(bytes)
    ))
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
pub(crate) fn list() -> Result<Vec<WindowInfo>, String> {
    Err("原生窗口观察仅支持 Windows/macOS".into())
}
#[cfg(not(any(target_os = "windows", target_os = "macos")))]
pub(crate) fn capture(_: u32, _: u32) -> Result<Frame, String> {
    Err("原生窗口观察仅支持 Windows/macOS".into())
}

#[cfg(all(test, target_os = "windows"))]
mod tests {
    use super::*;
    use base64::Engine;
    use windows::{
        core::w,
        Win32::{
            Foundation::{COLORREF, HINSTANCE, HWND, LPARAM, LRESULT, RECT, WPARAM},
            Graphics::Gdi::{CreateSolidBrush, DeleteObject, FillRect, UpdateWindow, HDC},
            System::LibraryLoader::GetModuleHandleW,
            UI::WindowsAndMessaging::*,
        },
    };
    unsafe extern "system" fn procedure(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        if msg == WM_PRINT || msg == WM_PRINTCLIENT {
            let brush = CreateSolidBrush(COLORREF(0x003322dd));
            FillRect(
                HDC(wparam.0 as *mut _),
                &RECT {
                    left: 0,
                    top: 0,
                    right: 640,
                    bottom: 480,
                },
                brush,
            );
            let _ = DeleteObject(brush.into());
            return LRESULT(1);
        }
        DefWindowProcW(hwnd, msg, wparam, lparam)
    }

    #[test]
    fn selected_window_identity_unavailable_frame_and_close_are_checked() {
        use std::os::windows::process::CommandExt;
        // xcap excludes the caller's own windows; isolate the fixture in a child process.
        let child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "economy_capture::tests::fixture_window_process",
                "--nocapture",
            ])
            .env("MINIS_CAPTURE_FIXTURE", "1")
            .creation_flags(0x08000000)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        struct Guard(std::process::Child);
        impl Drop for Guard {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let guard = Guard(child);
        let mut fixture = None;
        for _ in 0..50 {
            fixture = list()
                .unwrap()
                .into_iter()
                .find(|w| w.title == "MinisEconomyCaptureFixture" && w.pid == guard.0.id());
            if fixture.is_some() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        let fixture = fixture.expect("test window must be enumerable");
        // Windows does not render this deliberately off-screen window. Never pass a blank frame to analysis.
        assert!(capture(fixture.id, fixture.pid)
            .err()
            .unwrap()
            .contains("窗口没有可读取画面"));
        assert!(capture(fixture.id, fixture.pid.wrapping_add(1)).is_err());
        drop(guard);
        assert!(capture(fixture.id, fixture.pid).is_err());
    }

    #[test]
    fn frame_encoding_preserves_content_and_rejects_blank_images() {
        let frame = encode_frame(image::RgbaImage::from_pixel(
            640,
            480,
            image::Rgba([221, 34, 51, 0]),
        ))
        .unwrap();
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(frame.split(',').nth(1).unwrap())
            .unwrap();
        let decoded = image::load_from_memory(&bytes).unwrap().to_rgb8();
        assert!(decoded.width() <= 1600 && decoded.height() <= 1000);
        let pixel = decoded.get_pixel(100, 100);
        assert!(pixel[0] > 200 && pixel[1] < 60 && pixel[2] < 75);
        assert!(encode_frame(image::RgbaImage::new(640, 480)).is_err());
    }

    #[test]
    fn fixture_window_process() {
        if std::env::var("MINIS_CAPTURE_FIXTURE").as_deref() != Ok("1") {
            return;
        }
        // Off-screen, no focus, no taskbar button; its message loop paints only this fixture.
        unsafe {
            let module = GetModuleHandleW(None).unwrap();
            let class = w!("MinisEconomyCaptureTest");
            let wc = WNDCLASSW {
                lpfnWndProc: Some(procedure),
                hInstance: HINSTANCE(module.0),
                lpszClassName: class,
                hbrBackground: CreateSolidBrush(COLORREF(0x003322dd)),
                ..Default::default()
            };
            assert_ne!(RegisterClassW(&wc), 0);
            let hwnd = CreateWindowExW(
                WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                class,
                w!("MinisEconomyCaptureFixture"),
                WS_POPUP | WS_VISIBLE,
                -10000,
                -10000,
                640,
                480,
                None,
                None,
                Some(HINSTANCE(module.0)),
                None,
            )
            .unwrap();
            let _ = UpdateWindow(hwnd);
            let mut message = MSG::default();
            while GetMessageW(&mut message, None, 0, 0).as_bool() {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
    }
}
