#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
[[ "${1:-}" == "" || "${1:-}" == "--merge" ]] || { echo "Usage: $0 [--merge]" >&2; exit 2; }
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
if [[ "${1:-}" == "--merge" && -n "$(git status --porcelain)" ]]; then
  echo "Commit or preserve your working changes before merging upstream. No files were changed." >&2; exit 1
fi
effective="$(git "${git_args[@]}" remote get-url upstream)"
[[ "$effective" == "$url" ]] || { echo "Git rewrote upstream to $effective; refusing an implicit proxy." >&2; exit 1; }
printf 'Fetching official upstream: %s\n' "$effective"
git "${git_args[@]}" fetch upstream refs/heads/master:refs/remotes/upstream/master
printf 'Official upstream changes relative to current HEAD:\n'
git log --oneline HEAD..upstream/master
if [[ "${1:-}" == "--merge" ]]; then
  git merge --no-edit upstream/master
  printf 'Merged upstream. Recheck Linux patches and shared Rust protocols, then run build and tests.\n'
else
  printf 'Review changes with git diff HEAD...upstream/master. Run this script with --merge to integrate.\n'
fi
