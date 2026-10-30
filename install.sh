#!/usr/bin/env sh
# Installs a spin release, or a locally built spin image, and symlinks it onto PATH.
# Run with --help for usage.
#
# Everything runs from main() on the last line, so a truncated download of this script
# can't execute part of it.
set -e

SPIN_HOME="${SPIN_HOME:-$HOME/.spin}"
VERSIONS_DIR="$SPIN_HOME/versions"
CURRENT_LINK="$SPIN_HOME/current"
LINK_DIR="${SPIN_LINK_DIR:-$HOME/.local/bin}"
REPO="${SPIN_REPO:-Workday/spin.build}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in Darwin) HOST_OS=mac ;; Linux) HOST_OS=linux ;; *) HOST_OS=other ;; esac
case "$(uname -m)" in arm64|aarch64) HOST_ARCH=aarch64 ;; x86_64|amd64) HOST_ARCH=x86_64 ;; *) HOST_ARCH=other ;; esac

fail() {
    echo "Error: $*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Installs a spin release, or a locally built spin image, and symlinks it onto PATH.

Usage:
  curl -fsSL https://raw.githubusercontent.com/Workday/spin.build/main/install.sh | sh
  curl -fsSL https://raw.githubusercontent.com/Workday/spin.build/main/install.sh | sh -s -- 0.3.0

  ./install.sh               install the latest release
  ./install.sh 0.3.0         install release v0.3.0
  ./install.sh --local       install the image built in this checkout (as 'dev')
  ./install.sh --local DIR   install the spin image in DIR (as 'dev')
  ./install.sh --build       build this checkout (./mvnw package -DskipTests), then install it (as 'dev')
  ./install.sh --list        list cached versions and mark the active one
  ./install.sh --help        show this help

Every version ever installed is cached under $SPIN_HOME/versions/<tag>/, so switching back to
one you've already used is a symlink flip - no re-download.

Environment:
  SPIN_HOME      where versions are cached          (default: ~/.spin)
  SPIN_LINK_DIR  where the 'spin' link is created   (default: ~/.local/bin)
  SPIN_REPO      GitHub owner/name of the releases  (default: Workday/spin.build)
EOF
}

# -----------------------------------------------------------
# Point $CURRENT_LINK and PATH at an already-cached version dir
# -----------------------------------------------------------
activate() {
    mkdir -p "$LINK_DIR"
    # replacing a spin we didn't link (a package manager's, an older manual install) is allowed, but not silent
    if [ -e "$LINK_DIR/spin" ] || [ -L "$LINK_DIR/spin" ]; then
        case "$(readlink "$LINK_DIR/spin" 2>/dev/null)" in
            "$CURRENT_LINK"/*) ;;
            *) echo "Note: replacing existing $LINK_DIR/spin" ;;
        esac
    fi
    ln -sfn "$VERSIONS_DIR/$1" "$CURRENT_LINK"
    ln -sf "$CURRENT_LINK/bin/spin.sh" "$LINK_DIR/spin"
    chmod +x "$CURRENT_LINK/bin/spin.sh"

    echo "Active: $1"
    echo "Linked: $LINK_DIR/spin -> $CURRENT_LINK/bin/spin.sh"

    if ! echo ":$PATH:" | grep -q ":$LINK_DIR:"; then
        echo ""
        echo "Note: $LINK_DIR is not in your PATH. Add this to your shell profile:"
        echo "  export PATH=\"$LINK_DIR:\$PATH\""
    fi
}

# -----------------------------------------------------------
# List the cached versions, marking the active one
# -----------------------------------------------------------
list_versions() {
    ACTIVE=""
    if [ -L "$CURRENT_LINK" ]; then
        ACTIVE=$(basename "$(readlink "$CURRENT_LINK")")
    fi
    # the glob skips dot-directories, so an interrupted install's .install.* scratch space isn't listed
    for d in "$VERSIONS_DIR"/*/; do
        [ -d "$d" ] || continue
        v=$(basename "$d")
        if [ "$v" = "$ACTIVE" ]; then echo "* $v"; else echo "  $v"; fi
    done
}

# -----------------------------------------------------------
# Create scratch space inside the cache, so a finished image is moved into place within one file system
# -----------------------------------------------------------
make_scratch() {
    mkdir -p "$VERSIONS_DIR"
    TMP=$(mktemp -d "$VERSIONS_DIR/.install.XXXXXX")
    trap 'rm -rf "$TMP"' EXIT
    # some shells skip the EXIT trap when a signal ends the script; exiting from the signal trap runs it
    trap 'exit 1' INT TERM
}

# -----------------------------------------------------------
# Fetch over https only, including across redirects
# -----------------------------------------------------------
fetch() {
    curl --proto '=https' --proto-redir '=https' --tlsv1.2 "$@"
}

# -----------------------------------------------------------
# Whether $1 is X.Y.Z or vX.Y.Z; checked for stray characters first because grep matches any one line of many
# -----------------------------------------------------------
is_version() {
    case "$1" in *[!0-9.v]*) return 1 ;; esac
    printf '%s\n' "$1" | grep -Eq '^v?[0-9]+\.[0-9]+\.[0-9]+$'
}

