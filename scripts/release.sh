#!/usr/bin/env bash
# Cuts a release: checks the repository is ready, runs the Maven release plugin (which commits the
# release version, tags it, and commits the next -SNAPSHOT), then pushes the branch and the tag.
# Pushing the tag is what deploys: .github/workflows/publish.yml publishes to Maven Central and
# creates the GitHub release from the version's CHANGELOG section. A published version cannot be
# pulled back, so every check below fails before anything is changed.
#
#   scripts/release.sh <release-version> [next-snapshot-version] [--yes]
#
#   scripts/release.sh 0.17.2            # next defaults to the next patch: 0.17.3-SNAPSHOT
#   scripts/release.sh 0.18.0 0.18.1-SNAPSHOT
#   scripts/release.sh 0.17.2 --yes      # skip the confirmation prompt
set -euo pipefail

YES=false
ARGS=()
for arg in "$@"; do
    if [[ "$arg" == "--yes" ]]; then
        YES=true
    else
        ARGS+=("$arg")
    fi
done

VERSION=${ARGS[0]:-}
if [[ ! "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "usage: scripts/release.sh <release-version, e.g. 0.17.2> [next-snapshot-version] [--yes]" >&2
    exit 2
fi
NEXT=${ARGS[1]:-${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))-SNAPSHOT}
TAG="v$VERSION"

fail() {
    echo "release: $1" >&2
    exit 1
}

cd "$(git rev-parse --show-toplevel)"

[[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] || fail "not on main"
[[ -z "$(git status --porcelain)" ]] || fail "working tree is not clean (untracked files count: the release plugin refuses them)"

git fetch --quiet origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || fail "main is not at origin/main (pull or push first)"

if git rev-parse --quiet --verify "refs/tags/$TAG" > /dev/null; then
    fail "tag $TAG already exists"
fi

grep -qE "^## \[$VERSION\] — " CHANGELOG.md || fail "CHANGELOG.md has no '## [$VERSION] — <date>' section"
grep -qE "<version>$VERSION-SNAPSHOT</version>" pom.xml || fail "pom.xml is not at $VERSION-SNAPSHOT"

# The deploy runs only the tag's build, with tests skipped: a red main would ship unnoticed.
if command -v gh > /dev/null; then
    CI=$(gh run list --branch main --commit "$(git rev-parse HEAD)" --workflow CI \
        --json status,conclusion --jq '.[0] | "\(.status) \(.conclusion)"' 2> /dev/null || true)
    [[ "$CI" == "completed success" ]] || fail "CI on $(git rev-parse --short HEAD) is not green (got: '${CI:-no run}')"
else
    echo "release: gh not found, skipping the CI check" >&2
fi

echo "About to release $VERSION (next development version $NEXT) from $(git rev-parse --short HEAD)."
echo "This pushes tag $TAG, which publishes to Maven Central."
if [[ "$YES" != true ]]; then
    read -r -p "Continue? [y/N] " reply
    [[ "$reply" == "y" || "$reply" == "Y" ]] || fail "aborted"
fi

./mvnw --batch-mode release:clean release:prepare \
    -DreleaseVersion="$VERSION" -DdevelopmentVersion="$NEXT"

git push origin main
git push origin "$TAG"

echo "Pushed $TAG. Follow the deploy with: gh run watch \$(gh run list --workflow 'Publish to Maven Central' --limit 1 --json databaseId --jq '.[0].databaseId')"
