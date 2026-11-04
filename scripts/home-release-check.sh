#!/usr/bin/env bash
# Build-time checks for Tin Home on the Kvaesitso engine (docs/specs/2026-11-04-home-on-kvaesitso.md):
#   S0.1 android/kvaesitso, as committed, is upstream's tree except the paths listed in android/kvaesitso.patches
#   S0.4 Home lists no apps and loads no icons itself
#   S0.5 the licence split: android/home is GPL-3.0, the engine keeps upstream's licence, the README names both
# With --measure it also prints S0.7: the release APK's size and android/home's production Kotlin lines (build
# :home:assembleRelease first).
# Usage: scripts/home-release-check.sh [--measure]   env: UPSTREAM (default https://github.com/MM2-0/Kvaesitso)
# Exit 1 when any check fails.
# Upstream is fetched into a throwaway bare repo, so this checkout never becomes shallow.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
UPSTREAM=${UPSTREAM:-https://github.com/MM2-0/Kvaesitso}
PATCHES="$ROOT/android/kvaesitso.patches"
HOME_SRC="$ROOT/android/home/src/main"
failed=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*" >&2; failed=1; }

# S0.1: the pinned commit is the first full sha in the patches file's header.
sha=$(grep -oE '[0-9a-f]{40}' "$PATCHES" | head -1)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/kvaesitso.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
git init --quiet --bare "$tmp"
git -C "$tmp" fetch --quiet --depth 1 --no-tags "$UPSTREAM" "$sha"
git -C "$tmp" ls-tree -r "$sha" >"$tmp/upstream"
git -C "$ROOT" ls-tree -r HEAD:android/kvaesitso >"$tmp/ours"
# diff exits 1 when the trees differ and 2 when it could not compare them; only the second stops the script.
diff "$tmp/upstream" "$tmp/ours" >"$tmp/diff" || [ $? -eq 1 ]
{ grep '^[<>]' "$tmp/diff" || true; } | cut -f2 | sort -u >"$tmp/changed"
grep -v '^#' "$PATCHES" | cut -f1 | sed '/^$/d' | sort -u >"$tmp/listed"
unlisted=$(comm -23 "$tmp/changed" "$tmp/listed")
if [ -z "$unlisted" ]; then
  pass "S0.1 android/kvaesitso is upstream ${sha:0:9} plus $(grep -c . "$tmp/listed") listed patch(es)"
else
  fail "S0.1 changed in android/kvaesitso but not in kvaesitso.patches:" $'\n'"$unlisted"
fi

# S0.4
hits=$(grep -rnE 'getActivityList|getLauncherActivityList|getApplicationIcon' "$HOME_SRC" || true)
own=$(find "$HOME_SRC" -name 'IconRender*')
if [ -z "$hits$own" ]; then
  pass "S0.4 Home's own app loading and icon drawing are gone"
else
  fail "S0.4 Home still lists apps or loads icons itself:" $'\n'"$hits$own"
fi

# S0.5
if grep -q 'GNU GENERAL PUBLIC LICENSE' "$ROOT/android/home/LICENSE" 2>/dev/null &&
  grep -q 'Version 3, 29 June 2007' "$ROOT/android/home/LICENSE" &&
  [ -f "$ROOT/android/kvaesitso/LICENSE.txt" ] &&
  grep -q 'android/home/LICENSE' "$ROOT/README.md" && grep -q 'GPL-3.0' "$ROOT/README.md"; then
  pass "S0.5 android/home/LICENSE is GPL-3.0, android/kvaesitso/LICENSE.txt is upstream's, the README names the split"
else
  fail "S0.5 a licence file is missing or the README doesn't name the split"
fi

# S0.7
if [ "${1:-}" = --measure ]; then
  apk="$ROOT/android/home/build/outputs/apk/release/home-release.apk"
  if [ -f "$apk" ]; then
    bytes=$(wc -c <"$apk" | tr -d ' ')
    lines=$(find "$HOME_SRC" -name '*.kt' -print0 | xargs -0 cat | wc -l | tr -d ' ')
    echo "S0.7 release APK $bytes bytes ($((bytes / 1024)) KiB); android/home/src/main Kotlin $lines lines"
  else
    fail "S0.7 no release APK at ${apk#"$ROOT"/}; build :home:assembleRelease first"
  fi
fi

exit "$failed"
