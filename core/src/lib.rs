//! tin: app functions and system tools as MCP tools, gated by a policy and an approvals app. Android: AppFunctions,
//! served by a process running as the shell user. macOS: App Intents and Mac system tools.

pub mod backend;
pub mod gate;
pub mod macos;
pub mod mcp;
pub mod metadata;
pub mod policy;
pub mod system;
pub mod transport;
