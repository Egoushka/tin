//! Holding calls for an approvals tap. A held call becomes a ticket and an item on the approvals server
//! (`PUT /items/{id}`); the server signs the decision and POSTs it back to `/approvals/decision`, and the call runs only
//! after a valid `run`. A ticket runs at most once; one nobody decides is withdrawn when its time is up.

use hmac::{Hmac, Mac};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The approvals server, as tin uses it.
pub trait Approver: Send + Sync {
    /// Creates the item (`PUT /items/{id}`).
    fn put(&self, id: &str, item: &Value) -> Result<(), String>;
    /// Withdraws it (`DELETE /items/{id}`).
    fn delete(&self, id: &str) -> Result<(), String>;
}

/// `PUT`/`DELETE` over plain HTTP/1.1 (the tailnet is the encryption; tin carries no TLS stack).
pub struct HttpApprover {
    host: String,
    base: String,
    token: String,
}

const HTTP_TIMEOUT: Duration = Duration::from_secs(10);

impl HttpApprover {
    pub fn new(url: &str, token: &str) -> Result<Self, String> {
        let rest = url.strip_prefix("http://").ok_or_else(|| format!("approvals url {url}: only http:// is supported (use the tailnet address)"))?;
        let (host, path) = rest.split_once('/').map(|(h, p)| (h, format!("/{p}"))).unwrap_or((rest, "/".into()));
        let host = if host.contains(':') { host.to_string() } else { format!("{host}:80") };
        Ok(Self { host, base: path.trim_end_matches('/').to_string(), token: token.trim().to_string() })
    }

    fn request(&self, method: &str, path: &str, body: Option<&str>) -> Result<(u16, String), String> {
        let addr = self.host.to_socket_addrs().map_err(|e| format!("{}: {e}", self.host))?.next().ok_or_else(|| format!("{}: no address", self.host))?;
        let mut s = TcpStream::connect_timeout(&addr, HTTP_TIMEOUT).map_err(|e| format!("connect {}: {e}", self.host))?;
        s.set_read_timeout(Some(HTTP_TIMEOUT)).ok();
        s.set_write_timeout(Some(HTTP_TIMEOUT)).ok();
        let body = body.unwrap_or("");
        let head = format!(
            "{method} {}{path} HTTP/1.1\r\nHost: {}\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            self.base,
            self.host,
            self.token,
            body.len()
        );
        s.write_all(head.as_bytes()).and_then(|_| s.write_all(body.as_bytes())).map_err(|e| format!("write: {e}"))?;
        let mut buf = Vec::new();
        s.read_to_end(&mut buf).map_err(|e| format!("read: {e}"))?;
        let text = String::from_utf8_lossy(&buf);
        let code = text.split_whitespace().nth(1).and_then(|c| c.parse().ok()).ok_or("approvals: no HTTP status in the answer")?;
        let body = text.split_once("\r\n\r\n").map(|(_, b)| b.to_string()).unwrap_or_default();
        Ok((code, body))
    }
}

impl Approver for HttpApprover {
    fn put(&self, id: &str, item: &Value) -> Result<(), String> {
        match self.request("PUT", &format!("/items/{id}"), Some(&item.to_string()))? {
            (200 | 201, _) => Ok(()),
            (code, body) => Err(format!("approvals answered {code}: {}", body.chars().take(300).collect::<String>())),
        }
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        match self.request("DELETE", &format!("/items/{id}"), None)? {
            (200 | 204 | 404 | 409, _) => Ok(()),
            (code, body) => Err(format!("approvals answered {code}: {}", body.chars().take(300).collect::<String>())),
        }
    }
}

pub struct GateConfig {
    /// The approvals source; ticket ids start with `<source>:`.
    pub source: String,
    /// The item's callback URL (the policy's `callback` + `decision`).
    pub callback: String,
    /// How long one call blocks for a decision before it answers "pending".
    pub wait: Duration,
    /// How long a ticket can be decided; after that the item is withdrawn.
    pub ttl: Duration,
}

/// Undecided tickets allowed at once, so a looping client cannot flood the phone with pushes. A starting value.
pub const MAX_PENDING: usize = 10;

#[derive(Debug, Clone)]
enum State {
    Pending,
    Approved,
    Denied(String),
    Running,
    Done(Value),
    Expired,
}

