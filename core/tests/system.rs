//! Acceptance checks S1–S8 from docs/specs/2026-11-01-phone-mcp-system-tools.md, run off the phone: a fake backend
//! answers shell programs from synthetic dumps, and a fake approvals server records items.

use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tin_core::backend::Backend;
use tin_core::gate::{self, Approver, Gate, GateConfig, Outcome};
use tin_core::mcp::{Server, WAIT_TOOL};
use tin_core::policy::{Decision, Policy, SYSTEM, Subject};
use tin_core::system::{self, VERBS};

const KEY: &[u8] = b"test-approval-key";

fn synthetic(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/fixtures/synthetic/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

/// Answers `dumpsys <service>`, `uiautomator`, `cat` and `settings get` from fixtures; records every program it runs.
#[derive(Clone)]
struct Phone {
    calls: Arc<Mutex<Vec<Vec<String>>>>,
    activities: &'static str,
    ui: &'static str,
}

impl Default for Phone {
    fn default() -> Self {
        Self { calls: Default::default(), activities: "activities.txt", ui: "ui.xml" }
    }
}

impl Phone {
    fn ran(&self, program: &str) -> bool {
        self.calls.lock().unwrap().iter().any(|c| c[0] == program)
    }
}

impl Backend for Phone {
    fn list(&self) -> Result<Value, String> {
        Err("no AppFunctions on this fake".into())
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("no AppFunctions on this fake".into())
    }
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let mut call = vec![program.to_string()];
        call.extend(args.iter().cloned());
        self.calls.lock().unwrap().push(call);
        let first = args.first().map(String::as_str).unwrap_or("");
        Ok(match (program, first) {
            ("dumpsys", "notification") => synthetic("notification.txt"),
            ("dumpsys", "usagestats") => synthetic("usagestats.txt"),
            ("dumpsys", "activity") => synthetic(self.activities),
            ("dumpsys", "battery") => synthetic("battery.txt"),
            ("dumpsys", "connectivity") => synthetic("connectivity.txt"),
            ("settings", "get") => "0\n".into(),
            ("uiautomator", _) => format!("UI hierchary dumped to: {}\n", system::UI_DUMP),
            ("cat", _) => synthetic(self.ui),
            _ => String::new(),
        })
    }
}

