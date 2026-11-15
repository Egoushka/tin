//! One OpenTelemetry span per tool call, sent as OTLP/HTTP JSON to a collector over plain HTTP (the tailnet is the
//! encryption; tin carries no TLS stack). Spans carry names, decisions and timings, never arguments or results.
//! Sending never blocks or fails a call: spans go through a bounded queue to one background thread.

use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{SyncSender, TrySendError, sync_channel};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const SEND_TIMEOUT: Duration = Duration::from_secs(2);
const QUEUE: usize = 256;

#[derive(Debug, Clone)]
pub struct Span {
    pub trace_id: String,
    pub span_id: String,
    pub parent_id: Option<String>,
    pub name: String,
    pub start_ns: u64,
    pub end_ns: u64,
    pub attrs: Vec<(String, String)>,
    pub error: bool,
}

pub trait SpanSink: Send + Sync {
    fn emit(&self, span: Span);
}

pub fn now_ns() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0)
}

/// `n` random-looking hex characters. Ids must be unique, not secret: a hash of the clock, the process and a counter.
pub fn new_id(n: usize) -> String {
    static COUNT: AtomicU64 = AtomicU64::new(0);
    let mut h = Sha256::new();
    h.update(now_ns().to_le_bytes());
    h.update(std::process::id().to_le_bytes());
    h.update(COUNT.fetch_add(1, Ordering::Relaxed).to_le_bytes());
    h.finalize().iter().take(n.div_ceil(2)).map(|b| format!("{b:02x}")).collect::<String>()[..n].to_string()
}

/// W3C `traceparent` (`00-<32 hex>-<16 hex>-<2 hex>`) into (trace id, parent span id). All-zero ids are invalid.
pub fn parse_traceparent(s: &str) -> Option<(String, String)> {
    let mut p = s.trim().split('-');
    let (version, trace, parent, flags) = (p.next()?, p.next()?, p.next()?, p.next()?);
    let hex = |x: &str, n: usize| x.len() == n && x.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    let live = |x: &str| x.bytes().any(|b| b != b'0');
    (version.len() == 2 && version != "ff" && hex(flags, 2) && hex(trace, 32) && hex(parent, 16) && live(trace) && live(parent))
        .then(|| (trace.to_string(), parent.to_string()))
}

/// The OTLP/HTTP JSON body for one span.
pub fn otlp_body(service: &str, span: &Span) -> Value {
    let attrs: Vec<Value> = span.attrs.iter().map(|(k, v)| json!({ "key": k, "value": { "stringValue": v } })).collect();
    let mut s = json!({
        "traceId": span.trace_id,
        "spanId": span.span_id,
        "name": span.name,
        "kind": 2,
        "startTimeUnixNano": span.start_ns.to_string(),
        "endTimeUnixNano": span.end_ns.to_string(),
        "attributes": attrs,
        "status": { "code": if span.error { 2 } else { 1 } },
    });
    if let Some(p) = &span.parent_id {
        s["parentSpanId"] = json!(p);
    }
    json!({ "resourceSpans": [{
        "resource": { "attributes": [{ "key": "service.name", "value": { "stringValue": service } }] },
        "scopeSpans": [{ "scope": { "name": "tin", "version": env!("CARGO_PKG_VERSION") }, "spans": [s] }],
    }] })
}

/// Sends each span to the collector from a background thread; a full queue or a failed send drops the span.
pub struct OtlpSink {
    tx: SyncSender<Span>,
}

impl OtlpSink {
    pub fn new(url: &str, service: &str) -> Result<Self, String> {
        let rest = url.strip_prefix("http://").ok_or_else(|| "telemetry otlp_url: only http:// is supported (use the tailnet address)".to_string())?;
        let (host, path) = rest.split_once('/').map(|(h, p)| (h.to_string(), format!("/{p}"))).unwrap_or((rest.to_string(), "/v1/traces".into()));
        if host.is_empty() {
            return Err("telemetry otlp_url: no host".into());
        }
        let host = if host.contains(':') { host } else { format!("{host}:80") };
        let service = service.to_string();
        let (tx, rx) = sync_channel::<Span>(QUEUE);
        std::thread::spawn(move || {
            for span in rx {
                let _ = post(&host, &path, &otlp_body(&service, &span).to_string());
            }
        });
        Ok(Self { tx })
    }
}

impl SpanSink for OtlpSink {
    fn emit(&self, span: Span) {
        if let Err(TrySendError::Full(_) | TrySendError::Disconnected(_)) = self.tx.try_send(span) {
            // dropped: a collector that cannot keep up must not slow the phone
        }
    }
}

fn post(host: &str, path: &str, body: &str) -> Result<(), String> {
    let addr = host.to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("no address")?;
    let mut s = TcpStream::connect_timeout(&addr, SEND_TIMEOUT).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(SEND_TIMEOUT)).ok();
    s.set_write_timeout(Some(SEND_TIMEOUT)).ok();
    let head = format!("POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
    s.write_all(head.as_bytes()).and_then(|_| s.write_all(body.as_bytes())).map_err(|e| e.to_string())?;
    let mut sink = Vec::new();
    let _ = s.read_to_end(&mut sink);
    Ok(())
}
