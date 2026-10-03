#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/MCTier-Linux-Web/server/target/release/mctier-linux-web"
if [[ ! -x "$BIN" ]]; then
  "$ROOT/MCTier-Linux-Web/scripts/build-web-server.sh"
fi
printf 'Starting local service as the current user. Open http://127.0.0.1:14700 manually in Firefox or Chromium.\n'
exec "$BIN"
