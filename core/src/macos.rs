//! macOS: App Intents read from each app's `extract.actionsdata`, run through a one-action shortcut with
//! `shortcuts run`, and Mac system verbs (memory, processes, stale build and test processes, launchd agents). Every
//! program runs with argv, never through a shell; write verbs only run after an approvals tap.

use crate::backend::{Backend, run_argv};
use crate::metadata::Function;
use crate::system::{Ops, Shell, Verb};
use serde_json::{Map, Value, json};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

/// Where apps live. Each app's PlugIns, Extensions and Frameworks are searched too.
pub const ROOTS: &[&str] = &["/Applications", "/System/Applications"];

pub const INSTRUCTIONS: &str = "App Intents of the apps on this Mac and Mac system tools (memory, processes, stale build and test processes, launchd agents). Only what the policy allows is listed. Read-only tools run at once; anything that changes state (killing a process, quitting an app, unloading an agent, any intent the policy does not mark read-only) waits for a tap in the approvals app (call approval__wait with the ticket if the first wait runs out). Functions that move money are refused.";

/// Where the metadata sits inside a bundle, relative to the bundle. Weather keeps a second set under `Link.data`.
const METADATA: &[&str] = &[
    "Contents/Resources/Metadata.appintents/extract.actionsdata",
    "Contents/Resources/Link.data/extract.actionsdata",
    "Versions/A/Resources/Metadata.appintents/extract.actionsdata",
];
const INFO: &[&str] = &["Contents/Info.plist", "Versions/A/Resources/Info.plist", "Resources/Info.plist"];
const NESTED: &[&str] = &["Contents/PlugIns", "Contents/Extensions", "Contents/Frameworks"];

/// One actionsdata file and the bundle that publishes it; a bundle with two files is listed twice.
#[derive(Debug, Clone)]
pub struct Bundle {
    pub path: PathBuf,
    pub id: String,
    pub actionsdata: PathBuf,
}

/// Every bundle under `roots` with an actionsdata file: apps one level down (and in folders such as Utilities), plus
/// their nested plug-ins, extensions and frameworks.
pub fn scan(roots: &[PathBuf]) -> Vec<Bundle> {
    let mut apps = Vec::new();
    for root in roots {
        for e in children(root) {
            if is_bundle(&e, "app") {
                apps.push(e);
            } else if e.is_dir() && !e.is_symlink() {
                apps.extend(children(&e).into_iter().filter(|p| is_bundle(p, "app")));
            }
        }
    }
    let mut out = Vec::new();
    for app in apps {
        let mut bundles = vec![app.clone()];
        for dir in NESTED {
            bundles.extend(children(&app.join(dir)).into_iter().filter(|p| is_bundle(p, "appex") || is_bundle(p, "framework")));
        }
        for b in bundles {
            let files: Vec<PathBuf> = METADATA.iter().map(|m| b.join(m)).filter(|p| p.is_file()).collect();
            if files.is_empty() {
                continue;
            }
            let Some(id) = INFO.iter().map(|i| b.join(i)).find(|p| p.is_file()).and_then(|p| info_string(&p, "CFBundleIdentifier")) else {
                continue;
            };
            out.extend(files.into_iter().map(|actionsdata| Bundle { path: b.clone(), id: id.clone(), actionsdata }));
        }
    }
    out
}

fn children(dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(dir).map(|r| r.flatten().map(|e| e.path()).collect()).unwrap_or_default();
    v.sort();
    v
}

fn is_bundle(p: &Path, ext: &str) -> bool {
    p.extension().is_some_and(|e| e == ext) && p.is_dir()
}

/// A string value from an Info.plist: XML parsed here, binary through `plutil`.
pub fn info_string(path: &Path, key: &str) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    if bytes.starts_with(b"bplist") {
        let p = path.to_str()?;
        return run_argv("plutil", &["-extract".into(), key.into(), "raw".into(), "-o".into(), "-".into(), p.into()])
            .ok()
            .map(|s| s.trim().to_string());
    }
    plist_string(std::str::from_utf8(&bytes).ok()?, key)
}

/// `<key>KEY</key><string>VALUE</string>` from the top-level dict of an XML plist.
pub fn plist_string(xml: &str, key: &str) -> Option<String> {
    let opts = roxmltree::ParsingOptions { allow_dtd: true, ..Default::default() };
    let doc = roxmltree::Document::parse_with_options(xml, opts).ok()?;
    let dict = doc.root_element().children().find(|n| n.has_tag_name("dict"))?;
    let mut nodes = dict.children().filter(|n| n.is_element());
    while let Some(n) = nodes.next() {
        if n.has_tag_name("key") && n.text() == Some(key) {
            return nodes.next().filter(|v| v.has_tag_name("string")).and_then(|v| v.text()).map(str::to_string);
        }
    }
    None
}

