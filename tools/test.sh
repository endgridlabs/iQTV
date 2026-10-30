#!/usr/bin/env bash
# Runs the tests in tests/ on this computer (no TV, no Android SDK): the plain logic the app's classes share (Lists).
# Needs a JDK (17+), the same one build.sh uses.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$JAVAC" -nowarn -encoding UTF-8 -d "$out" app/src/com/endgrid/iqtv/Lists.java tests/*.java
status=0
for t in tests/*.java; do
  name="com.endgrid.iqtv.$(basename "$t" .java)"
  echo "== $name"
  "$JAVA" -cp "$out" "$name" || status=1
done
exit $status