#[derive(Clone, Default)]
struct Inbox {
    items: Arc<Mutex<Vec<Value>>>,
    withdrawn: Arc<Mutex<Vec<String>>>,
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
    fn nth(&self, n: usize) -> Value {
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

const ALL_SYSTEM: &str = "[[allow]]\npackage = \"system\"\nfunction = \"*\"\n";

fn gated(wait: Duration, ttl: Duration) -> (Arc<Server>, Phone, Inbox) {
    let phone = Phone::default();
    let inbox = Inbox::default();
    let cfg = GateConfig { source: "tin".into(), callback: "https://approvals.example/cb/tin/decision".into(), wait, ttl };
    let gate = Arc::new(Gate::new(Box::new(inbox.clone()), KEY, cfg));
    let server = Server::new(Box::new(phone.clone()), Policy::from_toml(ALL_SYSTEM).unwrap()).with_gate(gate);
    (Arc::new(server), phone, inbox)
}

fn ungated(policy: &str, phone: Phone) -> Server {
    Server::new(Box::new(phone), Policy::from_toml(policy).unwrap())
}

fn call(s: &Server, name: &str, args: Value) -> Value {
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": name, "arguments": args } })).unwrap();
    r["result"].clone()
}

fn text(result: &Value) -> &str {
    result["content"][0]["text"].as_str().unwrap()
}

fn signed(id: &str, action: &str) -> Vec<u8> {
    let (decision, at) = ("01JB0000000000000000000009", "2026-11-01T12:00:00.000Z");
    let sig = gate::sign(KEY, decision, id, action, at, &json!({}));
    json!({ "decision": decision, "id": id, "action": action, "edited": {}, "at": at, "sig": sig }).to_string().into_bytes()
}

// S1: the registry.

#[test]
fn registry_has_six_read_and_six_write_verbs() {
    assert_eq!(VERBS.len(), 12);
    let read: Vec<&str> = VERBS.iter().filter(|v| v.read_only).map(|v| v.name).collect();
    assert_eq!(read, ["notifications", "usage", "foreground_app", "screen_text", "battery", "connectivity"]);
    let write: Vec<&str> = VERBS.iter().filter(|v| !v.read_only).map(|v| v.name).collect();
    assert_eq!(write, ["tap", "swipe", "type_text", "key", "launch", "settings_put"]);
}

#[test]
fn registry_lists_system_tools_with_notes_and_wait_tool() {
    let (s, _, _) = gated(Duration::from_millis(10), Duration::from_secs(60));
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" })).unwrap();
    let tools = r["result"]["tools"].as_array().unwrap();
    assert_eq!(tools.len(), 13, "12 verbs and approval__wait");
    for v in VERBS {
        let t = tools.iter().find(|t| t["name"] == v.tool_name()).unwrap();
        let held = t["description"].as_str().unwrap().contains("approvals app");
        assert_eq!(held, !v.read_only, "{}", v.name);
        assert_eq!(t["inputSchema"]["type"], "object");
    }
    assert!(tools.iter().any(|t| t["name"] == WAIT_TOOL));
}

#[test]
fn registry_serves_system_tools_when_appfunctions_fail() {
    let s = ungated("[[allow]]\npackage = \"system\"\nfunction = \"battery\"\n", Phone::default());
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" })).unwrap();
    assert_eq!(r["result"]["tools"].as_array().unwrap().len(), 1);
    // No gate configured and nothing held: no wait tool either.
    assert_eq!(r["result"]["tools"][0]["name"], "system__battery");
}

#[test]
fn registry_empty_policy_lists_nothing_and_errors_like_before() {
    let s = ungated("", Phone::default());
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" })).unwrap();
    assert!(r["error"]["message"].as_str().unwrap().contains("no AppFunctions"));
}

// S2: parsers.

#[test]
fn parse_notifications() {
    let n = system::parse_notifications(&synthetic("notification.txt"));
    assert_eq!(n.len(), 3);
    assert_eq!(n[0]["package"], "com.whatsapp");
    assert_eq!(n[0]["key"], "0|com.whatsapp|1|null|10234");
    assert_eq!(n[0]["channel"], "individual_chat_defaults_3");
    assert_eq!(n[0]["category"], "msg");
    assert_eq!(n[0]["title"], "Олена");
    assert_eq!(n[0]["text"], "Привіт! Ти вдома?");
    assert_eq!(n[0]["posted_ms"], 1761988800000u64);
    assert_eq!(n[0]["ongoing"], false);
    assert_eq!(n[1]["ongoing"], true, "flags 0x62 has FLAG_ONGOING_EVENT");
    assert_eq!(n[2]["text"], Value::Null, "a redacted value stays null");
}

#[test]
fn parse_usage_daily_block_sorted() {
    let u = system::parse_usage(&synthetic("usagestats.txt"));
    let apps = u["apps"].as_array().unwrap();
    assert_eq!(apps.len(), 3, "weekly block ignored");
    assert_eq!(apps[0]["package"], "com.whatsapp");
    assert_eq!(apps[0]["foreground_ms"], 3_723_000);
    assert_eq!(apps[0]["launches"], 14);
    assert_eq!(apps[1]["foreground_ms"], 754_000);
    assert_eq!(u["range"], "2026-11-01, 00:00:00 - 2026-11-01, 12:34:56");
}

#[test]
fn parse_foreground() {
    let f = system::parse_foreground(&synthetic("activities.txt")).unwrap();
    assert_eq!(f, json!({ "package": "com.whatsapp", "activity": "com.whatsapp.HomeActivity" }));
    let m = system::parse_foreground(&synthetic("activities-mono.txt")).unwrap();
    assert_eq!(m["activity"], "com.ftband.app.MainActivity");
    assert!(system::parse_foreground("nothing here").is_none());
}

#[test]
fn parse_ui_leaves_out_password_text() {
    let s = system::parse_ui(&synthetic("ui.xml")).unwrap();
    assert_eq!(s["package"], "com.whatsapp");
    let nodes = s["nodes"].as_array().unwrap();
    assert_eq!(nodes.len(), 2);
    assert_eq!(nodes[0]["text"], "Олена");
    assert_eq!(nodes[0]["bounds"], json!([198, 412, 620, 470]));
    assert_eq!(nodes[1]["description"], "New chat");
    assert_eq!(nodes[1]["clickable"], true);
    assert!(!s.to_string().contains("hunter2"));
    assert!(system::parse_ui("<not xml").is_err());
}

#[test]
fn parse_battery() {
    let b = system::parse_battery(&synthetic("battery.txt"));
    assert_eq!(
        b,
        json!({ "level_percent": 76, "status": "charging", "plugged": "usb", "temperature_c": 28.7, "health": "good", "voltage_mv": 4123 })
    );
}

#[test]
fn parse_connectivity() {
    let c = system::parse_connectivity(&synthetic("connectivity.txt"));
    let nets = c["networks"].as_array().unwrap();
    assert_eq!(nets.len(), 2, "the Network Requests copy is not a second network");
    assert_eq!(c["default"]["id"], 105);
    assert_eq!(c["default"]["transports"], json!(["WIFI"]));
    assert_eq!(c["default"]["validated"], true);
    assert_eq!(c["default"]["metered"], false);
    assert_eq!(nets[1]["transports"], json!(["CELLULAR"]));
    assert_eq!(nets[1]["metered"], true);
}

#[test]
fn parse_read_tools_end_to_end() {
    let phone = Phone::default();
    let s = ungated(ALL_SYSTEM, phone.clone());
    let n: Value = serde_json::from_str(text(&call(&s, "system__notifications", json!({ "package": "com.spotify.music" })))).unwrap();
    assert_eq!(n["notifications"].as_array().unwrap().len(), 1);
    let c: Value = serde_json::from_str(text(&call(&s, "system__connectivity", json!({})))).unwrap();
    assert_eq!(c["airplane_mode"], false);
    let u: Value = serde_json::from_str(text(&call(&s, "system__usage", json!({ "limit": 1 })))).unwrap();
    assert_eq!(u["apps"].as_array().unwrap().len(), 1);
}

// S3: policy.

fn decide(policy: &str, package: &str, id: &str, read_only: Option<bool>) -> Decision {
    Policy::from_toml(policy).unwrap().decide_subject(&Subject { package, id, read_only })
}

#[test]
fn policy_decisions() {
    let p = r#"
[[allow]]
package = "com.android.settings"
function = "getBatteryDeviceState"
read_only = true

[[allow]]
package = "com.urbandroid.sleep"
function = "*"

[[approval]]
package = "com.google.android.calendar"
function = "*"

[[allow]]
package = "com.ftband.mono"
function = "*"
read_only = true

[[allow]]
package = "system"
function = "battery"

[[allow]]
package = "system"
function = "tap"

[[approval]]
package = "system"
function = "screen_text"
"#;
    assert_eq!(decide(p, "com.android.settings", "getBatteryDeviceState", None), Decision::Allow);
    assert_eq!(decide(p, "com.android.settings", "setWifi", None), Decision::Deny);
    assert_eq!(decide(p, "com.urbandroid.sleep", "createAlarm", None), Decision::Approval, "not marked read-only");
    assert_eq!(decide(p, "com.google.android.calendar", "listEvents", None), Decision::Approval);
    assert_eq!(decide(p, "com.ftband.mono", "getBalance", None), Decision::Refuse, "read_only cannot lift the money rule");
    assert_eq!(decide(p, "com.urbandroid.sleep", "buyPremium", None), Decision::Refuse);
    assert_eq!(decide(p, SYSTEM, "battery", Some(true)), Decision::Allow);
    assert_eq!(decide(p, SYSTEM, "tap", Some(false)), Decision::Approval, "a write verb in allow still waits");
    assert_eq!(decide(p, SYSTEM, "screen_text", Some(true)), Decision::Approval, "a read verb under approval waits");
    assert_eq!(decide(p, SYSTEM, "usage", Some(true)), Decision::Deny);
}

#[test]
fn policy_rejects_read_only_on_system_verbs() {
    let e = Policy::from_toml("[[allow]]\npackage = \"system\"\nfunction = \"tap\"\nread_only = true\n").unwrap_err();
    assert!(e.contains("read_only on system verb tap"), "{e}");
}

#[test]
fn policy_approvals_table_defaults() {
    let p = Policy::from_toml(
        "[approvals]\nurl = \"http://100.64.0.2:8080\"\ntoken_file = \"/t\"\nkey_file = \"/k\"\ncallback = \"https://a.example/cb/tin/\"\n",
    )
    .unwrap();
    let a = p.approvals.unwrap();
    assert_eq!((a.source.as_str(), a.wait_secs, a.ttl_secs, a.callback_listen), ("tin", 50, 900, None));
}

// S4: gating.

#[test]
fn gate_no_server_refuses_writes() {
    let phone = Phone::default();
    let s = ungated(ALL_SYSTEM, phone.clone());
    let r = call(&s, "system__key", json!({ "key": "KEYCODE_HOME" }));
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("approval required"));
    assert!(phone.calls.lock().unwrap().is_empty());
}

#[test]
fn gate_item_shape() {
    let (s, _, inbox) = gated(Duration::from_millis(10), Duration::from_secs(60));
    let r = call(&s, "system__tap", json!({ "x": 540, "y": 1200 }));
    assert_eq!(r["structuredContent"]["status"], "pending");
    let item = inbox.nth(0);
    let id = item["id"].as_str().unwrap();
    assert_eq!(r["structuredContent"]["ticket"], id);
    assert!(id.starts_with("tin:") && id.len() > 20, "{id}");
    assert_eq!(item["kind"], "decision");
    assert_eq!(item["priority"], 5);
    assert_eq!(item["title"], "tin: tap at (540, 1200)");
    assert_eq!(item["summary"], "input tap 540 1200");
    assert_eq!(item["editable"], json!([]));
    assert_eq!(item["actions"][0]["id"], "run");
    assert_eq!(item["actions"][1]["id"], "deny");
    assert_eq!(item["callback"], "https://approvals.example/cb/tin/decision");
    let exp = item["expires_at"].as_str().unwrap();
    assert!(exp.len() == 20 && exp.ends_with('Z') && exp.starts_with("20"), "{exp}");
}

#[test]
fn gate_runs_once_after_signed_run() {
    let (s, phone, inbox) = gated(Duration::from_secs(5), Duration::from_secs(60));
    let s2 = s.clone();
    let caller = std::thread::spawn(move || call(&s2, "system__key", json!({ "key": "KEYCODE_HOME" })));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    assert!(!phone.ran("input"), "nothing runs before the decision");
    assert_eq!(s.gate().unwrap().decide(&signed(&id, "run")).0, 200);
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], false, "{r}");
    let calls = phone.calls.lock().unwrap().clone();
    assert_eq!(calls, [vec!["input", "keyevent", "KEYCODE_HOME"]]);
    // A second wait gets the stored result, not a second run; a second decision is a conflict.
    let again = call(&s, WAIT_TOOL, json!({ "ticket": id }));
    assert_eq!(again, r);
    assert_eq!(s.gate().unwrap().decide(&signed(&id, "run")).0, 409);
    assert_eq!(phone.calls.lock().unwrap().len(), 1);
}