/// The discoverable actions of one actionsdata document as functions with JSON Schemas.
pub fn parse_actions(bundle_id: &str, data: &Value) -> Vec<Function> {
    let Some(actions) = data.get("actions").and_then(Value::as_object) else { return Vec::new() };
    let mut out: Vec<Function> = actions
        .values()
        .filter(|a| discoverable(a))
        .filter_map(|a| {
            let id = a.get("identifier")?.as_str()?.to_string();
            let title = key(a, "title").unwrap_or(&id).to_string();
            let mut description = match key(&a["descriptionMetadata"], "descriptionText") {
                Some(d) if !d.is_empty() => format!("{title}. {d}"),
                _ => title,
            };
            if a.get("openAppWhenRun").and_then(Value::as_bool) == Some(true) {
                description = format!("[opens the app] {description}");
            }
            let mut props = Map::new();
            let mut required = Vec::new();
            for p in a.get("parameters").and_then(Value::as_array).into_iter().flatten() {
                let Some(name) = p.get("name").and_then(Value::as_str) else { continue };
                props.insert(name.to_string(), parameter(p, data));
                if p.get("isOptional").and_then(Value::as_bool) != Some(true) {
                    required.push(name.to_string());
                }
            }
            let input_schema = json!({ "type": "object", "properties": props, "required": required });
            Some(Function { package: bundle_id.to_string(), id, description, input_schema, enabled_by_default: true })
        })
        .collect();
    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

/// Every action in a document, discoverable or not: what the survey counts.
pub fn action_count(data: &Value) -> usize {
    data.get("actions").and_then(Value::as_object).map_or(0, Map::len)
}

fn discoverable(a: &Value) -> bool {
    a.get("isDiscoverable").and_then(Value::as_bool).unwrap_or(true)
        && a["visibilityMetadata"].get("isDiscoverable").and_then(Value::as_bool).unwrap_or(true)
}

/// A localized string's `key` (the English text).
fn key<'a>(v: &'a Value, field: &str) -> Option<&'a str> {
    v.get(field)?.get("key")?.as_str()
}

/// `{"string": {"wrapper": x}}` and its siblings to plain JSON.
fn typed(v: &Value) -> Option<Value> {
    let (kind, inner) = v.as_object()?.iter().next()?;
    match kind.as_str() {
        "string" | "int" | "double" | "bool" => inner.get("wrapper").cloned(),
        "array" => Some(Value::Array(inner.get("elements")?.as_array()?.iter().filter_map(typed).collect())),
        _ => None,
    }
}

/// `typeSpecificMetadata` is a flat `[key, value, key, value]` list.
fn metadata<'a>(p: &'a Value, name: &str) -> Option<&'a Value> {
    let list = p.get("typeSpecificMetadata")?.as_array()?;
    list.chunks(2).find(|kv| kv[0] == name).and_then(|kv| kv.get(1))
}

fn parameter(p: &Value, data: &Value) -> Value {
    let mut s = value_schema(&p["valueType"], data, 0);
    let Some(obj) = s.as_object_mut() else { return s };
    let title = key(p, "title");
    let desc = key(p, "parameterDescription").filter(|d| !d.is_empty());
    let text = match (title, desc, obj.get("description").and_then(Value::as_str)) {
        (Some(t), Some(d), _) => format!("{t}: {d}"),
        (Some(t), None, Some(kind)) => format!("{t} ({kind})"),
        (Some(t), None, None) => t.to_string(),
        (None, Some(d), _) => d.to_string(),
        (None, None, k) => k.unwrap_or_default().to_string(),
    };
    if !text.is_empty() {
        obj.insert("description".into(), json!(text));
    }
    if let Some(d) = metadata(p, "LNValueTypeSpecificMetadataKeyDefaultValue").and_then(typed).filter(|d| d != "") {
        obj.insert("default".into(), d);
    }
    if let Some(lo) = metadata(p, "LNValueTypeMetadataKeyNumberLowerBound").and_then(typed) {
        obj.insert("minimum".into(), lo);
    }
    if let Some(hi) = metadata(p, "LNValueTypeMetadataKeyNumberUpperBound").and_then(typed) {
        obj.insert("maximum".into(), hi);
    }
    if let Some(Value::Array(utis)) = metadata(p, "LNValueTypeMetadataKeyFileSupportedTypes").and_then(typed) {
        let utis: Vec<&str> = utis.iter().filter_map(Value::as_str).collect();
        let d = obj.get("description").and_then(Value::as_str).unwrap_or("file path").to_string();
        obj.insert("description".into(), json!(format!("{d} [types: {}]", utis.join(", "))));
    }
    s
}

/// References nest (arrays of alternatives); past this depth a type is emitted unconstrained.
const MAX_DEPTH: usize = 6;

