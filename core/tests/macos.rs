//! Acceptance checks M1, M3 and M4 from docs/specs/2026-11-02-macos-backend.md, run without touching this Mac: a fake
//! backend answers `ps`, `sysctl`, `vm_stat` and `launchctl` from synthetic outputs, and a fake approvals server records
//! items. Every test name starts with `macos_`.

use serde_json::{Value, json};
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tin_core::backend::Backend;
use tin_core::gate::{self, Approver, Gate, GateConfig};
use tin_core::macos::{self, MacBackend, VERBS};
use tin_core::mcp::{Server, WAIT_TOOL};
use tin_core::metadata::Function;
use tin_core::policy::{Decision, Policy};
use tin_core::system::Verb;

const KEY: &[u8] = b"test-approval-key";
const BUNDLE: &str = "com.example.Counter";

fn synthetic(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/fixtures/synthetic/macos/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn actionsdata() -> Value {
    serde_json::from_str(&synthetic("extract.actionsdata")).unwrap()
}

/// Answers system programs from fixtures and records every program it runs. `restarted` makes `ps -p` report a
/// different start time, as when a pid is reused.
#[derive(Clone, Default)]
struct Mac {
    calls: Arc<Mutex<Vec<Vec<String>>>>,
    restarted: bool,
    calm: bool,
}

impl Mac {
    fn ran(&self, program: &str) -> Vec<Vec<String>> {
        self.calls.lock().unwrap().iter().filter(|c| c[0] == program).cloned().collect()
    }
}

impl Backend for Mac {
    fn functions(&self) -> Result<Vec<Function>, String> {
        Ok(macos::parse_actions(BUNDLE, &actionsdata()))
    }
    fn execute(&self, _: &str, _: &str, _: &str) -> Result<String, String> {
        Err("no shortcuts on this fake".into())
    }
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let mut call = vec![program.to_string()];
        call.extend(args.iter().cloned());
        self.calls.lock().unwrap().push(call);
        let a: Vec<&str> = args.iter().map(String::as_str).collect();
        Ok(match (program, a.as_slice()) {
            ("ps", ["-p", pid, "-o", fields]) => {
                let Some(p) = macos::parse_ps(&synthetic("ps.txt")).into_iter().find(|p| p.pid.to_string() == *pid) else {
                    return Ok(String::new());
                };
                match *fields {
                    "lstart=" if self.restarted => "Mon Nov  2 09:59:59 2026\n".into(),
                    "lstart=" => format!("{}\n", p.started.replace("Nov 1 ", "Nov  1 ")),
                    _ => format!("{} {}\n", p.rss_kb, p.command),
                }
            }
            ("ps", _) => synthetic("ps.txt"),
            ("sysctl", _) if self.calm => synthetic("sysctl-calm.txt"),
            ("sysctl", _) => synthetic("sysctl.txt"),
            ("vm_stat", _) => synthetic("vm_stat.txt"),
            ("launchctl", ["list"]) => synthetic("launchctl.txt"),
            _ => String::new(),
        })
    }
    fn verbs(&self) -> &'static [Verb] {
        VERBS
    }
    fn instructions(&self) -> &'static str {
        macos::INSTRUCTIONS
    }
}

#[derive(Clone, Default)]
struct Inbox {
    items: Arc<Mutex<Vec<Value>>>,
}

impl Approver for Inbox {
    fn put(&self, _: &str, item: &Value) -> Result<(), String> {
        self.items.lock().unwrap().push(item.clone());
        Ok(())
    }
    fn delete(&self, _: &str) -> Result<(), String> {
        Ok(())
    }
}

