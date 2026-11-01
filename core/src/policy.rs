//! Deny by default. A tool is listed only when the policy file names it; a read-only one runs at once, anything that can
//! change state waits for an approvals tap, and a money function never runs.

use crate::metadata::Function;
use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Listed and run at once: read-only.
    Allow,
    /// Listed; a call waits for an approvals tap and runs only after `run`.
    Approval,
    /// Listed with a "never runs" note; a call is refused without reaching the backend (money).
    Refuse,
    /// Not listed, not callable.
    Deny,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Policy {
    #[serde(default)]
    pub allow: Vec<Rule>,
    /// Kept from part 1: the same as `allow` without `read_only`.
    #[serde(default)]
    pub approval: Vec<Rule>,
    pub approvals: Option<Approvals>,
    #[serde(default)]
    pub speech: SpeechPolicy,
}

/// `[speech] enabled = true` lists the speech tools. Off by default: an agent with them can switch on the mic.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SpeechPolicy {
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    /// An app package, or `system` for tin's own tools.
    pub package: String,
    /// A `functionId` verbatim (a verb for `system`), or `*` for every function of the package.
    pub function: String,
    /// Runs without an approvals tap. Only for AppFunctions that change nothing; system verbs know their own kind.
    #[serde(default)]
    pub read_only: bool,
}

/// Where held calls are sent for a tap (`[approvals]` in the policy file). Secrets live in their own files.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Approvals {
    /// Approvals server base URL, `http://` over the tailnet: tin has no TLS.
    pub url: String,
    #[serde(default = "default_source")]
    pub source: String,
    pub token_file: String,
    /// `APPROVAL_HMAC_KEY`, which signs decisions.
    pub key_file: String,
    /// A prefix in the server's `APPROVALS_CALLBACK_ROUTES`, ending in `/`; tin appends `decision`.
    pub callback: String,
    /// `host:port` of a TCP listener that serves only `POST /approvals/decision`.
    pub callback_listen: Option<String>,
    #[serde(default = "default_wait")]
    pub wait_secs: u64,
    #[serde(default = "default_ttl")]
    pub ttl_secs: u64,
}

fn default_source() -> String {
    "tin".into()
}
fn default_wait() -> u64 {
    50
}
fn default_ttl() -> u64 {
    900
}

/// The package system tools are named under in the policy.
pub const SYSTEM: &str = "system";

/// What the policy decides about: an AppFunction or a system verb.
pub struct Subject<'a> {
    pub package: &'a str,
    pub id: &'a str,
    /// `Some` for system verbs, whose kind is built in; `None` for AppFunctions, whose kind comes from the rule.
    pub read_only: Option<bool>,
}

impl<'a> From<&'a Function> for Subject<'a> {
    fn from(f: &'a Function) -> Self {
        Subject { package: &f.package, id: &f.id, read_only: None }
    }
}

/// Packages whose every function is treated as moving money (Ukrainian banks, Google Wallet).
pub const MONEY_PACKAGES: &[&str] = &["com.ftband.mono", "ua.privatbank.ap24", "com.google.android.apps.walletnfcrel"];

/// Substrings of a function id that mark it as moving money. Matched case-insensitively on the whole id, so a false
/// positive denies a harmless function, which is the safe direction.
const MONEY_WORDS: &[&str] = &["topup", "pay", "transfer", "purchase", "buy", "send_money", "sendmoney"];

pub fn is_money(f: &Function) -> bool {
    is_money_id(&f.package, &f.id)
}

fn is_money_id(package: &str, id: &str) -> bool {
    let id = id.to_ascii_lowercase();
    MONEY_PACKAGES.contains(&package) || MONEY_WORDS.iter().any(|w| id.contains(w))
}

impl Rule {
    fn matches(&self, s: &Subject) -> bool {
        self.package == s.package && (self.function == "*" || self.function == s.id)
    }
}

impl Policy {
    pub fn from_toml(text: &str) -> Result<Self, String> {
        let p: Policy = toml::from_str(text).map_err(|e| format!("policy: {e}"))?;
        if let Some(r) = p.allow.iter().find(|r| r.read_only && r.package == SYSTEM) {
            return Err(format!(
                "policy: read_only on system verb {}: system verbs know whether they change state, and writes always wait for a tap",
                r.function
            ));
        }
        Ok(p)
    }

    pub fn decide(&self, f: &Function) -> Decision {
        self.decide_subject(&Subject::from(f))
    }

    pub fn decide_subject(&self, s: &Subject) -> Decision {
        let allow = self.allow.iter().find(|r| r.matches(s));
        let named = allow.is_some() || self.approval.iter().any(|r| r.matches(s));
        if !named {
            return Decision::Deny;
        }
        if s.read_only.is_none() && is_money_id(s.package, s.id) {
            // Neither an allow entry nor an approval lifts the money rule; naming it only makes it visible.
            return Decision::Refuse;
        }
        let read_only = match s.read_only {
            Some(builtin) => builtin,
            None => allow.is_some_and(|r| r.read_only),
        };
        if read_only && allow.is_some() { Decision::Allow } else { Decision::Approval }
    }
}
