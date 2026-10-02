#!/system/bin/sh
#
# PixelMacSync root watchdog  (SukiSU Ultra / KernelSU "service.d")
# Install as: /data/adb/service.d/pixelsync_watchdog.sh   (chmod 0755)
#
# PURPOSE: recovery only. It never bypasses Android security:
#   - it does NOT fight "Force Stop" (detects stopped=true and gives up),
#   - it does NOT modify framework / SystemUI / SELinux / Bluetooth,
#   - it does NOT hide the process.
#
# Recovery is graded (least invasive first):
#   L1 BLE reconnect  -> handled inside the app (CoreBluetooth side)
#   L2 listener rebind -> `cmd notification disallow/allow_listener`
#   L3 service restart -> `am start-foreground-service`
#   L4 app process     -> `monkey` (launcher) only if the FGS start failed
#   L5 watchdog keeps a low-frequency loop with exponential backoff
#
# Low overhead: 60 s poll while healthy; backoff 5/10/20/40/80/120 s on failure.

PKG=it.luigi.macsync
ACTION=$PKG/.MainActivity
SVC=$PKG/.MacSyncBleService
COMP=$PKG/$PKG.ble.MacSyncNotificationListener
LOG=/data/adb/pixelsync_watchdog.log
LOCK=/data/adb/pixelsync_watchdog.lock
MAXLOG=200000

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }
rotate() { if [ -f "$LOG" ]; then sz=$(wc -c < "$LOG" 2>/dev/null); [ -n "$sz" ] && [ "$sz" -gt "$MAXLOG" ] && mv -f "$LOG" "$LOG.1"; fi; }

pkg_installed()    { pm path "$PKG" >/dev/null 2>&1; }
pkg_force_stopped(){
  # Only the current user's line matters; other profiles (e.g. User 999) are
  # always stopped and must not be mistaken for a Force Stop.
  local line
  line=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 "User 0:")
  case "$line" in
    *stopped=true*) return 0 ;;
    *) return 1 ;;
  esac
}
proc_running()     { pidof "$PKG" >/dev/null 2>&1; }
fgs_running()      { dumpsys activity services "$PKG" 2>/dev/null | grep -q "MacSyncBleService"; }
listener_enabled() { settings get secure enabled_notification_listeners 2>/dev/null | grep -q "$COMP"; }
listener_live()    { dumpsys notification 2>/dev/null | sed -n '/Live notification listeners/,/^$/p' | grep -q "$PKG"; }
bt_on()            { [ "$(settings get global bluetooth_on 2>/dev/null)" = "1" ]; }

recover() {
  rotate
  if ! pkg_installed; then log "PKG not installed -> idle"; return 1; fi

  if pkg_force_stopped; then
    log "PKG is FORCE-STOPPED -> NOT recovering (Android/system-controlled). LIMITATION."
    return 2
  fi

  # L3/L4: process / FGS
  if ! proc_running; then
    log "process missing -> start FGS"
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
    sleep 3
    if ! proc_running; then
      log "FGS start did not revive process -> launch activity (monkey)"
      monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      sleep 2
    fi
    if proc_running; then log "process revived"; else log "process still missing"; fi
  fi

  if pkg_installed && ! fgs_running && ! pkg_force_stopped; then
    log "FGS missing -> start FGS"
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
  fi

  # L2: notification listener enabled but not live (HyperOS quirk)
  if listener_enabled && ! listener_live; then
    log "listener enabled but NOT live -> rebind cycle"
    cmd notification disallow_listener "$COMP" >/dev/null 2>&1
    sleep 1
    cmd notification allow_listener "$COMP" >/dev/null 2>&1
    sleep 2
    if listener_live; then log "listener rebound (live)"; else log "listener still not live"; fi
  fi

  # Bluetooth state (informational; the app handles BT OFF/ON itself)
  if ! bt_on; then log "Bluetooth is OFF (app will resume when it returns)"; fi
  return 0
}

# --- single-instance lock (also verify it is really our watchdog) ---
if [ -f "$LOCK" ]; then
  oldpid=$(cat "$LOCK" 2>/dev/null)
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null \
     && grep -qa pixelsync_watchdog "/proc/$oldpid/cmdline" 2>/dev/null; then
    exit 0
  fi
  rm -f "$LOCK"
fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"; exit 0' TERM INT

log "==== watchdog started (pid $$) ===="

FAIL=0
BACKOFF=5
while true; do
  if proc_running && fgs_running && listener_live; then
    FAIL=0; BACKOFF=5
    sleep 60
    continue
  fi

  recover

  if proc_running && fgs_running && listener_live; then
    log "state OK after recovery"
    FAIL=0; BACKOFF=5
    sleep 60
  else
    FAIL=$((FAIL+1))
    log "recovery attempt #$FAIL incomplete; backoff ${BACKOFF}s"
    sleep "$BACKOFF"
    BACKOFF=$((BACKOFF*2))
    [ "$BACKOFF" -gt 120 ] && BACKOFF=120
    if [ "$FAIL" -ge 8 ]; then
      log "too many failures -> idle 300s (likely Force Stop / user action)"
      sleep 300
      FAIL=0; BACKOFF=5
    fi
  fi
done
