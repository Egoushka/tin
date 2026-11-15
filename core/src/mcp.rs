//! The MCP server: JSON-RPC 2.0 messages in, responses out, transport-agnostic.

use crate::backend::Backend;
use crate::gate::{Gate, Outcome};
use crate::metadata::Function;
use crate::policy::{Decision, Policy, SYSTEM, Subject};
use crate::system::{self, Verb};
use crate::speech::{self, Speech};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::Instant;

pub const PROTOCOL_VERSION: &str = "2025-06-18";

/// The tool a client calls to keep waiting on a held call after its first wait ran out.
pub const WAIT_TOOL: &str = "approval__wait";

/// The self-check tool. Always listed, whatever the policy says: it reads no device data, and it is how a client finds
/// out why every other tool is missing.
pub const DOCTOR_TOOL: &str = "tin__doctor";

/// Where the policy came from, so the doctor can say why it is empty.
#[derive(Debug, Clone)]
pub enum PolicySource {
    File(String),
    /// No file at the path: the policy is empty and everything is denied.
    Missing(String),
}

#[derive(Debug, Clone)]
pub enum Source {
    App(Function),
    System(&'static Verb),
}

/// A tool the policy lets the client see, under its MCP tool name.
#[derive(Debug, Clone)]
pub struct Tool {
    pub name: String,
    pub source: Source,
    pub decision: Decision,
}

impl Tool {
    fn title(&self) -> String {
        match &self.source {
            Source::App(f) => format!("{} ({})", f.method(), f.package),
            Source::System(v) => format!("{} (system)", v.name),
        }
    }

    fn id(&self) -> &str {
        match &self.source {
            Source::App(f) => &f.id,
            Source::System(v) => v.name,
        }
    }
}

/// The MCP `annotations` object. Hints only: clients may use them to decide what to confirm. A tool that is not
/// read-only is reported destructive, the conservative reading, since tin cannot know what an app function changes.
pub fn annotations(title: &str, read_only: bool, open_world: bool) -> Value {
    json!({ "title": title, "readOnlyHint": read_only, "destructiveHint": !read_only, "idempotentHint": read_only, "openWorldHint": open_world })
}

pub struct Server {
    backend: Box<dyn Backend>,
    policy: Policy,
    gate: Option<Arc<Gate>>,
    /// Why the `[approvals]` gate could not be built (a short key), for the doctor.
    gate_off: Option<String>,
    tools: RwLock<HashMap<String, Tool>>,
    speech: Option<Speech>,
    policy_source: Option<PolicySource>,
}

/// `<last two package segments>__<method>`, limited to `[A-Za-z0-9_-]` and 64 characters (what MCP clients accept).
pub fn base_name(f: &Function) -> String {
    let segs: Vec<&str> = f.package.split('.').collect();
    let pkg = segs[segs.len().saturating_sub(2)..].join("_");
    let raw = format!("{pkg}__{}", f.method());
    let clean: String = raw.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
    clean.chars().take(64).collect()
}

/// Names every function, unique across the set: a clash gets `_2`, `_3`, ... within the 64-character limit.
pub fn name_all(functions: Vec<Function>) -> Vec<(String, Function)> {
    let mut seen: HashMap<String, usize> = HashMap::new();
    functions
        .into_iter()
        .map(|f| {
            let base = base_name(&f);
            let n = seen.entry(base.clone()).or_insert(0);
            *n += 1;
            let name = if *n == 1 {
                base
            } else {
                let suffix = format!("_{n}");
                format!("{}{suffix}", &base[..base.len().min(64 - suffix.len())])
            };
            (name, f)
        })
        .collect()
}

impl Server {
    pub fn new(backend: Box<dyn Backend>, policy: Policy) -> Self {
        let speech = policy.speech.enabled.then(|| Speech::new(speech::SOCKET));
        Self { backend, policy, gate: None, gate_off: None, tools: RwLock::new(HashMap::new()), speech, policy_source: None }
    }

