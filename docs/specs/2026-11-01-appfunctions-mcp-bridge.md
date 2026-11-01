# Spec: AppFunctions → MCP bridge (part 1)

Date: 2026-11-01. Status: approved scope from the phone-layer proposal
(`~/Projects/context/ai-setup/drafts/2026-11-01-phone-layer-proposal.md`, part 1).

## Goal

Every AppFunction an Android app publishes becomes an MCP tool, with no work per app. A process running as the shell
user (uid 2000, the user `adb shell` runs as) reads each function's metadata, turns it into an MCP tool with a JSON
Schema, and runs it through the system's AppFunctions service. Shizuku hosts that process so it keeps running without
a cable. An MCP client on the Mac reaches it through `adb forward`. Nothing runs unless a policy file allows it, and a
function that can move money is never run by the bridge at all.

Value hypothesis: agents act through apps' own typed functions instead of tapping pixels, on any Android 16+ phone
with Shizuku. Cheapest test: the public repo plus a demo of an agent setting an alarm; count stars and issues.

## Facts this rests on (probed on the Pixel 8a, stock Android 17 QPR1, 2026-11-01)

- `cmd app_function list-app-functions` prints AppSearch documents as JSON, keyed by package. Each package holds
  `AppFunctionStaticMetadata` (id, description, parameters, response), `AppFunctionRuntimeMetadata` and, when the app
  defines object types, one `AppFunctionComponentMetadataDocument` whose `dataTypes` resolve `dataTypeReference`.
  Every scalar is wrapped in a one-element array. The phone has 61 functions in 11 packages; the capture is the test
  fixture `core/tests/fixtures/list-2026-11-01.json`.
- Type codes seen: 1 boolean, 3 object, 4 double, 6 long, 7 int, 8 string, 10 array, 11 reference, 12 allOf, 13
  pending intent (androidx `AppFunctionDataTypeMetadata`).
- `cmd app_function execute-app-function --package P --function ID --parameters JSON` runs one. The function id is
  the metadata's `functionId` verbatim (Settings uses bare `getBatteryDeviceState`, Sleep as Android
  `com.urbandroid.sleep.functions.AppFunctions#createAlarm`). Device-state functions fail with code 1000 while the
  phone is locked.
- One raw call from `adb shell` took 0.29–0.54 s end to end.

## Design

- **Core, Rust** (`core/`, binary `tin`): metadata parser and JSON Schema translation, the policy, an MCP server
  (protocol `2025-06-18`: `initialize`, `ping`, `tools/list`, `tools/call`), and a backend that runs
  `cmd app_function` as a child process. Transports: stdio (`tin serve --stdio`, for `adb shell` use) and
  streamable HTTP on an abstract Unix socket `@tin` (`tin serve`), where every connection's peer uid is checked with
  `SO_PEERCRED` and only uid 0 and 2000 are served. adbd runs as uid 2000, so `adb forward tcp:7381 localabstract:tin`
  works and no app on the phone can reach the bridge.
- **Host, Kotlin** (`android/`): an app that binds a Shizuku user service. The service runs as uid 2000, copies the
  `tin` binary from the APK to `/data/local/tmp/tin/bin/tin`, starts `tin serve`, and restarts it if it exits.
- **Tool names**: `<last two package segments>__<method>`, sanitized to `[A-Za-z0-9_-]`, at most 64 characters, made
  unique with a numeric suffix. Example: `urbandroid_sleep__createAlarm`.
- **Policy** (`/data/local/tmp/tin/policy.toml`, readable only by shell and root): deny by default. `[[allow]]` entries
  name a package and a function id (or `*`). A built-in money rule matches function ids containing `topUp`, `pay`,
  `transfer`, `purchase`, `buy` or `send_money` (case-insensitive) and every function of a package in its list
  (`com.ftband.mono`, `ua.privatbank.ap24`). A money function is never executed, whatever the allowlist says: if the
  policy names it under `[[approval]]` it is listed with a "needs approval" note and a call returns an error saying so;
  otherwise it is hidden. The approvals-tap integration is part 3's work.

## Out of scope

The approvals tap itself, the phone dialing out to the homelab gateway (part 3), a direct binder backend instead of
`cmd` (only if the overhead check fails), other users and profiles (the backend never passes `--user`), pending
intents in responses, iOS.

## Acceptance checks

Each is a command with an expected result. A1–A3 run on the Mac with no phone; A4–A8 need the phone on USB,
unlocked, with Shizuku running.

| # | Check | Command | Pass when |
|---|---|---|---|
| A1 | Fixture parses | `cargo test -p tin-core fixture_` | 61 functions in 11 packages; `createAlarm` has `hour` and `minute`, both `integer`, both required; every reference resolves |
| A2 | Deny by default | `cargo test -p tin-core policy_` | an empty policy lists 0 tools; an `allow` naming monobank `topUpPhone` still never reaches the backend (a recording fake backend sees 0 calls) and the call returns an error containing `approval` |
| A3 | MCP conformance | `cargo test -p tin-core mcp_` | `initialize` → `tools/list` → `tools/call` round-trip over the stdio transport with the fake backend; unknown tool → JSON-RPC result with `isError: true` |
| A4 | Runs as shell under Shizuku | `adb shell ps -A -o USER,NAME` | a `tin` process owned by `shell` whose parent is the Shizuku user service, with no `adb shell` session holding it |
| A5 | Only adb can connect | `scripts/peer-check.sh` (runs `tin probe` as shell, then as the debuggable tin app's uid through `run-as`) | as shell: `200`; as the app uid: connection closed, no response |
| A6 | End to end | `uv run scripts/accept.py --policy demo` | the MCP Python SDK client lists exactly the demo policy's tools, `urbandroid_sleep__createAlarm` returns success once, `mono__topUpPhone` returns `isError` with `approval` |
| A7 | Latency | `uv run scripts/accept.py --bench 20` | 20 of 20 calls to `settings__getBatteryDeviceState` succeed; prints p50 and p95 |
| A8 | Overhead | same run | bridge p95 minus raw `adb shell cmd app_function execute-app-function` p95 (20 runs each, same function) ≤ 100 ms (starting value: the function itself takes 300–500 ms, so 100 ms of bridge cost would still be a minority) |

A5 is the weakest check: if SELinux stops the app uid from running the probe at all, A5 is recorded UNKNOWN in the
README rather than passed.

## Stop conditions

- From the proposal: `cmd app_function` is absent on GrapheneOS 17 → the bridge ships for stock Android only and waits
  for GrapheneOS's next base.
- A8 fails by more than 2× (overhead p95 > 200 ms) → replace the `cmd` backend with a Kotlin binder backend before
  anything else is built on it.