impl Inbox {
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

const ALL: &str = "[[allow]]\npackage = \"system\"\nfunction = \"*\"\n\n[[allow]]\npackage = \"com.example.Counter\"\nfunction = \"GetCountIntent\"\nread_only = true\n\n[[allow]]\npackage = \"com.example.Counter\"\nfunction = \"OpenThingIntent\"\n";

fn gated(mac: Mac) -> (Arc<Server>, Mac, Inbox) {
    let inbox = Inbox::default();
    let cfg = GateConfig {
        source: "tin".into(),
        callback: "https://approvals.example/cb/tin/decision".into(),
        wait: Duration::from_secs(5),
        ttl: Duration::from_secs(60),
    };
    let gate = Arc::new(Gate::new(Box::new(inbox.clone()), KEY, cfg));
    let server = Server::new(Box::new(mac.clone()), Policy::from_toml(ALL).unwrap()).with_gate(gate);
    (Arc::new(server), mac, inbox)
}

fn call(s: &Server, name: &str, args: Value) -> Value {
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": name, "arguments": args } })).unwrap();
    r["result"].clone()
}

fn text(result: &Value) -> &str {
    result["content"][0]["text"].as_str().unwrap()
}

fn data(result: &Value) -> Value {
    assert_eq!(result["isError"], false, "{result}");
    serde_json::from_str(text(result)).unwrap()
}

fn signed(id: &str, action: &str) -> Vec<u8> {
    let (decision, at) = ("01JB0000000000000000000009", "2026-11-02T12:00:00.000Z");
    let sig = gate::sign(KEY, decision, id, action, at, &json!({}));
    json!({ "decision": decision, "id": id, "action": action, "edited": {}, "at": at, "sig": sig }).to_string().into_bytes()
}

/// A held call on another thread, the item it opened, and the caller to join after a decision.
fn held(s: &Arc<Server>, inbox: &Inbox, tool: &'static str, args: Value) -> (String, std::thread::JoinHandle<Value>) {
    let s2 = s.clone();
    let caller = std::thread::spawn(move || call(&s2, tool, args));
    (inbox.nth(0)["id"].as_str().unwrap().to_string(), caller)
}

// M1: the App Intents catalog.

#[test]
fn macos_parse_maps_every_value_kind() {
    let fs = macos::parse_actions(BUNDLE, &actionsdata());
    let ids: Vec<&str> = fs.iter().map(|f| f.id.as_str()).collect();
    assert_eq!(ids, ["GetCountIntent", "OpenThingIntent"], "both kinds of non-discoverable action are left out");
    assert_eq!(macos::action_count(&actionsdata()), 4);
    let f = &fs[0];
    assert_eq!(f.package, BUNDLE);
    assert_eq!(f.description, "Get Count. Counts things.");
    let s = &f.input_schema;
    assert_eq!(s["type"], "object");
    assert_eq!(s["required"], json!(["text"]));
    let p = &s["properties"];
    assert_eq!(p["text"]["type"], "string");
    assert!(p["text"].get("default").is_none(), "an empty default is no default");
    assert_eq!(p["flag"], json!({ "type": "boolean", "default": true, "description": "Flag" }));
    assert_eq!(p["count"], json!({ "type": "integer", "minimum": 1, "maximum": 52, "default": 7, "description": "Count: How many." }));
    assert_eq!(p["ratio"]["type"], "number");
    assert_eq!(p["when"]["format"], "date-time");
    assert!(p["day"]["description"].as_str().unwrap().contains("ISO 8601"));
    assert_eq!(p["link"]["format"], "uri");
    assert_eq!(p["rich"]["type"], "string");
    assert_eq!(p["unit"]["enum"], json!(["week", "day"]));
    assert_eq!(p["unit"]["default"], "week");
    assert_eq!(p["note"]["description"], "NoteEntity id");
    assert_eq!(p["days"]["type"], "array");
    assert_eq!(p["days"]["items"]["enum"], json!(["week", "day"]));
    assert_eq!(p["either"]["anyOf"][0]["type"], "string");
    assert_eq!(p["either"]["anyOf"][1]["type"], "string");
    assert_eq!(p["query"]["description"], "search text");
    assert_eq!(p["file"]["description"], "File (file path) [types: public.image]");
    assert_eq!(p["app"]["description"], "app bundle id");
    assert_eq!(p["builtin"]["type"], "string");
    assert_eq!(p["length"]["type"], "number");
    assert_eq!(p["odd"]["description"], "somethingNew value", "an unknown kind stays unconstrained");
    assert_eq!(fs[1].description, "[opens the app] Open Thing");
    assert_eq!(fs[1].input_schema, json!({ "type": "object", "properties": {}, "required": [] }));
}

#[test]
fn macos_tools_list_names_notes_and_instructions() {
    let (s, _, _) = gated(Mac::default());
    let init = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "initialize" })).unwrap();
    assert!(init["result"]["instructions"].as_str().unwrap().contains("App Intents"));
    let r = s.handle(&json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" })).unwrap();
    let tools = r["result"]["tools"].as_array().unwrap();
    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(tools.len(), VERBS.len() + 2 + 1, "7 verbs, 2 intents the policy names, approval__wait: {names:?}");
    let count = tools.iter().find(|t| t["name"] == "example_Counter__GetCountIntent").unwrap();
    assert!(!count["description"].as_str().unwrap().contains("approvals app"), "marked read-only in the policy");
    let open = tools.iter().find(|t| t["name"] == "example_Counter__OpenThingIntent").unwrap();
    assert!(open["description"].as_str().unwrap().starts_with("[waits for a tap in the approvals app] [opens the app]"));
    for v in VERBS {
        let t = tools.iter().find(|t| t["name"] == v.tool_name()).unwrap();
        assert_eq!(t["description"].as_str().unwrap().contains("approvals app"), !v.read_only, "{}", v.name);
        assert_eq!(t["inputSchema"]["type"], "object");
    }
    assert!(names.contains(&WAIT_TOOL));
}

