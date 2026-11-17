#!/usr/bin/env bash
# Four Drop dev harness (Windows Git Bash). Pinned to one emulator: DEV (borrowed with the factory's emu_borrow.py).
#   tools/fd.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.fourdrop.DEBUG --es cmd NAME --es a ARG
#   tools/fd.sh shot OUT.png       still capture of the device (adb exec-out screencap)
#   tools/fd.sh log [REGEX]        logcat lines of tag FourDrop (optionally filtered)
#   tools/fd.sh install            install the debug APK and start the app
set -u
DEV="${DEV:?set DEV=emulator-55xx}"
PKG=io.embodit.fourdrop
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) "$ADB" -s "$DEV" exec-out screencap -p > "$2" ;;
  log) "$ADB" -s "$DEV" logcat -d -s FourDrop:V BoardInput:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
