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

# Provision root-owned phone public keys used to verify the signed grant.
# The v2 PAM module fails closed: without these keys, only the password works.
TRUST_DIR=/usr/local/etc/pixelsync/trusted_phones
install -d -m 755 -o root -g wheel /usr/local/etc/pixelsync "$TRUST_DIR"
TARGET_USER="${PMS_USER:-${SUDO_USER:-$(stat -f %Su /dev/console)}}"
USER_HOME="$(dscl . -read "/Users/$TARGET_USER" NFSHomeDirectory 2>/dev/null | awk '{print $2}')"
TRUST_JSON="$USER_HOME/Library/Application Support/PixelSync/trusted_devices.json"
if [ -f "$TRUST_JSON" ]; then
  echo "==> Provisioning trusted phone keys from: $TRUST_JSON"
  python3 - "$TRUST_JSON" "$TRUST_DIR" <<'PY'
import json, os, re, sys
src, outdir = sys.argv[1], sys.argv[2]
with open(src) as f:
    devices = json.load(f)
# Root-owned directory is rebuilt from the current trust list.
for name in os.listdir(outdir):
    if name.endswith(".pub"):
        os.unlink(os.path.join(outdir, name))
for d in devices:
    if not d.get("enabled", True):
        continue
    phone_id = d.get("id", "")
    pub = d.get("publicKeyB64", "")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", phone_id) or not pub:
        continue
    path = os.path.join(outdir, phone_id + ".pub")
    with open(path, "w") as f:
        f.write(pub)
    os.chmod(path, 0o644)
    print("   ", path)
PY
  chown root:wheel "$TRUST_DIR"/*.pub 2>/dev/null || true
else
  echo "WARN: trusted_devices.json not found for $TARGET_USER."
  echo "      Pair the phone in the app, then rerun: sudo ./install.sh"
fi

echo "==> Trusted phone keys:"
ls -l "$TRUST_DIR" 2>/dev/null || true

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
