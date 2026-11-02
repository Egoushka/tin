//! System tools: what tin reads and does on the phone as the shell user, outside AppFunctions. Each verb runs fixed
//! programs with argv (never a shell) and validates its arguments before anything runs. Read verbs parse `dumpsys` and
//! `uiautomator` output into JSON; write verbs only ever run after an approvals tap (the policy forces it).

use crate::policy::MONEY_PACKAGES;
use serde_json::{Map, Value, json};

/// Runs one program with argv as the shell user. `Ok` is stdout; `Err` the failure text.
pub type Shell<'a> = &'a dyn Fn(&str, &[String]) -> Result<String, String>;

#[derive(Debug)]
pub struct Verb {
    pub name: &'static str,
    pub read_only: bool,
    pub description: &'static str,
    pub ops: &'static Ops,
}

pub const VERBS: &[Verb] = &[
    Verb { name: "notifications", read_only: true, description: "Active notifications: key, app, channel, title, text, posted time, ongoing. Optional `package` filter.", ops: &ANDROID },
    Verb { name: "usage", read_only: true, description: "Today's foreground time and last use per app, most used first (`limit`, default 20).", ops: &ANDROID },
    Verb { name: "foreground_app", read_only: true, description: "The app and activity on screen now.", ops: &ANDROID },
    Verb { name: "screen_text", read_only: true, description: "Text and content descriptions on screen now (uiautomator), with bounds. Refused while a banking app is in front.", ops: &ANDROID },
    Verb { name: "battery", read_only: true, description: "Battery level, status, plug, temperature, health, voltage.", ops: &ANDROID },
    Verb { name: "connectivity", read_only: true, description: "Default network's transports and validation, every network's transports, airplane mode.", ops: &ANDROID },
    Verb { name: "tap", read_only: false, description: "Tap the screen at x, y (pixels).", ops: &ANDROID },
    Verb { name: "swipe", read_only: false, description: "Swipe from x1, y1 to x2, y2 over `ms` milliseconds (default 300).", ops: &ANDROID },
    Verb { name: "type_text", read_only: false, description: "Type text into the focused field (ASCII; `input text`).", ops: &ANDROID },
    Verb { name: "key", read_only: false, description: "Press a key: KEYCODE_HOME, KEYCODE_BACK, KEYCODE_ENTER, ...", ops: &ANDROID },
    Verb { name: "launch", read_only: false, description: "Open an app by package name.", ops: &ANDROID },
    Verb { name: "settings_put", read_only: false, description: "Set a value in Android settings (namespace system, secure or global).", ops: &ANDROID },
];

pub const INSTRUCTIONS: &str = "Android AppFunctions and system tools on this phone. Only what the phone's policy allows is listed. Read-only tools run at once; anything that changes state waits for a tap in the approvals app (call approval__wait with the ticket if the first wait runs out). Functions that move money are refused.";

pub fn verb(name: &str) -> Option<&'static Verb> {
    VERBS.iter().find(|v| v.name == name)
}

/// Where `screen_text` has uiautomator write its dump; removed after reading.
pub const UI_DUMP: &str = "/data/local/tmp/tin/ui.xml";

const MAX_COORD: i64 = 10_000;
const MAX_TEXT: usize = 1000;

impl Verb {
    pub fn tool_name(&self) -> String {
        format!("system__{}", self.name)
    }

    pub fn input_schema(&self) -> Value {
        (self.ops.schema)(self)
    }

    /// The argv a write verb runs, after validating every argument. Read verbs have no single argv.
    pub fn argv(&self, args: &Value) -> Result<Vec<String>, String> {
        (self.ops.argv)(self, args)
    }

    /// One line for the approvals item's title: what will happen. May read state through `shell` to name the target.
    pub fn describe(&self, args: &Value, shell: Shell) -> String {
        (self.ops.describe)(self, args, shell)
    }