#[test]
fn macos_money_rule_applies_unchanged() {
    let f = |id: &str| Function {
        package: BUNDLE.into(),
        id: id.into(),
        description: String::new(),
        input_schema: json!({}),
        enabled_by_default: true,
    };
    let p = Policy::from_toml("[[allow]]\npackage = \"com.example.Counter\"\nfunction = \"*\"\nread_only = true\n").unwrap();
    assert_eq!(p.decide(&f("SendPaymentIntent")), Decision::Refuse);
    assert_eq!(p.decide(&f("BuyTicketIntent")), Decision::Refuse);
    assert_eq!(p.decide(&f("GetCountIntent")), Decision::Allow);
}

#[test]
fn macos_execute_refuses_parameters_before_running() {
    let b = MacBackend { roots: vec![], timeout: Duration::from_secs(1) };
    let e = b.execute(BUNDLE, "GetCountIntent", r#"{"count":3}"#).unwrap_err();
    assert!(e.contains("does not pass parameters"), "{e}");
    assert_eq!(macos::shortcut_name(BUNDLE, "GetCountIntent"), "tin com.example.Counter.GetCountIntent");
}

#[test]
fn macos_shortcut_plist_is_valid_and_escaped() {
    let xml = macos::shortcut_plist(BUNDLE, "GetCountIntent", "Count & <Co>", Some("ABCDE12345"), "11111111-2222-4333-8444-555555555555");
    let opts = roxmltree::ParsingOptions { allow_dtd: true, ..Default::default() };
    let doc = roxmltree::Document::parse_with_options(&xml, opts).expect("well-formed plist");
    let strings: Vec<&str> = doc.descendants().filter(|n| n.has_tag_name("string")).filter_map(|n| n.text()).collect();
    for want in ["com.example.Counter.GetCountIntent", "Count & <Co>", "ABCDE12345", "is.workflow.actions.output"] {
        assert!(strings.contains(&want), "{want} missing from {strings:?}");
    }
    let no_team = macos::shortcut_plist(BUNDLE, "GetCountIntent", "Counter", None, "u");
    assert!(!no_team.contains("TeamIdentifier"));
}

fn write(path: &Path, text: &str) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, text).unwrap();
}

fn info(id: &str) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><dict><key>CFBundleName</key><string>X</string><key>CFBundleIdentifier</key><string>{id}</string></dict></plist>\n"
    )
}

