//! Acceptance checks A1–A3 from docs/specs/2026-11-01-appfunctions-mcp-bridge.md, run on the Mac with a fake backend
//! fed by the listing captured from a Pixel 8a.

use serde_json::{Value, json};
use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use tin_core::backend::Backend;
use tin_core::mcp::{self, Server};
use tin_core::metadata;
use tin_core::policy::{self, Policy};

fn fixture() -> Value {
    serde_json::from_str(include_str!("fixtures/list-2026-11-01.json")).unwrap()
}

#[derive(Clone, Default)]
struct Recording {
    calls: Arc<Mutex<Vec<(String, String, String)>>>,
}

impl Backend for Recording {
    fn list(&self) -> Result<Value, String> {
        Ok(fixture())
    }
    fn execute(&self, package: &str, function: &str, parameters: &str) -> Result<String, String> {
        self.calls.lock().unwrap().push((package.into(), function.into(), parameters.into()));
        Ok("{\"ok\":true}".into())
    }
}

/// The policy the device acceptance run (A6) uses too; the monobank entry is there to prove an allow cannot lift the money rule.
const DEMO: &str = include_str!("../../examples/demo-policy.toml");

fn server(policy: &str) -> (Server, Recording) {
    let backend = Recording::default();
    (Server::new(Box::new(backend.clone()), Policy::from_toml(policy).unwrap()), backend)
}

fn rpc(s: &Server, id: i64, method: &str, params: Value) -> Value {
    s.handle(&json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params })).unwrap()
}

#[test]
fn fixture_has_61_functions_in_11_packages() {
    let fns = metadata::parse(&fixture());
    assert_eq!(fns.len(), 61);
    assert_eq!(fns.iter().map(|f| &f.package).collect::<HashSet<_>>().len(), 11);
}

#[test]
fn fixture_create_alarm_schema() {
    let fns = metadata::parse(&fixture());
    let f = fns.iter().find(|f| f.id.ends_with("#createAlarm")).unwrap();
    let s = &f.input_schema;
    assert_eq!(s["properties"]["hour"]["type"], "integer");
    assert_eq!(s["properties"]["minute"]["type"], "integer");
    assert_eq!(s["required"], json!(["hour", "minute"]));
}

#[test]
fn fixture_every_reference_resolves() {
    // An unresolved reference falls back to an object schema with no `properties`; resolved ones carry them.
    fn bare_objects(v: &Value, out: &mut usize) {
        match v {
            Value::Object(m) => {
                if m.get("type") == Some(&json!("object")) && !m.contains_key("properties") {
                    *out += 1;
                }
                m.values().for_each(|x| bare_objects(x, out));
            }
            Value::Array(a) => a.iter().for_each(|x| bare_objects(x, out)),
            _ => {}
        }
    }
    for f in metadata::parse(&fixture()) {
        let mut n = 0;
        bare_objects(&f.input_schema, &mut n);
        assert_eq!(n, 0, "{}: {}", f.id, f.input_schema);
    }
}

