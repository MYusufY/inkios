#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/branding/out"
BACKUP="$HERE/device-backup/system_media"
SYS_BLOCK="$(adb shell "mount" | tr -d '\r' | awk '$2=="/system"{print $1; exit}')"

declare -a MAP=(
  "bootanimation.zip:/system/media/bootanimation.zip"
  "sleep.png:/system/media/standby/standy_r6801_1.png"
  "shutdown.png:/system/media/standby/shutdown_r6801.png"
  "battery_empty.png:/system/media/standby/nopower_r6801.png"
  "battery_low.png:/system/media/standby/standby_low_power.png"
)

adb shell id | grep -q "uid=0" || { echo "adb shell is not root; this script needs an eng/rooted firmware"; exit 1; }
[ -n "$SYS_BLOCK" ] || { echo "could not find the /system block device"; exit 1; }

remount() { adb shell "mount -o $1,remount $SYS_BLOCK /system"; }

if [ "${1:-}" = "--restore" ]; then
  [ -d "$BACKUP" ] || { echo "no backup at $BACKUP"; exit 1; }
  remount rw
  for m in "${MAP[@]}"; do
    dst="${m#*:}"; rel="${dst#/system/media/}"
    adb push "$BACKUP/$rel" /sdcard/inkios.tmp >/dev/null
    adb shell "cp /sdcard/inkios.tmp $dst && chmod 644 $dst"
  done
  adb shell rm /sdcard/inkios.tmp
  remount ro
  echo "Restored original artwork."
  exit 0
fi

if [ ! -d "$BACKUP" ]; then
  echo "Backing up original artwork to $BACKUP"
  mkdir -p "$BACKUP"
  adb pull /system/media/. "$BACKUP" >/dev/null
fi

remount rw
for m in "${MAP[@]}"; do
  src="$OUT/${m%%:*}"; dst="${m#*:}"
  [ -f "$src" ] || { remount ro; echo "missing $src (run branding/generate.py)"; exit 1; }
  adb push "$src" /sdcard/inkios.tmp >/dev/null
  adb shell "cp /sdcard/inkios.tmp $dst && chmod 644 $dst && chown root:root $dst"
  echo "installed $dst"
done
adb shell rm /sdcard/inkios.tmp
remount ro
echo "Done. Reboot to see the boot animation; press power to see the sleep screen."
