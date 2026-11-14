//! Transports: newline-delimited JSON-RPC on stdio, and MCP streamable HTTP (JSON responses only, no SSE) on a Unix
//! socket. On Android the socket is abstract (`@tin`): `adb forward tcp:7381 localabstract:tin` reaches it, and the
//! peer-uid check keeps every app on the phone out. An optional TCP listener serves only the approvals callback
//! (`POST /approvals/decision`), whose body is HMAC-signed, so the tailnet can reach nothing else.

use crate::mcp::Server;
use serde_json::Value;
use std::io::{BufRead, BufReader, Read, Write};
use std::os::fd::AsRawFd;
use std::net::{TcpListener, TcpStream};
use std::os::unix::net::{UnixListener, UnixStream};
use std::sync::Arc;

pub fn serve_stdio(server: &Server) -> std::io::Result<()> {
    let stdin = std::io::stdin();
    let mut stdout = std::io::stdout().lock();
    for line in stdin.lock().lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        let reply = match serde_json::from_str::<Value>(&line) {
            Ok(msg) => server.handle(&msg),
            Err(e) => Some(parse_error(&e)),
        };
        if let Some(r) = reply {
            writeln!(stdout, "{r}")?;
            stdout.flush()?;
        }
    }
    Ok(())
}

fn parse_error(e: &serde_json::Error) -> Value {
    serde_json::json!({ "jsonrpc": "2.0", "id": null, "error": { "code": -32700, "message": format!("parse error: {e}") } })
}

/// Binds `name` as an abstract socket on Android and Linux, or as a filesystem path elsewhere (tests on macOS).
pub fn bind(name: &str) -> std::io::Result<UnixListener> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        #[cfg(target_os = "android")]
        use std::os::android::net::SocketAddrExt;
        #[cfg(target_os = "linux")]
        use std::os::linux::net::SocketAddrExt;
        let addr = std::os::unix::net::SocketAddr::from_abstract_name(name.as_bytes())?;
        UnixListener::bind_addr(&addr)
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = std::fs::remove_file(name);
        UnixListener::bind(name)
    }
}

pub fn connect(name: &str) -> std::io::Result<UnixStream> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        #[cfg(target_os = "android")]
        use std::os::android::net::SocketAddrExt;
        #[cfg(target_os = "linux")]
        use std::os::linux::net::SocketAddrExt;
        let addr = std::os::unix::net::SocketAddr::from_abstract_name(name.as_bytes())?;
        UnixStream::connect_addr(&addr)
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        UnixStream::connect(name)
    }
}

/// The connecting process's uid.
pub fn peer_uid(stream: &UnixStream) -> std::io::Result<u32> {
    let fd = stream.as_raw_fd();
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let mut cred = libc::ucred { pid: 0, uid: 0, gid: 0 };
        let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        let rc = unsafe { libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_PEERCRED, &mut cred as *mut _ as *mut libc::c_void, &mut len) };
        if rc != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(cred.uid)
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let (mut uid, mut gid) = (0, 0);
        if unsafe { libc::getpeereid(fd, &mut uid, &mut gid) } != 0 {
            return Err(std::io::Error::last_os_error());
        }
        Ok(uid)
    }
}

/// Root, the shell user (adbd runs as it), and whoever runs the server.
pub fn allowed_uids() -> Vec<u32> {
    let me = unsafe { libc::getuid() };
    let mut v = vec![0, 2000, me];
    v.dedup();
    v
}

pub fn serve_http(server: Arc<Server>, listener: UnixListener) -> std::io::Result<()> {
    let allowed = allowed_uids();
    for stream in listener.incoming() {
        let stream = match stream {
            Ok(s) => s,
            Err(e) => {
                eprintln!("tin: accept: {e}");
                continue;
            }
        };
        match peer_uid(&stream) {
            Ok(uid) if allowed.contains(&uid) => {}
            Ok(uid) => {
                eprintln!("tin: refused connection from uid {uid}");
                continue; // dropping the stream closes it without a byte
            }
            Err(e) => {
                eprintln!("tin: peer uid: {e}");
                continue;
            }
        }
        // A silent client must not hold a thread forever; keep-alive clients idle for less than this.
        stream.set_read_timeout(Some(std::time::Duration::from_secs(60))).ok();
        let server = server.clone();
        std::thread::spawn(move || {
            let result = stream.try_clone().and_then(|r| handle_connection(&server, r, stream, Routes::All));
            if let Err(e) = result {
                eprintln!("tin: connection: {e}");
            }
        });
    }
    Ok(())
}

