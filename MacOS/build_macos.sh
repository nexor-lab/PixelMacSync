#!/bin/bash
#
# build_macos.sh — build PixelSync.app on macOS 12.7 using only the
# Xcode Command Line Tools (no full Xcode required).
#
# Usage:
#   ./build_macos.sh                 # x86_64 (default, 2015 Intel Mac)
#   ARCH=arm64 ./build_macos.sh      # Apple Silicon
#   ARCH=universal ./build_macos.sh  # fat binary
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/PixelSync/PixelSync"
ASSETS_DIR="$SCRIPT_DIR/PixelSync/AppIcon.icon/Assets"
OUT_DIR="$SCRIPT_DIR/build"
APP_NAME="PixelSync"
BUNDLE_ID="it.luigi.pixelsyncmac"
VERSION="2.3"
BUILD_NUMBER="4"
MIN_MACOS="12.0"
ARCH="${ARCH:-universal}"
DEBUG_BUILD="${DEBUG_BUILD:-0}"
BETA_BUILD="${BETA_BUILD:-0}"
BUILD_STAMP="$(date '+%Y-%m-%d %H:%M')"
if [ "$DEBUG_BUILD" = "1" ]; then
  DEBUG_FLAG="true";  BETA_FLAG="false"; DISPLAY_NAME="$APP_NAME Debug"
elif [ "$BETA_BUILD" = "1" ]; then
  DEBUG_FLAG="false"; BETA_FLAG="true";  DISPLAY_NAME="$APP_NAME Beta"
else
  DEBUG_FLAG="false"; BETA_FLAG="false"; DISPLAY_NAME="$APP_NAME"
fi

SDK="$(xcrun --show-sdk-path)"
APP="$OUT_DIR/$APP_NAME.app"
MACOS_DIR="$APP/Contents/MacOS"
RES_DIR="$APP/Contents/Resources"

echo "==> SDK:    $SDK"
echo "==> Arch:   $ARCH"
echo "==> Debug:  $DEBUG_FLAG  Beta: $BETA_FLAG (display name: $DISPLAY_NAME)"
echo "==> Output: $APP"

rm -rf "$APP"
mkdir -p "$MACOS_DIR" "$RES_DIR"

# ---------------------------------------------------------------------------
# 1. Compile the Swift sources directly into the app bundle executable.
#    -parse-as-library is required because of the @main attribute.
# ---------------------------------------------------------------------------
SOURCES=("$SRC_DIR/Protocol.swift" "$SRC_DIR/Localization.swift" "$SRC_DIR/LoginItem.swift" "$SRC_DIR/Platform.swift" "$SRC_DIR/KeyVault.swift" "$SRC_DIR/ContactsStore.swift" "$SRC_DIR/HandsFreeCall.swift" "$SRC_DIR/BLEManager.swift" "$SRC_DIR/ContentView.swift" "$SRC_DIR/PixelSyncApp.swift")

compile_arch() {
  local arch="$1"
  local out="$2"
  echo "==> Compiling for $arch ..."
  swiftc \
    -parse-as-library \
    -O \
    -whole-module-optimization \
    -target "${arch}-apple-macos${MIN_MACOS}" \
    -sdk "$SDK" \
    -framework CryptoKit \
    -framework IOBluetooth \
    -o "$out" \
    "${SOURCES[@]}"
}

if [ "$ARCH" = "universal" ]; then
  compile_arch "x86_64" "$OUT_DIR/$APP_NAME.x86_64"
  compile_arch "arm64"  "$OUT_DIR/$APP_NAME.arm64"
  lipo -create -output "$MACOS_DIR/$APP_NAME" "$OUT_DIR/$APP_NAME.x86_64" "$OUT_DIR/$APP_NAME.arm64"
  rm -f "$OUT_DIR/$APP_NAME.x86_64" "$OUT_DIR/$APP_NAME.arm64"
else
  compile_arch "$ARCH" "$MACOS_DIR/$APP_NAME"