/// One `valueType` to JSON Schema.
pub fn value_schema(vt: &Value, data: &Value, depth: usize) -> Value {
    let Some((kind, inner)) = vt.as_object().and_then(|o| o.iter().next()) else { return json!({}) };
    let w = &inner["wrapper"];
    let string = |d: String| json!({ "type": "string", "description": d });
    if depth > MAX_DEPTH {
        return json!({});
    }
    match kind.as_str() {
        "primitive" => match w["typeIdentifier"].as_i64() {
            Some(0) => json!({ "type": "string" }),
            Some(1) => json!({ "type": "boolean" }),
            Some(2) => json!({ "type": "integer" }),
            Some(7) => json!({ "type": "number" }),
            Some(8) => json!({ "type": "string", "format": "date-time" }),
            Some(9) => string("date components, ISO 8601: 2026-11-02 or 2026-11-02T09:30".into()),
            Some(11) => json!({ "type": "string", "format": "uri" }),
            Some(12) => string("text".into()),
            other => json!({ "description": format!("primitive type {}", other.unwrap_or(-1)) }),
        },
        "linkEnumeration" => {
            let ident = w["identifier"].as_str().unwrap_or("");
            let cases: Vec<&str> = enums(data)
                .find(|e| e["identifier"] == ident)
                .and_then(|e| e["cases"].as_array())
                .map(|c| c.iter().filter_map(|c| c["identifier"].as_str()).collect())
                .unwrap_or_default();
            if cases.is_empty() { string(format!("{ident} case")) } else { json!({ "type": "string", "enum": cases }) }
        }
        "entity" => string(format!("{} id", w["typeName"].as_str().unwrap_or("entity"))),
        "searchCriteria" => string("search text".into()),
        "intents" => match w["typeIdentifier"].as_i64() {
            Some(12) => string("file path".into()),
            Some(0) => string("app bundle id".into()),
            other => string(format!("intent value type {}", other.unwrap_or(-1))),
        },
        "builtIn" => string(format!("built-in type {}", w["builtInType"].as_i64().unwrap_or(-1))),
        "measurement" => json!({ "type": "number", "description": format!("measurement, unit type {}", w["unitType"].as_i64().unwrap_or(-1)) }),
        "array" => json!({ "type": "array", "items": value_schema(&w["memberValueType"], data, depth + 1) }),
        "alternative" => {
            let members: Vec<Value> = w["memberValueTypes"].as_array().into_iter().flatten().map(|m| value_schema(m, data, depth + 1)).collect();
            json!({ "anyOf": members })
        }
        other => json!({ "description": format!("{other} value") }),
    }
}

/// `enums` is an array in every file seen; an object is accepted too.
fn enums(data: &Value) -> Box<dyn Iterator<Item = &Value> + '_> {
    match &data["enums"] {
        Value::Array(a) => Box::new(a.iter()),
        Value::Object(o) => Box::new(o.values()),
        _ => Box::new(std::iter::empty()),
    }
}

/// The library name of the shortcut that runs one intent.
pub fn shortcut_name(bundle_id: &str, identifier: &str) -> String {
    format!("tin: {bundle_id}.{identifier}")
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// An unsigned one-action shortcut (XML plist) that runs the intent and outputs its result.
pub fn shortcut_plist(bundle_id: &str, identifier: &str, app_name: &str, team: Option<&str>, uuid: &str) -> String {
    let e = xml_escape;
    let team = team.map(|t| format!("<key>TeamIdentifier</key><string>{}</string>", e(t))).unwrap_or_default();
    format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>WFWorkflowClientVersion</key><string>3600.0.0</string>
<key>WFWorkflowMinimumClientVersion</key><integer>900</integer>
<key>WFWorkflowMinimumClientVersionString</key><string>900</string>
<key>WFWorkflowIcon</key><dict><key>WFWorkflowIconStartColor</key><integer>4282601983</integer><key>WFWorkflowIconGlyphNumber</key><integer>59511</integer></dict>
<key>WFWorkflowImportQuestions</key><array/>
<key>WFWorkflowTypes</key><array/>
<key>WFWorkflowInputContentItemClasses</key><array/>
<key>WFWorkflowOutputContentItemClasses</key><array/>
<key>WFQuickActionSurfaces</key><array/>
<key>WFWorkflowHasShortcutInputVariables</key><false/>
<key>WFWorkflowHasOutputFallback</key><false/>
<key>WFWorkflowActions</key><array>
<dict><key>WFWorkflowActionIdentifier</key><string>{b}.{i}</string>
<key>WFWorkflowActionParameters</key><dict><key>UUID</key><string>{u}</string>
<key>AppIntentDescriptor</key><dict><key>BundleIdentifier</key><string>{b}</string><key>Name</key><string>{n}</string>{team}<key>AppIntentIdentifier</key><string>{i}</string></dict>
</dict></dict>
<dict><key>WFWorkflowActionIdentifier</key><string>is.workflow.actions.output</string>
<key>WFWorkflowActionParameters</key><dict><key>WFOutput</key><dict><key>Value</key><dict><key>string</key><string>&#xFFFC;</string>
<key>attachmentsByRange</key><dict><key>{{0, 1}}</key><dict><key>OutputUUID</key><string>{u}</string><key>Type</key><string>ActionOutput</string><key>OutputName</key><string>{n}</string></dict></dict></dict>
<key>WFSerializationType</key><string>WFTextTokenString</string></dict></dict></dict>
</array>
</dict></plist>
"#,
        b = e(bundle_id),
        i = e(identifier),
        n = e(app_name),
        u = e(uuid),
    )
}