/// The approvals callback listener: `POST /approvals/decision` and nothing else.
pub fn serve_decisions(server: Arc<Server>, listener: TcpListener) -> std::io::Result<()> {
    for stream in listener.incoming() {
        let stream: TcpStream = match stream {
            Ok(s) => s,
            Err(e) => {
                eprintln!("tin: accept: {e}");
                continue;
            }
        };
        // A slow or silent peer must not hold a thread forever.
        stream.set_read_timeout(Some(std::time::Duration::from_secs(30))).ok();
        let server = server.clone();
        std::thread::spawn(move || {
            let result = stream.try_clone().and_then(|r| handle_connection(&server, r, stream, Routes::DecisionOnly));
            if let Err(e) = result {
                eprintln!("tin: decision connection: {e}");
            }
        });
    }
    Ok(())
}

#[derive(Clone, Copy, PartialEq)]
enum Routes {
    All,
    DecisionOnly,
}

/// Largest request body accepted; a tool call's arguments are a few hundred bytes.
const MAX_BODY: usize = 1 << 20;

/// Longest request or header line, and most headers, accepted before the request is refused.
const MAX_LINE: usize = 8 * 1024;
const MAX_HEADERS: usize = 100;

/// Reads one line of at most `MAX_LINE` bytes. `Ok(None)` means the line was longer.
fn read_capped_line(reader: &mut impl BufRead) -> std::io::Result<Option<String>> {
    let mut line = String::new();
    let n = reader.take(MAX_LINE as u64 + 1).read_line(&mut line)?;
    Ok(if n > MAX_LINE && !line.ends_with('\n') { None } else { Some(line) })
}

/// `host` or `host:port` where host is a loopback name; `tin` is the name the built-in probe sends.
fn host_allowed(host: &str) -> bool {
    let name = match host.strip_prefix('[') {
        Some(rest) => rest.split_once(']').map(|(h, tail)| (h, tail.trim_start_matches(':'))).map(|(h, _)| format!("[{h}]")),
        None => Some(host.split(':').next().unwrap_or("").to_string()),
    };
    matches!(name.as_deref(), Some("127.0.0.1" | "localhost" | "[::1]" | "tin"))
}

/// An `Origin` header that names a loopback page; anything else is a web page on another site.
fn origin_allowed(origin: &str) -> bool {
    let Some(rest) = origin.strip_prefix("http://") else { return false };
    host_allowed(rest) && !rest.starts_with("tin")
}

fn is_json_type(ctype: &str) -> bool {
    ctype.trim().get(..16).is_some_and(|p| p.eq_ignore_ascii_case("application/json"))
}

