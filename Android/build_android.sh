#!/bin/bash
#
# build_android.sh — build + sign a release MacSync APK without Android Studio.
#
# Requires:
#   JAVA_HOME  -> JDK 17+ (tested with Temurin 21)
#   ANDROID_HOME -> Android SDK with platforms;android-36[.1] and build-tools;36.x
#
# Usage: ./build_android.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${JAVA_HOME:?set JAVA_HOME to a JDK 17+}"
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

GRADLE="${GRADLE:-gradle}"          # gradle 9.4.1 or ./gradlew
VARIANT="${VARIANT:-Release}"       # Release | Beta | Debug
VARIANT_LC="$(echo "$VARIANT" | tr '[:upper:]' '[:lower:]')"
OUT_NAME="${OUT_NAME:-PixelMacSync-Android.apk}"
BT_VERSION="${BT_VERSION:-36.1.0}"
BT="$ANDROID_HOME/build-tools/$BT_VERSION"
KEYSTORE="${KEYSTORE:-$SCRIPT_DIR/keystore/pixelsync.jks}"
# Never hardcode the signing password: provide it via the environment, e.g.
#   KEYSTORE=/path/to/your.jks KS_PASS=… ./build_android.sh
KS_PASS="${KS_PASS:-}"
if [ -z "$KS_PASS" ]; then
  echo "ERROR: KS_PASS is not set (keystore password)." >&2
  echo "       Set KEYSTORE and KS_PASS in the environment." >&2
  exit 2
fi

echo "sdk.dir=$ANDROID_HOME" > "$SCRIPT_DIR/local.properties"

cd "$SCRIPT_DIR"
"$GRADLE" ":app:assemble${VARIANT}" --no-daemon --console=plain

# Release produces an *-unsigned.apk; debug/beta are already signed (debug key),
# so fall back to the signed artifact and re-sign it with the release keystore.
SRC_APK="app/build/outputs/apk/${VARIANT_LC}/app-${VARIANT_LC}-unsigned.apk"
[ -f "$SRC_APK" ] || SRC_APK="app/build/outputs/apk/${VARIANT_LC}/app-${VARIANT_LC}.apk"
ALIGNED="app/build/outputs/apk/${VARIANT_LC}/app-${VARIANT_LC}-aligned.apk"
OUT="$SCRIPT_DIR/$OUT_NAME"

"$BT/zipalign" -f -p 4 "$SRC_APK" "$ALIGNED"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED"

"$BT/apksigner" verify --print-certs "$OUT" | head -4
echo "==> Built: $OUT"
