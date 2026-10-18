#!/bin/bash
# build_sim.sh — compile the iOS app for the iOS Simulator with swiftc (no
# XcodeGen/Xcode project needed). For a real device, use Xcode 26+ with
# `xcodegen generate` (project.yml) because Liquid Glass / iOS 26+ needs a newer
# SDK; this script is only for compile validation on the current toolchain.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
OUT="$DIR/build/PixelMacSync.app"
TARGET="${TARGET:-x86_64-apple-ios16.2-simulator}"

rm -rf "$OUT"
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

SRC=$(find "$DIR/PixelMacSync" -name '*.swift' | sort)

echo "==> SDK: $SDK"
echo "==> target: $TARGET"
swiftc -parse-as-library \
  -sdk "$SDK" \
  -target "$TARGET" \
  -o "$OUT/PixelMacSync" \
  $SRC \
  -framework CoreBluetooth \
  -framework Security \
  -framework LocalAuthentication \
  -framework UserNotifications \
  -framework Network

cp "$DIR/PixelMacSync/Info.plist" "$OUT/Info.plist"
for icon in "$DIR"/PixelMacSync/Resources/*.png; do [ -e "$icon" ] && cp "$icon" "$OUT/"; done
echo "==> Built: $OUT"
