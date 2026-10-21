#!/usr/bin/env bash
# Shared helpers for AA Tube device scripts. Sourced, not run.
# Works on macOS and Linux; on Windows use Git Bash or WSL (see README.md).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_CLASS="io.github.hassanjaveed84.aatube.phone.MainActivity"
PKG_DAILY="io.github.hassanjaveed84.aatube"
PKG_LAB="io.github.hassanjaveed84.aatube.lab"
PKG="${AATUBE_PKG:-$PKG_LAB}"
GEARHEAD="com.google.android.projection.gearhead"

# grep that never fails the pipeline (set -o pipefail) when nothing matches.
g() { grep "$@" || true; }

say()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

sdk_root() {
  local candidates=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk")
  if [[ -n "${LOCALAPPDATA:-}" ]]; then candidates+=("$LOCALAPPDATA/Android/Sdk"); fi
  for c in "${candidates[@]}"; do
    [[ -n "$c" && -d "$c" ]] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

find_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  local root; root="$(sdk_root)" || die "Android SDK not found. Set ANDROID_HOME."
  for c in "$root/platform-tools/adb" "$root/platform-tools/adb.exe"; do
    [[ -x "$c" ]] && { printf '%s' "$c"; return; }
  done
  die "adb not found. Install SDK Platform-Tools or add adb to PATH."
}

find_dhu() {
  local root; root="$(sdk_root)" || die "Android SDK not found. Set ANDROID_HOME."
  for c in "$root/extras/google/auto/desktop-head-unit" "$root/extras/google/auto/desktop-head-unit.exe"; do
    [[ -x "$c" ]] && { printf '%s' "$c"; return; }
  done
  die "DHU not found. Android Studio -> SDK Manager -> SDK Tools -> 'Android Auto Desktop Head Unit Emulator'."
}

ADB="$(find_adb)"
adb_() { "$ADB" "$@"; }

require_device() {
  local n
  n="$(adb_ devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ' || true)"
  [[ "$n" == "1" ]] || die "Expected exactly one authorised device in 'adb devices' (found $n). Unlock the phone and accept the USB debugging prompt."
}

# A new results folder: test-results/<UTC timestamp>-<name>
new_run_dir() {
  local name="${1:-run}"
  name="$(printf '%s' "$name" | tr -c 'A-Za-z0-9_.-' '-')"
  local dir="$REPO_ROOT/test-results/$(date -u +%Y%m%dT%H%M%SZ)-$name"
  mkdir -p "$dir/screens" "$dir/dumpsys"
  printf '%s' "$dir"
}

# Inline marker in logcat, so every excerpt can be split by step.
mark() { adb_ shell log -t AATubeTest "\"$*\"" >/dev/null 2>&1 || true; printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*" >> "$RUN/steps.txt"; }

device_facts() {
  {
    echo "captured_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host: $(uname -s) $(uname -m)"
    echo "adb: $("$ADB" version | head -1)"
    echo "model: $(adb_ shell getprop ro.product.model | tr -d '\r')"
    echo "android: $(adb_ shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb_ shell getprop ro.build.version.sdk | tr -d '\r'))"
    echo "build: $(adb_ shell getprop ro.build.display.id | tr -d '\r')"
    echo "android_auto: $(adb_ shell dumpsys package $GEARHEAD | g -m1 versionName | tr -d '\r' | sed 's/^ *//')"
    echo "webview: $(adb_ shell dumpsys webviewupdate | g -m1 -i 'Current WebView package' | tr -d '\r' | sed 's/^ *//')"
    for p in "$PKG_LAB" "$PKG_DAILY"; do
      local v; v="$(adb_ shell dumpsys package "$p" | g -m1 versionName | tr -d '\r' | sed 's/^ *//')"
      local inst; inst="$(adb_ shell dumpsys package "$p" | g -m1 installerPackageName | tr -d '\r' | sed 's/^ *//')"
      echo "app $p: ${v:-not installed} ${inst}"
    done
    echo "location_mode: $(adb_ shell settings get secure location_mode | tr -d '\r') (3 = high accuracy / on)"
  }
}

permissions_report() {
  adb_ shell dumpsys package "$PKG" | tr -d '\r' \
    | g -E 'CAR_SPEED|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|POST_NOTIFICATIONS' | sed 's/^ *//' | sort -u
}

# Start a full logcat capture in the background (kept local; filtered at the end).
start_logcat() {
  [[ "${KEEP_LOGCAT_HISTORY:-0}" == "1" ]] || adb_ logcat -c || true
  adb_ logcat -v threadtime > "$RUN/.logcat-full.txt" 2>&1 &
  LOGCAT_PID=$!
}

stop_logcat() {
  [[ -n "${LOGCAT_PID:-}" ]] && kill "$LOGCAT_PID" 2>/dev/null || true
  wait "${LOGCAT_PID:-}" 2>/dev/null || true
  # Keep only AA Tube, WebView/Chromium, car-app, codec and crash lines.
  grep -E 'AATube|aatube|CarPlayerDisplay|chromium|cr_|CarApp|CarContext|ProjectedCarHardware|MediaCodec|CCodec|AndroidRuntime|FATAL EXCEPTION|ANR in|ForegroundService|ActivityManager.*(aatube|Background start)' \
    "$RUN/.logcat-full.txt" > "$RUN/logcat-aatube.txt" || true
  if [[ "${FULL_LOGCAT:-0}" == "1" ]]; then
    mv "$RUN/.logcat-full.txt" "$RUN/logcat-full.txt"
    warn "logcat-full.txt includes other apps' logs; review it before sharing."
  else
    rm -f "$RUN/.logcat-full.txt"
  fi
}

snapshot_dumpsys() {
  local tag="$1" d="$RUN/dumpsys"
  adb_ shell dumpsys activity services "$PKG" > "$d/$tag-services.txt" 2>&1 || true
  adb_ shell dumpsys gfxinfo "$PKG" > "$d/$tag-gfxinfo.txt" 2>&1 || true
  adb_ shell dumpsys media.resource_manager > "$d/$tag-codecs.txt" 2>&1 || true
  adb_ shell dumpsys audio | tr -d '\r' | sed -n '/Audio Focus stack/,/^$/p' > "$d/$tag-audio-focus.txt" 2>&1 || true
  adb_ shell dumpsys media_session | head -120 > "$d/$tag-media-session.txt" 2>&1 || true
  { adb_ shell dumpsys display | tr -d '\r' | g -i -E 'AATube|virtual'; } > "$d/$tag-displays.txt" 2>&1 || true
  { adb_ shell dumpsys location | tr -d '\r' | g -i -E 'aatube|gps provider|enabled' | head -40; } > "$d/$tag-location.txt" 2>&1 || true
}

phone_screenshot() {
  adb_ exec-out screencap -p > "$RUN/screens/phone-$1.png" 2>/dev/null || rm -f "$RUN/screens/phone-$1.png"
}

# Lab log files from the app's private storage (debuggable builds only).
pull_lab_logs() {
  mkdir -p "$RUN/lab-log"
  local files
  files="$(adb_ shell run-as "$PKG" ls files/lab 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$files" || "$files" == *"not debuggable"* || "$files" == *"unknown package"* ]]; then
    echo "run-as unavailable (release build?) - rely on logcat-aatube.txt" > "$RUN/lab-log/README.txt"
    return
  fi
  for f in $files; do
    adb_ exec-out run-as "$PKG" cat "files/lab/$f" > "$RUN/lab-log/$f" 2>/dev/null || true
  done
}

