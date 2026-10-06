#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
merge=false
stable=false
for option in "$@"; do
  case "$option" in
    --merge) merge=true ;;
    --stable) stable=true ;;
    *) echo "Usage: $0 [--stable] [--merge]" >&2; exit 2 ;;
  esac
done
url="$(git config --get remote.upstream.url)"
official="https://github.com/pmh1314520/MCTier.git"
git_args=()
if [[ "$url" == "$official" ]]; then
  # An exact, command-local rewrite defeats broader user-configured proxy rewrites.
  git_args=(-c "url.$official.insteadOf=$official")
fi
case "$url" in
  https://github.com/pmh1314520/MCTier.git|git@github.com:pmh1314520/MCTier.git) ;;
  *) echo "upstream must point directly to official pmh1314520/MCTier. Current: $url" >&2; exit 1 ;;
esac
if [[ "$merge" == true && -n "$(git status --porcelain)" ]]; then
  echo "Commit or preserve your working changes before merging upstream. No files were changed." >&2; exit 1
fi
effective="$(git "${git_args[@]}" remote get-url upstream)"
[[ "$effective" == "$url" ]] || { echo "Git rewrote upstream to $effective; refusing an implicit proxy." >&2; exit 1; }
printf 'Fetching official upstream: %s\n' "$effective"
ref=upstream/master
if [[ "$stable" == true ]]; then
  metadata="$(mktemp)"
  trap 'rm -f -- "$metadata"' EXIT
  curl --fail --silent --show-error --location https://api.github.com/repos/pmh1314520/MCTier/releases/latest -o "$metadata"
  tag="$(node -e 'const d=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")); if(d.draft || d.prerelease || !/^v?[0-9]+\.[0-9]+\.[0-9]+$/.test(d.tag_name)) throw Error("Invalid stable release metadata"); process.stdout.write(d.tag_name)' "$metadata")"
  git "${git_args[@]}" fetch upstream "refs/tags/$tag:refs/tags/$tag"
  ref="refs/tags/$tag"
  printf 'Official stable release: %s (%s)\n' "$tag" "$(git rev-parse "$ref^{commit}")"
else
  git "${git_args[@]}" fetch upstream refs/heads/master:refs/remotes/upstream/master
fi
printf 'Official upstream changes relative to current HEAD:\n'
git log --oneline "HEAD..$ref"
if [[ "$merge" == true ]]; then
  git merge --no-edit "$ref"
  printf 'Merged upstream. Recheck Linux patches and shared Rust protocols, then run build and tests.\n'
else
  printf 'Review changes with git diff HEAD...%s. Add --merge after preserving working changes.\n' "$ref"
fi