    /// Runs the verb. Write verbs must only be reached after an approval; the server enforces that.
    pub fn run(&self, args: &Value, shell: Shell) -> Result<Value, String> {
        (self.ops.run)(self, args, shell)
    }
}

/// How one platform's verbs build their schema and argv, describe a held call, and run.
pub struct Ops {
    pub schema: fn(&Verb) -> Value,
    pub argv: fn(&Verb, &Value) -> Result<Vec<String>, String>,
    pub describe: fn(&Verb, &Value, Shell) -> String,
    pub run: fn(&Verb, &Value, Shell) -> Result<Value, String>,
}

impl std::fmt::Debug for Ops {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("Ops")
    }
}

const ANDROID: Ops = Ops { schema: android::schema, argv: android::argv, describe: android::describe, run: android::run };

mod android {
    use super::*;

    pub(super) fn schema(v: &Verb) -> Value {
        let int = |d: &str| json!({ "type": "integer", "minimum": 0, "maximum": MAX_COORD, "description": d });
        let (props, required): (Value, &[&str]) = match v.name {
            "notifications" => (json!({ "package": { "type": "string", "description": "only this app's notifications" } }), &[]),
            "usage" => (json!({ "limit": { "type": "integer", "minimum": 1, "maximum": 200 } }), &[]),
            "tap" => (json!({ "x": int("pixels from the left"), "y": int("pixels from the top") }), &["x", "y"]),
            "swipe" => (
                json!({ "x1": int("start x"), "y1": int("start y"), "x2": int("end x"), "y2": int("end y"), "ms": int("duration in milliseconds") }),
                &["x1", "y1", "x2", "y2"],
            ),
            "type_text" => (json!({ "text": { "type": "string", "maxLength": MAX_TEXT } }), &["text"]),
            "key" => (json!({ "key": { "type": "string", "pattern": "^KEYCODE_[A-Z0-9_]+$" } }), &["key"]),
            "launch" => (json!({ "package": { "type": "string" } }), &["package"]),
            "settings_put" => (
                json!({
                    "namespace": { "type": "string", "enum": ["system", "secure", "global"] },
                    "key": { "type": "string" },
                    "value": { "type": "string", "maxLength": MAX_TEXT }
                }),
                &["namespace", "key", "value"],
            ),
            _ => (json!({}), &[]),
        };
        json!({ "type": "object", "properties": props, "required": required })
    }

    /// The argv a write verb runs, after validating every argument. Read verbs have no single argv.
    pub(super) fn argv(v: &Verb, args: &Value) -> Result<Vec<String>, String> {
        let a = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        match v.name {
            "tap" => Ok(a(&["input", "tap", &coord(args, "x")?, &coord(args, "y")?])),
            "swipe" => {
                let ms = if args.get("ms").is_some() { coord(args, "ms")? } else { "300".into() };
                Ok(a(&["input", "swipe", &coord(args, "x1")?, &coord(args, "y1")?, &coord(args, "x2")?, &coord(args, "y2")?, &ms]))
            }
            "type_text" => {
                let t = text(args, "text")?;
                if t.contains("%s") {
                    return Err("text: `%s` cannot be typed (`input text` reads it as a space)".into());
                }
                Ok(a(&["input", "text", &t.replace(' ', "%s")]))
            }
            "key" => {
                let k = string(args, "key")?;
                let ok = k.strip_prefix("KEYCODE_").is_some_and(|r| !r.is_empty() && r.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_'));
                if !ok {
                    return Err(format!("key: {k:?} is not a KEYCODE_ name"));
                }
                Ok(a(&["input", "keyevent", k]))
            }
            "launch" => Ok(a(&["monkey", "-p", &package(args, "package")?, "-c", "android.intent.category.LAUNCHER", "1"])),
            "settings_put" => {
                let ns = string(args, "namespace")?;
                if !["system", "secure", "global"].contains(&ns) {
                    return Err(format!("namespace: {ns:?} is not system, secure or global"));
                }
                let key = string(args, "key")?;
                if key.is_empty() || key.len() > 100 || !key.chars().all(|c| c.is_ascii_alphanumeric() || "_.:-".contains(c)) {
                    return Err(format!("key: {key:?} is not a settings key"));
                }
                Ok(a(&["settings", "put", ns, key, &text(args, "value")?]))
            }
            other => Err(format!("{other} is not a write verb")),
        }
    }

    /// One line for the approvals item's title: what will happen on the phone.
    pub(super) fn describe(v: &Verb, args: &Value, _: Shell) -> String {
        let s = |k: &str| args.get(k).map(|v| v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string())).unwrap_or_default();
        match v.name {
            "tap" => format!("tap at ({}, {})", s("x"), s("y")),
            "swipe" => format!("swipe from ({}, {}) to ({}, {})", s("x1"), s("y1"), s("x2"), s("y2")),
            "type_text" => format!("type \"{}\"", clip(&s("text"), 120)),
            "key" => format!("press {}", s("key")),
            "launch" => format!("open {}", s("package")),
            "settings_put" => format!("set {} {} = {}", s("namespace"), s("key"), clip(&s("value"), 80)),
            other => other.to_string(),
        }
    }

