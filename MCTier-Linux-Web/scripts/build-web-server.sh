#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
node MCTier-Linux-Web/scripts/prepare-frontend.mjs
npx tsc --project MCTier-Linux-Web/tsconfig.web.json
npx vite build --config MCTier-Linux-Web/vite.config.ts
node MCTier-Linux-Web/scripts/prepare-emoji.mjs
cargo build --release --locked --manifest-path "$ROOT/MCTier-Linux-Web/server/Cargo.toml"
printf 'Built %s\n' "$ROOT/MCTier-Linux-Web/server/target/release/mctier-linux-web"
