#!/usr/bin/env bash
# Checks the computer, phone and DHU setup before a test run.
# usage: tools/device/preflight.sh
source "$(dirname "$0")/lib.sh"

ok=1
check() { if eval "$2" >/dev/null 2>&1; then printf '  [ok]  %s\n' "$1"; else printf '  [!!]  %s  -> %s\n' "$1" "$3"; ok=0; fi; }

say "Computer"
check "adb found ($ADB)" "true" ""
check "Android SDK found" "sdk_root" "set ANDROID_HOME"
check "DHU installed" "find_dhu" "SDK Manager -> SDK Tools -> Android Auto Desktop Head Unit Emulator"
check "Java 17+ (for ./gradlew)" "java -version 2>&1 | grep -E 'version \"(1[7-9]|2[0-9])'" "install JDK 17 or newer"

say "Phone"
require_device
device_facts | sed 's/^/  /'
check "Android Auto installed" "adb_ shell pm path $GEARHEAD | grep -q package" "install Android Auto from Play"
check "Location is on" "[ \"\$(adb_ shell settings get secure location_mode | tr -d '\r')\" != 0 ]" "turn on Location (GPS fallback for the parked check)"
check "AA Tube Lab installed" "adb_ shell pm path $PKG_LAB | grep -q package" "run tools/device/install.sh"

say "AA Tube Lab permissions"
permissions_report | sed 's/^/  /'

say "Reminders (cannot be checked over adb)"
echo "  - Android Auto -> Settings -> tap 'Version' 10x -> OK to enable developer settings"
echo "  - Android Auto -> overflow menu -> 'Start head unit server' (needed before each DHU session)"
echo "  - Phone stays unlocked and on USB during DHU runs"
[[ $ok == 1 ]] && say "Preflight OK" || warn "Fix the [!!] items above"