    /// Points the speech tools at another socket (tests); does not enable them.
    pub fn with_speech_socket(mut self, socket: &str) -> Self {
        if self.speech.is_some() {
            self.speech = Some(Speech::new(socket));
        }
        self
    }

    /// Tells the doctor where the policy was read from.
    pub fn with_policy_source(mut self, source: PolicySource) -> Self {
        self.policy_source = Some(source);
        self
    }

    /// Held calls go to `gate`. Without one, a call that needs approval is refused.
    pub fn with_gate(mut self, gate: Arc<Gate>) -> Self {
        self.gate = Some(gate);
        self
    }

    /// The `[approvals]` table is set but its gate could not be built. Writes are refused as with no gate; the doctor says why.
    pub fn with_gate_off(mut self, reason: String) -> Self {
        self.gate_off = Some(reason);
        self
    }

    pub fn gate(&self) -> Option<&Gate> {
        self.gate.as_deref()
    }

    /// System verbs the policy names, in table order.
    pub fn system_tools(&self) -> Vec<Tool> {
        self.backend
            .verbs()
            .iter()
            .filter_map(|v| {
                let decision = self.policy.decide_subject(&Subject { package: SYSTEM, id: v.name, read_only: Some(v.read_only) });
                (decision != Decision::Deny).then(|| Tool { name: v.tool_name(), source: Source::System(v), decision })
            })
            .collect()
    }

    /// Re-reads the function list. Apps add and drop functions on update, so `tools/list` always refreshes. System
    /// tools are named first; when the app function listing fails they are still served.
    pub fn refresh(&self) -> Result<usize, String> {
        let mut tools: HashMap<String, Tool> = self.system_tools().into_iter().map(|t| (t.name.clone(), t)).collect();
        match self.backend.functions() {
            Ok(functions) => {
                for (name, function) in name_all(functions) {
                    let decision = self.policy.decide(&function);
                    if decision != Decision::Deny && !tools.contains_key(&name) {
                        tools.insert(name.clone(), Tool { name, source: Source::App(function), decision });
                    }
                }
            }
            Err(e) if tools.is_empty() => return Err(e),
            Err(e) => eprintln!("tin: listing app functions failed, serving system tools only: {e}"),
        }
        let n = tools.len();
        *self.tools.write().unwrap() = tools;
        Ok(n)
    }

    pub fn tools(&self) -> Vec<Tool> {
        let mut t: Vec<Tool> = self.tools.read().unwrap().values().cloned().collect();
        t.sort_by(|a, b| a.name.cmp(&b.name));
        t
    }

