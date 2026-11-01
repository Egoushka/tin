# Spec: phone MCP endpoint, system tools and approval gating (part 3, first slice)

Date: 2026-11-01. Status: scope from the phone-layer proposal
(`~/Projects/context/ai-setup/drafts/2026-11-01-phone-layer-proposal.md`, part 3 and the tier-1 broker section).

## Goal

One MCP endpoint on the phone serves two tool sources: the AppFunctions bridge (part 1, merged in #2) and **system
tools** that tin runs as the shell user (uid 2000): active notifications, app usage, the foreground app, the text on
screen, battery, connectivity, and input and settings verbs. Every tool that changes state waits for a tap in the
approvals app (Egoushka/approvals) before it runs, through the approvals server's existing item and decision API.

Value hypothesis: an agent can answer "what did I miss, what am I doing, how is the phone" from the phone itself, and
act on it, without a tool per app and without a write ever happening behind Yehor's back. Cheapest test: the device
run below (D1–D4) plus one approved tap end to end.

## Facts this rests on

- tin already serves MCP streamable HTTP on `@tin` (peer uid 0 or 2000 only) and stdio, runs `cmd` as argv, and
  refuses money functions before they reach the backend (`docs/specs/2026-11-01-appfunctions-mcp-bridge.md`).
- AppFunction metadata carries no read-only marker: the 61 functions in the fixture have `scope`, `schemaCategory`
  (`device_state` for some) and no side-effect field. Read-only therefore has to be declared in the policy.
- Approvals server (`personal/approvals`, `server/README.md`, `server/src/{items,decide,sign,callback}.ts`):
  - A source creates an item with `PUT /items/{id}` and `Authorization: Bearer <source token>`; the id must start
    with `<source>:`; `kind: decision` items need actions and a `callback`. Answers: 201 created, 200 updated,
    409 already decided, 400 validation, 401. `DELETE /items/{id}` withdraws one.
  - The callback must be `https:` and start with a prefix in `APPROVALS_CALLBACK_ROUTES` (`prefix=target`); the
    server POSTs to the *target*, which may be any URL the box reaches (for tin: the phone's tailnet address).
  - The decision body is `{decision, id, action, edited, at, sig}`, `sig = HMAC-SHA256(APPROVAL_HMAC_KEY,
    decision|id|action|at|h)`, `h` = SHA-256 hex of the sorted edited fields, `""` when none. Vectors:
    `contract/fixtures/decisions/vectors.json`, key `test-approval-key`.
  - The server treats 2xx (unless `{ok:false}`) and 409 as delivered; anything else shows as a failed decision.
  - Priority 5 makes the server push at once (`notify.ts`: `priority >= 5` is urgent).
  - The phone-shell spec's safety rule (`docs/specs/2026-10-31-shell-design.md`): anything that sends, pays or
    deletes becomes a `kind: decision` item and runs only after a human tap.

## Design

### Tool registry

One registry, two sources, one naming rule. System tools are named `system__<verb>`; AppFunctions keep
`<last two package segments>__<method>`. System names are assigned first, so an app can never shadow one.

| Tool | Kind | Runs (argv, never a shell) | Returns |
|---|---|---|---|
| `system__notifications` | read | `dumpsys notification --noredact` | active notifications: key, package, channel, title, text, posted time, ongoing |
| `system__usage` | read | `dumpsys usagestats` | today's per-app foreground time and last use, top `limit` (default 20) |
| `system__foreground_app` | read | `dumpsys activity activities` | package and activity of the resumed activity |
| `system__screen_text` | read | `uiautomator dump <tin dir>/ui.xml`, then `cat` it | package and the nodes with text or a content description: text, description, resource id, class, bounds, clickable |
| `system__battery` | read | `dumpsys battery` | level, status, plugged, temperature °C, health, voltage mV |
| `system__connectivity` | read | `dumpsys connectivity`, `settings get global airplane_mode_on` | default network's transports, validated, all networks' transports, airplane mode |
| `system__tap` | write | `input tap X Y` | — |
| `system__swipe` | write | `input swipe X1 Y1 X2 Y2 MS` | — |
| `system__type_text` | write | `input text T` (spaces as `%s`) | — |
| `system__key` | write | `input keyevent KEYCODE_*` | — |
| `system__launch` | write | `monkey -p PKG -c android.intent.category.LAUNCHER 1` | — |
| `system__settings_put` | write | `settings put system\|secure\|global KEY VALUE` | — |

Arguments are validated before anything runs: coordinates are integers in 0–10000, a package matches
`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$`, a key code matches `^KEYCODE_[A-Z0-9_]+$`, a settings key
`^[A-Za-z0-9_.:-]{1,100}$`, text is at most 1000 characters with no control characters.

`system__screen_text` refuses when the foreground package, or any node's package, is a money package (the policy's
list): uiautomator reads the accessibility tree, which `FLAG_SECURE` does not hide, so bank screens would otherwise be
readable.

### Policy

The policy file keeps its shape and gains one field and one table:

```toml
[[allow]]                     # listed; a call waits for an approvals tap
package = "com.urbandroid.sleep"
function = "com.urbandroid.sleep.functions.AppFunctions#createAlarm"

[[allow]]                     # read_only: runs without a tap
package = "com.android.settings"
function = "getBatteryDeviceState"
read_only = true

[[allow]]                     # system tools use package "system" and the verb as the function
package = "system"
function = "battery"

[approvals]                   # absent: every held call is refused, as before
url = "http://100.64.0.4:8110"                       # approvals server, reached over the tailnet
source = "tin"
token_file = "/data/local/tmp/tin/approvals.token"   # source token, mode 600
key_file = "/data/local/tmp/tin/approvals.key"       # APPROVAL_HMAC_KEY, mode 600
callback = "https://tin.phone.invalid/approvals/"    # a prefix in APPROVALS_CALLBACK_ROUTES
callback_listen = "100.64.0.9:7382"                  # optional TCP listener serving only the decision route
wait_secs = 50
ttl_secs = 900
```

Decisions, in order:

1. Not named by any rule → **deny** (not listed).
2. A money function (built-in rule, unchanged) → **refuse**: listed only when named, never run, not even after an
   approval (AGENTS.md: a money function never reaches the backend).
3. A system write verb → **approval**, whatever the rule says; `read_only = true` on one is a load error.
4. A system read verb, or an AppFunction with `read_only = true` → **allow** (runs at once).
5. Any other named AppFunction (including `[[approval]]` rules, kept for compatibility) → **approval**.

This changes part 1's behaviour on purpose: an allowed AppFunction without `read_only` (the demo's `createAlarm`) now
waits for a tap.

