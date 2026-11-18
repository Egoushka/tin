//! Where functions are listed and run. On the phone that is `cmd app_function`, run as a child process with argv
//! (no shell), so parameters JSON needs no quoting.

use crate::metadata::{self, Function};
use crate::system::{self, Verb};
use serde_json::Value;
use std::process::{Command, Output, Stdio};
use std::time::Duration;

pub trait Backend: Send + Sync {
    /// Raw `list-app-functions` JSON.
    fn list(&self) -> Result<Value, String> {
        Err("this backend has no AppFunctions listing".into())
    }
    /// The app functions to serve. Android parses `list()`; other platforms read their own metadata.
    fn functions(&self) -> Result<Vec<Function>, String> {
        self.list().map(|l| metadata::parse(&l))
    }
    /// Runs one function. `Ok` holds the function's output, `Err` the system's error text.
    fn execute(&self, package: &str, function: &str, parameters: &str) -> Result<String, String>;
    /// Runs one program with argv as the shell user (system tools). `Ok` is stdout.
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let _ = (program, args);
        Err("this backend runs no shell programs".into())
    }
    /// This platform's system verbs.
    fn verbs(&self) -> &'static [Verb] {
        system::VERBS
    }
    /// The `initialize` instructions for clients.
    fn instructions(&self) -> &'static str {
        system::INSTRUCTIONS
    }
}

/// Runs a command with stdin closed, killing it after `timeout`. The waiter thread keeps the child and still
/// calls `wait`, so a killed process is reaped.
pub fn run_output(mut cmd: Command, timeout: Duration) -> Result<Output, String> {
    let child = cmd.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().map_err(|e| e.to_string())?;
    let pid = child.id() as libc::pid_t;
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(child.wait_with_output());
    });
    match rx.recv_timeout(timeout) {
        Ok(out) => out.map_err(|e| e.to_string()),
        Err(_) => {
            // SAFETY: kill(2) on the child we spawned; it has not been reaped, the waiting thread still holds it.
            unsafe { libc::kill(pid, libc::SIGKILL) };
            Err(format!("timed out after {} s", timeout.as_secs()))
        }
    }
}

/// `run_output` as (exit success, stdout, stderr).
pub fn run_with_timeout(cmd: Command, timeout: Duration) -> Result<(bool, String, String), String> {
    let out = run_output(cmd, timeout)?;
    Ok((out.status.success(), String::from_utf8_lossy(&out.stdout).into_owned(), String::from_utf8_lossy(&out.stderr).into_owned()))
}

/// Default limit for one subprocess.
pub const RUN_TIMEOUT: Duration = Duration::from_secs(30);

/// Runs `program` with argv, no shell. `Ok` is stdout; a non-zero exit is `Err` with stderr (or stdout).
pub fn run_argv(program: &str, args: &[String]) -> Result<String, String> {
    run_argv_timeout(program, args, run_limit())
}

fn run_limit() -> Duration {
    #[cfg(test)]
    if let Some(d) = test_seam::RUN_LIMIT.with(|c| c.get()) {
        return d;
    }
    RUN_TIMEOUT
}

/// Per-thread overrides so a test can shorten the limits and point `CmdBackend` at a fake `cmd`.
#[cfg(test)]
mod test_seam {
    use std::{cell::{Cell, RefCell}, time::Duration};
    thread_local! {
        pub static RUN_LIMIT: Cell<Option<Duration>> = const { Cell::new(None) };
        pub static CMD_MARGIN: Cell<Option<Duration>> = const { Cell::new(None) };
        pub static CMD_PROGRAM: RefCell<Option<String>> = const { RefCell::new(None) };
    }
}

/// `run_argv` with a limit: a program still running after `timeout` is killed and the call fails.
pub fn run_argv_timeout(program: &str, args: &[String], timeout: Duration) -> Result<String, String> {
    let mut cmd = Command::new(program);
    cmd.args(args);
    let out = run_output(cmd, timeout).map_err(|e| spawn_or_timeout(program, e))?;
    let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        let text = if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() };
        return Err(format!("{program} exited {}: {text}", out.status.code().unwrap_or(-1)));
    }
    Ok(stdout)
}

