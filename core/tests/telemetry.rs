//! Acceptance checks T1–T10 from docs/specs/2026-11-15-otel-tool-spans.md.

mod common;

use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tin_core::backend::Backend;
use tin_core::mcp::Server;
use tin_core::policy::Policy;
use tin_core::telemetry::{self, OtlpSink, Span, SpanSink};

#[derive(Clone, Default)]
struct Rec(Arc<Mutex<Vec<Span>>>);

impl SpanSink for Rec {
    fn emit(&self, span: Span) {
        self.0.lock().unwrap().push(span);
    }
}

impl Rec {
    fn spans(&self) -> Vec<Span> {
        self.0.lock().unwrap().clone()
    }
}

struct Fake;

impl Backend for Fake {
    fn list(&self) -> Result<Value, String> {
        Err("no AppFunctions".into())
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("secret-error-text".into())
    }
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let first = args.first().map(String::as_str).unwrap_or("");
        Ok(match (program, first) {
            ("dumpsys", "battery") => "  level: 80\n  scale: 100\n  status: 3\n".into(),
            _ => String::new(),
        })
    }
}

const POLICY: &str = "[[allow]]\npackage = \"system\"\nfunction = \"battery\"\n[[allow]]\npackage = \"system\"\nfunction = \"tap\"\n";

fn server(rec: &Rec) -> Server {
    Server::new(Box::new(Fake), Policy::from_toml(POLICY).unwrap()).with_telemetry(Arc::new(rec.clone()))
}

fn call_meta(s: &Server, name: &str, args: Value, meta: Value) -> Value {
    let msg = json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": name, "arguments": args, "_meta": meta } });
    s.handle(&msg).unwrap()["result"].clone()
}

fn attr<'a>(s: &'a Span, k: &str) -> Option<&'a str> {
    s.attrs.iter().find(|(key, _)| key == k).map(|(_, v)| v.as_str())
}

const TRACE: &str = "4bf92f3577b34da6a3ce929d0e0e4736";
const PARENT: &str = "00f067aa0ba902b7";

#[test]
fn t1_allowed_read_call_is_one_span() {
    let rec = Rec::default();
    let s = server(&rec);
    call_meta(&s, "system__battery", json!({}), json!({}));
    let spans = rec.spans();
    assert_eq!(spans.len(), 1);
    let sp = &spans[0];
    assert_eq!(sp.name, "tin.tool_call");
    assert_eq!(attr(sp, "tin.tool"), Some("system__battery"));
    assert_eq!(attr(sp, "tin.policy.decision"), Some("allow"));
    assert_eq!(attr(sp, "tin.outcome"), Some("ok"));
    assert!(!sp.error);
    assert!(sp.parent_id.is_none());
    assert!(sp.end_ns >= sp.start_ns);
}

#[test]
fn t2_caller_context_joins_the_trace() {
    let rec = Rec::default();
    let s = server(&rec);
    call_meta(&s, "system__battery", json!({}), json!({ "traceparent": format!("00-{TRACE}-{PARENT}-01"), "run_id": "run-42" }));
    let sp = &rec.spans()[0];
    assert_eq!(sp.trace_id, TRACE);
    assert_eq!(sp.parent_id.as_deref(), Some(PARENT));
    assert_eq!(attr(sp, "jarvis.run_id"), Some("run-42"));
}

#[test]
fn t3_invalid_traceparent_starts_a_new_trace() {
    let rec = Rec::default();
    let s = server(&rec);
    for bad in ["garbage", "00-00000000000000000000000000000000-00f067aa0ba902b7-01", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"] {
        call_meta(&s, "system__battery", json!({}), json!({ "traceparent": bad }));
    }
    for sp in rec.spans() {
        assert_eq!(sp.trace_id.len(), 32);
        assert_ne!(sp.trace_id, TRACE);
        assert!(sp.parent_id.is_none());
    }
}

#[test]
fn t4_held_call_has_an_approval_wait_child() {
    let rec = Rec::default();
    let inbox = common::Inbox::default();
    let cfg = tin_core::gate::GateConfig { source: "tin".into(), callback: "https://a.example/cb/tin/decision".into(), wait: Duration::from_secs(5), ttl: Duration::from_secs(60) };
    let gate = Arc::new(tin_core::gate::Gate::new(Box::new(inbox.clone()), common::KEY, cfg));
    let s = Arc::new(Server::new(Box::new(Fake), Policy::from_toml(POLICY).unwrap()).with_gate(gate).with_telemetry(Arc::new(rec.clone())));
    let s2 = s.clone();
    let caller = std::thread::spawn(move || call_meta(&s2, "system__tap", json!({ "x": 1, "y": 2 }), json!({ "run_id": "r1" })));
    let id = inbox.nth(0)["id"].as_str().unwrap().to_string();
    let body = common::signed_at(&id, "run", &common::now_stamp());
    s.gate().unwrap().decide(&body);
    caller.join().unwrap();
    let spans = rec.spans();
    assert_eq!(spans.len(), 2, "{spans:?}");
    let wait = spans.iter().find(|x| x.name == "tin.approval.wait").unwrap();
    let call = spans.iter().find(|x| x.name == "tin.tool_call").unwrap();
    assert_eq!(wait.parent_id.as_deref(), Some(call.span_id.as_str()));
    assert_eq!(wait.trace_id, call.trace_id);
    assert_eq!(attr(call, "tin.policy.decision"), Some("approval"));
    assert_eq!(attr(call, "tin.outcome"), Some("ok"));
    assert!(attr(call, "tin.ticket").is_some());
    assert_eq!(attr(call, "tin.ticket"), attr(wait, "tin.ticket"));
}

