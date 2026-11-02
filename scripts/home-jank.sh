#!/usr/bin/env bash
# Drawer-open jank for Tin Home on a USB-connected phone (unlocked, Tin Home the default Home).
# Opens the drawer N times (swipe up on Home, Back to close) and prints the gfxinfo frame summary.
# Usage: scripts/home-jank.sh [opens=10] [apk]   env: ANDROID_SERIAL, ADB, WAIT (seconds, default 1.2)
# With an apk path it installs that build first (debug build: android/home/build/outputs/apk/debug/home-debug.apk).
set -euo pipefail
ADB=${ADB:-adb}
PKG=io.github.egoushka.tin.home
OPENS=${1:-10}
WAIT=${WAIT:-1.2}
APK=${2:-}

[ -n "$APK" ] && "$ADB" install -r "$APK" >/dev/null

size=$("$ADB" shell wm size | tail -1 | sed 's/.*: //')
W=${size%x*}; H=${size#*x}
x=$((W / 2)); y0=$((H * 3 / 4)); y1=$((H / 4))

"$ADB" shell am force-stop $PKG
"$ADB" shell input keyevent KEYCODE_HOME
sleep 3                                      # cold start, first frames
"$ADB" shell dumpsys gfxinfo $PKG reset >/dev/null
for _ in $(seq "$OPENS"); do
  "$ADB" shell input swipe "$x" "$y0" "$x" "$y1" 150
  sleep "$WAIT"
  "$ADB" shell input keyevent KEYCODE_BACK
  sleep "$WAIT"
done
"$ADB" shell dumpsys gfxinfo $PKG | grep -E "Total frames rendered|Janky frames|50th|90th|95th|99th|Number (Missed|High input|Slow)"