/// App Intents and system tools on this Mac.
pub struct MacBackend {
    pub roots: Vec<PathBuf>,
    pub timeout: Duration,
}

impl Default for MacBackend {
    fn default() -> Self {
        Self { roots: ROOTS.iter().map(PathBuf::from).collect(), timeout: Duration::from_secs(30) }
    }
}

impl MacBackend {
    pub fn bundles(&self) -> Vec<Bundle> {
        scan(&self.roots)
    }

    /// Writes `<dir>/<shortcut name>.shortcut`, signed for import. Returns its path.
    pub fn write_shortcut(&self, bundle_id: &str, identifier: &str, dir: &Path) -> Result<PathBuf, String> {
        let bundle = self
            .bundles()
            .into_iter()
            .filter(|b| b.id == bundle_id)
            .find(|b| read_json(&b.actionsdata).is_ok_and(|d| d["actions"].get(identifier).is_some_and(Value::is_object)))
            .ok_or_else(|| format!("no App Intents bundle {bundle_id} with an action {identifier}"))?;
        // The app the intent belongs to: an extension's intents show under its containing app.
        let app = bundle.path.ancestors().find(|p| is_bundle(p, "app")).unwrap_or(&bundle.path).to_path_buf();
        let info = INFO.iter().map(|i| app.join(i)).find(|p| p.is_file());
        let name = info
            .as_deref()
            .and_then(|p| info_string(p, "CFBundleDisplayName").or_else(|| info_string(p, "CFBundleName")))
            .unwrap_or_else(|| bundle_id.to_string());
        let team = team_id(&app);
        let uuid = uuid_v4();
        let unsigned = dir.join(format!("{identifier}.unsigned.shortcut"));
        std::fs::write(&unsigned, shortcut_plist(bundle_id, identifier, &name, team.as_deref(), &uuid)).map_err(|e| e.to_string())?;
        let out = dir.join(format!("{}.shortcut", shortcut_name(bundle_id, identifier).replace([':', '/'], "")));
        let s = |p: &Path| p.to_string_lossy().into_owned();
        let signed = run_argv("shortcuts", &["sign".into(), "--mode".into(), "people-who-know-me".into(), "-i".into(), s(&unsigned), "-o".into(), s(&out)]);
        let _ = std::fs::remove_file(&unsigned);
        signed.map(|_| out)
    }
}

fn read_json(path: &Path) -> Result<Value, String> {
    let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
    serde_json::from_slice(&bytes).map_err(|e| e.to_string())
}

/// The signing team of a bundle, from `codesign -dv` (it prints to stderr).
fn team_id(bundle: &Path) -> Option<String> {
    let out = Command::new("codesign").arg("-dv").arg(bundle).output().ok()?;
    String::from_utf8_lossy(&out.stderr)
        .lines()
        .find_map(|l| l.strip_prefix("TeamIdentifier="))
        .filter(|t| *t != "not set")
        .map(str::to_string)
}

fn uuid_v4() -> String {
    let mut b = [0u8; 16];
    if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
        let _ = std::io::Read::read_exact(&mut f, &mut b);
    }
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h: String = b.iter().map(|x| format!("{x:02X}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

static RUNS: AtomicU64 = AtomicU64::new(0);

/// Runs a command with stdin closed, killing it after `timeout`. `Ok` is (exit success, stdout, stderr).
fn run_with_timeout(mut cmd: Command, timeout: Duration) -> Result<(bool, String, String), String> {
    let child = cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| e.to_string())?;
    let pid = child.id() as libc::pid_t;
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(child.wait_with_output());
    });
    match rx.recv_timeout(timeout) {
        Ok(Ok(out)) => Ok((
            out.status.success(),
            String::from_utf8_lossy(&out.stdout).into_owned(),
            String::from_utf8_lossy(&out.stderr).into_owned(),
        )),
        Ok(Err(e)) => Err(e.to_string()),
        Err(_) => {
            // SAFETY: kill(2) on the child we spawned; it has not been reaped, the waiting thread still holds it.
            unsafe { libc::kill(pid, libc::SIGKILL) };
            Err(format!("timed out after {} s", timeout.as_secs()))
        }
    }
}

impl Backend for MacBackend {
    fn functions(&self) -> Result<Vec<Function>, String> {
        let mut out = Vec::new();
        for b in self.bundles() {
            match read_json(&b.actionsdata) {
                Ok(data) => out.extend(parse_actions(&b.id, &data)),
                Err(e) => eprintln!("tin: {}: {e}", b.actionsdata.display()),
            }
        }
        Ok(out)
    }