#[test]
fn gate_pending_then_wait_tool() {
    let (s, phone, inbox) = gated(Duration::from_millis(20), Duration::from_secs(60));
    let r = call(&s, "system__launch", json!({ "package": "com.android.chrome" }));
    assert_eq!(r["isError"], false);
    assert!(text(&r).contains(WAIT_TOOL));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    assert_eq!(call(&s, WAIT_TOOL, json!({ "ticket": id }))["structuredContent"]["status"], "pending");
    s.gate().unwrap().decide(&signed(&id, "run"));
    let r = call(&s, WAIT_TOOL, json!({ "ticket": id }));
    assert_eq!(r["isError"], false, "{r}");
    assert!(phone.ran("monkey"));
}

#[test]
fn gate_deny_never_runs() {
    let (s, phone, inbox) = gated(Duration::from_secs(5), Duration::from_secs(60));
    let s2 = s.clone();
    let caller = std::thread::spawn(move || call(&s2, "system__type_text", json!({ "text": "hello world" })));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    assert_eq!(s.gate().unwrap().decide(&signed(&id, "deny")).0, 200);
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("denied"));
    assert!(phone.calls.lock().unwrap().is_empty());
}

#[test]
fn gate_bad_decisions() {
    let (s, phone, inbox) = gated(Duration::from_millis(10), Duration::from_secs(60));
    call(&s, "system__key", json!({ "key": "KEYCODE_BACK" }));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    let g = s.gate().unwrap();
    let mut forged: Value = serde_json::from_slice(&signed(&id, "deny")).unwrap();
    forged["action"] = json!("run");
    assert_eq!(g.decide(forged.to_string().as_bytes()).0, 401, "a signature for deny does not carry run");
    assert_eq!(g.decide(&signed("tin:0-unknown", "run")).0, 404);
    assert_eq!(g.decide(b"{}").0, 400);
    assert_eq!(g.decide(b"nope").0, 400);
    assert!(phone.calls.lock().unwrap().is_empty());
}

