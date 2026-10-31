#!/usr/bin/env bash
set -Eeuo pipefail
umask 022
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
OUTPUT="${1:-$ROOT/MCTier-Linux-Web/dist}"
mkdir -p -- "$OUTPUT"
OUTPUT="$(cd -- "$OUTPUT" && pwd)"
VERSION="$(awk -F '"' '/^version = / {print $2; exit}' MCTier-Linux-Web/server/Cargo.toml)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid version' >&2; exit 1; }
[[ "$(dpkg --print-architecture)" == amd64 && "$(uname -m)" == x86_64 ]] || { echo 'Debian amd64 required' >&2; exit 1; }
NAME="mctier-web_${VERSION}-1_linux-amd64"
ARCHIVE="$OUTPUT/$NAME.tar.gz"
[[ ! -e "$ARCHIVE" ]] || { echo "Refusing to overwrite $ARCHIVE" >&2; exit 1; }
CORE=MCTier-Linux-Web/resources/binaries/easytier-core
CLI=MCTier-Linux-Web/resources/binaries/easytier-cli
printf '%s  %s\n%s  %s\n' f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322 "$CORE" e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838 "$CLI" | sha256sum -c -
SERVICE=MCTier-Linux-Web/server/target/release/mctier-linux-web
[[ -x "$SERVICE" ]] || { echo 'Build release service first using package-deb.sh' >&2; exit 1; }
STAGE="$(mktemp -d)"
trap 'rm -rf -- "$STAGE"' EXIT
APP="$STAGE/$NAME"
mkdir -p "$APP/bin/binaries"
# Generate from the Debian launcher, keeping the security checks identical.
sed -e 's|^SERVICE=/usr/lib/mctier-web/mctier-linux-web-service$|APP_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" \&\& pwd -P)"\nSERVICE="$APP_DIR/bin/mctier-linux-web-service"|' \
    -e 's|^CORE=/usr/lib/mctier-web/binaries/easytier-core$|CORE="$APP_DIR/bin/binaries/easytier-core"|' \
    MCTier-Linux-Web/packaging/mctier-web > "$APP/mctier-web"
chmod 0755 "$APP/mctier-web"
install -m 0755 "$SERVICE" "$APP/bin/mctier-linux-web-service"
install -m 0755 "$CORE" "$APP/bin/binaries/easytier-core"
install -m 0755 "$CLI" "$APP/bin/binaries/easytier-cli"
install -m 0644 LICENSE "$APP/LICENSE"
install -m 0644 MCTier-Linux-Web/packaging/EasyTier-LICENSE "$APP/EasyTier-LICENSE"
install -m 0644 MCTier-Linux-Web/packaging/README-Portable.txt "$APP/README.txt"
install -m 0644 MCTier-Linux-Web/UFW-P2P-TROUBLESHOOTING.md "$APP/UFW-P2P-TROUBLESHOOTING.md"
tar -C "$STAGE" -czf "$ARCHIVE" "$NAME"
sha256sum "$ARCHIVE"