#[test]
fn macos_scan_finds_apps_extensions_frameworks_and_link_data() {
    let root = std::env::temp_dir().join(format!("tin-scan-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let data = synthetic("extract.actionsdata");
    let app = root.join("Counter.app");
    write(&app.join("Contents/Info.plist"), &info("com.example.Counter"));
    write(&app.join("Contents/Resources/Metadata.appintents/extract.actionsdata"), &data);
    write(&app.join("Contents/Resources/Link.data/extract.actionsdata"), &data);
    let ext = app.join("Contents/PlugIns/Widget.appex");
    write(&ext.join("Contents/Info.plist"), &info("com.example.Counter.Widget"));
    write(&ext.join("Contents/Resources/Metadata.appintents/extract.actionsdata"), &data);
    let fw = app.join("Contents/Frameworks/Intents.framework");
    write(&fw.join("Versions/A/Resources/Info.plist"), &info("com.example.CounterIntents"));
    write(&fw.join("Versions/A/Resources/Metadata.appintents/extract.actionsdata"), &data);
    let util = root.join("Utilities/Tool.app");
    write(&util.join("Contents/Info.plist"), &info("com.example.Tool"));
    write(&util.join("Contents/Resources/Metadata.appintents/extract.actionsdata"), &data);
    // No Info.plist: skipped. No metadata: skipped.
    write(&root.join("Broken.app/Contents/Resources/Metadata.appintents/extract.actionsdata"), &data);
    write(&root.join("Plain.app/Contents/Info.plist"), &info("com.example.Plain"));

    let mut ids: Vec<String> = macos::scan(std::slice::from_ref(&root)).into_iter().map(|b| b.id).collect();
    ids.sort();
    assert_eq!(ids, ["com.example.Counter", "com.example.Counter", "com.example.Counter.Widget", "com.example.CounterIntents", "com.example.Tool"]);
    let b = MacBackend { roots: vec![root.clone()], timeout: Duration::from_secs(1) };
    assert_eq!(b.functions().unwrap().len(), 10, "2 discoverable actions in each of 5 files");
    std::fs::remove_dir_all(&root).unwrap();
}

#[test]
fn macos_plist_string_reads_top_level_keys() {
    let xml = info("com.example.Counter");
    assert_eq!(macos::plist_string(&xml, "CFBundleIdentifier").as_deref(), Some("com.example.Counter"));
    assert_eq!(macos::plist_string(&xml, "CFBundleName").as_deref(), Some("X"));
    assert_eq!(macos::plist_string(&xml, "Missing"), None);
    assert_eq!(macos::plist_string("not xml", "CFBundleIdentifier"), None);
}

// M3: Mac system tools.

#[test]
fn macos_registry_has_four_read_and_three_write_verbs() {
    let read: Vec<&str> = VERBS.iter().filter(|v| v.read_only).map(|v| v.name).collect();
    assert_eq!(read, ["memory", "processes", "stale_processes", "launch_agents"]);
    let write: Vec<&str> = VERBS.iter().filter(|v| !v.read_only).map(|v| v.name).collect();
    assert_eq!(write, ["kill", "quit_app", "unload_agent"]);
}

#[test]
fn macos_parse_memory() {
    let m = macos::parse_sysctl(&synthetic("sysctl.txt"));
    assert_eq!(m["ram_mb"], 16384);
    assert_eq!(m["pressure"], "warning");
    assert_eq!(m["swap"], json!({ "total_mb": 7168, "used_mb": 6313, "free_mb": 855 }));
    assert_eq!(macos::parse_swapusage("total = 2.00G  used = 512.00K  free = 2.00G")["total_mb"], 2048);
    let pages = macos::parse_vm_stat(&synthetic("vm_stat.txt"));
    assert_eq!(pages["free"], 4179 * 16384 / 1024 / 1024);
    assert_eq!(pages["active"], 300_000 * 16 / 1024);
    assert_eq!(pages["compressor"], 4000);
    assert!(pages.get("Translation faults").is_none());
}

#[test]
fn macos_parse_processes() {
    assert_eq!(macos::parse_etime("05:00"), Some(300));
    assert_eq!(macos::parse_etime("01:00:05"), Some(3605));
    assert_eq!(macos::parse_etime("3-01:00:00"), Some(3 * 86_400 + 3600));
    assert_eq!(macos::parse_etime("x"), None);
    let ps = macos::parse_ps(&synthetic("ps.txt"));
    assert_eq!(ps.len(), 13);
    let node = ps.iter().find(|p| p.pid == 2001).unwrap();
    assert_eq!((node.ppid, node.rss_kb, node.age_secs), (1, 420_000, 93_600));
    assert_eq!(node.started, "Sun Nov 1 07:00:00 2026");
    assert!(node.command.ends_with("/nodemode:1 /nodeReuse:true /low:false"));
    let code = ps.iter().find(|p| p.pid == 2200).unwrap();
    assert_eq!(code.command, "/Applications/Visual Studio Code.app/Contents/MacOS/Electron", "spaces in a path survive");
}

#[test]
fn macos_stale_classification() {
    let kind = |pid: i64| {
        let ps = macos::parse_ps(&synthetic("ps.txt"));
        macos::stale_kind(&ps.iter().find(|p| p.pid == pid).unwrap().command)
    };
    assert_eq!(kind(2001), Some("msbuild_node"));
    assert_eq!(kind(2010), None, "the build's own MSBuild is not a node");
    assert_eq!(kind(2020), Some("vbcscompiler"));
    assert_eq!(kind(2030), Some("vstest"));
    assert_eq!(kind(2031), Some("testhost"));
    assert_eq!(kind(2040), Some("test_host"));
    assert_eq!(kind(2100), None, "dotnet build itself");
    assert_eq!(kind(512), None);
}

#[test]
fn macos_stale_report_proposes_only_over_threshold() {
    let ps = macos::parse_ps(&synthetic("ps.txt"));
    let swap = macos::parse_sysctl(&synthetic("sysctl.txt"))["swap"].clone();
    let r = macos::stale_report(&ps, &swap, 30, 4096);
    let pids: Vec<i64> = r["stale"].as_array().unwrap().iter().map(|p| p["pid"].as_i64().unwrap()).collect();
    assert_eq!(pids, [2040, 2031, 2020, 2001, 2002, 2030], "by RSS; the 5-minute node is too young");
    assert_eq!(r["over_threshold"], true);
    assert_eq!(r["stale_rss_mb"], 859 + 595 + 498 + 410 + 371 + 195, "the sum of each process's whole MB");
    let proposals = r["proposals"].as_array().unwrap();
    assert_eq!(proposals.len(), 6);
    assert_eq!(proposals[0], json!({ "tool": "system__kill", "arguments": { "pid": 2040, "started": "Sat Oct 31 09:10:00 2026" } }));
    let calm = macos::parse_sysctl(&synthetic("sysctl-calm.txt"))["swap"].clone();
    let r = macos::stale_report(&ps, &calm, 30, 4096);
    assert_eq!(r["over_threshold"], false);
    assert_eq!(r["stale"].as_array().unwrap().len(), 6, "still listed");
    assert_eq!(r["proposals"], json!([]), "nothing proposed under the threshold");
    let young = macos::stale_report(&ps, &swap, 0, 0);
    assert_eq!(young["stale"].as_array().unwrap().len(), 7);
}

#[test]
fn macos_parse_launch_agents() {
    let a = macos::parse_launchctl(&synthetic("launchctl.txt"));
    assert_eq!(a.len(), 4);
    assert_eq!(a[0], json!({ "label": "com.example.sync", "pid": null, "last_exit": 0 }));
    assert_eq!(a[1]["pid"], 4242);
    assert_eq!(a[2]["last_exit"], -9);
}

#[test]
fn macos_read_tools_end_to_end() {
    let (s, mac, inbox) = gated(Mac::default());
    let m = data(&call(&s, "system__memory", json!({})));
    assert_eq!(m["swap"]["used_mb"], 6313);
    assert_eq!(m["pages_mb"]["wired"], 150_000 * 16 / 1024);
    let p = data(&call(&s, "system__processes", json!({ "limit": 3 })));
    let top: Vec<i64> = p["processes"].as_array().unwrap().iter().map(|p| p["pid"].as_i64().unwrap()).collect();
    assert_eq!(top, [401, 2040, 512]);
    let st = data(&call(&s, "system__stale_processes", json!({ "swap_threshold_mb": 8000 })));
    assert_eq!(st["over_threshold"], false);
    let la = data(&call(&s, "system__launch_agents", json!({})));
    let labels: Vec<&str> = la["agents"].as_array().unwrap().iter().map(|a| a["label"].as_str().unwrap()).collect();
    assert_eq!(labels, ["com.example.sync", "com.example.agent", "io.example.helper"], "Apple's left out by default");
    let la = data(&call(&s, "system__launch_agents", json!({ "prefix": "com.", "include_apple": true })));
    assert_eq!(la["agents"].as_array().unwrap().len(), 3);
    assert!(inbox.items.lock().unwrap().is_empty(), "reads never ask");
    assert!(mac.ran("kill").is_empty());
}

// M4: held writes.

#[test]
fn macos_kill_waits_for_a_tap_then_runs_once() {
    let (s, mac, inbox) = gated(Mac::default());
    let (id, caller) = held(&s, &inbox, "system__kill", json!({ "pid": 2040, "started": "Sat Oct 31  09:10:00 2026" }));
    let item = inbox.nth(0);
    let title = item["title"].as_str().unwrap();
    assert!(title.contains("kill -TERM 2040") && title.contains("App.Claims.Tests") && title.contains("859 MB"), "{title}");
    assert!(mac.ran("kill").is_empty(), "nothing is killed before the decision");
    assert_eq!(s.gate().unwrap().decide(&signed(&id, "run")).0, 200);
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], false, "{r}");
    assert_eq!(mac.ran("kill"), [vec!["kill", "-TERM", "2040"]]);
}

