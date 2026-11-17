#!/usr/bin/env bash
# Start a new release line, e.g. ./push_version.sh 0.1
# Patch versions are not set here: the nightly build in
# .github/workflows/build-and-deploy.yml derives them from the git tags.
set -euo pipefail

if [[ ! "${1:-}" =~ ^[0-9]+\.[0-9]+$ ]]; then
  echo "usage: $0 <major>.<minor>" >&2
  exit 1
fi

cd "$(dirname "$0")"
sed -e "s|<revision>[0-9]*\.[0-9]*-SNAPSHOT</revision>|<revision>$1-SNAPSHOT</revision>|" -i pom.xml