    /// Runs the verb. Write verbs must only be reached after an approval; the server enforces that.
    pub(super) fn run(v: &Verb, args: &Value, shell: Shell) -> Result<Value, String> {
        let run = |argv: &[&str]| shell(argv[0], &argv[1..].iter().map(|s| s.to_string()).collect::<Vec<_>>());
        match v.name {
            "notifications" => {
                let only = args.get("package").and_then(Value::as_str);
                let list: Vec<Value> = parse_notifications(&run(&["dumpsys", "notification", "--noredact"])?)
                    .into_iter()
                    .filter(|n| only.is_none_or(|p| n["package"] == p))
                    .collect();
                Ok(json!({ "notifications": list }))
            }
            "usage" => {
                let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(20).clamp(1, 200) as usize;
                let mut u = parse_usage(&run(&["dumpsys", "usagestats"])?);
                if let Some(apps) = u.get_mut("apps").and_then(Value::as_array_mut) {
                    apps.truncate(limit);
                }
                Ok(u)
            }
            "foreground_app" => parse_foreground(&run(&["dumpsys", "activity", "activities"])?).ok_or_else(|| "no resumed activity found".into()),
            "screen_text" => {
                if let Some(fg) = parse_foreground(&run(&["dumpsys", "activity", "activities"])?) {
                    refuse_money(fg["package"].as_str().unwrap_or(""))?;
                }
                let out = run(&["uiautomator", "dump", UI_DUMP])?;
                if !out.contains("dumped to") {
                    return Err(format!("uiautomator: {out}"));
                }
                let xml = run(&["cat", UI_DUMP]);
                let _ = run(&["rm", "-f", UI_DUMP]);
                let screen = parse_ui(&xml?)?;
                for p in screen["packages"].as_array().into_iter().flatten() {
                    refuse_money(p.as_str().unwrap_or(""))?;
                }
                Ok(screen)
            }
            "battery" => Ok(parse_battery(&run(&["dumpsys", "battery"])?)),
            "connectivity" => {
                let mut c = parse_connectivity(&run(&["dumpsys", "connectivity"])?);
                let airplane = run(&["settings", "get", "global", "airplane_mode_on"]).ok().map(|s| s.trim() == "1");
                c["airplane_mode"] = json!(airplane);
                Ok(c)
            }
            _ => {
                let argv = argv(v, args)?;
                let out = shell(&argv[0], &argv[1..])?;
                if v.name == "launch" && out.contains("No activities found") {
                    return Err(format!("no launcher activity in {}", argv[2]));
                }
                Ok(json!({ "ok": true, "output": out.trim() }))
            }
        }
    }
}

