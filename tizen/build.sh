#!/usr/bin/env bash
# Builds EndGrid iQTV+ for Samsung TVs (Tizen): the app package (.wgt) the TV installs. Needs bash and a JDK (17+).
#
#   ./tizen/build.sh                 # your build: your config.properties -> iQTV-tizen.wgt, not signed yet
#   ./tizen/build.sh --sign PROFILE  # signed with a certificate profile of Samsung's Tizen tools (`tizen package`)
#   ./tizen/build.sh --public        # a build to share: config.example.properties only -> release/iQTV-tizen-<version>.wgt
#   ./tizen/build.sh --preview       # also leaves the app in out/tizen-preview/ to open index.html in a browser
#
# Samsung TVs only install signed packages: --sign here, or TizenBrew Installer, which signs it with your Samsung
# certificate (see docs/LG-SAMSUNG.md). Install a signed build with: java tizen/Install.java <TV IP>.
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/webapp.sh
webapp_args "$@"

ID="$(sed -n 's/.*<tizen:application id="\([^"]*\)".*/\1/p' tizen/config.xml | head -1)"
VERSION="$(sed -n 's/.*<widget [^>]*version="\([^"]*\)".*/\1/p' tizen/config.xml | head -1)"
APP=out/tizen
webapp_files "$APP" tizen/build.sh
cp tizen/config.xml tizen/icon.png "$APP/"
if $PUBLIC; then webapp_check_public "$APP"; fi

WGT=iQTV-tizen.wgt
if $PUBLIC; then mkdir -p release; WGT="release/iQTV-tizen-$VERSION.wgt"; fi
"$JAVA" tools/WebPack.java wgt "$APP" "$WGT"

if [ -n "$PROFILE" ]; then
  # Samsung's `tizen` command: Tizen Studio's CLI or Samsung's Tizen SDK.
  TIZEN=""
  for base in "${TIZEN_STUDIO:-}" "$HOME/tizen-studio" "$HOME/tizen-sdk" "/c/tizen-studio"; do
    for name in tizen tizen.bat; do
      if [ -n "$base" ] && [ -f "$base/tools/ide/bin/$name" ]; then TIZEN="$base/tools/ide/bin/$name"; break 2; fi
    done
  done
  [ -n "$TIZEN" ] || TIZEN="$(command -v tizen || true)"
  [ -n "$TIZEN" ] || { echo "Signing needs Samsung's Tizen tools (Tizen Studio's CLI). Not found: set TIZEN_STUDIO." >&2; exit 1; }
  signed="$(mktemp -d)"
  "$TIZEN" package -t wgt -s "$PROFILE" -o "$signed" -- "$WGT"
  made="$(ls "$signed"/*.wgt 2>/dev/null | head -1 || true)"
  [ -n "$made" ] || { echo "Samsung's tizen package didn't sign it." >&2; exit 1; }
  mv -f "$made" "$WGT"
  rm -rf "$signed"
  unzip -l "$WGT" | grep -q author-signature.xml || { echo "The package came back without its signatures." >&2; exit 1; }
fi

if $PREVIEW; then webapp_preview "$APP" out/tizen-preview; fi
SIGNED="not signed"; [ -n "$PROFILE" ] && SIGNED="signed with \"$PROFILE\""
echo "Built $WGT ($ID $VERSION, $(du -k "$WGT" | cut -f1) KB, $SIGNED)."
webapp_has_key "$APP" || echo "No TMDB key: the rows stay off until you add one (in config.properties, or on the TV in Settings)."