fi

# ---------------------------------------------------------------------------
# 2. Info.plist
# ---------------------------------------------------------------------------
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key>
	<string>$APP_NAME</string>
	<key>CFBundleDisplayName</key>
	<string>$DISPLAY_NAME</string>
	<key>PixelSyncDebug</key>
	<string>$DEBUG_FLAG</string>
	<key>PixelSyncBeta</key>
	<string>$BETA_FLAG</string>
	<key>PixelSyncBuildTimestamp</key>
	<string>$BUILD_STAMP</string>
	<key>CFBundleIdentifier</key>
	<string>$BUNDLE_ID</string>
	<key>CFBundleExecutable</key>
	<string>$APP_NAME</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>$VERSION</string>
	<key>CFBundleVersion</key>
	<string>$BUILD_NUMBER</string>
	<key>CFBundleIconFile</key>
	<string>AppIcon</string>
	<key>CFBundleURLTypes</key>
	<array>
		<dict>
			<key>CFBundleURLName</key>
			<string>$BUNDLE_ID</string>
			<key>CFBundleURLSchemes</key>
			<array>
				<string>pixelsync</string>
			</array>
		</dict>
	</array>
	<key>LSMinimumSystemVersion</key>
	<string>$MIN_MACOS</string>
	<key>LSUIElement</key>
	<true/>
	<key>LSApplicationCategoryType</key>
	<string>public.app-category.utilities</string>
	<key>NSBluetoothAlwaysUsageDescription</key>
	<string>PixelSync usa il Bluetooth per sincronizzare le notifiche del Pixel.</string>
	<key>NSBluetoothPeripheralUsageDescription</key>
	<string>PixelSync usa il Bluetooth per sincronizzare le notifiche del Pixel.</string>
	<key>NSHumanReadableCopyright</key>
	<string>PixelMacSync — personal-use macOS 12 port</string>
</dict>
</plist>
PLIST

# ---------------------------------------------------------------------------
# 3. App icon (.icns generated from the original Icon Composer PNG assets)
# ---------------------------------------------------------------------------
ICON_SRC="$ASSETS_DIR/Frame 2.png"
if [ -f "$ICON_SRC" ]; then
  echo "==> Building AppIcon.icns ..."
  TMP_ICONSET="$OUT_DIR/AppIcon.iconset"
  rm -rf "$TMP_ICONSET"; mkdir -p "$TMP_ICONSET"
  for size in 16 32 128 256 512; do
    sips -z $size $size          "$ICON_SRC" --out "$TMP_ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z $double $double      "$ICON_SRC" --out "$TMP_ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$TMP_ICONSET" -o "$RES_DIR/AppIcon.icns"
  rm -rf "$TMP_ICONSET"
fi

# ---------------------------------------------------------------------------
# 4. Code signature.
#    A stable identity is strongly preferred: ad-hoc signed apps are refused
#    by the UserNotifications system ("Notifications are not allowed for this
#    application", UNErrorDomain Code=1) on macOS 12.
#    Use SIGN_IDENTITY=... to override, or it falls back to ad-hoc.
# ---------------------------------------------------------------------------
SIGN_IDENTITY="${SIGN_IDENTITY:-PixelMacSync Dev}"
if security find-identity -p codesigning 2>/dev/null | grep -q "$SIGN_IDENTITY"; then
  echo "==> Code signing with identity: $SIGN_IDENTITY"
  codesign --force --deep --sign "$SIGN_IDENTITY" "$APP" 2>&1 || echo "WARN: codesign failed"
else
  echo "==> No '$SIGN_IDENTITY' identity found; ad-hoc signing (notifications may be refused)"
  codesign --force --deep --sign - "$APP" 2>&1 || echo "WARN: codesign failed"
fi

echo ""
echo "==> Built: $APP"
echo "==> Verify: file \"$MACOS_DIR/$APP_NAME\""
file "$MACOS_DIR/$APP_NAME"
