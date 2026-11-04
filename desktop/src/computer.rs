//! Capture-only CLI used by the local Java sidecar. Never initialize Tauri, focus windows, or synthesize input.
use serde_json::{json, Value};

enum Request {
    List,
    Capture(u32, u32),
}

fn parse(args: &[String]) -> Result<Request, String> {
    match args {
        [action] if action == "list" => Ok(Request::List),
        [action, id, pid] if action == "capture" => {
            let id = id.parse::<u32>().map_err(|_| "无效窗口 ID")?;
            let pid = pid.parse::<u32>().map_err(|_| "无效进程 ID")?;
            if id == 0 || pid == 0 {
                return Err("窗口 ID 和进程 ID 必须来自窗口清单".into());
            }
            Ok(Request::Capture(id, pid))
        }
        _ => Err("仅支持 list 或 capture <window_id> <pid>".into()),
    }
}

fn execute(args: &[String]) -> Result<Value, String> {
    match parse(args)? {
        Request::List => {
            serde_json::to_value(crate::economy_capture::list()?).map_err(|e| e.to_string())
        }
        Request::Capture(id, pid) => {
            let frame = crate::economy_capture::capture(id, pid)?;
            serde_json::to_value(frame).map_err(|e| e.to_string())
        }
    }
}

pub fn run_cli() -> Option<i32> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(String::as_str) != Some("--computer-use") {
        return None;
    }
    let (response, code) = match execute(&args[1..]) {
        Ok(result) => (json!({"ok": true, "result": result}), 0),
        Err(error) => (json!({"ok": false, "error": error}), 1),
    };
    println!("{response}");
    Some(code)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_input_commands_and_invalid_window_identity() {
        for args in [
            vec!["click"],
            vec!["capture", "0", "1"],
            vec!["capture", "1", "-1"],
            vec!["capture", "4294967296", "1"],
            vec!["list", "extra"],
        ] {
            assert!(parse(&args.into_iter().map(String::from).collect::<Vec<_>>()).is_err());
        }
        assert!(matches!(parse(&["list".into()]), Ok(Request::List)));
        assert!(matches!(
            parse(&["capture".into(), "2".into(), "3".into()]),
            Ok(Request::Capture(2, 3))
        ));
    }
}