summarize() {
  local s="$RUN/summary.md" log="$RUN/logcat-aatube.txt"
  {
    echo "# AA Tube test run: $(basename "$RUN")"
    echo
    echo '## Device'
    echo '```'; cat "$RUN/device.txt" 2>/dev/null; echo '```'
    echo
    echo '## Permissions (dumpsys package)'
    echo '```'; cat "$RUN/permissions.txt" 2>/dev/null; echo '```'
    echo
    echo '## Crashes / ANRs'
    echo '```'; grep -E 'FATAL EXCEPTION|ANR in' "$log" 2>/dev/null | head -20 || true; echo '```'
    echo
    echo '## Gate transitions (which speed source decided)'
    echo '```'; grep -E '\[gate-transition\]' "$log" 2>/dev/null | sed -E 's/^.*\[gate-transition\] //' | head -60 || true; echo '```'
    echo
    echo '## Last gate snapshot'
    echo '```'; grep -E '\[gate-(snapshot|transition)\]' "$log" 2>/dev/null | tail -1 | sed -E 's/^.*AATube *: //' || true; echo '```'
    echo
    echo '## Speed sources and permissions'
    echo '```'; grep -E '\[(speed|permission)\]' "$log" 2>/dev/null | sed -E 's/^.*AATube *: //' | head -60 || true; echo '```'
    echo
    echo '## YouTube TV automation / player events'
    echo '```'; grep -E '\[(tv|player|display)\]' "$log" 2>/dev/null | sed -E 's/^.*AATube *: //' | head -80 || true; echo '```'
    echo
    echo '## Pipeline levels per step (lab build, one line per second)'
    echo '```'
    awk '
      /AATubeTest/ && /STEP/ { sub(/.*STEP /, ""); step=$0; order[++n]=step; next }
      /\[pipeline\] level=/ { match($0, /level=[A-Z_]+/); lvl=substr($0, RSTART+6, RLENGTH-6); c[step SUBSEP lvl]++; if ($0 ~ /GATE_VIOLATION/) v[step]++ }
      END {
        if (n == 0) { order[++n] = ""; }
        for (i = 1; i <= n; i++) { s = order[i]; line = (s == "" ? "(no steps)" : s) ":";
          split("NO_SURFACE SURFACE_ONLY APP_DRAWING MEDIA_DECODING", L, " ");
          for (j = 1; j <= 4; j++) if (c[s SUBSEP L[j]]) line = line " " L[j] "=" c[s SUBSEP L[j]];
          if (v[s]) line = line " GATE_VIOLATION=" v[s];
          print line }
      }' "$log" 2>/dev/null || true
    echo '```'
    echo
    if [[ -f "$RUN/screens.txt" ]]; then
      echo '## DHU screenshot pairs (1 s apart; host-side evidence)'
      echo '```'; cat "$RUN/screens.txt"; echo '```'
      echo
    fi
    if [[ -f "$RUN/checks.txt" ]]; then
      echo '## Automatic checks (objective log conditions only)'
      echo '```'; cat "$RUN/checks.txt"; echo '```'
      echo
    fi
    echo '## Steps'
    echo '```'; cat "$RUN/steps.txt" 2>/dev/null; echo '```'
  } > "$s"
}

pack_run() {
  local zip="$RUN.zip"
  ( cd "$(dirname "$RUN")" && if command -v zip >/dev/null 2>&1; then zip -qr "$(basename "$zip")" "$(basename "$RUN")"; else tar -czf "$(basename "$RUN").tar.gz" "$(basename "$RUN")"; fi )
  say "Results: $RUN"
  say "Archive: $(ls "$RUN".zip "$RUN".tar.gz 2>/dev/null | head -1)"
  say "Send the archive (or summary.md) back for debugging - see docs/RETURNING_RESULTS.md"
}