# -----------------------------------------------------------
# Move the image directory $1 into the cache as tag $2, and activate it
# -----------------------------------------------------------
cache() {
    [ -f "$1/bin/spin.sh" ] || fail "$2 is not a spin image (it has no bin/spin.sh)"
    rm -rf "${VERSIONS_DIR:?}/${2:?}"
    mv "$1" "$VERSIONS_DIR/$2"
    activate "$2"
}

# -----------------------------------------------------------
# Install an image built from source, as 'dev'
# -----------------------------------------------------------
install_local() {
    IMAGE="$1"
    if [ -z "$IMAGE" ]; then
        IMAGE="$SCRIPT_DIR/spin/.build/spin-${HOST_OS}-${HOST_ARCH}"
    fi

    if [ ! -f "$IMAGE/bin/spin.sh" ]; then
        fail "no spin image found at $IMAGE (build one with: ./mvnw clean install)"
    fi

    make_scratch
    # copy the contents, not $IMAGE itself: cp -R would copy a symlinked $IMAGE as a link, and the rm below would reach through it
    mkdir "$TMP/image"
    cp -pR "$IMAGE/." "$TMP/image/"
    # an image writes this on its first run; a release doesn't ship one, so neither does a local install
    rm -f "$TMP/image/app.jsa"

    cache "$TMP/image" dev
}

# -----------------------------------------------------------
# Build this checkout, so the image --local installs is current
# -----------------------------------------------------------
build_local() {
    echo "Building spin..."
    (cd "$SCRIPT_DIR" && ./mvnw package -DskipTests)
}

# -----------------------------------------------------------
# Download (or reuse a cached) release from GitHub
# -----------------------------------------------------------
install_release() {
    case "$1" in
        dev|latest) ;;
        *) is_version "$1" || fail "invalid version '$1' (expected dev, latest, X.Y.Z or vX.Y.Z; pre-release tags are not supported)" ;;
    esac

    # a cached name as typed ('dev', 'v0.4.0') needs no tag handling and no network; 'latest' always resolves
    if [ "$1" != latest ] && [ -d "$VERSIONS_DIR/$1" ]; then
        echo "Using cached $1 (already downloaded)"
        activate "$1"
        return
    fi

    [ "$1" != dev ] || fail "no dev version is cached (install one with: ./install.sh --local)"

    command -v curl >/dev/null 2>&1 || fail "curl is required to download a release"

    VERSION="${1#v}"
    if [ "$VERSION" = "latest" ]; then
        # /releases/latest redirects to /releases/tag/<tag>
        TAG=$(fetch -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest") \
            || fail "failed to resolve the latest release for $REPO"
        TAG="${TAG##*/}"
        case "$TAG" in v*) ;; *) fail "$REPO has no releases" ;; esac
    else
        TAG="v${VERSION}"
    fi

    # release assets use a different os/arch naming scheme than the local build's image dirs
    case "${HOST_OS}/${HOST_ARCH}" in
        linux/x86_64)  ASSET="spin-linux-amd64.tar.gz" ;;
        linux/aarch64) ASSET="spin-linux-arm64.tar.gz" ;;
        mac/aarch64)   ASSET="spin-macos-arm64.tar.gz" ;;
        *) fail "no spin release is published for $(uname -s)/$(uname -m)" ;;
    esac

    if [ -d "$VERSIONS_DIR/$TAG" ]; then
        echo "Using cached $TAG (already downloaded)"
        activate "$TAG"
        return
    fi

    URL="https://github.com/$REPO/releases/download/$TAG"

    make_scratch
    echo "Downloading $ASSET from $REPO@$TAG..."
    fetch -fsSL -o "$TMP/$ASSET" "$URL/$ASSET" || fail "failed to download $ASSET for release $TAG from $REPO"

    fetch -fsSL -o "$TMP/SHA256SUMS" "$URL/SHA256SUMS" || fail "failed to download SHA256SUMS for release $TAG from $REPO"
    if command -v sha256sum >/dev/null 2>&1; then
        CHECKSUM_CMD="sha256sum -c -"
    elif command -v shasum >/dev/null 2>&1; then
        CHECKSUM_CMD="shasum -a 256 -c -"
    else
        fail "neither sha256sum nor shasum found; cannot verify checksum for $ASSET"
    fi
    (cd "$TMP" && grep " $ASSET\$" SHA256SUMS | $CHECKSUM_CMD) || fail "checksum verification failed for $ASSET"

    mkdir "$TMP/extracted"
    tar xzf "$TMP/$ASSET" -C "$TMP/extracted"
    IMAGE=$(find "$TMP/extracted" -maxdepth 1 -mindepth 1 -type d | head -1)
    [ -n "$IMAGE" ] || fail "no image found in $ASSET"

    cache "$IMAGE" "$TAG"
}

main() {
    case "${1:-latest}" in
        -h|--help) usage ;;
        --list) list_versions ;;
        --local) install_local "${2:-}" ;;
        --build) build_local; install_local "" ;;
        -*) fail "unknown option $1 (see --help)" ;;
        *) install_release "${1:-latest}" ;;
    esac
}

main "$@"
