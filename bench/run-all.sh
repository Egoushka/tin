#!/usr/bin/env bash
# Reproduces bench/results.md: fetches FLEURS uk/en test and the models into $1 (default bench/.cache), builds
# whisper.cpp for the CPU only (no Metal, no Accelerate: the phone has neither), then runs
#   1. Parakeet on the full test sets,
#   2. all engines on the same first 150 clips of each language (paired; Whisper turbo on CPU is ~2.5x real time).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cache=${1:-$here/.cache}
mkdir -p "$cache/data" "$cache/models" "$cache/work-full" "$cache/work-150"
hf=https://huggingface.co

for l in uk_ua en_us; do
  [ -f "$cache/data/$l.test.tsv" ] || curl -fsSL -o "$cache/data/$l.test.tsv" "$hf/datasets/google/fleurs/resolve/main/data/$l/test.tsv"
  if [ ! -d "$cache/data/$l/test" ]; then
    mkdir -p "$cache/data/$l"
    curl -fsSL "$hf/datasets/google/fleurs/resolve/main/data/$l/audio/test.tar.gz" | tar -xz -C "$cache/data/$l"
  fi
done
p=sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
[ -d "$cache/models/$p" ] || curl -fsSL "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$p.tar.bz2" | tar -xj -C "$cache/models"
for m in ggml-large-v3-turbo-q8_0.bin ggml-small-q8_0.bin; do
  [ -f "$cache/models/$m" ] || curl -fsSL -o "$cache/models/$m" "$hf/ggerganov/whisper.cpp/resolve/main/$m"
done

cli=$cache/whisper.cpp/build/bin/whisper-cli
if [ ! -x "$cli" ]; then
  git clone -q --depth 1 --branch v1.8.2 https://github.com/ggml-org/whisper.cpp "$cache/whisper.cpp"
  cmake -S "$cache/whisper.cpp" -B "$cache/whisper.cpp/build" -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=OFF -DGGML_BLAS=OFF
  cmake --build "$cache/whisper.cpp/build" -j 8 --target whisper-cli
fi

common=(--data "$cache/data" --models "$cache/models" --whisper-cli "$cli" --threads 4)
uv run "$here/run.py" --all --engines parakeet "${common[@]}" --work "$cache/work-full" --json "$cache/full.jsonl"
uv run "$here/run.py" --all --limit 150 "${common[@]}" --work "$cache/work-150" --json "$cache/paired.jsonl"
