#!/usr/bin/env bash
# Builds and installs AA Tube on the connected phone and grants its runtime
# permissions over adb, then prints the resulting grant state.
# usage: tools/device/install.sh [lab|daily]   (default: lab)
source "$(dirname "$0")/lib.sh"
flavor="${1:-lab}"
case "$flavor" in
  lab) task=installLabDebug; PKG="$PKG_LAB" ;;
  daily) task=installDailyDebug; PKG="$PKG_DAILY" ;;
  *) die "flavor must be lab or daily" ;;
esac
require_device
say "Building and installing $flavor debug"
if ! ( cd "$REPO_ROOT" && ./gradlew "$task" ); then
  warn "Install failed. If the error mentions INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature mismatch,"
  warn "e.g. an Internal App Sharing build is installed), uninstall first:  adb uninstall $PKG"
  exit 1
fi
say "Granting runtime permissions"
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION \
         android.permission.POST_NOTIFICATIONS com.google.android.gms.permission.CAR_SPEED; do
  if adb_ shell pm grant "$PKG" "$p" 2>/tmp/aatube-grant.$$; then echo "  granted $p"
  else echo "  could not grant $p over adb: $(tr -d '\r' < /tmp/aatube-grant.$$ | head -1)"; fi
done
rm -f /tmp/aatube-grant.$$
say "Grant state"
PKG="$PKG" permissions_report | sed 's/^/  /'
echo
echo "If CAR_SPEED could not be granted over adb, open the app and tap 'Grant permissions',"
echo "or accept the prompt Android Auto shows when AA Tube opens on the car screen."
