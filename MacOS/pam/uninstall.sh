#!/bin/bash
# uninstall.sh — remove the PAM hook + module.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then echo "ERROR: run with sudo" >&2; exit 1; fi

if [ -f /etc/pam.d/screensaver.bak.pixelsync ]; then
  echo "==> Restoring backup"
  cp /etc/pam.d/screensaver.bak.pixelsync /etc/pam.d/screensaver
else
  echo "==> Removing pam_pixelsync line"
  grep -v "pam_pixelsync.so" /etc/pam.d/screensaver > /tmp/screensaver.new
  cp /tmp/screensaver.new /etc/pam.d/screensaver
  rm -f /tmp/screensaver.new
fi

rm -f /usr/local/lib/pam/pam_pixelsync.so
echo "==> Done:"
cat /etc/pam.d/screensaver