#[test]
fn gate_bad_arguments_never_ask() {
    let (s, _, inbox) = gated(Duration::from_millis(10), Duration::from_secs(60));
    let r = call(&s, "system__tap", json!({ "x": -1, "y": 5 }));
    assert_eq!(r["isError"], true);
    assert!(inbox.items.lock().unwrap().is_empty());
}

#[test]
fn gate_expiry_withdraws_and_never_runs() {
    let (s, phone, inbox) = gated(Duration::from_secs(5), Duration::from_millis(100));
    let r = call(&s, "system__key", json!({ "key": "KEYCODE_HOME" }));
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("expired"), "{r}");
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    assert_eq!(*inbox.withdrawn.lock().unwrap(), std::slice::from_ref(&id));
    assert_eq!(s.gate().unwrap().decide(&signed(&id, "run")).0, 409, "a late run is not taken");
    assert!(phone.calls.lock().unwrap().is_empty());
}

#[test]
fn gate_caps_undecided_tickets() {
    let (s, _, inbox) = gated(Duration::from_millis(1), Duration::from_secs(60));
    for x in 0..gate::MAX_PENDING as i64 {
        assert_eq!(call(&s, "system__tap", tap(x, 0))["isError"], false);
    }
    let r = call(&s, "system__tap", tap(99, 0));
    assert_eq!(r["isError"], true);
    assert_eq!(inbox.items.lock().unwrap().len(), gate::MAX_PENDING);
}