### Gating

A call to an approval tool:

1. tin makes a ticket `tin:<unix ms>-<16 hex random>` holding the tool name and the exact arguments, and `PUT`s an
   item: `source: "tin"`, `kind: "decision"`, `priority: 5` (an agent is waiting, so the server pushes at once),
   title `Phone: <what it does>` (for example `Phone: tap at (540, 1200)`), body `{tool, arguments}`, no editable
   fields, actions `run` (primary) and `deny` (danger), `expires_at` = now + `ttl_secs`, `callback` = the configured
   prefix + `decision`.
2. The call blocks up to `wait_secs`. Nothing reaches the shell or `cmd` before a valid `run` decision.
3. The decision arrives as `POST /approvals/decision` on tin's HTTP server (the `@tin` socket, and the optional
   TCP listener, which serves this route and nothing else). tin verifies the HMAC with the key file, then: unknown id
   → 404; already decided → 409 (the server counts it delivered); `run` or `deny` → recorded, 200.
4. `run` → the call runs exactly the stored arguments once and returns its result. `deny` → `isError`, nothing runs.
   No decision in time → `isError` naming the ticket; `approval__wait {ticket}` waits again and runs on approval.
   A ticket runs at most once and expires after `ttl_secs`; tin withdraws expired items (`DELETE /items/{id}`).

Starting values, to calibrate on the device: `wait_secs` 50 (long enough to reach the phone and tap, shorter than the
60–120 s read timeouts common in HTTP MCP clients, so tin answers before the client gives up); `ttl_secs` 900 (an
approval older than 15 minutes answers a situation that may have changed).

How the decision reaches the phone: the approvals server POSTs to the route target, which for tin is
`http://<phone tailnet address>:7382/approvals/`. The HMAC is the authentication; the listener serves only that route
and only for open tickets. The proposal's dial-out WebSocket would remove the inbound port; it is out of scope here and
replaces the listener when it lands.

The HTTP client is a small HTTP/1.1 `PUT`/`DELETE` over a TCP stream: tin has no TLS, so `url` must be `http://` and
reached over the tailnet (WireGuard-encrypted). This keeps the binary near its 570 KB.

## Out of scope

The dial-out WebSocket and UnifiedPush wake (rest of part 3), the shadow display (part 2), the approvals server's own
configuration (a `tin` source token and callback route are a homelab-gitops change), recording sessions for replay,
notifications actions (reply, snooze, cancel), other users and profiles.

## Acceptance checks

S1–S8 run on the Mac and in CI with fakes (`cargo test`); D1–D6 need the phone, unlocked, tin serving, and, for D3
and D5, the approvals server configured for tin. Parser fixtures are written from Android's dump formats and marked
synthetic until D1 replaces them with captures from the phone.