fn refuse_money(package: &str) -> Result<(), String> {
    if MONEY_PACKAGES.contains(&package) {
        return Err(format!("refused: {package} is a banking app, and tin does not read money screens"));
    }
    Ok(())
}

fn clip(s: &str, n: usize) -> String {
    if s.chars().count() <= n { s.to_string() } else { format!("{}…", s.chars().take(n).collect::<String>()) }
}

fn string<'a>(args: &'a Value, k: &str) -> Result<&'a str, String> {
    args.get(k).and_then(Value::as_str).ok_or_else(|| format!("{k}: a string is required"))
}

fn coord(args: &Value, k: &str) -> Result<String, String> {
    match args.get(k).and_then(Value::as_i64) {
        Some(n) if (0..=MAX_COORD).contains(&n) => Ok(n.to_string()),
        _ => Err(format!("{k}: an integer from 0 to {MAX_COORD} is required")),
    }
}

fn text(args: &Value, k: &str) -> Result<String, String> {
    let t = string(args, k)?;
    if t.chars().count() > MAX_TEXT || t.chars().any(char::is_control) {
        return Err(format!("{k}: at most {MAX_TEXT} characters, no control characters"));
    }
    Ok(t.to_string())
}

fn package(args: &Value, k: &str) -> Result<String, String> {
    let p = string(args, k)?;
    let segs: Vec<&str> = p.split('.').collect();
    let ok = segs.len() >= 2
        && segs.iter().all(|s| !s.is_empty() && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_'))
        && segs[0].starts_with(|c: char| c.is_ascii_alphabetic());
    if !ok {
        return Err(format!("{k}: {p:?} is not a package name"));
    }
    Ok(p.to_string())
}

/// `key=value` pairs on a line; a value may be `"quoted with spaces"`.
fn fields(line: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut rest = line.trim();
    while let Some(eq) = rest.find('=') {
        let key = rest[..eq].rsplit(' ').next().unwrap_or("").to_string();
        let after = &rest[eq + 1..];
        let (value, next) = if let Some(q) = after.strip_prefix('"') {
            match q.find('"') {
                Some(end) => (&q[..end], &q[end + 1..]),
                None => (q, ""),
            }
        } else {
            match after.find(' ') {
                Some(sp) => (&after[..sp], &after[sp..]),
                None => (after, ""),
            }
        };
        out.push((key, value.to_string()));
        rest = next.trim_start();
    }
    out
}

/// `dumpsys notification --noredact`: the records under "Notification List:".
pub fn parse_notifications(text: &str) -> Vec<Value> {
    let mut out: Vec<Value> = Vec::new();
    let mut section: Option<usize> = None;
    for line in text.lines() {
        let indent = line.len() - line.trim_start().len();
        let t = line.trim();
        if t == "Notification List:" {
            section = Some(indent);
            continue;
        }
        let Some(base) = section else { continue };
        if !t.is_empty() && indent <= base {
            break;
        }
        if let Some(head) = t.strip_prefix("NotificationRecord(") {
            let f: Map<String, Value> = fields(head).into_iter().map(|(k, v)| (k, json!(v))).collect();
            let flags = f.get("flags").and_then(Value::as_str).and_then(|s| u64::from_str_radix(s.trim_start_matches("0x"), 16).ok()).unwrap_or(0);
            out.push(json!({
                "key": f.get("key"),
                "package": f.get("pkg"),
                "channel": f.get("Notification(channel").or(f.get("channel")),
                "category": f.get("category").filter(|c| *c != "null"),
                "ongoing": flags & 0x2 != 0,
                "title": null,
                "text": null,
                "posted_ms": null,
            }));
            continue;
        }
        let Some(n) = out.last_mut() else { continue };
        let extra = |name: &str| t.strip_prefix(name).and_then(|r| r.strip_prefix('=')).map(extra_value);
        if let Some(v) = extra("android.title") {
            n["title"] = v;
        } else if let Some(v) = extra("android.text") {
            n["text"] = v;
        } else if let Some(v) = extra("android.bigText").filter(|_| n["text"].is_null()) {
            n["text"] = v;
        } else if let Some(ms) = t.strip_prefix("mCreationTimeMs=").and_then(|s| s.parse::<u64>().ok()) {
            n["posted_ms"] = json!(ms);
        }
    }
    out
}

/// An extras value: `String (Alice)` → "Alice"; a redacted `String [length=5]` or `null` → null.
fn extra_value(v: &str) -> Value {
    match v.split_once(" (") {
        Some((_, rest)) => json!(rest.strip_suffix(')').unwrap_or(rest)),
        None => Value::Null,
    }
}

/// `dumpsys usagestats`: the first "In-memory daily stats" block, most foreground time first.
pub fn parse_usage(text: &str) -> Value {
    let mut apps = Vec::new();
    let mut range = Value::Null;
    let mut inside = false;
    for line in text.lines() {
        let t = line.trim();
        if t.starts_with("In-memory daily stats") {
            if inside {
                break;
            }
            inside = true;
            continue;
        }
        if !inside {
            continue;
        }
        if t.starts_with("In-memory") || t.starts_with("user=") {
            break;
        }
        if let Some(r) = t.strip_prefix("timeRange=") {
            range = json!(r.trim_matches('"'));
        } else if t.starts_with("package=") {
            let f: std::collections::HashMap<String, String> = fields(t).into_iter().collect();
            let used = f.get("totalTimeUsed").and_then(|d| duration_ms(d)).unwrap_or(0);
            apps.push(json!({
                "package": f.get("package"),
                "foreground_ms": used,
                "last_used": f.get("lastTimeUsed"),
                "launches": f.get("appLaunchCount").and_then(|n| n.parse::<u64>().ok()),
            }));
        }
    }
    apps.sort_by(|a, b| b["foreground_ms"].as_u64().cmp(&a["foreground_ms"].as_u64()));
    json!({ "range": range, "apps": apps })
}

/// `01:02:03`, `02:03` or plain milliseconds.
fn duration_ms(d: &str) -> Option<u64> {
    if !d.contains(':') {
        return d.parse().ok();
    }
    d.split(':').try_fold(0u64, |acc, p| p.parse::<u64>().ok().map(|n| acc * 60 + n)).map(|s| s * 1000)
}

/// `dumpsys activity activities`: the resumed activity's package and class.
pub fn parse_foreground(text: &str) -> Option<Value> {
    const KEYS: &[&str] = &["topResumedActivity=", "mResumedActivity:", "ResumedActivity:"];
    let line = KEYS.iter().find_map(|k| text.lines().find(|l| l.trim_start().starts_with(k)))?;
    let rec = &line[line.find("ActivityRecord{")? + "ActivityRecord{".len()..];
    let component = rec.split_whitespace().find(|w| w.contains('/'))?.trim_end_matches('}');
    let (pkg, act) = component.split_once('/')?;
    let act = if act.starts_with('.') { format!("{pkg}{act}") } else { act.to_string() };
    Some(json!({ "package": pkg, "activity": act }))
}

/// `uiautomator dump` XML: nodes that carry text or a description, password text left out.
pub fn parse_ui(xml: &str) -> Result<Value, String> {
    let doc = roxmltree::Document::parse(xml).map_err(|e| format!("uiautomator XML: {e}"))?;
    let mut nodes = Vec::new();
    let mut packages: Vec<String> = Vec::new();
    for n in doc.descendants().filter(|n| n.has_tag_name("node")) {
        let attr = |k: &str| n.attribute(k).unwrap_or("");
        let pkg = attr("package");
        if !pkg.is_empty() && !packages.iter().any(|p| p == pkg) {
            packages.push(pkg.to_string());
        }
        let password = attr("password") == "true";
        let (text, desc) = (attr("text"), attr("content-desc"));
        if (text.is_empty() || password) && desc.is_empty() {
            continue;
        }
        let bounds: Vec<i64> = attr("bounds").split(|c: char| !c.is_ascii_digit() && c != '-').filter_map(|s| s.parse().ok()).collect();
        nodes.push(json!({
            "text": if text.is_empty() || password { Value::Null } else { json!(text) },
            "description": if desc.is_empty() { Value::Null } else { json!(desc) },
            "id": Some(attr("resource-id")).filter(|s| !s.is_empty()),
            "class": attr("class").rsplit('.').next(),
            "bounds": bounds,
            "clickable": attr("clickable") == "true",
        }));
    }
    Ok(json!({ "package": packages.first(), "packages": packages, "nodes": nodes }))
}

/// `dumpsys battery`.
pub fn parse_battery(text: &str) -> Value {
    let get = |k: &str| text.lines().find_map(|l| l.trim().strip_prefix(k).and_then(|r| r.strip_prefix(": ")).map(str::trim));
    let num = |k: &str| get(k).and_then(|v| v.parse::<i64>().ok());
    let yes = |k: &str| get(k) == Some("true");
    let status = match num("status") {
        Some(2) => "charging",
        Some(3) => "discharging",
        Some(4) => "not charging",
        Some(5) => "full",
        Some(_) => "unknown",
        None => return json!({}),
    };
    let health = match num("health") {
        Some(2) => "good",
        Some(3) => "overheat",
        Some(4) => "dead",
        Some(5) => "over voltage",
        Some(6) => "failure",
        Some(7) => "cold",
        _ => "unknown",
    };
    let plugged = if yes("AC powered") {
        "ac"
    } else if yes("USB powered") {
        "usb"
    } else if yes("Wireless powered") {
        "wireless"
    } else if yes("Dock powered") {
        "dock"
    } else {
        "none"
    };
    let percent = match (num("level"), num("scale")) {
        (Some(l), Some(s)) if s > 0 => Some(l * 100 / s),
        (l, _) => l,
    };
    json!({
        "level_percent": percent,
        "status": status,
        "plugged": plugged,
        "temperature_c": num("temperature").map(|t| t as f64 / 10.0),
        "health": health,
        "voltage_mv": num("voltage"),
    })
}

/// `dumpsys connectivity`: the default network and the first record of every network.
pub fn parse_connectivity(text: &str) -> Value {
    let default = text.lines().find_map(|l| l.trim().strip_prefix("Active default network:")).map(str::trim).and_then(|s| s.parse::<u64>().ok());
    let mut networks: Vec<Value> = Vec::new();
    for line in text.lines().filter(|l| l.contains("NetworkAgentInfo{")) {
        let Some(id) = between(line, "network{", "}").and_then(|s| s.parse::<u64>().ok()) else { continue };
        if networks.iter().any(|n| n["id"] == id) {
            continue;
        }
        let transports: Vec<String> = between(line, "Transports: ", " ").map(|t| t.split('|').map(str::to_string).collect()).unwrap_or_default();
        let caps = between(line, "Capabilities: ", " ").unwrap_or("");
        let has = |c: &str| caps.split('&').any(|x| x == c);
        networks.push(json!({
            "id": id,
            "transports": transports,
            "validated": has("VALIDATED"),
            "metered": !has("NOT_METERED"),
            "default": Some(id) == default,
        }));
    }
    let def = networks.iter().find(|n| n["default"] == true).cloned();
    json!({ "default": def, "networks": networks })
}

fn between<'a>(s: &'a str, start: &str, end: &str) -> Option<&'a str> {
    let from = s.find(start)? + start.len();
    let rest = &s[from..];
    Some(&rest[..rest.find(end).unwrap_or(rest.len())])
}
