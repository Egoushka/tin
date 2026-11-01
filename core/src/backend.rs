//! Where functions are listed and run. On the phone that is `cmd app_function`, run as a child process with argv
//! (no shell), so parameters JSON needs no quoting.

use serde_json::Value;
use std::process::Command;

pub trait Backend: Send + Sync {
    /// Raw `list-app-functions` JSON.
    fn list(&self) -> Result<Value, String>;
    /// Runs one function. `Ok` holds the function's output, `Err` the system's error text.
    fn execute(&self, package: &str, function: &str, parameters: &str) -> Result<String, String>;
    /// Runs one program with argv as the shell user (system tools). `Ok` is stdout.
    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let _ = (program, args);
        Err("this backend runs no shell programs".into())
    }
}

pub struct CmdBackend {
    pub timeout_secs: u32,
}

impl CmdBackend {
    fn run(args: &[&str]) -> Result<String, String> {
        let out = Command::new("cmd").arg("app_function").args(args).output().map_err(|e| format!("cmd: {e}"))?;
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
        let text = Self::run(&["list-app-functions"])?;
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
        ])
    }

    fn shell(&self, program: &str, args: &[String]) -> Result<String, String> {
        let out = Command::new(program).args(args).output().map_err(|e| format!("{program}: {e}"))?;
        let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
        if !out.status.success() {
            let stderr = String::from_utf8_lossy(&out.stderr);
            let text = if stderr.trim().is_empty() { stdout.trim() } else { stderr.trim() };
            return Err(format!("{program} exited {}: {text}", out.status.code().unwrap_or(-1)));
        }
        Ok(stdout)
    }
}