    /// Runs the intent's shortcut. Parameters are not wired into the shortcut yet, so a call with any is refused.
    fn execute(&self, package: &str, function: &str, parameters: &str) -> Result<String, String> {
        let params: Value = serde_json::from_str(parameters).map_err(|e| format!("parameters: {e}"))?;
        if params.as_object().is_some_and(|o| !o.is_empty()) {
            return Err("this backend does not pass parameters to intents yet; call it with no arguments".into());
        }
        let name = shortcut_name(package, function);
        let out = std::env::temp_dir().join(format!("tin-{}-{}.out", std::process::id(), RUNS.fetch_add(1, Ordering::Relaxed)));
        let mut cmd = Command::new("shortcuts");
        cmd.args(["run", &name, "-o"]).arg(&out);
        let result = run_with_timeout(cmd, self.timeout);
        let output = std::fs::read_to_string(&out).unwrap_or_default();
        let _ = std::fs::remove_file(&out);
        let (ok, stdout, stderr) = result.map_err(|e| format!("shortcuts run {name:?}: {e}"))?;
        if !ok {
            let err = if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() };
            if err.contains("Couldn’t find shortcut") || err.contains("Couldn't find shortcut") {
                return Err(format!("no shortcut {name:?} in the Shortcuts library: run `tin shortcut {package} {function}` and open the file once to import it"));
            }
            return Err(format!("shortcuts run {name:?}: {err}"));
        }
        Ok(if output.trim().is_empty() { stdout.trim().to_string() } else { output.trim().to_string() })
    }

    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        run_argv(program, args)
    }

    fn verbs(&self) -> &'static [Verb] {
        VERBS
    }

    fn instructions(&self) -> &'static str {
        INSTRUCTIONS
    }
}

// System verbs.

pub const VERBS: &[Verb] = &[
    Verb { name: "memory", read_only: true, description: "RAM, memory pressure level (normal, warning, critical), swap in use, and page classes in MB.", ops: &MAC },
    Verb { name: "processes", read_only: true, description: "Processes with the most resident memory (RSS): pid, parent, MB, age, start time, command (`limit`, default 15).", ops: &MAC },
    Verb { name: "stale_processes", read_only: true, description: "Leftover build and test processes (MSBuild nodes, VBCSCompiler, vstest, test hosts) older than `min_age_minutes` (default 30), with swap in use. When swap in use is at least `swap_threshold_mb` (default 4096) it proposes a system__kill call for each.", ops: &MAC },
    Verb { name: "launch_agents", read_only: true, description: "launchd jobs in this login session: label, pid (null when not running), last exit status. Apple's own are left out unless `include_apple`; `prefix` filters labels.", ops: &MAC },
    Verb { name: "kill", read_only: false, description: "Send TERM (default), INT or KILL to a process. Takes `pid` and `started` exactly as system__processes or system__stale_processes print them; a pid whose start time changed is not killed.", ops: &MAC },
    Verb { name: "quit_app", read_only: false, description: "Ask an app to quit by bundle id (as File > Quit; the app may ask to save).", ops: &MAC },
    Verb { name: "unload_agent", read_only: false, description: "Unload a launchd agent of this user (`launchctl bootout`) until the next login or a bootstrap. Never an Apple agent.", ops: &MAC },
];

const MAC: Ops = Ops { schema, argv, describe, run };

pub fn verb(name: &str) -> Option<&'static Verb> {
    VERBS.iter().find(|v| v.name == name)
}

pub const DEFAULT_MIN_AGE_MINUTES: u64 = 30;
pub const DEFAULT_SWAP_THRESHOLD_MB: u64 = 4096;
const SIGNALS: &[&str] = &["TERM", "INT", "KILL"];
const PS: &[&str] = &["-axww", "-o", "pid=,ppid=,rss=,etime=,lstart=,command="];

fn schema(v: &Verb) -> Value {
    let (props, required): (Value, &[&str]) = match v.name {
        "processes" => (json!({ "limit": { "type": "integer", "minimum": 1, "maximum": 200 } }), &[]),
        "stale_processes" => (
            json!({
                "min_age_minutes": { "type": "integer", "minimum": 0, "description": "only processes at least this old" },
                "swap_threshold_mb": { "type": "integer", "minimum": 0, "description": "propose kills when swap in use is at least this" }
            }),
            &[],
        ),
        "launch_agents" => (json!({ "prefix": { "type": "string" }, "include_apple": { "type": "boolean" } }), &[]),
        "kill" => (
            json!({
                "pid": { "type": "integer", "minimum": 2 },
                "started": { "type": "string", "description": "the process's start time as listed, e.g. \"Sun Nov 1 18:51:12 2026\"" },
                "signal": { "type": "string", "enum": SIGNALS }
            }),
            &["pid", "started"],
        ),
        "quit_app" => (json!({ "bundle_id": { "type": "string", "pattern": "^[A-Za-z0-9.-]+$" } }), &["bundle_id"]),
        "unload_agent" => (json!({ "label": { "type": "string", "pattern": "^[A-Za-z0-9._-]+$" } }), &["label"]),
        _ => (json!({}), &[]),
    };
    json!({ "type": "object", "properties": props, "required": required })
}

