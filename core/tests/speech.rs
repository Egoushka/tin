//! Check S5 from docs/specs/2026-11-01-speech.md: the speech tools against a fake @tin-speech server.

use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::sync::mpsc;
use tin_core::backend::Backend;
use tin_core::mcp::Server;
use tin_core::policy::Policy;
use tin_core::transport;

struct NoApps;

impl Backend for NoApps {
    fn list(&self) -> Result<Value, String> {
        Ok(json!([]))
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("no apps".into())
    }
}

/// A socket name that works as an abstract name on Linux and as a path on macOS.
fn socket_name(tag: &str) -> String {
    let dir = std::env::temp_dir();
    let path = dir.join(format!("tin-speech-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_file(&path);
    path.to_string_lossy().into_owned()
}

/// Answers one connection with `answer` and sends the request line it got back to the test.
fn fake_phone(name: &str, answer: &'static str) -> mpsc::Receiver<String> {
    let listener = transport::bind(name).unwrap();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        let mut line = String::new();
        BufReader::new(&s).read_line(&mut line).unwrap();
        tx.send(line.trim().to_string()).unwrap();
        writeln!(s, "{answer}").unwrap();
    });
    rx
}

fn server(policy: &str, socket: &str) -> Server {
    Server::new(Box::new(NoApps), Policy::from_toml(policy).unwrap()).with_speech_socket(socket)
}

fn rpc(s: &Server, method: &str, params: Value) -> Value {
    s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": method, "params": params })).unwrap()["result"].clone()
}

fn names(s: &Server) -> Vec<String> {
    rpc(s, "tools/list", json!({}))["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap().to_string()).collect()
}

const ON: &str = "[speech]\nenabled = true\n";

#[test]
fn speech_tools_hidden_by_default() {
    for policy in ["", "[speech]\n", "[speech]\nenabled = false\n"] {
        let s = server(policy, &socket_name("off"));
        assert!(names(&s).is_empty(), "{policy:?}");
        let r = rpc(&s, "tools/call", json!({ "name": "speech__transcribe", "arguments": {} }));
        assert_eq!(r["isError"], true);
    }
}

#[test]
fn speech_policy_rejects_unknown_keys() {
    assert!(Policy::from_toml("[speech]\nenable = true\n").is_err());
}

#[test]
fn speech_tools_listed_when_enabled() {
    assert_eq!(names(&server(ON, &socket_name("on"))), ["speech__transcribe", "speech__push_to_talk"]);
}

#[test]
fn speech_transcribe_reaches_the_phone() {
    let name = socket_name("call");
    let got = fake_phone(&name, r#"{"ok":true,"text":"Привіт, світ","audio_s":1.5,"decode_ms":120}"#);
    let r = rpc(&server(ON, &name), "tools/call", json!({ "name": "speech__transcribe", "arguments": { "seconds": 5 } }));
    assert_eq!(serde_json::from_str::<Value>(&got.recv().unwrap()).unwrap(), json!({ "op": "transcribe", "seconds": 5.0 }));
    assert_eq!(r["isError"], false);
    let body: Value = serde_json::from_str(r["content"][0]["text"].as_str().unwrap()).unwrap();
    assert_eq!(body, json!({ "text": "Привіт, світ", "audio_s": 1.5, "decode_ms": 120 }));
}

#[test]
fn speech_push_to_talk_and_phone_errors() {
    let name = socket_name("ptt");
    let got = fake_phone(&name, r#"{"ok":false,"error":"mic busy"}"#);
    let r = rpc(&server(ON, &name), "tools/call", json!({ "name": "speech__push_to_talk", "arguments": { "since_id": 2 } }));
    assert_eq!(got.recv().unwrap(), r#"{"op":"transcripts","since_id":2}"#);
    assert_eq!(r, json!({ "content": [{ "type": "text", "text": "mic busy" }], "isError": true }));
}

#[test]
fn speech_missing_socket_is_a_tool_error() {
    let r = rpc(&server(ON, &socket_name("none")), "tools/call", json!({ "name": "speech__transcribe", "arguments": {} }));
    assert_eq!(r["isError"], true);
    assert!(r["content"][0]["text"].as_str().unwrap().contains("open tin on the phone"));
}

#[test]
fn speech_file_with_a_path_never_reaches_the_phone() {
    // No fake server: if the request were sent, the error would be "unreachable" instead.
    let r = rpc(&server(ON, &socket_name("path")), "tools/call", json!({ "name": "speech__transcribe", "arguments": { "file": "../../data/x.wav" } }));
    assert_eq!(r["content"][0]["text"], "file must be a bare .wav name in the bench folder");
}
