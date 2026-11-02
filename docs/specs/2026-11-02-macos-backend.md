# Spec: a macOS backend for tin (App Intents, Mac system tools, held writes)

Date: 2026-11-02. Status: spike. Written before the code; results are filled in under "Results".

## Goal

The same tin core serves a second platform. On a Mac, tin lists every App Intent that installed apps publish as an MCP
tool with a JSON Schema, runs the ones it can reach without the screen, and serves Mac system tools: memory pressure,
swap, top processes by resident memory (RSS), stale build and test processes, and launchd agents. Killing a process,
quitting an app and unloading an agent wait for a tap in the approvals app, through the same signed-decision path the
phone uses.

The policy, the money rule and the approvals gate stay one copy of code, shared by the Android and macOS backends: the
core is abstracted from two real consumers.

Value hypothesis: Mac users of AI agents get typed, policy-gated control of their apps without clicking screenshots.
Cheapest test: the tool count from M1 plus one intent invoked from an MCP call (M2).

First consumer: a "Mac governor". The 16 GB MacBook this was written on had 11 of 12 GB of swap in use. The cause was
leftover build processes: 30 idle MSBuild nodes, 8 vstest runners and a `Switchknife.Pace.Tests` host alive for 2
days. `system__stale_processes` finds them and, above a swap threshold, proposes `system__kill` calls; each kill waits
for a tap.

## Facts this rests on

- Every app built with the App Intents framework ships its metadata as JSON at
  `<bundle>/Contents/Resources/Metadata.appintents/extract.actionsdata` (frameworks keep it under
  `Versions/A/Resources/`). Some apps add a second file at `Contents/Resources/Link.data/extract.actionsdata`
  (Weather: 11 actions). On this Mac (macOS 27.0, Darwin 27.0.0) there are 60 `Metadata.appintents` files under
  `/Applications` and `/System/Applications`, nested PlugIns, Frameworks and Extensions included, holding 324
  actions, plus Weather's `Link.data` with 11: 61 files, 335 actions. 103 of the 335 are not discoverable
  (`isDiscoverable` false or hidden from Shortcuts). 115 of the 324 set `openAppWhenRun`.
  - Top-level keys: `actions` (an object keyed by identifier), `entities` (an object), `enums` (an array),
    `queries`, `autoShortcuts`, `generator`, `version` (1 in all 60).
  - Each action carries `identifier`, `title.key`, `descriptionMetadata.descriptionText.key`, `mangledTypeName`,
    `openAppWhenRun`, `isDiscoverable`, `parameters[]` and `outputType`.
  - Each parameter carries `name`, `title.key`, `parameterDescription.key`, `isOptional`, `valueType` and
    `typeSpecificMetadata`, a flat `[key, value, key, value]` list: defaults, number bounds, file UTIs.
  - `valueType` kinds across the 324 actions: `entity` 168, `linkEnumeration` 94, `array` 87, `primitive` 177
    (type ids 0 string 59, 1 bool 73, 2 integer 10, 7 double 4, 8 date 4, 9 date components 4, 11 URL 5,
    12 attributed string 18), `alternative` 14, `searchCriteria` 8, `intents` 6, `builtIn` 1, `measurement` 1.
- No action carries a read-only marker, as on Android: read-only is declared in the policy.
- Calling an intent directly is closed. `LinkServices.framework` (private) builds an `LNAction` from the
  actionsdata's `mangledTypeName` and an `LNActionExecutor` for it, but:
  - `perform` fails in 0.5 ms with `LNConnectionErrorCodeMissingConnectionEntitlement` (2700), a client-side check
    for `com.apple.private.appintents.connection` and `com.apple.private.dmd.policy`;
  - with that check swizzled out, `com.apple.linkd.mediator` drops the connection (XPC 4097) after 11.9 ms: linkd
    enforces the private entitlement itself, so only Apple-signed binaries get through while SIP is on;
  - `LNMetadataProvider` is refused the same way (`com.apple.linkd.registry`, XPC 4097).
  The probe sources are in this spike's notes; no code here uses private API.
