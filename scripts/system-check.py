#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=2.3"]
# ///
"""Device checks D1-D6 (docs/specs/2026-11-01-phone-mcp-system-tools.md).

Needs the phone on USB, unlocked, with tin serving on @tin and examples/system-policy.toml (with its [approvals]
table filled in for D3, D5 and D6) at /data/local/tmp/tin/policy.toml. Read-only unless --write.

  uv run scripts/system-check.py --read            D1: the 6 read tools; raw dumps saved to core/tests/fixtures/device/
  uv run scripts/system-check.py --bench 20        D2: p50/p95 per read tool, written to docs/results/
  uv run scripts/system-check.py --write           D3: system__key KEYCODE_HOME through the approvals app (Run, then Deny)
  uv run scripts/system-check.py --bank            D4: screen text refused while a banking app is in front
  uv run scripts/system-check.py --listen          D6: tin (uid 2000) holds the callback TCP port
  python3 scripts/system-check.py --callback URL   D5, from the box: URL/approvals/decision answers 401 to a bad signature
"""

import argparse
import asyncio
import contextlib
import json
import math
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = 7381
URL = f"http://127.0.0.1:{PORT}/mcp"
ROOT = pathlib.Path(__file__).resolve().parent.parent
READ_TOOLS = ["notifications", "usage", "foreground_app", "screen_text", "battery", "connectivity"]
# Raw dumps the parsers read, saved by D1 so the parser tests can run on real output (spec S2).
DUMPS = {
    "notification.txt": ["dumpsys", "notification", "--noredact"],
    "usagestats.txt": ["dumpsys", "usagestats"],
    "activities.txt": ["dumpsys", "activity", "activities"],
    "battery.txt": ["dumpsys", "battery"],
    "connectivity.txt": ["dumpsys", "connectivity"],
}
# May be empty on a phone that was just set up: recorded, not failed.
MAY_BE_EMPTY = {"notifications": "notifications", "usage": "apps"}
BANKS = {"com.ftband.mono": "monobank", "ua.privatbank.ap24": "Privat24"}
# Spec stop condition: screen_text p95 above this moves screen reading to an accessibility service (starting value).
SCREEN_P95_LIMIT_MS = 3000
CALLBACK_PORT = 7382


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def text_of(result) -> str:
    return " ".join(getattr(c, "text", "") for c in result.content)


def pct(values: list[float], q: float) -> float:
    """Nearest-rank percentile: p95 of 20 samples is the 19th smallest."""
    s = sorted(values)
    return s[max(0, math.ceil(q * len(s)) - 1)]


class Checks:
    def __init__(self):
        self.failed = 0

    def check(self, label: str, ok: bool, detail: str = ""):
        self.failed += not ok
        print(f"{'PASS' if ok else 'FAIL'} {label}{': ' + detail if detail else ''}")


@contextlib.asynccontextmanager
async def session():
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client

    async with streamable_http_client(URL) as (read, write), ClientSession(read, write) as s:
        await s.initialize()
        yield s


async def read(c: Checks):
    out = ROOT / "core/tests/fixtures/device"
    out.mkdir(parents=True, exist_ok=True)
    for name, argv in DUMPS.items():
        (out / name).write_text(adb("shell", *argv))
    adb("shell", "uiautomator", "dump", "/data/local/tmp/tin/ui-capture.xml")
    (out / "ui.xml").write_text(adb("shell", "cat", "/data/local/tmp/tin/ui-capture.xml"))
    adb("shell", "rm", "-f", "/data/local/tmp/tin/ui-capture.xml")
    print(f"raw dumps saved to {out.relative_to(ROOT)} (review them for personal data before committing)")
    async with session() as s:
        names = {t.name for t in (await s.list_tools()).tools}
        for verb in READ_TOOLS:
            tool = f"system__{verb}"
            if tool not in names:
                c.check(f"D1 {tool}", False, "not listed: is it in the policy?")
                continue
            r = await s.call_tool(tool, {})
            body = text_of(r)
            try:
                value = json.loads(body)
            except ValueError:
                c.check(f"D1 {tool}", False, body[:300])
                continue
            key = MAY_BE_EMPTY.get(verb)
            empty = not value or (key and not value.get(key))
            if key and empty and not r.is_error:
                print(f"NOTE D1 {tool}: empty {key} (allowed on a fresh phone)")
            c.check(f"D1 {tool}", not r.is_error and (bool(key) or not empty), body[:200])


