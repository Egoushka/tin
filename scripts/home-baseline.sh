#!/usr/bin/env bash
# Start-time and drawer-jank baseline for Tin Home on a USB-connected phone (unlocked, Tin Home installed).
# Starts Home N times with `am start -S -W` while Settings is on top, prints the median and p95 TotalTime, runs
# scripts/home-jank.sh for the janky-frame percentage after opening the drawer, and appends both to
# docs/results/<date>-home-baseline.md.
# Why not `force-stop` then `am start -W`: Home is the default launcher, so the system restarts it at once and
# `am start -W` reports TotalTime 0 for a start that never happened. A launch that reports 0 aborts the run.
# Usage: scripts/home-baseline.sh [launches=20]   env: ANDROID_SERIAL, ADB, OPENS (drawer opens, default 10),
#   SETTLE (seconds after Settings opens, default 2), WAIT (home-jank.sh's pause between drawer swipes)
# Exit 2 with "no device" when no phone is attached.
# The p95 is nearest-rank: the ceil(0.95 * N)th smallest launch.
set -euo pipefail
ADB=${ADB:-adb}
PKG=io.github.egoushka.tin.home
N=${1:-20}
OPENS=${OPENS:-10}
SETTLE=${SETTLE:-2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

[ "$N" -ge 1 ] 2>/dev/null || { echo "launches must be a positive number" >&2; exit 1; }
[ "$("$ADB" get-state 2>/dev/null || true)" = device ] || { echo "no device" >&2; exit 2; }

times=()
states=()
for i in $(seq "$N"); do
  "$ADB" shell am start -W -a android.settings.SETTINGS >/dev/null
  sleep "$SETTLE"
  out=$("$ADB" shell am start -S -W -n $PKG/.HomeActivity | tr -d '\r')
  t=$(sed -n 's/^TotalTime: //p' <<<"$out")
  if [ -z "$t" ] || [ "$t" -eq 0 ]; then
    echo "launch $i did not start Home (TotalTime ${t:-missing}):" >&2
    echo "$out" >&2
    exit 1
  fi
  times+=("$t")
  states+=("$(sed -n 's/^LaunchState: //p' <<<"$out")")
done
launch_states=$(printf '%s\n' "${states[@]}" | sort | uniq -c | sed 's/^ *\([0-9]*\) \(.*\)$/\2 x\1/' | paste -sd, - | sed 's/,/, /g')

# min, median (mean of the two middle ones for an even N), p95, max
read -r min median p95 max < <(printf '%s\n' "${times[@]}" | sort -n | awk '
  { a[NR] = $1 }
  END {
    k = int((95 * NR + 99) / 100)
    m = (NR % 2) ? a[(NR + 1) / 2] : (a[NR / 2] + a[NR / 2 + 1]) / 2
    print a[1], m, a[k], a[NR]
  }')

jank=$("$ROOT/scripts/home-jank.sh" "$OPENS" | tr -d '\r')
frames=$(sed -n 's/^ *Total frames rendered: //p' <<<"$jank" | head -1)
janky=$(sed -n 's/^ *Janky frames: [0-9]* (\(.*\))$/\1/p' <<<"$jank" | head -1)

model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
android=$("$ADB" shell getprop ro.build.version.release | tr -d '\r')
pkg=$("$ADB" shell dumpsys package $PKG | tr -d '\r')
version=$(sed -n 's/^ *versionName=//p' <<<"$pkg" | head -1)
installed=$(sed -n 's/^ *lastUpdateTime=//p' <<<"$pkg" | head -1)
grep -Eq '^ *(pkgFlags|flags)=.*DEBUGGABLE' <<<"$pkg" && kind=debug || kind=release

echo "launches: $N ($launch_states)"
echo "TotalTime ms: min $min, median $median, p95 $p95, max $max"
echo "drawer: $janky janky of $frames frames over $OPENS opens"

report="$ROOT/docs/results/$(date +%F)-home-baseline.md"
[ -f "$report" ] || printf '# Home baseline, %s\n' "$(date +%F)" >"$report"
# shellcheck disable=SC2016  # the backticks are literal Markdown
{
  printf '\n## %s: %s, Android %s\n\n' "$(date +%H:%M)" "$model" "$android"
  printf -- '- Build: %s %s, installed %s; checkout %s (the installed build is what was measured).\n' \
    "$kind" "$version" "$installed" "$(git -C "$ROOT" rev-parse --short HEAD)"
  printf -- '- Start: %s launches of `am start -S -W` (LaunchState %s). `TotalTime` ms: min %s, **median %s**, **p95 %s**, max %s.\n' \
    "$N" "$launch_states" "$min" "$median" "$p95" "$max"
  printf -- '- All launches (ms): %s\n' "${times[*]}"
  printf -- '- Drawer: **%s janky** of %s frames over %s opens (`scripts/home-jank.sh`).\n\n' "$janky" "$frames" "$OPENS"
  printf '```\n%s\n```\n' "$jank"
} >>"$report"
echo "appended to ${report#"$ROOT"/}"