#[test]
fn macos_kill_refuses_a_reused_pid() {
    let (s, mac, inbox) = gated(Mac { restarted: true, ..Default::default() });
    let (id, caller) = held(&s, &inbox, "system__kill", json!({ "pid": 2001, "started": "Sun Nov 1 07:00:00 2026", "signal": "KILL" }));
    s.gate().unwrap().decide(&signed(&id, "run"));
    let r = caller.join().unwrap();
    assert_eq!(r["isError"], true);
    assert!(text(&r).contains("not killed"), "{}", text(&r));
    assert!(mac.ran("kill").is_empty());
}

#[test]
fn macos_deny_never_kills() {
    let (s, mac, inbox) = gated(Mac::default());
    let (id, caller) = held(&s, &inbox, "system__kill", json!({ "pid": 2001, "started": "Sun Nov 1 07:00:00 2026" }));
    s.gate().unwrap().decide(&signed(&id, "deny"));
    assert_eq!(caller.join().unwrap()["isError"], true);
    assert!(mac.ran("kill").is_empty());
}

#[test]
fn macos_quit_app_and_unload_agent_run_after_a_tap() {
    let (s, mac, inbox) = gated(Mac::default());
    let (id, caller) = held(&s, &inbox, "system__quit_app", json!({ "bundle_id": "com.example.Counter" }));
    assert_eq!(inbox.nth(0)["title"], "tin: quit com.example.Counter");
    assert!(mac.ran("osascript").is_empty());
    s.gate().unwrap().decide(&signed(&id, "run"));
    assert_eq!(caller.join().unwrap()["isError"], false);
    assert_eq!(mac.ran("osascript"), [vec!["osascript", "-e", "tell application id \"com.example.Counter\" to quit"]]);

    let (s, mac, inbox) = gated(Mac::default());
    let (id, caller) = held(&s, &inbox, "system__unload_agent", json!({ "label": "com.example.sync" }));
    s.gate().unwrap().decide(&signed(&id, "run"));
    assert_eq!(caller.join().unwrap()["isError"], false);
    let uid = unsafe { libc::getuid() };
    assert_eq!(mac.ran("launchctl"), [vec!["launchctl".to_string(), "bootout".into(), format!("gui/{uid}/com.example.sync")]]);
}