// I1–I7: a repeat of a pending call returns its ticket (docs/specs/2026-11-02-idempotent-holds.md).

fn tap(x: i64, y: i64) -> Value {
    json!({ "x": x, "y": y })
}

fn is_repeat(r: &Value) -> bool {
    r["structuredContent"]["note"] == "already pending"
}

fn direct_gate(ttl: Duration) -> (Gate, Inbox) {
    let inbox = Inbox::default();
    let cfg = GateConfig { source: "tin".into(), callback: "https://x/cb/decision".into(), wait: Duration::from_millis(5), ttl };
    (Gate::new(Box::new(inbox.clone()), KEY, cfg), inbox)
}

#[test]
fn gate_repeat_returns_the_pending_ticket() {
    let (s, _, inbox) = gated(Duration::from_millis(1), Duration::from_secs(60));
    let first = call(&s, "system__tap", tap(10, 20));
    assert!(!is_repeat(&first));
    let second = call(&s, "system__tap", tap(10, 20));
    assert!(is_repeat(&second), "{second}");
    assert!(text(&second).starts_with("Already pending"), "{second}");
    assert_eq!(second["isError"], false);
    assert_eq!(second["structuredContent"]["ticket"], first["structuredContent"]["ticket"]);
    let reordered = call(&s, "system__tap", json!({ "y": 20, "x": 10 }));
    assert!(is_repeat(&reordered), "key order does not make a different call");
    assert_eq!(inbox.items.lock().unwrap().len(), 1);
}

#[test]
fn gate_repeat_loop_opens_one_item() {
    let (s, phone, inbox) = gated(Duration::from_millis(1), Duration::from_secs(60));
    let answers: Vec<Value> = (0..12).map(|_| call(&s, "system__tap", tap(10, 20))).collect();
    assert_eq!(inbox.items.lock().unwrap().len(), 1);
    assert_eq!(answers.iter().filter(|r| is_repeat(r)).count(), 11);
    assert!(answers.iter().all(|r| r["isError"] == false), "a loop above MAX_PENDING is not refused");
    assert!(phone.calls.lock().unwrap().is_empty());
}

#[test]
fn gate_repeat_different_call_opens_its_own() {
    let (s, _, inbox) = gated(Duration::from_millis(1), Duration::from_secs(60));
    let a = call(&s, "system__tap", tap(10, 20));
    let b = call(&s, "system__tap", tap(10, 21));
    assert!(!is_repeat(&b));
    assert_ne!(a["structuredContent"]["ticket"], b["structuredContent"]["ticket"]);
    let c = call(&s, "system__swipe", json!({ "x1": 10, "y1": 20, "x2": 10, "y2": 20 }));
    assert!(!is_repeat(&c), "{c}");
    assert_eq!(inbox.items.lock().unwrap().len(), 3);
}

