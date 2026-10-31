#!/usr/bin/env bash
# Forward pact check (specs/20-testing-strategy.md §10.3, specs/00-engineering-baseline.md §14 steps 6 and 7): the
# providers of the last release verify this commit's pacts, so new consumers that start before their providers have
# finished rolling keep working.
#
# Needs this commit's pacts in target/pacts (./mvnw package) and the release tags (fetch-depth: 0). The last release
# is the newest v<major>.<minor>.<patch> tag, as the release job creates them; PACT_FORWARD_TAG names another tag,
# e.g. a test tag.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
pacts="$root/target/pacts"
tag=${PACT_FORWARD_TAG:-$(git -C "$root" tag --list 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n 1 || true)}

if [ -z "$tag" ]; then
  echo "Forward pact check: no release tag yet, nothing to verify"
  exit 0
fi
if ! git -C "$root" rev-parse --quiet --verify "refs/tags/$tag^{commit}" > /dev/null; then
  echo "Forward pact check: no tag $tag" >&2
  exit 1
fi
if ! compgen -G "$pacts/*.json" > /dev/null; then
  echo "Forward pact check: no pacts in $pacts; build this commit first (./mvnw package)" >&2
  exit 1
fi

release=$(mktemp -d)
cleanup() {
  git -C "$root" worktree remove --force "$release" > /dev/null 2>&1 || true
  rm -rf "$release"
  git -C "$root" worktree prune
}
trap cleanup EXIT
git -C "$root" worktree add --quiet --detach "$release" "$tag"

echo "Forward pact check: the providers of $tag verify the pacts of $(git -C "$root" rev-parse --short HEAD)"
# only this commit's pacts: the release's own released pacts are not part of this check
(cd "$release" && ./mvnw -B -Ppact-verify verify "-Dpact.collected.dir=$pacts" -Dpact.released.skip=true)
echo "Forward pact check: passed against $tag"
