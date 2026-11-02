# /// script
# requires-python = ">=3.11"
# dependencies = ["sherpa-onnx==2.0.1", "soundfile==0.13.1", "numpy>=2", "jiwer==4.0.0"]
# ///
"""Speech benchmark on the Mac: Parakeet-tdt-0.6b-v3 int8 (sherpa-onnx) vs Whisper (whisper.cpp) on FLEURS test.

Every (engine, language) pair runs in its own process; peak RSS is that process's (Parakeet: the Python process,
interpreter included) or whisper-cli's own, read with `wait4`.
RTF (real-time factor) is decode time over audio time, model load excluded. WER is corpus-level over FLEURS'
normalised transcription, both sides passed through `normalise` below.

    uv run bench/run.py --all --data DIR --models DIR --whisper-cli PATH
    uv run bench/run.py --engine parakeet --lang uk --limit 50 ...
"""

import argparse
import json
import os
import re
import resource
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

LANGS = {"uk": "uk_ua", "en": "en_us"}
PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
ENGINES = {
    "parakeet": {"files": [f"{PARAKEET}/{n}" for n in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")]},
    "whisper-turbo": {"files": ["ggml-large-v3-turbo-q8_0.bin"]},
    "whisper-small": {"files": ["ggml-small-q8_0.bin"]},
}


def normalise(text: str) -> str:
    t = unicodedata.normalize("NFC", text).lower()
    t = re.sub(r"[’ʼ`ʹ]", "'", t)
    t = "".join(c if c.isalnum() or c == "'" else " " for c in t)
    t = re.sub(r"(^|\s)'+|'+(\s|$)", " ", t)
    return " ".join(t.split())


def clips(data: Path, lang: str, limit: int | None) -> list[dict]:
    rows = []
    for line in (data / f"{LANGS[lang]}.test.tsv").read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        rows.append({"file": str(data / LANGS[lang] / "test" / cols[1]), "ref": cols[3], "audio_s": int(cols[5]) / 16000})
    rows.sort(key=lambda r: Path(r["file"]).name)
    return rows[:limit] if limit else rows


def run_parakeet(models: Path, rows: list[dict], threads: int) -> list[str]:
    import sherpa_onnx
    import soundfile

    d = models / PARAKEET
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), joiner=str(d / "joiner.int8.onnx"),
        tokens=str(d / "tokens.txt"), num_threads=threads, model_type="nemo_transducer", decoding_method="greedy_search")
    hyps, decode_s = [], 0.0
    for r in rows:
        samples, rate = soundfile.read(r["file"], dtype="float32")
        t0 = time.perf_counter()
        s = rec.create_stream()
        s.accept_waveform(rate, samples)
        rec.decode_stream(s)
        decode_s += time.perf_counter() - t0
        hyps.append(s.result.text)
    return hyps, decode_s, resource.getrusage(resource.RUSAGE_SELF).ru_maxrss


def run_whisper(cli: str, model: Path, lang: str, rows: list[dict], threads: int, out: Path):
    # One process for all clips: the model loads once; decode time = wall time minus the load time it reports.
    for r in rows:
        Path(r["file"] + ".txt").unlink(missing_ok=True)
    args = [cli, "-m", str(model), "-l", lang, "-t", str(threads), "-bs", "1", "-bo", "1", "-nt", "-otxt", "-np"]
    for r in rows:
        args += ["-f", r["file"]]
    err = out.with_suffix(".stderr")
    t0 = time.perf_counter()
    with open(err, "w") as f:
        p = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=f)
        _, status, usage = os.wait4(p.pid, 0)
    wall = time.perf_counter() - t0
    log = err.read_text(errors="replace")
    if os.waitstatus_to_exitcode(status) != 0:
        sys.exit(f"whisper-cli failed: {log[-2000:]}")
    m = re.search(r"load time =\s*([\d.]+) ms", log)
    load_s = float(m.group(1)) / 1000 if m else 0.0
    hyps = [Path(r["file"] + ".txt").read_text(encoding="utf-8") for r in rows]
    return hyps, wall - load_s, usage.ru_maxrss


