# AGENTS.md

## Layout

- `core/`: Rust crate `tin-core`, binary `tin`. `metadata.rs` (AppSearch documents → tools), `policy.rs`,
  `mcp.rs` (protocol 2025-06-18), `backend.rs` (`cmd app_function`, shell argv), `transport.rs` (stdio, HTTP on
  `@tin`, the decision-only TCP listener), `system.rs` (system verbs, their argv and dump parsers), `gate.rs` (held
  calls, the approvals client, decision signatures).
- `android/`: Kotlin host app `io.github.egoushka.tin`; `TinService` is the Shizuku user service.
- `scripts/`: `build.sh`, `accept.py` (A6–A8), `peer-check.sh` (A5), `system-check.py` (D1–D6).

## Commands

- Tests (A1–A3, S1–S8, no phone): `cargo test`; CI also runs `cargo clippy --all-targets -- -D warnings`.
  `mcp_http_over_unix_socket` and `http_decision_routes` bind sockets, so they fail inside a sandbox that blocks binds.
- Build the APK: `JAVA_HOME=<JDK 17+> ./scripts/build.sh` (NDK path: `ANDROID_NDK_HOME`, default the Homebrew one).
- Device checks: phone on USB, unlocked, tin serving, demo policy pushed:
  `./scripts/peer-check.sh`, `uv run scripts/accept.py --policy demo`, `uv run scripts/accept.py --bench 20`.
  `--policy demo` no longer creates an alarm: without `[approvals]` it checks the call is refused.
  System tools: push `examples/system-policy.toml` with `[approvals]` filled in, then `uv run scripts/system-check.py
  --read`, `--bench 20`, `--write` (needs taps), `--bank` (a banking app open), `--listen`; `--callback URL` from the box.

## Rules

- A money function (`policy::is_money`) never reaches the backend. Don't add a path around it; widen the word or
  package lists instead. A false positive is the safe direction.
- The backend runs `cmd` with argv, never through a shell.
- A state-changing call never runs without a verified `run` decision. System verbs carry their kind in `system::VERBS`;
  an AppFunction is read-only only when its allow rule says `read_only = true`.
- `system__screen_text` checks the foreground app and every node's package against the money list; keep both checks.
- The decision signature must match approvals' `contract/fixtures/decisions/vectors.json` (copied to
  `core/tests/fixtures/`); refresh the copy when approvals changes it.
- Shizuku starts the service with umask 0: set every file mode explicitly (`Os.chmod`).
- Tool names are `<last two package segments>__<method>`; tests and `accept.py` depend on them.