#[test]
fn macos_bad_arguments_fail_before_anyone_is_asked() {
    let (s, mac, inbox) = gated(Mac::default());
    let bad = [
        ("system__unload_agent", json!({ "label": "com.apple.Finder" })),
        ("system__unload_agent", json!({ "label": "x; rm -rf ~" })),
        ("system__quit_app", json!({ "bundle_id": "com.example\" to quit\ndo shell script \"id" })),
        ("system__quit_app", json!({ "bundle_id": "nodots" })),
        ("system__kill", json!({ "pid": 1, "started": "Fri Oct 30 09:00:00 2026" })),
        ("system__kill", json!({ "pid": std::process::id(), "started": "Mon Nov 2 09:00:00 2026" })),
        ("system__kill", json!({ "pid": 2001 })),
        ("system__kill", json!({ "pid": 2001, "started": "$(id)" })),
        ("system__kill", json!({ "pid": 2001, "started": "Sun Nov 1 07:00:00 2026", "signal": "HUP" })),
    ];
    for (tool, args) in bad {
        let r = call(&s, tool, args.clone());
        assert_eq!(r["isError"], true, "{tool} {args}");
    }
    assert!(inbox.items.lock().unwrap().is_empty(), "no item for a call that can never run");
    assert!(mac.ran("kill").is_empty() && mac.ran("osascript").is_empty() && mac.ran("launchctl").is_empty());
}

#[test]
fn macos_shortcut_name_survives_the_import_file_name() {
    let name = macos::shortcut_name("com.sindresorhus.Week-Number", "GetWeekNumberIntent");
    assert_eq!(name, "tin com.sindresorhus.Week-Number.GetWeekNumberIntent");
    assert!(!name.contains([':', '/']), "Shortcuts drops these from the imported name");
}

#[test]
fn macos_shortcuts_run_asks_for_plain_text_output() {
    let args = macos::shortcuts_run_args("tin a.B", Path::new("/tmp/x.out"));
    let at = |flag: &str| args.iter().position(|a| a == flag).map(|i| args[i + 1].as_str());
    assert_eq!(args[..2], ["run", "tin a.B"]);
    assert_eq!(at("--output-path"), Some("/tmp/x.out"));
    assert_eq!(at("--output-type"), Some("public.plain-text"), "without a type shortcuts writes no file");
}