#[test]
fn gate_repeat_after_ttl_opens_a_new_ticket() {
    let (s, _, inbox) = gated(Duration::from_millis(1), Duration::from_millis(100));
    let first = call(&s, "system__tap", tap(10, 20));
    std::thread::sleep(Duration::from_millis(150));
    let again = call(&s, "system__tap", tap(10, 20));
    assert!(!is_repeat(&again), "{again}");
    assert_ne!(first["structuredContent"]["ticket"], again["structuredContent"]["ticket"]);
    assert_eq!(inbox.items.lock().unwrap().len(), 2);
    assert_eq!(inbox.withdrawn.lock().unwrap().len(), 1, "the first item was withdrawn when it expired");
}

#[test]
fn gate_repeat_cap_counts_distinct_tickets() {
    let (s, _, inbox) = gated(Duration::from_millis(1), Duration::from_secs(60));
    for x in 0..gate::MAX_PENDING as i64 {
        assert_eq!(call(&s, "system__tap", tap(x, 0))["isError"], false);
    }
    let repeat = call(&s, "system__tap", tap(3, 0));
    assert!(is_repeat(&repeat), "{repeat}");
    assert_eq!(call(&s, "system__tap", tap(99, 0))["isError"], true);
    assert_eq!(inbox.items.lock().unwrap().len(), gate::MAX_PENDING);
}

#[test]
fn gate_repeat_decided_ticket_is_not_reused() {
    let (g, inbox) = direct_gate(Duration::from_secs(60));
    let args = tap(10, 20);
    let denied = g.hold("system__tap", &args, "t", "s").unwrap();
    assert_eq!(g.decide(&signed(&denied.ticket, "deny")).0, 200);
    let after_deny = g.hold("system__tap", &args, "t", "s").unwrap();
    assert!(!after_deny.existing);
    assert_ne!(after_deny.ticket, denied.ticket);

    assert_eq!(g.decide(&signed(&after_deny.ticket, "run")).0, 200);
    assert!(matches!(g.wait(&after_deny.ticket), Outcome::Run { .. }));
    g.finish(&after_deny.ticket, json!({}));
    let after_run = g.hold("system__tap", &args, "t", "s").unwrap();
    assert!(!after_run.existing);
    assert_ne!(after_run.ticket, after_deny.ticket);
    assert_eq!(inbox.items.lock().unwrap().len(), 3);
}

#[test]
fn gate_repeat_race_opens_one_ticket() {
    let (g, inbox) = direct_gate(Duration::from_secs(60));
    let args = tap(10, 20);
    let held: Vec<_> = std::thread::scope(|sc| {
        let threads: Vec<_> = (0..8).map(|_| sc.spawn(|| g.hold("system__tap", &args, "t", "s").unwrap())).collect();
        threads.into_iter().map(|t| t.join().unwrap()).collect()
    });
    assert_eq!(inbox.items.lock().unwrap().len(), 1);
    assert!(held.iter().all(|h| h.ticket == held[0].ticket));
    assert_eq!(held.iter().filter(|h| !h.existing).count(), 1);
}

#[test]
fn gate_wait_outcomes_direct() {
    let inbox = Inbox::default();
    let cfg = GateConfig { source: "tin".into(), callback: "https://x/cb/decision".into(), wait: Duration::from_millis(5), ttl: Duration::from_secs(60) };
    let g = Gate::new(Box::new(inbox), KEY, cfg);
    assert_eq!(g.wait("tin:nope"), Outcome::Unknown);
    let t = g.hold("system__key", &json!({ "key": "KEYCODE_HOME" }), "t", "s").unwrap().ticket;
    assert_eq!(g.wait(&t), Outcome::Pending);
    g.decide(&signed(&t, "run"));
    assert_eq!(g.wait(&t), Outcome::Run { tool: "system__key".into(), args: json!({ "key": "KEYCODE_HOME" }) });
    assert_eq!(g.wait(&t), Outcome::Running, "claimed once");
}

// S5: signature compatibility with the approvals server.

#[test]
fn gate_vectors_match_approvals_server() {
    let vectors: Vec<Value> = serde_json::from_str(include_str!("fixtures/approvals-decision-vectors.json")).unwrap();
    assert_eq!(vectors.len(), 3);
    for v in &vectors {
        let s = |k: &str| v[k].as_str().unwrap();
        assert_eq!(gate::edited_hash(&v["edited"]), s("h"), "{}", s("id"));
        assert_eq!(gate::sign(KEY, s("decision"), s("id"), s("action"), s("at"), &v["edited"]), s("sig"));
        assert!(gate::verify(KEY, s("decision"), s("id"), s("action"), s("at"), &v["edited"], s("sig")));
        assert!(!gate::verify(b"other-key", s("decision"), s("id"), s("action"), s("at"), &v["edited"], s("sig")));
        assert!(!gate::verify(KEY, s("decision"), s("id"), "run", s("at"), &v["edited"], s("sig")));
    }
}

