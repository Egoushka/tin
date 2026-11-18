//! Helpers the backend test files share: a fake approvals inbox, the gate wiring, call and decision helpers.
#![allow(dead_code)]

use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tin_core::backend::Backend;
use tin_core::gate::{self, Approver, Gate, GateConfig};
use tin_core::mcp::Server;
use tin_core::policy::Policy;

pub const KEY: &[u8] = b"test-approval-key";

/// A fake approvals server: records every item put and every id withdrawn.
#[derive(Clone, Default)]
pub struct Inbox {
    pub items: Arc<Mutex<Vec<Value>>>,
    pub withdrawn: Arc<Mutex<Vec<String>>>,
}

impl Approver for Inbox {
    fn put(&self, _: &str, item: &Value) -> Result<(), String> {
        self.items.lock().unwrap().push(item.clone());
        Ok(())
    }
    fn delete(&self, id: &str) -> Result<(), String> {
        self.withdrawn.lock().unwrap().push(id.to_string());
        Ok(())
    }
}

impl Inbox {
    /// Waits for the n-th item to arrive (the call that opens it runs on another thread).
    pub fn nth(&self, n: usize) -> Value {
        let start = Instant::now();
        loop {
            if let Some(i) = self.items.lock().unwrap().get(n) {
                return i.clone();
            }
            assert!(start.elapsed() < Duration::from_secs(5), "no item {n} arrived");
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

/// A server over `backend` and `policy` whose gate posts to a fresh `Inbox`.
pub fn gated_server(backend: Box<dyn Backend>, policy: &str, wait: Duration, ttl: Duration) -> (Arc<Server>, Inbox) {
    let inbox = Inbox::default();
    let cfg = GateConfig { source: "tin".into(), callback: "https://approvals.example/cb/tin/decision".into(), wait, ttl };
    let gate = Arc::new(Gate::new(Box::new(inbox.clone()), KEY, cfg));
    let server = Server::new(backend, Policy::from_toml(policy).unwrap()).with_gate(gate);
    (Arc::new(server), inbox)
}

pub fn call(s: &Server, name: &str, args: Value) -> Value {
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": name, "arguments": args } })).unwrap();
    r["result"].clone()
}

pub fn text(result: &Value) -> &str {
    result["content"][0]["text"].as_str().unwrap()
}

/// The server's timestamp format for the current moment, so a decision is never older than a ticket's ttl.
pub fn now_stamp() -> String {
    let now = std::time::SystemTime::now();
    let ms = now.duration_since(std::time::UNIX_EPOCH).unwrap().subsec_millis();
    format!("{}.{ms:03}Z", &gate::rfc3339(now)[..19])
}

/// A correctly signed decision body for item `id`, stamped `at`.
pub fn signed_at(id: &str, action: &str, at: &str) -> Vec<u8> {
    let decision = "01JB0000000000000000000009";
    let sig = gate::sign(KEY, decision, id, action, at, &json!({}));
    json!({ "decision": decision, "id": id, "action": action, "edited": {}, "at": at, "sig": sig }).to_string().into_bytes()
}

/// A held call on another thread, the item it opened, and the caller to join after a decision.
pub fn held(s: &Arc<Server>, inbox: &Inbox, tool: &'static str, args: Value) -> (String, std::thread::JoinHandle<Value>) {
    let s2 = s.clone();
    let caller = std::thread::spawn(move || call(&s2, tool, args));
    (inbox.nth(0)["id"].as_str().unwrap().to_string(), caller)
}
