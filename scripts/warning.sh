#!/bin/bash
#
# warning.sh — uniform "human intervention required" signal.
# Usage: ./scripts/warning.sh "<reason>" "<current task>"
#
# Plays a sound, shows a macOS notification, prints a loud terminal block,
# writes HUMAN_INTERVENTION_REQUIRED and appends to AUTOMATION_STATE.md.
#
set -uo pipefail

REASON="${1:-unspecified}"
TASK="${2:-unspecified}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TS="$(date '+%Y-%m-%d %H:%M:%S')"
MARKER="$ROOT/HUMAN_INTERVENTION_REQUIRED"
STATE="$ROOT/AUTOMATION_STATE.md"
ADB="/usr/local/share/android-commandlinetools/platform-tools/adb"

DEV="$( "$ADB" devices 2>/dev/null | sed -n '2p' )"
[ -z "$DEV" ] && DEV="(no device)"

echo ""
echo -e "\033[41;97m  ⚠ HUMAN INTERVENTION REQUIRED  \033[0m"
echo "  Time:   $TS"
echo "  Reason: $REASON"
echo "  Task:   $TASK"
echo "  ADB:    $DEV"
echo ""

# Sound + macOS notification (osascript reaches Notification Center).
afplay /System/Library/Sounds/Basso.aiff >/dev/null 2>&1 &
osascript -e "display notification \"$REASON\" with title \"PixelMacSync ALERT\" subtitle \"Automation paused\" sound name \"Basso\"" >/dev/null 2>&1
say "PixelMacSync automation paused. Human intervention required." >/dev/null 2>&1 &

cat > "$MARKER" <<EOF
timestamp: $TS
reason: $REASON
current_task: $TASK
device_state: $DEV
adb_state: $DEV
required_human_action: see reason; reconnect/unlock device or grant permission, then delete this file
next_safe_action: re-audit device state, then resume
EOF

{
  echo ""
  echo "## [PAUSED] $TS"
  echo "- reason: $REASON"
  echo "- current task: $TASK"
  echo "- adb: $DEV"
  echo "- marker: HUMAN_INTERVENTION_REQUIRED"
} >> "$STATE" 2>/dev/null || {
  printf "# AUTOMATION_STATE.md\n\n## [PAUSED] %s\n- reason: %s\n- current task: %s\n- adb: %s\n" "$TS" "$REASON" "$TASK" "$DEV" > "$STATE"
}

echo "Wrote: $MARKER"
echo "Appended: $STATE"
exit 0
