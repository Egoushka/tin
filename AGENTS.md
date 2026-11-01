# AGENTS.md

## Layout

- `core/`: Rust crate `tin-core`, binary `tin`. `metadata.rs` (AppSearch documents → tools), `policy.rs`,
  `mcp.rs` (protocol 2025-06-18), `backend.rs` (`cmd app_function`), `transport.rs` (stdio, HTTP on `@tin`).
- `android/`: Kotlin host app `io.github.egoushka.tin`; `TinService` is the Shizuku user service.
- `scripts/`: `build.sh`, `accept.py` (A6–A8), `peer-check.sh` (A5).

## Commands

- Tests (A1–A3, no phone): `cargo test`. `mcp_http_over_unix_socket` binds a Unix socket, so it fails inside a
  sandbox that blocks binds.
- Build the APK: `JAVA_HOME=<JDK 17+> ./scripts/build.sh` (NDK path: `ANDROID_NDK_HOME`, default the Homebrew one).
- Device checks: phone on USB, unlocked, tin serving, demo policy pushed:
  `./scripts/peer-check.sh`, `uv run scripts/accept.py --policy demo`, `uv run scripts/accept.py --bench 20`.
  `--policy demo` creates one real alarm; say the time to the phone's owner.

## Rules

- A money function (`policy::is_money`) never reaches the backend. Don't add a path around it; widen the word or
  package lists instead. A false positive is the safe direction.
- The backend runs `cmd` with argv, never through a shell.
- Shizuku starts the service with umask 0: set every file mode explicitly (`Os.chmod`).
- Tool names are `<last two package segments>__<method>`; tests and `accept.py` depend on them.
