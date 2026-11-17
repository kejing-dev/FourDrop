#!/usr/bin/env bash
# tools/shot.sh OUT.png : emulator console screenshot of $DEV (adb screencap returns white frames on the PICO emulator)
set -u
DEV="${DEV:?set DEV}"; ADB="${ADB:-/c/Users/Admin/Android/Sdk/platform-tools/adb.exe}"
out="$1"; tmp="$(dirname "$out")/.emu"; mkdir -p "$tmp"; rm -f "$tmp"/Screenshot_*.png
"$ADB" -s "$DEV" emu screenrecord screenshot "$(cygpath -w "$tmp")" >/dev/null 2>&1
for i in $(seq 1 40); do f="$(ls -1t "$tmp"/Screenshot_*.png 2>/dev/null | head -1)"; [ -n "$f" ] && { sleep 0.3; mv "$f" "$out"; echo "$out"; exit 0; }; sleep 0.25; done
echo "no screenshot" >&2; exit 1
