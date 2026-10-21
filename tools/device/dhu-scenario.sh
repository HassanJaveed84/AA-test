#!/usr/bin/env bash
# Automated DHU lab run (macOS / Linux). Starts the Desktop Head Unit with the
# repo's sensor config, drives it through its console (speed, restrict,
# screenshot), drives AA Tube Lab over adb (mode, link, play), and records
# logcat with a STEP marker per step, DHU screenshot pairs, dumpsys snapshots
# and the lab log. Ends with summary.md and an archive to send back.
#
# Requires: phone on USB, unlocked, Android Auto developer settings enabled,
# "Start head unit server" running, AA Tube Lab (debug) installed.
#
# usage: tools/device/dhu-scenario.sh [--video URL] [--modes "TEST_PATTERN DIRECT YOUTUBE_TV"]
#                                     [--manual] [--full-logcat] [name]
#   --manual   do not pipe commands into the DHU; print each one for you to
#              type into the DHU console (use this on Windows/Git Bash, or if
#              the DHU ignores piped input).
source "$(dirname "$0")/lib.sh"

video="https://www.youtube.com/watch?v=aqz-KE-bpKQ"   # Big Buck Bunny (Blender Foundation), embeddable
modes="TEST_PATTERN DIRECT YOUTUBE_TV"
manual=0
name="dhu"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --video) video="$2"; shift 2 ;;
    --modes) modes="$2"; shift 2 ;;
    --manual) manual=1; shift ;;
    --full-logcat) FULL_LOGCAT=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) name="$1"; shift ;;
  esac
done

require_device
adb_ shell pm path "$PKG" 2>/dev/null | grep -q package || die "$PKG not installed: run tools/device/install.sh"
DHU="$(find_dhu)"; DHU_DIR="$(dirname "$DHU")"
INI="$REPO_ROOT/docs/dhu/headunit.ini"
RUN="$(new_run_dir "$name")"
[[ "$RUN" != *" "* ]] || warn "Results path contains spaces; DHU screenshots may fail. Move the repo to a path without spaces."
device_facts > "$RUN/device.txt"
permissions_report > "$RUN/permissions.txt"
: > "$RUN/screens.txt"; : > "$RUN/checks.txt"