#[test]
fn fixture_tool_names_are_unique_and_valid() {
    let named = mcp::name_all(metadata::parse(&fixture()));
    let names: HashSet<_> = named.iter().map(|(n, _)| n.clone()).collect();
    assert_eq!(names.len(), named.len());
    for n in &names {
        assert!(n.len() <= 64 && n.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-'), "{n}");
    }
    assert!(names.contains("urbandroid_sleep__createAlarm"));
}

#[test]
fn policy_empty_lists_nothing() {
    let (s, _) = server("");
    let r = rpc(&s, 1, "tools/list", json!({}));
    // The doctor is the one tool a policy cannot hide: it is how a client learns the policy is empty.
    let names: Vec<&str> = r["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(names, [mcp::DOCTOR_TOOL]);
}

#[test]
fn policy_money_never_reaches_backend() {
    let (s, backend) = server(DEMO);
    let r = rpc(&s, 1, "tools/list", json!({}));
    let tools = r["result"]["tools"].as_array().unwrap();
    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(names, ["android_settings__getBatteryDeviceState", "ftband_mono__topUpPhone", "urbandroid_sleep__createAlarm", "tin__doctor"]);
    let mono = tools.iter().find(|t| t["name"] == "ftband_mono__topUpPhone").unwrap();
    assert!(mono["description"].as_str().unwrap().contains("never runs"));
    let alarm = tools.iter().find(|t| t["name"] == "urbandroid_sleep__createAlarm").unwrap();
    assert!(alarm["description"].as_str().unwrap().contains("approvals app"));

    let r = rpc(&s, 2, "tools/call", json!({ "name": "ftband_mono__topUpPhone", "arguments": { "phoneNumber": "+380501234567", "amount": 100.0 } }));
    assert_eq!(r["result"]["isError"], true);
    assert!(r["result"]["content"][0]["text"].as_str().unwrap().contains("approval"));
    assert!(backend.calls.lock().unwrap().is_empty());
}

#[test]
fn policy_money_rule_covers_every_mono_function() {
    for f in metadata::parse(&fixture()).iter().filter(|f| f.package == "com.ftband.mono") {
        assert!(policy::is_money(f), "{}", f.id);
    }
}

#[test]
fn mcp_round_trip_over_handle() {
    let (s, backend) = server(DEMO);
    let init = rpc(&s, 1, "initialize", json!({ "protocolVersion": mcp::PROTOCOL_VERSION, "capabilities": {}, "clientInfo": { "name": "t", "version": "0" } }));
    assert_eq!(init["result"]["protocolVersion"], mcp::PROTOCOL_VERSION);
    assert!(s.handle(&json!({ "jsonrpc": "2.0", "method": "notifications/initialized" })).is_none());
    rpc(&s, 2, "tools/list", json!({}));
    let r = rpc(&s, 3, "tools/call", json!({ "name": "android_settings__getBatteryDeviceState", "arguments": { "verbose": true } }));
    assert_eq!(r["result"]["isError"], false);
    let calls = backend.calls.lock().unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].1, "getBatteryDeviceState");
    let params: Value = serde_json::from_str(&calls[0].2).unwrap();
    assert_eq!(params, json!({ "verbose": true }));
}

#[test]
fn policy_state_change_without_approvals_server_is_refused() {
    let (s, backend) = server(DEMO);
    let r = rpc(&s, 1, "tools/call", json!({ "name": "urbandroid_sleep__createAlarm", "arguments": { "hour": 7, "minute": 30 } }));
    assert_eq!(r["result"]["isError"], true);
    assert!(r["result"]["content"][0]["text"].as_str().unwrap().contains("approval required"));
    assert!(backend.calls.lock().unwrap().is_empty());
}

#[test]
fn mcp_unknown_tool_is_tool_error() {
    let (s, backend) = server(DEMO);
    let r = rpc(&s, 1, "tools/call", json!({ "name": "nope", "arguments": {} }));
    assert_eq!(r["result"]["isError"], true);
    // A denied function is as unknown as a missing one.
    let r = rpc(&s, 2, "tools/call", json!({ "name": "android_settings__getStorageDeviceState", "arguments": {} }));
    assert_eq!(r["result"]["isError"], true);
    assert!(backend.calls.lock().unwrap().is_empty());
}

#[test]
fn mcp_unknown_method_is_jsonrpc_error() {
    let (s, _) = server("");
    assert_eq!(rpc(&s, 1, "resources/list", json!({}))["error"]["code"], -32601);
}

