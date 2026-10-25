#!/usr/bin/env bash
# Builds EndGrid iQTV+ for LG TVs (webOS): the app package that the TV's Developer Mode installs. Needs bash and a
# JDK (17+), no LG tools: tools/WebPack.java packs it the way LG's ares-package does.
#
#   ./webos/build.sh            # your build: your config.properties (TMDB key, name, languages) -> iQTV-webos.ipk
#   ./webos/build.sh --public   # a build to share: config.example.properties only -> release/iQTV-webos-<version>.ipk
#   ./webos/build.sh --preview  # also leaves the app in out/webos-preview/ to open index.html in a browser
#   ./webos/build.sh --config other.properties
#
# Install it: ./webos/install.sh <TV IP> (see docs/LG-SAMSUNG.md).
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/webapp.sh
webapp_args "$@"

ID="$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' webos/appinfo.json | head -1)"
VERSION="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' webos/appinfo.json | head -1)"
APP=out/webos
webapp_files "$APP" webos/build.sh
cp webos/appinfo.json webos/icon.png webos/largeIcon.png "$APP/"
if $PUBLIC; then webapp_check_public "$APP"; fi

IPK=iQTV-webos.ipk
if $PUBLIC; then mkdir -p release; IPK="release/iQTV-webos-$VERSION.ipk"; fi
"$JAVA" tools/WebPack.java ipk "$APP" "$IPK" "$ID" "$VERSION"
if $PREVIEW; then webapp_preview "$APP" out/webos-preview; fi
echo "Built $IPK ($ID $VERSION, $(du -k "$IPK" | cut -f1) KB)."
webapp_has_key "$APP" || echo "No TMDB key: the rows stay off until you add one (in config.properties, or on the TV in Settings)."