- `shortcuts run <name> [-i input] [-o output]` runs a shortcut from the Shortcuts library with no window. A lookup
  of a missing shortcut fails in 79 ms p50, 89 ms p95 (20 runs, 2026-11-02): the path's floor. `shortcuts` has no
  import command; `shortcuts sign` signs a generated `.shortcut` file, and opening it shows Shortcuts' "Add Shortcut"
  sheet, which needs one tap per intent.
- Prior art (web search, 2026-11-02): existing Mac MCP servers drive apps through AppleScript or run named Shortcuts;
  none lists App Intents from their metadata.

## Design

### Shared core

`policy.rs`, `gate.rs` and `mcp.rs` keep no platform code. The platform lives behind `Backend`:

- `functions()`: the app functions to serve (Android: `cmd app_function list-app-functions` parsed by `metadata.rs`;
  macOS: every actionsdata file under the scan roots, parsed by `macos.rs`).
- `execute(package, id, parameters)`, `shell(program, argv)`: as before.
- `verbs()`: the platform's system verb table; `instructions()`: the `initialize` text.

A `Verb` carries an `Ops` table (schema, argv, describe, run), so each platform's verbs keep their own argument checks
and parsers. `describe` may read state (the approvals title for a kill names the process).

### App Intents on macOS

- A tool per discoverable action, named `<last two bundle id segments>__<identifier>`. The description is the
  action's title and description; `[opens the app]` marks `openAppWhenRun`.
- Schema mapping: string, boolean, integer, number (double, measurement), `format: date-time` (date), `format: uri`
  (URL), `enum` of case identifiers (`linkEnumeration`, resolved through `enums`), a string id (`entity`,
  `searchCriteria`, `builtIn`, legacy `intents`), `array` of the member type, `anyOf` (`alternative`). Defaults and
  number bounds come from `typeSpecificMetadata`.
- The money rule applies unchanged: `policy::is_money` on bundle id and identifier.
- Invocation goes through Shortcuts. `tin shortcut <bundle> <identifier>` writes a signed one-action shortcut named
  `tin <bundle>.<identifier>` (no colon: Shortcuts drops it when it names an import after the file); it is imported
  once by opening it. `execute` runs it with `shortcuts run`, stdin
  closed, output to a temporary file, with a timeout. Parameters are not wired into the shortcut in this slice: a call
  with arguments is refused before anything runs.

### Mac system tools

Read: `memory` (`sysctl`, `vm_stat`: RAM, pressure level, swap, page classes), `processes` (`ps`, top N by RSS),
`stale_processes` (MSBuild nodes, VBCSCompiler, vstest, test hosts older than `min_age_minutes`; above
`swap_threshold_mb` it returns `system__kill` proposals), `launch_agents` (`launchctl list`).

Write, always held: `kill` (`pid` and the `started` time from `ps`; at run time the start time is checked again, so a
reused pid is never killed), `quit_app` (`osascript`, bundle id validated to `[A-Za-z0-9.-]`), `unload_agent`
(`launchctl bootout gui/<uid>/<label>`, never a `com.apple.` label).

Thresholds are starting values to calibrate, not measured: `min_age_minutes` 30 (an MSBuild node with node reuse
idles out on its own after 15 minutes, so twice that is past normal reuse) and `swap_threshold_mb` 4096 (a quarter of
RAM on this Mac; the observed bad state was 11 GB).

## Out of scope

HTTP transport and a launchd service for tin on the Mac (stdio only), passing intent parameters through the
shortcut, entities' own queries (listing a Notes folder to fill an entity id), the ToolKit database
(`~/Library/Shortcuts/ToolKit`, 2056 tools, binary parameter types) as a second catalog.

## Acceptance checks

