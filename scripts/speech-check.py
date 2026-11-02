#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=2.3", "jiwer==4.0.0"]
# ///
"""Device checks S7 and S8 (docs/specs/2026-11-01-speech.md). Exits 2 with "no device" until the phone is there.

Needs the phone on USB with tin serving on @tin, `[speech] enabled = true` in /data/local/tmp/tin/policy.toml, the
model on the phone (scripts/speech-push-model.sh) and tin's screen opened once so SpeechService runs. Never flashes,
unlocks or reboots anything: it only pushes WAV files into the debug app's own folder and calls MCP tools.

  uv run scripts/speech-check.py                  S7: 20 FLEURS uk clips through speech__transcribe {file}
  uv run scripts/speech-check.py --mic 5          S8: speak 5 phrases into the phone
"""

import argparse
import asyncio
import importlib.util
import json
import math
import subprocess
import sys
import time
from pathlib import Path

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

PORT = 7381
URL = f"http://127.0.0.1:{PORT}/mcp"
APP = "io.github.egoushka.tin"
ROOT = Path(__file__).resolve().parent.parent
# Spec S7: pass below PASS_RTF at p95; above STOP_RTF the plan switches to Moonshine base-uk (proposal part 5).
PASS_RTF, STOP_RTF = 0.5, 1.0
# Silero VAD closes a segment after this much silence (SherpaVad.minSilenceDuration); part of end-of-speech latency.
VAD_TAIL_MS = 500


def load_bench():
    spec = importlib.util.spec_from_file_location("bench_run", ROOT / "bench" / "run.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def adb(*args: str, stdin=None) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, stdin=stdin).stdout.decode()


def device_present() -> bool:
    try:
        return subprocess.run(["adb", "get-state"], capture_output=True, text=True, timeout=10).stdout.strip() == "device"
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False


def pct(values: list[float], q: float) -> float:
    """Nearest-rank percentile: p95 of 20 samples is the 19th smallest."""
    s = sorted(values)
    return s[max(0, math.ceil(q * len(s)) - 1)]


def push_clips(rows: list[dict]):
    """Pipes each clip into the app's files/bench through run-as (debug builds), so no shared folder is needed."""
    adb("shell", f"run-as {APP} mkdir -p files/bench")
    for r in rows:
        name = Path(r["file"]).name
        with open(r["file"], "rb") as f:
            adb("shell", f"run-as {APP} sh -c 'cat > files/bench/{name}'", stdin=f)


async def call(s: ClientSession, args: dict) -> tuple[dict, float]:
    t = time.perf_counter()
    r = await s.call_tool("speech__transcribe", args)
    wall_ms = (time.perf_counter() - t) * 1000
    text = " ".join(getattr(c, "text", "") for c in r.content)
    if r.is_error:
        raise RuntimeError(text)
    return json.loads(text), wall_ms


async def run(a) -> int:
    bench = load_bench()
    failed = 0
    async with streamable_http_client(URL) as (read, write), ClientSession(read, write) as s:
        await s.initialize()
        names = {t.name for t in (await s.list_tools()).tools}
        if "speech__transcribe" not in names:
            print("FAIL speech tools not listed: add `[speech]\\nenabled = true` to /data/local/tmp/tin/policy.toml")
            return 1
        if a.mic:
            print(f"S8: {a.mic} tries. Say a short phrase after each prompt, then stay quiet.")
            decode, overhead, texts = [], [], []
            for i in range(a.mic):
                input(f"[{i + 1}/{a.mic}] press Enter, then speak... ")
                r, wall = await call(s, {"seconds": 10})
                texts.append(r["text"])
                decode.append(r["decode_ms"])
                print(f"  heard: {r['text']!r} ({r['audio_s']:.1f} s of speech, decode {r['decode_ms']} ms)")
            lat = [VAD_TAIL_MS + d for d in decode]
            empty = sum(not t.strip() for t in texts)
            print(f"\nend of speech -> text = VAD tail {VAD_TAIL_MS} ms + decode: p50 {pct(lat, 0.5):.0f} ms, p95 {pct(lat, 0.95):.0f} ms"
                  " (plus the bridge, a few ms; see S7's overhead)")
            ok = empty == 0
            failed += not ok
            print(f"{'PASS' if ok else 'FAIL'} S8 {a.mic - empty} of {a.mic} phrases gave text")
            print(json.dumps({"check": "S8", "latency_p50_ms": pct(lat, 0.5), "latency_p95_ms": pct(lat, 0.95), "texts": texts},
                             ensure_ascii=False))
            return failed

        rows = bench.clips(Path(a.data), "uk", a.clips)
        print(f"S7: pushing {len(rows)} FLEURS uk clips to {APP} files/bench ...")
        push_clips(rows)
        await call(s, {"file": Path(rows[0]["file"]).name})  # warm-up: first decode pays for page-ins
        rtf, overhead, hyps = [], [], []
        for r in rows:
            res, wall = await call(s, {"file": Path(r["file"]).name})
            rtf.append(res["decode_ms"] / 1000 / res["audio_s"])
            overhead.append(wall - res["decode_ms"])
            hyps.append(res["text"])
        import jiwer
        wer = jiwer.wer([bench.normalise(r["ref"]) for r in rows], [bench.normalise(h) for h in hyps]) * 100
        p50, p95 = pct(rtf, 0.5), pct(rtf, 0.95)
        print(f"\n| clips | RTF p50 | RTF p95 | WER % | call minus decode p50 ms |\n|---|---|---|---|---|\n"
              f"| {len(rows)} | {p50:.3f} | {p95:.3f} | {wer:.1f} | {pct(overhead, 0.5):.0f} |")
        if p95 > STOP_RTF:
            print(f"STOP S7 RTF p95 {p95:.2f} > {STOP_RTF}: switch to Moonshine base-uk (proposal part 5)")
            failed += 1
        else:
            ok = p95 < PASS_RTF
            failed += not ok
            print(f"{'PASS' if ok else 'FAIL'} S7 RTF p95 {p95:.3f} < {PASS_RTF}")
        print(json.dumps({"check": "S7", "rtf_p50": p50, "rtf_p95": p95, "wer": wer, "clips": len(rows)}))
    return failed


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=str(ROOT / "bench" / ".cache" / "data"), help="FLEURS dir from bench/run-all.sh")
    ap.add_argument("--clips", type=int, default=20)
    ap.add_argument("--mic", type=int, metavar="N", help="S8 instead: N spoken phrases")
    a = ap.parse_args()
    if not device_present():
        print("no device")
        sys.exit(2)
    adb("forward", f"tcp:{PORT}", "localabstract:tin")
    sys.exit(1 if asyncio.run(run(a)) else 0)


if __name__ == "__main__":
    main()
