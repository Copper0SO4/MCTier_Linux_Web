#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
VERSION="${1:-0.1.0}"
[[ "$VERSION" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo "Usage: $0 VERSION" >&2; exit 2; }
for tool in node npm cargo zip sha256sum; do command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }; done
# Refuse a mislabeled or overwritten archive before doing expensive build work.
RELEASE_VERSION="$(awk -F '"' '/^version = / {print $2; exit}' MCTier-Linux-Web/server/Cargo.toml)"
[[ "${VERSION#v}" == "$RELEASE_VERSION" ]] || { echo "Archive version must match Cargo version $RELEASE_VERSION" >&2; exit 1; }
DIST="$ROOT/MCTier-Linux-Web/dist"
NAME="mctier-linux-web-linux-x86_64-${VERSION}"
ARCHIVE="$DIST/${NAME}.zip"
[[ ! -e "$ARCHIVE" && ! -e "$ARCHIVE.sha256" ]] || { echo "Refusing to overwrite existing release files: $ARCHIVE" >&2; exit 1; }
# Published binaries must not inherit native CPU flags from this build machine.
# CARGO_ENCODED_RUSTFLAGS has higher precedence than RUSTFLAGS.
unset CARGO_ENCODED_RUSTFLAGS
export RUSTFLAGS="-C target-cpu=x86-64"
export CFLAGS="-O2 -march=x86-64 -mtune=generic"
export CXXFLAGS="$CFLAGS"
printf 'Portable release target: Rust x86-64; C/C++ x86-64, generic tuning\n'
./MCTier-Linux-Web/scripts/build-web-server.sh
CORE="$ROOT/MCTier-Linux-Web/resources/binaries/easytier-core"
CLI="$ROOT/MCTier-Linux-Web/resources/binaries/easytier-cli"
[[ -x "$CORE" && -x "$CLI" ]] || { echo "Run scripts/fetch-binaries.sh first" >&2; exit 1; }
[[ "$(sha256sum "$CORE" | cut -d' ' -f1)" == f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322 ]]
[[ "$(sha256sum "$CLI" | cut -d' ' -f1)" == e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838 ]]
STAGE="$(mktemp -d)"
trap 'rm -rf -- "$STAGE"' EXIT
APP="$STAGE/$NAME"
mkdir -p "$APP/bin/binaries"
install -m 0755 MCTier-Linux-Web/scripts/launch-linux-web.sh "$APP/mctier-linux-web"
install -m 0755 MCTier-Linux-Web/server/target/release/mctier-linux-web "$APP/bin/mctier-linux-web-service"
install -m 0755 "$CORE" "$APP/bin/binaries/easytier-core"
install -m 0755 "$CLI" "$APP/bin/binaries/easytier-cli"
install -m 0644 LICENSE "$APP/LICENSE"
install -m 0644 MCTier-Linux-Web/packaging/EasyTier-LICENSE "$APP/EasyTier-LICENSE"
install -m 0644 MCTier-Linux-Web/packaging/README-Linux.txt "$APP/README-Linux.txt"
install -m 0644 MCTier-Linux-Web/ADVANCED-NETWORK.md "$APP/ADVANCED-NETWORK.md"
install -m 0644 MCTier-Linux-Web/UFW-P2P-TROUBLESHOOTING.md "$APP/UFW-P2P-TROUBLESHOOTING.md"
mkdir -p "$DIST"
[[ ! -e "$ARCHIVE" && ! -e "$ARCHIVE.sha256" ]] || { echo "Refusing to overwrite existing archive: $ARCHIVE" >&2; exit 1; }
(cd "$STAGE" && zip -qr -9 "$ARCHIVE" "$NAME")
(cd "$DIST" && sha256sum "${NAME}.zip" > "${NAME}.zip.sha256")
printf 'Release archive: %s\n' "$ARCHIVE"
cat "$ARCHIVE.sha256"
