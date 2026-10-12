#!/usr/bin/env bash
# Cross-compiles opencode to an arm64 Android binary.
#
# This is the agent the app runs. Not a wrapper, not an emulator: Bun compiles opencode
# straight to an ELF aarch64 binary with interpreter /system/bin/linker64, linking only
# libc/libm/libdl. Running it on the phone is running opencode on the phone.
#
# Output: dist/opencode-arm64 (~192 MB)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${ROOT}/dist"

if ! command -v bun >/dev/null 2>&1; then
    echo "bun is not on PATH. Install with: curl -fsSL https://bun.sh/install | bash" >&2
    exit 1
fi

"${ROOT}/scripts/fetch-opencode.sh"

echo "Cross-compiling opencode for android-arm64…"
# --skip-web-ui: the Android client is native Compose, so bundling opencode's own web
# assets would add tens of megabytes nothing ever loads.
(cd "${ROOT}/third_party/opencode" && bun run packages/cli/script/build-android.ts \
    --target=opencode-android-arm64 \
    --skip-web-ui \
    --skip-install)

SRC="${ROOT}/third_party/opencode/packages/cli/dist/cli-android-arm64/bin/opencode"
if [ ! -f "$SRC" ]; then
    echo "Expected a build at ${SRC}; it is not there." >&2
    exit 1
fi

mkdir -p "$OUT"
cp "$SRC" "${OUT}/opencode-arm64"
chmod +x "${OUT}/opencode-arm64"

echo
file "${OUT}/opencode-arm64"
ls -la "${OUT}/opencode-arm64"
echo
echo "Built: ${OUT}/opencode-arm64"
echo "Install it with: adb push ${OUT}/opencode-arm64 /sdcard/Download/"