fn string<'a>(args: &'a Value, k: &str) -> Result<&'a str, String> {
    args.get(k).and_then(Value::as_str).ok_or_else(|| format!("{k}: a string is required"))
}

fn pid(args: &Value) -> Result<i64, String> {
    let p = args.get("pid").and_then(Value::as_i64).ok_or("pid: an integer is required")?;
    if !(2..=99_999_999).contains(&p) {
        return Err(format!("pid: {p} is out of range"));
    }
    if p == std::process::id() as i64 {
        return Err("pid: that is tin itself".into());
    }
    Ok(p)
}

/// `ps` pads the day of month; compare start times word by word.
fn normalize(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn started(args: &Value) -> Result<String, String> {
    let s = normalize(string(args, "started")?);
    if s.is_empty() || s.len() > 40 || !s.chars().all(|c| c.is_ascii_alphanumeric() || c == ' ' || c == ':') {
        return Err(format!("started: {s:?} is not a start time from ps"));
    }
    Ok(s)
}

fn argv(v: &Verb, args: &Value) -> Result<Vec<String>, String> {
    match v.name {
        "kill" => {
            let p = pid(args)?;
            started(args)?;
            let sig = args.get("signal").map(|s| s.as_str().ok_or("signal: a string is required")).transpose()?.unwrap_or("TERM");
            if !SIGNALS.contains(&sig) {
                return Err(format!("signal: {sig:?} is not TERM, INT or KILL"));
            }
            Ok(vec!["kill".into(), format!("-{sig}"), p.to_string()])
        }
        "quit_app" => {
            let b = string(args, "bundle_id")?;
            if b.len() < 3 || b.len() > 155 || !b.contains('.') || !b.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-') {
                return Err(format!("bundle_id: {b:?} is not a bundle id"));
            }
            Ok(vec!["osascript".into(), "-e".into(), format!("tell application id \"{b}\" to quit")])
        }
        "unload_agent" => {
            let l = string(args, "label")?;
            if l.is_empty() || l.len() > 200 || !l.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)) {
                return Err(format!("label: {l:?} is not a launchd label"));
            }
            if l.starts_with("com.apple.") {
                return Err(format!("label: {l} is Apple's; tin never unloads Apple agents"));
            }
            // SAFETY: getuid(2) cannot fail.
            let uid = unsafe { libc::getuid() };
            Ok(vec!["launchctl".into(), "bootout".into(), format!("gui/{uid}/{l}")])
        }
        other => Err(format!("{other} is not a write verb")),
    }
}

fn describe(v: &Verb, args: &Value, shell: Shell) -> String {
    let s = |k: &str| args.get(k).map(|v| v.as_str().map(str::to_string).unwrap_or_else(|| v.to_string())).unwrap_or_default();
    match v.name {
        "kill" => {
            let sig = args.get("signal").and_then(Value::as_str).unwrap_or("TERM");
            let what = shell("ps", &["-p".into(), s("pid"), "-o".into(), "rss=,command=".into()])
                .ok()
                .and_then(|out| {
                    let line = out.lines().next()?.trim().to_string();
                    let (rss, cmd) = line.split_once(char::is_whitespace)?;
                    let mb = rss.parse::<u64>().ok()? / 1024;
                    Some(format!("{}, {mb} MB", clip(short_command(cmd.trim()), 80)))
                })
                .unwrap_or_else(|| "not running".into());
            format!("kill -{sig} {} ({what})", s("pid"))
        }
        "quit_app" => format!("quit {}", s("bundle_id")),
        "unload_agent" => format!("unload launchd agent {}", s("label")),
        other => other.to_string(),
    }
}

