#!/usr/bin/env bash
# A5: only the shell user (and root) can talk to the bridge. Runs `tin probe` against @tin as shell, then as the
# debuggable tin app's uid through run-as, using the copy of the binary inside the APK (an app may execute its own
# native libraries). PASS: 200 as shell, connection closed with no response as the app. UNKNOWN: the app uid could
# not run the probe at all, so the socket check never happened.
set -uo pipefail
PKG=io.github.egoushka.tin
LOG=/data/local/tmp/tin/tin.log

as_shell=$(adb shell /data/local/tmp/tin/bin/tin probe 2>&1)
echo "as shell: $as_shell"

apk=$(adb shell pm path "$PKG" | sed -n 's/^package://p' | tr -d '\r')
lib="${apk%/base.apk}/lib/arm64/libtin.so"
echo "app uid: $(adb shell run-as "$PKG" id -u 2>&1 | tr -d '\r')"
as_app=$(adb shell run-as "$PKG" "$lib" probe 2>&1 | tr -d '\r')
echo "as app:   $as_app"
echo "tin log:  $(adb shell tail -n 50 "$LOG" 2>/dev/null | grep 'refused connection' | tail -1 | tr -d '\r')"

if [[ "$as_shell" != "200" ]]; then
  echo "FAIL A5: shell did not get 200"; exit 1
elif [[ "$as_app" == *"closed without a response"* || "$as_app" == *"reset by peer"* || "$as_app" == *"Broken pipe"* ]]; then
  echo "PASS A5"
elif [[ "$as_app" == "200" ]]; then
  echo "FAIL A5: the app uid got an answer"; exit 1
else
  echo "UNKNOWN A5: the app uid could not run the probe"; exit 2
fi
