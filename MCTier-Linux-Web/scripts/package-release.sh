#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
VERSION="${1:-0.1.0}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo "Usage: $0 VERSION" >&2; exit 2; }
for tool in node npm cargo zip sha256sum; do command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }; done
./MCTier-Linux-Web/scripts/build-web-server.sh
CORE="$ROOT/MCTier-Linux-Web/resources/binaries/easytier-core"
CLI="$ROOT/MCTier-Linux-Web/resources/binaries/easytier-cli"
[[ -x "$CORE" && -x "$CLI" ]] || { echo "Run scripts/fetch-binaries.sh first" >&2; exit 1; }
[[ "$(sha256sum "$CORE" | cut -d' ' -f1)" == f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322 ]]
[[ "$(sha256sum "$CLI" | cut -d' ' -f1)" == e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838 ]]
STAGE="$(mktemp -d)"
trap 'rm -rf -- "$STAGE"' EXIT
NAME="mctier-linux-web-linux-x86_64-${VERSION}"
APP="$STAGE/$NAME"
mkdir -p "$APP/bin/binaries"
install -m 0755 MCTier-Linux-Web/scripts/launch-linux-web.sh "$APP/mctier-linux-web"
install -m 0755 MCTier-Linux-Web/server/target/release/mctier-linux-web "$APP/bin/mctier-linux-web-service"
install -m 0755 "$CORE" "$APP/bin/binaries/easytier-core"
install -m 0755 "$CLI" "$APP/bin/binaries/easytier-cli"
install -m 0644 MCTier-Linux-Web/packaging/README-Linux.txt "$APP/README-Linux.txt"
DIST="$ROOT/MCTier-Linux-Web/dist"
mkdir -p "$DIST"
ARCHIVE="$DIST/${NAME}.zip"
[[ ! -e "$ARCHIVE" ]] || { echo "Refusing to overwrite existing archive: $ARCHIVE" >&2; exit 1; }
(cd "$STAGE" && zip -qr -9 "$ARCHIVE" "$NAME")
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
printf 'Release archive: %s\n' "$ARCHIVE"
cat "$ARCHIVE.sha256"