    /// Handles one JSON-RPC message. `None` for notifications, which get no response.
    pub fn handle(&self, msg: &Value) -> Option<Value> {
        let id = msg.get("id").cloned();
        let method = msg.get("method").and_then(Value::as_str).unwrap_or("");
        let params = msg.get("params").cloned().unwrap_or(json!({}));
        let id = id?; // no id: a notification (initialized, cancelled), nothing to answer
        let result = match method {
            "initialize" => Ok(json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": { "tools": { "listChanged": false } },
                "serverInfo": { "name": "tin", "version": env!("CARGO_PKG_VERSION") },
                "instructions": self.backend.instructions()
            })),
            "ping" => Ok(json!({})),
            "tools/list" => self.list_tools(),
            "tools/call" => Ok(self.call_tool(&params)),
            _ => Err((-32601, format!("method not found: {method}"))),
        };
        Some(match result {
            Ok(r) => json!({ "jsonrpc": "2.0", "id": id, "result": r }),
            Err((code, message)) => json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message } }),
        })
    }

    fn list_tools(&self) -> Result<Value, (i64, String)> {
        self.refresh().map_err(|e| (-32603, e))?;
        let tools = self.tools();
        let mut out: Vec<Value> = tools
            .iter()
            .map(|t| {
                let note = match t.decision {
                    Decision::Approval => "[waits for a tap in the approvals app] ",
                    Decision::Refuse => "[moves money: never runs, approval or not] ",
                    _ => "",
                };
                let (description, schema) = match &t.source {
                    Source::App(f) => (format!("{note}{} (app: {}, function: {})", f.description, f.package, f.id), f.input_schema.clone()),
                    Source::System(v) => (format!("{note}{}", v.description), v.input_schema()),
                };
                let read_only = t.decision == Decision::Allow;
                let open_world = matches!(t.source, Source::App(_));
                json!({ "name": t.name, "title": t.title(), "description": description, "inputSchema": schema, "annotations": annotations(&t.title(), read_only, open_world) })
            })
            .collect();
        if self.gate.is_some() && tools.iter().any(|t| t.decision == Decision::Approval) {
            out.push(json!({
                "name": WAIT_TOOL,
                "title": "Wait for an approval",
                "description": "Keeps waiting for the owner's decision on a held call, and returns the call's result once it ran.",
                "inputSchema": { "type": "object", "properties": { "ticket": { "type": "string" } }, "required": ["ticket"] },
                "annotations": annotations("Wait for an approval", false, false),
            }));
        }
        out.push(json!({
            "name": DOCTOR_TOOL,
            "title": "Check tin's own setup",
            "description": "Reports whether the policy was found, how many tools it lets through, whether the approvals server is configured, and what is wrong. Call it when an expected tool is missing or a call is refused. It reads no device data.",
            "inputSchema": { "type": "object", "properties": {} },
            "annotations": annotations("Check tin's own setup", true, false),
        }));
        if self.speech.is_some() {
            out.extend(Speech::tools());
        }
        Ok(json!({ "tools": out }))
    }

    fn call_tool(&self, params: &Value) -> Value {
        let name = params.get("name").and_then(Value::as_str).unwrap_or("");
        let args = params.get("arguments").cloned().unwrap_or(json!({}));
        if let Some(speech) = &self.speech
            && Speech::handles(name)
        {
            let start = Instant::now();
            let outcome = speech.call(name, &args);
            eprintln!("tin: called {name} ok={} {} ms", outcome.is_ok(), start.elapsed().as_millis());
            return match outcome {
                Ok(text) => json!({ "content": [{ "type": "text", "text": text }], "isError": false }),
                Err(e) => tool_error(&e),
            };
        }
        if name == DOCTOR_TOOL {
            let report = self.doctor();
            return json!({ "content": [{ "type": "text", "text": report.to_string() }], "structuredContent": report, "isError": false });
        }
        if name == WAIT_TOOL {
            return match (&self.gate, args.get("ticket").and_then(Value::as_str)) {
                (Some(gate), Some(ticket)) => self.settle(gate, ticket, false),
                (None, _) => tool_error("no approvals server is configured, so nothing is ever held"),
                (_, None) => tool_error("ticket: a string is required"),
            };
        }
        if self.tools.read().unwrap().is_empty() {
            // A client may call without listing first (after a restart, say).
            if let Err(e) = self.refresh() {
                return tool_error(&e);
            }
        }
        let Some(tool) = self.tools.read().unwrap().get(name).cloned() else {
            return tool_error(&format!("unknown or not allowed tool: {name}"));
        };
        match tool.decision {
            Decision::Allow => self.run(&tool, &args),
            Decision::Refuse => {
                eprintln!("tin: refused {name}: money");
                tool_error(&format!(
                    "refused: {} moves money, and tin never runs such functions, approval or not. Open the app yourself instead.",
                    tool.id()
                ))
            }
            Decision::Deny => tool_error(&format!("unknown or not allowed tool: {name}")),
            Decision::Approval => {
                let Some(gate) = &self.gate else {
                    eprintln!("tin: refused {name}: needs approval, no approvals server");
                    return tool_error(&format!(
                        "approval required: {name} changes state, and tin has no approvals gate running to ask the owner (no [approvals] table, or its key was refused at startup; tin__doctor says which), so tin does not run it."
                    ));
                };
                // Bad arguments fail here, before anyone is asked to approve a call that cannot run.
                let (title, summary) = match &tool.source {
                    Source::System(v) => match v.argv(&args) {
                        Ok(argv) => (format!("tin: {}", v.describe(&args, &|p, a| self.backend.shell(p, a))), argv.join(" ")),
                        Err(e) => return tool_error(&e),
                    },
                    Source::App(f) => (format!("tin: {} in {}", f.method(), f.package), args.to_string()),
                };
                match gate.hold(name, &args, &title, &summary) {
                    Ok(held) => self.settle(gate, &held.ticket, held.existing),
                    Err(e) => tool_error(&format!("could not ask for approval: {e}")),
                }
            }
        }
    }

    /// Waits on a ticket and turns the outcome into a tool result, running the call if it was approved. `repeat` marks
    /// a call that found its ticket already pending.
    fn settle(&self, gate: &Gate, ticket: &str, repeat: bool) -> Value {
        match gate.wait(ticket) {
            Outcome::Run { tool, args } => {
                let result = match self.tools.read().unwrap().get(&tool).cloned() {
                    Some(t) if t.decision == Decision::Approval => self.run(&t, &args),
                    _ => tool_error(&format!("{tool} is no longer allowed by the policy; not run")),
                };
                gate.finish(ticket, result.clone());
                result
            }
            Outcome::Done(result) => result,
            Outcome::Pending if repeat => json!({
                "content": [{ "type": "text", "text": format!("Already pending: this call is waiting for a tap in the approvals app as {ticket}, and no second request was opened. Call {WAIT_TOOL} with ticket {ticket} to keep waiting.") }],
                "structuredContent": { "status": "pending", "ticket": ticket, "note": "already pending" },
                "isError": false,
            }),
            Outcome::Pending => json!({
                "content": [{ "type": "text", "text": format!("Waiting for a tap in the approvals app. Call {WAIT_TOOL} with ticket {ticket} to keep waiting.") }],
                "structuredContent": { "status": "pending", "ticket": ticket },
                "isError": false,
            }),
            Outcome::Running => tool_error(&format!("{ticket} was approved and is running for another caller")),
            Outcome::Denied(action) => tool_error(&format!("denied in the approvals app ({action}); not run")),
            Outcome::Expired => tool_error(&format!("{ticket} expired without a decision; not run")),
            Outcome::Unknown => tool_error(&format!("no such ticket: {ticket}")),
        }
    }

    /// A health report of tin's own setup: no device data, no secret values, only whether things are there.
    fn doctor(&self) -> Value {
        let mut problems: Vec<String> = Vec::new();
        let policy = match &self.policy_source {
            Some(PolicySource::File(path)) => json!({ "path": path, "status": "loaded" }),
            Some(PolicySource::Missing(path)) => {
                problems.push(format!("no policy file at {path}, so every function is denied; push a policy there (examples/ has samples)"));
                json!({ "path": path, "status": "missing" })
            }
            None => json!({ "status": "built in" }),
        };
        if self.policy.allow.is_empty() && self.policy.approval.is_empty() && self.policy_source.as_ref().is_none_or(|s| matches!(s, PolicySource::File(_))) {
            problems.push("the policy has no [[allow]] or [[approval]] rule, so every function is denied".into());
        }

        let functions = self.backend.functions();
        let verbs = self.backend.verbs();
        let backend = match &functions {
            Ok(f) => json!({ "app_functions": f.len(), "system_verbs": verbs.len() }),
            Err(e) => {
                let e: String = e.chars().take(200).collect();
                problems.push(format!("listing app functions failed: {e}"));
                json!({ "app_functions": null, "system_verbs": verbs.len(), "error": e })
            }
        };

        // Rules that match nothing the backend offers are the usual typo.
        let listed: Vec<Subject> = verbs
            .iter()
            .map(|v| Subject { package: SYSTEM, id: v.name, read_only: Some(v.read_only) })
            .chain(functions.iter().flatten().map(Subject::from))
            .collect();
        if functions.is_ok() || !verbs.is_empty() {
            for r in self.policy.allow.iter().chain(&self.policy.approval) {
                if !listed.iter().any(|s| r.matches(s)) && !(functions.is_err() && r.package != SYSTEM) {
                    problems.push(format!("rule {} {} matches no function on this device (check the spelling and that the app is installed)", r.package, r.function));
                }
            }
        }

        let _ = self.refresh(); // a failure is already in `problems` through the listing above
        let tools = self.tools();
        let count = |d: Decision| tools.iter().filter(|t| t.decision == d).count();
        let (allow, approval, refuse) = (count(Decision::Allow), count(Decision::Approval), count(Decision::Refuse));

        let readable = |path: &str| if std::fs::File::open(path).is_ok() { "readable" } else { "missing or unreadable" };
        let approvals = match &self.policy.approvals {
            None => {
                if approval > 0 {
                    problems.push(format!("{approval} tool(s) need an approval tap but the policy has no [approvals] table, so calls to them are refused"));
                }
                json!({ "configured": false })
            }
            Some(_) if self.gate_off.is_some() => {
                let reason = self.gate_off.as_deref().unwrap_or_default();
                problems.push(format!("the approvals gate is off, so tools that need a tap are refused: {reason}"));
                json!({ "configured": true, "gate": "off", "reason": reason })
            }
            Some(a) => {
                let (token, key) = (readable(&a.token_file), readable(&a.key_file));
                for (what, state) in [("token_file", token), ("key_file", key)] {
                    if state != "readable" {
                        problems.push(format!("[approvals] {what} is {state}, so tin cannot ask for approval"));
                    }
                }
                // A configured table has a gate here, unless the key was short or unreadable (the branch above).
                let reachable = self.gate.as_ref().map(|g| g.probe());
                if let Some(Err(e)) = &reachable {
                    problems.push(format!("the approvals server is not reachable ({e}), so held calls cannot reach the phone"));
                }
                json!({
                    "configured": true,
                    "reachable": reachable.as_ref().map(|r| r.is_ok()),
                    "token_file": token,
                    "key_file": key,
                    "callback_listen": a.callback_listen.is_some()
                })
            }
        };

        json!({
            "version": env!("CARGO_PKG_VERSION"),
            "ok": problems.is_empty(),
            "policy": policy,
            "backend": backend,
            "tools": { "allow": allow, "approval": approval, "refuse": refuse },
            "approvals": approvals,
            "speech": { "enabled": self.speech.is_some() },
            "problems": problems,
        })
    }

    fn run(&self, tool: &Tool, args: &Value) -> Value {
        let start = Instant::now();
        // The text of the answer, and the PNG (base64) a verb attached, if any.
        let outcome: Result<(String, Option<String>), String> = match &tool.source {
            Source::App(f) => self.backend.execute(&f.package, &f.id, &args.to_string()).map(|t| (t, None)),
            Source::System(v) => v.run(args, &|p, a| self.backend.shell(p, a)).map(|mut r| {
                if v.name == "notifications" {
                    system::mask_notification_codes(&mut r, &self.policy.notifications.show_codes);
                }
                let image = r.as_object_mut().and_then(|o| o.remove(system::IMAGE_KEY)).and_then(|v| v.as_str().map(str::to_string));
                (r.to_string(), image)
            }),
        };
        eprintln!("tin: called {} ok={} {} ms", tool.name, outcome.is_ok(), start.elapsed().as_millis());
        match outcome {
            Ok((text, image)) => {
                let mut content = vec![json!({ "type": "text", "text": text })];
                content.extend(image.map(|data| json!({ "type": "image", "data": data, "mimeType": "image/png" })));
                json!({ "content": content, "isError": false })
            }
            Err(e) => tool_error(&e),
        }
    }
}

fn tool_error(text: &str) -> Value {
    json!({ "content": [{ "type": "text", "text": crate::redact::redact(text) }], "isError": true })
}
