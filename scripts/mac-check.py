#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=2.3"]
# ///
"""Mac checks M1-M3 (docs/specs/2026-11-02-macos-backend.md), over stdio against target/debug/tin on this Mac.

Read-only. Uses examples/macos-policy.toml unless --policy names another file.

  uv run scripts/mac-check.py --count       M1: tools listed with --all, each with an object inputSchema
  uv run scripts/mac-check.py --read        M3: the 4 read tools return parsed JSON (nothing is saved: ps output is
                                            personal data)
  uv run scripts/mac-check.py --bench 20    M2: p50/p95 of the Week Number intent through Shortcuts, written to
                                            docs/results/
"""

import argparse
import asyncio
import contextlib
import json
import math
import pathlib
import platform
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
TIN = ROOT / "target/debug/tin"
READ_TOOLS = ["memory", "processes", "stale_processes", "launch_agents"]
INTENT_TOOL = "sindresorhus_Week-Number__GetWeekNumberIntent"
# Spec M2 bar (starting value: the phone's AppFunctions path takes 1.5 s).
INTENT_P95_LIMIT_MS = 1000


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
async def session(policy: pathlib.Path):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    params = StdioServerParameters(command=str(TIN), args=["serve", "--stdio", "--platform", "macos", "--policy", str(policy)])
    async with stdio_client(params) as (read, write), ClientSession(read, write) as s:
        await s.initialize()
        yield s


def count(c: Checks) -> list[str]:
    out = subprocess.run([str(TIN), "list", "--all", "--platform", "macos"], check=True, capture_output=True, text=True).stdout
    lines = [line.split() for line in out.splitlines() if line.strip()]
    apps = [line for line in lines if not line[1].startswith("system__")]
    bundles = sorted({line[2] for line in apps})
    c.check("M1 tin list --all", len(apps) > 0, f"{len(apps)} App Intent tools from {len(bundles)} bundles, {len(lines) - len(apps)} system verbs")
    return bundles


async def schemas(bundles: list[str], c: Checks):
    """Serves every bundle under a throwaway policy (listing only, nothing is called) and checks each schema."""
    rules = "".join(f'[[allow]]\npackage = "{b}"\nfunction = "*"\nread_only = true\n\n' for b in bundles)
    with tempfile.NamedTemporaryFile("w", suffix=".toml") as f:
        f.write(rules)
        f.flush()
        async with session(pathlib.Path(f.name)) as s:
            tools = [t for t in (await s.list_tools()).tools if "__" in t.name and not t.name.startswith(("system__", "approval__"))]
    bad = [t.name for t in tools if t.input_schema.get("type") != "object"]
    c.check("M1 every App Intent tool has an object inputSchema", not bad, f"{len(tools)} served (money ids refused)" + (f", bad: {bad}" if bad else ""))


async def read(policy: pathlib.Path, c: Checks):
    async with session(policy) as s:
        names = {t.name for t in (await s.list_tools()).tools}
        for verb in READ_TOOLS:
            tool = f"system__{verb}"
            if tool not in names:
                c.check(f"M3 {tool}", False, "not listed: is it in the policy?")
                continue
            t = time.perf_counter()
            r = await s.call_tool(tool, {})
            ms = (time.perf_counter() - t) * 1000
            body = text_of(r)
            try:
                value = json.loads(body)
            except ValueError:
                c.check(f"M3 {tool}", False, body[:300])
                continue
            c.check(f"M3 {tool}", not r.is_error and bool(value), f"{ms:.0f} ms, keys {sorted(value)}")
            if verb == "memory":
                print(f"  ram {value['ram_mb']} MB, pressure {value['pressure']}, swap used {value['swap']['used_mb']} of {value['swap']['total_mb']} MB")
            if verb == "stale_processes":
                kinds: dict[str, int] = {}
                for p in value["stale"]:
                    kinds[p["kind"]] = kinds.get(p["kind"], 0) + 1
                print(f"  stale {kinds or 'none'}, {value['stale_rss_mb']} MB, over threshold: {value['over_threshold']}, {len(value['proposals'])} kill proposals")


async def bench(policy: pathlib.Path, n: int, c: Checks):
    ms = []
    async with session(policy) as s:
        for i in range(n):
            t = time.perf_counter()
            r = await s.call_tool(INTENT_TOOL, {})
            ms.append((time.perf_counter() - t) * 1000)
            if r.is_error:
                c.check(f"M2 {INTENT_TOOL}", False, text_of(r)[:300])
                return
            if i == 0:
                print(f"  first answer: {text_of(r)[:120]!r}")
    row = {"tool": INTENT_TOOL, "n": n, "p50": round(pct(ms, 0.5)), "p95": round(pct(ms, 0.95)), "max": round(max(ms))}
    c.check(f"M2 p95 under {INTENT_P95_LIMIT_MS} ms", row["p95"] <= INTENT_P95_LIMIT_MS, f"p50 {row['p50']} ms, p95 {row['p95']} ms, max {row['max']} ms")
    results = ROOT / "docs/results"
    results.mkdir(parents=True, exist_ok=True)
    path = results / f"{time.strftime('%Y-%m-%d')}-macos-intent-bench.json"
    path.write_text(json.dumps({"mac": platform.mac_ver()[0], "rows": [row]}, indent=2) + "\n")
    print(f"written to {path.relative_to(ROOT)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", action="store_true")
    ap.add_argument("--read", action="store_true")
    ap.add_argument("--bench", type=int, metavar="N")
    ap.add_argument("--policy", type=pathlib.Path, default=ROOT / "examples/macos-policy.toml")
    a = ap.parse_args()
    if not (a.count or a.read or a.bench):
        ap.print_help()
        sys.exit(2)
    if not TIN.exists():
        sys.exit(f"{TIN} missing: run cargo build first")
    c = Checks()
    if a.count:
        asyncio.run(schemas(count(c), c))
    if a.read:
        asyncio.run(read(a.policy, c))
    if a.bench:
        asyncio.run(bench(a.policy, a.bench, c))
    sys.exit(1 if c.failed else 0)


if __name__ == "__main__":
    main()
