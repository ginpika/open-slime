use std::path::PathBuf;

fn main() {
    // tauri-build 自己只声明了对 tauri.conf.json / capabilities / runtime 的依赖，没有声明图标。
    // 结果是「只换图标」不会触发 build script 重跑，也就不会重新链接——exe 里内嵌的还是旧图标；
    // 而 Windows 的窗口/任务栏图标恰恰是从 exe 的内嵌资源取的（见 tauri::image 的
    // default_window_icon_from_app_icon_resource），于是换了 icons/ 之后界面看不出来。
    // 这里替它补上：icons 目录一有改动就重跑 build script、重新编译资源并链接。
    println!("cargo:rerun-if-changed=icons");

    // 品牌名来自仓库根目录 branding.json：编译期读入并注入环境变量，供 main.rs / backend.rs 取用。
    // 这样改正式名称只需改根 branding.json，桌面端窗口标题 / 引导页 / jar 名随之统一。
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let brand_path = manifest_dir.join("..").join("branding.json");
    println!("cargo:rerun-if-changed={}", brand_path.display());

    let brand: serde_json::Value = std::fs::read_to_string(&brand_path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    let read = |key: &str, fallback: &str| {
        brand
            .get(key)
            .and_then(|v| v.as_str())
            .unwrap_or(fallback)
            .to_string()
    };
    println!("cargo:rustc-env=BRAND_NAME={}", read("displayName", "minis"));
    println!("cargo:rustc-env=BRAND_SLUG={}", read("slug", "minis"));
    println!("cargo:rustc-env=BRAND_TAGLINE={}", read("tagline", ""));

    tauri_build::build()
}
