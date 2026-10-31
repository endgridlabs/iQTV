#!/usr/bin/env bash
# One release, start to finish: tools/release.sh 2.0.5 "what's new, in a few words"
#  1. sets the version (versionName, versionCode + 1) and turns CHANGELOG.md's "## Unreleased" into "## 2.0.5 — title";
#  2. runs the tests and builds the shareable APK (./build.sh --public: config.example.properties, no keys);
#  3. checks that none of your keys (config.properties' *_key values) is inside it;
#  4. commits, tags v2.0.5, pushes main and the tag, and publishes the GitHub release with the APK (gh, signed in).
# Needs a clean working tree on main, an "## Unreleased" section in CHANGELOG.md and `gh auth login` done once.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-}"
TITLE="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && -n "$TITLE" ]] || { echo "Usage: tools/release.sh 2.0.5 \"what's new\""; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "Release from main."; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first."; exit 1; }
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && { echo "v$VERSION already exists."; exit 1; }
grep -q '^## Unreleased$' CHANGELOG.md || { echo "CHANGELOG.md has no \"## Unreleased\" section to release."; exit 1; }
command -v gh >/dev/null && gh auth status >/dev/null 2>&1 || { echo "Run gh auth login first."; exit 1; }

# 1. Version and changelog
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' app/AndroidManifest.xml | head -1)
sed -i "s/android:versionCode=\"$CODE\"/android:versionCode=\"$((CODE + 1))\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION\"/" app/AndroidManifest.xml
sed -i "0,/^## Unreleased\$/s//## $VERSION — $TITLE/" CHANGELOG.md

# 2. Tests and the shareable build
bash tools/test.sh
./build.sh --public
APK="release/iQTV-$VERSION.apk"
[ -f "$APK" ] || { echo "No $APK."; exit 1; }

# 3. No key of yours inside
if [ -f config.properties ]; then
  while IFS='=' read -r name value; do
    value="$(printf '%s' "$value" | tr -d '\r "\\' )"
    [ -n "$value" ] || continue
    if unzip -p "$APK" 'classes*.dex' | grep -aqF "$value"; then
      echo "Your $name is inside $APK: not releasing."; git checkout -- app/AndroidManifest.xml CHANGELOG.md; exit 1
    fi
  done < <(grep -E '^[[:space:]]*[a-z_]*_key[[:space:]]*=' config.properties)
fi
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

# 4. Commit, tag, push, publish
git add app/AndroidManifest.xml CHANGELOG.md
git commit -q -m "EndGrid iQTV+ $VERSION"
git tag -a "v$VERSION" -m "EndGrid iQTV+ $VERSION"
git push -q origin main
git push -q origin "v$VERSION"
NOTES=$(mktemp)
{
  awk -v v="## $VERSION" 'index($0, v) == 1 {f = 1; next} /^## / {f = 0} f' CHANGELOG.md
  printf '## Install\n\n'
  printf '**From 2.x:** install `iQTV-%s.apk` below over it (or Settings › Personalize › About › Updates on the TV); your settings stay.\n\n' "$VERSION"
  printf '**From 1.x (`io.freemytv`):** in the old app, Settings › Home Screen › **Original**, then uninstall it; install this one and choose **EndGrid iQTV+** under Home Screen (accept "Allow USB debugging", with "Always allow").\n\n'
  printf 'The APK contains no API keys: add your own free keys in Settings › Personalize › Recommendations (TMDB, Gemini, Watchmode, OMDb).\n\n'
  printf 'SHA-256: `%s`\n' "$SHA"
} > "$NOTES"
gh release create "v$VERSION" "$APK" --title "EndGrid iQTV+ $VERSION — $TITLE" --notes-file "$NOTES" --latest
rm -f "$NOTES"
echo "Released v$VERSION: $(gh release view "v$VERSION" --json url --jq .url)"
