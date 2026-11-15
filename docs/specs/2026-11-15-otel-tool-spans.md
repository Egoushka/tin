# Spec: an OpenTelemetry span per tool call (tin/P04)

Date: 2026-11-15. Status: approved wave 2 (proposal tin/P04, borrows mobilerun/003).

## Goal

Every tool call tin serves emits one span (plus one child span while it waits for an approvals tap) to an OTLP/HTTP
endpoint, carrying the caller's run id and joining the caller's trace when it sends a W3C `traceparent`.
Value hypothesis: one trace shows a phone action from the agent's prompt to the tap decision, so debugging happens in
one place. Cheapest test: the checks below (off the phone), then one real call viewed in Tempo.

## Behaviour

1. Off by default. `[telemetry] otlp_url = "http://host:4318/v1/traces"` (optional `service_name`, default `tin`)
   turns it on. Only `http://` (the tailnet carries the encryption; tin has no TLS). An `https://` or malformed URL
   is refused at startup with a message; tin keeps serving without telemetry.
2. One span `tin.tool_call` per `tools/call`, kind SERVER, with attributes: `tin.tool`, `tin.policy.decision`
   (`allow`, `approval`, `refuse`, `unknown`, `builtin`), `tin.outcome` (`ok`, `error`, `pending`, `denied`,
   `expired`), `tin.ticket` (when held) and `jarvis.run_id` (when the caller sent one). Status ERROR when the call
   returned `isError`.
3. A held call also emits a child span `tin.approval.wait`: from the hold to the decision (or to the wait running
   out), with `tin.ticket`.
4. **Privacy.** Spans never carry arguments, results, error text or titles: those hold what is on the owner's screen.
5. **Context.** The caller's context comes from `params._meta.traceparent` and `params._meta.run_id`, or, over HTTP,
   from the `traceparent` and `x-run-id` request headers (the `_meta` value wins). A valid `traceparent` makes the
   span its child (same trace id, parent span id). An invalid one is ignored and a new trace starts.
6. **Never in the way.** Spans go through a bounded queue to a background sender with a 2 s timeout. A dead
   collector, a full queue or a bad answer drops spans and never delays or fails a tool call.
7. Exports use OTLP/HTTP JSON (`resourceSpans`, nanosecond times as strings).

## Acceptance checks

Run off the phone (`cargo test`, CI). Tests are in `core/tests/telemetry.rs`.

| # | Check | Pass when |
|---|---|---|
| T1 | allowed read call | one span, `tin.tool`, decision `allow`, outcome `ok`, status OK, no parent |
| T2 | `_meta.traceparent` + `run_id` | span has that trace id and parent id, and `jarvis.run_id` |
| T3 | invalid `traceparent` | new 32-hex trace id, no parent |
| T4 | held call approved | `tin.approval.wait` child of the call span, outcome `ok`, ticket on both |
| T5 | call to an unknown tool | decision `unknown`, status ERROR, outcome `error` |
| T6 | privacy | no arguments, result text or error text in any span's JSON |
| T7 | OTLP body | `resourceSpans[0].resource` has `service.name`; ids hex of length 32 and 16; times are strings |
| T8 | policy | `[telemetry]` parses; `https://` url is rejected by the sink |
| T9 | HTTP headers | `traceparent` and `x-run-id` headers reach the span |
| T10 | dead collector | sink on a closed port: call still returns in under 1 s |
