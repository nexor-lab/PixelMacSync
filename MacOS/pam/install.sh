#!/bin/bash
# install.sh — install the PAM module and hook it into the lock screen.
# Requires root. Only ADDS a `sufficient` line; the password path is untouched.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SO="$DIR/pam_pixelsync.so"
DEST="/usr/local/lib/pam/pam_pixelsync.so"

if [ "$(id -u)" -ne 0 ]; then echo "ERROR: run with sudo" >&2; exit 1; fi
if [ ! -f "$SO" ]; then echo "ERROR: build first (./build.sh)" >&2; exit 1; fi
if [ ! -f /etc/pam.d/screensaver ]; then
  echo "ERROR: /etc/pam.d/screensaver not found (unsupported macOS variant)" >&2
  exit 1
fi

echo "==> Installing module -> $DEST"
mkdir -p /usr/local/lib/pam
install -m 755 -o root -g wheel "$SO" "$DEST"

echo "==> Backing up /etc/pam.d/screensaver"
cp -n /etc/pam.d/screensaver /etc/pam.d/screensaver.bak.pixelsync 2>/dev/null || true

if grep -q "pam_pixelsync.so" /etc/pam.d/screensaver; then
  echo "==> Already present"
else
  echo "==> Inserting sufficient auth line"
  awk '
    /^auth[ \t]+required[ \t]+pam_opendirectory/ && !done {
      print "auth       sufficient     /usr/local/lib/pam/pam_pixelsync.so";
      done=1
    }
    { print }
  ' /etc/pam.d/screensaver > /tmp/screensaver.new
  cp /tmp/screensaver.new /etc/pam.d/screensaver
  rm -f /tmp/screensaver.new
fi

echo "==> Result:"
cat /etc/pam.d/screensaver
echo
echo "Test: lock the screen, approve on the phone, then press Enter on the Mac."
echo "Rollback: sudo ./uninstall.sh"
