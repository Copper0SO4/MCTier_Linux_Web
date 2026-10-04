#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE=debug
BUILD_ARGS=(--debug)
case "${1:-}" in
  "") ;;
  --debug) ;;
  --release) PROFILE=release; BUILD_ARGS=() ;;
  *) echo "Usage: $0 [--debug|--release]" >&2; exit 2 ;;
esac
BIN="$ROOT/MCTier-Linux-Web/server/target/$PROFILE/mctier-linux-web"
# Source development must embed the current frontend and backend, even when a binary already exists.
"$ROOT/MCTier-Linux-Web/scripts/build-web-server.sh" "${BUILD_ARGS[@]}"
printf 'Starting local service as the current user. Open http://127.0.0.1:14700 manually in Firefox or Chromium.\n'
exec "$BIN"
