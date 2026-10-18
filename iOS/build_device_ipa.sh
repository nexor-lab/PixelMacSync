#!/bin/bash
# build_device_ipa.sh — build a real-device (arm64) unsigned IPA with the current
# iOS SDK. Sign/install it with Sideloadly/AltStore (needs your Apple ID + the
# device UDID). This avoids Xcode's per-iOS DeviceSupport requirement.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
OUT="$DIR/build-device/PixelMacSync.app"
TARGET="${TARGET:-arm64-apple-ios16.2}"

rm -rf "$DIR/build-device"
mkdir -p "$OUT"

STAMP="$(date '+%Y-%m-%d %H:%M')"
cat > "$DIR/PixelMacSync/Core/BuildInfo.swift" <<EOF
import Foundation
enum BuildInfo {
    static let isBeta = true
    static let version = "1.0"
    static let timestamp = "$STAMP"
}
EOF

SRC=()
while IFS= read -r f; do SRC+=("$f"); done < <(find "$DIR/PixelMacSync" -name '*.swift' | sort)

echo "==> SDK: $SDK"
echo "==> target: $TARGET"
swiftc -parse-as-library \
  -sdk "$SDK" \
  -target "$TARGET" \
  -o "$OUT/PixelMacSync" \
  "${SRC[@]}" \
  -framework CoreBluetooth \
  -framework Security \
  -framework LocalAuthentication \
  -framework UserNotifications \
  -framework Network

cp "$DIR/PixelMacSync/Info.plist" "$OUT/Info.plist"
for icon in "$DIR"/PixelMacSync/Resources/*.png; do [ -e "$icon" ] && cp "$icon" "$OUT/"; done

mkdir -p "$DIR/build-device/Payload"
cp -R "$OUT" "$DIR/build-device/Payload/"
( cd "$DIR/build-device" && zip -qry PixelMacSync-unsigned.ipa Payload )

echo "==> App:  $OUT"
echo "==> IPA:  $DIR/build-device/PixelMacSync-unsigned.ipa"
file "$OUT/PixelMacSync" | sed 's/.*: //'
