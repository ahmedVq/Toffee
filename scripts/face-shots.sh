#!/usr/bin/env bash
# Screenshot every mood on the real panel.
#
# The face has no unit tests of its own — everything it draws is a pure
# function that does — so this is how it gets verified. Nine launches, nine
# screenshots, looked at by a human or by Claude.
#
#   scripts/face-shots.sh [output-dir]
set -euo pipefail

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
OUT="${1:-face-shots}"
PKG=dev.toffee.companion

mkdir -p "$OUT"

for mood in neutral happy sad frustrated loving smug sleepy listening offline; do
  "$ADB" shell am force-stop "$PKG"
  "$ADB" shell am start -n "$PKG/.MainActivity" -e mood "$mood" >/dev/null
  # 1.9s of wake, 1s of mood fade, then a moment to settle.
  sleep 4
  "$ADB" exec-out screencap -p > "$OUT/$mood.png"
  printf '%-12s %s\n' "$mood" "$(wc -c < "$OUT/$mood.png") bytes"
done

echo "wrote $OUT/"
