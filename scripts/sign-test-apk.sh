#!/usr/bin/env bash
# Signs a release APK with a self-signed test key, for sideloading onto a device.
#
# This is NOT for distribution. The key is generated locally, self-signed, and the password
# lives in this script, so anyone can produce an update the phone will accept. That is
# acceptable for testing and genuinely dangerous for shipping.
#
# Set DEV2GO_KEYSTORE and DEV2GO_KEY_PASS to sign with your own key.
#
# Usage: scripts/sign-test-apk.sh <unsigned.apk> [output.apk]
set -euo pipefail

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
BUILD_TOOLS="$(ls -d "$ANDROID_SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
KEYSTORE="${DEV2GO_KEYSTORE:-$HOME/dev2go-keys/dev2go-test.jks}"
STOREPASS="${DEV2GO_KEY_PASS:-dev2go-test}"

if [ -z "$BUILD_TOOLS" ]; then
    echo "No build-tools under $ANDROID_SDK; set ANDROID_SDK_ROOT." >&2
    exit 1
fi

INPUT="${1:?usage: sign-test-apk.sh <unsigned.apk> [output.apk]}"
OUTPUT="${2:-$(dirname "$INPUT")/$(basename "$INPUT" .apk)-test-signed.apk}"

if [ ! -f "$INPUT" ]; then
    echo "No such APK: $INPUT" >&2
    exit 1
fi

# Generate the key on first use.
if [ ! -f "$KEYSTORE" ]; then
    echo "No keystore at $KEYSTORE; generating a new test key."
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias dev2go-test \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storepass "$STOREPASS" -keypass "$STOREPASS" \
        -dname "CN=Dev2go Test, OU=Testing, O=Dev2go, L=-, ST=-, C=US"
fi

ALIGNED="$(mktemp -t dev2go-aligned.XXXXXX.apk)"
trap 'rm -f "$ALIGNED"' EXIT

# Align before signing. apksigner signs the final layout, and this APK holds several
# megabytes of native libraries, so 4-byte alignment matters for install-time mapping.
"$BUILD_TOOLS/zipalign" -p -f 4 "$INPUT" "$ALIGNED"

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias dev2go-test \
    --ks-pass "pass:$STOREPASS" \
    --key-pass "pass:$STOREPASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$OUTPUT" "$ALIGNED"

echo
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUTPUT" | head -10
echo
echo "Signed: $OUTPUT"
sha256sum "$OUTPUT"