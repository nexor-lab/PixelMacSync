#!/bin/bash
#
# resign_macos.sh — (re)sign PixelSync.app with an Apple-issued certificate.
#
# Adapted from Hequnjie/ReSignShell (macOS/macOSReSign), simplified for a
# non-sandboxed, framework-free menu-bar app: no provisioning profile is
# required for local use, and no .pkg is built.
#
# Usage:
#   ./resign_macos.sh --app <PixelSync.app> \
#       [--p12 <cert.p12> --password <pw>] \
#       [--identity "Apple Development: you@example.com (XXXXXXXXXX)"] \
#       [--profile <embedded.provisionprofile>] \
#       [--entitlements <entitlements.plist>] \
#       [--keychain <path>]
#
# If --p12 is given it is imported into a private temporary keychain and the
# identity is discovered automatically. Otherwise --identity is used from the
# login keychain.
#
set -euo pipefail

APP=""
P12=""
PASSWORD=""
IDENTITY=""
PROFILE=""
ENTITLEMENTS=""
KEYCHAIN=""
SIGN_KEYCHAIN=""

while [ $# -gt 0 ]; do
  case "$1" in
    --app)          APP="$2"; shift 2 ;;
    --p12)          P12="$2"; shift 2 ;;
    --password)     PASSWORD="$2"; shift 2 ;;
    --identity)     IDENTITY="$2"; shift 2 ;;
    --profile)      PROFILE="$2"; shift 2 ;;
    --entitlements) ENTITLEMENTS="$2"; shift 2 ;;
    --keychain)     KEYCHAIN="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 2 ;;
  esac
done

[ -n "$APP" ] && [ -d "$APP" ] || { echo "ERROR: --app <PixelSync.app> required"; exit 2; }

TMP_KC=""
ORIG_KC_LIST="$(security list-keychains -d user | sed 's/^ *//; s/ *$//; s/"//g' | tr '\n' ' ')"
cleanup() {
  if [ -n "$TMP_KC" ]; then
    if [ -n "$ORIG_KC_LIST" ]; then
      security list-keychains -d user -s $ORIG_KC_LIST 2>/dev/null || true
    fi
    security delete-keychain "$TMP_KC" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# --- optionally import the .p12 into a private keychain -----------------------
if [ -n "$P12" ]; then
  [ -f "$P12" ] || { echo "ERROR: p12 not found: $P12"; exit 2; }
  TMP_KC="${KEYCHAIN:-$HOME/Library/Keychains/pixelsync-resign.keychain-db}"
  security delete-keychain "$TMP_KC" 2>/dev/null || true
  security create-keychain -p "resign" "$TMP_KC"
  security set-keychain-settings -lut 21600 "$TMP_KC"
  security unlock-keychain -p "resign" "$TMP_KC"
  security import "$P12" -k "$TMP_KC" -P "$PASSWORD" -T /usr/bin/codesign -T /usr/bin/security
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "resign" "$TMP_KC" >/dev/null
  # Prepend the temp keychain to the search list for this session.
  security list-keychains -d user -s "$TMP_KC" /Library/Keychains/System.keychain $(security list-keychains -d user | tr -d '"')

  # Apple Worldwide Developer Relations intermediates are required to build the
  # trust chain for codesign. Import G3/G4 if missing.
  for ca in AppleWWDRCAG3 AppleWWDRCAG4; do
    ca_tmp="$(mktemp /tmp/${ca}.XXXX.cer)"
    if curl -sL --max-time 30 "https://www.apple.com/certificateauthority/${ca}.cer" -o "$ca_tmp" 2>/dev/null && [ -s "$ca_tmp" ]; then
      security import "$ca_tmp" -k "$TMP_KC" -T /usr/bin/codesign >/dev/null 2>&1 || true
    fi
    rm -f "$ca_tmp"
  done

  IDENTITY="$(security find-identity -v -p codesigning "$TMP_KC" | awk -F'"' '/"/{print $2; exit}')"
  if [ -z "$IDENTITY" ]; then
    # Fall back to a non-validated match (chain may validate at sign time).
    IDENTITY="$(security find-identity -p codesigning "$TMP_KC" | awk -F'"' '/"/{print $2; exit}')"
  fi
  [ -n "$IDENTITY" ] || { echo "ERROR: no code-signing identity in $P12"; exit 1; }
  # codesign must be pointed at the private temp keychain explicitly; relying
  # on the user search list alone makes it fail with errSecInternalComponent
  # ("unable to build chain to self-signed root") on some macOS 12 setups.
  SIGN_KEYCHAIN="$TMP_KC"
fi

[ -n "$IDENTITY" ] || { echo "ERROR: provide --p12 or --identity"; exit 2; }

echo "==> Signing identity: $IDENTITY"

# --- entitlements (from profile if provided, else from --entitlements) --------
ENT_FILE=""
if [ -n "$PROFILE" ]; then
  [ -f "$PROFILE" ] || { echo "ERROR: profile not found: $PROFILE"; exit 2; }
  cp "$PROFILE" "$APP/Contents/embedded.provisionprofile"
  ENT_FILE="$(mktemp /tmp/pixelsync_ent.XXXXXX.plist)"
  security cms -D -i "$PROFILE" | /usr/libexec/PlistBuddy -x -c "print :Entitlements" /dev/stdin > "$ENT_FILE" 2>/dev/null || ENT_FILE=""
elif [ -n "$ENTITLEMENTS" ]; then
  ENT_FILE="$ENTITLEMENTS"
fi

sign() {
  local target="$1"
  local kc=()
  [ -n "$SIGN_KEYCHAIN" ] && kc=(--keychain "$SIGN_KEYCHAIN")
  # Use a secure (Apple TSA) timestamp so the signature stays valid after the
  # development certificate expires; fall back to no timestamp if offline.
  if [ -n "$ENT_FILE" ]; then
    codesign --force --options runtime "${kc[@]}" --timestamp --entitlements "$ENT_FILE" --sign "$IDENTITY" "$target" \
      || codesign --force --options runtime "${kc[@]}" --timestamp=none --entitlements "$ENT_FILE" --sign "$IDENTITY" "$target"
  else
    codesign --force --options runtime "${kc[@]}" --timestamp --sign "$IDENTITY" "$target" \
      || codesign --force --options runtime "${kc[@]}" --timestamp=none --sign "$IDENTITY" "$target"
  fi
}

# --- re-sign nested code first (none expected, but be safe) -------------------
for sub in Frameworks PlugIns XPCServices; do
  DIR="$APP/Contents/$sub"
  [ -d "$DIR" ] || continue
  find "$DIR" -maxdepth 2 \( -name "*.framework" -o -name "*.appex" -o -name "*.dylib" -o -name "*.xpc" \) -print0 |
    while IFS= read -r -d '' item; do
      rm -rf "$item/_CodeSignature" "$item/Contents/_CodeSignature"
      sign "$item"
    done
done

# --- re-sign the app ----------------------------------------------------------
rm -rf "$APP/Contents/_CodeSignature"
sign "$APP"

# --- verify -------------------------------------------------------------------
echo "==> codesign verify"
codesign --verify --deep --strict --verbose=2 "$APP" || true
echo "==> signature info"
codesign -dvvv "$APP" 2>&1 | grep -E "Identifier|Authority|TeamIdentifier|Signature|flags" || true
echo "==> Gatekeeper assessment (expected 'source=...' for local built apps)"
spctl -a -vvv "$APP" 2>&1 || true
echo "==> Done."
if [ -n "$ENT_FILE" ] && [ "$ENT_FILE" != "$ENTITLEMENTS" ]; then rm -f "$ENT_FILE"; fi
