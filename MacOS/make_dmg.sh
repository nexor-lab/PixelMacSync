#!/bin/bash
# Builds PixelSync.app (if needed) and packages it into a .dmg
# Usage: ./make_dmg.sh   ->  PixelMacSync-macOS12-Intel.dmg
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$SCRIPT_DIR/build/PixelSync.app"
DMG_NAME="PixelMacSync-macOS12-Intel"
STAGE="/tmp/pixelsync_dmg_stage"

if [ ! -d "$APP" ]; then
  "$SCRIPT_DIR/build_macos.sh"
fi

rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

OUT="$SCRIPT_DIR/build/$DMG_NAME.dmg"
rm -f "$OUT"
hdiutil create -volname "PixelSync" -srcfolder "$STAGE" -ov -format UDZO "$OUT"
rm -rf "$STAGE"

echo "==> Built: $OUT"