def child(a):
    rows = clips(Path(a.data), a.lang, a.limit)
    if a.engine == "parakeet":
        hyps, decode_s, rss = run_parakeet(Path(a.models), rows, a.threads)
    else:
        model = Path(a.models) / ENGINES[a.engine]["files"][0]
        hyps, decode_s, rss = run_whisper(a.whisper_cli, model, a.lang, rows, a.threads, Path(a.out))
    Path(a.out).write_text(json.dumps({"hyps": hyps, "decode_s": decode_s, "rss_bytes": rss}, ensure_ascii=False), encoding="utf-8")


def measure(a, engine: str, lang: str) -> dict:
    import jiwer

    out = Path(a.work) / f"{engine}-{lang}.json"
    cmd = [sys.executable, __file__, "--child", "--engine", engine, "--lang", lang, "--data", a.data, "--models", a.models,
           "--threads", str(a.threads), "--whisper-cli", a.whisper_cli, "--out", str(out)]
    if a.limit:
        cmd += ["--limit", str(a.limit)]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"{engine}/{lang} failed:\n{p.stderr[-3000:]}")
    res = json.loads(out.read_text(encoding="utf-8"))
    rss = res["rss_bytes"]  # bytes on macOS
    rows = clips(Path(a.data), lang, a.limit)
    refs = [normalise(r["ref"]) for r in rows]
    hyps = [normalise(h) for h in res["hyps"]]
    audio_s = sum(r["audio_s"] for r in rows)
    size = sum((Path(a.models) / f).stat().st_size for f in ENGINES[engine]["files"])
    (Path(a.work) / f"{engine}-{lang}.pairs.tsv").write_text(
        "".join(f"{Path(r['file']).name}\t{ref}\t{hyp}\n" for r, ref, hyp in zip(rows, refs, hyps)), encoding="utf-8")
    return {"engine": engine, "lang": lang, "clips": len(rows), "audio_min": audio_s / 60,
            "wer": jiwer.wer(refs, hyps) * 100, "rtf": res["decode_s"] / audio_s, "rss_mb": rss / 1e6, "size_mb": size / 1e6}


def table(results: list[dict]) -> str:
    head = "| Engine | Lang | Clips | Audio (min) | WER % | RTF | Peak RSS (MB) | Model (MB) |\n|---|---|---|---|---|---|---|---|\n"
    return head + "".join(
        f"| {r['engine']} | {r['lang']} | {r['clips']} | {r['audio_min']:.1f} | {r['wer']:.1f} | {r['rtf']:.3f} | "
        f"{r['rss_mb']:.0f} | {r['size_mb']:.0f} |\n" for r in results)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--engine", choices=list(ENGINES))
    p.add_argument("--lang", choices=list(LANGS))
    p.add_argument("--all", action="store_true", help="every engine on every language")
    p.add_argument("--engines", default=",".join(ENGINES), help="with --all: comma-separated subset")
    p.add_argument("--data", required=True, help="FLEURS dir: <lang>.test.tsv and <lang>/test/*.wav")
    p.add_argument("--models", required=True)
    p.add_argument("--whisper-cli", default="whisper-cli")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--limit", type=int)
    p.add_argument("--work", default=os.environ.get("TMPDIR", "/tmp"))
    p.add_argument("--json", help="append each result as one JSON line here")
    p.add_argument("--child", action="store_true", help=argparse.SUPPRESS)
    p.add_argument("--out", help=argparse.SUPPRESS)
    a = p.parse_args()
    if a.child:
        return child(a)
    pairs = [(e, l) for e in a.engines.split(",") for l in LANGS] if a.all else [(a.engine, a.lang)]
    results = []
    for e, l in pairs:
        r = measure(a, e, l)
        print(json.dumps(r), file=sys.stderr, flush=True)
        if a.json:
            with open(a.json, "a", encoding="utf-8") as f:
                f.write(json.dumps(r) + "\n")
        results.append(r)
    print(table(results))


if __name__ == "__main__":
    main()