/// HTTP/1.1 with keep-alive: one request after another on the same connection until the client closes.
fn handle_connection(server: &Server, read: impl Read, mut writer: impl Write, routes: Routes) -> std::io::Result<()> {
    let mut reader = BufReader::new(read);
    loop {
        let Some(request_line) = read_capped_line(&mut reader)? else {
            respond(&mut writer, 431, "Request Header Fields Too Large", None)?;
            return Ok(());
        };
        if request_line.is_empty() {
            return Ok(());
        }
        let mut parts = request_line.split_whitespace();
        let (method, path) = (parts.next().unwrap_or("").to_string(), parts.next().unwrap_or("").to_string());
        let mut content_length = 0usize;
        let mut close = false;
        let (mut origin, mut host, mut ctype) = (None, None, None);
        let mut headers = 0;
        loop {
            let Some(h) = read_capped_line(&mut reader)? else {
                respond(&mut writer, 431, "Request Header Fields Too Large", None)?;
                return Ok(());
            };
            if h.is_empty() {
                return Ok(());
            }
            headers += 1;
            if headers > MAX_HEADERS {
                respond(&mut writer, 431, "Request Header Fields Too Large", None)?;
                return Ok(());
            }
            let h = h.trim_end();
            if h.is_empty() {
                break;
            }
            if let Some((k, v)) = h.split_once(':') {
                match k.trim().to_ascii_lowercase().as_str() {
                    "content-length" => content_length = v.trim().parse().unwrap_or(0),
                    "connection" => close = v.trim().eq_ignore_ascii_case("close"),
                    "origin" => origin = Some(v.trim().to_string()),
                    "host" => host = Some(v.trim().to_string()),
                    "content-type" => ctype = Some(v.trim().to_string()),
                    _ => {}
                }
            }
        }
        if content_length > MAX_BODY {
            respond(&mut writer, 413, "Payload Too Large", None)?;
            return Ok(());
        }
        let mut body = vec![0; content_length];
        reader.read_exact(&mut body)?;
        let path = path.trim_end_matches('/');
        if routes == Routes::DecisionOnly && path != "/approvals/decision" {
            respond(&mut writer, 404, "Not Found", None)?;
            return Ok(());
        }
        match (method.as_str(), path) {
            ("POST", "/approvals/decision") => match server.gate() {
                Some(gate) => {
                    let (code, reply) = gate.decide(&body);
                    respond(&mut writer, code, reason(code), Some(&reply.to_string()))?
                }
                None => respond(&mut writer, 404, "Not Found", None)?,
            },
            // A browser can reach this socket through `adb forward`: refuse other sites' pages (Origin, and Host for DNS
            // rebinding) and the no-preflight content types a cross-site form or fetch can send.
            ("POST", "/mcp") if origin.as_deref().is_some_and(|o| !origin_allowed(o)) || host.as_deref().is_some_and(|h| !host_allowed(h)) => {
                respond(&mut writer, 403, "Forbidden", None)?;
                return Ok(());
            }
            ("POST", "/mcp") if !ctype.as_deref().is_some_and(is_json_type) => {
                respond(&mut writer, 415, "Unsupported Media Type", None)?;
                return Ok(());
            }
            ("POST", "/mcp") => match serde_json::from_slice::<Value>(&body) {
                Ok(msg) => match server.handle(&msg) {
                    Some(reply) => respond(&mut writer, 200, "OK", Some(&reply.to_string()))?,
                    None => respond(&mut writer, 202, "Accepted", None)?,
                },
                Err(e) => respond(&mut writer, 400, "Bad Request", Some(&parse_error(&e).to_string()))?,
            },
            // No server-initiated stream and no sessions: GET and DELETE are not offered (allowed by the spec).
            (_, "/mcp") => respond(&mut writer, 405, "Method Not Allowed", None)?,
            ("GET", "/health") => respond(&mut writer, 200, "OK", Some("{\"ok\":true}"))?,
            _ => respond(&mut writer, 404, "Not Found", None)?,
        }
        if close {
            return Ok(());
        }
    }
}

fn reason(code: u16) -> &'static str {
    match code {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        409 => "Conflict",
        _ => "",
    }
}

fn respond(w: &mut impl Write, code: u16, reason: &str, body: Option<&str>) -> std::io::Result<()> {
    let body = body.unwrap_or("");
    let ctype = if body.is_empty() { "" } else { "Content-Type: application/json\r\n" };
    write!(w, "HTTP/1.1 {code} {reason}\r\n{ctype}Content-Length: {}\r\n\r\n{body}", body.len())?;
    w.flush()
}

/// One `GET /health` over the socket: the HTTP status, or an error if the server closed the connection unanswered.
pub fn probe(name: &str) -> Result<u16, String> {
    let mut s = connect(name).map_err(|e| format!("connect: {e}"))?;
    s.write_all(b"GET /health HTTP/1.1\r\nHost: tin\r\nConnection: close\r\n\r\n").map_err(|e| format!("write: {e}"))?;
    let mut buf = String::new();
    s.read_to_string(&mut buf).map_err(|e| format!("read: {e}"))?;
    let status = buf.split_whitespace().nth(1).ok_or("closed without a response")?;
    status.parse().map_err(|_| format!("bad status line: {buf}"))
}
