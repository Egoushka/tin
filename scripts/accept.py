#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=2.3"]
# ///
"""Device acceptance checks A6-A8 (docs/specs/2026-11-01-appfunctions-mcp-bridge.md).

Needs the phone on USB, unlocked, with tin serving on @tin (under Shizuku or `adb shell tin serve`) and the demo
policy (examples/demo-policy.toml) at /data/local/tmp/tin/policy.toml.

  uv run scripts/accept.py --policy demo --alarm 13:37   A6: list, one createAlarm, topUpPhone refused
  uv run scripts/accept.py --bench 20                    A7 + A8: bridge vs raw `cmd`, p50/p95
"""

import argparse
import asyncio
import json
import math
import re
import statistics
import subprocess
import sys
import time

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

PORT = 7381
URL = f"http://127.0.0.1:{PORT}/mcp"
DEMO_TOOLS = {"android_settings__getBatteryDeviceState", "ftband_mono__topUpPhone", "urbandroid_sleep__createAlarm"}
BENCH_TOOL = "android_settings__getBatteryDeviceState"
BENCH_RAW = ["adb", "shell", "cmd", "app_function", "execute-app-function",
             "--package", "com.android.settings", "--function", "getBatteryDeviceState", "--parameters", "{}"]
# Spec A8: bridge p95 minus raw p95 must stay under this (starting value, see the spec).
OVERHEAD_LIMIT_MS = 100


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def text_of(result) -> str:
    return " ".join(getattr(c, "text", "") for c in result.content)


def pct(values: list[float], q: float) -> float:
    """Nearest-rank percentile: p95 of 20 samples is the 19th smallest."""
    s = sorted(values)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def summary(name: str, ms: list[float]) -> dict:
    return {"path": name, "n": len(ms), "p50": round(pct(ms, 0.5)), "p95": round(pct(ms, 0.95)),
            "min": round(min(ms)), "max": round(max(ms)), "mean": round(statistics.fmean(ms))}


class Checks:
    def __init__(self):
        self.failed = 0

    def check(self, label: str, ok: bool, detail: str = ""):
        self.failed += not ok
        print(f"{'PASS' if ok else 'FAIL'} {label}{': ' + detail if detail else ''}")


async def demo(alarm: str, c: Checks):
    hour, minute = (int(x) for x in alarm.split(":"))
    async with streamable_http_client(URL) as (read, write), ClientSession(read, write) as s:
        init = await s.initialize()
        print(f"server: {init.server_info.name} {init.server_info.version}, protocol {init.protocol_version}")
        names = {t.name for t in (await s.list_tools()).tools}
        c.check("A6 tools/list is exactly the demo policy", names == DEMO_TOOLS, ", ".join(sorted(names)))

        r = await s.call_tool("urbandroid_sleep__createAlarm", {"hour": hour, "minute": minute})
        c.check(f"A6 createAlarm {alarm}", not r.is_error, text_of(r)[:300])

        r = await s.call_tool("ftband_mono__topUpPhone", {"phoneNumber": "+380000000000", "amount": 1.0})
        c.check("A6 topUpPhone refused", r.is_error and "approval" in text_of(r), text_of(r)[:200])


async def bench_bridge(n: int) -> tuple[list[float], list[str]]:
    ms, errors = [], []
    async with streamable_http_client(URL) as (read, write), ClientSession(read, write) as s:
        await s.initialize()
        await s.list_tools()
        await s.call_tool(BENCH_TOOL, {})  # warm-up, not counted
        for _ in range(n):
            t = time.perf_counter()
            r = await s.call_tool(BENCH_TOOL, {})
            ms.append((time.perf_counter() - t) * 1000)
            if r.is_error:
                errors.append(text_of(r)[:200])
    return ms, errors


def bench_raw(n: int, c: Checks) -> list[float]:
    ms = []
    subprocess.run(BENCH_RAW, capture_output=True)  # warm-up, not counted
    for _ in range(n):
        t = time.perf_counter()
        p = subprocess.run(BENCH_RAW, capture_output=True, text=True)
        ms.append((time.perf_counter() - t) * 1000)
        if p.returncode != 0 or p.stdout.startswith("Error"):
            c.check("A8 raw call", False, (p.stdout or p.stderr)[:200])
    return ms


def backend_ms(n: int, log: str) -> list[float]:
    """The last n `cmd` times tin logged itself: the function's cost with the bridge's own transport taken out."""
    try:
        tail = adb("shell", "tail", "-n", "200", log)
    except subprocess.CalledProcessError:
        return []
    found = re.findall(rf"called {BENCH_TOOL} ok=true (\d+) ms", tail)
    return [float(x) for x in found[-n:]]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--policy", choices=["demo"], help="run A6 against the demo policy")
    ap.add_argument("--alarm", default="13:37", help="HH:MM for the one createAlarm call (default 13:37)")
    ap.add_argument("--bench", type=int, metavar="N", help="run A7/A8 with N calls per path")
    ap.add_argument("--log", default="/data/local/tmp/tin/tin.log", help="tin's log on the phone")
    a = ap.parse_args()
    if not a.policy and not a.bench:
        ap.error("pass --policy demo and/or --bench N")

    adb("forward", f"tcp:{PORT}", "localabstract:tin")
    c = Checks()
    if a.policy:
        asyncio.run(demo(a.alarm, c))
    if a.bench:
        bridge, errors = asyncio.run(bench_bridge(a.bench))
        raw = bench_raw(a.bench, c)
        rows = [summary("bridge (MCP over adb forward)", bridge), summary("raw adb shell cmd", raw)]
        inner = backend_ms(a.bench, a.log)
        if len(inner) == a.bench:
            rows.append(summary("cmd inside tin (from its log)", inner))
        print("\n| path | n | p50 ms | p95 ms | min | max | mean |\n|---|---|---|---|---|---|---|")
        for r in rows:
            print(f"| {r['path']} | {r['n']} | {r['p50']} | {r['p95']} | {r['min']} | {r['max']} | {r['mean']} |")
        overhead = rows[0]["p95"] - rows[1]["p95"]
        c.check(f"A7 {a.bench - len(errors)} of {a.bench} bridge calls succeed", not errors, "; ".join(errors[:3]))
        c.check(f"A8 overhead p95 {overhead} ms <= {OVERHEAD_LIMIT_MS} ms", overhead <= OVERHEAD_LIMIT_MS)
        print(json.dumps({"rows": rows, "overhead_p95_ms": overhead}))
    sys.exit(1 if c.failed else 0)


if __name__ == "__main__":
    main()
