#!/usr/bin/env bash
# One-shot setup for Ruchira's S24+ over ADB-on-Tailscale.
#
# The phone drops wireless debugging whenever its screen sleeps, so this does the
# whole job in a single pass: find the port, keep the screen on, install, grant,
# write the block and places, then capture what the app shows.
set -uo pipefail

HOST="${HOST:-100.82.18.61}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG=dev.hablock.app

find_serial() {
  local ports
  ports=$(python3 - "$HOST" <<'PY'
import asyncio, sys
host = sys.argv[1]
found = []
sem = asyncio.Semaphore(600)
async def probe(p):
    async with sem:
        try:
            r, w = await asyncio.wait_for(asyncio.open_connection(host, p), timeout=0.9)
            w.close()
            found.append(p)
        except Exception:
            pass
async def main():
    ports = list(range(30000, 50001))
    for i in range(0, len(ports), 2500):
        await asyncio.gather(*(probe(p) for p in ports[i:i + 2500]))
asyncio.run(main())
print(" ".join(str(p) for p in sorted(found)))
PY
)
  for p in $ports; do
    adb connect "$HOST:$p" >/dev/null 2>&1
    if adb devices | grep -q "^$HOST:$p[[:space:]]\+device"; then
      echo "$HOST:$p"
      return 0
    fi
  done
  return 1
}

SERIAL=$(find_serial) || { echo "FAIL: no adb device on the tailnet"; exit 1; }
echo "serial: $SERIAL"

d() { adb -s "$SERIAL" "$@"; }

# Keep the screen awake so Samsung does not tear the session down mid-install.
d shell svc power stayon true >/dev/null 2>&1

d install -r "$APK" | tail -1
d shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
d shell cmd appops set "$PKG" SCHEDULE_EXACT_ALARM allow >/dev/null 2>&1
d shell pm grant "$PKG" android.permission.health.READ_STEPS >/dev/null 2>&1
d shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
d shell pm grant "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION >/dev/null 2>&1
d shell settings put secure enabled_accessibility_services \
  "$PKG/$PKG.system.HablockAccessibilityService" >/dev/null 2>&1

d shell pm list packages | sed 's/package://' | tr -d '\r' > /tmp/s24_pkgs.txt
python3 scripts/s24_config.py --packages /tmp/s24_pkgs.txt --out /tmp/s24_prefs.pb

d push /tmp/s24_prefs.pb /data/local/tmp/prefs.pb >/dev/null
d shell "run-as $PKG mkdir -p files/datastore"
d shell "run-as $PKG sh -c 'cat /data/local/tmp/prefs.pb > files/datastore/hablock.preferences_pb'"
d shell am force-stop "$PKG"
d shell am start -n "$PKG/.ui.MainActivity" >/dev/null
sleep 5
d exec-out screencap -p > /tmp/s24_after.png
echo "done -> /tmp/s24_after.png"