struct Ticket {
    tool: String,
    args: Value,
    state: State,
    expires: Instant,
}

/// What a wait ends with.
#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// Approved and claimed by this caller: run it, then `finish`.
    Run { tool: String, args: Value },
    Denied(String),
    /// No decision within the wait; the ticket stays open.
    Pending,
    Expired,
    /// Approved and being run by another caller.
    Running,
    /// Already ran: the stored result.
    Done(Value),
    Unknown,
}

pub struct Gate {
    approver: Box<dyn Approver>,
    key: Vec<u8>,
    cfg: GateConfig,
    tickets: Mutex<HashMap<String, Ticket>>,
    changed: Condvar,
}

impl Gate {
    pub fn new(approver: Box<dyn Approver>, key: &[u8], cfg: GateConfig) -> Self {
        Self { approver, key: key.to_vec(), cfg, tickets: Mutex::new(HashMap::new()), changed: Condvar::new() }
    }

    /// Opens a ticket and its approvals item. Nothing runs until a signed `run` arrives.
    pub fn hold(&self, tool: &str, args: &Value, title: &str, summary: &str) -> Result<String, String> {
        self.sweep();
        let pending = self.tickets.lock().unwrap().values().filter(|t| matches!(t.state, State::Pending)).count();
        if pending >= MAX_PENDING {
            return Err(format!("{pending} approvals are already waiting on the phone; decide those first"));
        }
        let id = format!("{}:{}-{}", self.cfg.source, unix_ms(), random_hex());
        let expires_at = rfc3339(SystemTime::now() + self.cfg.ttl);
        let item = json!({
            "source": self.cfg.source,
            "id": id,
            "kind": "decision",
            "priority": 5,
            "title": clip(title, 200),
            "summary": clip(summary, 300),
            "body": { "tool": tool, "arguments": args },
            "editable": [],
            "actions": [
                { "id": "run", "label": "Run on phone", "style": "primary" },
                { "id": "deny", "label": "Deny", "style": "danger" }
            ],
            "expires_at": expires_at,
            "callback": self.cfg.callback,
        });
        // The ticket exists before the item does, so a decision that comes back fast finds it.
        self.tickets.lock().unwrap().insert(
            id.clone(),
            Ticket { tool: tool.to_string(), args: args.clone(), state: State::Pending, expires: Instant::now() + self.cfg.ttl },
        );
        if let Err(e) = self.approver.put(&id, &item) {
            self.tickets.lock().unwrap().remove(&id);
            return Err(e);
        }
        eprintln!("tin: held {tool} as {id}");
        Ok(id)
    }

    /// Blocks up to the configured wait for a decision on `ticket`.
    pub fn wait(&self, ticket: &str) -> Outcome {
        self.sweep();
        let deadline = Instant::now() + self.cfg.wait;
        let mut tickets = self.tickets.lock().unwrap();
        loop {
            let Some(t) = tickets.get_mut(ticket) else { return Outcome::Unknown };
            match &t.state {
                State::Approved => {
                    t.state = State::Running;
                    return Outcome::Run { tool: t.tool.clone(), args: t.args.clone() };
                }
                State::Denied(a) => return Outcome::Denied(a.clone()),
                State::Running => return Outcome::Running,
                State::Done(v) => return Outcome::Done(v.clone()),
                State::Expired => return Outcome::Expired,
                State::Pending => {}
            }
            let now = Instant::now();
            if now >= t.expires {
                drop(tickets);
                self.sweep();
                return Outcome::Expired;
            }
            if now >= deadline {
                return Outcome::Pending;
            }
            let until = deadline.min(t.expires) - now;
            tickets = self.changed.wait_timeout(tickets, until).unwrap().0;
        }
    }

    /// Stores what an approved ticket returned, so a later wait on it gets the same answer instead of a second run.
    pub fn finish(&self, ticket: &str, result: Value) {
        if let Some(t) = self.tickets.lock().unwrap().get_mut(ticket) {
            t.state = State::Done(result);
        }
        self.changed.notify_all();
    }

