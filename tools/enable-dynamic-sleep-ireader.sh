#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
TARGET=/system/media/standby/standy_r6801_1.png
LIVE=/data/data/tech.tachion.inkios/files/sleep/sleep.png
SYS_BLOCK="$(adb shell mount | tr -d '\r' | awk '$2=="/system"{print $1; exit}')"

adb shell id | grep -q "uid=0" || { echo "adb shell is not root"; exit 1; }
remount() { adb shell "mount -o $1,remount $SYS_BLOCK /system"; }

if [ "${1:-}" = "--restore" ]; then
  [ -f "$HERE/branding/out/sleep.png" ] || python3 "$HERE/branding/generate.py" >/dev/null
  remount rw
  adb push "$HERE/branding/out/sleep.png" /sdcard/inkios.tmp >/dev/null
  adb shell "rm -f $TARGET; cp /sdcard/inkios.tmp $TARGET; chmod 644 $TARGET; rm /sdcard/inkios.tmp"
  remount ro
  echo "Static sleep image restored."
  exit 0
fi

adb shell "[ -f $LIVE ]" || { echo "Open the Inkios home screen once first (it renders $LIVE)."; exit 1; }
remount rw
adb shell "rm -f $TARGET; ln -s $LIVE $TARGET"
remount ro
adb shell ls -l "$TARGET"
echo "Dynamic sleep screen enabled. Press the power button to see it."
