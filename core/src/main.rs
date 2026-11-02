use std::sync::Arc;
use std::time::Duration;
use tin_core::backend::{Backend, CmdBackend};
use tin_core::gate::{Gate, GateConfig, HttpApprover};
use tin_core::mcp::{self, Server};
use tin_core::policy::{Decision, Policy};
use tin_core::macos::MacBackend;
use tin_core::transport;

const USAGE: &str = "usage:
  tin serve [--stdio] [--socket NAME] [--policy FILE]   serve MCP (default: HTTP on abstract socket @tin)
  tin list [--all] [--policy FILE]                      show tool names and what the policy decides (allow, approval,
                                                        refuse, deny)
  tin probe [--socket NAME]                             GET /health over the socket, print the status
  tin shortcut BUNDLE_ID INTENT [--out DIR]             macOS: write the signed shortcut that runs one App Intent;
                                                        open it once to add it to Shortcuts

every command takes --platform android|macos (default: the one tin was built for).
defaults: --socket tin, --policy /data/local/tmp/tin/policy.toml on Android, ~/.config/tin/policy.toml on macOS
(missing file = deny everything)";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |name: &str| args.iter().any(|a| a == name);
    let value = |name: &str, default: &str| -> String {
        args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned().unwrap_or_else(|| default.to_string())
    };
    let socket = value("--socket", "tin");
    let platform = value("--platform", if cfg!(target_os = "macos") { "macos" } else { "android" });
    let default_policy = match platform.as_str() {
        "macos" => format!("{}/.config/tin/policy.toml", std::env::var("HOME").unwrap_or_default()),
        _ => "/data/local/tmp/tin/policy.toml".to_string(),
    };
    let policy_path = value("--policy", &default_policy);
    let backend = || -> Result<Box<dyn Backend>, String> {
        match platform.as_str() {
            "android" => Ok(Box::new(CmdBackend { timeout_secs: 30 })),
            "macos" => Ok(Box::new(MacBackend::default())),
            other => Err(format!("--platform {other}: android or macos")),
        }
    };
    let result = match args.first().map(String::as_str) {
        Some("serve") => load_policy(&policy_path).and_then(|policy| {
            let approvals = policy.approvals.clone();
            let mut server = Server::new(backend()?, policy);
            if let Some(a) = &approvals {
                server = server.with_gate(Arc::new(gate(a)?));
            }
            let server = Arc::new(server);
            if let Some(addr) = approvals.as_ref().and_then(|a| a.callback_listen.clone()) {
                let listener = std::net::TcpListener::bind(&addr).map_err(|e| format!("bind {addr}: {e}"))?;
                eprintln!("tin: approvals callback on {addr}");
                let s = server.clone();
                std::thread::spawn(move || transport::serve_decisions(s, listener));
            }
            if flag("--stdio") {
                transport::serve_stdio(&server).map_err(|e| e.to_string())
            } else {
                let listener = transport::bind(&socket).map_err(|e| format!("bind @{socket}: {e}"))?;
                eprintln!("tin {}: serving MCP on @{socket}, policy {policy_path}", env!("CARGO_PKG_VERSION"));
                transport::serve_http(server, listener).map_err(|e| e.to_string())
            }
        }),
        Some("list") => load_policy(&policy_path).and_then(|policy| list(&*backend()?, &policy, flag("--all"))),
        Some("shortcut") => match (args.get(1), args.get(2)) {
            (Some(bundle), Some(intent)) if !bundle.starts_with("--") && !intent.starts_with("--") => {
                MacBackend::default().write_shortcut(bundle, intent, std::path::Path::new(&value("--out", "."))).map(|path| {
                    println!("{}", path.display());
                    eprintln!("tin: open it once and tap Add Shortcut: open {:?}", path.display().to_string());
                })
            }
            _ => Err(USAGE.to_string()),
        },
        Some("probe") => transport::probe(&socket).map(|code| println!("{code}")),
        _ => Err(USAGE.to_string()),
    };
    if let Err(e) = result {
        eprintln!("tin: {e}");
        std::process::exit(1);
    }
}

fn load_policy(path: &str) -> Result<Policy, String> {
    match std::fs::read_to_string(path) {
        Ok(text) => Policy::from_toml(&text),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            eprintln!("tin: no policy at {path}; every function is denied");
            Ok(Policy::default())
        }
        Err(e) => Err(format!("{path}: {e}")),
    }
}

/// The approvals gate from the policy's `[approvals]` table; the token and key are read from their files.
fn gate(a: &tin_core::policy::Approvals) -> Result<Gate, String> {
    let read = |path: &str| std::fs::read_to_string(path).map(|s| s.trim().to_string()).map_err(|e| format!("{path}: {e}"));
    let approver = HttpApprover::new(&a.url, &read(&a.token_file)?)?;
    let cfg = GateConfig {
        source: a.source.clone(),
        callback: format!("{}decision", a.callback),
        wait: Duration::from_secs(a.wait_secs),
        ttl: Duration::from_secs(a.ttl_secs),
    };
    Ok(Gate::new(Box::new(approver), read(&a.key_file)?.as_bytes(), cfg))
}

fn list(backend: &dyn Backend, policy: &Policy, all: bool) -> Result<(), String> {
    use tin_core::policy::{SYSTEM, Subject};
    for v in backend.verbs() {
        let d = policy.decide_subject(&Subject { package: SYSTEM, id: v.name, read_only: Some(v.read_only) });
        if all || d != Decision::Deny {
            println!("{:<9} {:<50} {SYSTEM} {}", format!("{d:?}").to_lowercase(), v.tool_name(), v.name);
        }
    }
    for (name, f) in mcp::name_all(backend.functions()?) {
        let d = policy.decide(&f);
        if all || d != Decision::Deny {
            println!("{:<9} {name:<50} {} {}", format!("{d:?}").to_lowercase(), f.package, f.id);
        }
    }
    Ok(())
}
