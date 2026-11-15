#!/usr/bin/env bash
# Builds a signed release APK and publishes it as a GitHub release.
#
#   1. Bump gatedelay.versionName / gatedelay.versionCode in gradle.properties
#   2. Add a "## X.Y.Z" section to CHANGELOG.md, commit, push
#   3. bash tools/release.sh
#
# Needs: keystore.properties (git-ignored) pointing at the release keystore, JAVA_HOME set,
# and a GitHub token in $GITHUB_TOKEN (or the one git already uses for github.com).
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="Moran296/GateDelay"
VERSION=$(sed -n 's/^gatedelay.versionName=//p' gradle.properties | tr -d '\r')
TAG="v$VERSION"
APK="dist/GateDelay-$VERSION.apk"

[ -f keystore.properties ] || { echo "keystore.properties missing: release must be signed with the release key"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean; commit first"; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Tag $TAG already exists; bump the version"; exit 1; }

# Release notes = the CHANGELOG section for this version
NOTES=$(awk -v v="## $VERSION" '$0==v{f=1;next} /^## /{f=0} f' CHANGELOG.md)
[ -n "$NOTES" ] || { echo "No '## $VERSION' section in CHANGELOG.md"; exit 1; }

./gradlew -q :shared:jvmTest :composeApp:assembleRelease
mkdir -p dist
cp composeApp/build/outputs/apk/release/composeApp-release.apk "$APK"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

TOKEN=${GITHUB_TOKEN:-$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')}
[ -n "$TOKEN" ] || { echo "No GitHub token"; exit 1; }

git tag -a "$TAG" -m "GateDelay $VERSION"
git push origin "$TAG"

BODY=$(python -c 'import json,sys; print(json.dumps({"tag_name":sys.argv[1],"name":"GateDelay "+sys.argv[2],
  "body":sys.argv[3]+"\n\n**Install:** download `"+sys.argv[4]+"` below and open it on your Android phone.\n\nSHA-256: `"+sys.argv[5]+"`"}))' \
  "$TAG" "$VERSION" "$NOTES" "$(basename "$APK")" "$SHA")
UPLOAD_URL=$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/releases" -d "$BODY" | python -c 'import json,sys; print(json.load(sys.stdin)["upload_url"].split("{")[0])')
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" "$UPLOAD_URL?name=$(basename "$APK")" >/dev/null

echo "Released $TAG: https://github.com/$REPO/releases/tag/$TAG"
