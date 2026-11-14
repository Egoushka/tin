//! Scrubs secrets out of error text before it is returned to the model. A one-time code or token that reaches a
//! tool error (a backend's stderr, a notification dump) would otherwise land in the model's context.
//! No regex crate: the patterns are few, so they are scanned by hand. A false positive is the safe direction.

pub const MASK: &str = "[redacted]";

/// Words that mark the text after them as a secret: `password=hunter2`, `token: abc`, `Bearer abc`.
const KEYS: &[&str] = &[
    "password", "passwd", "passcode", "secret", "token", "api_key", "apikey", "api-key",
    "otp", "pin", "cvv", "code",
];

/// Prefixes of well-known token formats; the run after the prefix is masked.
const PREFIXES: &[&str] = &["sk-", "ghp_", "gho_", "ghs_", "github_pat_", "xoxb-", "xoxp-", "AKIA", "AIza"];

pub fn redact(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    // The previous word was a secret key, so the next value-like word is masked ("code is 123456", "Bearer abc").
    let mut after_key = false;
    // The previous word was `Authorization` or `Bearer`: whatever word comes next is the credential.
    let mut after_auth = false;
    let mut rest = text;
    while !rest.is_empty() {
        let start = rest.find(is_word).unwrap_or(rest.len());
        out.push_str(&rest[..start]);
        rest = &rest[start..];
        if rest.is_empty() {
            break;
        }
        let end = rest.find(|c| !is_word(c)).unwrap_or(rest.len());
        let word = &rest[..end];
        let sep = &rest[end..];
        let lower = word.to_ascii_lowercase();
        if after_auth && !matches!(lower.as_str(), "bearer" | "basic") {
            out.push_str(MASK);
            after_auth = false;
            rest = sep;
            continue;
        }
        if matches!(lower.as_str(), "authorization" | "bearer" | "basic") {
            out.push_str(word);
            after_auth = true;
            rest = sep;
            continue;
        }
        let is_key = KEYS.contains(&lower.as_str());
        if is_key {
            out.push_str(word);
            // `key=value` and `key: value` mask the value even when it is text; a bare "code 123456" only masks digits.
            let (gap, value_end) = value_after(sep);
            if value_end > 0 && gap_is_assignment(gap) {
                out.push_str(gap);
                out.push_str(MASK);
                rest = &sep[value_end..];
                after_key = false;
                continue;
            }
            after_key = true;
        } else if is_secret_shaped(word) || (after_key && is_code(word)) {
            out.push_str(MASK);
            after_key = false;
        } else {
            out.push_str(word);
            if !matches!(lower.as_str(), "is" | "was" | "are" | "for" | "your" | "the" | "a" | "to" | "use" | "with") {
                after_key = false;
            }
        }
        rest = sep;
    }
    out
}

fn is_word(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.' | '/' | '+')
}

/// `(separator, length of separator + value)` for the text after a key, or `("", 0)` when no value follows.
fn value_after(sep: &str) -> (&str, usize) {
    let gap_len = sep.find(|c: char| !matches!(c, ':' | '=' | ' ' | '"' | '\'')).unwrap_or(sep.len());
    let gap = &sep[..gap_len];
    let vlen = sep[gap_len..].find(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | ',' | ';' | '&' | '}')).unwrap_or(sep.len() - gap_len);
    if vlen == 0 { ("", 0) } else { (gap, gap_len + vlen) }
}

fn gap_is_assignment(gap: &str) -> bool {
    gap.contains(':') || gap.contains('=')
}

/// A 4 to 8 digit run: the shape of a one-time code.
fn is_code(w: &str) -> bool {
    (4..=8).contains(&w.len()) && w.chars().all(|c| c.is_ascii_digit())
        || w.len() == 7 && w.chars().enumerate().all(|(i, c)| if i == 3 { c == '-' } else { c.is_ascii_digit() })
}

fn is_secret_shaped(w: &str) -> bool {
    if PREFIXES.iter().any(|p| w.starts_with(p) && w.len() > p.len() + 6) {
        return true;
    }
    // A JWT: three dot-separated base64url parts, the first starting `eyJ`.
    if w.starts_with("eyJ") && w.matches('.').count() == 2 {
        return true;
    }
    // A long unbroken run mixing letters and digits: a key, hash or opaque token. Paths and plain words stay.
    w.len() >= 32 && !w.contains('/') && w.chars().any(|c| c.is_ascii_digit()) && w.chars().any(|c| c.is_ascii_alphabetic())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn masks_one_time_codes() {
        assert_eq!(redact("Your code is 123456, do not share"), "Your code is [redacted], do not share");
        assert_eq!(redact("OTP 4821 expires"), "OTP [redacted] expires");
        assert_eq!(redact("verification code: 123-456"), "verification code: [redacted]");
    }

    #[test]
    fn masks_key_values() {
        assert_eq!(redact("login failed password=hunter2 for bob"), "login failed password=[redacted] for bob");
        assert_eq!(redact("Authorization: Bearer abc.def"), "Authorization: Bearer [redacted]");
        assert_eq!(redact(r#"{"token":"abcdef","user":"bob"}"#), r#"{"token":"[redacted]","user":"bob"}"#);
        assert_eq!(redact("sent bearer abcdef0123 twice"), "sent bearer [redacted] twice");
    }

    #[test]
    fn masks_token_shapes() {
        let jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
        assert_eq!(redact(&format!("rejected {jwt} now")), "rejected [redacted] now");
        assert_eq!(redact("key ghp_abcdefghijklmnop failed"), "key [redacted] failed");
        assert_eq!(redact("hash 0123456789abcdef0123456789abcdef ok"), "hash [redacted] ok");
    }

    #[test]
    fn leaves_ordinary_errors() {
        for s in [
            "unknown or not allowed tool: android_settings__getBatteryDeviceState",
            "cmd: exit status 1 in 250 ms",
            "no such file: /data/local/tmp/tin/policy.toml",
            "ticket: a string is required",
            "hour must be 0 to 23, got 25",
        ] {
            assert_eq!(redact(s), s);
        }
    }
}