async def bench(n: int, c: Checks):
    rows = []
    async with session() as s:
        for verb in READ_TOOLS:
            ms = []
            for _ in range(n):
                t = time.perf_counter()
                r = await s.call_tool(f"system__{verb}", {})
                ms.append((time.perf_counter() - t) * 1000)
                if r.is_error:
                    c.check(f"D2 system__{verb}", False, text_of(r)[:200])
                    break
            if len(ms) == n:
                rows.append({"tool": f"system__{verb}", "n": n, "p50": round(pct(ms, 0.5)), "p95": round(pct(ms, 0.95)), "max": round(max(ms))})
                print(f"D2 system__{verb}: p50 {rows[-1]['p50']} ms, p95 {rows[-1]['p95']} ms")
    screen = next((r for r in rows if r["tool"] == "system__screen_text"), None)
    if screen:
        c.check(f"D2 screen_text p95 under {SCREEN_P95_LIMIT_MS} ms", screen["p95"] <= SCREEN_P95_LIMIT_MS, f"{screen['p95']} ms")
    results = ROOT / "docs/results"
    results.mkdir(parents=True, exist_ok=True)
    path = results / f"{time.strftime('%Y-%m-%d')}-system-tools-bench.json"
    path.write_text(json.dumps({"device": adb("shell", "getprop", "ro.product.model").strip(), "rows": rows}, indent=2) + "\n")
    print(f"written to {path.relative_to(ROOT)}")


async def settle(s, r, label: str):
    """Follows a held call through approval__wait until it is decided."""
    while not r.is_error and (r.structuredContent or {}).get("status") == "pending":
        print(f"{label}: waiting for the tap (ticket {r.structuredContent['ticket']})")
        r = await s.call_tool("approval__wait", {"ticket": r.structuredContent["ticket"]})
    return r


async def write(c: Checks):
    async with session() as s:
        print("D3 step 1: an item 'tin: press KEYCODE_HOME' appears in the approvals app. Tap Run.")
        t = time.perf_counter()
        r = await settle(s, await s.call_tool("system__key", {"key": "KEYCODE_HOME"}), "run")
        c.check("D3 Run → the call succeeds", not r.is_error, f"{text_of(r)[:200]} after {time.perf_counter() - t:.1f} s")
        print("D3 step 2: a second item appears. Tap Deny.")
        r = await settle(s, await s.call_tool("system__key", {"key": "KEYCODE_HOME"}), "deny")
        c.check("D3 Deny → isError, nothing runs", r.is_error and "denied" in text_of(r), text_of(r)[:200])
    print("D3: confirm by eye that the home screen showed once, after the Run tap.")


async def bank(c: Checks):
    fg = adb("shell", "dumpsys", "activity", "activities")
    open_bank = next((name for pkg, name in BANKS.items() if pkg in fg), None)
    if not open_bank:
        c.check("D4 a banking app is in front", False, "open monobank or Privat24 first")
        return
    async with session() as s:
        r = await s.call_tool("system__screen_text", {})
        c.check(f"D4 screen_text refused with {open_bank} open", r.is_error and "banking app" in text_of(r), text_of(r)[:200])


def listen(c: Checks):
    ss = adb("shell", "ss", "-ltnp")
    line = next((l for l in ss.splitlines() if f":{CALLBACK_PORT} " in l), "")
    c.check(f"D6 something listens on :{CALLBACK_PORT}", bool(line), line.strip() or "nothing; check tin.log for 'bind'")
    log = adb("shell", "tail", "-n", "200", "/data/local/tmp/tin/tin.log")
    c.check("D6 tin logged the callback listener", "approvals callback on" in log)


def callback(url: str, c: Checks):
    body = json.dumps({"decision": "01JB0000000000000000000000", "id": "tin:0-d5", "action": "run", "edited": {},
                       "at": "2026-11-01T00:00:00.000Z", "sig": "00"}).encode()
    req = urllib.request.Request(url.rstrip("/") + "/approvals/decision", body, {"content-type": "application/json"})
    try:
        code = urllib.request.urlopen(req, timeout=10).status
    except urllib.error.HTTPError as e:
        code = e.code
    except OSError as e:
        c.check("D5 the callback listener is reachable", False, str(e))
        return
    c.check("D5 a bad signature gets 401", code == 401, f"HTTP {code}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--read", action="store_true")
    ap.add_argument("--bench", type=int, metavar="N")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--bank", action="store_true")
    ap.add_argument("--listen", action="store_true")
    ap.add_argument("--callback", metavar="URL", help="tin's callback base, e.g. http://<phone tailnet ip>:7382")
    a = ap.parse_args()
    c = Checks()
    if a.callback:
        callback(a.callback, c)
    phone = a.read or a.bench or a.write or a.bank or a.listen
    if phone:
        adb("forward", f"tcp:{PORT}", "localabstract:tin")
    if a.read:
        asyncio.run(read(c))
    if a.bench:
        asyncio.run(bench(a.bench, c))
    if a.write:
        asyncio.run(write(c))
    if a.bank:
        asyncio.run(bank(c))
    if a.listen:
        listen(c)
    if not (phone or a.callback):
        ap.print_help()
        sys.exit(2)
    sys.exit(1 if c.failed else 0)


if __name__ == "__main__":
    main()
