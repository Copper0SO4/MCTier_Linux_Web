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
PACKAGE="$OUTPUT/mctier-web_${VERSION}-1_amd64.deb"
[[ ! -e "$PACKAGE" ]] || { echo "Refusing to overwrite $PACKAGE" >&2; exit 1; }
for tool in dpkg-deb dpkg-shlibdeps sha256sum cargo npm node; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
CORE=MCTier-Linux-Web/resources/binaries/easytier-core
CLI=MCTier-Linux-Web/resources/binaries/easytier-cli
printf '%s  %s\n%s  %s\n' f1bd60be7a50da84f50732ed4b826b70284c84f05dadbd3fe448429dfe184322 "$CORE" e339aea31943f0c5ced2a5a6ecdd675da3bb25843cf847107744e656f8200838 "$CLI" | sha256sum -c -
unset CARGO_ENCODED_RUSTFLAGS
export RUSTFLAGS='-C target-cpu=x86-64'
export CFLAGS='-O2 -march=x86-64 -mtune=generic'
export CXXFLAGS="$CFLAGS"
./MCTier-Linux-Web/scripts/build-web-server.sh
STAGE="$(mktemp -d)"
trap 'rm -rf -- "$STAGE"' EXIT
PKG="$STAGE/pkg"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin" "$PKG/usr/lib/mctier-web/binaries" "$PKG/usr/share/doc/mctier-web" "$STAGE/debian"
install -m 0755 MCTier-Linux-Web/packaging/mctier-web "$PKG/usr/bin/mctier-web"
install -m 0755 MCTier-Linux-Web/server/target/release/mctier-linux-web "$PKG/usr/lib/mctier-web/mctier-linux-web-service"
install -m 0755 "$CORE" "$PKG/usr/lib/mctier-web/binaries/easytier-core"
install -m 0755 "$CLI" "$PKG/usr/lib/mctier-web/binaries/easytier-cli"
install -m 0644 LICENSE "$PKG/usr/share/doc/mctier-web/copyright"
install -m 0644 MCTier-Linux-Web/packaging/EasyTier-LICENSE "$PKG/usr/share/doc/mctier-web/EasyTier-LICENSE"
install -m 0644 MCTier-Linux-Web/packaging/README-Debian.txt "$PKG/usr/share/doc/mctier-web/README.Debian"
install -m 0644 MCTier-Linux-Web/UFW-P2P-TROUBLESHOOTING.md "$PKG/usr/share/doc/mctier-web/"
cat > "$STAGE/debian/control" <<CONTROL
Source: mctier-web
Section: net
Priority: optional
Maintainer: MCTier Linux Web maintainers
Standards-Version: 4.7.0

Package: mctier-web
Architecture: amd64
Description: MCTier local web service
CONTROL
SHLIBS="$(cd "$STAGE" && dpkg-shlibdeps -O -e pkg/usr/lib/mctier-web/mctier-linux-web-service)"
DEPENDS="${SHLIBS#shlibs:Depends=}"
[[ -n "$DEPENDS" && "$DEPENDS" != "$SHLIBS" ]] || { echo 'Unable to determine shared library dependencies' >&2; exit 1; }
SIZE="$(du -sk "$PKG/usr" | cut -f1)"
cat > "$PKG/DEBIAN/control" <<CONTROL
Package: mctier-web
Version: ${VERSION}-1
Architecture: amd64
Maintainer: MCTier Linux Web maintainers
Section: net
Priority: optional
Installed-Size: $SIZE
Depends: $DEPENDS, libcap2-bin, pkexec
Description: Local browser interface for MCTier on Linux
 Experimental loopback web service with bundled EasyTier core and CLI.
 Built on Debian 13 for amd64; start manually in a terminal.
CONTROL
dpkg-deb --build --root-owner-group "$PKG" "$PACKAGE"
sha256sum "$PACKAGE"