fn spawn_or_timeout(program: &str, e: String) -> String {
    if e.starts_with("timed out") { format!("{program} {e}") } else { format!("{program}: {e}") }
}

pub struct CmdBackend {
    pub timeout_secs: u32,
}

impl CmdBackend {
    /// The process limit: the forwarded `--timeout-duration` plus a margin for `cmd` itself to report its own timeout.
    fn limit(&self) -> Duration {
        #[allow(unused_mut)]
        let mut margin = Duration::from_secs(5);
        #[cfg(test)]
        if let Some(m) = test_seam::CMD_MARGIN.with(|c| c.get()) {
            margin = m;
        }
        Duration::from_secs(u64::from(self.timeout_secs)) + margin
    }

    fn run(args: &[&str], timeout: Duration) -> Result<String, String> {
        #[allow(unused_mut)]
        let mut program = "cmd".to_string();
        #[cfg(test)]
        if let Some(p) = test_seam::CMD_PROGRAM.with(|c| c.borrow().clone()) {
            program = p;
        }
        let mut cmd = Command::new(program);
        cmd.arg("app_function").args(args);
        let out = run_output(cmd, timeout).map_err(|e| spawn_or_timeout("cmd", e))?;
        let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
        let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
        // `cmd` exits 0 on a failed execution and prints "Error executing app function: ..." instead.
        if !out.status.success() || stdout.starts_with("Error") {
            let text = if stdout.is_empty() { stderr } else { stdout };
            return Err(text);
        }
        Ok(stdout)
    }
}

impl Backend for CmdBackend {
    fn list(&self) -> Result<Value, String> {
        let text = Self::run(&["list-app-functions"], self.limit())?;
        serde_json::from_str(&text).map_err(|e| format!("list-app-functions output is not JSON: {e}"))
    }

    fn execute(&self, package: &str, function: &str, parameters: &str) -> Result<String, String> {
        let timeout = self.timeout_secs.to_string();
        Self::run(&[
            "execute-app-function",
            "--package",
            package,
            "--function",
            function,
            "--parameters",
            parameters,
            "--timeout-duration",
            &timeout,
        ], self.limit())
    }

    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        run_argv(program, args)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slow_program_is_killed_at_the_limit() {
        let started = std::time::Instant::now();
        let err = run_argv_timeout("sleep", &["5".into()], Duration::from_secs(1)).unwrap_err();
        assert_eq!(err, "sleep timed out after 1 s");
        assert!(started.elapsed() < Duration::from_secs(4));
    }

    #[test]
    fn fast_program_still_returns_output_and_exit_errors() {
        assert_eq!(run_argv_timeout("echo", &["hi".into()], Duration::from_secs(5)).unwrap(), "hi\n");
        let err = run_argv_timeout("sh", &["-c".into(), "echo bad >&2; exit 3".into()], Duration::from_secs(5)).unwrap_err();
        assert_eq!(err, "sh exited 3: bad");
    }

    #[test]
    fn stdin_is_closed() {
        assert_eq!(run_argv_timeout("cat", &[], Duration::from_secs(5)).unwrap(), "");
    }

    #[test]
    fn run_argv_uses_the_default_limit() {
        test_seam::RUN_LIMIT.with(|c| c.set(Some(Duration::from_millis(300))));
        let started = std::time::Instant::now();
        let err = run_argv("sleep", &["10".into()]).unwrap_err();
        assert_eq!(err, "sleep timed out after 0 s");
        assert!(started.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn cmd_backend_kills_a_hung_cmd_at_its_limit() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("tin-fake-cmd-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let fake = dir.join("cmd");
        std::fs::write(&fake, "#!/bin/sh\nexec sleep 10\n").unwrap();
        std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
        test_seam::CMD_PROGRAM.with(|c| *c.borrow_mut() = Some(fake.display().to_string()));
        test_seam::CMD_MARGIN.with(|c| c.set(Some(Duration::from_millis(300))));
        let started = std::time::Instant::now();
        let err = CmdBackend { timeout_secs: 0 }.list().unwrap_err();
        assert!(err.ends_with("timed out after 0 s"), "{err}");
        assert!(started.elapsed() < Duration::from_secs(5));
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
