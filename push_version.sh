#!/usr/bin/env bash
set -euo pipefail

# ADR-CI-18: releases are cut as annotated git tags (v<version>), not branch pushes.
# build-and-deploy.yml triggers on `push: tags: ['v*.*.*']`; pushing the tag this script
# creates starts that privileged, secret-bearing pipeline (build -> e2e -> security scans
# -> publish-image -> deploy). Because that is an irreversible production action, this
# script deliberately only bumps the version files and creates the tag LOCALLY -- it never
# pushes anything itself. Review the diff, commit, then push the branch and the tag
# explicitly when you are ready to release.

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <new-version>   (e.g. $0 0.0.9)" >&2
  echo "Bumps pom.xml/frontend/package.json/README.md to <new-version>, commits the bump," >&2
  echo "and creates an annotated tag v<new-version> locally. Does NOT push anything." >&2
  exit 1
fi

VERSION="$1"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must be of the form MAJOR.MINOR.PATCH (e.g. 0.0.9), got '${VERSION}'" >&2
  exit 1
fi
TAG="v${VERSION}"

dir=$(pwd)

cd "$dir"
sed -e "s|^    <version>[0-9]*\.[0-9]*\.[0-9]*</version>$|    <version>$VERSION</version>|" -i pom.xml
sed -e "s|<glacier.version>[0-9]*\.[0-9]*\.[0-9]*</glacier.version>|<glacier.version>$VERSION</glacier.version>|" -i pom.xml
sed -e "s|glacier-[0-9]*\.[0-9]*\.[0-9]*\.jar|glacier-$VERSION.jar|" -i README.md

cd "$dir/frontend"
sed -e "s|^  \"version\": \"[0-9]*\.[0-9]*\.[0-9]*\"|  \"version\": \"$VERSION\"|" -i package.json
npm install

cd "$dir"
git add pom.xml README.md frontend/package.json frontend/package-lock.json
git commit -m "chore(release): bump version to ${VERSION}"
git tag -a "${TAG}" -m "Release ${TAG}"

cat <<EOF

Version bumped to ${VERSION} and tag ${TAG} created locally (nothing pushed yet).

Review the commit, then push explicitly to start the release:
  git push origin HEAD
  git push origin ${TAG}

Pushing the tag triggers build-and-deploy.yml, which deploys to production after the
full e2e + security-scan gate passes -- push it only when you are ready to release.
EOF
