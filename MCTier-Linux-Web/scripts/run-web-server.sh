#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE=release
BUILD_ARGS=()
case "${1:-}" in
  "") ;;
  --debug) PROFILE=debug; BUILD_ARGS=(--debug) ;;
  *) echo "Usage: $0 [--debug]" >&2; exit 2 ;;
esac
BIN="$ROOT/MCTier-Linux-Web/server/target/$PROFILE/mctier-linux-web"
if [[ ! -x "$BIN" ]]; then
  "$ROOT/MCTier-Linux-Web/scripts/build-web-server.sh" "${BUILD_ARGS[@]}"
fi
printf 'Starting local service as the current user. Open http://127.0.0.1:14700 manually in Firefox or Chromium.\n'
exec "$BIN"