| # | Check | Command | Pass when |
|---|---|---|---|
| S1 | Registry | `cargo test -p tin-core registry_` | 12 system tools with fixed kinds (6 read, 6 write); names unique together with the 61-function fixture; an empty policy lists 0 tools |
| S2 | Parsers | `cargo test -p tin-core parse_` | each fixture parses to the expected JSON; garbage input gives an empty result, not a panic |
| S3 | Policy | `cargo test -p tin-core policy_` | `read_only` on a write verb fails to load; AppFunction allow without `read_only` → approval, with → allow; a money function stays refused with `read_only` and under `[[approval]]` (0 backend calls); part 1's A2 still passes |
| S4 | Gating | `cargo test -p tin-core gate_` | a held call PUTs exactly one item that passes approvals' validation rules (id prefix, action ids, priority, title/summary lengths, body ≤ 32 KB) and makes 0 shell or backend calls before a decision; a signed `run` → exactly 1 call with the original arguments; `deny` → 0 calls and `isError`; a bad signature → 401 and 0 calls; no decision in time → `isError` with the ticket and 0 calls, then `approval__wait` after a `run` → 1 call; a second decision → 409; an expired ticket never runs; without `[approvals]` a held call is refused with 0 calls |
| S5 | Signature compatibility | `cargo test -p tin-core gate_vectors` | tin accepts all 3 vectors from approvals' `contract/fixtures/decisions/vectors.json` and rejects each with one character changed |
| S6 | Bank screens | `cargo test -p tin-core screen_` | `system__screen_text` with a money package in the foreground returns `isError` and runs no `uiautomator` |
| S7 | Argv | `cargo test -p tin-core argv_` | every verb's exact argv; `type_text` sends spaces as `%s`; a key code with `;`, a coordinate of -1, a package with a space are refused before anything runs |
| S8 | Decision over HTTP | `cargo test -p tin-core http_decision` | `POST /approvals/decision` over the Unix socket and over the TCP listener completes a held call; any other path on the TCP listener → 404 |
| D1 | Read tools on the phone | `uv run scripts/system-check.py --read` | the 6 read tools return without `isError`; raw outputs are saved to `core/tests/fixtures/device/` and the parsers return a non-empty result for each (notifications and usage may be empty on a fresh phone: recorded, not failed) |
| D2 | Read latency | `uv run scripts/system-check.py --bench 20` | prints p50 and p95 per read tool over 20 calls; recorded in `docs/results/` |
| D3 | Approved write end to end | `uv run scripts/system-check.py --write` | `system__key KEYCODE_HOME` shows an item in the approvals app within 10 s; Run → the call returns success and the home screen shows; a second call → Deny → `isError`, nothing happens |
| D4 | Bank screen refused | `uv run scripts/system-check.py --bank` (monobank open) | `system__screen_text` returns `isError` |
| D5 | Callback path | `python3 scripts/system-check.py --callback http://<phone tailnet>:7382` from the box | the box reaches `<phone tailnet>:7382/approvals/decision`; a request with a bad signature gets 401 |
| D6 | Shell can listen | `uv run scripts/system-check.py --listen` | tin as uid 2000 binds the tailnet TCP port (shell's network access is UNKNOWN until run) |

## Stop conditions

- D6 fails (shell cannot bind or reach the network) → decisions travel over the dial-out channel instead, and that
  channel is built before any write tool ships.
- `system__screen_text` p95 over 3 s in D2 (starting value: an agent reading the screen once per step stalls past
  that) → screen reading moves to the accessibility service in the approvals shell (role P5) and tin keeps the other
  read tools.
- Any write runs without a `run` decision, on the device or in S4 → the write tools are removed until the cause is
  fixed; the rule is binary.

## Still needs the phone and the box

S1–S8 pass in CI. Nothing below has run: the Pixel 8a is due to be wiped for GrapheneOS, so D1–D6 wait for the phone
on its new OS. Each item says who does it.

On the phone (Yehor plugs it in and says so; no fastboot, nothing that unlocks or flashes):

1. Tailscale on the phone, so the box can reach `<phone tailnet>:7382`. Its tailnet IP fills `callback_listen`.
2. Push `examples/system-policy.toml` with `[approvals]` filled in, and the token and key files (mode 600) to
   `/data/local/tmp/tin/`. The secrets go from the box's secret store to the phone by pipe, never through a chat.
3. D1 `--read`: real dump formats. If a parser comes back empty on real output, fix it against
   `core/tests/fixtures/device/` and replace the synthetic fixture. Review the dumps for personal data before
   committing any.
4. D2 `--bench 20`: per-tool latency; the screen_text stop condition decides on it.
5. D6 `--listen`: whether shell (uid 2000) may bind a TCP port on the tailnet address. UNKNOWN until run, and it
   decides between this listener and the dial-out channel.
6. D3 `--write` and D4 `--bank` (a banking app open, and logged in, on the new OS).

On the box (homelab-gitops PRs; the secret steps are Yehor's):

1. An approvals source token for `tin` (`scripts/secret.sh` on the box), copied to the phone's `token_file`.
2. An `APPROVALS_CALLBACK_ROUTES` entry mapping `https://tin.phone.invalid/approvals/` (the policy's `callback`) to
   `http://<phone tailnet>:7382/approvals/`.
3. `APPROVAL_HMAC_KEY` copied to the phone's `key_file`.
4. D5 `--callback` run from the box once 1–3 are live.
