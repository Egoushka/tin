//! Speech tools. tin runs as shell, which may not hold the mic, so the app's SpeechService records and decodes, and
//! the Shizuku service relays one JSON line each way over the abstract socket `@tin-speech` (wire format in
//! docs/specs/2026-11-01-speech.md). Off unless the policy says `[speech] enabled = true`.

use crate::transport;
use serde_json::{Map, Value, json};
use std::io::{BufRead, BufReader, Write};
use std::time::Duration;

pub const SOCKET: &str = "tin-speech";
pub const TRANSCRIBE: &str = "speech__transcribe";
pub const PUSH_TO_TALK: &str = "speech__push_to_talk";

pub struct Speech {
    socket: String,
    /// Recording (up to 60 s) plus decoding; a dead app must not hang the MCP call.
    timeout: Duration,
}

impl Speech {
    pub fn new(socket: &str) -> Self {
        Self { socket: socket.to_string(), timeout: Duration::from_secs(120) }
    }

    pub fn handles(name: &str) -> bool {
        name == TRANSCRIBE || name == PUSH_TO_TALK
    }

    pub fn tools() -> Vec<Value> {
        vec![
            json!({
                "name": TRANSCRIBE,
                "title": "Transcribe speech (on the phone)",
                "description": "Records from the phone's mic until the speaker stops (or `seconds` pass) and returns the text, recognised on the phone (Parakeet, Ukrainian and English). With `file`, decodes that WAV from the app's bench folder instead. Returns JSON: text, audio_s, decode_ms.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "seconds": { "type": "number", "minimum": 1, "maximum": 60, "default": 8, "description": "Longest recording, seconds." },
                        "file": { "type": "string", "description": "Bare .wav name under the app's files/bench/ (16 kHz mono)." }
                    },
                    "additionalProperties": false
                }
            }),
            json!({
                "name": PUSH_TO_TALK,
                "title": "Push-to-talk transcripts",
                "description": "What the owner said with the push-to-talk button (notification or Quick Settings tile), newest last. Pass the last id you saw as since_id to get only new ones.",
                "inputSchema": {
                    "type": "object",
                    "properties": { "since_id": { "type": "integer", "minimum": 0 } },
                    "additionalProperties": false
                }
            }),
        ]
    }

    /// Runs a tool. `Ok` is the JSON text for the client, `Err` a tool error.
    pub fn call(&self, name: &str, args: &Value) -> Result<String, String> {
        let request = request(name, args)?;
        let mut response = self.exchange(&request)?;
        let obj = response.as_object_mut().ok_or("speech: response is not an object")?;
        if obj.remove("ok") != Some(Value::Bool(true)) {
            return Err(obj.get("error").and_then(Value::as_str).unwrap_or("speech: failed without a message").to_string());
        }
        Ok(response.to_string())
    }

    fn exchange(&self, request: &Value) -> Result<Value, String> {
        let unreachable = |e: std::io::Error| format!("speech service unreachable on @{}: {e}; open tin on the phone", self.socket);
        let mut stream = transport::connect(&self.socket).map_err(unreachable)?;
        stream.set_read_timeout(Some(self.timeout)).map_err(|e| e.to_string())?;
        writeln!(stream, "{request}").map_err(unreachable)?;
        let mut line = String::new();
        BufReader::new(&stream).read_line(&mut line).map_err(|e| format!("speech: no answer: {e}"))?;
        if line.is_empty() {
            return Err("speech: connection closed without an answer".into());
        }
        serde_json::from_str(&line).map_err(|e| format!("speech: bad answer: {e}"))
    }
}

/// Checks the arguments here as well as on the phone, so a bad call never opens the mic.
fn request(name: &str, args: &Value) -> Result<Value, String> {
    let empty = Map::new();
    let args = match args {
        Value::Object(m) => m,
        Value::Null => &empty,
        _ => return Err("arguments must be an object".into()),
    };
    let allowed: &[&str] = if name == TRANSCRIBE { &["seconds", "file"] } else { &["since_id"] };
    if let Some(k) = args.keys().find(|k| !allowed.contains(&k.as_str())) {
        return Err(format!("unknown argument: {k}"));
    }
    if name == PUSH_TO_TALK {
        let mut r = json!({ "op": "transcripts" });
        if let Some(v) = args.get("since_id") {
            r["since_id"] = json!(v.as_u64().ok_or("since_id must be a non-negative integer")?);
        }
        return Ok(r);
    }
    let mut r = json!({ "op": "transcribe" });
    if let Some(v) = args.get("seconds") {
        let s = v.as_f64().filter(|s| (1.0..=60.0).contains(s)).ok_or("seconds must be 1 to 60")?;
        r["seconds"] = json!(s);
    }
    if let Some(v) = args.get("file") {
        let f = v.as_str().filter(|f| is_bare_wav(f)).ok_or("file must be a bare .wav name in the bench folder")?;
        r["file"] = json!(f);
    }
    Ok(r)
}

/// Same rule as the app's `SpeechServer.FILE_NAME`: no path, no leading dot, `.wav`.
fn is_bare_wav(f: &str) -> bool {
    let Some(stem) = f.strip_suffix(".wav") else { return false };
    (1..=128).contains(&stem.len())
        && stem.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
        && stem.starts_with(|c: char| c.is_ascii_alphanumeric())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests() {
        assert_eq!(request(TRANSCRIBE, &json!({})).unwrap(), json!({ "op": "transcribe" }));
        assert_eq!(request(TRANSCRIBE, &Value::Null).unwrap(), json!({ "op": "transcribe" }));
        assert_eq!(request(TRANSCRIBE, &json!({ "seconds": 5 })).unwrap(), json!({ "op": "transcribe", "seconds": 5.0 }));
        assert_eq!(request(TRANSCRIBE, &json!({ "file": "uk-001.wav" })).unwrap()["file"], "uk-001.wav");
        assert_eq!(request(PUSH_TO_TALK, &json!({ "since_id": 3 })).unwrap(), json!({ "op": "transcripts", "since_id": 3 }));
    }

    #[test]
    fn refusals() {
        for f in ["../x.wav", "/sdcard/x.wav", "a/b.wav", ".x.wav", "x.mp3", ".wav", "x\\y.wav"] {
            assert_eq!(request(TRANSCRIBE, &json!({ "file": f })).unwrap_err(), "file must be a bare .wav name in the bench folder", "{f}");
        }
        assert_eq!(request(TRANSCRIBE, &json!({ "seconds": 0.5 })).unwrap_err(), "seconds must be 1 to 60");
        assert_eq!(request(TRANSCRIBE, &json!({ "seconds": "8" })).unwrap_err(), "seconds must be 1 to 60");
        assert_eq!(request(TRANSCRIBE, &json!({ "since_id": 1 })).unwrap_err(), "unknown argument: since_id");
        assert_eq!(request(PUSH_TO_TALK, &json!({ "since_id": -1 })).unwrap_err(), "since_id must be a non-negative integer");
        assert_eq!(request(PUSH_TO_TALK, &json!([1])).unwrap_err(), "arguments must be an object");
    }
}
