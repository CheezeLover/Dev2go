#!/usr/bin/env bash
# Fetches the pinned opencode that gets cross-compiled to an arm64 Android binary and
# embedded in the APK, then applies our android adaptation on top.
#
# Not committed for the same reason as llama.cpp: it is ~230 MB of source. opencode is
# MIT licensed, so pinning and patching it is straightforward.
#
# Why the binary ships in the app: the requirement is opencode's full feature set
# running locally on the phone — providers, MCP, plugins, permissions, sessions. All
# of that lives in this codebase. Reimplementing the agent loop in Kotlin could not
# reproduce it, so the agent is the real opencode, cross-compiled.
#
# Bun cross-compiles to Android directly:
#     bun build --compile --target=bun-android-arm64
# which yields an ELF aarch64 binary with interpreter /system/bin/linker64 linking only
# libc/libm/libdl. That is why minSdk is 28 — Bun's android runtime is built for API 28.
#
# Upstream ships NO android target (grep -i android packages/cli/script/ finds nothing),
# so patches/patch-opencode-android.mjs derives build-android.ts from the upstream
# build script. It changes three things and nothing else:
#   1. adds { os: "android", arch: "arm64" } to the target table
#   2. resolves @parcel/watcher to a local no-op stub (no android prebuild exists;
#      watcher.ts already degrades on its own when the binding is absent)
#   3. targetName() emits opencode-android-arm64, which becomes bun-android-arm64
#
# The PTY needs no patch: resolveOpencodePty() returns undefined for anything that is
# not darwin/linux, and the build already emits `export default undefined` for it.
set -euo pipefail

COMMIT="1c74b9f0209deea9d7a6192fac989d8cf0562502"
SHA256="8fab60f19954231013be6dae5c989c403e721d2b98197c423ccc14edf1cb5dd3"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/third_party/opencode"
URL="https://github.com/anomalyco/opencode/archive/${COMMIT}.tar.gz"

if [ -d "$DEST" ]; then
    echo "opencode ${COMMIT} already present at ${DEST}"
else
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT

    echo "Downloading opencode ${COMMIT}…"
    curl -sSL -o "${TMP}/opencode.tar.gz" "$URL"

    echo "Verifying checksum…"
    echo "${SHA256}  ${TMP}/opencode.tar.gz" | sha256sum --check --status || {
        echo "Checksum mismatch for ${URL}" >&2
        echo "Refusing to install. Refusing to guess why." >&2
        exit 1
    }

    echo "Extracting…"
    mkdir -p "${TMP}/out"
    tar xzf "${TMP}/opencode.tar.gz" -C "${TMP}/out"

    EXTRACTED="$(find "${TMP}/out" -maxdepth 1 -mindepth 1 -type d | head -1)"
    if [ -z "$EXTRACTED" ]; then
        echo "Extraction produced no directory — the tarball layout may have changed" >&2
        exit 1
    fi

    mkdir -p "$(dirname "$DEST")"
    mv "$EXTRACTED" "$DEST"
    echo "opencode ${COMMIT} installed at ${DEST}"
fi

# Apply the android adaptation. Needs bun on PATH.
if ! command -v bun >/dev/null 2>&1; then
    echo "bun is not on PATH — needed to derive the android build script." >&2
    echo "Install it with: curl -fsSL https://bun.sh/install | bash" >&2
    exit 1
fi

echo "Applying android adaptation…"
(cd "$DEST" && bun "${ROOT}/patches/patch-opencode-android.mjs")