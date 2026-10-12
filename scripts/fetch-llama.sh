#!/usr/bin/env bash
# Fetches the pinned llama.cpp that the on-device LLM server is built against.
#
# The source is not committed: tens of megabytes across a thousand-odd files, and
# almost none of it is ever read by anyone reviewing this project. Pinning the tag
# and the checksum gives the same reproducibility at none of the cost.
#
# Why llama.cpp: it is the only runtime that gives us both halves of what this app
# needs. tools/server ships `llama-server-impl`, a library target commented upstream
# as "server logic, reusable by app", which we embed in-process through JNI instead
# of shipping a separate daemon. That target already serves OpenAI-compatible routes
# (/v1/models, /v1/chat/completions, /v1/completions) including tool calls and
# streaming, which is exactly what opencode's ollama provider consumes.
#
# It ships NO Ollama-native /api/* routes — grep for "ollama" under tools/server
# returns nothing at v0.6.0. Those are added by our own dev2go_server.cpp.
set -euo pipefail

TAG="v0.6.0"
SHA256="09b36dba235fcac180efff18514da7038e65d8e5b9e4aefa59238468abfdec12"

DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/src/main/cpp/third_party/llama.cpp"
URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${TAG}.tar.gz"

if [ -d "$DEST" ]; then
    echo "llama.cpp $TAG already present at $DEST"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading llama.cpp $TAG…"
curl -sSL -o "$TMP/llama.tar.gz" "$URL"

echo "Verifying checksum…"
echo "${SHA256}  ${TMP}/llama.tar.gz" | sha256sum --check --status || {
    echo "Checksum mismatch for ${URL}" >&2
    echo "Refusing to install. Refusing to guess why." >&2
    exit 1
}

echo "Extracting…"
mkdir -p "$TMP/out"
tar xzf "$TMP/llama.tar.gz" -C "$TMP/out"

# GitHub tags unpack with the name as it appears in the archive, which differs between
# `bNNNN` and `vN.N.N` forms. Find it rather than assuming a prefix.
EXTRACTED="$(find "$TMP/out" -maxdepth 1 -mindepth 1 -type d | head -1)"
if [ -z "$EXTRACTED" ]; then
    echo "Extraction produced no directory — the tarball layout may have changed" >&2
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
mv "$EXTRACTED" "$DEST"

echo "llama.cpp $TAG installed at $DEST"