#[test]
fn gate_rfc3339() {
    let t = std::time::UNIX_EPOCH + Duration::from_secs(1_761_998_400);
    assert_eq!(gate::rfc3339(t), "2025-11-01T12:00:00Z");
    assert_eq!(gate::rfc3339(std::time::UNIX_EPOCH + Duration::from_secs(951_782_400)), "2000-02-29T00:00:00Z");
}

// S6: no screen text from banking apps.

#[test]
fn screen_text_normal() {
    let phone = Phone::default();
    let s = ungated(ALL_SYSTEM, phone.clone());
    let r = call(&s, "system__screen_text", json!({}));
    assert_eq!(r["isError"], false, "{r}");
    assert!(text(&r).contains("Олена"));
    let calls = phone.calls.lock().unwrap().clone();
    assert!(calls.contains(&vec!["uiautomator".into(), "dump".into(), system::UI_DUMP.into()]));
    assert!(calls.contains(&vec!["rm".into(), "-f".into(), system::UI_DUMP.into()]), "the dump is removed");
}

#[test]
fn screen_text_refused_with_bank_in_front() {
    let phone = Phone { activities: "activities-mono.txt", ..Phone::default() };
    let s = ungated(ALL_SYSTEM, phone.clone());
    let r = call(&s, "system__screen_text", json!({}));
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("banking app"));
    assert!(!phone.ran("uiautomator"), "nothing is dumped");
}

#[test]
fn screen_text_refused_with_bank_overlay() {
    let phone = Phone { ui: "ui-mono-overlay.xml", ..Phone::default() };
    let s = ungated(ALL_SYSTEM, phone);
    let r = call(&s, "system__screen_text", json!({}));
    assert_eq!(r["isError"], true);
    assert!(!text(&r).contains("12 345"));
}

// S7: argv.

fn argv(verb: &str, args: Value) -> Result<Vec<String>, String> {
    system::verb(verb).unwrap().argv(&args)
}

#[test]
fn argv_exact() {
    let cases: &[(&str, Value, &[&str])] = &[
        ("tap", json!({ "x": 540, "y": 1200 }), &["input", "tap", "540", "1200"]),
        ("swipe", json!({ "x1": 1, "y1": 2, "x2": 3, "y2": 4 }), &["input", "swipe", "1", "2", "3", "4", "300"]),
        ("swipe", json!({ "x1": 1, "y1": 2, "x2": 3, "y2": 4, "ms": 50 }), &["input", "swipe", "1", "2", "3", "4", "50"]),
        ("type_text", json!({ "text": "hi there; rm -rf /" }), &["input", "text", "hi%sthere;%srm%s-rf%s/"]),
        ("key", json!({ "key": "KEYCODE_HOME" }), &["input", "keyevent", "KEYCODE_HOME"]),
        ("launch", json!({ "package": "com.android.chrome" }), &["monkey", "-p", "com.android.chrome", "-c", "android.intent.category.LAUNCHER", "1"]),
        ("settings_put", json!({ "namespace": "system", "key": "screen_brightness", "value": "128" }), &["settings", "put", "system", "screen_brightness", "128"]),
    ];
    for (verb, args, want) in cases {
        assert_eq!(argv(verb, args.clone()).unwrap(), *want, "{verb}");
    }
}

#[test]
fn argv_rejects_bad_arguments() {
    let bad: &[(&str, Value)] = &[
        ("tap", json!({ "x": 10001, "y": 0 })),
        ("tap", json!({ "x": "1", "y": 0 })),
        ("swipe", json!({ "x1": 1, "y1": 2, "x2": 3 })),
        ("type_text", json!({ "text": "line\nbreak" })),
        ("type_text", json!({ "text": "100%s" })),
        ("type_text", json!({ "text": "x".repeat(1001) })),
        ("key", json!({ "key": "HOME" })),
        ("key", json!({ "key": "KEYCODE_home" })),
        ("launch", json!({ "package": "com" })),
        ("launch", json!({ "package": "-p com.x" })),
        ("settings_put", json!({ "namespace": "vendor", "key": "a", "value": "b" })),
        ("settings_put", json!({ "namespace": "global", "key": "a b", "value": "c" })),
    ];
    for (verb, args) in bad {
        assert!(argv(verb, args.clone()).is_err(), "{verb} {args}");
    }
    assert!(argv("battery", json!({})).is_err(), "read verbs have no argv");
}