#[test]
fn t5_money_refusal_is_an_error_span() {
    let rec = Rec::default();
    let s = Server::new(Box::new(Fake), Policy::from_toml("[[allow]]\npackage = \"system\"\nfunction = \"battery\"\n").unwrap()).with_telemetry(Arc::new(rec.clone()));
    call_meta(&s, "system__nothing", json!({}), json!({}));
    let sp = &rec.spans()[0];
    assert_eq!(attr(sp, "tin.policy.decision"), Some("unknown"));
    assert!(sp.error);
    assert_eq!(attr(sp, "tin.outcome"), Some("error"));
}

#[test]
fn t6_spans_carry_no_arguments_or_error_text() {
    let rec = Rec::default();
    let s = server(&rec);
    call_meta(&s, "system__battery", json!({ "secret_arg": "hunter2-argument" }), json!({}));
    call_meta(&s, "system__nothing", json!({ "secret_arg": "hunter2-argument" }), json!({}));
    let all = telemetry::otlp_body("tin", &rec.spans()[0]).to_string() + &telemetry::otlp_body("tin", &rec.spans()[1]).to_string();
    assert!(!all.contains("hunter2"));
    assert!(!all.contains("secret-error-text"));
    assert!(!all.contains("not allowed"));
}

#[test]
fn t7_otlp_body_shape() {
    let rec = Rec::default();
    let s = server(&rec);
    call_meta(&s, "system__battery", json!({}), json!({ "traceparent": format!("00-{TRACE}-{PARENT}-01") }));
    let body = telemetry::otlp_body("tin-test", &rec.spans()[0]);
    let res = &body["resourceSpans"][0];
    assert_eq!(res["resource"]["attributes"][0]["key"], "service.name");
    assert_eq!(res["resource"]["attributes"][0]["value"]["stringValue"], "tin-test");
    let sp = &res["scopeSpans"][0]["spans"][0];
    assert_eq!(sp["traceId"].as_str().unwrap().len(), 32);
    assert_eq!(sp["spanId"].as_str().unwrap().len(), 16);
    assert_eq!(sp["parentSpanId"], PARENT);
    assert!(sp["startTimeUnixNano"].is_string() && sp["endTimeUnixNano"].is_string());
    assert_eq!(sp["status"]["code"], 1);
}

#[test]
fn t8_policy_and_sink_url() {
    let p = Policy::from_toml("[telemetry]\notlp_url = \"http://100.64.0.4:4318/v1/traces\"\nservice_name = \"tin-phone\"\n").unwrap();
    let t = p.telemetry.unwrap();
    assert_eq!(t.otlp_url, "http://100.64.0.4:4318/v1/traces");
    assert_eq!(t.service_name, "tin-phone");
    assert!(OtlpSink::new("https://x.example/v1/traces", "tin").is_err());
    assert!(OtlpSink::new("nonsense", "tin").is_err());
    assert!(OtlpSink::new("http://127.0.0.1:4318/v1/traces", "tin").is_ok());
}

#[test]
fn t9_http_headers_reach_the_span() {
    let rec = Rec::default();
    let s = Arc::new(server(&rec));
    let (a, b) = std::os::unix::net::UnixStream::pair().unwrap();
    let body = json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": "system__battery", "arguments": {} } }).to_string();
    let req = format!("POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\ntraceparent: 00-{TRACE}-{PARENT}-01\r\nx-run-id: run-http\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
    use std::io::Write;
    (&a).write_all(req.as_bytes()).unwrap();
    a.shutdown(std::net::Shutdown::Write).unwrap();
    tin_core::transport::serve_connection(&s, b.try_clone().unwrap(), b).unwrap();
    let sp = &rec.spans()[0];
    assert_eq!(sp.trace_id, TRACE);
    assert_eq!(attr(sp, "jarvis.run_id"), Some("run-http"));
}

#[test]
fn t10_dead_collector_never_delays_a_call() {
    let port = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    let sink = OtlpSink::new(&format!("http://127.0.0.1:{port}/v1/traces"), "tin").unwrap();
    let s = Server::new(Box::new(Fake), Policy::from_toml(POLICY).unwrap()).with_telemetry(Arc::new(sink));
    let start = Instant::now();
    for _ in 0..20 {
        let r = call_meta(&s, "system__battery", json!({}), json!({}));
        assert_eq!(r["isError"], false);
    }
    assert!(start.elapsed() < Duration::from_secs(1));
}
