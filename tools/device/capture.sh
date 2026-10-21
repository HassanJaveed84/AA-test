#!/usr/bin/env bash
# Records an AA Tube session from the connected phone: logcat (AA Tube,
# WebView, car-app, codec and crash lines), dumpsys snapshots, phone
# screenshots, the lab log, and a summary. Use it for manual DHU runs and
# for the BMW (phone on USB to a laptop, or over wireless adb).
#
# While it runs, type a short note and press Enter to drop a timestamped
# STEP marker into the log (for example "parked test pattern"). Type q to
# finish, or pass --seconds N for an unattended capture.
#
# usage: tools/device/capture.sh <run-name> [--seconds N] [--full-logcat] [--keep-history]
source "$(dirname "$0")/lib.sh"
name="${1:-capture}"; shift || true
seconds=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --seconds) seconds="$2"; shift 2 ;;
    --full-logcat) FULL_LOGCAT=1; shift ;;
    --keep-history) KEEP_LOGCAT_HISTORY=1; shift ;;
    *) die "unknown option $1" ;;
  esac
done
require_device
RUN="$(new_run_dir "$name")"
device_facts > "$RUN/device.txt"
permissions_report > "$RUN/permissions.txt"
start_logcat
trap 'stop_logcat' EXIT
snapshot_dumpsys start
phone_screenshot start
mark "STEP capture-start"
say "Capturing to $RUN"
if [[ -n "$seconds" ]]; then
  sleep "$seconds"
else
  n=0
  while true; do
    read -r -p "note (Enter = marker, s = phone screenshot, q = finish): " note || break
    case "$note" in
      q|Q) break ;;
      s|S) n=$((n+1)); phone_screenshot "manual-$n"; echo "  saved phone-manual-$n.png" ;;
      *) mark "STEP ${note:-marker}" ;;
    esac
  done
fi
mark "STEP capture-end"
snapshot_dumpsys end
phone_screenshot end
stop_logcat
trap - EXIT
pull_lab_logs
summarize
pack_run
