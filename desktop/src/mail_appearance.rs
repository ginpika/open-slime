//! HostID appearance v1: only the mail WebView can use this navigation bridge.
//! The launcher applies the color only while its mail view is active.

use tauri::AppHandle;

pub const SCRIPT: &str = include_str!("../ui/mail-appearance.js");
const PATH: &str = "/__minis_mail_appearance";

fn background_from_url(url: &tauri::Url) -> Option<String> {
    if url.path() != PATH {
        return None;
    }
    let version = url.query_pairs().find(|(key, _)| key == "version")?.1;
    if version != "1" {
        return None;
    }
    let background = url
        .query_pairs()
        .find(|(key, _)| key == "backgroundColor")?
        .1;
    let hex = background.strip_prefix('#')?;
    if hex.len() != 6 || !hex.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    Some(background.to_ascii_lowercase())
}

fn update_script(background: Option<&str>) -> String {
    match background {
        Some(background) => format!(
            "document.documentElement.style.setProperty('--mail-background', {});",
            serde_json::to_string(background).unwrap()
        ),
        None => "document.documentElement.style.removeProperty('--mail-background');".into(),
    }
}

pub fn reset(app: &AppHandle) {
    if let Some(webview) = super::main_webview(app) {
        let _ = webview.eval(update_script(None));
    }
}

/// Return false for every appearance sentinel, including malformed payloads.
/// No sentinel is allowed to leave the WebView or replace the current page.
pub fn intercept_navigation(app: &AppHandle, url: &tauri::Url) -> bool {
    if url.path() != PATH {
        return true;
    }
    if let Some(background) = background_from_url(url) {
        if let Some(webview) = super::main_webview(app) {
            let _ = webview.eval(update_script(Some(&background)));
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_version_one_opaque_hex_colors() {
        let url = tauri::Url::parse(
            "https://mail.example.test/__minis_mail_appearance?version=1&backgroundColor=%23F4F6FA",
        )
        .unwrap();
        assert_eq!(background_from_url(&url).as_deref(), Some("#f4f6fa"));
    }

    #[test]
    fn rejects_unrelated_paths_versions_and_non_color_payloads() {
        for path in [
            "/?version=1&backgroundColor=%23f4f6fa",
            "/__minis_mail_appearance?backgroundColor=%23f4f6fa",
            "/__minis_mail_appearance?version=2&backgroundColor=%23f4f6fa",
            "/__minis_mail_appearance?version=1",
            "/__minis_mail_appearance?version=1&backgroundColor=transparent",
            "/__minis_mail_appearance?version=1&backgroundColor=%23fff",
            "/__minis_mail_appearance?version=1&backgroundColor=%23f4f6faff",
            "/__minis_mail_appearance?version=1&backgroundColor=%23f4f6fa%3Balert(1)",
        ] {
            let url = tauri::Url::parse(&format!("https://mail.example.test{path}")).unwrap();
            assert_eq!(background_from_url(&url), None, "{path}");
        }
    }

    #[test]
    fn clearing_the_background_restores_the_launcher_default() {
        assert!(update_script(Some("#10151e")).contains("\"#10151e\""));
        assert!(update_script(None).contains("removeProperty('--mail-background')"));
    }
}
