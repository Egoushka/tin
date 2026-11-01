//! The MCP server: JSON-RPC 2.0 messages in, responses out, transport-agnostic.

use crate::backend::Backend;
use crate::metadata::{self, Function};
use crate::policy::{Decision, Policy};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::RwLock;
use std::time::Instant;

pub const PROTOCOL_VERSION: &str = "2025-06-18";

/// A function the policy lets the client see, under its MCP tool name.
#[derive(Debug, Clone)]
pub struct Tool {
    pub name: String,
    pub function: Function,
    pub decision: Decision,
}

pub struct Server {
    backend: Box<dyn Backend>,
    policy: Policy,
    tools: RwLock<HashMap<String, Tool>>,
}

/// `<last two package segments>__<method>`, limited to `[A-Za-z0-9_-]` and 64 characters (what MCP clients accept).
pub fn base_name(f: &Function) -> String {
    let segs: Vec<&str> = f.package.split('.').collect();
    let pkg = segs[segs.len().saturating_sub(2)..].join("_");
    let raw = format!("{pkg}__{}", f.method());
    let clean: String = raw.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
    clean.chars().take(64).collect()
}

/// Names every function, unique across the set: a clash gets `_2`, `_3`, ... within the 64-character limit.
pub fn name_all(functions: Vec<Function>) -> Vec<(String, Function)> {
    let mut seen: HashMap<String, usize> = HashMap::new();
    functions
        .into_iter()
        .map(|f| {
            let base = base_name(&f);
            let n = seen.entry(base.clone()).or_insert(0);
            *n += 1;
            let name = if *n == 1 {
                base
            } else {
                let suffix = format!("_{n}");
                format!("{}{suffix}", &base[..base.len().min(64 - suffix.len())])
            };
            (name, f)
        })
        .collect()
}

impl Server {
    pub fn new(backend: Box<dyn Backend>, policy: Policy) -> Self {
        Self { backend, policy, tools: RwLock::new(HashMap::new()) }
    }

    /// Re-reads the function list. Apps add and drop functions on update, so `tools/list` always refreshes.
    pub fn refresh(&self) -> Result<usize, String> {
        let listing = self.backend.list()?;
        let tools: HashMap<String, Tool> = name_all(metadata::parse(&listing))
            .into_iter()
            .filter_map(|(name, function)| {
                let decision = self.policy.decide(&function);
                (decision != Decision::Deny).then(|| (name.clone(), Tool { name, function, decision }))
            })
            .collect();
        let n = tools.len();
        *self.tools.write().unwrap() = tools;
        Ok(n)
    }

    pub fn tools(&self) -> Vec<Tool> {
        let mut t: Vec<Tool> = self.tools.read().unwrap().values().cloned().collect();
        t.sort_by(|a, b| a.name.cmp(&b.name));
        t
    }

    /// Handles one JSON-RPC message. `None` for notifications, which get no response.
    pub fn handle(&self, msg: &Value) -> Option<Value> {
        let id = msg.get("id").cloned();
        let method = msg.get("method").and_then(Value::as_str).unwrap_or("");
        let params = msg.get("params").cloned().unwrap_or(json!({}));
        let id = id?; // no id: a notification (initialized, cancelled), nothing to answer
        let result = match method {
            "initialize" => Ok(json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": { "tools": { "listChanged": false } },
                "serverInfo": { "name": "tin", "version": env!("CARGO_PKG_VERSION") },
                "instructions": "Android AppFunctions on this phone, as tools. Only functions the phone's policy allows are listed; functions that move money are refused."
            })),
            "ping" => Ok(json!({})),
            "tools/list" => self.list_tools(),
            "tools/call" => Ok(self.call_tool(&params)),
            _ => Err((-32601, format!("method not found: {method}"))),
        };
        Some(match result {
            Ok(r) => json!({ "jsonrpc": "2.0", "id": id, "result": r }),
            Err((code, message)) => json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message } }),
        })
    }

    fn list_tools(&self) -> Result<Value, (i64, String)> {
        self.refresh().map_err(|e| (-32603, e))?;
        let tools: Vec<Value> = self
            .tools()
            .into_iter()
            .map(|t| {
                let note = if t.decision == Decision::Approval { "[needs approval; never run automatically] " } else { "" };
                json!({
                    "name": t.name,
                    "title": format!("{} ({})", t.function.method(), t.function.package),
                    "description": format!("{note}{} (app: {}, function: {})", t.function.description, t.function.package, t.function.id),
                    "inputSchema": t.function.input_schema,
                })
            })
            .collect();
        Ok(json!({ "tools": tools }))
    }

    fn call_tool(&self, params: &Value) -> Value {
        let name = params.get("name").and_then(Value::as_str).unwrap_or("");
        let args = params.get("arguments").cloned().unwrap_or(json!({}));
        if self.tools.read().unwrap().is_empty() {
            // A client may call without listing first (after a restart, say).
            if let Err(e) = self.refresh() {
                return tool_error(&e);
            }
        }
        let Some(tool) = self.tools.read().unwrap().get(name).cloned() else {
            return tool_error(&format!("unknown or not allowed tool: {name}"));
        };
        if tool.decision != Decision::Allow {
            eprintln!("tin: refused {name}: needs approval");
            return tool_error(&format!(
                "approval required: {} moves money or is marked for approval, and tin never runs such functions itself. Open it on the phone instead.",
                tool.function.id
            ));
        }
        let start = Instant::now();
        let outcome = self.backend.execute(&tool.function.package, &tool.function.id, &args.to_string());
        eprintln!("tin: called {name} ok={} {} ms", outcome.is_ok(), start.elapsed().as_millis());
        match outcome {
            Ok(text) => json!({ "content": [{ "type": "text", "text": text }], "isError": false }),
            Err(e) => tool_error(&e),
        }
    }
}

fn tool_error(text: &str) -> Value {
    json!({ "content": [{ "type": "text", "text": text }], "isError": true })
}
