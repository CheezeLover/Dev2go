#!/usr/bin/env bash
# Fetches OpenChamber's built mobile app and stages it into the APK's assets.
#
# Why this exists: OpenChamber already ships a mobile surface (mobile.html ->
# renderMobileApp). It is a *direct* opencode client — of everything the desktop UI calls,
# it uses exactly two endpoints, both `client-auth` pairing, and everything else goes
# straight to `opencode serve`. That is the same architecture this app already has, so
# shipping OpenChamber's own UI beats reimplementing it.
#
# The closure is 129 files / ~8.6 MB, not the 47 MB desktop bundle. That is the difference
# between "embed the mobile app" and "embed the web app".
#
# One patch is applied, described in patch-openchamber-auth.mjs.
#
# @openchamber/web is MIT licensed (github.com/openchamber/openchamber).
set -euo pipefail

VERSION="2.3.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/app/src/main/assets/openchamber"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -d "$DEST" ] && [ -f "$DEST/.stamp" ] && [ "$(cat "$DEST/.stamp")" = "$VERSION" ]; then
    echo "OpenChamber web $VERSION already staged at $DEST"
    exit 0
fi

echo "Downloading @openchamber/web@$VERSION…"
cd "$WORK"
npm pack "@openchamber/web@$VERSION" --silent > tarball.txt
TARBALL="$(cat tarball.txt)"
tar xzf "$TARBALL"

PKG="${WORK}/package"
if [ ! -d "$PKG/dist" ]; then
    echo "Expected a built dist/ in the tarball; the package layout may have changed." >&2
    exit 1
fi

echo "Staging the mobile closure…"
rm -rf "$DEST"
mkdir -p "$DEST/assets"
cp "$PKG/dist/mobile.html" "$DEST/"

node "$ROOT/scripts/resolve-closure.mjs" "$PKG/dist/assets" "$DEST/assets"

# Only the HTML and the assets are needed. Copying the logo and PWA icons keeps the app
# from rendering a blank tab strip during launch.
for icon in favicon.svg logo-dark-192x192.png logo-light-192x192.png apple-touch-icon.png; do
    [ -f "$PKG/dist/$icon" ] && cp "$PKG/dist/$icon" "$DEST/"
done
true

echo "Applying the auth patch…"
node "$ROOT/scripts/patch-openchamber-auth.mjs" "$DEST/assets"

echo "$VERSION" > "$DEST/.stamp"

COUNT="$(find "$DEST" -type f | wc -l | tr -d ' ')"
SIZE="$(du -sh "$DEST" | cut -f1)"
echo "Staged ${COUNT} files (${SIZE}) at $DEST"