fn run(v: &Verb, args: &Value, shell: Shell) -> Result<Value, String> {
    let call = |argv: &[&str]| shell(argv[0], &argv[1..].iter().map(|s| s.to_string()).collect::<Vec<_>>());
    match v.name {
        "memory" => {
            let mut m = parse_sysctl(&call(&["sysctl", "hw.memsize", "vm.swapusage", "kern.memorystatus_vm_pressure_level"])?);
            m["pages_mb"] = parse_vm_stat(&call(&["vm_stat"])?);
            Ok(m)
        }
        "processes" => {
            let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(15).clamp(1, 200) as usize;
            let mut procs = parse_ps(&call(&[&["ps"], PS].concat())?);
            procs.sort_by_key(|p| std::cmp::Reverse(p.rss_kb));
            Ok(json!({ "processes": procs.iter().take(limit).map(Proc::to_json).collect::<Vec<_>>() }))
        }
        "stale_processes" => {
            let min_age = args.get("min_age_minutes").and_then(Value::as_u64).unwrap_or(DEFAULT_MIN_AGE_MINUTES);
            let threshold = args.get("swap_threshold_mb").and_then(Value::as_u64).unwrap_or(DEFAULT_SWAP_THRESHOLD_MB);
            let swap = parse_sysctl(&call(&["sysctl", "vm.swapusage"])?)["swap"].clone();
            let procs = parse_ps(&call(&[&["ps"], PS].concat())?);
            Ok(stale_report(&procs, &swap, min_age, threshold))
        }
        "launch_agents" => {
            let prefix = args.get("prefix").and_then(Value::as_str).unwrap_or("");
            let apple = args.get("include_apple").and_then(Value::as_bool).unwrap_or(false);
            let agents: Vec<Value> = parse_launchctl(&call(&["launchctl", "list"])?)
                .into_iter()
                .filter(|a| {
                    let l = a["label"].as_str().unwrap_or("");
                    l.starts_with(prefix) && (apple || !l.starts_with("com.apple."))
                })
                .collect();
            Ok(json!({ "agents": agents }))
        }
        "kill" => {
            let argv = argv(v, args)?;
            let want = started(args)?;
            let now = call(&["ps", "-p", &argv[2], "-o", "lstart="]).map(|s| normalize(&s)).unwrap_or_default();
            if now != want {
                return Err(format!("pid {} started {now:?}, not {want:?}: a different process or gone; not killed", argv[2]));
            }
            let out = shell(&argv[0], &argv[1..])?;
            Ok(json!({ "ok": true, "output": out.trim() }))
        }
        _ => {
            let argv = argv(v, args)?;
            let out = shell(&argv[0], &argv[1..])?;
            Ok(json!({ "ok": true, "output": out.trim() }))
        }
    }
}

fn clip(s: &str, n: usize) -> String {
    if s.chars().count() <= n { s.to_string() } else { format!("{}…", s.chars().take(n).collect::<String>()) }
}

/// The program's file name and its first arguments, without the directory: enough to recognise it.
fn short_command(cmd: &str) -> &str {
    let first_end = cmd.find(' ').unwrap_or(cmd.len());
    let start = cmd[..first_end].rfind('/').map_or(0, |i| i + 1);
    &cmd[start..]
}

/// `sysctl hw.memsize vm.swapusage kern.memorystatus_vm_pressure_level` output.
pub fn parse_sysctl(text: &str) -> Value {
    let mut out = json!({});
    for line in text.lines() {
        let Some((k, v)) = line.split_once(':') else { continue };
        let v = v.trim();
        match k.trim() {
            "hw.memsize" => out["ram_mb"] = json!(v.parse::<u64>().ok().map(|b| b / 1024 / 1024)),
            "vm.swapusage" => out["swap"] = parse_swapusage(v),
            "kern.memorystatus_vm_pressure_level" => {
                out["pressure"] = json!(match v {
                    "1" => "normal",
                    "2" => "warning",
                    "4" => "critical",
                    _ => "unknown",
                })
            }
            _ => {}
        }
    }
    out
}

/// `total = 7168.00M  used = 6313.44M  free = 854.56M  (encrypted)` to whole MB.
pub fn parse_swapusage(v: &str) -> Value {
    let field = |name: &str| {
        let rest = v.split(&format!("{name} = ")).nth(1)?;
        let num = rest.split_whitespace().next()?;
        let (n, unit) = num.split_at(num.len().checked_sub(1)?);
        let n: f64 = n.parse().ok()?;
        let mb = match unit {
            "K" => n / 1024.0,
            "M" => n,
            "G" => n * 1024.0,
            _ => return None,
        };
        Some(mb.round() as u64)
    };
    json!({ "total_mb": field("total"), "used_mb": field("used"), "free_mb": field("free") })
}

/// `vm_stat` page counts to MB, keyed in snake case (`pages_free` → `free`).
pub fn parse_vm_stat(text: &str) -> Value {
    let page = text
        .lines()
        .next()
        .and_then(|l| l.split("page size of ").nth(1))
        .and_then(|r| r.split_whitespace().next())
        .and_then(|n| n.parse::<u64>().ok())
        .unwrap_or(16384);
    let keys = [
        ("Pages free", "free"),
        ("Pages active", "active"),
        ("Pages inactive", "inactive"),
        ("Pages speculative", "speculative"),
        ("Pages wired down", "wired"),
        ("Pages purgeable", "purgeable"),
        ("Pages occupied by compressor", "compressor"),
        ("File-backed pages", "file_backed"),
        ("Anonymous pages", "anonymous"),
    ];
    let mut out = Map::new();
    for line in text.lines() {
        let Some((k, v)) = line.split_once(':') else { continue };
        if let Some((_, name)) = keys.iter().find(|(label, _)| *label == k.trim())
            && let Ok(n) = v.trim().trim_end_matches('.').parse::<u64>()
        {
            out.insert((*name).into(), json!(n * page / 1024 / 1024));
        }
    }
    Value::Object(out)
}

