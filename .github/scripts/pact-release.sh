#!/usr/bin/env bash
# Releases this commit's pacts (specs/20-testing-strategy.md §10.3, specs/00-engineering-baseline.md §14 step 7):
# refuses unless the forward pact check is green, then copies target/pacts to pacts/released/<version>/, commits them
# and tags the commit v<version>. Pushes the commit and the tag unless PACT_RELEASE_PUSH=false.
#
# Usage: pact-release.sh <major>.<minor>.<patch>   (after ./mvnw package, with the release tags fetched)
set -euo pipefail

version=${1:-}
if ! [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 <major>.<minor>.<patch>" >&2
  exit 2
fi

root=$(git rev-parse --show-toplevel)
pacts="$root/target/pacts"
released="$root/pacts/released/$version"

if git -C "$root" rev-parse --quiet --verify "refs/tags/v$version" > /dev/null; then
  echo "Pact release: tag v$version exists already" >&2
  exit 1
fi
if [ -e "$released" ]; then
  echo "Pact release: $released exists already" >&2
  exit 1
fi
# before the first release the forward check has nothing to verify and does not look at target/pacts
if ! compgen -G "$pacts/*.json" > /dev/null; then
  echo "Pact release: no pacts in $pacts; build this commit first (./mvnw package)" >&2
  exit 1
fi
if ! "$root/.github/scripts/pact-forward-check.sh"; then
  echo "Pact release: the forward pact check failed, v$version is not released" >&2
  exit 1
fi

mkdir -p "$released"
cp "$pacts"/*.json "$released/"
git -C "$root" add "pacts/released/$version"
git -C "$root" commit --quiet -m "chore(release): pacts of $version"
git -C "$root" tag "v$version"
echo "Pact release: pacts of $version committed in pacts/released/$version and tagged v$version"

if [ "${PACT_RELEASE_PUSH:-true}" != "false" ]; then
  # both or neither: a tag without its pacts commit would be a release the backward check cannot see
  git -C "$root" push --atomic origin HEAD "refs/tags/v$version"
fi
