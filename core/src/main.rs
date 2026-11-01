use std::sync::Arc;
use tin_core::backend::CmdBackend;
use tin_core::mcp::{self, Server};
use tin_core::policy::{Decision, Policy};
use tin_core::{metadata, transport};

const USAGE: &str = "usage:
  tin serve [--stdio] [--socket NAME] [--policy FILE]   serve MCP (default: HTTP on abstract socket @tin)
  tin list [--all] [--policy FILE]                      show tool names and what the policy decides
  tin probe [--socket NAME]                             GET /health over the socket, print the status

defaults: --socket tin, --policy /data/local/tmp/tin/policy.toml (missing file = deny everything)";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let flag = |name: &str| args.iter().any(|a| a == name);
    let value = |name: &str, default: &str| -> String {
        args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned().unwrap_or_else(|| default.to_string())
    };
    let socket = value("--socket", "tin");
    let policy_path = value("--policy", "/data/local/tmp/tin/policy.toml");
    let result = match args.first().map(String::as_str) {
        Some("serve") => load_policy(&policy_path).and_then(|policy| {
            let server = Arc::new(Server::new(Box::new(CmdBackend { timeout_secs: 30 }), policy));
            if flag("--stdio") {
                transport::serve_stdio(&server).map_err(|e| e.to_string())
            } else {
                let listener = transport::bind(&socket).map_err(|e| format!("bind @{socket}: {e}"))?;
                eprintln!("tin {}: serving MCP on @{socket}, policy {policy_path}", env!("CARGO_PKG_VERSION"));
                transport::serve_http(server, listener).map_err(|e| e.to_string())
            }
        }),
        Some("list") => load_policy(&policy_path).and_then(|policy| list(&policy, flag("--all"))),
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

fn list(policy: &Policy, all: bool) -> Result<(), String> {
    use tin_core::backend::Backend;
    let listing = CmdBackend { timeout_secs: 30 }.list()?;
    for (name, f) in mcp::name_all(metadata::parse(&listing)) {
        let d = policy.decide(&f);
        if all || d != Decision::Deny {
            println!("{:<9} {name:<50} {} {}", format!("{d:?}").to_lowercase(), f.package, f.id);
        }
    }
    Ok(())
}