/// One `ps -o pid=,ppid=,rss=,etime=,lstart=,command=` row.
#[derive(Debug, Clone, PartialEq)]
pub struct Proc {
    pub pid: i64,
    pub ppid: i64,
    pub rss_kb: u64,
    pub age_secs: u64,
    pub started: String,
    pub command: String,
}

impl Proc {
    fn to_json(&self) -> Value {
        json!({
            "pid": self.pid,
            "ppid": self.ppid,
            "rss_mb": self.rss_kb / 1024,
            "age_minutes": self.age_secs / 60,
            "started": self.started,
            "command": clip(&self.command, 160),
        })
    }
}

/// `[[dd-]hh:]mm:ss` to seconds.
pub fn parse_etime(s: &str) -> Option<u64> {
    let (days, rest) = match s.split_once('-') {
        Some((d, r)) => (d.parse::<u64>().ok()?, r),
        None => (0, s),
    };
    let parts: Vec<u64> = rest.split(':').map(|p| p.parse().ok()).collect::<Option<_>>()?;
    let (h, m, sec) = match parts.as_slice() {
        [m, s] => (0, *m, *s),
        [h, m, s] => (*h, *m, *s),
        _ => return None,
    };
    Some(days * 86_400 + h * 3600 + m * 60 + sec)
}

pub fn parse_ps(text: &str) -> Vec<Proc> {
    text.lines()
        .filter_map(|line| {
            let mut it = line.split_whitespace();
            let pid = it.next()?.parse().ok()?;
            let ppid = it.next()?.parse().ok()?;
            let rss_kb = it.next()?.parse().ok()?;
            let age_secs = parse_etime(it.next()?)?;
            let started: Vec<&str> = it.by_ref().take(5).collect();
            if started.len() != 5 {
                return None;
            }
            let command = it.collect::<Vec<_>>().join(" ");
            Some(Proc { pid, ppid, rss_kb, age_secs, started: started.join(" "), command })
        })
        .collect()
}

/// What kind of leftover build or test process a command line is, if any.
pub fn stale_kind(command: &str) -> Option<&'static str> {
    let argv0 = command.split_whitespace().next().unwrap_or("");
    let program = argv0.rsplit('/').next().unwrap_or(argv0);
    let lower = command.to_ascii_lowercase();
    if lower.contains("msbuild.dll") && (lower.contains("nodemode:") || lower.contains("nodereuse:")) {
        Some("msbuild_node")
    } else if lower.contains("vbcscompiler") {
        Some("vbcscompiler")
    } else if lower.contains("vstest.console") {
        Some("vstest")
    } else if lower.contains("testhost") {
        Some("testhost")
    } else if program.ends_with(".Tests") || lower.contains("--server dotnettestcli") {
        Some("test_host")
    } else {
        None
    }
}

/// The governor's report: stale processes, swap, and kill proposals when swap in use is at or over the threshold.
pub fn stale_report(procs: &[Proc], swap: &Value, min_age_minutes: u64, swap_threshold_mb: u64) -> Value {
    let mut stale: Vec<(&Proc, &str)> = procs
        .iter()
        .filter(|p| p.age_secs >= min_age_minutes * 60)
        .filter_map(|p| stale_kind(&p.command).map(|k| (p, k)))
        .collect();
    stale.sort_by_key(|(p, _)| std::cmp::Reverse(p.rss_kb));
    let used = swap["used_mb"].as_u64();
    let over = used.is_some_and(|u| u >= swap_threshold_mb);
    let reclaim: u64 = stale.iter().map(|(p, _)| p.rss_kb / 1024).sum();
    let list: Vec<Value> = stale
        .iter()
        .map(|(p, k)| {
            let mut j = p.to_json();
            j["kind"] = json!(k);
            j
        })
        .collect();
    let proposals: Vec<Value> = if over {
        stale.iter().map(|(p, _)| json!({ "tool": "system__kill", "arguments": { "pid": p.pid, "started": p.started } })).collect()
    } else {
        Vec::new()
    };
    json!({
        "swap": swap,
        "swap_threshold_mb": swap_threshold_mb,
        "over_threshold": over,
        "min_age_minutes": min_age_minutes,
        "stale": list,
        "stale_rss_mb": reclaim,
        "proposals": proposals,
    })
}

/// `launchctl list`: `PID\tStatus\tLabel` rows after a header.
pub fn parse_launchctl(text: &str) -> Vec<Value> {
    text.lines()
        .skip_while(|l| l.starts_with("PID"))
        .filter_map(|l| {
            let mut it = l.split('\t');
            let pid = it.next()?.trim();
            let status = it.next()?.trim();
            let label = it.next()?.trim();
            Some(json!({ "label": label, "pid": pid.parse::<i64>().ok(), "last_exit": status.parse::<i64>().ok() }))
        })
        .collect()
}
