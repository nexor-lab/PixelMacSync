#!/bin/bash
# Compiles and runs the BLE packet-parser unit tests (CLT only, no hardware).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="$(xcrun --show-sdk-path)"
OUT="/tmp/pixelsync_parser_test"
TRUST_OUT="/tmp/pixelsync_trusted_device_store_test"

swiftc \
  -target x86_64-apple-macos12.0 \
  -sdk "$SDK" \
  -o "$OUT" \
  "$SCRIPT_DIR/PixelSync/PixelSync/Protocol.swift" \
  "$SCRIPT_DIR/tests/parser_test.swift"

"$OUT"

swiftc \
  -target x86_64-apple-macos12.0 \
  -sdk "$SDK" \
  -o "$TRUST_OUT" \
  "$SCRIPT_DIR/PixelSync/PixelSync/unlock/TrustedDeviceStore.swift" \
  "$SCRIPT_DIR/tests/trusted_device_store_test.swift"

"$TRUST_OUT"