    /// The callback route: a signed decision from the approvals server. Returns the HTTP status and JSON body.
    pub fn decide(&self, body: &[u8]) -> (u16, Value) {
        let answer = |code: u16, ok: bool, message: &str| (code, json!({ "ok": ok, "message": message }));
        let Ok(d) = serde_json::from_slice::<Value>(body) else { return answer(400, false, "not JSON") };
        let field = |k: &str| d.get(k).and_then(Value::as_str).unwrap_or("");
        let (decision, id, action, at, sig) = (field("decision"), field("id"), field("action"), field("at"), field("sig"));
        if decision.is_empty() || id.is_empty() || action.is_empty() || at.is_empty() {
            return answer(400, false, "decision, id, action and at are required");
        }
        if !verify(&self.key, decision, id, action, at, d.get("edited").unwrap_or(&Value::Null), sig) {
            eprintln!("tin: decision for {id} with a bad signature");
            return answer(401, false, "bad signature");
        }
        self.sweep();
        let mut tickets = self.tickets.lock().unwrap();
        let Some(t) = tickets.get_mut(id) else { return answer(404, false, "no such ticket") };
        if !matches!(t.state, State::Pending) {
            return answer(409, true, "already decided");
        }
        t.state = if action == "run" { State::Approved } else { State::Denied(action.to_string()) };
        drop(tickets);
        self.changed.notify_all();
        eprintln!("tin: {id} decided {action}");
        answer(200, true, if action == "run" { "tin will run it" } else { "tin will not run it" })
    }

    /// Expires undecided tickets past their time (withdrawing their items) and forgets finished ones a ttl later.
    pub fn sweep(&self) {
        let now = Instant::now();
        let mut withdraw = Vec::new();
        {
            let mut tickets = self.tickets.lock().unwrap();
            for (id, t) in tickets.iter_mut() {
                if matches!(t.state, State::Pending) && now >= t.expires {
                    t.state = State::Expired;
                    withdraw.push(id.clone());
                }
            }
            let ttl = self.cfg.ttl;
            tickets.retain(|_, t| matches!(t.state, State::Running) || now < t.expires + ttl);
        }
        if withdraw.is_empty() {
            return;
        }
        self.changed.notify_all();
        for id in withdraw {
            match self.approver.delete(&id) {
                Ok(()) => eprintln!("tin: {id} expired, item withdrawn"),
                Err(e) => eprintln!("tin: {id} expired, withdraw failed: {e}"),
            }
        }
    }
}

/// `h` in the signature: SHA-256 hex of the edited fields as JSON with sorted keys, `""` when nothing was edited.
pub fn edited_hash(edited: &Value) -> String {
    match edited {
        Value::Object(m) if !m.is_empty() => hex(&Sha256::digest(edited.to_string().as_bytes())),
        _ => String::new(),
    }
}

/// `HMAC-SHA256(key, decision|id|action|at|h)` as hex, the approvals server's decision signature.
pub fn sign(key: &[u8], decision: &str, id: &str, action: &str, at: &str, edited: &Value) -> String {
    hex(&mac(key, decision, id, action, at, edited).finalize().into_bytes())
}

pub fn verify(key: &[u8], decision: &str, id: &str, action: &str, at: &str, edited: &Value, sig: &str) -> bool {
    unhex(sig).is_some_and(|s| mac(key, decision, id, action, at, edited).verify_slice(&s).is_ok())
}

fn mac(key: &[u8], decision: &str, id: &str, action: &str, at: &str, edited: &Value) -> Hmac<Sha256> {
    let mut m = Hmac::<Sha256>::new_from_slice(key).expect("HMAC takes any key length");
    m.update(format!("{decision}|{id}|{action}|{at}|{}", edited_hash(edited)).as_bytes());
    m
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn unhex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(s.get(i..i + 2)?, 16).ok()).collect()
}

fn clip(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

fn unix_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

fn random_hex() -> String {
    let mut b = [0u8; 8];
    if std::fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut b)).is_err() {
        // No urandom: time and a counter still keep ids unique within this process.
        static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let n = N.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        b = ((unix_ms() as u64) ^ n.rotate_left(32)).to_be_bytes();
    }
    hex(&b)
}

/// `2026-11-01T12:00:00Z` from a system time (UTC, whole seconds).
pub fn rfc3339(t: SystemTime) -> String {
    let secs = t.duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let (days, rem) = (secs / 86_400, secs % 86_400);
    // Howard Hinnant's civil_from_days.
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}