app() { adb_ shell am start -n "$PKG/$APP_CLASS" "$@" >/dev/null 2>&1 || warn "am start failed: $*"; }
share() { adb_ shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$1'" -n "$PKG/$APP_CLASS" >/dev/null 2>&1 || warn "share failed"; }

DHU_PID=""
cleanup() {
  [[ -n "${DHU_FD_OPEN:-}" ]] && exec 3>&- || true
  [[ -n "$DHU_PID" ]] && kill "$DHU_PID" 2>/dev/null || true
  stop_logcat
}

say "Forwarding tcp:5277 and starting the DHU"
adb_ forward tcp:5277 tcp:5277
start_logcat
trap cleanup EXIT
if [[ $manual == 0 ]]; then
  FIFO="$RUN/.dhu.fifo"; mkfifo "$FIFO"
  ( cd "$DHU_DIR" && exec "$DHU" -c "$INI" ) < "$FIFO" > "$RUN/dhu-console.txt" 2>&1 &
  DHU_PID=$!
  exec 3>"$FIFO"; DHU_FD_OPEN=1
  dhu() { printf '%s\n' "$*" >&3; printf '%s dhu> %s\n' "$(date -u +%H:%M:%SZ)" "$*" >> "$RUN/steps.txt"; sleep 0.3; }
else
  say "Start the DHU yourself in another terminal:"
  echo "    cd \"$DHU_DIR\" && ./$(basename "$DHU") -c \"$INI\""
  read -r -p "Press Enter once the DHU window shows Android Auto... " _
  dhu() { printf '\n  >>> type into the DHU console:  %s\n' "$*"; read -r -p "      (Enter when done) " _; printf '%s dhu> %s\n' "$(date -u +%H:%M:%SZ)" "$*" >> "$RUN/steps.txt"; }
fi

# Two DHU screenshots 1 s apart; identical files mean the host showed a frozen picture.
shot_pair() {
  local label="$1" a="$RUN/screens/dhu-$1-a.png" b="$RUN/screens/dhu-$1-b.png"
  dhu "screenshot $a"; sleep 1; dhu "screenshot $b"
  for _ in 1 2 3 4 5 6; do [[ -s "$a" && -s "$b" ]] && break; sleep 0.5; done
  local verdict
  if [[ ! -s "$a" || ! -s "$b" ]]; then verdict="missing (DHU did not save screenshots: use --manual)"
  elif cmp -s "$a" "$b"; then verdict="static (identical frames 1 s apart)"
  else verdict="changing (frames differ 1 s apart)"; fi
  printf '%-34s %s\n' "$label" "$verdict" >> "$RUN/screens.txt"
  echo "    screenshots: $verdict"
}

# Log lines since the last STEP marker (scope "step") or the whole capture ("all").
log_scope() {
  if [[ "${1:-step}" == all ]]; then cat "$RUN/.logcat-full.txt"
  else awk '/AATubeTest/ && /STEP/ {buf=""} {buf=buf"\n"$0} END {print buf}' "$RUN/.logcat-full.txt"; fi
}

# Wait until the app logs a line matching $1 (within $3 scope, default this step).
wait_for_log() {
  local pattern="$1" timeout="${2:-30}" scope="${3:-step}" t=0
  while (( t < timeout )); do
    if log_scope "$scope" | grep -q -E "$pattern"; then return 0; fi
    sleep 1; t=$((t+1))
  done
  return 1
}

# Collect, summarise and package; also used when the run stops early.
finish() {
  snapshot_dumpsys end
  phone_screenshot end
  mark "STEP done"
  cleanup
  trap - EXIT
  pull_lab_logs
  summarize
  pack_run
}

fail_run() {
  echo "FAIL  $1" >> "$RUN/checks.txt"
  warn "$1"
  finish
  exit 1
}

check() { # check <label> <pattern-that-must-appear> [timeout] [scope]
  if wait_for_log "$2" "${3:-10}" "${4:-step}"; then echo "PASS  $1" >> "$RUN/checks.txt"; echo "    check PASS: $1"
  else echo "FAIL  $1  (no log line matching: $2)" >> "$RUN/checks.txt"; echo "    check FAIL: $1"; fi
}

check_absent() { # check_absent <label> <pattern-that-must-not-appear-in-step>
  if log_scope step | grep -q -E "$2"; then
    echo "FAIL  $1  (found: $2)" >> "$RUN/checks.txt"; echo "    check FAIL: $1"
  else echo "PASS  $1" >> "$RUN/checks.txt"; echo "    check PASS: $1"; fi
}

step() { mark "STEP $1"; say "STEP $1"; }

say "Waiting for the DHU to connect. Then open 'AA Tube Lab' on the DHU screen (app launcher)."
dhu "speed 0"
step "open-app"
# "all": the app may already have been open on the DHU before this script started.
if ! wait_for_log '\[surface\] display attached' 180 all; then
  fail_run "AA Tube Lab did not attach to the car surface within 180 s (is it in the DHU launcher? If it was already open, close and reopen it.)"
fi
snapshot_dumpsys attached
check "gate opens on DHU speed 0 (car speed source)" 'decision=OPEN by CAR' 15 all

for mode in $modes; do
  m="$(printf '%s' "$mode" | tr 'A-Z_' 'a-z-')"
  step "$m-parked"
  dhu "restrict none"; dhu "speed 0"
  case "$mode" in
    TEST_PATTERN) app --es aatube.mode TEST_PATTERN ;;
    DIRECT) app --es aatube.mode DIRECT; share "$video" ;;
    YOUTUBE_TV) app --es aatube.mode YOUTUBE_TV; share "$video" ;;
  esac
  if [[ "$mode" == YOUTUBE_TV ]]; then wait_for_log 'deep-linked video playing|auto-continue stopped|profile picker lists' 45 || true; fi
  sleep 12
  shot_pair "$m-parked"
  check "$m: media progressing while parked" 'pipeline\] level=MEDIA_DECODING' 10
  snapshot_dumpsys "$m-parked"
  phone_screenshot "$m-parked"

  step "$m-restrict-all-speed0"
  dhu "restrict all"; sleep 6
  shot_pair "$m-restrict-all"
  check "$m: host restrictions alone do not stop media (desk, speed 0)" 'pipeline\] level=MEDIA_DECODING' 6
  dhu "restrict none"

  step "$m-moving"
  dhu "speed 8"; sleep 4
  shot_pair "$m-moving"
  check "$m: gate closes on speed 8 m/s" 'gate-transition\].*decision=CLOSED MOVING' 5
  check_absent "$m: no media progress while moving" 'GATE_VIOLATION'

  step "$m-play-while-moving"
  app --es aatube.command PLAY; sleep 3
  check "$m: PLAY refused while moving" 'PLAY refused' 5
  check_absent "$m: still no media progress after PLAY" 'GATE_VIOLATION'

  step "$m-stopped"
  dhu "speed 0"; sleep 8
  shot_pair "$m-stopped"
  check "$m: gate settles then reopens" 'gate-transition\].*decision=OPEN' 10
  check_absent "$m: no automatic resume after stopping" 'pipeline\] level=MEDIA_DECODING'

  step "$m-resume"
  app --es aatube.command PLAY; sleep 6
  shot_pair "$m-resume"
  check "$m: media resumes on PLAY" 'pipeline\] level=MEDIA_DECODING' 8
done

step "sensor-off"
dhu "speed"; sleep 15
check "gate logs its source after the car speed sensor is switched off" 'gate-(snapshot|transition)\].*speedSource=' 12

say "Scenario finished; collecting results"
finish
