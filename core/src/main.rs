use std::sync::Arc;
use std::time::Duration;
use tin_core::backend::CmdBackend;
use tin_core::gate::{Gate, GateConfig, HttpApprover};
use tin_core::mcp::{self, Server};
use tin_core::policy::{Decision, Policy};
use tin_core::{metadata, transport};

const USAGE: &str = "usage:
  tin serve [--stdio] [--socket NAME] [--policy FILE]   serve MCP (default: HTTP on abstract socket @tin)
  tin list [--all] [--policy FILE]                      show tool names and what the policy decides (allow, approval,
                                                        refuse, deny)
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
            let approvals = policy.approvals.clone();
            let mut server = Server::new(Box::new(CmdBackend { timeout_secs: 30 }), policy);
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

fn list(policy: &Policy, all: bool) -> Result<(), String> {
    use tin_core::backend::Backend;
    use tin_core::policy::{SYSTEM, Subject};
    for v in tin_core::system::VERBS {
        let d = policy.decide_subject(&Subject { package: SYSTEM, id: v.name, read_only: Some(v.read_only) });
        if all || d != Decision::Deny {
            println!("{:<9} {:<50} {SYSTEM} {}", format!("{d:?}").to_lowercase(), v.tool_name(), v.name);
        }
    }
    let listing = CmdBackend { timeout_secs: 30 }.list()?;
    for (name, f) in mcp::name_all(metadata::parse(&listing)) {
        let d = policy.decide(&f);
        if all || d != Decision::Deny {
            println!("{:<9} {name:<50} {} {}", format!("{d:?}").to_lowercase(), f.package, f.id);
        }
    }
    Ok(())
}
