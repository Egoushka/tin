# Spec: idempotent holds (FE-SESS-009)

Date: 2026-11-02. Status: scope from the phone-shell research (feature FE-SESS-009; facts FA-SESS-004, FA-SESS-005).
Builds on `2026-11-01-phone-mcp-system-tools.md` (the gate, S4).

## Goal

A repeat of a held call returns the ticket that is already waiting, instead of opening a second one.

Why: tin answers "pending" after `wait_secs` (50 s), shorter than the 60–120 s read timeouts common in MCP clients,
and `Gate::hold` mints a new ticket on every call (it checks only the pending count). A client that retries
`system__tap` instead of calling `approval__wait` opens a second ticket; if the owner approves both, the tap runs
twice. Value hypothesis: a retry cannot double a tap or a settings write, and the owner sees one card per intent.
Cheapest test: the checks below, which run off the phone.

## Behaviour

1. A call is identified by its **call hash**: SHA-256 of the tool name and the arguments as JSON with sorted keys
   (`serde_json` objects are sorted maps), so `{"x":1,"y":2}` and `{"y":2,"x":1}` are the same call.
2. `hold` first looks for a **pending** ticket with the same call hash. Found → it returns that ticket, opens no
   item, and the tool answer carries the note `already pending` (`structuredContent.note` and the text). Not found
   → the ticket opens as before.
3. The window is the ticket's own ttl (`ttl_secs`, 900 s): a ticket is pending until it is decided or expires, so
   there is no second timer. After a decision (run, deny) or expiry, the same call opens a new ticket, so an
   intentional second identical tap is possible.
4. A changed argument is a different call and opens its own ticket.
5. `MAX_PENDING` counts distinct tickets. A repeat of a pending call returns its ticket even at the cap.
6. The lookup and the insert happen under one lock, so two identical calls racing cannot both open a ticket.

**No session in the key.** The feature names a session as part of the index. tin issues no `Mcp-Session-Id`
(`transport.rs`: "no sessions": GET and DELETE are not offered) and serves one owner on one phone, so the call hash is
the finest identity available. Two agents making the same call share one ticket, which is right: one tap is one
decision about one action. If tin gains sessions, the session id joins the key in `Gate::hold` alone.

## Acceptance checks

Run off the phone (`cargo test`, CI).

| # | Check | Command | Pass when |
|---|---|---|---|
| I1 | Repeat returns the ticket | `cargo test -p tin-core gate_repeat_returns` | two identical `system__tap` calls give 1 approvals item; the second answer has the first's ticket and `note: "already pending"`; the same arguments in another key order are the same call |
| I2 | Looping client | `cargo test -p tin-core gate_repeat_loop` | 12 identical calls (above `MAX_PENDING`) give 1 item, 11 answers carry the note, none is an error |
| I3 | Different call, new ticket | `cargo test -p tin-core gate_repeat_different` | two taps at different points give 2 items; a swipe gives a third |
| I4 | After the ttl | `cargo test -p tin-core gate_repeat_after_ttl` | the same tap after the ttl gives a new item, and the first was withdrawn |
| I5 | Cap counts distinct tickets | `cargo test -p tin-core gate_repeat_cap` | with `MAX_PENDING` distinct taps pending, a repeat of one returns its ticket, a new point is refused, items stay at `MAX_PENDING` |
| I6 | Decided is not reused | `cargo test -p tin-core gate_repeat_decided` | after `deny` or after an approved run, the same call opens a new ticket, with no note |
| I7 | Concurrent identical calls | `cargo test -p tin-core gate_repeat_race` | 8 threads call `hold` with the same call at once → 1 item and 1 ticket id |
| I8 | Nothing else moved | `cargo test -p tin-core` and `cargo clippy --all-targets -- -D warnings` | S1–S8 and the rest still pass |

## Not in this change

- Returning the stored result of a call that already ran when it is repeated: a repeat after a decision is a new
  intent (point 3), not a retry.
- A session id in the key (see above).
- Calibrating the window on the device: it is the ttl, itself a starting value (FA-SESS-005).

## Stop condition

If a client is found whose second, different-intent call has identical arguments within 15 minutes of an undecided
first (so the owner sees one card for two intents), the window shortens below the ttl or the key gains a
caller-supplied id. Until a real client shows it, the ttl is the window.
