#!/usr/bin/env bash
# Copies Parakeet-tdt-0.6b-v3 int8 and Silero VAD from the bench cache into the debug app's files/models/, where
# ModelStore looks (names and sizes pinned in android/speech/.../ModelStore.kt). Run bench/run-all.sh first, or pass
# the models dir. Pipes through run-as, so it needs a debug build; it changes nothing outside the app's own folder.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
models=${1:-$here/bench/.cache/models}
app=io.github.egoushka.tin
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "no device"; exit 2; }

push() { # <model name> <file>
  adb shell "run-as $app mkdir -p files/models/$1"
  adb shell "run-as $app sh -c 'cat > files/models/$1/$(basename "$2")'" < "$2"
  echo "$1/$(basename "$2"): $(adb shell "run-as $app stat -c %s files/models/$1/$(basename "$2")") bytes"
}
src=$models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do push parakeet-tdt-0.6b-v3-int8 "$src/$f"; done
push silero-vad "$models/silero_vad.onnx"
echo "done; reopen tin so SpeechService loads the model"
