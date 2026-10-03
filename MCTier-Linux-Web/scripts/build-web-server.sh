#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
PROFILE=release
case "${1:-}" in
  "") ;;
  --debug) PROFILE=debug ;;
  *) echo "Usage: $0 [--debug]" >&2; exit 2 ;;
esac
node MCTier-Linux-Web/scripts/prepare-frontend.mjs
node MCTier-Linux-Web/scripts/prepare-secrets.mjs
npx tsc --project MCTier-Linux-Web/tsconfig.web.json
npx vite build --config MCTier-Linux-Web/vite.config.ts
if [[ "$PROFILE" == debug ]]; then
  cargo build --locked --manifest-path "$ROOT/MCTier-Linux-Web/server/Cargo.toml"
else
  cargo build --release --locked --manifest-path "$ROOT/MCTier-Linux-Web/server/Cargo.toml"
fi
printf 'Built %s\n' "$ROOT/MCTier-Linux-Web/server/target/$PROFILE/mctier-linux-web"
