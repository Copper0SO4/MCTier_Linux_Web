#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
node MCTier-Linux-Web/scripts/prepare-frontend.mjs
node MCTier-Linux-Web/scripts/prepare-secrets.mjs
node --test MCTier-Linux-Web/tests/*.test.mjs
cargo test --locked --offline --manifest-path MCTier-Linux-Web/server/Cargo.toml
