#!/bin/bash
# build.sh — build pam_pixelsync.so (no sudo).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="$(xcrun --show-sdk-path)"
OUT="$DIR/pam_pixelsync.so"

clang -bundle -fPIC -O2 -Wall \
  -arch x86_64 -arch arm64 \
  -isysroot "$SDK" \
  -mmacosx-version-min=12.0 \
  -undefined dynamic_lookup \
  -o "$OUT" "$DIR/pam_pixelsync.c"

SIGN_IDENTITY="${SIGN_IDENTITY:-Apple Development}"
IDENT=$(security find-identity -v -p codesigning 2>/dev/null | grep -m1 "Apple Development" | sed -E 's/.*"(.*)"/\1/')
if [ -n "$IDENT" ]; then
  codesign --force --sign "$IDENT" "$OUT" && echo "==> Signed with: $IDENT"
else
  codesign --force --sign - "$OUT" 2>/dev/null || true
  echo "==> ad-hoc signed"
fi

echo "==> Built: $OUT"
nm -gU "$OUT" 2>/dev/null | grep -i pam_sm || true
