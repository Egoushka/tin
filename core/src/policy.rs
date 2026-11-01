//! Deny by default. A function runs only when the policy file allows it, and a money function never runs.

use crate::metadata::Function;
use serde::Deserialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Listed and callable.
    Allow,
    /// Listed with a "needs approval" note; a call is refused without reaching the backend.
    Approval,
    /// Not listed, not callable.
    Deny,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Policy {
    #[serde(default)]
    pub allow: Vec<Rule>,
    #[serde(default)]
    pub approval: Vec<Rule>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    pub package: String,
    /// A `functionId` verbatim, or `*` for every function of the package.
    pub function: String,
}

impl Rule {
    fn matches(&self, f: &Function) -> bool {
        self.package == f.package && (self.function == "*" || self.function == f.id)
    }
}

/// Packages whose every function is treated as moving money (Ukrainian banks, Google Wallet).
const MONEY_PACKAGES: &[&str] = &["com.ftband.mono", "ua.privatbank.ap24", "com.google.android.apps.walletnfcrel"];

/// Substrings of a function id that mark it as moving money. Matched case-insensitively on the whole id, so a false
/// positive denies a harmless function, which is the safe direction.
const MONEY_WORDS: &[&str] = &["topup", "pay", "transfer", "purchase", "buy", "send_money", "sendmoney"];

pub fn is_money(f: &Function) -> bool {
    let id = f.id.to_ascii_lowercase();
    MONEY_PACKAGES.contains(&f.package.as_str()) || MONEY_WORDS.iter().any(|w| id.contains(w))
}

impl Policy {
    pub fn from_toml(text: &str) -> Result<Self, String> {
        toml::from_str(text).map_err(|e| format!("policy: {e}"))
    }

    pub fn decide(&self, f: &Function) -> Decision {
        let named = self.allow.iter().chain(&self.approval).any(|r| r.matches(f));
        if is_money(f) {
            // An allow entry cannot lift the money rule; naming it only makes it visible.
            return if named { Decision::Approval } else { Decision::Deny };
        }
        if self.allow.iter().any(|r| r.matches(f)) {
            Decision::Allow
        } else if self.approval.iter().any(|r| r.matches(f)) {
            Decision::Approval
        } else {
            Decision::Deny
        }
    }
}