| Id | What | How | Pass |
|---|---|---|---|
| M1 | Catalog | `cargo test macos_` on a synthetic actionsdata fixture with every value kind; `tin list --platform macos --all` on this Mac | every kind maps to the schema above; the live count equals the survey's 324 actions minus non-discoverable ones, each with an object `inputSchema` |
| M2 | Headless invoke | `tin shortcut com.sindresorhus.Week-Number GetWeekNumberIntent`, one import tap, then `scripts/mac-check.py --bench 20` | 20 MCP calls return the week number with no window shown; p50 and p95 recorded; pass at p95 under 1000 ms (starting bar: the phone path is 1.5 s) |
| M3 | Mac system tools | `cargo test macos_` on outputs captured from this Mac; `scripts/mac-check.py --read` | the 4 read verbs return parsed JSON; stale detection classifies the captured MSBuild, vstest and testhost lines and proposes kills only above the threshold |
| M4 | Held writes | `cargo test macos_` with the fake approvals inbox | `kill`, `quit_app`, `unload_agent` hold; zero processes run before a signed `run`; exactly one `kill` argv after it; a changed start time refuses the kill; `com.apple.` labels and bad bundle ids fail before anyone is asked |
| M5 | Shared core | `cargo test`, `cargo clippy --all-targets -- -D warnings` | Android tests pass unchanged apart from `describe` taking a shell; `policy.rs` and `gate.rs` untouched |

## Stop condition

If M2 finds no headless invoke path, ship M3 and M4 alone as the Mac governor and record M1 as a catalog without
calls. M2's partial outcome (headless calls after a one-time tap per intent) does not trigger it; it is recorded as is.

## Results

Run 2026-11-02 on a 16 GB MacBook, macOS 27.0.

| Id | Result | Evidence |
|---|---|---|
| M1 | pass | `macos_parse_maps_every_value_kind` covers every kind; `tin list --platform macos --all` lists 232 App Intent tools from 32 bundles (335 − 103 non-discoverable = 232) plus 7 system verbs; `mac-check.py --count` serves all 232, each with an object `inputSchema`, none refused as money |
| M2 | pass (with a load caveat) | `shortcuts run` floor 79 / 89 ms p50 / p95. After the import tap, `tools/call` returned `45` (ISO week 45, correct) for the Week Number intent with no window shown, so the shortcut runs the intent and `-o` captures it. 5 runs of 20 calls, p50 / p95 / max in ms: 401 / 614 / 1305; 410 / 1315 / 1558; 399 / 562 / 700; 382 / 812 / 969; 483 / 786 / 842. Median p95 786 ms: 4 of 5 runs under the 1000 ms bar. The failing run was on a loaded Mac (load average 33-40, swap 9 of 10 GB); the first call of a run is the slowest. Two bugs fixed on the way: Shortcuts drops the colon from `tin: ...` when naming an import, and `shortcuts run -o` writes nothing without `--output-type public.plain-text`. A second import of the same intent creates a duplicate name, and `shortcuts run <name>` then fails with "Couldn't find shortcut" (run by id works). Raw: `docs/results/2026-11-02-macos-intent-bench.json` (last run) |
| M3 | pass | 8 `macos_` parser and stale-detection tests on synthetic outputs; live `mac-check.py --read`: memory 17 ms (pressure warning, 7.6 of 9 GB swap used), processes 135 ms, stale_processes 123 ms (1 test host, 1 kill proposal), launch_agents 15 ms |
| M4 | pass | `macos_kill_waits_for_a_tap_then_runs_once`, `macos_kill_refuses_a_reused_pid`, `macos_deny_never_kills`, `macos_quit_app_and_unload_agent_run_after_a_tap`, `macos_bad_arguments_fail_before_anyone_is_asked` (9 bad calls, zero inbox items, zero write processes) |
| M5 | pass | `cargo test`: 66 tests pass (12 bridge, 19 macos, 35 system; the 3 socket tests need a sandbox that allows binds); `cargo clippy --all-targets --locked -- -D warnings` clean; `git diff main -- core/src/policy.rs core/src/gate.rs` is empty |

Rejected: calling intents through private `LinkServices` (see Facts). The stop condition did not fire: the Shortcuts
path is headless after a one-time tap per intent.

The stale-process numbers are lower than the ones that motivated the spec (30 MSBuild nodes, 11 GB of swap): those
processes had ended before the run, so the live kill proposal is a single test host.