#[test]
fn mcp_http_over_unix_socket() {
    use std::io::{Read, Write};
    let (s, _) = server(DEMO);
    let path = std::env::temp_dir().join(format!("tin-test-{}.sock", std::process::id()));
    let name = path.to_str().unwrap().to_string();
    let listener = tin_core::transport::bind(&name).unwrap();
    let s = Arc::new(s);
    std::thread::spawn(move || tin_core::transport::serve_http(s, listener));
    assert_eq!(tin_core::transport::probe(&name).unwrap(), 200);

    // Two requests on one keep-alive connection, the way HTTP clients send them.
    let mut c = tin_core::transport::connect(&name).unwrap();
    for (i, method) in ["ping", "tools/list"].iter().enumerate() {
        let body = json!({ "jsonrpc": "2.0", "id": i, "method": method }).to_string();
        write!(c, "POST /mcp HTTP/1.1\r\nHost: tin\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
        let mut head = Vec::new();
        let mut byte = [0u8; 1];
        while !head.ends_with(b"\r\n\r\n") {
            c.read_exact(&mut byte).unwrap();
            head.push(byte[0]);
        }
        let head = String::from_utf8(head).unwrap();
        assert!(head.starts_with("HTTP/1.1 200"), "{head}");
        let len: usize = head.lines().find_map(|l| l.strip_prefix("Content-Length: ")).unwrap().trim().parse().unwrap();
        let mut body = vec![0; len];
        c.read_exact(&mut body).unwrap();
        let v: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(v["id"], i);
    }
    let _ = std::fs::remove_file(&path);
}

struct Leaky;

impl Backend for Leaky {
    fn list(&self) -> Result<Value, String> {
        Ok(fixture())
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("cmd failed: notification text \"Your code is 482913\" token=abc123secret".into())
    }
}

#[test]
fn error_output_has_secrets_redacted() {
    let s = Server::new(Box::new(Leaky), Policy::from_toml(DEMO).unwrap());
    let r = rpc(&s, 1, "tools/call", json!({ "name": "android_settings__getBatteryDeviceState", "arguments": {} }));
    assert_eq!(r["result"]["isError"], true);
    let text = r["result"]["content"][0]["text"].as_str().unwrap();
    assert!(!text.contains("482913") && !text.contains("abc123secret"), "{text}");
    assert!(text.contains("cmd failed"), "{text}");
}

fn doctor(s: &Server) -> Value {
    let r = rpc(s, 1, "tools/call", json!({ "name": mcp::DOCTOR_TOOL, "arguments": {} }));
    assert_eq!(r["result"]["isError"], false);
    r["result"]["structuredContent"].clone()
}

fn problems(d: &Value) -> String {
    d["problems"].as_array().unwrap().iter().map(|p| p.as_str().unwrap()).collect::<Vec<_>>().join("\n")
}

#[test]
fn doctor_says_why_a_missing_policy_denies_everything() {
    let s = Server::new(Box::new(Recording::default()), Policy::default()).with_policy_source(mcp::PolicySource::Missing("/x/policy.toml".into()));
    let d = doctor(&s);
    assert_eq!(d["ok"], false);
    assert_eq!(d["policy"]["status"], "missing");
    assert!(problems(&d).contains("no policy file at /x/policy.toml"), "{d}");
}

#[test]
fn doctor_flags_rules_that_match_nothing_and_missing_approvals() {
    let toml = "[[approval]]\npackage = \"urbandroid.sleep\"\nfunction = \"*\"\n\n[[allow]]\npackage = \"com.android.settings\"\nfunction = \"*\"\nread_only = true\n";
    let s = Server::new(Box::new(Recording::default()), Policy::from_toml(toml).unwrap()).with_policy_source(mcp::PolicySource::File("p".into()));
    let d = doctor(&s);
    let p = problems(&d);
    assert!(p.contains("rule urbandroid.sleep * matches no function"), "{p}");
    assert!(!p.contains("com.android.settings"), "{p}");
    assert_eq!(d["policy"]["status"], "loaded");
    assert_eq!(d["backend"]["app_functions"], 61);
}

#[test]
fn doctor_reports_a_healthy_setup_and_never_shows_secrets() {
    let dir = std::env::temp_dir().join(format!("tin-doctor-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (token, key) = (dir.join("token"), dir.join("key"));
    std::fs::write(&token, "tok-SECRET-1").unwrap();
    std::fs::write(&key, "key-SECRET-2").unwrap();
    let toml = format!(
        "[[allow]]\npackage = \"com.android.settings\"\nfunction = \"*\"\nread_only = true\n\n[approvals]\nurl = \"http://approvals.invalid:1\"\ntoken_file = \"{}\"\nkey_file = \"{}\"\ncallback = \"/cb/\"\n",
        token.display(),
        key.display()
    );
    let s = Server::new(Box::new(Recording::default()), Policy::from_toml(&toml).unwrap()).with_policy_source(mcp::PolicySource::File("p".into()));
    let r = rpc(&s, 1, "tools/call", json!({ "name": mcp::DOCTOR_TOOL, "arguments": {} }));
    let text = r["result"]["content"][0]["text"].as_str().unwrap();
    assert!(!text.contains("SECRET") && !text.contains("approvals.invalid"), "{text}");
    let d = &r["result"]["structuredContent"];
    assert_eq!(d["approvals"]["token_file"], "readable");
    assert_eq!(d["approvals"]["key_file"], "readable");
    assert!(d["tools"]["allow"].as_u64().unwrap() > 0, "{d}");
    std::fs::remove_dir_all(&dir).unwrap();
}