// S8: the decision route over HTTP.

fn http(stream: &mut (impl std::io::Read + std::io::Write), method: &str, path: &str, body: &[u8]) -> (u16, String) {
    write!(stream, "{method} {path} HTTP/1.1\r\nHost: tin\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len()).unwrap();
    stream.write_all(body).unwrap();
    let mut out = String::new();
    stream.read_to_string(&mut out).unwrap();
    let code = out.split_whitespace().nth(1).unwrap().parse().unwrap();
    (code, out.split_once("\r\n\r\n").map(|(_, b)| b.to_string()).unwrap_or_default())
}

#[test]
fn http_decision_routes() {
    let (s, phone, inbox) = gated(Duration::from_secs(5), Duration::from_secs(60));
    let path = std::env::temp_dir().join(format!("tin-gate-{}.sock", std::process::id()));
    let name = path.to_str().unwrap().to_string();
    let listener = tin_core::transport::bind(&name).unwrap();
    let tcp = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = tcp.local_addr().unwrap().port();
    let (a, b) = (s.clone(), s.clone());
    std::thread::spawn(move || tin_core::transport::serve_http(a, listener));
    std::thread::spawn(move || tin_core::transport::serve_decisions(b, tcp));

    let s2 = s.clone();
    let caller = std::thread::spawn(move || call(&s2, "system__key", json!({ "key": "KEYCODE_HOME" })));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    let tcp = || std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();

    // The TCP listener serves nothing but the decision route.
    assert_eq!(http(&mut tcp(), "POST", "/mcp", b"{}").0, 404);
    assert_eq!(http(&mut tcp(), "GET", "/health", b"").0, 404);
    let mut bad: Value = serde_json::from_slice(&signed(&id, "run")).unwrap();
    bad["sig"] = json!("00");
    assert_eq!(http(&mut tcp(), "POST", "/approvals/decision", bad.to_string().as_bytes()).0, 401);
    assert_eq!(http(&mut tcp(), "POST", "/approvals/decision", &signed("tin:0-x", "run")).0, 404);
    let (code, body) = http(&mut tcp(), "POST", "/approvals/decision", &signed(&id, "run"));
    assert_eq!(code, 200, "{body}");
    assert_eq!(serde_json::from_str::<Value>(&body).unwrap()["ok"], true);
    assert_eq!(caller.join().unwrap()["isError"], false);
    // The same route on the unix socket answers too; a repeat is a conflict the server counts as delivered.
    let mut unix = tin_core::transport::connect(&name).unwrap();
    assert_eq!(http(&mut unix, "POST", "/approvals/decision", &signed(&id, "run")).0, 409);
    assert_eq!(phone.calls.lock().unwrap().len(), 1);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn http_decision_without_gate_is_404() {
    let s = Arc::new(ungated(ALL_SYSTEM, Phone::default()));
    let path = std::env::temp_dir().join(format!("tin-nogate-{}.sock", std::process::id()));
    let name = path.to_str().unwrap().to_string();
    let listener = tin_core::transport::bind(&name).unwrap();
    std::thread::spawn(move || tin_core::transport::serve_http(s, listener));
    let mut c = tin_core::transport::connect(&name).unwrap();
    assert_eq!(http(&mut c, "POST", "/approvals/decision", &signed("tin:1-a", "run")).0, 404);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn http_approver_parses_url() {
    assert!(gate::HttpApprover::new("https://approvals.example", "t").is_err(), "no TLS in tin");
    assert!(gate::HttpApprover::new("http://100.64.0.2:8080/api/", "t").is_ok());
}

#[test]
fn policy_system_example_loads() {
    let p = Policy::from_toml(include_str!("../../examples/system-policy.toml")).unwrap();
    let s = Server::new(Box::new(Phone::default()), p);
    let tools: Vec<(String, Decision)> = s.system_tools().into_iter().map(|t| (t.name, t.decision)).collect();
    assert_eq!(tools.len(), 7);
    assert_eq!(tools.iter().filter(|(_, d)| *d == Decision::Approval).map(|(n, _)| n.as_str()).collect::<Vec<_>>(), ["system__key"]);
}
